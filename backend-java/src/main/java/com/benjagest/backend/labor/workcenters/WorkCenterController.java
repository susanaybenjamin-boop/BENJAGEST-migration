package com.benjagest.backend.labor.workcenters;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Port CONTENDO {@code centros_trabajo_180}. Bajo
 * {@code /api/labor/work-centers}.
 */
@RestController
@RequestMapping("/api/labor/work-centers")
@RequiresModule("labor")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class WorkCenterController {

    private final WorkCenterService service;

    public WorkCenterController(WorkCenterService service) {
        this.service = service;
    }

    @GetMapping
    public List<WorkCenterService.WorkCenter> list() {
        return service.list();
    }

    @PostMapping
    @RequiresRole({"OWNER", "ADMIN"})
    public WorkCenterService.WorkCenter create(@RequestBody WorkCenterService.UpsertRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @RequiresRole({"OWNER", "ADMIN"})
    public WorkCenterService.WorkCenter update(
            @PathVariable("id") String id,
            @RequestBody WorkCenterService.UpsertRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @RequiresRole({"OWNER", "ADMIN"})
    public void delete(@PathVariable("id") String id) {
        service.deactivate(id);
    }
}
