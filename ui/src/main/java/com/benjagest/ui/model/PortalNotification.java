package com.benjagest.ui.model;

/**
 * PORT-1 — Notificación que afecta al empleado. Filtrada por
 * (company_id, target_user_id IN (NULL, currentUser)). Read-only.
 */
public record PortalNotification(
        String id,
        String severity,
        String title,
        String body,
        String createdAt,
        boolean read
) {}
