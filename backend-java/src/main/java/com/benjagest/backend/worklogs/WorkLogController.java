package com.benjagest.backend.worklogs;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.time.LocalDate;
import java.util.List;
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
 * MÓDULO TRABAJOS — REST API de trabajos/partes facturables. Bajo el módulo
 * "shifts" (jornadas). Gestión por OWNER/ADMIN/ACCOUNTANT; el empleado solo
 * consulta los suyos (PWA).
 */
@RestController
@RequestMapping("/api/work-logs")
@RequiresModule("shifts")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class WorkLogController {

    private final WorkLogService service;

    public WorkLogController(WorkLogService service) {
        this.service = service;
    }

    /** Listado para la empresa, con filtros opcionales (OWNER/ADMIN/ACCOUNTANT). */
    @GetMapping
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public List<WorkLogService.WorkLog> list(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to")   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "customerId", required = false) String customerId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "billableUnbilledOnly", required = false, defaultValue = "false")
            boolean billableUnbilledOnly) {
        return service.listForCompany(from, to, customerId, status, billableUnbilledOnly);
    }

    /** Mis propios trabajos (EMPLOYEE, PWA). */
    @GetMapping("/mine")
    public List<WorkLogService.WorkLog> listMine(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to")   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.listMine(from, to);
    }

    public record MineCreateRequest(String logDate, Integer minutes, String description, String customerId) {}

    /** El empleado crea un parte propio (queda DRAFT). */
    @PostMapping("/mine")
    public WorkLogService.WorkLog createMine(@RequestBody MineCreateRequest req) {
        LocalDate d = req.logDate() == null || req.logDate().isBlank()
                ? null : LocalDate.parse(req.logDate().substring(0, 10));
        return service.createMine(d, req.minutes(), req.description(), req.customerId());
    }

    /** El empleado ENVÍA su parte (DRAFT → SUBMITTED) para que el admin lo apruebe. */
    @PostMapping("/mine/{id}/submit")
    public WorkLogService.WorkLog submitMine(@PathVariable("id") String id) {
        return service.submitMine(id);
    }

    /** El empleado borra un parte propio no aprobado/facturado. */
    @DeleteMapping("/mine/{id}")
    public void deleteMine(@PathVariable("id") String id) {
        service.deleteMine(id);
    }

    @PostMapping
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public WorkLogService.WorkLog create(@RequestBody WorkLogService.UpsertRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public WorkLogService.WorkLog update(@PathVariable("id") String id,
                                         @RequestBody WorkLogService.UpsertRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public void delete(@PathVariable("id") String id) {
        service.delete(id);
    }

    @PostMapping("/{id}/status")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public WorkLogService.WorkLog setStatus(@PathVariable("id") String id,
                                            @RequestParam("status") String status) {
        return service.setStatus(id, status);
    }

    /** TRB-3 — Factura los trabajos seleccionados. Devuelve {invoiceId}. */
    @PostMapping("/bill")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public java.util.Map<String, String> bill(@RequestBody BillRequest req) {
        String invoiceId = service.billSelected(req.ids(), req.merge(), req.mergedConcept());
        return java.util.Map.of("invoiceId", invoiceId);
    }

    public record BillRequest(List<String> ids, boolean merge, String mergedConcept) {}

    /** Marca trabajos como facturados enlazados a una factura ya creada (editor). */
    @PostMapping("/mark-billed")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public void markBilled(@RequestBody MarkBilledRequest req) {
        service.markBilled(req.ids(), req.invoiceId());
    }

    public record MarkBilledRequest(List<String> ids, String invoiceId) {}
}
