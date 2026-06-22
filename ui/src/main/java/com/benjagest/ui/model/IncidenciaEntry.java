package com.benjagest.ui.model;

import java.math.BigDecimal;

/** INC-1 — Incidencia de nómina de un empleado en un periodo (V136). */
public record IncidenciaEntry(
        String id,
        String employeeId,
        int periodYear,
        int periodMonth,
        String kind,        // OVERTIME | COMPLEMENT | ABSENCE | DEDUCTION | OTHER
        String subtype,     // overtime: STRUCTURAL | NORMAL ; absence: JUSTIFIED_PAID | JUSTIFIED_UNPAID | UNJUSTIFIED
        String concept,
        BigDecimal hours,
        BigDecimal unitPrice,
        BigDecimal days,
        BigDecimal amount,
        boolean cotizes,
        boolean taxable,
        String notes,
        String source       // MANUAL | AUTO
) {}
