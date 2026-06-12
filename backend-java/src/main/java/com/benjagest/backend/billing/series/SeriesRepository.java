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

    /**
     * Busca una serie con el codigo dado en la empresa actual que este
     * soft-deleted. Permite "reactivarla" en lugar de fallar con 1062
     * cuando el usuario hace un ciclo borrar+crear con el mismo codigo
     * (el UNIQUE company_id+code no distingue active=FALSE).
     */
    public Optional<Series> findInactiveByCode(String code) {
        List<Series> matches = jdbcTemplate.query("""
                SELECT id, company_id, expedited_by_company_id, code, invoice_kind, numbering_type,
                       format_template, next_number, current_year,
                       locked, active, created_at, updated_at
                  FROM invoice_series
                 WHERE code = ?
                   AND company_id = ?
                   AND active = FALSE
                """,
                this::mapSeries,
                code,
                tenantContext.getCurrentCompanyId()
        );
        return matches.stream().findFirst();
    }

    /**
     * Marca como active=TRUE una serie dormida y rescribe sus campos
     * con los del nuevo POST. Conserva el id original (asi la FK desde
     * cualquier sales_invoices.series_id que apuntase a esta serie
     * vieja sigue siendo valida — la "antigua" vida de la serie queda
     * resucitada bajo los nuevos parametros).
     */
    public int reactivateAndUpdate(String id, String code, String invoiceKind, String numberingType,
                                    String formatTemplate, int nextNumber, Integer currentYear) {
        return jdbcTemplate.update("""
                UPDATE invoice_series
                   SET code = ?,
                       invoice_kind = ?,
                       numbering_type = ?,
                       format_template = ?,
                       next_number = ?,
                       current_year = ?,
                       locked = FALSE,
                       active = TRUE
                 WHERE id = ?
                   AND company_id = ?
                """,
                code,
                invoiceKind,
                numberingType,
                formatTemplate,
                nextNumber,
                currentYear,
                id,
                tenantContext.getCurrentCompanyId()
        );
    }

    public List<Series> findAllActive() {
        return jdbcTemplate.query("""
                SELECT id, company_id, expedited_by_company_id, code, invoice_kind, numbering_type,
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

    /**
     * Devuelve la serie activa cuyo invoice_kind coincide con el
     * solicitado para la empresa actual. Usado por el server para
     * resolver "que serie toca" segun el tipo de la factura
     * (NORMAL → STANDARD, PROFORMA → PROFORMA, RECTIFYING → RECTIFYING)
     * sin que el cliente tenga que mandar series_id explicito.
     *
     * Si la empresa tiene varias series del mismo kind (improbable, pero
     * posible en SQL), devuelve la primera por code alfabetico. La UI
     * de seed (V16) garantiza unicidad logica para PROF/RECT.
     */
    public Optional<Series> findActiveByKind(String invoiceKind) {
        List<Series> matches = jdbcTemplate.query("""
                SELECT id, company_id, expedited_by_company_id, code, invoice_kind, numbering_type,
                       format_template, next_number, current_year,
                       locked, active, created_at, updated_at
                  FROM invoice_series
                 WHERE invoice_kind = ?
                   AND company_id = ?
                   AND active = TRUE
                 ORDER BY code
                 LIMIT 1
                """,
                this::mapSeries,
                invoiceKind,
                tenantContext.getCurrentCompanyId()
        );
        return matches.stream().findFirst();
    }

    public Optional<Series> findById(String id) {
        List<Series> matches = jdbcTemplate.query("""
                SELECT id, company_id, expedited_by_company_id, code, invoice_kind, numbering_type,
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
                SELECT id, company_id, expedited_by_company_id, code, invoice_kind, numbering_type,
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

    /**
     * Cuenta cuantas facturas VALIDATED tiene esta serie en el ano
     * indicado. Lo usa SeriesService para decidir si la serie esta
     * bloqueada (>=1 validada en el ano actual = no se puede cambiar
     * formato ni codigo hasta cierre).
     */
    public long countValidatedInYear(String seriesId, int year) {
        Long n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM sales_invoices
                 WHERE series_id = ?
                   AND company_id = ?
                   AND status = 'VALIDATED'
                   AND YEAR(invoice_date) = ?
                """,
                Long.class,
                seriesId,
                tenantContext.getCurrentCompanyId(),
                year
        );
        return n == null ? 0 : n;
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
                rs.getString("expedited_by_company_id"),
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

    // ====================================================================
    //  TPB-2 — Series expedidas por tercero (RD 1619/2012 art. 6.1.b)
    // ====================================================================

    /**
     * Busca la serie TPB activa para el par (clientCompanyId, advisoryCompanyId).
     * Usado sin TenantContext porque la asesoría puede consultarla
     * estando "actuando como" el cliente.
     */
    public Optional<Series> findTpbSeries(String clientCompanyId, String advisoryCompanyId) {
        List<Series> matches = jdbcTemplate.query("""
                SELECT id, company_id, expedited_by_company_id, code, invoice_kind,
                       numbering_type, format_template, next_number, current_year,
                       locked, active, created_at, updated_at
                  FROM invoice_series
                 WHERE company_id = ?
                   AND expedited_by_company_id = ?
                   AND active = TRUE
                   AND invoice_kind = 'STANDARD'
                 ORDER BY created_at DESC
                 LIMIT 1
                """, this::mapSeries, clientCompanyId, advisoryCompanyId);
        return matches.stream().findFirst();
    }

    /**
     * Inserta una serie TPB con companyId y expeditedByCompanyId explícitos
     * (sin TenantContext, idéntico patrón a otros inserts "for company").
     */
    public void insertForCompany(String id, String clientCompanyId,
                                   String advisoryCompanyId, String code,
                                   String invoiceKind, String numberingType,
                                   String formatTemplate, int nextNumber,
                                   Integer currentYear) {
        jdbcTemplate.update("""
                INSERT INTO invoice_series (
                    id, company_id, expedited_by_company_id, code, invoice_kind,
                    numbering_type, format_template, next_number, current_year
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, clientCompanyId, advisoryCompanyId, code, invoiceKind,
                numberingType, formatTemplate, nextNumber, currentYear);
    }
}
