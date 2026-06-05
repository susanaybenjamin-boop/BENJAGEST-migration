package com.benjagest.backend.purchases;

import com.benjagest.backend.tenant.TenantContext;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea un asiento contable a partir de una factura de compra cuando
 * la empresa tiene el plan contable mínimo activo.
 *
 * Asiento de compra estándar (PGC español):
 *
 *   Debe  600x (Compras) o 6xx por defecto       = base_amount
 *   Debe  472x (IVA soportado)                    = vat_amount
 *                Haber  400x (Proveedores)         = total_amount
 *
 * El service busca cuentas por prefijo (600/472/400) para tolerar
 * sub-cuentas analíticas (600001, 472001, 400015...). Si no encuentra
 * alguna o no hay fiscal_year OPEN para la fecha de la factura,
 * devuelve {@code null} (la factura se guarda sin asiento) — el
 * módulo contable maduro lo creará en otro slice.
 *
 * Decisión de scope: este service es deliberadamente simple para el
 * caso 80%. Asientos complejos (intracomunitarios con autorrepercusión,
 * prorrata IVA, retenciones IRPF a proveedores…) llegarán cuando se
 * cierre el módulo contable completo.
 */
@Service
public class PurchaseJournalEntryService {

    private static final String SRC_TYPE = "PURCHASE_INVOICE";

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public PurchaseJournalEntryService(JdbcTemplate jdbcTemplate,
                                         TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    /**
     * Intenta crear un asiento para la factura. Devuelve el id del
     * asiento o {@code null} si no fue posible (faltan cuentas o
     * fiscal_year). Nunca lanza excepción: el caller decide qué hacer
     * cuando devuelve null (típicamente loguear y persistir la factura
     * sin journal_entry_id).
     */
    @Transactional
    public String createForPurchase(PurchaseInvoice purchase, String userId) {
        if (purchase.invoiceDate() == null
                || purchase.baseAmount() == null
                || purchase.vatAmount() == null
                || purchase.totalAmount() == null) {
            return null;
        }
        String companyId = tenantContext.getCurrentCompanyId();

        // 1) Fiscal year OPEN para la fecha de la factura.
        String fiscalYearId = findOpenFiscalYearId(companyId, purchase.invoiceDate());
        if (fiscalYearId == null) return null;

        // 2) Cuentas 600/472/400 (prefijo).
        String acc600 = findAccountByPrefix(companyId, "600");
        String acc472 = findAccountByPrefix(companyId, "472");
        String acc400 = findAccountByPrefix(companyId, "400");
        if (acc600 == null || acc472 == null || acc400 == null) {
            return null;
        }

        // 3) entry_number = siguiente para este fiscal_year. Posibilidad
        //    de race en concurrencia alta — aceptable hasta que llegue
        //    el slice contable serio (con secuencia oficial).
        Integer maxEntryNumber = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0)
                  FROM journal_entries
                 WHERE company_id = ?
                   AND fiscal_year_id = ?
                """, Integer.class, companyId, fiscalYearId);
        int entryNumber = (maxEntryNumber == null ? 0 : maxEntryNumber) + 1;

        // 4) Crear el asiento.
        String entryId = UUID.randomUUID().toString();
        String concept = buildConcept(purchase);
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', FALSE, ?)
                """,
                entryId, companyId, fiscalYearId, entryNumber,
                Date.valueOf(purchase.invoiceDate()),
                concept, SRC_TYPE, purchase.id(), userId);

        // 5) Líneas: 2 debes (600 base, 472 iva) + 1 haber (400 total).
        insertLine(entryId, acc600, "Compra " + safe(purchase.supplierName()),
                purchase.baseAmount(), java.math.BigDecimal.ZERO);
        insertLine(entryId, acc472, "IVA soportado " + safe(purchase.vatPercent()) + "%",
                purchase.vatAmount(), java.math.BigDecimal.ZERO);
        insertLine(entryId, acc400, "Pdte. pago " + safe(purchase.supplierNif()),
                java.math.BigDecimal.ZERO, purchase.totalAmount());

        return entryId;
    }

    /** Revierte el asiento (anula el asiento original y crea uno opuesto). */
    @Transactional
    public void reverseForPurchase(PurchaseInvoice purchase) {
        if (purchase.journalEntryId() == null) return;
        // MVP: marcar el asiento como VOIDED. La inversión real (asiento
        // espejo con debe/haber cambiados) llega cuando el módulo
        // contable madure.
        jdbcTemplate.update("""
                UPDATE journal_entries
                   SET status = 'VOIDED'
                 WHERE id = ?
                   AND company_id = ?
                """, purchase.journalEntryId(), tenantContext.getCurrentCompanyId());
    }

    private String findOpenFiscalYearId(String companyId, LocalDate date) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id
                  FROM fiscal_years
                 WHERE company_id = ?
                   AND status = 'OPEN'
                   AND ? BETWEEN start_date AND end_date
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, Date.valueOf(date));
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * Busca la primera cuenta cuyo code empieza por el prefijo dado.
     * Si la empresa tiene la cuenta exacta (e.g. "600"), gana sobre
     * sub-cuentas (e.g. "600001"). Si solo tiene sub-cuentas, devuelve
     * la primera alfabéticamente.
     */
    private String findAccountByPrefix(String companyId, String prefix) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id
                  FROM accounting_accounts
                 WHERE company_id = ?
                   AND active = TRUE
                   AND code LIKE ?
                 ORDER BY LENGTH(code), code
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, prefix + "%");
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void insertLine(String entryId, String accountId, String description,
                              java.math.BigDecimal debit, java.math.BigDecimal credit) {
        jdbcTemplate.update("""
                INSERT INTO journal_entry_lines (
                    id, journal_entry_id, account_id, description, debit, credit
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entryId, accountId,
                description == null ? null
                        : (description.length() > 240 ? description.substring(0, 240) : description),
                debit, credit);
    }

    private String buildConcept(PurchaseInvoice p) {
        StringBuilder sb = new StringBuilder("Fra. ");
        if (p.invoiceNumber() != null) sb.append(p.invoiceNumber()).append(' ');
        if (p.supplierName() != null) sb.append("- ").append(p.supplierName());
        String s = sb.toString();
        return s.length() > 240 ? s.substring(0, 240) : s;
    }

    private String safe(Object v) {
        return v == null ? "" : v.toString();
    }

    /** Para tests del slice futuro de contabilidad — no usado todavía. */
    @SuppressWarnings("unused")
    private Map<String, Object> sentinel() { return Map.of(); }
}
