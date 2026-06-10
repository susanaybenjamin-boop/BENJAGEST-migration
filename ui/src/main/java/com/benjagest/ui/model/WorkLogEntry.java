package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * PORT-2 — Parte de día (work_log) leído desde /api/work-logs.
 */
public record WorkLogEntry(
        String id,
        String employeeId,
        String logDate,
        int minutesWorked,
        String customerId,
        String description,
        boolean billable,
        BigDecimal billableAmount,
        String status,
        String billedInvoiceLineId
) {}
