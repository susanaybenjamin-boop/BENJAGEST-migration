package com.benjagest.backend.billing.verifactu;

import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a verifactu_registry. Cada operacion filtra por TenantContext:
 * una empresa NUNCA ve registros de otra, ni siquiera con id en URL.
 */
@Repository
public class VerifactuRegistryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public VerifactuRegistryRepository(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    /**
     * Hash del ULTIMO registro de la cadena (company + modo). Devuelve
     * cadena vacia si la cadena esta vacia: ese caso es el primer
     * registro de la empresa para ese modo.
     */
    public String findLastHash(String mode) {
        List<String> matches = jdbcTemplate.query("""
                SELECT hash_current
                  FROM verifactu_registry
                 WHERE company_id = ?
                   AND mode = ?
                 ORDER BY generated_at DESC
                 LIMIT 1
                """,
                (rs, rowNum) -> rs.getString("hash_current"),
                tenantContext.getCurrentCompanyId(),
                mode
        );
        return matches.isEmpty() ? "" : matches.get(0);
    }

    public Optional<VerifactuRegistryEntry> findByInvoiceAndMode(String invoiceId, String mode) {
        List<VerifactuRegistryEntry> matches = jdbcTemplate.query("""
                SELECT r.id, r.company_id, r.invoice_id, i.invoice_number, r.mode,
                       r.hash_current, r.hash_previous, r.generated_at, r.sent_at, r.ack_at,
                       r.status, r.retry_count, r.last_error, r.signed_at, r.signature_data
                  FROM verifactu_registry r
                  LEFT JOIN sales_invoices i ON i.id = r.invoice_id
                 WHERE r.invoice_id = ?
                   AND r.mode = ?
                   AND r.company_id = ?
                """,
                this::mapEntry,
                invoiceId,
                mode,
                tenantContext.getCurrentCompanyId()
        );
        return matches.stream().findFirst();
    }

    public List<VerifactuRegistryEntry> findForCompany(String modeFilter, String statusFilter, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.id, r.company_id, r.invoice_id, i.invoice_number, r.mode,
                       r.hash_current, r.hash_previous, r.generated_at, r.sent_at, r.ack_at,
                       r.status, r.retry_count, r.last_error, r.signed_at, r.signature_data
                  FROM verifactu_registry r
                  LEFT JOIN sales_invoices i ON i.id = r.invoice_id
                 WHERE r.company_id = ?
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());

        if (modeFilter != null && !modeFilter.isBlank()) {
            sql.append("   AND r.mode = ?\n");
            args.add(modeFilter.trim());
        }
        if (statusFilter != null && !statusFilter.isBlank()) {
            sql.append("   AND r.status = ?\n");
            args.add(statusFilter.trim());
        }
        sql.append(" ORDER BY r.generated_at DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 500));

        return jdbcTemplate.query(sql.toString(), this::mapEntry, args.toArray());
    }

    public void insert(VerifactuRegistryEntry entry) {
        jdbcTemplate.update("""
                INSERT INTO verifactu_registry (
                    id, company_id, invoice_id, mode,
                    hash_current, hash_previous, status, retry_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entry.id(),
                tenantContext.getCurrentCompanyId(),
                entry.invoiceId(),
                entry.mode(),
                entry.hashCurrent(),
                entry.hashPrevious(),
                entry.status() == null ? "PENDING" : entry.status(),
                entry.retryCount()
        );
    }

    private VerifactuRegistryEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        Timestamp generatedAt = rs.getTimestamp("generated_at");
        Timestamp sentAt = rs.getTimestamp("sent_at");
        Timestamp ackAt = rs.getTimestamp("ack_at");
        Timestamp signedAt = rs.getTimestamp("signed_at");
        return new VerifactuRegistryEntry(
                rs.getString("id"),
                rs.getString("company_id"),
                rs.getString("invoice_id"),
                rs.getString("invoice_number"),
                rs.getString("mode"),
                rs.getString("hash_current"),
                rs.getString("hash_previous"),
                generatedAt == null ? null : generatedAt.toInstant(),
                sentAt == null ? null : sentAt.toInstant(),
                ackAt == null ? null : ackAt.toInstant(),
                rs.getString("status"),
                rs.getInt("retry_count"),
                rs.getString("last_error"),
                signedAt == null ? null : signedAt.toInstant(),
                rs.getString("signature_data")
        );
    }
}
