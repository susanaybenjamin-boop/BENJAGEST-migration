package com.benjagest.ui.model;

/**
 * Snapshot de los datos editables de la empresa activa, tal y como los
 * devuelve GET /api/settings/company. Equivalente cliente del DTO del
 * backend CompanyDataResponse.
 */
public record CompanyData(
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
