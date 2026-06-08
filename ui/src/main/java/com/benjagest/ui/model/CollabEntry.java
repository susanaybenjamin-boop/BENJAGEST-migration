package com.benjagest.ui.model;

import java.time.Instant;

/**
 * L4-6/L4-7 — Colaboración entre asesorías visible en la UI.
 * Espejo del record backend {@code AdvisoryCollaboration}.
 *
 * <p>{@code partnerAdvisoryId} es NULL hasta que el receptor acepta —
 * la asesoría destino solo se resuelve al aceptar (el invited_email
 * sirve antes para identificar al destinatario).
 */
public record CollabEntry(
        String id,
        String advisoryCompanyId,
        String partnerAdvisoryId,
        String invitedEmail,
        String status,
        Instant invitedAt,
        Instant acceptedAt,
        Instant revokedAt,
        String notes
) {
    public boolean isPending() { return "PENDING".equals(status); }
    public boolean isAccepted() { return "ACCEPTED".equals(status); }
    public boolean isRejected() { return "REJECTED".equals(status); }
    public boolean isRevoked() { return "REVOKED".equals(status); }
}
