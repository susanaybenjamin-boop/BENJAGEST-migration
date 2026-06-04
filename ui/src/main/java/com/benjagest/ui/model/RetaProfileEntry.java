package com.benjagest.ui.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Perfil RETA de un autonomo (titular o empleado autonomo societario). */
public record RetaProfileEntry(
        String id,
        String ownerId,
        String employeeId,
        String fullName,
        String taxIdentifier,
        String socialSecurityNumber,
        LocalDate retaStartDate,
        LocalDate retaEndDate,
        boolean pluriactividad,
        boolean tarifaPlana,
        LocalDate tarifaPlanaUntil,
        String activityCode,
        String activityDescription,
        String iaeEpigraph,
        BigDecimal expectedNetIncome,
        BigDecimal currentBase,
        BigDecimal currentQuota,
        String notes,
        boolean active
) {}
