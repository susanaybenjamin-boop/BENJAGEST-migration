package com.benjagest.ui.model;

/**
 * Representacion cliente de una entrada de audit_events tal como la
 * devuelve GET /api/settings/audit-events. Equivalente al
 * AuditEventResponse del backend.
 */
public record AuditEvent(
        String id,
        String companyId,
        String userId,
        String eventType,
        String entityType,
        String entityId,
        String result,
        String ipAddress,
        String userAgent,
        String details,
        String createdAt
) {
}
