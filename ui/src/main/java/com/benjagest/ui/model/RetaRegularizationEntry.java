package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * Aviso de regularización RETA (RETA-3): un autónomo cuya base de cotización
 * está fuera del tramo que le corresponde por su rendimiento real (P&L).
 * status: NO_BASE | UNDER (cotiza de menos) | OVER (cotiza de más).
 */
public record RetaRegularizationEntry(
        String companyId,
        String companyName,
        String profileId,
        String fullName,
        BigDecimal currentBase,
        BigDecimal netIncome,
        String tramoLabel,
        BigDecimal baseMin,
        BigDecimal baseMax,
        String status
) {}
