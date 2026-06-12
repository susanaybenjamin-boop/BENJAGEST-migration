package com.benjagest.backend.timeclock;

import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a time_clock_events, _corrections, _verifications.
 *
 * El INSERT de un evento es atomico y nunca actualiza filas existentes
 * (RD 8/2019 art. 34.9 — inalterabilidad del fichaje original).
 *
 * Una correccion es una fila nueva en time_clock_corrections que
 * apunta al evento original — el evento sigue como estaba.
 *
 * Un CSV se emite a peticion del trabajador. La columna csv_code lleva
 * UNIQUE para que ningun par de fichajes comparta CSV (la verificacion
 * publica usa el CSV como llave).
 */
@Repository
public class TimeClockRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public TimeClockRepository(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    /**
     * Busca la ficha de empleado activa de un usuario en una empresa.
     * Devuelve {@code Optional.empty()} si el usuario no tiene fila en
     * {@code employees} para esa empresa (típico: el OWNER de la
     * empresa nunca se dio de alta como empleado). La UI usa este
     * resultado para decidir si puede fichar o si tiene que pedir alta.
     */
    public Optional<MyEmployeeRow> findEmployeeByUserAndCompany(String userId, String companyId) {
        return jdbcTemplate.query("""
                SELECT id, full_name
                  FROM employees
                 WHERE user_id = ?
                   AND company_id = ?
                   AND active = TRUE
                 LIMIT 1
                """,
                rs -> rs.next()
                        ? Optional.of(new MyEmployeeRow(rs.getString("id"), rs.getString("full_name")))
                        : Optional.empty(),
                userId, companyId);
    }

    public record MyEmployeeRow(String employeeId, String fullName) {}

    /**
     * TC-CAL — Si el empleado tiene un {@code work_calendar_id}
     * asignado y la fecha de hoy coincide con alguna fila de
     * {@code holidays} de ese calendario, devuelve los datos del
     * festivo para el warning amarillo. Si no hay calendario o no es
     * festivo, devuelve empty.
     */
    public Optional<TimeClockService.HolidayWarning> findTodaysHolidayForEmployee(String employeeId) {
        return jdbcTemplate.query("""
                SELECT h.name AS holiday_name,
                       h.holiday_type,
                       h.scope
                  FROM employees e
                  JOIN holidays h ON h.work_calendar_id = e.work_calendar_id
                 WHERE e.id = ?
                   AND e.work_calendar_id IS NOT NULL
                   AND h.holiday_date = CURRENT_DATE
                 LIMIT 1
                """,
                rs -> rs.next()
                        ? Optional.of(new TimeClockService.HolidayWarning(
                                rs.getString("holiday_name"),
                                rs.getString("holiday_type"),
                                rs.getString("scope")))
                        : Optional.empty(),
                employeeId);
    }

    public void insertEvent(TimeClockEvent event) {
        insertEvent(event, null, null, null);
    }

    /**
     * GEO-FICHAR (V100): inserta con coordenadas + distancia al centro
     * si vienen. Para fichajes pre-V100 los 3 campos son NULL.
     */
    public void insertEvent(TimeClockEvent event,
                              java.math.BigDecimal lat,
                              java.math.BigDecimal lng,
                              Integer geoWarningMeters) {
        jdbcTemplate.update("""
                INSERT INTO time_clock_events (
                    id, company_id, employee_id, customer_id,
                    event_type, event_time, origin, status,
                    lat, lng, geo_warning_meters
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.id(),
                tenantContext.getCurrentCompanyId(),
                event.employeeId(),
                event.customerId(),
                event.eventType(),
                Timestamp.from(event.eventTime()),
                event.origin(),
                event.status() == null ? "VALID" : event.status(),
                lat, lng, geoWarningMeters
        );
    }

    /** GEO-FICHAR — datos geo del centro asignado al empleado. */
    public java.util.Optional<EmployeeWorkCenterGeo> findEmployeeWorkCenterGeo(String employeeId) {
        java.util.List<EmployeeWorkCenterGeo> matches = jdbcTemplate.query("""
                SELECT w.lat, w.lng, w.radio_m, w.geo_policy, w.name
                  FROM employees e
                  JOIN work_centers w ON w.id = e.work_center_id
                 WHERE e.id = ?
                   AND e.company_id = ?
                """, (rs, n) -> new EmployeeWorkCenterGeo(
                        rs.getBigDecimal("lat"),
                        rs.getBigDecimal("lng"),
                        (Integer) rs.getObject("radio_m"),
                        rs.getString("geo_policy"),
                        rs.getString("name")),
                employeeId, tenantContext.getCurrentCompanyId());
        return matches.stream().findFirst();
    }

    public record EmployeeWorkCenterGeo(java.math.BigDecimal lat,
                                          java.math.BigDecimal lng,
                                          Integer radioM,
                                          String geoPolicy,
                                          String centerName) {}

    /**
     * Eventos de la empresa activa entre dos fechas (inclusive). Si
     * employeeId no es null, filtra por trabajador. Pensado para
     * exportar el registro a PDF/CSV de cara a Hacienda / Inspección
     * de Trabajo (RD 8/2019 art. 35.8 — verificación pública).
     */
    public List<TimeClockEvent> findInRange(Instant from, Instant to, String employeeId) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, company_id, employee_id, customer_id,
                       event_type, event_time, origin, status, created_at
                  FROM time_clock_events
                 WHERE company_id = ?
                   AND event_time >= ?
                   AND event_time <= ?
                """);
        if (employeeId != null && !employeeId.isBlank()) {
            sql.append(" AND employee_id = ? ");
        }
        sql.append(" ORDER BY event_time ASC");
        if (employeeId != null && !employeeId.isBlank()) {
            return jdbcTemplate.query(sql.toString(), this::mapEvent,
                    tenantContext.getCurrentCompanyId(),
                    Timestamp.from(from), Timestamp.from(to), employeeId);
        }
        return jdbcTemplate.query(sql.toString(), this::mapEvent,
                tenantContext.getCurrentCompanyId(),
                Timestamp.from(from), Timestamp.from(to));
    }

    /**
     * Devuelve el CSV (Codigo Seguro de Verificacion) asignado a un
     * evento. Lo usa el export para incluir el CSV al lado de cada
     * fila — asi un inspector con el fichero impreso puede entrar
     * al endpoint publico y verificar fila a fila.
     */
    public String findCsvForEvent(String eventId) {
        if (eventId == null) return null;
        return jdbcTemplate.query("""
                SELECT csv_code FROM time_clock_verifications
                 WHERE event_id = ? ORDER BY emitted_at ASC LIMIT 1
                """,
                rs -> rs.next() ? rs.getString("csv_code") : null,
                eventId);
    }

    /**
     * Datos del trabajador para la cabecera del export (nombre + NIF).
     * Si no existe, devuelve "—". Solo lectura, sin filtro de tenant
     * porque ya se garantiza arriba al cruzar con events.
     */
    public String findEmployeeFullName(String employeeId) {
        if (employeeId == null) return null;
        return jdbcTemplate.query(
                "SELECT full_name FROM employees WHERE id = ?",
                rs -> rs.next() ? rs.getString("full_name") : null,
                employeeId);
    }

    public List<TimeClockEvent> findRecentByEmployee(String employeeId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, company_id, employee_id, customer_id,
                       event_type, event_time, origin, status, created_at
                  FROM time_clock_events
                 WHERE company_id = ?
                   AND employee_id = ?
                 ORDER BY event_time DESC
                 LIMIT ?
                """,
                this::mapEvent,
                tenantContext.getCurrentCompanyId(),
                employeeId,
                Math.min(Math.max(limit, 1), 500)
        );
    }

    public Optional<TimeClockEvent> findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, company_id, employee_id, customer_id,
                       event_type, event_time, origin, status, created_at
                  FROM time_clock_events
                 WHERE id = ?
                   AND company_id = ?
                """,
                this::mapEvent, id, tenantContext.getCurrentCompanyId()
        ).stream().findFirst();
    }

    /**
     * Inserta una correccion vinculada a un evento original. NO
     * modifica el original. La cascada de aplicacion (que correccion
     * APROBADA gana cuando hay varias) se hace al leer en service.
     */
    public void insertCorrection(String id, String originalEventId,
                                  String correctionType,
                                  String proposedEventType,
                                  Instant proposedEventTime,
                                  String reason,
                                  String requestedBy) {
        jdbcTemplate.update("""
                INSERT INTO time_clock_corrections (
                    id, company_id, original_event_id, correction_type,
                    proposed_event_type, proposed_event_time, reason,
                    requested_by, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """,
                id,
                tenantContext.getCurrentCompanyId(),
                originalEventId,
                correctionType,
                proposedEventType,
                proposedEventTime == null ? null : Timestamp.from(proposedEventTime),
                reason,
                requestedBy
        );
    }

    /**
     * Emite un CSV (Codigo Seguro de Verificacion) para un evento. El
     * CSV NO es secreto — es un identificador publico que permite a
     * terceros (Inspeccion de Trabajo, otros sistemas) verificar la
     * autenticidad del fichaje sin credenciales.
     */
    public void insertVerification(String id, String eventId,
                                    String csvCode, String issuedTo) {
        jdbcTemplate.update("""
                INSERT INTO time_clock_verifications (
                    id, company_id, event_id, csv_code, issued_to
                ) VALUES (?, ?, ?, ?, ?)
                """,
                id,
                tenantContext.getCurrentCompanyId(),
                eventId,
                csvCode,
                issuedTo
        );
    }

    /**
     * Lookup publico por CSV — no filtra por tenant a proposito.
     * Cualquier verificador externo (sin sesion) puede consultarlo.
     */
    public Optional<TimeClockEvent> findEventByCsv(String csvCode) {
        return jdbcTemplate.query("""
                SELECT e.id, e.company_id, e.employee_id, e.customer_id,
                       e.event_type, e.event_time, e.origin, e.status, e.created_at
                  FROM time_clock_verifications v
                  JOIN time_clock_events e ON e.id = v.event_id
                 WHERE v.csv_code = ?
                   AND v.revoked_at IS NULL
                """, this::mapEvent, csvCode).stream().findFirst();
    }

    private TimeClockEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        Timestamp t = rs.getTimestamp("event_time");
        Timestamp c = rs.getTimestamp("created_at");
        return new TimeClockEvent(
                rs.getString("id"),
                rs.getString("company_id"),
                rs.getString("employee_id"),
                rs.getString("customer_id"),
                rs.getString("event_type"),
                t == null ? null : t.toInstant(),
                rs.getString("origin"),
                rs.getString("status"),
                c == null ? null : c.toInstant()
        );
    }
}
