package com.benjagest.ui.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Contrato de trabajo. */
public record ContractEntry(
        String id,
        String employeeId,
        String contractType,
        String sepeContractCode,
        String collectiveAgreement,
        String professionalCategory,
        String professionalGroup,
        LocalDate startDate,
        LocalDate seniorityDate,
        LocalDate endDate,
        BigDecimal weeklyHours,
        BigDecimal grossSalary,
        Integer annualBonuses,
        Boolean extrasProrated,
        Integer vacationDays,
        BigDecimal irpfPercent,
        BigDecimal atEpPercent,
        Integer ssContributionGroup,
        String workplaceAddress,
        String status,
        String terminationReason,
        Integer probationDays,
        String pdfModel,
        java.util.List<SalaryItemEntry> salaryItems
) {}
