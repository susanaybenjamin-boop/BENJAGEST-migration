package com.benjagest.backend.tenant;

/**
 * Contiene el company_id activo durante una peticion HTTP.
 * Implementado como bean request-scoped: cada peticion HTTP recibe
 * su propia instancia y los Repositories acceden a ella via proxy.
 *
 * Hoy se alimenta del header HTTP X-Company-Id (lo pone la UI tras
 * el login), con fallback a la empresa demo si el header falta. Cuando
 * llegue el slice de login real, lo alimentara el claim del JWT en
 * lugar del header.
 *
 * Importante: este servicio sustituye al uso directo de DemoCompany.ID
 * en los Repositories del patron dedicado (issuer, customer). Es la
 * pieza que cierra la brecha de aislamiento multi-empresa.
 */
public interface TenantContext {

    String getCurrentCompanyId();

    void setCurrentCompanyId(String companyId);
}
