package com.benjagest.backend.timeclock;

import com.benjagest.backend.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * FJ-1 — "¿Qué toca fichar ahora?" según la jornada asignada (estilo CONTENDO).
 *
 * <p>Resuelve la plantilla de horario vigente del empleado para HOY
 * ({@code work_schedule_assignments} + {@code work_schedule_blocks}, JOR-2 / V131),
 * construye las transiciones esperadas (Entrada / Pausa / Volver de pausa / Salida)
 * y, mirando lo ya fichado hoy ({@link TimeClockService#recent}), devuelve la
 * <b>siguiente</b> que toca, con una ventana de ±{@value #MARGIN_MINUTES} min.
 *
 * <p>NO bloquea: solo sugiere y destaca. La vida real se desvía del horario; el
 * empleado siempre puede fichar otra cosa (queda como desviación). Día sin jornada
 * asignada (o día libre) → {@code hasSchedule = false} y la UI muestra todos los
 * botones como hasta ahora.
 *
 * <p>Tenant-scoped: todas las consultas filtran por la empresa activa, igual que
 * {@link WorkScheduleService} y {@link TimeClockService}.
 */
@Service
public class ScheduleFichajeService {

    /** Margen CONTENDO: el botón aparece ±15 min de la hora teórica. */
    private static final int MARGIN_MINUTES = 15;

    /**
     * Zona para agrupar los fichajes (Instant UTC) en "el día de hoy". El
     * despliegue es on-premise en España ("todo es un puesto") → la zona del
     * sistema es la correcta. Candidato a parámetro por empresa, como el margen.
     */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final TimeClockService timeClock;

    public ScheduleFichajeService(JdbcTemplate jdbc, TenantContext tenant, TimeClockService timeClock) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.timeClock = timeClock;
    }

    /**
     * Calcula qué fichaje toca al empleado en el momento {@code now}.
     *
     * @param employeeId ficha del empleado ({@code employees.id}).
     * @param now        momento local de referencia (la UI pasa {@code LocalDateTime.now()}).
     * @return sugerencia; {@code hasSchedule=false} si hoy no hay jornada; {@code action=null}
     *         con {@code hasSchedule=true} si la jornada ya está completa.
     */
    public Suggestion suggestNextPunch(String employeeId, LocalDateTime now) {
        LocalDate today = now.toLocalDate();

        String templateId = resolveTemplateId(employeeId, today);
        if (templateId == null) {
            return Suggestion.noSchedule();
        }

        int weekday = today.getDayOfWeek().getValue(); // ISO 1=lunes .. 7=domingo
        List<Block> blocks = loadBlocks(templateId, weekday);
        if (blocks.isEmpty()) {
            return Suggestion.noSchedule(); // día libre dentro de la plantilla
        }

        List<Expected> expected = buildExpected(blocks);
        if (expected.isEmpty()) {
            return Suggestion.noSchedule(); // bloques solo BREAK, sin trabajo: nada que sugerir
        }

        int done = countTodaysPunches(employeeId, today);
        if (done >= expected.size()) {
            return Suggestion.completed(); // ya fichó todo lo previsto hoy
        }

        Expected next = expected.get(done);
        LocalTime scheduled = next.time();
        LocalTime from = scheduled.minusMinutes(MARGIN_MINUTES);
        LocalTime to = scheduled.plusMinutes(MARGIN_MINUTES);
        LocalTime nowTime = now.toLocalTime();
        boolean inWindow = !nowTime.isBefore(from) && !nowTime.isAfter(to);

        return new Suggestion(true, next.action(), hhmm(scheduled), hhmm(from), hhmm(to), inWindow);
    }

    // ---- Resolución de jornada -------------------------------------------

    /** Plantilla vigente del empleado a la fecha (la más reciente que cubre {@code day}). */
    private String resolveTemplateId(String employeeId, LocalDate day) {
        List<String> rows = jdbc.query("""
                SELECT template_id
                  FROM work_schedule_assignments
                 WHERE company_id = ?
                   AND employee_id = ?
                   AND effective_from <= ?
                   AND (effective_to IS NULL OR effective_to >= ?)
                 ORDER BY effective_from DESC
                 LIMIT 1
                """,
                (rs, n) -> rs.getString("template_id"),
                tenant.getCurrentCompanyId(), employeeId, day, day);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Bloques de la plantilla para ese día de la semana, ordenados por hora. */
    private List<Block> loadBlocks(String templateId, int weekday) {
        return jdbc.query("""
                SELECT block_type,
                       CAST(start_time AS CHAR) AS start_time,
                       CAST(end_time AS CHAR) AS end_time
                  FROM work_schedule_blocks
                 WHERE company_id = ? AND template_id = ? AND weekday = ?
                 ORDER BY start_time
                """,
                (rs, n) -> new Block(
                        "BREAK".equalsIgnoreCase(rs.getString("block_type")),
                        LocalTime.parse(rs.getString("start_time").substring(0, 5)),
                        LocalTime.parse(rs.getString("end_time").substring(0, 5))),
                tenant.getCurrentCompanyId(), templateId, weekday);
    }

    /**
     * Construye las transiciones esperadas en orden cronológico:
     *   - inicio del 1er bloque WORK → IN (Entrada).
     *   - fin de un WORK seguido de BREAK → BREAK_START (Pausa).
     *   - fin de un BREAK seguido de WORK → BREAK_END (Volver de pausa).
     *   - fin del último bloque WORK → OUT (Salida).
     */
    private List<Expected> buildExpected(List<Block> blocks) {
        List<Expected> expected = new ArrayList<>();
        int firstWork = -1;
        int lastWork = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (!blocks.get(i).isBreak()) {
                if (firstWork < 0) firstWork = i;
                lastWork = i;
            }
        }
        if (firstWork < 0) {
            return expected; // ningún bloque de trabajo
        }
        expected.add(new Expected("IN", blocks.get(firstWork).start()));
        for (int i = 0; i < blocks.size() - 1; i++) {
            Block cur = blocks.get(i);
            Block nxt = blocks.get(i + 1);
            if (!cur.isBreak() && nxt.isBreak()) {
                expected.add(new Expected("BREAK_START", cur.end()));
            } else if (cur.isBreak() && !nxt.isBreak()) {
                expected.add(new Expected("BREAK_END", cur.end()));
            }
        }
        expected.add(new Expected("OUT", blocks.get(lastWork).end()));
        return expected;
    }

    /** Cuántos fichajes ha hecho el empleado HOY (zona local). */
    private int countTodaysPunches(String employeeId, LocalDate today) {
        List<TimeClockEvent> recent = timeClock.recent(employeeId, 50);
        int count = 0;
        for (TimeClockEvent e : recent) {
            Instant t = e.eventTime();
            if (t == null) continue;
            if (t.atZone(ZONE).toLocalDate().equals(today)) {
                count++;
            }
        }
        return count;
    }

    private static String hhmm(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    // ---- Records ----------------------------------------------------------

    private record Block(boolean isBreak, LocalTime start, LocalTime end) {}

    private record Expected(String action, LocalTime time) {}

    /**
     * Sugerencia de fichaje. {@code action} = IN | OUT | BREAK_START | BREAK_END
     * (la UI lo traduce con {@code t(...)}); horas en formato "HH:mm".
     */
    public record Suggestion(
            boolean hasSchedule,
            String action,
            String scheduledTime,
            String windowFrom,
            String windowTo,
            boolean inWindow) {

        static Suggestion noSchedule() {
            return new Suggestion(false, null, null, null, null, false);
        }

        static Suggestion completed() {
            return new Suggestion(true, null, null, null, null, false);
        }
    }
}
