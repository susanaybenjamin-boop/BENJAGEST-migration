package com.benjagest.backend.settings;

/**
 * Datos editables de la empresa activa que la pantalla "Configuracion ->
 * Empresa" muestra y permite cambiar.
 *
 * NO incluye id ni created_at (no editables) ni company_type ni
 * parent_company_id (decisiones estructurales que se cambian desde
 * admin, no desde la pantalla de configuracion de la empresa).
 */
public record CompanyDataResponse(
        String id,
        String legalName,
        String tradeName,
        String taxIdentifier,
        String companyType,
        String email,
        String phone,
        String website
) {
}
