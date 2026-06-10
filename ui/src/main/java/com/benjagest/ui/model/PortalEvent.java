package com.benjagest.ui.model;

/**
 * PORT-1 — Evento del calendario laboral visto desde el Portal del
 * empleado. {@code kind} discrimina entre CALENDAR (evento normal de la
 * Agenda) y LEAVE (baja médica derivada de medical_leaves).
 */
public record PortalEvent(
        String id,
        String date,
        String title,
        String detail,
        String eventType,
        String kind,
        String sourceType
) {}
