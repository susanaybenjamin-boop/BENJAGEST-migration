package com.benjagest.backend.worklogs;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * JOR-1 — Jornada REAL calculada a partir de los fichajes (time_clock_events).
 *
 * <p>Es "lo que pertenece a fichajes": el empleado ficha y de ahí sale su
 * jornada (horas trabajadas, pausas) por día. NO duplica los eventos ni la
 * cadena hash; solo los AGREGA para revisión.
 *
 * <p>Algoritmo (independiente de los códigos, usa el flag {@code is_work_time}
 * del tipo de evento = "¿está trabajando DESPUÉS de este evento?"): se ordenan
 * los eventos del día y, entre cada par consecutivo, se suma el intervalo como
 * TRABAJADO si el evento anterior dejaba "trabajando", o como PAUSA si no.
 * Seed: IN/BREAK_END → trabajando; OUT/BREAK_START → no.
 */
@Service
public class WorkdayService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public WorkdayService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public List<Workday> compute(LocalDate from, LocalDate to, String employeeId) {
        String companyId = tenant.getCurrentCompanyId();
        StringBuilder sql = new StringBuilder("""
                SELECT e.employee_id, emp.full_name,
                       e.event_time, e.event_type,
                       COALESCE(t.is_work_time, TRUE) AS wt
                  FROM time_clock_events e
                  JOIN employees emp ON emp.id = e.employee_id
                  LEFT JOIN time_clock_event_types t
                         ON t.company_id = e.company_id AND t.code = e.event_type
                 WHERE e.company_id = ?
                   AND (e.status IS NULL OR e.status <> 'VOIDED')
                   AND CAST(e.event_time AS DATE) BETWEEN ? AND ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(companyId); args.add(java.sql.Date.valueOf(from)); args.add(java.sql.Date.valueOf(to));
        if (employeeId != null && !employeeId.isBlank()) {
            sql.append(" AND e.employee_id = ?");
            args.add(employeeId);
        }
        sql.append(" ORDER BY e.employee_id, e.event_time");

        List<Ev> evs = jdbc.query(sql.toString(), (rs, n) -> {
            Ev x = new Ev();
            x.employeeId = rs.getString("employee_id");
            x.employeeName = rs.getString("full_name");
            x.time = rs.getTimestamp("event_time").toInstant();
            x.date = rs.getTimestamp("event_time").toLocalDateTime().toLocalDate();
            x.working = rs.getBoolean("wt");
            return x;
        }, args.toArray());

        // Agrupar por (empleado, día) respetando el orden ya venido por tiempo.
        List<Workday> out = new ArrayList<>();
        int i = 0;
        while (i < evs.size()) {
            String emp = evs.get(i).employeeId;
            LocalDate day = evs.get(i).date;
            int j = i;
            long worked = 0, paused = 0;
            Instant firstIn = null, lastOut = null;
            while (j < evs.size() && evs.get(j).employeeId.equals(emp) && evs.get(j).date.equals(day)) {
                Ev cur = evs.get(j);
                if (firstIn == null && cur.working) firstIn = cur.time;
                if (!cur.working) lastOut = cur.time;
                if (j + 1 < evs.size()
                        && evs.get(j + 1).employeeId.equals(emp) && evs.get(j + 1).date.equals(day)) {
                    long mins = java.time.Duration.between(cur.time, evs.get(j + 1).time).toMinutes();
                    if (mins > 0) { if (cur.working) worked += mins; else paused += mins; }
                }
                j++;
            }
            out.add(new Workday(emp, evs.get(i).employeeName, day,
                    firstIn, lastOut, worked, paused, j - i));
            i = j;
        }
        return out;
    }

    private static final class Ev {
        String employeeId; String employeeName; Instant time; LocalDate date; boolean working;
    }

    public record Workday(
            String employeeId, String employeeName, LocalDate date,
            Instant firstIn, Instant lastOut,
            long workedMinutes, long pauseMinutes, int events
    ) {}

    @RestController
    @RequestMapping("/api/labor/workdays")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class Controller {
        private final WorkdayService service;
        public Controller(WorkdayService service) { this.service = service; }

        @GetMapping
        public List<Workday> list(
                @RequestParam(value = "from", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                @RequestParam(value = "to", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                @RequestParam(value = "employeeId", required = false) String employeeId) {
            LocalDate f = from != null ? from : LocalDate.now().withDayOfMonth(1);
            LocalDate t = to != null ? to : LocalDate.now();
            return service.compute(f, t, employeeId);
        }
    }
}
