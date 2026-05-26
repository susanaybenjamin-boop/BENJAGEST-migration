package com.benjagest.backend.workspace;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ModuleCreateRequest(
        String legalName,
        String tradeName,
        String taxIdentifier,
        String contactName,
        String email,
        String phone,
        String customerId,
        String supplierId,
        String employeeId,
        String title,
        String description,
        String category,
        LocalDate date,
        BigDecimal amount,
        BigDecimal vatPercent,
        Integer minutes,
        String status,
        String eventType,
        String pin
) {
}
