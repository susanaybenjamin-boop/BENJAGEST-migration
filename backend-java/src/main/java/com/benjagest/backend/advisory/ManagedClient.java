package com.benjagest.backend.advisory;

/**
 * Resumen de un cliente gestionado por una asesoria. Solo trae los
 * campos minimos para el listado — la ficha completa se obtiene con
 * GET /api/settings/company en el TenantContext de ese cliente.
 */
public record ManagedClient(
        String id,
        String legalName,
        String tradeName,
        String taxIdentifier,
        String companyType,
        String email,
        String phone,
        String city,
        String province
) {
}
