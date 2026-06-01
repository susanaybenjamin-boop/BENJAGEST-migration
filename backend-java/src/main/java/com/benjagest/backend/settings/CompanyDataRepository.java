package com.benjagest.backend.settings;

import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Lecturas y escrituras de la fila "companies" de la empresa activa.
 *
 * Aislamiento: el id viene del TenantContext, no del cliente. Asi nadie
 * puede pedir GET /api/settings/company para una empresa distinta de la
 * suya con un id en query string.
 */
@Repository
public class CompanyDataRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public CompanyDataRepository(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public Optional<CompanyDataResponse> findCurrent() {
        List<CompanyDataResponse> matches = jdbcTemplate.query("""
                SELECT id, legal_name, trade_name, tax_identifier, company_type,
                       email, phone, website
                  FROM companies
                 WHERE id = ?
                """, this::mapCompany, tenantContext.getCurrentCompanyId());
        return matches.stream().findFirst();
    }

    public int updateCurrent(CompanyDataUpdateRequest request) {
        return jdbcTemplate.update("""
                UPDATE companies
                   SET legal_name = ?,
                       trade_name = ?,
                       tax_identifier = ?,
                       email = ?,
                       phone = ?,
                       website = ?
                 WHERE id = ?
                """,
                request.legalName().trim(),
                blankToNull(request.tradeName()),
                blankToNull(request.taxIdentifier()),
                blankToNull(request.email()),
                blankToNull(request.phone()),
                blankToNull(request.website()),
                tenantContext.getCurrentCompanyId()
        );
    }

    private CompanyDataResponse mapCompany(ResultSet rs, int rowNum) throws SQLException {
        return new CompanyDataResponse(
                rs.getString("id"),
                rs.getString("legal_name"),
                rs.getString("trade_name"),
                rs.getString("tax_identifier"),
                rs.getString("company_type"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("website")
        );
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
