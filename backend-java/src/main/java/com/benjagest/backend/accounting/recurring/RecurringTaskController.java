package com.benjagest.backend.accounting.recurring;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API REST de tareas recurrentes contables.
 *
 * <ul>
 *   <li>{@code GET    /api/accounting/recurring} — listado.</li>
 *   <li>{@code POST   /api/accounting/recurring} — crear.</li>
 *   <li>{@code PUT    /api/accounting/recurring/{id}} — editar.</li>
 *   <li>{@code DELETE /api/accounting/recurring/{id}} — borrar.</li>
 *   <li>{@code PUT    /api/accounting/recurring/{id}/active} — activar/desactivar.</li>
 *   <li>{@code POST   /api/accounting/recurring/{id}/run-now} — dispara una sola tarea ya.</li>
 *   <li>{@code GET    /api/accounting/recurring/{id}/runs} — historial.</li>
 *   <li>{@code POST   /api/accounting/recurring/run-all?date=...} — dispara todas las due (manual override del cron).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/accounting/recurring")
@RequiresModule("accounting")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class RecurringTaskController {

    private final RecurringTaskService service;
    private final RecurringTaskScheduler scheduler;
    private final RecurringCandidateService candidateService;

    public RecurringTaskController(RecurringTaskService service,
                                   RecurringTaskScheduler scheduler,
                                   RecurringCandidateService candidateService) {
        this.service = service;
        this.scheduler = scheduler;
        this.candidateService = candidateService;
    }

    /**
     * Lista candidatos a recurrencia: facturas (emitidas o recibidas)
     * que se repiten con el mismo tercero + mismo importe en la
     * ventana indicada. El frontend usa esto para mostrar un banner
     * "tienes N posibles plantillas detectadas — ¿activarlas?".
     *
     * @param kind        SALES_INVOICE o PURCHASE.
     * @param windowDays  ventana de análisis hacia atrás (default 180).
     */
    @GetMapping("/candidates")
    public List<RecurringCandidateService.Candidate> candidates(
            @RequestParam("kind") String kind,
            @RequestParam(value = "windowDays", required = false,
                    defaultValue = "180") int windowDays) {
        return candidateService.findCandidates(kind, windowDays);
    }

    /**
     * Slice 3P — ¿Existe ya una recurrente activa que cubra este
     * tercero + importe? Lo usa el UI tras validar una venta o
     * gasto para NO preguntar "¿hacer recurrente?" si la factura
     * recién validada ya es generada por una plantilla existente.
     */
    @GetMapping("/already-covers")
    public Map<String, Object> alreadyCovers(
            @RequestParam("kind") String kind,
            @RequestParam("partyName") String partyName,
            @RequestParam("amount") java.math.BigDecimal amount) {
        return Map.of("covered",
                candidateService.alreadyCovers(kind, partyName, amount));
    }

    @GetMapping
    public List<RecurringTaskService.RecurringTaskView> list(
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "active", required = false) Boolean active) {
        return service.list(kind, active);
    }

    @GetMapping("/{id}")
    public RecurringTaskService.RecurringTaskView get(@PathVariable("id") String id) {
        return service.get(id);
    }

    @PostMapping
    public RecurringTaskService.RecurringTaskView create(
            @RequestBody RecurringTaskService.RecurringTaskRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public RecurringTaskService.RecurringTaskView update(
            @PathVariable("id") String id,
            @RequestBody RecurringTaskService.RecurringTaskRequest req) {
        return service.update(id, req);
    }

    @PutMapping("/{id}/active")
    public Map<String, Object> setActive(
            @PathVariable("id") String id,
            @RequestBody ActiveRequest body) {
        service.setActive(id, body.active() != null && body.active());
        return Map.of("id", id, "active", body.active() != null && body.active());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id) {
        service.delete(id);
        return Map.of("id", id, "deleted", true);
    }

    @GetMapping("/{id}/runs")
    public List<RecurringTaskService.RunView> runs(
            @PathVariable("id") String id,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return service.listRuns(id, limit);
    }

    @PostMapping("/{id}/run-now")
    public RecurringTaskService.RunView runOne(
            @PathVariable("id") String id,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        RecurringTaskService.RunView view = service.runOne(
                id, date == null ? LocalDate.now() : date);
        // Slice 3H-4 — Si la ejecución falló (status=ERROR), el service
        // captura la excepción internamente para dejar registro en la
        // historia, pero hasta ahora devolvía 200 OK al frontend → el
        // asesor pensaba que se había generado el documento. Aquí
        // promovemos el ERROR a HTTP 422 (Unprocessable Entity) para
        // que el cliente vea el fallo real y el mensaje del backend.
        if ("ERROR".equals(view.status())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    view.message() == null
                            ? "La tarea falló sin mensaje del backend."
                            : view.message());
        }
        if ("SKIPPED".equals(view.status())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Tarea omitida: " + (view.message() == null ? "(sin razón)" : view.message()));
        }
        return view;
    }

    @PostMapping("/run-all")
    public RecurringTaskScheduler.RunSummary runAll(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return scheduler.runForDate(date == null ? LocalDate.now() : date);
    }

    public record ActiveRequest(Boolean active) {}
}
