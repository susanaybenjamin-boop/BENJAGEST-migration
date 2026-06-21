package com.benjagest.ui.model;

/** MEMP-4 — Una solicitud de ausencia del empleado vista desde el escritorio. */
public record LeaveRequestEntry(
        String id,
        String employeeId,
        String employeeName,
        String kind,
        String startDate,
        String endDate,
        String days,
        String reason,
        String status,
        String reviewNotes,
        int attachmentCount
) {}
