package com.benjagest.backend.advisory.collab;

import java.time.Instant;

/**
 * L4-6 — Colaboración entre dos asesorías. La asesoría anfitriona
 * ({@code advisoryCompanyId}) tiene los clientes; la colaboradora
 * ({@code partnerAdvisoryId}) presta trabajo sobre esos clientes
 * tras aceptar la invitación.
 *
 * <p>El campo {@code invitedEmail} identifica al destinatario antes
 * de que acepte — útil cuando el invitado todavía no tiene cuenta o
 * no se ha resuelto su asesoría todavía. Al aceptar, el backend
 * rellena {@code partnerAdvisoryId} con la asesoría OWNER del que
 * acepta.
 */
public record AdvisoryCollaboration(
        String id,
        String advisoryCompanyId,
        String partnerAdvisoryId,
        String invitedEmail,
        String status,
        Instant invitedAt,
        String invitedByUserId,
        Instant acceptedAt,
        String acceptedByUserId,
        Instant revokedAt,
        String revokedByUserId,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_REVOKED = "REVOKED";
}
