package com.benjagest.backend.issuer;

import java.time.Instant;

/**
 * Lo que el backend devuelve al cliente cuando lee o lista emisores.
 * Incluye id, todos los campos, timestamps de auditoria y el flag active.
 */
public record IssuerResponse(
        String id,
        String legalName,
        String taxIdentifier,
        String addressLine,
        String city,
        String province,
        String postalCode,
        String country,
        String email,
        String phone,
        String iban,
        String registryInformation,
        String legalTerms,
        String invoiceFooter,
        boolean active,
        boolean isDefault,
        Instant createdAt,
        Instant updatedAt
) {
}
