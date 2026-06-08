package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.billing.invoices.SalesInvoice;
import com.benjagest.backend.billing.invoices.SalesInvoiceRepository;
import com.benjagest.backend.billing.invoices.SalesJournalEntryService;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.purchases.PurchaseInvoice;
import com.benjagest.backend.purchases.PurchaseInvoiceRepository;
import com.benjagest.backend.purchases.PurchaseJournalEntryService;
import com.benjagest.backend.tenant.TenantContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Servicio que rellena asientos contables para facturas RECIBIDAS o
 * EMITIDAS que ya existen en BD pero quedaron sin {@code journal_entry_id}
 * porque cuando se guardaron no había {@code fiscal_year} OPEN para su
 * fecha o porque las cuentas PGC todavía no estaban sembradas.
 *
 * <p>Tras V51 (que siembra fiscal_year 2026 OPEN) y V46 (PGC PYMES
 * completo), tiene sentido poder pulsar un botón único y "regenerar
 * todo lo que falta" en lugar de tener que crear cada asiento a mano.
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code POST /api/accounting/backfill/run} — recorre todas las
 *       purchase_invoices con journal_entry_id NULL y las todas
 *       sales_invoices VALIDATED sin asiento, e invoca el service auto
 *       correspondiente. Devuelve resumen.</li>
 * </ul>
 *
 * <p>Idempotente: si la factura ya tiene asiento, la salta.
 */
@Service
public class BackfillJournalService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final PurchaseInvoiceRepository purchaseRepo;
    private final PurchaseJournalEntryService purchaseJournal;
    private final SalesInvoiceRepository salesRepo;
    private final SalesJournalEntryService salesJournal;
    private final CurrentUserService currentUserService;

    public BackfillJournalService(JdbcTemplate jdbcTemplate, TenantContext tenantContext,
                                    PurchaseInvoiceRepository purchaseRepo,
                                    PurchaseJournalEntryService purchaseJournal,
                                    SalesInvoiceRepository salesRepo,
                                    SalesJournalEntryService salesJournal,
                                    CurrentUserService currentUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.purchaseRepo = purchaseRepo;
        this.purchaseJournal = purchaseJournal;
        this.salesRepo = salesRepo;
        this.salesJournal = salesJournal;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public BackfillResult run() {
        String companyId = tenantContext.getCurrentCompanyId();
        String userId;
        try { userId = currentUserService.require().userId(); }
        catch (Exception ex) { userId = null; }

        int purchasesProcessed = 0;
        int purchasesPosted = 0;
        int purchasesSkipped = 0;

        // Purchases: las que no tienen journal_entry_id.
        List<String> purchaseIds = jdbcTemplate.query("""
                SELECT id FROM purchase_invoices
                 WHERE company_id = ? AND journal_entry_id IS NULL
                 ORDER BY invoice_date
                """, (rs, n) -> rs.getString("id"), companyId);
        for (String id : purchaseIds) {
            purchasesProcessed++;
            PurchaseInvoice p = purchaseRepo.findById(id).orElse(null);
            if (p == null) { purchasesSkipped++; continue; }
            try {
                String entryId = purchaseJournal.createForPurchase(p, userId);
                if (entryId != null) {
                    purchaseRepo.updateJournalEntryFk(id, entryId);
                    purchasesPosted++;
                } else {
                    purchasesSkipped++;
                }
            } catch (Exception ex) {
                purchasesSkipped++;
                System.err.println("[backfill] purchase " + id + ": " + ex.getMessage());
            }
        }

        int salesProcessed = 0;
        int salesPosted = 0;
        int salesSkipped = 0;

        // Sales: facturas VALIDATED sin asiento en journal_entries con
        // source_type = SALES_INVOICE y source_id = la factura.
        List<String> salesIds = jdbcTemplate.query("""
                SELECT si.id FROM sales_invoices si
                 WHERE si.company_id = ?
                   AND si.status IN ('VALIDATED','PAID','PARTIAL','OVERDUE')
                   AND NOT EXISTS (
                       SELECT 1 FROM journal_entries je
                        WHERE je.company_id = si.company_id
                          AND je.source_type = 'SALES_INVOICE'
                          AND je.source_id = si.id
                          AND je.status <> 'VOIDED'
                   )
                 ORDER BY si.invoice_date
                """, (rs, n) -> rs.getString("id"), companyId);
        for (String id : salesIds) {
            salesProcessed++;
            SalesInvoice s = salesRepo.findById(id).orElse(null);
            if (s == null) { salesSkipped++; continue; }
            try {
                String entryId = salesJournal.createForSales(s, userId);
                if (entryId != null) salesPosted++;
                else salesSkipped++;
            } catch (Exception ex) {
                salesSkipped++;
                System.err.println("[backfill] sales " + id + ": " + ex.getMessage());
            }
        }

        Map<String, Object> diagnostics = new HashMap<>();
        Integer fyCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fiscal_years
                 WHERE company_id = ? AND status = 'OPEN'
                """, Integer.class, companyId);
        diagnostics.put("openFiscalYears", fyCount);
        Integer acc600 = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM accounting_accounts
                 WHERE company_id = ? AND code LIKE '6%' AND active = TRUE
                """, Integer.class, companyId);
        diagnostics.put("accounts6xx", acc600);

        return new BackfillResult(
                purchasesProcessed, purchasesPosted, purchasesSkipped,
                salesProcessed, salesPosted, salesSkipped,
                diagnostics);
    }

    public record BackfillResult(
            int purchasesProcessed, int purchasesPosted, int purchasesSkipped,
            int salesProcessed, int salesPosted, int salesSkipped,
            Map<String, Object> diagnostics
    ) {}

    @RestController
    @RequestMapping("/api/accounting/backfill")
    @RequiresModule("accounting")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class BackfillController {
        private final BackfillJournalService service;
        public BackfillController(BackfillJournalService service) { this.service = service; }

        @PostMapping("/run")
        public BackfillResult run() { return service.run(); }
    }
}
