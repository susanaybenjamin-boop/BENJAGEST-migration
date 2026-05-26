package com.benjagest.backend.workspace;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/modules")
public class ModuleController {

    private static final Set<String> SUPPORTED_MODULES = Set.of(
            "customers", "billing", "purchases", "labor", "tax", "reports", "settings", "calendar"
    );

    private final WorkspaceRepository repository;

    public ModuleController(WorkspaceRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{module}")
    public ModuleSummary list(
            @PathVariable("module") String module,
            @RequestParam(name = "mode", defaultValue = "BUSINESS") String mode
    ) {
        validateModule(module);
        return repository.module(module, mode);
    }

    @PostMapping("/{module}")
    @ResponseStatus(HttpStatus.CREATED)
    public ModuleRecord create(@PathVariable("module") String module, @RequestBody ModuleCreateRequest request) {
        validateModule(module);
        return repository.create(module, request);
    }

    @PutMapping("/{module}/{id}")
    public ModuleRecord update(
            @PathVariable("module") String module,
            @PathVariable("id") String id,
            @RequestBody ModuleCreateRequest request
    ) {
        validateModule(module);
        return repository.update(module, id, request);
    }

    @DeleteMapping("/{module}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("module") String module, @PathVariable("id") String id) {
        validateModule(module);
        repository.delete(module, id);
    }

    private void validateModule(String module) {
        if (!SUPPORTED_MODULES.contains(module)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Modulo no soportado: " + module);
        }
    }
}
