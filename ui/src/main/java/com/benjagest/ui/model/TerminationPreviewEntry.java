package com.benjagest.ui.model;

import java.math.BigDecimal;

/** Previsualización de una baja/despido: totales del finiquito + indemnización. */
public record TerminationPreviewEntry(
        BigDecimal settlementGross,
        BigDecimal settlementSs,
        BigDecimal settlementIrpf,
        BigDecimal settlementNet,
        BigDecimal employerCost,
        BigDecimal sevGross,
        BigDecimal sevExempt,
        BigDecimal sevTaxable,
        BigDecimal sevDays,
        BigDecimal sevAntiquity,
        BigDecimal sevDaily,
        int antiqYears,
        int antiqMonths,
        int antiqDays
) {}
