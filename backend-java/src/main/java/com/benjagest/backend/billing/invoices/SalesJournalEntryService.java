package com.benjagest.backend.billing.invoices;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea un asiento contable a partir de una factura emitida cuando la
 * empresa tiene el plan contable mínimo activo. Espejo de
 * PurchaseJournalEntryService pero con las cuentas de ventas.
 *
 * <p>Asiento de venta estándar (PGC español):
 *
 * <pre>
 *   Debe   430x (Clientes)              = total - retención
 *   Debe   473x (HP retenciones a/c)    = retención (si aplica)
 *                Haber  700x (Ventas)               = base_amount
 *                Haber  477x (HP IVA repercutido)    = vat_amount
 * </pre>
 *
 * <p>Si no encuentra alguna cuenta o no hay fiscal_year OPEN para la
 * fecha, devuelve {@code null} (la validación de la factura continúa
 * sin asiento). Mismo nivel de scope que purchases: el módulo
 * contable maduro (subcuentas analíticas por cliente, libros oficiales)
 * llegará en otro slice.
 *
 * <p>Decisión de scope (espejo de purchases):
 * <ul>
 *   <li>Cuentas por prefijo: 430/700/477/473. Tolera sub-cuentas
 *       (4300xx por cliente, 7000xx por producto, etc.) si existen.</li>
 *   <li>Si no hay retención, no se crea la línea 473.</li>
 *   <li>Anulación: marca status=VOIDED del asiento (igual que purchases).
 *       La inversión real con asiento espejo llega en YEAR-CLOSE.</li>
 * </ul>
 */
@Service
public class SalesJournalEntryService {

    private static final String SRC_TYPE = "SALES_INVOICE";

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public SalesJournalEntryService(JdbcTemplate jdbcTemplate,
                                      TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    /**
     * Intenta crear un asiento para la factura validada. Devuelve el
     * id del asiento o {@code null} si no fue posible. Nunca lanza:
     * el caller (SalesInvoiceService.validateInternal) decide qué
     * hacer cuando devuelve null — típicamente loguea y continúa,
     * porque la validación legal de la factura es independiente de la
     * generación del asiento.
     */
    @Transactional
    public String createForSales(SalesInvoice invoice, String userId) {
        if (invoice.invoiceDate() == null
                || invoice.subtotal() == null
                || invoice.vatTotal() == null
                || invoice.total() == null) {
            return null;
        }
        String companyId = tenantContext.getCurrentCompanyId();

        String fiscalYearId = findOpenFiscalYearId(companyId, invoice.invoiceDate());
        if (fiscalYearId == null) return null;

        String acc430 = findAccountByPrefix(companyId, "430"); // Clientes
        String acc700 = findAccountByPrefix(companyId, "700"); // Ventas
        String acc477 = findAccountByPrefix(companyId, "477"); // HP IVA repercutido
        if (acc430 == null || acc700 == null || acc477 == null) {
            return null;
        }
        // 473 solo si hay retención.
        BigDecimal retention = invoice.retentionTotal() == null
                ? BigDecimal.ZERO : invoice.retentionTotal();
        String acc473 = null;
        if (retention.signum() > 0) {
            acc473 = findAccountByPrefix(companyId, "473"); // HP retenciones a cuenta
            if (acc473 == null) return null;
        }

        Integer maxEntryNumber = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0)
                  FROM journal_entries
                 WHERE company_id = ?
                   AND fiscal_year_id = ?
                """, Integer.class, companyId, fiscalYearId);
        int entryNumber = (maxEntryNumber == null ? 0 : maxEntryNumber) + 1;

        String entryId = UUID.randomUUID().toString();
        String concept = buildConcept(invoice);
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', FALSE, ?)
                """,
                entryId, companyId, fiscalYearId, entryNumber,
                Date.valueOf(invoice.invoiceDate()),
                concept, SRC_TYPE, invoice.id(), userId);

        // Debe 430: cliente cobra total menos retención
        BigDecimal clientDebit = invoice.total().subtract(retention);
        insertLine(entryId, acc430, "Cliente " + safe(invoice.customerLegalName()),
                clientDebit, BigDecimal.ZERO);

        // Debe 473: si hay retención IRPF
        if (acc473 != null) {
            insertLine(entryId, acc473, "Retención IRPF a cuenta",
                    retention, BigDecimal.ZERO);
        }

        // Haber 700: ventas (base imponible)
        insertLine(entryId, acc700, "Venta factura " + safe(invoice.invoiceNumber()),
                BigDecimal.ZERO, invoice.subtotal());

        // Haber 477: IVA repercutido
        insertLine(entryId, acc477, "IVA repercutido factura " + safe(invoice.invoiceNumber()),
                BigDecimal.ZERO, invoice.vatTotal());

        return entryId;
    }

    /** Marca el asiento de una factura como VOIDED al anularla. */
    @Transactional
    public void reverseForSales(String invoiceId) {
        jdbcTemplate.update("""
                UPDATE journal_entries
                   SET status = 'VOIDED'
                 WHERE source_type = ?
                   AND source_id = ?
                   AND company_id = ?
                   AND status = 'DRAFT'
                """, SRC_TYPE, invoiceId, tenantContext.getCurrentCompanyId());
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
                              BigDecimal debit, BigDecimal credit) {
        jdbcTemplate.update("""
                INSERT INTO journal_entry_lines (
                    id, journal_entry_id, account_id, description,
                    debit, credit
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entryId, accountId,
                description, debit, credit);
    }

    private String buildConcept(SalesInvoice invoice) {
        StringBuilder sb = new StringBuilder("Venta ");
        if (invoice.invoiceNumber() != null) sb.append(invoice.invoiceNumber()).append(' ');
        if (invoice.customerLegalName() != null) sb.append("a ").append(invoice.customerLegalName());
        return sb.toString().trim();
    }

    private static String safe(Object v) { return v == null ? "" : v.toString(); }
}
