package com.benjagest.backend.settings;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.benjagest.backend.audit.AuditService;
import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.modules.Module;
import com.benjagest.backend.modules.ModuleAccessService;
import com.benjagest.backend.modules.ModuleRepository;
import com.benjagest.backend.tenant.TenantContext;

/**
 * Pestana "Modulos" de Configuracion.
 *
 * Logica clave:
 *   - listar: cruza el catalogo con los activos de la empresa para
 *     devolver todo el catalogo con el flag active correcto.
 *   - setActive: cuando se activa un modulo con dependencia (requires_module_id),
 *     se auto-activa la dependencia para no dejar la empresa en un
 *     estado incoherente. Decision del seed: las dependencias se
 *     resuelven en backend, no a mano.
 */
@Service
public class CompanyModulesService {

    /**
     * Subarbol "Nucleo" del catalogo (categoria 'core' + sus 3 hijos).
     * Siempre activo, no editable. Hasta el slice de bugfix de modulos
     * era posible desactivarlo de tres formas: directamente con
     * slug=core (404 por categoria), via cascada al desmarcar la
     * categoria, o por slug=settings/customers/users (este ultimo
     * "settings" tenia defensa especifica, los otros dos no). Cualquier
     * camino que desactivase 'settings' rompia el acceso a esta misma
     * pantalla (@RequiresModule("settings") -> 403 -> no hay vuelta).
     *
     * A partir de este slice: estos 4 slugs no se exponen en el catalogo
     * y cualquier intento de tocarlos via PUT devuelve 400.
     */
    private static final Set<String> CORE_LOCKED_SLUGS = Set.of(
            "core", "customers", "settings", "users"
    );

    private final ModuleRepository moduleRepository;
    private final ModuleAccessService moduleAccessService;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    public CompanyModulesService(ModuleRepository moduleRepository,
                                 ModuleAccessService moduleAccessService,
                                 TenantContext tenantContext,
                                 CurrentUserService currentUserService,
                                 AuditService auditService) {
        this.moduleRepository = moduleRepository;
        this.moduleAccessService = moduleAccessService;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    public List<CompanyModuleView> list() {
        List<Module> catalog = moduleAccessService.listCatalog();
        Set<String> activeSlugs = moduleAccessService.listActiveForCurrentCompany().stream()
                .map(Module::slug)
                .collect(Collectors.toSet());
        return catalog.stream()
                // El subarbol 'core' (categoria + customers + settings +
                // users) no se expone: siempre activo, no editable. Lo
                // filtramos aqui para que la UI no llegue a renderizarlo.
                .filter(m -> !CORE_LOCKED_SLUGS.contains(m.slug()))
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
        if (slug != null && CORE_LOCKED_SLUGS.contains(slug.toLowerCase())) {
            // Bloqueo total del subarbol core. Aplica a active=true y a
            // active=false — el catalogo no lo expone, asi que cualquier
            // llamada aqui con estos slugs es un cliente malformado o
            // un intento manual. Devolvemos 400 explicito en lugar de
            // 404 para que el operador entienda que es decision de
            // diseno, no un slug inexistente.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El modulo '" + slug + "' es del nucleo y no se puede modificar");
        }

        String moduleId = moduleRepository.findIdBySlug(slug);
        if (moduleId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Modulo no encontrado en el catalogo: " + slug);
        }

        AuthenticatedUser actor = currentUserService.require();
        String companyId = tenantContext.getCurrentCompanyId();
        boolean isCategory = moduleRepository.isRootCategory(slug);

        // Defensa: un CLIENT no puede activar un modulo advisory_only
        // (Mis clientes, Asesoria, etc.). El catalogo filtrado por
        // ModuleAccessService ya no se lo ofrece, pero un POST a mano
        // sigue funcionando si no bloqueamos aqui.
        if (active) {
            Module target = moduleAccessService.listCatalog().stream()
                    .filter(m -> m.slug().equals(slug)).findFirst().orElse(null);
            if (target != null && target.advisoryOnly()) {
                String companyType = moduleRepository.findCompanyType(companyId);
                boolean isAdvisory = "INTERNAL".equalsIgnoreCase(companyType)
                        || "ADVISORY".equalsIgnoreCase(companyType);
                if (!isAdvisory) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "El modulo '" + slug + "' solo esta disponible para "
                                    + "empresas de tipo asesoria.");
                }
            }
        }

        if (active) {
            activateWithDependencies(slug, companyId, actor.userId(), new HashSet<>());
            if (isCategory) {
                // Cascada al activar una categoria: activar todos los sub-modulos
                // (UX CONTENDO: la categoria es todo-o-nada).
                for (String childSlug : moduleRepository.findChildSlugsOfCategory(slug)) {
                    activateWithDependencies(childSlug, companyId, actor.userId(), new HashSet<>());
                }
            }
        } else {
            moduleRepository.setActive(companyId, moduleId, false, actor.userId());
            if (isCategory) {
                // Cascada al desactivar una categoria: tambien apaga sus hijos
                // para no dejar datos huerfanos visibles.
                for (String childSlug : moduleRepository.findChildSlugsOfCategory(slug)) {
                    String childId = moduleRepository.findIdBySlug(childSlug);
                    if (childId != null) {
                        moduleRepository.setActive(companyId, childId, false, actor.userId());
                    }
                }
            }
        }

        // Auditoria del slug raiz: una linea por accion del usuario, no
        // una por cada hijo cascadeado (esos quedan implicitos en el
        // detalle si hace falta investigar).
        auditService.recordModuleToggled(actor.userId(), companyId, slug, active);

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
