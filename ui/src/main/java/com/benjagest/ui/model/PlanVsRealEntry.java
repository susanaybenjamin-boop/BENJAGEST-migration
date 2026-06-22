package com.benjagest.ui.model;

/** JOR-4 — Comparacion planificado vs real de un dia de un empleado. */
public record PlanVsRealEntry(
        String employeeId,
        String employeeName,
        String date,
        int plannedMinutes,
        int workedMinutes,
        int diffMinutes
) {}
