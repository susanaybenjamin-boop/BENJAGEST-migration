package com.benjagest.backend.billing.series;

import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a invoice_series. Aislamiento por TenantContext (cada empresa
 * solo ve y modifica sus series).
 *
 * El metodo findByIdForUpdate hace SELECT ... FOR UPDATE: bloquea la
 * fila durante la transaccion para que dos llamadas concurrentes a
 * claimNextNumber no emitan el mismo correlativo (esto es la pieza
 * critica de cumplimiento — duplicar un numero de factura legal
 * arrastra una mancha legal que cuesta meses limpiar).
 */
@Repository
public class SeriesRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public SeriesRepository(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public void insert(String id, String code, String invoiceKind, String numberingType,
                       String formatTemplate, int nextNumber, Integer currentYear) {
        jdbcTemplate.update("""
                INSERT INTO invoice_series (
                    id, company_id, code, invoice_kind, numbering_type,
                    format_template, next_number, current_year
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                tenantContext.getCurrentCompanyId(),
                code,
                invoiceKind,
                numberingType,
                formatTemplate,
                nextNumber,
                currentYear
        );
    }

    public int update(String id, String code, String invoiceKind, String numberingType,
                      String formatTemplate, boolean locked) {
        return jdbcTemplate.update("""
                UPDATE invoice_series
                   SET code = ?,
                       invoice_kind = ?,
                       numbering_type = ?,
                       format_template = ?,
                       locked = ?
                 WHERE id = ?
                   AND company_id = ?
                """,
                code,
                invoiceKind,
                numberingType,
                formatTemplate,
                locked,
                id,
                tenantContext.getCurrentCompanyId()
        );
    }

    public int softDelete(String id) {
        return jdbcTemplate.update("""
                UPDATE invoice_series
                   SET active = FALSE
                 WHERE id = ?
                   AND company_id = ?
                """,
                id,
                tenantContext.getCurrentCompanyId()
        );
    }

    public List<Series> findAllActive() {
        return jdbcTemplate.query("""
                SELECT id, company_id, code, invoice_kind, numbering_type,
                       format_template, next_number, current_year,
                       locked, active, created_at, updated_at
                  FROM invoice_series
                 WHERE company_id = ?
                   AND active = TRUE
                 ORDER BY code
                """,
                this::mapSeries,
                tenantContext.getCurrentCompanyId()
        );
    }

    public Optional<Series> findById(String id) {
        List<Series> matches = jdbcTemplate.query("""
                SELECT id, company_id, code, invoice_kind, numbering_type,
                       format_template, next_number, current_year,
                       locked, active, created_at, updated_at
                  FROM invoice_series
                 WHERE id = ?
                   AND company_id = ?
                """,
                this::mapSeries,
                id,
                tenantContext.getCurrentCompanyId()
        );
        return matches.stream().findFirst();
    }

    /**
     * Lee la serie con bloqueo de fila para emitir el siguiente numero
     * de forma atomica. Debe llamarse dentro de @Transactional para que
     * el lock se libere al confirmar; si no, se queda colgado.
     *
     * Si la serie no existe o no pertenece a la empresa actual, devuelve
     * vacio (el Service lo traduce a 404).
     */
    public Optional<Series> findByIdForUpdate(String id) {
        List<Series> matches = jdbcTemplate.query("""
                SELECT id, company_id, code, invoice_kind, numbering_type,
                       format_template, next_number, current_year,
                       locked, active, created_at, updated_at
                  FROM invoice_series
                 WHERE id = ?
                   AND company_id = ?
                   AND active = TRUE
                 FOR UPDATE
                """,
                this::mapSeries,
                id,
                tenantContext.getCurrentCompanyId()
        );
        return matches.stream().findFirst();
    }

    public int updateCounter(String id, int newNextNumber, Integer newCurrentYear) {
        return jdbcTemplate.update("""
                UPDATE invoice_series
                   SET next_number = ?,
                       current_year = ?
                 WHERE id = ?
                   AND company_id = ?
                """,
                newNextNumber,
                newCurrentYear,
                id,
                tenantContext.getCurrentCompanyId()
        );
    }

    private Series mapSeries(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        Integer currentYear = rs.getInt("current_year");
        if (rs.wasNull()) {
            currentYear = null;
        }
        return new Series(
                rs.getString("id"),
                rs.getString("company_id"),
                rs.getString("code"),
                rs.getString("invoice_kind"),
                rs.getString("numbering_type"),
                rs.getString("format_template"),
                rs.getInt("next_number"),
                currentYear,
                rs.getBoolean("locked"),
                rs.getBoolean("active"),
                createdAt == null ? null : createdAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant()
        );
    }
}
