package com.benjagest.backend.settings.owners;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CompanyOwnerRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public CompanyOwnerRepository(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public List<CompanyOwner> findAllForCurrentCompany() {
        return jdbcTemplate.query("""
                SELECT id, company_id, full_name, tax_identifier, role,
                       ownership_percent, ss_regime, appointment_date,
                       termination_date, email, phone, notes, active,
                       created_at, updated_at
                  FROM company_owners
                 WHERE company_id = ?
                 ORDER BY active DESC, ownership_percent DESC, full_name
                """,
                this::mapOwner,
                tenantContext.getCurrentCompanyId()
        );
    }

    public Optional<CompanyOwner> findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, company_id, full_name, tax_identifier, role,
                       ownership_percent, ss_regime, appointment_date,
                       termination_date, email, phone, notes, active,
                       created_at, updated_at
                  FROM company_owners
                 WHERE id = ? AND company_id = ?
                """, this::mapOwner, id, tenantContext.getCurrentCompanyId()).stream().findFirst();
    }

    public void insert(String id, String fullName, String taxIdentifier,
                       String role, BigDecimal pct, String ssRegime,
                       LocalDate appointment, LocalDate termination,
                       String email, String phone, String notes) {
        jdbcTemplate.update("""
                INSERT INTO company_owners (
                    id, company_id, full_name, tax_identifier, role,
                    ownership_percent, ss_regime, appointment_date,
                    termination_date, email, phone, notes, active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                """,
                id, tenantContext.getCurrentCompanyId(),
                fullName, taxIdentifier, role, pct, ssRegime,
                appointment == null ? null : Date.valueOf(appointment),
                termination == null ? null : Date.valueOf(termination),
                email, phone, notes
        );
    }

    public int update(String id, String fullName, String role,
                       BigDecimal pct, String ssRegime,
                       LocalDate appointment, LocalDate termination,
                       String email, String phone, String notes,
                       boolean active) {
        return jdbcTemplate.update("""
                UPDATE company_owners
                   SET full_name = ?, role = ?,
                       ownership_percent = ?, ss_regime = ?,
                       appointment_date = ?, termination_date = ?,
                       email = ?, phone = ?, notes = ?, active = ?
                 WHERE id = ? AND company_id = ?
                """,
                fullName, role, pct, ssRegime,
                appointment == null ? null : Date.valueOf(appointment),
                termination == null ? null : Date.valueOf(termination),
                email, phone, notes, active,
                id, tenantContext.getCurrentCompanyId()
        );
    }

    public int delete(String id) {
        return jdbcTemplate.update("""
                DELETE FROM company_owners
                 WHERE id = ? AND company_id = ?
                """, id, tenantContext.getCurrentCompanyId());
    }

    private CompanyOwner mapOwner(ResultSet rs, int rowNum) throws SQLException {
        Date appoint = rs.getDate("appointment_date");
        Date term = rs.getDate("termination_date");
        Timestamp c = rs.getTimestamp("created_at");
        Timestamp u = rs.getTimestamp("updated_at");
        return new CompanyOwner(
                rs.getString("id"),
                rs.getString("company_id"),
                rs.getString("full_name"),
                rs.getString("tax_identifier"),
                rs.getString("role"),
                rs.getBigDecimal("ownership_percent"),
                rs.getString("ss_regime"),
                appoint == null ? null : appoint.toLocalDate(),
                term == null ? null : term.toLocalDate(),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("notes"),
                rs.getBoolean("active"),
                c == null ? null : c.toInstant(),
                u == null ? null : u.toInstant()
        );
    }
}
