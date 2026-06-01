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
 * Lee y escribe los tres campos VeriFactu que viven en `companies`
 * (introducidos en V13). LEFT JOIN a digital_certificates para
 * devolver el alias del certificado de forma directa.
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
                SELECT c.verifactu_mode AS mode,
                       c.verifactu_certificate_id AS certificate_id,
                       cert.alias AS certificate_alias,
                       c.invoice_footer_template AS invoice_footer_template
                  FROM companies c
                  LEFT JOIN digital_certificates cert ON cert.id = c.verifactu_certificate_id
                 WHERE c.id = ?
                """, this::mapConfig, tenantContext.getCurrentCompanyId());
        return matches.stream().findFirst();
    }

    public int update(String mode, String certificateId, String invoiceFooterTemplate) {
        return jdbcTemplate.update("""
                UPDATE companies
                   SET verifactu_mode = ?,
                       verifactu_certificate_id = ?,
                       invoice_footer_template = ?
                 WHERE id = ?
                """,
                mode,
                blankToNull(certificateId),
                blankToNull(invoiceFooterTemplate),
                tenantContext.getCurrentCompanyId()
        );
    }

    private VerifactuConfig mapConfig(ResultSet rs, int rowNum) throws SQLException {
        return new VerifactuConfig(
                rs.getString("mode"),
                rs.getString("certificate_id"),
                rs.getString("certificate_alias"),
                rs.getString("invoice_footer_template")
        );
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
