package com.benjagest.ui.model;

/**
 * PORT-1 — Trabajo asignado al empleado. Hoy siempre lista vacía hasta
 * que se decida si portar work_logs de CONTENDO (PORT-2). Placeholder.
 */
public record PortalJob(
        String id,
        String title,
        String date,
        String status
) {}
