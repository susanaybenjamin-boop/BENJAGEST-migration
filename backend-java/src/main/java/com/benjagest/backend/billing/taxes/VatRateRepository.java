package com.benjagest.backend.billing.taxes;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a vat_rates filtrado por TenantContext. Cada empresa tiene
 * su propio catalogo.
 */
@Repository
public class VatRateRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public VatRateRepository(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public List<VatRate> findActiveForCurrentCompany(String kindFilter) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, company_id, kind, code, label, percent,
                       is_default, active, notes, legal_text, created_at, updated_at
                  FROM vat_rates
                 WHERE company_id = ?
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        // Por defecto ocultamos los inactive (que estan ahi para
        // historia de facturas pasadas). Si el caller quiere ver TODOS
        // (la pestaña de configuracion los necesita para editar), pasa
        // kindFilter=null y aplica filtro de active=false aparte.
        if (kindFilter != null && !kindFilter.isBlank()) {
            sql.append(" AND kind = ?");
            args.add(kindFilter);
        }
        sql.append(" AND active = TRUE ORDER BY kind, percent DESC");
        return jdbcTemplate.query(sql.toString(), this::mapRate, args.toArray());
    }

    public List<VatRate> findAllForCurrentCompany() {
        return jdbcTemplate.query("""
                SELECT id, company_id, kind, code, label, percent,
                       is_default, active, notes, legal_text, created_at, updated_at
                  FROM vat_rates
                 WHERE company_id = ?
                 ORDER BY kind, active DESC, percent DESC
                """,
                this::mapRate,
                tenantContext.getCurrentCompanyId()
        );
    }

    public Optional<VatRate> findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, company_id, kind, code, label, percent,
                       is_default, active, notes, legal_text, created_at, updated_at
                  FROM vat_rates
                 WHERE id = ? AND company_id = ?
                """, this::mapRate, id, tenantContext.getCurrentCompanyId()).stream().findFirst();
    }

    public void insert(String id, String kind, String code, String label,
                       BigDecimal percent, boolean isDefault, String notes,
                       String legalText) {
        jdbcTemplate.update("""
                INSERT INTO vat_rates (
                    id, company_id, kind, code, label, percent,
                    is_default, active, notes, legal_text
                ) VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)
                """,
                id,
                tenantContext.getCurrentCompanyId(),
                kind, code, label, percent, isDefault, notes, legalText
        );
    }

    public int update(String id, String label, BigDecimal percent,
                       boolean isDefault, boolean active, String notes,
                       String legalText) {
        return jdbcTemplate.update("""
                UPDATE vat_rates
                   SET label = ?, percent = ?, is_default = ?,
                       active = ?, notes = ?, legal_text = ?
                 WHERE id = ? AND company_id = ?
                """,
                label, percent, isDefault, active, notes, legalText,
                id, tenantContext.getCurrentCompanyId()
        );
    }

    /**
     * Garantiza un unico default por (company, kind). Cuando se marca
     * uno como default, los demas del mismo kind pierden la marca.
     */
    public void clearDefaultsExcept(String kind, String exceptId) {
        jdbcTemplate.update("""
                UPDATE vat_rates
                   SET is_default = FALSE
                 WHERE company_id = ?
                   AND kind = ?
                   AND id <> ?
                """,
                tenantContext.getCurrentCompanyId(), kind, exceptId);
    }

    public int delete(String id) {
        return jdbcTemplate.update("""
                DELETE FROM vat_rates
                 WHERE id = ? AND company_id = ?
                """,
                id, tenantContext.getCurrentCompanyId()
        );
    }

    private VatRate mapRate(ResultSet rs, int rowNum) throws SQLException {
        Timestamp c = rs.getTimestamp("created_at");
        Timestamp u = rs.getTimestamp("updated_at");
        return new VatRate(
                rs.getString("id"),
                rs.getString("company_id"),
                rs.getString("kind"),
                rs.getString("code"),
                rs.getString("label"),
                rs.getBigDecimal("percent"),
                rs.getBoolean("is_default"),
                rs.getBoolean("active"),
                rs.getString("notes"),
                c == null ? null : c.toInstant(),
                u == null ? null : u.toInstant(),
                rs.getString("legal_text")
        );
    }
}
