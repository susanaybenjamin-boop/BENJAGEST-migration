package com.benjagest.backend.billing.verifactu;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    /**
     * Persiste la firma electronica del registro (slice VF-SIGN). Lo
     * separamos del INSERT porque la firma puede llegar tarde (cuando
     * VF-SIGN no este configurado o falle, el registro queda sin firma
     * y otro slice puede reintentar mas adelante — VF4).
     */
    public int setSignature(String invoiceId, String mode, String signatureXml) {
        return jdbcTemplate.update("""
                UPDATE verifactu_registry
                   SET signature_data = ?,
                       signed_at = CURRENT_TIMESTAMP,
                       status = 'SIGNED'
                 WHERE invoice_id = ?
                   AND mode = ?
                   AND company_id = ?
                """,
                signatureXml, invoiceId, mode, tenantContext.getCurrentCompanyId()
        );
    }

    /**
     * Variante de {@link #insert} que persiste {@code generated_at} con el
     * MISMO instante usado para calcular el hash (truncado a segundo).
     * Necesario para que la verificación posterior pueda recalcular el
     * hash con el mismo input — si dejásemos DEFAULT CURRENT_TIMESTAMP,
     * habría una desincronización de milisegundos invisible que
     * romperíamos la cadena al verificar.
     */
    public void insertWithGenerationTime(String id, String invoiceId, String mode,
                                          String hashCurrent, String hashPrevious,
                                          OffsetDateTime generationTime) {
        jdbcTemplate.update("""
                INSERT INTO verifactu_registry (
                    id, company_id, invoice_id, mode,
                    hash_current, hash_previous,
                    generated_at, status, retry_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0)
                """,
                id,
                tenantContext.getCurrentCompanyId(),
                invoiceId,
                mode,
                hashCurrent,
                hashPrevious,
                // Convertimos a UTC porque MariaDB TIMESTAMP almacena en
                // UTC y aplica session zone al leer. Usamos Timestamp para
                // mantener consistencia con cualquier zona del servidor.
                Timestamp.from(generationTime.toInstant())
        );
    }

    /**
     * Cadena cronológica ASC (la más antigua primero) para
     * {@code (company_id, mode)}. Cada fila incluye los datos de la
     * factura necesarios para recalcular su hash. Se usa para verify.
     */
    public List<ChainRow> findChainOrderedAsc(String mode) {
        return findChainOrderedAscForCompany(tenantContext.getCurrentCompanyId(), mode);
    }

    /**
     * Variante sin TenantContext — usada por jobs @Scheduled (VF-ANOMALY,
     * VF4) que iteran sobre todas las empresas fuera del request scope.
     */
    public List<ChainRow> findChainOrderedAscForCompany(String companyId, String mode) {
        return jdbcTemplate.query("""
                SELECT r.invoice_id, i.invoice_number, i.invoice_date,
                       i.vat_total, i.total,
                       r.hash_current, r.hash_previous, r.generated_at
                  FROM verifactu_registry r
                  JOIN sales_invoices i ON i.id = r.invoice_id
                 WHERE r.company_id = ?
                   AND r.mode = ?
                 ORDER BY r.generated_at ASC, r.id ASC
                """,
                this::mapChainRow,
                companyId,
                mode
        );
    }

    /**
     * NIF/CIF de una empresa concreta sin TenantContext. Lo necesitan los
     * jobs que verifican cadenas de empresas distintas a la actual.
     */
    public String findTaxIdentifierForCompany(String companyId) {
        List<String> matches = jdbcTemplate.query(
                "SELECT tax_identifier FROM companies WHERE id = ?",
                (rs, rowNum) -> rs.getString("tax_identifier"),
                companyId);
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * Lista de empresas con modalidad y mode configurados (usado por
     * VF-ANOMALY para iterar). Devuelve pares (companyId, mode) — la
     * cadena de hash se segmenta por mode (TEST/PROD), asi que cada
     * empresa puede tener dos cadenas a verificar.
     */
    public List<CompanyChainRef> findAllChainRefs() {
        return jdbcTemplate.query("""
                SELECT DISTINCT c.id AS company_id, r.mode
                  FROM companies c
                  JOIN verifactu_registry r ON r.company_id = c.id
                """,
                (rs, rowNum) -> new CompanyChainRef(
                        rs.getString("company_id"),
                        rs.getString("mode"))
        );
    }

    public record CompanyChainRef(String companyId, String mode) {}

    /**
     * Filas PENDING (sin firma) candidatas a reintento por VF4. Trae los
     * mismos campos canonicos que necesita el firmador para reconstruir
     * el XML del registro identicamente al que se intento firmar
     * originalmente.
     */
    public List<PendingForSigning> findPendingForSigning(int limit) {
        return jdbcTemplate.query("""
                SELECT r.invoice_id, r.mode, r.company_id,
                       c.tax_identifier,
                       i.invoice_number, i.invoice_date,
                       i.vat_total, i.total,
                       r.hash_current, r.hash_previous, r.generated_at
                  FROM verifactu_registry r
                  JOIN companies c ON c.id = r.company_id
                  JOIN sales_invoices i ON i.id = r.invoice_id
                 WHERE r.status = 'PENDING'
                 ORDER BY r.generated_at ASC
                 LIMIT ?
                """,
                (rs, rowNum) -> new PendingForSigning(
                        rs.getString("invoice_id"),
                        rs.getString("mode"),
                        rs.getString("company_id"),
                        rs.getString("tax_identifier"),
                        rs.getString("invoice_number"),
                        rs.getDate("invoice_date") == null ? null : rs.getDate("invoice_date").toLocalDate(),
                        rs.getBigDecimal("vat_total"),
                        rs.getBigDecimal("total"),
                        rs.getString("hash_current"),
                        rs.getString("hash_previous"),
                        rs.getTimestamp("generated_at") == null ? null
                                : OffsetDateTime.ofInstant(rs.getTimestamp("generated_at").toInstant(), ZoneOffset.UTC)),
                Math.min(Math.max(limit, 1), 200));
    }

    /**
     * Setter de firma con companyId explicito (para VF4: el job no
     * tiene TenantContext).
     */
    public int setSignatureForCompany(String invoiceId, String mode,
                                       String companyId, String signatureXml) {
        return jdbcTemplate.update("""
                UPDATE verifactu_registry
                   SET signature_data = ?,
                       signed_at = CURRENT_TIMESTAMP,
                       status = 'SIGNED'
                 WHERE invoice_id = ?
                   AND mode = ?
                   AND company_id = ?
                """,
                signatureXml, invoiceId, mode, companyId);
    }

    /**
     * Filas SIGNED candidatas a envio AEAT (VF3-SOAP). Solo aplican
     * empresas con modalidad VERIFACTU; el JOIN con companies lo
     * filtra. Solo devuelve PROD (la modalidad VeriFactu real solo
     * existe en PROD; TEST queda como sandbox local).
     */
    public List<PendingForSending> findPendingForSending(int limit) {
        return jdbcTemplate.query("""
                SELECT r.invoice_id, r.mode, r.company_id,
                       c.tax_identifier, c.legal_name,
                       r.hash_current, r.signature_data, r.retry_count
                  FROM verifactu_registry r
                  JOIN companies c ON c.id = r.company_id
                 WHERE r.status = 'SIGNED'
                   AND c.verifactu_modality = 'VERIFACTU'
                   AND r.retry_count < 5
                 ORDER BY r.generated_at ASC
                 LIMIT ?
                """,
                (rs, rowNum) -> new PendingForSending(
                        rs.getString("invoice_id"),
                        rs.getString("mode"),
                        rs.getString("company_id"),
                        rs.getString("tax_identifier"),
                        rs.getString("legal_name"),
                        rs.getString("hash_current"),
                        rs.getString("signature_data"),
                        rs.getInt("retry_count")),
                Math.min(Math.max(limit, 1), 50));
    }

    public int markSent(String invoiceId, String mode, String companyId) {
        return jdbcTemplate.update("""
                UPDATE verifactu_registry
                   SET status = 'SENT',
                       sent_at = CURRENT_TIMESTAMP
                 WHERE invoice_id = ? AND mode = ? AND company_id = ?
                """, invoiceId, mode, companyId);
    }

    public int markAcknowledged(String invoiceId, String mode, String companyId) {
        return jdbcTemplate.update("""
                UPDATE verifactu_registry
                   SET status = 'ACKNOWLEDGED',
                       ack_at = CURRENT_TIMESTAMP
                 WHERE invoice_id = ? AND mode = ? AND company_id = ?
                """, invoiceId, mode, companyId);
    }

    public int markError(String invoiceId, String mode, String companyId, String errorMessage) {
        return jdbcTemplate.update("""
                UPDATE verifactu_registry
                   SET status = 'ERROR',
                       retry_count = retry_count + 1,
                       last_error = ?
                 WHERE invoice_id = ? AND mode = ? AND company_id = ?
                """, errorMessage, invoiceId, mode, companyId);
    }

    public record PendingForSending(
            String invoiceId,
            String mode,
            String companyId,
            String taxIdentifier,
            String companyLegalName,
            String hashCurrent,
            String signatureData,
            int retryCount
    ) {}

    public record PendingForSigning(
            String invoiceId,
            String mode,
            String companyId,
            String taxIdentifier,
            String invoiceNumber,
            LocalDate invoiceDate,
            BigDecimal vatTotal,
            BigDecimal total,
            String hashCurrent,
            String hashPrevious,
            OffsetDateTime generatedAt
    ) {}

    private ChainRow mapChainRow(ResultSet rs, int rowNum) throws SQLException {
        Date invDate = rs.getDate("invoice_date");
        Timestamp gen = rs.getTimestamp("generated_at");
        return new ChainRow(
                rs.getString("invoice_id"),
                rs.getString("invoice_number"),
                invDate == null ? null : invDate.toLocalDate(),
                rs.getBigDecimal("vat_total"),
                rs.getBigDecimal("total"),
                rs.getString("hash_current"),
                rs.getString("hash_previous"),
                // El TIMESTAMP vuelve como Instant; lo expresamos como
                // OffsetDateTime para que el servicio lo proyecte a
                // Europe/Madrid y el formato del hash coincida.
                gen == null ? null : OffsetDateTime.ofInstant(gen.toInstant(), ZoneOffset.UTC)
        );
    }

    public record ChainRow(
            String invoiceId,
            String invoiceNumber,
            LocalDate invoiceDate,
            BigDecimal vatTotal,
            BigDecimal total,
            String hashCurrent,
            String hashPrevious,
            OffsetDateTime generatedAt
    ) {
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
