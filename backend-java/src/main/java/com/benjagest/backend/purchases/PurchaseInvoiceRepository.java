package com.benjagest.backend.purchases;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a {@code purchase_invoices}. Aislado por TenantContext —
 * una empresa nunca ve facturas de otra empresa ni pasando ids.
 */
@Repository
public class PurchaseInvoiceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public PurchaseInvoiceRepository(JdbcTemplate jdbcTemplate,
                                       TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public void insert(PurchaseInvoice inv) {
        jdbcTemplate.update("""
                INSERT INTO purchase_invoices (
                    id, company_id,
                    supplier_nif, supplier_name,
                    invoice_number, invoice_date,
                    base_amount, vat_percent, vat_amount, total_amount,
                    document_sha256, invoice_index_in_pdf,
                    status, journal_entry_id, expense_account_code,
                    paid, paid_date, payment_account_code, concept, notes,
                    uploaded_by_user_id, uploaded_by_company_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                inv.id(),
                tenantContext.getCurrentCompanyId(),
                inv.supplierNif(),
                inv.supplierName(),
                inv.invoiceNumber(),
                inv.invoiceDate() == null ? null : Date.valueOf(inv.invoiceDate()),
                inv.baseAmount(),
                inv.vatPercent(),
                inv.vatAmount(),
                inv.totalAmount(),
                inv.documentSha256(),
                inv.invoiceIndexInPdf(),
                inv.status() == null ? PurchaseInvoice.STATUS_POSTED : inv.status(),
                inv.journalEntryId(),
                inv.expenseAccountCode(),
                inv.paid(),
                inv.paidDate() == null ? null : Date.valueOf(inv.paidDate()),
                inv.paymentAccountCode(),
                inv.concept(),
                inv.notes(),
                inv.uploadedByUserId(),
                inv.uploadedByCompanyId()
        );
    }

    public Optional<PurchaseInvoice> findById(String id) {
        try {
            PurchaseInvoice row = jdbcTemplate.queryForObject("""
                    SELECT id, company_id, supplier_nif, supplier_name,
                           invoice_number, invoice_date,
                           base_amount, vat_percent, vat_amount, total_amount,
                           document_sha256, invoice_index_in_pdf,
                           status, journal_entry_id, expense_account_code,
                       paid, paid_date, payment_account_code, concept, notes,
                           uploaded_by_user_id, uploaded_by_company_id,
                           created_at, updated_at
                      FROM purchase_invoices
                     WHERE id = ?
                       AND company_id = ?
                    """, this::map, id, tenantContext.getCurrentCompanyId());
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * Busca por (sha + index) para detección de duplicados. El service
     * lo usa para devolver 409 con el id de la factura ya existente.
     */
    public Optional<PurchaseInvoice> findByShaAndIndex(String sha256, int index) {
        if (sha256 == null || sha256.isBlank()) return Optional.empty();
        List<PurchaseInvoice> rows = jdbcTemplate.query("""
                SELECT id, company_id, supplier_nif, supplier_name,
                       invoice_number, invoice_date,
                       base_amount, vat_percent, vat_amount, total_amount,
                       document_sha256, invoice_index_in_pdf,
                       status, journal_entry_id, expense_account_code,
                       paid, paid_date, payment_account_code, concept, notes,
                       uploaded_by_user_id, uploaded_by_company_id,
                       created_at, updated_at
                  FROM purchase_invoices
                 WHERE company_id = ?
                   AND document_sha256 = ?
                   AND invoice_index_in_pdf = ?
                """,
                this::map,
                tenantContext.getCurrentCompanyId(), sha256, index);
        return rows.stream().findFirst();
    }

    /**
     * Listado con filtros opcionales (year/status/supplier). Para
     * paginación se podrá añadir LIMIT/OFFSET cuando lo pida la UI.
     */
    public List<PurchaseInvoice> list(Integer year, String status, String supplierNif) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, company_id, supplier_nif, supplier_name,
                       invoice_number, invoice_date,
                       base_amount, vat_percent, vat_amount, total_amount,
                       document_sha256, invoice_index_in_pdf,
                       status, journal_entry_id, expense_account_code,
                       paid, paid_date, payment_account_code, concept, notes,
                       uploaded_by_user_id, uploaded_by_company_id,
                       created_at, updated_at
                  FROM purchase_invoices
                 WHERE company_id = ?
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (year != null) {
            sql.append(" AND YEAR(invoice_date) = ?");
            args.add(year);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (supplierNif != null && !supplierNif.isBlank()) {
            sql.append(" AND supplier_nif = ?");
            args.add(supplierNif.toUpperCase());
        }
        sql.append(" ORDER BY invoice_date DESC, created_at DESC");
        return jdbcTemplate.query(sql.toString(), this::map, args.toArray());
    }

    /** Cambia el status (DRAFT/POSTED/VOID) sin tocar el resto. */
    public int updateStatus(String id, String status) {
        return jdbcTemplate.update("""
                UPDATE purchase_invoices SET status = ?
                 WHERE id = ? AND company_id = ?
                """, status, id, tenantContext.getCurrentCompanyId());
    }

    /** GAS-2 — Marca el gasto como pagado (fecha + cuenta de banco usada). */
    public int markPaid(String id, java.time.LocalDate paidDate, String paymentAccountCode) {
        return jdbcTemplate.update("""
                UPDATE purchase_invoices
                   SET paid = TRUE, paid_date = ?, payment_account_code = ?
                 WHERE id = ? AND company_id = ?
                """,
                paidDate == null ? null : Date.valueOf(paidDate),
                paymentAccountCode, id, tenantContext.getCurrentCompanyId());
    }

    public int updateJournalEntryFk(String id, String journalEntryId) {
        return jdbcTemplate.update("""
                UPDATE purchase_invoices
                   SET journal_entry_id = ?
                 WHERE id = ?
                   AND company_id = ?
                """, journalEntryId, id, tenantContext.getCurrentCompanyId());
    }

    /**
     * Borrado físico. Antes de invocar, el Service revierte el asiento
     * contable y registra el audit_event correspondiente para que la
     * traza quede aunque la fila desaparezca.
     */
    public int deletePhysical(String id) {
        return jdbcTemplate.update("""
                DELETE FROM purchase_invoices
                 WHERE id = ?
                   AND company_id = ?
                """, id, tenantContext.getCurrentCompanyId());
    }

    private PurchaseInvoice map(ResultSet rs, int rowNum) throws SQLException {
        Date d = rs.getDate("invoice_date");
        Date pd = rs.getDate("paid_date");
        Timestamp ca = rs.getTimestamp("created_at");
        Timestamp ua = rs.getTimestamp("updated_at");
        return new PurchaseInvoice(
                rs.getString("id"),
                rs.getString("company_id"),
                rs.getString("supplier_nif"),
                rs.getString("supplier_name"),
                rs.getString("invoice_number"),
                d == null ? null : d.toLocalDate(),
                (BigDecimal) rs.getObject("base_amount"),
                (BigDecimal) rs.getObject("vat_percent"),
                (BigDecimal) rs.getObject("vat_amount"),
                (BigDecimal) rs.getObject("total_amount"),
                rs.getString("document_sha256"),
                rs.getInt("invoice_index_in_pdf"),
                rs.getString("status"),
                rs.getString("journal_entry_id"),
                rs.getString("expense_account_code"),
                rs.getBoolean("paid"),
                pd == null ? null : pd.toLocalDate(),
                rs.getString("payment_account_code"),
                rs.getString("concept"),
                rs.getString("notes"),
                rs.getString("uploaded_by_user_id"),
                rs.getString("uploaded_by_company_id"),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant()
        );
    }
}
