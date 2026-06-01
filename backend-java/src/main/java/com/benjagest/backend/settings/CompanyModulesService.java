package com.benjagest.backend.settings;

import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.modules.Module;
import com.benjagest.backend.modules.ModuleAccessService;
import com.benjagest.backend.modules.ModuleRepository;
import com.benjagest.backend.tenant.TenantContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pestana "Modulos" de Configuracion.
 *
 * Logica clave:
 *   - listar: cruza el catalogo con los activos de la empresa para
 *     devolver TODO el catalogo con el flag active correcto.
 *   - setActive: cuando se activa un modulo con dependencia (requires_module_id),
 *     se auto-activa la dependencia para no dejar la empresa en un
 *     estado incoherente. Decision del seed: las dependencias se
 *     resuelven en backend, no a mano.
 */
@Service
public class CompanyModulesService {

    private final ModuleRepository moduleRepository;
    private final ModuleAccessService moduleAccessService;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;

    public CompanyModulesService(ModuleRepository moduleRepository,
                                 ModuleAccessService moduleAccessService,
                                 TenantContext tenantContext,
                                 CurrentUserService currentUserService) {
        this.moduleRepository = moduleRepository;
        this.moduleAccessService = moduleAccessService;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
    }

    public List<CompanyModuleView> list() {
        List<Module> catalog = moduleAccessService.listCatalog();
        Set<String> activeSlugs = moduleAccessService.listActiveForCurrentCompany().stream()
                .map(Module::slug)
                .collect(Collectors.toSet());
        return catalog.stream()
                .map(m -> new CompanyModuleView(
                        m.slug(),
                        m.label(),
                        m.description(),
                        m.parentSlug(),
                        m.requiresSlug(),
                        m.icon(),
                        m.displayOrder(),
                        m.advisoryOnly(),
                        activeSlugs.contains(m.slug())
                ))
                .toList();
    }

    @Transactional
    public List<CompanyModuleView> setActive(String slug, boolean active) {
        if ("settings".equalsIgnoreCase(slug) && !active) {
            // Defensa: si la empresa se queda sin settings, nadie podra
            // volver a entrar en esta pantalla a re-activarlo.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se puede desactivar el modulo de Configuracion para la empresa");
        }

        String moduleId = moduleRepository.findIdBySlug(slug);
        if (moduleId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Modulo no encontrado en el catalogo: " + slug);
        }

        AuthenticatedUser actor = currentUserService.require();
        String companyId = tenantContext.getCurrentCompanyId();

        if (active) {
            activateWithDependencies(slug, companyId, actor.userId(), new HashSet<>());
        } else {
            moduleRepository.setActive(companyId, moduleId, false, actor.userId());
        }

        return list();
    }

    /**
     * Activa un modulo y, recursivamente, cualquier dependencia que tenga
     * declarada en requires_module_id. Usa un set de visitados para
     * proteger contra ciclos (no deberian existir, pero por si acaso).
     */
    private void activateWithDependencies(String slug, String companyId, String userId, Set<String> visited) {
        if (!visited.add(slug)) {
            return;
        }
        Module module = moduleAccessService.listCatalog().stream()
                .filter(m -> m.slug().equals(slug))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Modulo no encontrado en el catalogo: " + slug));
        if (module.requiresSlug() != null && !module.requiresSlug().isBlank()) {
            activateWithDependencies(module.requiresSlug(), companyId, userId, visited);
        }
        String moduleId = moduleRepository.findIdBySlug(slug);
        moduleRepository.setActive(companyId, moduleId, true, userId);
    }
}
