package com.benjagest.ui.model;

import java.math.BigDecimal;

/** Resultado de la previsualización de una nómina (sin guardar). */
public record PayslipPreview(
        BigDecimal gross,
        BigDecimal cotizationBase,
        BigDecimal ssEmployee,
        BigDecimal irpf,
        BigDecimal irpfPct,
        BigDecimal otherDeductions,
        BigDecimal net,
        BigDecimal employerTotal,
        BigDecimal employerCost
) {}
