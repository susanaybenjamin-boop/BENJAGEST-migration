package com.benjagest.ui.model;

import java.time.Instant;

/**
 * Vista UI de una invitación asesoría↔cliente.
 *
 * El campo {@code token} solo se devuelve al creador (asesoría) — el
 * empresario que lista sus pendientes no lo necesita para aceptar
 * (puede aceptar por id si está logueado correctamente), pero el
 * backend lo expone igualmente; la UI no lo muestra al empresario.
 */
public record AdvisoryInvitationEntry(
        String id,
        String advisoryCompanyId,
        String invitedEmail,
        String invitedNif,
        String invitedCompanyName,
        String invitedCompanyId,
        String token,
        String status,
        Instant expiresAt,
        Instant createdAt,
        Instant acceptedAt
) {
    public boolean isPending() { return "PENDING".equalsIgnoreCase(status); }
    public boolean isAccepted() { return "ACCEPTED".equalsIgnoreCase(status); }
}
