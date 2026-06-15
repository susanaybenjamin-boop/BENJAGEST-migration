package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * Un tramo de cotización RETA de un año (tabla reta_tramos, RETA-0).
 * Editable desde la UI; no-code al cambiar de año.
 */
public record RetaTramoEntry(
        String label,
        BigDecimal incomeMaxMonthly,
        BigDecimal baseMin,
        BigDecimal baseMax,
        BigDecimal quotaMin
) {}
