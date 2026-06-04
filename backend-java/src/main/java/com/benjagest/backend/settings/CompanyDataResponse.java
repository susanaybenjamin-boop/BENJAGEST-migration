package com.benjagest.backend.settings;

/**
 * Datos editables de la empresa activa que la pantalla "Configuracion ->
 * Empresa" muestra y permite cambiar.
 *
 * Tras la unificacion V10 (decision 2026-06-01), la empresa es su propio
 * emisor: los datos fiscales (direccion, IBAN, registro mercantil)
 * viven aqui y NO en una tabla `issuers` aparte.
 *
 * Tras la consolidacion V22 (decision 2026-06-04), los CAMPOS PROPIOS
 * DE LA FACTURA — pie, condiciones legales, textos exencion/inversion
 * sujeto pasivo/IVA reducido/rectificativa, mostrar IBAN — viven SOLO
 * en `invoice_texts` y se editan en Facturacion -> Configuracion. No
 * se duplican aqui.
 *
 * NO incluye id ni created_at (no editables) ni company_type ni
 * parent_company_id (decisiones estructurales).
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
        String registryInformation
) {
}
