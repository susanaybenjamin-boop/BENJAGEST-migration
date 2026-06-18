package com.benjagest.ui.model;

/** JOR-1 — Jornada real calculada desde los fichajes (un dia de un empleado). */
public record WorkdayEntry(
        String employeeId,
        String employeeName,
        String date,
        String firstIn,
        String lastOut,
        int workedMinutes,
        int pauseMinutes,
        int events
) {}
