package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * LIQ-SS-UI — Un mes con cuota de Seguridad Social, tal como lo devuelve
 * {@code GET /api/labor/social-security/pending}. {@code alreadyPaid} indica si
 * ya tiene su asiento {@code 476 → 572}; {@code motivo} (si no es null) explica
 * por qué ese mes no se podría contabilizar todavía.
 */
public record SsMonthEntry(
        int year, int month, BigDecimal amount, boolean alreadyPaid, String motivo) {}
