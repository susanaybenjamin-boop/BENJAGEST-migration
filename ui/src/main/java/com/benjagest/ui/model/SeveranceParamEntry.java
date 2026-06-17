package com.benjagest.ui.model;

import java.math.BigDecimal;

/** N3(b) — Topes de indemnización por despido de un año (no-code, V127). */
public record SeveranceParamEntry(
        int yearNumber,
        BigDecimal unfairDaysPerYear, int unfairCapDays,
        BigDecimal unfairPre2012DaysPerYear, int unfairPre2012CapDays,
        BigDecimal objectiveDaysPerYear, int objectiveCapDays,
        BigDecimal endContractDaysPerYear, BigDecimal irpfExemptCap,
        String legalReference
) {}
