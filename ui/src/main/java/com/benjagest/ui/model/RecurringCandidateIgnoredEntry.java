package com.benjagest.ui.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Candidato de recurrencia silenciado. Mismas claves que el candidato
 * normal pero con metadatos del silenciado: hasta cuándo, razón.
 *
 * <p>{@code ignoreUntil} NULL = indefinido (no vuelve a aparecer hasta
 * que se rehabilite explícitamente).
 */
public record RecurringCandidateIgnoredEntry(
        String id,
        String kind,
        String partyNif,
        String partyNameNorm,
        BigDecimal totalAmount,
        LocalDate ignoreUntil,
        String reason,
        String createdAt
) {}
