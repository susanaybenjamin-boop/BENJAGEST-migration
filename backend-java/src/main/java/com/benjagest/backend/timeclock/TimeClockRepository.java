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

    public void insertEvent(TimeClockEvent event) {
        jdbcTemplate.update("""
                INSERT INTO time_clock_events (
                    id, company_id, employee_id, customer_id,
                    event_type, event_time, origin, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.id(),
                tenantContext.getCurrentCompanyId(),
                event.employeeId(),
                event.customerId(),
                event.eventType(),
                Timestamp.from(event.eventTime()),
                event.origin(),
                event.status() == null ? "VALID" : event.status()
        );
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
