package com.benjagest.ui.model;

import java.math.BigDecimal;

/** TRB-4 — Tarifa de trabajo (customer_work_rates). customerId null = general. */
public record WorkRateEntry(
        String id,
        String customerId,
        String unit,        // HOURS | DAYS | MONTHS | FIXED
        String concept,
        BigDecimal price,
        boolean active
) {}
