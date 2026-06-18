package com.benjagest.ui.model;

/** JOR-2 — Plantilla de horario (planificacion de jornada). */
public record ScheduleTemplateEntry(
        String id,
        String name,
        String description,
        boolean active,
        int blocks,
        int assignments
) {}
