package com.benjagest.ui.model;

import java.math.BigDecimal;

/** Módulo Trabajos — un trabajo (work_log) leído desde /api/work-logs. */
public record WorkLogEntry(
        String id,
        String employeeId,
        String employeeName,
        String logDate,
        int minutesWorked,
        String customerId,
        String customerName,
        String description,
        boolean billable,
        String billingUnit,     // HOURS | DAYS | MONTHS | FIXED
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal billableAmount,
        String status,          // DRAFT | APPROVED | BILLED
        String billedInvoiceLineId
) {}
