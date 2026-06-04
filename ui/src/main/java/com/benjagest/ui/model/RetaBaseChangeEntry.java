package com.benjagest.ui.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Cambio de base de cotizacion RETA. */
public record RetaBaseChangeEntry(
        String id,
        String profileId,
        LocalDate effectiveDate,
        String changeReason,
        BigDecimal newBase,
        BigDecimal newQuota,
        BigDecimal expectedNetIncome,
        boolean submittedToSs,
        String notes
) {}
