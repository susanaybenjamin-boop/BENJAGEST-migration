package com.benjagest.ui.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FixedAssetEntry(
        String id,
        String code,
        String name,
        String description,
        String category,
        String accountingAccountId,
        LocalDate acquisitionDate,
        BigDecimal acquisitionCost,
        BigDecimal residualValue,
        BigDecimal usefulLifeYears,
        String depreciationMethod,
        LocalDate inServiceDate,
        LocalDate disposedAt,
        String disposalReason,
        BigDecimal disposalValue,
        String supplierName,
        String invoiceReference,
        String notes,
        boolean active
) {}
