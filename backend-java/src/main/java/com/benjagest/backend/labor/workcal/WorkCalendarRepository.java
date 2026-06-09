package com.benjagest.backend.labor.workcal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * L3-1 — Repositorio único para {@link WorkCalendar} y sus {@link Holiday}.
 * Mantengo ambas tablas en un solo repo porque siempre se consultan
 * juntas (mostrar/comparar un calendario implica leer sus festivos).
 *
 * <p>Todas las consultas filtran por company_id desde el caller — el
 * tenant context lo aplica el servicio.
 */
@Repository
public class WorkCalendarRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorkCalendarRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ============================================================
    //  work_calendars
    // ============================================================

    /** Calendario activo de la empresa para el año dado (Optional vacío si no hay). */
    public Optional<WorkCalendar> findActiveForCompanyYear(String companyId, int year) {
        List<WorkCalendar> rows = jdbcTemplate.query("""
                SELECT id, company_id, year, region_ccaa, region_municipality,
                       name, active, created_at, updated_at
                  FROM work_calendars
                 WHERE company_id = ? AND year = ? AND active = TRUE
                 LIMIT 1
                """, WORK_CAL_MAPPER, companyId, year);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Listado completo (activos + archivados) para la empresa. Para la UI. */
    public List<WorkCalendar> listByCompany(String companyId) {
        return jdbcTemplate.query("""
                SELECT id, company_id, year, region_ccaa, region_municipality,
                       name, active, created_at, updated_at
                  FROM work_calendars
                 WHERE company_id = ?
                 ORDER BY year DESC, active DESC
                """, WORK_CAL_MAPPER, companyId);
    }

    public Optional<WorkCalendar> findById(String id) {
        List<WorkCalendar> rows = jdbcTemplate.query("""
                SELECT id, company_id, year, region_ccaa, region_municipality,
                       name, active, created_at, updated_at
                  FROM work_calendars
                 WHERE id = ?
                """, WORK_CAL_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void insert(WorkCalendar c) {
        jdbcTemplate.update("""
                INSERT INTO work_calendars
                       (id, company_id, year, region_ccaa, region_municipality,
                        name, active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """,
                c.id(), c.companyId(), c.year(), c.regionCcaa(),
                c.regionMunicipality(), c.name(), c.active());
    }

    public void update(WorkCalendar c) {
        jdbcTemplate.update("""
                UPDATE work_calendars
                   SET region_ccaa = ?, region_municipality = ?, name = ?,
                       active = ?, updated_at = NOW()
                 WHERE id = ?
                """,
                c.regionCcaa(), c.regionMunicipality(), c.name(),
                c.active(), c.id());
    }

    public void delete(String id) {
        // ON DELETE CASCADE elimina los holidays asociados.
        jdbcTemplate.update("DELETE FROM work_calendars WHERE id = ?", id);
    }

    /**
     * Archiva el calendario activo de (empresa, año) si existe, para
     * dejar sitio a uno nuevo. Devuelve el id archivado o null si no
     * había. Usado en operaciones "reemplazar calendario".
     */
    public String archiveActive(String companyId, int year) {
        Optional<WorkCalendar> existing = findActiveForCompanyYear(companyId, year);
        if (existing.isEmpty()) return null;
        jdbcTemplate.update("""
                UPDATE work_calendars
                   SET active = FALSE, updated_at = NOW()
                 WHERE id = ?
                """, existing.get().id());
        return existing.get().id();
    }

    // ============================================================
    //  holidays
    // ============================================================

    /** Festivos de un calendario, ordenados por fecha. */
    public List<Holiday> listByCalendar(String workCalendarId) {
        return jdbcTemplate.query("""
                SELECT id, work_calendar_id, holiday_date, name, scope,
                       is_paid, notes, holiday_type, created_at
                  FROM holidays
                 WHERE work_calendar_id = ?
                 ORDER BY holiday_date ASC
                """, HOLIDAY_MAPPER, workCalendarId);
    }

    public void insertHoliday(Holiday h) {
        jdbcTemplate.update("""
                INSERT INTO holidays
                       (id, work_calendar_id, holiday_date, name, scope,
                        is_paid, notes, holiday_type, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
                """,
                h.id(), h.workCalendarId(), h.holidayDate(), h.name(),
                h.scope(), h.isPaid(), h.notes(),
                h.holidayType() == null ? Holiday.TYPE_FESTIVO : h.holidayType());
    }

    public void deleteHoliday(String id) {
        jdbcTemplate.update("DELETE FROM holidays WHERE id = ?", id);
    }

    /** Reemplaza todos los festivos del calendario por la lista dada. */
    public void replaceHolidays(String workCalendarId, List<Holiday> holidays) {
        jdbcTemplate.update("DELETE FROM holidays WHERE work_calendar_id = ?",
                workCalendarId);
        for (Holiday h : holidays) {
            insertHoliday(h);
        }
    }

    // ============================================================
    //  Mappers
    // ============================================================

    private static final RowMapper<WorkCalendar> WORK_CAL_MAPPER = (rs, i) -> new WorkCalendar(
            rs.getString("id"),
            rs.getString("company_id"),
            rs.getInt("year"),
            rs.getString("region_ccaa"),
            rs.getString("region_municipality"),
            rs.getString("name"),
            rs.getBoolean("active"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    private static final RowMapper<Holiday> HOLIDAY_MAPPER = (rs, i) -> new Holiday(
            rs.getString("id"),
            rs.getString("work_calendar_id"),
            rs.getObject("holiday_date", LocalDate.class),
            rs.getString("name"),
            rs.getString("scope"),
            rs.getBoolean("is_paid"),
            rs.getString("notes"),
            rs.getString("holiday_type"),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
