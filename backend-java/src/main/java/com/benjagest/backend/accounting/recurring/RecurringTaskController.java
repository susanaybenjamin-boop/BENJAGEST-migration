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

    public RecurringTaskController(RecurringTaskService service, RecurringTaskScheduler scheduler) {
        this.service = service;
        this.scheduler = scheduler;
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
        return service.runOne(id, date == null ? LocalDate.now() : date);
    }

    @PostMapping("/run-all")
    public RecurringTaskScheduler.RunSummary runAll(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return scheduler.runForDate(date == null ? LocalDate.now() : date);
    }

    public record ActiveRequest(Boolean active) {}
}
