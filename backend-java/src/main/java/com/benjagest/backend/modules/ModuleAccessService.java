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
        return repository.findActiveForCompany(tenantContext.getCurrentCompanyId());
    }

    public List<Module> listCatalog() {
        return repository.findAll();
    }
}
