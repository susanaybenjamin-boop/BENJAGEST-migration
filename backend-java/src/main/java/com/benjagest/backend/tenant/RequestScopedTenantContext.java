package com.benjagest.backend.tenant;

import com.benjagest.backend.workspace.DemoCompany;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;

/**
 * Implementacion request-scoped del TenantContext.
 *
 * ScopedProxyMode.TARGET_CLASS hace que cuando este bean se inyecta en
 * un singleton (Repository o Service), Spring inyecte un PROXY. Cada
 * vez que el singleton llama a un metodo del proxy, este resuelve la
 * instancia real de la peticion actual. Asi, sin esfuerzo, cada
 * peticion HTTP ve su propio company_id.
 *
 * Si nadie ha llamado setCurrentCompanyId() durante la peticion (porque
 * el header X-Company-Id no llego), getCurrentCompanyId() devuelve la
 * empresa demo. Esto mantiene compatibilidad con la UI actual mientras
 * el sistema de login real no esta listo.
 */
@Component
@Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class RequestScopedTenantContext implements TenantContext {

    private String companyId;

    @Override
    public String getCurrentCompanyId() {
        return companyId != null ? companyId : DemoCompany.ID;
    }

    @Override
    public void setCurrentCompanyId(String companyId) {
        this.companyId = companyId;
    }
}
