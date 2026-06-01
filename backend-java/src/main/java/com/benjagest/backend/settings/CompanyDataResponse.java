package com.benjagest.backend.settings;

/**
 * Datos editables de la empresa activa que la pantalla "Configuracion ->
 * Empresa" muestra y permite cambiar.
 *
 * Tras la unificacion V10 (decision 2026-06-01), la empresa es su propio
 * emisor: los datos fiscales (direccion, IBAN, registro, textos de factura)
 * viven aqui y NO en una tabla `issuers` aparte. Asi, al editar la razon
 * social desde esta pantalla, el resto de la app (dashboard, header,
 * facturas) ve el cambio sin necesidad de sincronizacion manual.
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
        String website,
        String addressLine,
        String city,
        String province,
        String postalCode,
        String country,
        String iban,
        String registryInformation,
        String legalTerms,
        String invoiceFooter
) {
}
