package com.benjagest.backend.empleado;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.timeclock.ScheduleFichajeService;
import com.benjagest.backend.timeclock.TimeClockController;
import com.benjagest.backend.timeclock.TimeClockRepository;
import com.benjagest.backend.timeclock.TimeClockService;
import com.benjagest.backend.worklogs.WorkdayService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MEMP-3 — "Mi jornada" en el portal del empleado (PWA).
 *
 * <p>Compone, para el empleado del JWT y el día de HOY:
 * <ul>
 *   <li>su horario asignado (JOR-2: {@link ScheduleFichajeService#scheduledBlocksForDay}),</li>
 *   <li>su jornada real fichada (JOR-1: {@link WorkdayService#compute}),</li>
 *   <li>si hoy es festivo de su calendario laboral, y</li>
 *   <li>qué fichaje le toca ahora ({@link ScheduleFichajeService#suggestNextPunch}).</li>
 * </ul>
 *
 * <p>Solo lectura. No {@code @RequiresModule}: el empleado ve SU jornada
 * independientemente de los módulos del sidebar de la empresa, igual que el fichaje.
 */
@RestController
@RequestMapping("/api/empleado/jornada")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE", "USER"})
public class EmployeeJornadaController {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final TimeClockService timeClock;
    private final ScheduleFichajeService scheduleService;
    private final WorkdayService workdayService;
    private final TimeClockRepository timeClockRepository;

    public EmployeeJornadaController(TimeClockService timeClock,
                                     ScheduleFichajeService scheduleService,
                                     WorkdayService workdayService,
                                     TimeClockRepository timeClockRepository) {
        this.timeClock = timeClock;
        this.scheduleService = scheduleService;
        this.workdayService = workdayService;
        this.timeClockRepository = timeClockRepository;
    }

    @GetMapping
    public JornadaToday today() {
        TimeClockController.MyEmployeeInfo me = timeClock.resolveCurrentEmployee();
        String employeeId = me.employeeId();
        LocalDate today = LocalDate.now(ZONE);

        List<ScheduleFichajeService.DayBlock> blocks = scheduleService.scheduledBlocksForDay(employeeId, today);

        Holiday holiday = timeClockRepository.findTodaysHolidayForEmployee(employeeId)
                .map(h -> new Holiday(h.holidayName(), h.holidayType()))
                .orElse(null);

        Worked worked = null;
        List<WorkdayService.Workday> days = workdayService.compute(today, today, employeeId);
        if (!days.isEmpty()) {
            WorkdayService.Workday w = days.get(0);
            worked = new Worked(hhmm(w.firstIn()), hhmm(w.lastOut()), w.workedMinutes(), w.pauseMinutes());
        }

        ScheduleFichajeService.Suggestion suggestion =
                scheduleService.suggestNextPunch(employeeId, LocalDateTime.now(ZONE));

        return new JornadaToday(today.toString(), !blocks.isEmpty(), blocks, holiday, worked, suggestion);
    }

    private static String hhmm(Instant t) {
        if (t == null) return null;
        LocalDateTime ldt = LocalDateTime.ofInstant(t, ZONE);
        return String.format("%02d:%02d", ldt.getHour(), ldt.getMinute());
    }

    public record JornadaToday(
            String date,
            boolean hasSchedule,
            List<ScheduleFichajeService.DayBlock> blocks,
            Holiday holiday,
            Worked worked,
            ScheduleFichajeService.Suggestion suggestion) {}

    public record Holiday(String name, String type) {}

    public record Worked(String firstIn, String lastOut, long workedMinutes, long pauseMinutes) {}
}
