package com.benjagest.backend.billing.invoices;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a sales_invoices + sales_invoice_lines. Carga eager de lineas
 * por factura (cada SalesInvoice se devuelve con su List<InvoiceLine>).
 *
 * Aislamiento por TenantContext: cada query filtra por company_id. El
 * customer_legal_name se devuelve por LEFT JOIN para ahorrar una
 * llamada extra al pintar listados.
 */
@Repository
public class SalesInvoiceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public SalesInvoiceRepository(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public void insertHeader(SalesInvoice invoice) {
        jdbcTemplate.update("""
                INSERT INTO sales_invoices (
                    id, company_id, customer_id, series_id, invoice_number,
                    invoice_date, due_date, invoice_type, status, payment_status,
                    subtotal, vat_total, retention_total, total, paid_amount,
                    currency, original_invoice_id, notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                invoice.id(),
                tenantContext.getCurrentCompanyId(),
                invoice.customerId(),
                invoice.seriesId(),
                invoice.invoiceNumber(),
                invoice.invoiceDate() == null ? null : Date.valueOf(invoice.invoiceDate()),
                invoice.dueDate() == null ? null : Date.valueOf(invoice.dueDate()),
                invoice.invoiceType(),
                invoice.status(),
                invoice.paymentStatus(),
                invoice.subtotal(),
                invoice.vatTotal(),
                invoice.retentionTotal(),
                invoice.total(),
                invoice.paidAmount() == null ? BigDecimal.ZERO : invoice.paidAmount(),
                invoice.currency() == null ? "EUR" : invoice.currency(),
                invoice.originalInvoiceId(),
                invoice.notes()
        );
    }

    public int updateHeader(String id, SalesInvoice invoice) {
        return jdbcTemplate.update("""
                UPDATE sales_invoices
                   SET customer_id = ?,
                       series_id = ?,
                       invoice_date = ?,
                       due_date = ?,
                       invoice_type = ?,
                       subtotal = ?,
                       vat_total = ?,
                       retention_total = ?,
                       total = ?,
                       original_invoice_id = ?,
                       notes = ?
                 WHERE id = ?
                   AND company_id = ?
                   AND status = 'DRAFT'
                """,
                invoice.customerId(),
                invoice.seriesId(),
                invoice.invoiceDate() == null ? null : Date.valueOf(invoice.invoiceDate()),
                invoice.dueDate() == null ? null : Date.valueOf(invoice.dueDate()),
                invoice.invoiceType(),
                invoice.subtotal(),
                invoice.vatTotal(),
                invoice.retentionTotal(),
                invoice.total(),
                invoice.originalInvoiceId(),
                invoice.notes(),
                id,
                tenantContext.getCurrentCompanyId()
        );
    }

    /**
     * Sella la factura al validar: numero asignado, status VALIDATED,
     * validated_at, totales finales. Solo cambia si la factura sigue
     * en DRAFT (defensa contra carreras).
     */
    public int markValidated(String id, String invoiceNumber,
                             BigDecimal subtotal, BigDecimal vatTotal,
                             BigDecimal retentionTotal, BigDecimal total) {
        return jdbcTemplate.update("""
                UPDATE sales_invoices
                   SET invoice_number = ?,
                       status = 'VALIDATED',
                       validated_at = CURRENT_TIMESTAMP,
                       subtotal = ?,
                       vat_total = ?,
                       retention_total = ?,
                       total = ?
                 WHERE id = ?
                   AND company_id = ?
                   AND status = 'DRAFT'
                """,
                invoiceNumber,
                subtotal,
                vatTotal,
                retentionTotal,
                total,
                id,
                tenantContext.getCurrentCompanyId()
        );
    }

    /**
     * Cancela una factura en DRAFT (status = CANCELLED). Una factura
     * VALIDATED no se borra ni cancela aqui — para esa hay que
     * emitir un evento ANULACION (otro slice).
     */
    public int softCancelDraft(String id) {
        return jdbcTemplate.update("""
                UPDATE sales_invoices
                   SET status = 'CANCELLED'
                 WHERE id = ?
                   AND company_id = ?
                   AND status = 'DRAFT'
                """,
                id,
                tenantContext.getCurrentCompanyId()
        );
    }

    public void deleteLinesForInvoice(String invoiceId) {
        jdbcTemplate.update("""
                DELETE FROM sales_invoice_lines
                 WHERE invoice_id IN (
                     SELECT id FROM sales_invoices
                      WHERE id = ?
                        AND company_id = ?
                 )
                """,
                invoiceId,
                tenantContext.getCurrentCompanyId()
        );
    }

    public void insertLine(InvoiceLine line) {
        jdbcTemplate.update("""
                INSERT INTO sales_invoice_lines (
                    id, invoice_id, catalog_item_id, description,
                    quantity, unit_price, vat_percent, retention_percent,
                    line_subtotal, line_vat, line_retention, line_total
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                line.id(),
                line.invoiceId(),
                line.catalogItemId(),
                line.description(),
                line.quantity(),
                line.unitPrice(),
                line.vatPercent(),
                line.retentionPercent(),
                line.lineSubtotal(),
                line.lineVat(),
                line.lineRetention(),
                line.lineTotal()
        );
    }

    public Optional<SalesInvoice> findById(String id) {
        List<SalesInvoice> matches = jdbcTemplate.query("""
                SELECT i.id, i.company_id, i.customer_id, c.legal_name AS customer_legal_name,
                       i.series_id, i.invoice_number, i.invoice_date, i.due_date,
                       i.invoice_type, i.status, i.payment_status,
                       i.subtotal, i.vat_total, i.retention_total, i.total, i.paid_amount,
                       i.currency, i.original_invoice_id, i.rectifying_invoice_id,
                       i.notes, i.validated_at, i.created_at, i.updated_at
                  FROM sales_invoices i
                  LEFT JOIN customers c ON c.id = i.customer_id
                 WHERE i.id = ?
                   AND i.company_id = ?
                """,
                this::mapHeader,
                id,
                tenantContext.getCurrentCompanyId()
        );
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(withLines(matches.get(0)));
    }

    /**
     * Listado paginado con filtros opcionales. Solo el listado: las
     * lineas NO se cargan por defecto (puede haber cientos de facturas
     * con varias lineas cada una; cargar todo serializa innecesariamente).
     * Quien quiera lineas, llama a findById.
     */
    public List<SalesInvoice> findAll(String statusFilter,
                                      String paymentStatusFilter,
                                      String customerIdFilter,
                                      int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT i.id, i.company_id, i.customer_id, c.legal_name AS customer_legal_name,
                       i.series_id, i.invoice_number, i.invoice_date, i.due_date,
                       i.invoice_type, i.status, i.payment_status,
                       i.subtotal, i.vat_total, i.retention_total, i.total, i.paid_amount,
                       i.currency, i.original_invoice_id, i.rectifying_invoice_id,
                       i.notes, i.validated_at, i.created_at, i.updated_at
                  FROM sales_invoices i
                  LEFT JOIN customers c ON c.id = i.customer_id
                 WHERE i.company_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());

        if (statusFilter != null && !statusFilter.isBlank()) {
            sql.append("   AND i.status = ?\n");
            args.add(statusFilter.trim());
        }
        if (paymentStatusFilter != null && !paymentStatusFilter.isBlank()) {
            sql.append("   AND i.payment_status = ?\n");
            args.add(paymentStatusFilter.trim());
        }
        if (customerIdFilter != null && !customerIdFilter.isBlank()) {
            sql.append("   AND i.customer_id = ?\n");
            args.add(customerIdFilter.trim());
        }
        sql.append(" ORDER BY i.invoice_date DESC, i.created_at DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 500));

        return jdbcTemplate.query(sql.toString(), this::mapHeader, args.toArray());
    }

    private SalesInvoice withLines(SalesInvoice header) {
        List<InvoiceLine> lines = jdbcTemplate.query("""
                SELECT id, invoice_id, catalog_item_id, description,
                       quantity, unit_price, vat_percent, retention_percent,
                       line_subtotal, line_vat, line_retention, line_total
                  FROM sales_invoice_lines
                 WHERE invoice_id = ?
                 ORDER BY created_at
                """,
                this::mapLine,
                header.id()
        );
        return new SalesInvoice(
                header.id(), header.companyId(), header.customerId(), header.customerLegalName(),
                header.seriesId(), header.invoiceNumber(), header.invoiceDate(), header.dueDate(),
                header.invoiceType(), header.status(), header.paymentStatus(),
                header.subtotal(), header.vatTotal(), header.retentionTotal(),
                header.total(), header.paidAmount(), header.currency(),
                header.originalInvoiceId(), header.rectifyingInvoiceId(),
                header.notes(), header.validatedAt(),
                header.createdAt(), header.updatedAt(),
                lines
        );
    }

    private SalesInvoice mapHeader(ResultSet rs, int rowNum) throws SQLException {
        Date invDate = rs.getDate("invoice_date");
        Date due = rs.getDate("due_date");
        Timestamp validatedAt = rs.getTimestamp("validated_at");
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new SalesInvoice(
                rs.getString("id"),
                rs.getString("company_id"),
                rs.getString("customer_id"),
                rs.getString("customer_legal_name"),
                rs.getString("series_id"),
                rs.getString("invoice_number"),
                invDate == null ? null : invDate.toLocalDate(),
                due == null ? null : due.toLocalDate(),
                rs.getString("invoice_type"),
                rs.getString("status"),
                rs.getString("payment_status"),
                rs.getBigDecimal("subtotal"),
                rs.getBigDecimal("vat_total"),
                rs.getBigDecimal("retention_total"),
                rs.getBigDecimal("total"),
                rs.getBigDecimal("paid_amount"),
                rs.getString("currency"),
                rs.getString("original_invoice_id"),
                rs.getString("rectifying_invoice_id"),
                rs.getString("notes"),
                validatedAt == null ? null : validatedAt.toInstant(),
                createdAt == null ? null : createdAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant(),
                List.of()
        );
    }

    private InvoiceLine mapLine(ResultSet rs, int rowNum) throws SQLException {
        return new InvoiceLine(
                rs.getString("id"),
                rs.getString("invoice_id"),
                rs.getString("catalog_item_id"),
                rs.getString("description"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("vat_percent"),
                rs.getBigDecimal("retention_percent"),
                rs.getBigDecimal("line_subtotal"),
                rs.getBigDecimal("line_vat"),
                rs.getBigDecimal("line_retention"),
                rs.getBigDecimal("line_total")
        );
    }

    // Helper para tests futuros: indica cuantas filas existen para la
    // empresa actual (util en smoke tests).
    public long countAll() {
        Map<String, Object> args = new HashMap<>();
        args.put("c", tenantContext.getCurrentCompanyId());
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sales_invoices WHERE company_id = ?",
                Long.class,
                tenantContext.getCurrentCompanyId()
        );
        return n == null ? 0 : n;
    }
}
