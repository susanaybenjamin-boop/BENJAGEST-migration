package com.benjagest.backend.timeclock;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de fichaje RD 8/2019.
 *
 *   POST /api/timeclock/punch                  → fichar (IN/OUT/...).
 *   GET  /api/timeclock/employee/{id}/recent   → ultimos N del trabajador.
 *   POST /api/timeclock/correction             → solicitar correccion.
 *
 *   GET  /api/public/timeclock/verify?csv=...  → verificacion publica
 *                                                por CSV (sin auth).
 *
 * Ninguna escritura es autorizada por encima de OWNER/ADMIN/USER — el
 * propio trabajador ficha, su responsable lo aprueba/corrige.
 */
@RestController
@RequestMapping("/api/timeclock")
@RequiresModule("time-clock")
@RequiresRole({"OWNER", "ADMIN", "USER"})
public class TimeClockController {

    private final TimeClockService service;

    public TimeClockController(TimeClockService service) {
        this.service = service;
    }

    @PostMapping("/punch")
    public TimeClockService.PunchResult punch(@RequestBody PunchRequest req) {
        return service.punch(req.employeeId(), req.eventType(), req.customerId(), req.origin());
    }

    /**
     * Resuelve {@code employees.id} del usuario logueado en la
     * empresa activa (tenant). La UI lo llama antes de fichar para
     * obtener un employeeId válido; si el user no tiene ficha de
     * empleado en esta empresa, devuelve 404 con mensaje claro
     * ("Pide al administrador que te dé de alta en Personal").
     */
    @GetMapping("/me/employee")
    public MyEmployeeInfo myEmployee() {
        return service.resolveCurrentEmployee();
    }

    public record MyEmployeeInfo(String employeeId, String fullName) {}

    @GetMapping("/employee/{id}/recent")
    public List<TimeClockEvent> recent(@PathVariable("id") String employeeId,
                                       @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return service.recent(employeeId, limit);
    }

    @PostMapping("/correction")
    public void correction(@RequestBody CorrectionRequest req) {
        service.requestCorrection(req.originalEventId(), req.correctionType(),
                req.proposedEventType(), req.proposedEventTime(), req.reason());
    }

    public record PunchRequest(
            @NotBlank String employeeId,
            @NotBlank String eventType,
            String customerId,
            String origin
    ) {}

    public record CorrectionRequest(
            @NotBlank String originalEventId,
            @NotBlank String correctionType,
            String proposedEventType,
            Instant proposedEventTime,
            @NotBlank String reason
    ) {}
}
