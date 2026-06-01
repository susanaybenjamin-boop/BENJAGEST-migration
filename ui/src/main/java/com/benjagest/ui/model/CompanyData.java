package com.benjagest.ui.model;

/**
 * Snapshot de los datos editables de la empresa activa, tal y como los
 * devuelve GET /api/settings/company. Equivalente cliente del DTO del
 * backend CompanyDataResponse.
 *
 * Tras la unificacion V10 (decision 2026-06-01), la empresa absorbe los
 * datos fiscales del emisor (direccion, IBAN, registro, textos de
 * factura). Por eso este record tiene tantos campos: es la unica fuente
 * de verdad sobre "como se identifica la empresa al facturar".
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
        String registryInformation,
        String legalTerms,
        String invoiceFooter
) {
}
