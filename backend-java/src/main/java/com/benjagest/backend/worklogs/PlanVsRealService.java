package com.benjagest.backend.worklogs;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import com.benjagest.backend.timeclock.ScheduleFichajeService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * JOR-4 — Comparación PLANIFICADO vs REAL por empleado y día.
 *
 * <p>Informe DESCRIPTIVO (no opina): para cada empleado-día del rango cruza los
 * minutos de trabajo PLANIFICADOS (suma de los bloques WORK de la plantilla de
 * horario vigente, JOR-2, vía {@link ScheduleFichajeService#scheduledBlocksForDay})
 * con los minutos REALES trabajados ({@link WorkdayService}, JOR-1, a partir de los
 * fichajes) y muestra la diferencia. La interpretación (tolerancias, festivos,
 * excepciones de calendario) queda fuera a propósito: es el sub-ítem aparte
 * "excepciones de calendario por fecha".
 *
 * <p>Se listan los días donde hay ALGO que comparar: planificado &gt; 0 o real &gt; 0.
 * Un día con plan y sin fichaje (ausencia) o con fichaje sin plan (trabajó sin
 * horario asignado) aparece como diferencia visible.
 *
 * <p>Tenant-scoped: reusa servicios que ya filtran por la empresa activa.
 */
@Service
public class PlanVsRealService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final WorkdayService workdays;
    private final ScheduleFichajeService schedule;

    public PlanVsRealService(JdbcTemplate jdbc, TenantContext tenant,
                             WorkdayService workdays, ScheduleFichajeService schedule) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.workdays = workdays;
        this.schedule = schedule;
    }

    public List<Row> compare(LocalDate from, LocalDate to, String employeeId) {
        // 1) Real: minutos trabajados por (empleado, día) desde los fichajes.
        Map<String, Long> workedByKey = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (WorkdayService.Workday wd : workdays.compute(from, to, employeeId)) {
            workedByKey.put(key(wd.employeeId(), wd.date()), wd.workedMinutes());
            names.putIfAbsent(wd.employeeId(), wd.employeeName());
        }

        // 2) Empleados con horario asignado que solapa el rango (aunque no fichen:
        //    un día planificado sin fichaje es una ausencia que queremos ver).
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT a.employee_id, emp.full_name
                  FROM work_schedule_assignments a
                  JOIN employees emp ON emp.id = a.employee_id
                 WHERE a.company_id = ?
                   AND a.effective_from <= ?
                   AND (a.effective_to IS NULL OR a.effective_to >= ?)
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenant.getCurrentCompanyId());
        args.add(java.sql.Date.valueOf(to));
        args.add(java.sql.Date.valueOf(from));
        if (employeeId != null && !employeeId.isBlank()) {
            sql.append(" AND a.employee_id = ?");
            args.add(employeeId);
        }
        jdbc.query(sql.toString(), rs -> {
            names.putIfAbsent(rs.getString("employee_id"), rs.getString("full_name"));
        }, args.toArray());

        // 3) Para cada empleado y día del rango: planificado vs real.
        List<Row> out = new ArrayList<>();
        for (Map.Entry<String, String> emp : names.entrySet()) {
            String empId = emp.getKey();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                long planned = plannedWorkMinutes(empId, d);
                long worked = workedByKey.getOrDefault(key(empId, d), 0L);
                if (planned == 0 && worked == 0) continue;
                out.add(new Row(empId, emp.getValue(), d, planned, worked, worked - planned));
            }
        }
        out.sort((a, b) -> {
            int c = a.employeeName() == null ? 0
                    : a.employeeName().compareToIgnoreCase(b.employeeName() == null ? "" : b.employeeName());
            return c != 0 ? c : a.date().compareTo(b.date());
        });
        return out;
    }

    /** Minutos de los bloques WORK de la plantilla vigente del empleado ese día. */
    private long plannedWorkMinutes(String employeeId, LocalDate day) {
        long total = 0;
        for (ScheduleFichajeService.DayBlock b : schedule.scheduledBlocksForDay(employeeId, day)) {
            if (!"WORK".equalsIgnoreCase(b.type())) continue;
            try {
                LocalTime s = LocalTime.parse(b.start());
                LocalTime e = LocalTime.parse(b.end());
                long mins = java.time.Duration.between(s, e).toMinutes();
                if (mins > 0) total += mins;
            } catch (Exception ignored) { /* hora mal formada: la ignoramos */ }
        }
        return total;
    }

    private static String key(String employeeId, LocalDate date) {
        return employeeId + "|" + date;
    }

    public record Row(
            String employeeId, String employeeName, LocalDate date,
            long plannedMinutes, long workedMinutes, long diffMinutes
    ) {}

    @RestController
    @RequestMapping("/api/labor/plan-vs-real")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class Controller {
        private final PlanVsRealService service;

        public Controller(PlanVsRealService service) { this.service = service; }

        @GetMapping
        public List<Row> list(
                @RequestParam(value = "from", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                @RequestParam(value = "to", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                @RequestParam(value = "employeeId", required = false) String employeeId) {
            LocalDate f = from != null ? from : LocalDate.now().withDayOfMonth(1);
            LocalDate t = to != null ? to : LocalDate.now();
            return service.compare(f, t, employeeId);
        }
    }
}
