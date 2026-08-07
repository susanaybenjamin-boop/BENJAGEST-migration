package com.benjagest.backend.billing.verifactu;

import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Lee y escribe los cuatro campos VeriFactu que viven en `companies`
 * (V13 introdujo `verifactu_mode`/`certificate_id`/`invoice_footer_template`,
 * V17 anyade `verifactu_modality`). LEFT JOIN a digital_certificates
 * para devolver el alias del certificado de forma directa.
 */
@Repository
public class VerifactuConfigRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public VerifactuConfigRepository(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public Optional<VerifactuConfig> findCurrent() {
        List<VerifactuConfig> matches = jdbcTemplate.query("""
                SELECT c.verifactu_modality AS modality,
                       c.verifactu_mode AS mode,
                       c.verifactu_certificate_id AS certificate_id,
                       cert.alias AS certificate_alias,
                       c.invoice_footer_template AS invoice_footer_template,
                       c.invoice_storage_root AS invoice_storage_root,
                       c.verifactu_print_qr AS print_qr,
                       c.tax_identifier AS tax_identifier
                  FROM companies c
                  LEFT JOIN digital_certificates cert ON cert.id = c.verifactu_certificate_id
                 WHERE c.id = ?
                """, this::mapConfig, tenantContext.getCurrentCompanyId());
        return matches.stream().findFirst();
    }

    public int update(String modality, String mode, String certificateId,
                       String invoiceFooterTemplate, String invoiceStorageRoot,
                       boolean printQr) {
        return jdbcTemplate.update("""
                UPDATE companies
                   SET verifactu_modality = ?,
                       verifactu_mode = ?,
                       verifactu_certificate_id = ?,
                       invoice_footer_template = ?,
                       invoice_storage_root = ?,
                       verifactu_print_qr = ?
                 WHERE id = ?
                """,
                modality,
                mode,
                blankToNull(certificateId),
                blankToNull(invoiceFooterTemplate),
                blankToNull(invoiceStorageRoot),
                printQr,
                tenantContext.getCurrentCompanyId()
        );
    }

    private VerifactuConfig mapConfig(ResultSet rs, int rowNum) throws SQLException {
        return new VerifactuConfig(
                rs.getString("modality"),
                rs.getString("mode"),
                rs.getString("certificate_id"),
                rs.getString("certificate_alias"),
                rs.getString("invoice_footer_template"),
                rs.getString("invoice_storage_root"),
                rs.getBoolean("print_qr"),
                VerifactuDates.qrOptOutDeadline(rs.getString("tax_identifier")).toString()
        );
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
