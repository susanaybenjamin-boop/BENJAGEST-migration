package com.benjagest.ui.model;

public record TimeClockAuditSummary(
        String employeeId,
        String employeeName,
        int totalEvents,
        String firstEvent,
        String lastEvent,
        int pauses,
        int ins,
        int outs,
        int corrections,
        boolean hasIncidence
) {}
