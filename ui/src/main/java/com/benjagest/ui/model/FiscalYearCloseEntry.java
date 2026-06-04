package com.benjagest.ui.model;

import java.math.BigDecimal;

public record FiscalYearCloseEntry(
        String id,
        int periodYear,
        String status,
        BigDecimal incomeTotal,
        BigDecimal expenseTotal,
        BigDecimal resultAmount,
        BigDecimal taxAmount,
        BigDecimal resultAfterTax,
        BigDecimal reservesAllocation,
        BigDecimal dividendsAllocation,
        BigDecimal accumulatedLossesAllocation,
        String closedAt,
        String reopenedAt,
        String notes
) {}
