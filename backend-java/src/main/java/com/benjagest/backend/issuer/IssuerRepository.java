package com.benjagest.backend.issuer;

import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Unica clase que habla con la tabla issuers.
 * El resto del backend pasa por aqui — nadie hace SQL directo a issuers.
 *
 * El company_id sale del TenantContext (request-scoped). Hoy lo alimenta
 * el header X-Company-Id; manana lo alimentara el JWT del usuario
 * logueado. La diferencia es transparente para esta clase.
 */
@Repository
public class IssuerRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public IssuerRepository(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    private String currentCompanyId() {
        return tenantContext.getCurrentCompanyId();
    }

    public void insert(String id, IssuerCreateRequest request) {
        jdbcTemplate.update("""
                INSERT INTO issuers (
                    id, company_id, legal_name, tax_identifier,
                    address_line, city, province, postal_code, country,
                    email, phone, iban,
                    registry_information, legal_terms, invoice_footer
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                currentCompanyId(),
                request.legalName().trim(),
                request.taxIdentifier().trim(),
                blankToNull(request.addressLine()),
                blankToNull(request.city()),
                blankToNull(request.province()),
                blankToNull(request.postalCode()),
                StringUtils.hasText(request.country()) ? request.country().trim() : "Espana",
                blankToNull(request.email()),
                blankToNull(request.phone()),
                blankToNull(request.iban()),
                blankToNull(request.registryInformation()),
                blankToNull(request.legalTerms()),
                blankToNull(request.invoiceFooter())
        );
    }

    public int update(String id, IssuerCreateRequest request) {
        return jdbcTemplate.update("""
                UPDATE issuers
                   SET legal_name = ?,
                       tax_identifier = ?,
                       address_line = ?,
                       city = ?,
                       province = ?,
                       postal_code = ?,
                       country = ?,
                       email = ?,
                       phone = ?,
                       iban = ?,
                       registry_information = ?,
                       legal_terms = ?,
                       invoice_footer = ?
                 WHERE id = ?
                   AND company_id = ?
                """,
                request.legalName().trim(),
                request.taxIdentifier().trim(),
                blankToNull(request.addressLine()),
                blankToNull(request.city()),
                blankToNull(request.province()),
                blankToNull(request.postalCode()),
                StringUtils.hasText(request.country()) ? request.country().trim() : "Espana",
                blankToNull(request.email()),
                blankToNull(request.phone()),
                blankToNull(request.iban()),
                blankToNull(request.registryInformation()),
                blankToNull(request.legalTerms()),
                blankToNull(request.invoiceFooter()),
                id,
                currentCompanyId()
        );
    }

    public int softDelete(String id) {
        return jdbcTemplate.update("""
                UPDATE issuers
                   SET active = FALSE
                 WHERE id = ?
                   AND company_id = ?
                """,
                id,
                currentCompanyId()
        );
    }

    public Optional<IssuerResponse> findById(String id) {
        List<IssuerResponse> matches = jdbcTemplate.query("""
                SELECT id, legal_name, tax_identifier,
                       address_line, city, province, postal_code, country,
                       email, phone, iban,
                       registry_information, legal_terms, invoice_footer,
                       active, is_default, created_at, updated_at
                  FROM issuers
                 WHERE id = ?
                   AND company_id = ?
                """,
                this::mapIssuer,
                id,
                currentCompanyId()
        );
        return matches.stream().findFirst();
    }

    public List<IssuerResponse> findAllActive() {
        return jdbcTemplate.query("""
                SELECT id, legal_name, tax_identifier,
                       address_line, city, province, postal_code, country,
                       email, phone, iban,
                       registry_information, legal_terms, invoice_footer,
                       active, is_default, created_at, updated_at
                  FROM issuers
                 WHERE company_id = ?
                   AND active = TRUE
                 ORDER BY is_default DESC, legal_name
                 LIMIT 200
                """,
                this::mapIssuer,
                currentCompanyId()
        );
    }

    public Optional<IssuerResponse> findDefault() {
        List<IssuerResponse> matches = jdbcTemplate.query("""
                SELECT id, legal_name, tax_identifier,
                       address_line, city, province, postal_code, country,
                       email, phone, iban,
                       registry_information, legal_terms, invoice_footer,
                       active, is_default, created_at, updated_at
                  FROM issuers
                 WHERE company_id = ?
                   AND is_default = TRUE
                   AND active = TRUE
                 LIMIT 1
                """,
                this::mapIssuer,
                currentCompanyId()
        );
        return matches.stream().findFirst();
    }

    public void clearDefaultsForCompany() {
        jdbcTemplate.update("""
                UPDATE issuers
                   SET is_default = FALSE
                 WHERE company_id = ?
                   AND is_default = TRUE
                """,
                currentCompanyId()
        );
    }

    public int setDefault(String id) {
        return jdbcTemplate.update("""
                UPDATE issuers
                   SET is_default = TRUE
                 WHERE id = ?
                   AND company_id = ?
                   AND active = TRUE
                """,
                id,
                currentCompanyId()
        );
    }

    private IssuerResponse mapIssuer(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new IssuerResponse(
                rs.getString("id"),
                rs.getString("legal_name"),
                rs.getString("tax_identifier"),
                rs.getString("address_line"),
                rs.getString("city"),
                rs.getString("province"),
                rs.getString("postal_code"),
                rs.getString("country"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("iban"),
                rs.getString("registry_information"),
                rs.getString("legal_terms"),
                rs.getString("invoice_footer"),
                rs.getBoolean("active"),
                rs.getBoolean("is_default"),
                createdAt == null ? null : createdAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant()
        );
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
