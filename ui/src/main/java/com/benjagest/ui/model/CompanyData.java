package com.benjagest.ui.model;

/**
 * Snapshot de los datos editables de la empresa activa, tal y como los
 * devuelve GET /api/settings/company. Equivalente cliente del DTO del
 * backend CompanyDataResponse.
 *
 * Tras V10 (2026-06-01) la empresa absorbe los datos administrativos
 * del emisor (direccion, IBAN, registro).
 *
 * Tras V22 (2026-06-04), los textos "de factura" (legal_terms,
 * invoice_footer) salen de aqui y viven solo en `invoice_texts`,
 * editables en Facturacion -> Configuracion -> Textos legales.
 */
public record CompanyData(
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
