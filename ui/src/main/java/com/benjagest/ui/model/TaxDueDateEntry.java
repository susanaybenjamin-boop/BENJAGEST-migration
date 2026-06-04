package com.benjagest.ui.model;

/** Vencimiento teorico del calendario fiscal. */
public record TaxDueDateEntry(
        String taxModelCode,
        String taxModelName,
        int periodYear,
        Integer periodQuarter,
        Integer periodMonth,
        String deadlineAt
) {}
