package com.benjagest.ui.model;

import java.math.BigDecimal;

/** Sugerencia de tramo RETA dada un rendimiento neto. */
public record RetaTramoSuggestion(
        String tramoLabel,
        BigDecimal baseMinima,
        BigDecimal baseMaxima,
        BigDecimal cuotaMinima,
        BigDecimal annualNetIncome,
        BigDecimal monthlyIncome
) {}
