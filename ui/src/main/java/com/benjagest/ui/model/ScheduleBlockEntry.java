package com.benjagest.ui.model;

/** JOR-2 — Bloque horario de una plantilla (por dia de la semana). */
public record ScheduleBlockEntry(
        String id,
        int weekday,
        String blockType,
        String startTime,
        String endTime
) {}
