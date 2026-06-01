package com.benjagest.backend.settings;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pestana "Modulos" de Configuracion: lista el catalogo entero con su
 * estado activo/inactivo para la empresa actual, y permite a OWNER/ADMIN
 * cambiar el estado de cada uno.
 *
 * Activar un modulo con dependencias activa automaticamente las
 * dependencias (logica en el service). Desactivar settings esta
 * prohibido (devolveria a la empresa sin forma de entrar en esta
 * pantalla).
 */
@RestController
@RequestMapping("/api/settings/modules")
@RequiresModule("settings")
@RequiresRole({"OWNER", "ADMIN"})
public class CompanyModulesController {

    private final CompanyModulesService service;

    public CompanyModulesController(CompanyModulesService service) {
        this.service = service;
    }

    @GetMapping
    public List<CompanyModuleView> list() {
        return service.list();
    }

    @PutMapping("/{slug}")
    public List<CompanyModuleView> setActive(@PathVariable("slug") String slug,
                                             @RequestBody SetActiveRequest request) {
        return service.setActive(slug, request.active());
    }

    public record SetActiveRequest(@NotNull Boolean active) {
    }
}
