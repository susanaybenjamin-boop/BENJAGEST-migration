package com.benjagest.backend.purchases;

import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.audit.AuditService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Persistencia y consulta de facturas de compra (gastos).
 *
 * Flujo principal save(request):
 *   1) Dedup: si (company, sha, index) ya existe → 409 con id existente.
 *   2) Insertar la factura con status POSTED.
 *   3) Intentar crear asiento contable vía
 *      {@link PurchaseJournalEntryService}; si la empresa no tiene
 *      cuentas 600/472/400 o no hay fiscal_year OPEN, se persiste
 *      sin asiento (sub-slice contable lo regenera más tarde).
 *   4) Auditar PURCHASE_INVOICE_POSTED.
 */
@Service
public class PurchaseInvoiceService {

    private final PurchaseInvoiceRepository repository;
    private final PurchaseJournalEntryService journalService;
    private final CurrentUserService currentUserService;
    private final TenantContext tenantContext;
    private final AuditService auditService;

    public PurchaseInvoiceService(PurchaseInvoiceRepository repository,
                                    PurchaseJournalEntryService journalService,
                                    CurrentUserService currentUserService,
                                    TenantContext tenantContext,
                                    AuditService auditService) {
        this.repository = repository;
        this.journalService = journalService;
        this.currentUserService = currentUserService;
        this.tenantContext = tenantContext;
        this.auditService = auditService;
    }

    @Transactional
    public SaveResult save(SaveRequest req) {
        // 1) Dedup por (company, sha, index).
        if (req.documentSha256() != null && !req.documentSha256().isBlank()) {
            var existing = repository.findByShaAndIndex(
                    req.documentSha256(), req.invoiceIndexInPdf());
            if (existing.isPresent()) {
                return new SaveResult(existing.get(), true, "Factura ya registrada");
            }
        }

        AuthenticatedUser user = currentUserService.require();
        String tenant = tenantContext.getCurrentCompanyId();
        String uploaderCompany = user.activeCompanyId();

        String id = UUID.randomUUID().toString();
        PurchaseInvoice draft = new PurchaseInvoice(
                id,
                tenant,
                normalize(req.supplierNif()),
                blankToNull(req.supplierName()),
                blankToNull(req.invoiceNumber()),
                req.invoiceDate(),
                req.baseAmount(),
                req.vatPercent(),
                req.vatAmount(),
                req.totalAmount(),
                blankToNull(req.documentSha256()),
                req.invoiceIndexInPdf(),
                PurchaseInvoice.STATUS_POSTED,
                null,
                blankToNull(req.notes()),
                user.userId(),
                uploaderCompany,
                null, null
        );

        // 2) Insertar.
        repository.insert(draft);

        // 3) Intentar asiento (best effort).
        String journalEntryId = null;
        try {
            journalEntryId = journalService.createForPurchase(draft, user.userId());
        } catch (Exception ex) {
            // Tragamos: la factura ya está persistida; el asiento se
            // generará en sub-slice contable. Loguear sin romper.
            System.err.println("[purchases] no se pudo crear asiento para "
                    + id + ": " + ex.getMessage());
        }
        if (journalEntryId != null) {
            repository.updateJournalEntryFk(id, journalEntryId);
        }

        // 4) Auditoría.
        auditService.recordPurchaseInvoicePosted(user.userId(), tenant, id,
                draft.totalAmount(), journalEntryId != null);

        PurchaseInvoice persisted = repository.findById(id).orElseThrow();
        return new SaveResult(persisted, false, null);
    }

    public PurchaseInvoice get(String id) {
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Gasto no encontrado"));
    }

    public List<PurchaseInvoice> list(Integer year, String status, String supplierNif) {
        return repository.list(year, status, supplierNif);
    }

    @Transactional
    public void voidInvoice(String id) {
        PurchaseInvoice existing = get(id);
        if (PurchaseInvoice.STATUS_VOID.equals(existing.status())) {
            return; // ya anulada
        }
        repository.updateStatus(id, PurchaseInvoice.STATUS_VOID);
        if (existing.journalEntryId() != null) {
            try {
                journalService.reverseForPurchase(existing);
            } catch (Exception ex) {
                System.err.println("[purchases] no se pudo revertir asiento " + ex.getMessage());
            }
        }
        AuthenticatedUser user = currentUserService.require();
        auditService.recordPurchaseInvoiceVoided(
                user.userId(), tenantContext.getCurrentCompanyId(), id);
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
    private String normalize(String nif) {
        return nif == null || nif.isBlank() ? null : nif.trim().toUpperCase();
    }

    // ====================================================================
    //  DTOs públicos
    // ====================================================================

    public record SaveRequest(
            String supplierNif,
            String supplierName,
            String invoiceNumber,
            LocalDate invoiceDate,
            BigDecimal baseAmount,
            BigDecimal vatPercent,
            BigDecimal vatAmount,
            BigDecimal totalAmount,
            String documentSha256,
            int invoiceIndexInPdf,
            String notes
    ) {}

    public record SaveResult(
            PurchaseInvoice invoice,
            boolean duplicate,
            String message
    ) {}

    /** Sin uso — sentinel para futuros campos calculados. */
    @SuppressWarnings("unused")
    private Map<String, Object> _sentinel() { return Map.of(); }
}
