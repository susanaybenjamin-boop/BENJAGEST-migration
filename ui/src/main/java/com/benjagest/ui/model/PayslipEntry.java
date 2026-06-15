package com.benjagest.ui.model;

import java.math.BigDecimal;

public record PayslipEntry(
        String id,
        String employeeId,
        String employeeName,
        String contractId,
        int periodYear,
        int periodMonth,
        String payslipType,
        BigDecimal grossAmount,
        BigDecimal ssEmployeeAmount,
        BigDecimal irpfAmount,
        BigDecimal otherDeductions,
        BigDecimal netAmount,
        String status,
        String paidAt,
        String pdfPath,
        String notes,
        String deliveredAt,
        String deliveryMethod,
        String acknowledgedAt
) {}
