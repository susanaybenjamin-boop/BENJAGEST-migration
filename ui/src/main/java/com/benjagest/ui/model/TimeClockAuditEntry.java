package com.benjagest.ui.model;

public record TimeClockAuditEntry(
        String id,
        String employeeId,
        String employeeName,
        String eventType,
        String eventTime,
        String origin,
        String status,
        String createdAt,
        String csv,
        int correctionCount,
        String lastCorrectionAt
) {}
