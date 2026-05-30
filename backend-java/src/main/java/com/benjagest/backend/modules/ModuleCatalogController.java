package com.benjagest.backend.modules;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints para consultar el catalogo y la activacion de la empresa
 * actual. La UI llama a /active para construir su sidebar dinamica.
 *
 * Nombre intencionado: ya existia un ModuleCatalogController en workspace/ que
 * sirve el patron generico (kitchen sink). Este nuevo vive en el
 * paquete modules/ y atiende rutas /api/modules-catalog (diferente
 * raiz para no chocar). Cuando el ModuleCatalogController generico desaparezca
 * en el futuro, esta clase podra reubicarse en /api/modules.
 */
@RestController
@RequestMapping("/api/modules-catalog")
public class ModuleCatalogController {

    private final ModuleAccessService accessService;

    public ModuleCatalogController(ModuleAccessService accessService) {
        this.accessService = accessService;
    }

    @GetMapping
    public List<Module> catalog() {
        return accessService.listCatalog();
    }

    @GetMapping("/active")
    public List<Module> active() {
        return accessService.listActiveForCurrentCompany();
    }
}
