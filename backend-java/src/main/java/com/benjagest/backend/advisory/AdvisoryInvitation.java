package com.benjagest.backend.advisory;

import java.time.Instant;

/**
 * Representación en memoria de una fila de {@code advisory_invitations}.
 *
 * Una invitación es un token con caducidad emitido por una asesoría
 * para que un empresario (cliente potencial) se vincule como
 * {@code companies.parent_company_id}. El empresario la acepta desde
 * su sesión validando que el NIF/email de la invitación coincide con
 * los suyos.
 */
public record AdvisoryInvitation(
        String id,
        String advisoryCompanyId,
        String invitedEmail,
        String invitedNif,
        String invitedCompanyName,
        String invitedCompanyId,
        String token,
        String status,
        Instant expiresAt,
        String notes,
        String createdByUserId,
        String acceptedByUserId,
        Instant acceptedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_REVOKED = "REVOKED";
    /**
     * Estado terminal cuando una invitación ACCEPTED se rompe porque
     * el empresario desvincula a su asesoría. Se conserva la fila para
     * histórico (auditabilidad + posibilidad de reinvitar con un
     * click), pero deja de reflejarse como vínculo activo.
     */
    public static final String STATUS_UNLINKED = "UNLINKED";
}
