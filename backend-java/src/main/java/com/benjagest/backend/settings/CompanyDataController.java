package com.benjagest.backend.settings;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pestana "Empresa" de la pantalla de Configuracion. Solo OWNER/ADMIN
 * pueden ver y editar los datos generales de la empresa activa.
 *
 * No hay endpoint para crear o borrar empresas aqui: ese caso pertenece
 * a un flujo de alta/onboarding que vendra en otro slice.
 */
@RestController
@RequestMapping("/api/settings/company")
@RequiresModule("settings")
@RequiresRole({"OWNER", "ADMIN"})
public class CompanyDataController {

    private final CompanyDataService service;

    public CompanyDataController(CompanyDataService service) {
        this.service = service;
    }

    @GetMapping
    public CompanyDataResponse get() {
        return service.getCurrent();
    }

    @PutMapping
    public CompanyDataResponse update(@Valid @RequestBody CompanyDataUpdateRequest request) {
        return service.update(request);
    }
}
