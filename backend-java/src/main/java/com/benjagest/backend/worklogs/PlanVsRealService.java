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
    private final com.benjagest.backend.auth.CurrentUserService currentUser;

    public PlanVsRealService(JdbcTemplate jdbc, TenantContext tenant,
                             WorkdayService workdays, ScheduleFichajeService schedule,
                             com.benjagest.backend.auth.CurrentUserService currentUser) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.workdays = workdays;
        this.schedule = schedule;
        this.currentUser = currentUser;
    }

    public List<Row> compare(LocalDate from, LocalDate to, String employeeId) {
        // 1) Real: minutos trabajados por (empleado, día) desde los fichajes.
        Map<String, Long> workedByKey = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (WorkdayService.Workday wd : workdays.compute(from, to, employeeId)) {
            workedByKey.put(key(wd.employeeId(), wd.date()), wd.workedMinutes());
            names.putIfAbsent(wd.employeeId(), wd.employeeName());
        }

        // 1.bis) Festivos/cierres del calendario laboral activo de la empresa en el
        // rango: esos días el planificado es 0 (no se programa trabajo). Evita
        // falsos "trabajó de menos" en festivos. AJUSTE y matices por centro
        // quedan para el sub-ítem "excepciones de calendario".
        java.util.Set<LocalDate> holidays = loadHolidays(from, to);

        // 1.ter) Días (empleado, fecha) que el admin ya revisó y dio por bueno
        // (FICHA-REVIEW). Se marcan en cada Row para que la UI no los resalte.
        java.util.Set<String> reviewedKeys = loadReviewedDays(from, to);

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
                boolean holiday = holidays.contains(d);
                long planned = holiday ? 0 : plannedWorkMinutes(empId, d);
                String k = key(empId, d);
                long worked = workedByKey.getOrDefault(k, 0L);
                if (planned == 0 && worked == 0) continue;
                out.add(new Row(empId, emp.getValue(), d, planned, worked, worked - planned,
                        holiday, reviewedKeys.contains(k)));
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

    /** Fechas festivo/cierre del calendario activo de la empresa en el rango. */
    private java.util.Set<LocalDate> loadHolidays(LocalDate from, LocalDate to) {
        java.util.Set<LocalDate> out = new java.util.HashSet<>();
        jdbc.query("""
                SELECT h.holiday_date
                  FROM holidays h
                  JOIN work_calendars c ON c.id = h.work_calendar_id
                 WHERE c.company_id = ?
                   AND c.active = TRUE
                   AND c.year BETWEEN ? AND ?
                   AND h.holiday_type IN ('FESTIVO', 'CIERRE')
                   AND h.holiday_date BETWEEN ? AND ?
                """, rs -> {
                    LocalDate d = rs.getObject("holiday_date", LocalDate.class);
                    if (d != null) out.add(d);
                },
                tenant.getCurrentCompanyId(), from.getYear(), to.getYear(),
                java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        return out;
    }

    private static String key(String employeeId, LocalDate date) {
        return employeeId + "|" + date;
    }

    public record Row(
            String employeeId, String employeeName, LocalDate date,
            long plannedMinutes, long workedMinutes, long diffMinutes,
            boolean holiday, boolean reviewed
    ) {}

    /** (empleado|fecha) que el admin ha revisado y dado por bueno en el rango. */
    private java.util.Set<String> loadReviewedDays(LocalDate from, LocalDate to) {
        java.util.Set<String> out = new java.util.HashSet<>();
        jdbc.query("""
                SELECT employee_id, review_date FROM time_clock_day_reviews
                 WHERE company_id = ? AND review_date >= ? AND review_date <= ?
                """, rs -> {
            LocalDate d = rs.getObject("review_date", LocalDate.class);
            out.add(key(rs.getString("employee_id"), d));
        }, tenant.getCurrentCompanyId(), java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        return out;
    }

    /** FICHA-REVIEW — marca (empleado, día) como revisado y dado por bueno (upsert). */
    @org.springframework.transaction.annotation.Transactional
    public void review(String employeeId, LocalDate date, String note) {
        String userId;
        try {
            userId = currentUser.require().userId();
        } catch (RuntimeException ex) {
            userId = null;
        }
        jdbc.update("""
                INSERT INTO time_clock_day_reviews
                    (id, company_id, employee_id, review_date, reviewed_by_user_id, note)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE reviewed_by_user_id = VALUES(reviewed_by_user_id),
                                        reviewed_at = CURRENT_TIMESTAMP,
                                        note = VALUES(note)
                """,
                java.util.UUID.randomUUID().toString(), tenant.getCurrentCompanyId(),
                employeeId, java.sql.Date.valueOf(date), userId,
                note == null || note.isBlank() ? null : note.trim());
    }

    /** FICHA-REVIEW — quita la marca de revisado de (empleado, día). */
    @org.springframework.transaction.annotation.Transactional
    public void unreview(String employeeId, LocalDate date) {
        jdbc.update("""
                DELETE FROM time_clock_day_reviews
                 WHERE company_id = ? AND employee_id = ? AND review_date = ?
                """, tenant.getCurrentCompanyId(), employeeId, java.sql.Date.valueOf(date));
    }

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

        /** Dar por bueno (o quitar) un día de fichaje. Solo gestión, no empleado. */
        @org.springframework.web.bind.annotation.PostMapping("/review")
        @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
        public java.util.Map<String, Object> review(
                @org.springframework.web.bind.annotation.RequestBody ReviewRequest req) {
            LocalDate d = LocalDate.parse(req.date());
            if (req.reviewed()) {
                service.review(req.employeeId(), d, req.note());
            } else {
                service.unreview(req.employeeId(), d);
            }
            return java.util.Map.of("ok", true);
        }
    }

    public record ReviewRequest(String employeeId, String date, boolean reviewed, String note) {}
}
