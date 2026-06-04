package com.benjagest.backend.settings.owners;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Titular de una empresa: administrador, socio o autonomo asociado.
 * Imprescindible para modelo 200, 184 e informes SS.
 */
public record CompanyOwner(
        String id,
        String companyId,
        String fullName,
        String taxIdentifier,
        String role,
        BigDecimal ownershipPercent,
        String ssRegime,
        LocalDate appointmentDate,
        LocalDate terminationDate,
        String email,
        String phone,
        String notes,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
