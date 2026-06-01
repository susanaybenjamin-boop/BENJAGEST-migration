package com.benjagest.backend.audit;

/**
 * DTO de salida para listados de audit_events. Conviene aislarlo del
 * record interno por si quisieramos ocultar campos (p.ej. ip_address)
 * a roles que no sean OWNER/ADMIN.
 */
public record AuditEventResponse(
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
