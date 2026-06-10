package com.benjagest.backend.suggestions;

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
 * PORT-3 SUG — REST API del módulo Sugerencias.
 *
 * <p>Bajo {@code /api/suggestions}. Per-tenant. Cualquier rol puede
 * crear y consultar las suyas; el OWNER puede cerrar y borrar.
 */
@RestController
@RequestMapping("/api/suggestions")
@RequiresModule("suggestions")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class SuggestionController {

    private final SuggestionService service;

    public SuggestionController(SuggestionService service) {
        this.service = service;
    }

    @GetMapping
    public List<SuggestionService.Suggestion> list() {
        return service.listForCurrentCompany();
    }

    @PostMapping
    public SuggestionService.Suggestion create(@RequestBody SuggestionService.CreateRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}/status")
    @RequiresRole({"OWNER", "ADMIN"})
    public SuggestionService.Suggestion updateStatus(
            @PathVariable("id") String id,
            @RequestBody SuggestionService.UpdateStatusRequest req) {
        return service.updateStatus(id, req.status());
    }

    @DeleteMapping("/{id}")
    @RequiresRole({"OWNER", "ADMIN"})
    public void delete(@PathVariable("id") String id) {
        service.delete(id);
    }
}
