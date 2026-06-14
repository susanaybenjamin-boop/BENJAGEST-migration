package com.benjagest.ui.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Periodo de vacaciones de un empleado (CV-VAC). */
public record VacationEntry(
        String id,
        String employeeId,
        String employeeName,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal days,
        String status,
        String notes
) {}
