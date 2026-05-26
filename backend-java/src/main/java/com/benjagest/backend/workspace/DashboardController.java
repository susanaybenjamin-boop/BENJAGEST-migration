package com.benjagest.backend.workspace;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final WorkspaceRepository repository;

    public DashboardController(WorkspaceRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public DashboardResponse dashboard(@RequestParam(name = "mode", defaultValue = "BUSINESS") String mode) {
        return repository.dashboard(mode);
    }
}
