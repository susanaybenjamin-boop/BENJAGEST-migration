package com.benjagest.backend.audit;

import java.time.Instant;

/**
 * Una entrada inmutable de la tabla `audit_events`. La tabla la creo
 * Pablo en V2 pero hasta ahora nadie escribia ni leia. Con este paquete
 * empezamos a usarla para LOGIN, COMPANY_SWITCHED, MODULE_ENABLED/
 * DISABLED y COMPANY_DATA_UPDATED. Mas eventos se anaden cuando lleguen
 * los slices que los generan (facturas validadas, certificados subidos,
 * etc.).
 *
 * Campos opcionales (companyId, userId, entityType, entityId, ip, ua,
 * details) son NULL cuando no aplica. Por ejemplo en LOGIN_FAIL no hay
 * userId conocido y companyId no esta resuelto todavia.
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
        Instant createdAt,
        Long sequenceNumber,
        String prevEventHash,
        String eventHash
) {
    /** Constructor compat para callers antiguos que aún no manejan la cadena. */
    public AuditEvent(String id, String companyId, String userId, String eventType,
                       String entityType, String entityId, String result,
                       String ipAddress, String userAgent, String details, Instant createdAt) {
        this(id, companyId, userId, eventType, entityType, entityId, result,
                ipAddress, userAgent, details, createdAt, null, null, null);
    }
}
