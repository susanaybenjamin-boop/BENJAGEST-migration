package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * Titular / administrador / socio de la empresa.
 *
 *   - role: ADMINISTRATOR / JOINT / SOLE / BOARD_MEMBER / PARTNER / AUTONOMOUS
 *   - ssRegime: RETA / GENERAL / AUTONOMO_SOCIETARIO / NO_COTIZA / OTHER
 *   - ownershipPercent: 0..100
 */
public record CompanyOwnerEntry(
        String id,
        String fullName,
        String taxIdentifier,
        String role,
        String ssRegime,
        BigDecimal ownershipPercent,
        String appointmentDate,
        String terminationDate,
        String email,
        String phone,
        String notes,
        boolean active
) {}
