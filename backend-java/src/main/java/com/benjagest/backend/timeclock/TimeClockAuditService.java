package com.benjagest.backend.timeclock;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vista de auditoría de fichajes. Devuelve eventos + correcciones +
 * verificaciones unidas cronológicamente para una empresa, con filtros
 * por rango de fecha, empleado y tipo.
 *
 * Se usa desde:
 *   1) UI sub-tab "Auditoría" del módulo Personal del empresario.
 *   2) Mismo endpoint para asesoría operando como cliente (TenantContext
 *      ya cambia con X-Company-Id).
 *   3) Generación del PDF/CSV exportable (TC-EXPORT) reusa esto.
 *
 * Cumple las exigencias del RD 8/2019 (art. 34.9 inalterabilidad,
 * art. 35.8 verificación) y prepara el formato para el nuevo RD 2026
 * (acceso telemático con datos completos por trabajador).
 */
@Service
public class TimeClockAuditService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public TimeClockAuditService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    /**
     * Lista eventos en el rango [from, to] ambos inclusive, filtrados
     * opcionalmente por empleado y tipo. Para cada evento añade:
     *   - employee_name (resolved)
     *   - CSV de verificación (si lo tiene)
     *   - flags si tiene correcciones (correctedAt + correctionReason)
     */
    public List<AuditEntry> query(LocalDate from, LocalDate to,
                                    String employeeId, String eventType) {
        if (from == null) from = LocalDate.now().minusMonths(1);
        if (to == null) to = LocalDate.now();
        Instant fromInst = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInst = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        StringBuilder sql = new StringBuilder("""
                SELECT e.id, e.employee_id, emp.full_name AS employee_name,
                       e.event_type, e.event_time, e.origin, e.status,
                       e.created_at,
                       v.csv_code AS csv,
                       (
                         SELECT COUNT(*) FROM time_clock_corrections c
                          WHERE c.original_event_id = e.id
                            AND c.company_id = e.company_id
                       ) AS correction_count,
                       (
                         SELECT MAX(c.created_at) FROM time_clock_corrections c
                          WHERE c.original_event_id = e.id
                            AND c.company_id = e.company_id
                       ) AS last_correction_at
                  FROM time_clock_events e
                  JOIN employees emp ON emp.id = e.employee_id
                  LEFT JOIN time_clock_verifications v ON v.event_id = e.id
                 WHERE e.company_id = ?
                   AND e.event_time >= ?
                   AND e.event_time < ?
                """);

        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        args.add(Timestamp.from(fromInst));
        args.add(Timestamp.from(toInst));

        if (StringUtils.hasText(employeeId)) {
            sql.append(" AND e.employee_id = ?");
            args.add(employeeId);
        }
        if (StringUtils.hasText(eventType)) {
            sql.append(" AND e.event_type = ?");
            args.add(eventType.toUpperCase());
        }
        sql.append(" ORDER BY e.event_time DESC");

        return jdbcTemplate.query(sql.toString(), this::mapEntry, args.toArray());
    }

    /**
     * Resumen agregado por empleado para el rango: total eventos,
     * fichaje IN más antiguo y más reciente, número de pausas, número
     * de correcciones, indicador "tiene incidencia" (pausas sin
     * cierre, OUT antes de IN, etc).
     *
     * Usado por la pestaña Auditoría para resaltar empleados con
     * cosas raras y por el export PDF para el resumen cabecera.
     */
    public List<AuditSummary> summaryByEmployee(LocalDate from, LocalDate to) {
        if (from == null) from = LocalDate.now().minusMonths(1);
        if (to == null) to = LocalDate.now();
        Instant fromInst = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInst = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        return jdbcTemplate.query("""
                SELECT e.employee_id, emp.full_name AS employee_name,
                       COUNT(*) AS total_events,
                       MIN(e.event_time) AS first_event,
                       MAX(e.event_time) AS last_event,
                       SUM(CASE WHEN t.is_pause = TRUE THEN 1 ELSE 0 END) AS pauses,
                       SUM(CASE WHEN e.event_type = 'IN' THEN 1 ELSE 0 END) AS ins,
                       SUM(CASE WHEN e.event_type = 'OUT' THEN 1 ELSE 0 END) AS outs,
                       (
                         SELECT COUNT(*) FROM time_clock_corrections c
                          WHERE c.company_id = e.company_id
                            AND c.original_event_id IN (
                                SELECT id FROM time_clock_events e2
                                 WHERE e2.employee_id = e.employee_id
                                   AND e2.event_time >= ? AND e2.event_time < ?)
                       ) AS corrections
                  FROM time_clock_events e
                  JOIN employees emp ON emp.id = e.employee_id
                  LEFT JOIN time_clock_event_types t
                         ON t.code = e.event_type AND t.company_id = e.company_id
                 WHERE e.company_id = ?
                   AND e.event_time >= ?
                   AND e.event_time < ?
                 GROUP BY e.employee_id, emp.full_name
                 ORDER BY emp.full_name
                """, this::mapSummary,
                Timestamp.from(fromInst), Timestamp.from(toInst),
                tenantContext.getCurrentCompanyId(),
                Timestamp.from(fromInst), Timestamp.from(toInst));
    }

    private AuditEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        Timestamp t = rs.getTimestamp("event_time");
        Timestamp c = rs.getTimestamp("created_at");
        Timestamp lc = rs.getTimestamp("last_correction_at");
        return new AuditEntry(
                rs.getString("id"),
                rs.getString("employee_id"),
                rs.getString("employee_name"),
                rs.getString("event_type"),
                t == null ? null : t.toInstant(),
                rs.getString("origin"),
                rs.getString("status"),
                c == null ? null : c.toInstant(),
                rs.getString("csv"),
                rs.getInt("correction_count"),
                lc == null ? null : lc.toInstant()
        );
    }

    private AuditSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        Timestamp fe = rs.getTimestamp("first_event");
        Timestamp le = rs.getTimestamp("last_event");
        int ins = rs.getInt("ins");
        int outs = rs.getInt("outs");
        boolean hasIncidence = Math.abs(ins - outs) > 0
                || rs.getInt("corrections") > 0;
        return new AuditSummary(
                rs.getString("employee_id"),
                rs.getString("employee_name"),
                rs.getInt("total_events"),
                fe == null ? null : fe.toInstant(),
                le == null ? null : le.toInstant(),
                rs.getInt("pauses"),
                ins, outs,
                rs.getInt("corrections"),
                hasIncidence
        );
    }

    public record AuditEntry(
            String id, String employeeId, String employeeName,
            String eventType, Instant eventTime,
            String origin, String status, Instant createdAt,
            String csv, int correctionCount, Instant lastCorrectionAt
    ) {}

    public record AuditSummary(
            String employeeId, String employeeName,
            int totalEvents, Instant firstEvent, Instant lastEvent,
            int pauses, int ins, int outs, int corrections,
            boolean hasIncidence
    ) {}

    @RestController
    @RequestMapping("/api/timeclock/audit")
    @RequiresModule("time-clock")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class AuditController {
        private final TimeClockAuditService service;
        public AuditController(TimeClockAuditService service) { this.service = service; }

        @GetMapping
        public List<AuditEntry> query(
                @RequestParam(value = "from", required = false) String from,
                @RequestParam(value = "to", required = false) String to,
                @RequestParam(value = "employeeId", required = false) String employeeId,
                @RequestParam(value = "eventType", required = false) String eventType) {
            LocalDate f = from == null || from.isBlank() ? null : LocalDate.parse(from);
            LocalDate tt = to == null || to.isBlank() ? null : LocalDate.parse(to);
            return service.query(f, tt, employeeId, eventType);
        }

        @GetMapping("/summary")
        public List<AuditSummary> summary(
                @RequestParam(value = "from", required = false) String from,
                @RequestParam(value = "to", required = false) String to) {
            LocalDate f = from == null || from.isBlank() ? null : LocalDate.parse(from);
            LocalDate tt = to == null || to.isBlank() ? null : LocalDate.parse(to);
            return service.summaryByEmployee(f, tt);
        }
    }
}
