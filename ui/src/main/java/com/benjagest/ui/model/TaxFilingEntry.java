package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * Declaracion concreta para un (modelo, ano, trimestre/mes). El campo
 * `data` lleva el JSON crudo de las casillas — los editores de cada
 * modelo lo parsean y serializan.
 */
public record TaxFilingEntry(
        String id,
        String taxModelCode,
        int periodYear,
        Integer periodQuarter,
        Integer periodMonth,
        String status,
        BigDecimal totalAmount,
        String deadlineAt,
        String presentedAt,
        String csvAeat,
        String notes,
        String dataJson
) {}
