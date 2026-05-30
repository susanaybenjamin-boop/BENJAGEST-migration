package com.benjagest.backend.issuer;

import com.benjagest.backend.workspace.DemoCompany;
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
 * Hoy todos los emisores se atribuyen a la empresa demo (DemoCompany.ID)
 * porque todavia no hay sesion real con company_id por usuario.
 */
@Repository
public class IssuerRepository {

    private final JdbcTemplate jdbcTemplate;

    public IssuerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
                DemoCompany.ID,
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
                DemoCompany.ID
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
                DemoCompany.ID
        );
    }

    public Optional<IssuerResponse> findById(String id) {
        List<IssuerResponse> matches = jdbcTemplate.query("""
                SELECT id, legal_name, tax_identifier,
                       address_line, city, province, postal_code, country,
                       email, phone, iban,
                       registry_information, legal_terms, invoice_footer,
                       active, created_at, updated_at
                  FROM issuers
                 WHERE id = ?
                   AND company_id = ?
                """,
                this::mapIssuer,
                id,
                DemoCompany.ID
        );
        return matches.stream().findFirst();
    }

    public List<IssuerResponse> findAllActive() {
        return jdbcTemplate.query("""
                SELECT id, legal_name, tax_identifier,
                       address_line, city, province, postal_code, country,
                       email, phone, iban,
                       registry_information, legal_terms, invoice_footer,
                       active, created_at, updated_at
                  FROM issuers
                 WHERE company_id = ?
                   AND active = TRUE
                 ORDER BY legal_name
                 LIMIT 200
                """,
                this::mapIssuer,
                DemoCompany.ID
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
                createdAt == null ? null : createdAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant()
        );
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
