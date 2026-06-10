package com.benjagest.backend.customer;

import java.time.Instant;

/**
 * Datos del cliente expuestos por GET /api/customers (listado y por id).
 *
 * <p>2026-06-10 tarde: ampliado con dirección postal — antes solo
 * llegaban legalName/taxIdentifier/email/phone, y el editor de
 * factura no podía pintar la dirección en el bloque del cliente
 * (aunque el PDF de factura sí porque iba directo a customers).
 *
 * <p>email/phone: se prefiere {@code customers.email/phone}
 * (lo nuevo, V85 y editor PORT-4 CLI v3). Si está vacío se cae al
 * primary_contact del customer_contacts (legacy).
 */
public record CustomerResponse(
        String id,
        String legalName,
        String tradeName,
        String taxIdentifier,
        String primaryContactName,
        String email,
        String phone,
        String address,
        String city,
        String province,
        String postalCode,
        String country,
        Instant createdAt
) {
}
