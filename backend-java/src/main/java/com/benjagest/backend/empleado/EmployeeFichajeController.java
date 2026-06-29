package com.benjagest.backend.empleado;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.timeclock.ScheduleFichajeService;
import com.benjagest.backend.timeclock.TimeClockController;
import com.benjagest.backend.timeclock.TimeClockEvent;
import com.benjagest.backend.timeclock.TimeClockService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MEMP-2 — Fichaje desde la app del empleado (PWA).
 *
 * <p>El empleado se autentica por PIN ({@code /api/auth/pin-login}) y obtiene
 * un JWT con su empresa activa. Estos endpoints resuelven su ficha de empleado
 * y reutilizan {@link TimeClockService} (mismo motor que el fichaje de
 * escritorio y el kiosco): cadena hash, geo (evidencia), RD 8/2019.
 *
 * <p>NO lleva {@code @RequiresModule}: el fichaje del empleado no debe depender
 * de que la empresa tenga activado el módulo "time-clock" en su sidebar.
 */
@RestController
@RequestMapping("/api/empleado/fichaje")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE", "USER"})
public class EmployeeFichajeController {

    private final TimeClockService service;
    private final ScheduleFichajeService scheduleService;

    public EmployeeFichajeController(TimeClockService service, ScheduleFichajeService scheduleService) {
        this.service = service;
        this.scheduleService = scheduleService;
    }

    /** Ficha un evento (IN/OUT/BREAK_START/BREAK_END) con geo opcional. */
    @PostMapping
    public PunchOut punch(@RequestBody PunchIn req) {
        TimeClockController.MyEmployeeInfo me = service.resolveCurrentEmployee();
        TimeClockService.PunchResult res = service.punch(
                me.employeeId(), req.eventType(), null, "MOBILE", req.lat(), req.lng());
        String warning = null;
        if (res.geoWarning() != null) {
            warning = "Fuera del radio del centro \"" + res.geoWarning().centerName()
                    + "\" (" + res.geoWarning().distanceMeters() + " m).";
        } else if (res.holidayWarning() != null) {
            warning = "Hoy es festivo: " + res.holidayWarning().holidayName();
        }
        return new PunchOut(res.event().eventType(), res.event().eventTime(), res.csv(), warning);
    }

    /** Estado actual: últimos fichajes + si está dentro o fuera. */
    @GetMapping("/estado")
    public Estado estado() {
        TimeClockController.MyEmployeeInfo me = service.resolveCurrentEmployee();
        List<TimeClockEvent> recent = service.recent(me.employeeId(), 8);
        String lastType = recent.isEmpty() ? null : recent.get(0).eventType();
        boolean clockedIn = "IN".equals(lastType) || "BREAK_END".equals(lastType);
        List<PunchOut> items = new ArrayList<>();
        for (TimeClockEvent e : recent) {
            items.add(new PunchOut(e.eventType(), e.eventTime(), null, null));
        }
        return new Estado(me.fullName(), clockedIn, lastType, items);
    }

    /**
     * FJ-2 — Qué fichaje toca ahora según la jornada asignada (estilo CONTENDO).
     * La PWA destaca el botón sugerido cuando {@code inWindow}; los demás quedan
     * disponibles (no se bloquea nada). {@code hasSchedule=false} = hoy sin jornada.
     */
    @GetMapping("/sugerencia")
    public ScheduleFichajeService.Suggestion sugerencia() {
        TimeClockController.MyEmployeeInfo me = service.resolveCurrentEmployee();
        return scheduleService.suggestNextPunch(me.employeeId(), LocalDateTime.now());
    }

    /**
     * OFF-1 — Sincronización por lotes de fichajes capturados OFFLINE. Cada
     * fichaje trae su {@code clientUuid} (dedup) y su {@code eventTime} (sello del
     * momento real). Idempotente: reenviar el lote no duplica. Devuelve cuántos se
     * aceptaron, cuántos eran duplicados (ya sincronizados) y cuántos fallaron.
     */
    @PostMapping("/sync")
    public SyncResult sync(@RequestBody SyncIn req) {
        TimeClockController.MyEmployeeInfo me = service.resolveCurrentEmployee();
        int accepted = 0, duplicates = 0, failed = 0;
        if (req.punches() != null) {
            for (SyncPunch p : req.punches()) {
                try {
                    Instant when = (p.eventTime() == null || p.eventTime().isBlank())
                            ? Instant.now() : Instant.parse(p.eventTime());
                    var res = service.syncPunch(me.employeeId(), p.eventType(), null,
                            "OFFLINE", p.lat(), p.lng(), when, p.clientUuid());
                    if (res == null) duplicates++; else accepted++;
                } catch (Exception ex) {
                    failed++;
                }
            }
        }
        return new SyncResult(accepted, duplicates, failed);
    }

    public record SyncPunch(String clientUuid, String eventType, String eventTime,
                            BigDecimal lat, BigDecimal lng) {}

    public record SyncIn(List<SyncPunch> punches) {}

    public record SyncResult(int accepted, int duplicates, int failed) {}

    public record PunchIn(String eventType, BigDecimal lat, BigDecimal lng) {}

    public record PunchOut(String eventType, Instant eventTime, String csv, String warning) {}

    public record Estado(String employeeName, boolean clockedIn, String lastType, List<PunchOut> recent) {}
}
