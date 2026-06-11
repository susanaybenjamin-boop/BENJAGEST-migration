package com.benjagest.backend.modules;

import com.benjagest.backend.tenant.TenantContext;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Punto unico de consulta para "?este modulo esta encendido para la
 * empresa que esta usando la app ahora?". Cualquier codigo del backend
 * que necesite saberlo debe pasar por aqui, no por el repository
 * directamente.
 *
 * Combina TenantContext (que dice "que empresa") con ModuleRepository
 * (que dice "que modulos tiene activos esa empresa").
 */
@Service
public class ModuleAccessService {

    private final ModuleRepository repository;
    private final TenantContext tenantContext;

    public ModuleAccessService(ModuleRepository repository, TenantContext tenantContext) {
        this.repository = repository;
        this.tenantContext = tenantContext;
    }

    public boolean isEnabledForCurrentCompany(String slug) {
        return repository.isModuleActive(tenantContext.getCurrentCompanyId(), slug);
    }

    public List<Module> listActiveForCurrentCompany() {
        List<Module> active = filterByCompanyType(
                repository.findActiveForCompany(tenantContext.getCurrentCompanyId()));
        // COMM-LINK 2026-06-11: el módulo de Comunicación NO está en
        // module_catalog (se eliminó en V95). Lo inyectamos como Module
        // virtual cuando la empresa actual tiene vinculación real
        // asesoría↔empresario. Sin vínculo no aparece en el sidebar.
        if (repository.hasCommLink(tenantContext.getCurrentCompanyId())) {
            List<Module> out = new java.util.ArrayList<>(active);
            out.add(new Module(
                    "comm", "Comunicación",
                    "Comunicación asesoría ↔ cliente: mensajes y documentos compartidos.",
                    null, null, "fas-comments",
                    145, false, true));
            return out;
        }
        return active;
    }

    public List<Module> listCatalog() {
        return filterByCompanyType(repository.findAll());
    }

    /**
     * Filtra los modulos {@code advisory_only=TRUE} cuando la empresa
     * activa NO es una asesoria (company_type != INTERNAL/ADVISORY).
     * Cierra el agujero documentado en el backlog: una empresa CLIENT
     * llegaba a ver "Asesoria" como modulo activable y el sidebar
     * podia colarse el slug "Mis clientes" para un empresario.
     */
    private List<Module> filterByCompanyType(List<Module> modules) {
        String companyId = tenantContext.getCurrentCompanyId();
        if (companyId == null || companyId.isBlank()) return modules;
        String companyType = repository.findCompanyType(companyId);
        boolean isAdvisory = "INTERNAL".equalsIgnoreCase(companyType)
                || "ADVISORY".equalsIgnoreCase(companyType);
        if (isAdvisory) return modules; // ve todo
        return modules.stream()
                .filter(m -> !m.advisoryOnly())
                .toList();
    }
}
