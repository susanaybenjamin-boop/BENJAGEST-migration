package com.benjagest.backend.worklogs;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PORT-2 (skeleton 2026-06-10) — REST API base de partes de día.
 * UX completa pendiente de diseño Benjamin.
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

    /** Listado para la empresa (OWNER/ADMIN). */
    @GetMapping
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public List<WorkLogService.WorkLog> list(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to")   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.listForCompany(from, to);
    }

    /** Mis propios partes (EMPLOYEE). */
    @GetMapping("/mine")
    public List<WorkLogService.WorkLog> listMine(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to")   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.listMine(from, to);
    }

    @PostMapping
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public WorkLogService.WorkLog create(@RequestBody WorkLogService.CreateRequest req) {
        return service.create(req);
    }
}
