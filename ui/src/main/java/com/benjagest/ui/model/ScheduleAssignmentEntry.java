package com.benjagest.ui.model;

/** JOR-2 — Asignacion de una plantilla de horario a un empleado (con vigencia). */
public record ScheduleAssignmentEntry(
        String id,
        String employeeId,
        String employeeName,
        String effectiveFrom,
        String effectiveTo
) {}
