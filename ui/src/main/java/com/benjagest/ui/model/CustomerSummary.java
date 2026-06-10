package com.benjagest.ui.model;

/**
 * Cliente devuelto por GET /api/customers.
 *
 * <p>Ampliado 2026-06-10 tarde con dirección postal: el editor de
 * factura necesita mostrar el bloque del cliente con NIF + calle +
 * CP + ciudad debajo del combo cuando se selecciona.
 */
public record CustomerSummary(
        String id,
        String legalName,
        String taxIdentifier,
        String email,
        String phone,
        String address,
        String city,
        String province,
        String postalCode,
        String country
) {
}
