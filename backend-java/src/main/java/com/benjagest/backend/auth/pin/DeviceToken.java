package com.benjagest.backend.auth.pin;

import java.time.Instant;

/**
 * L4-1 — Token que empareja un ordenador físico con la asesoría.
 *
 * <p>El secret en plano solo existe en el filesystem del PC y en el
 * momento del handshake inicial. En BD vive {@code tokenHash} (bcrypt)
 * + {@code tokenPrefix} (8 chars del plano, para que el OWNER
 * distinga en "Mis equipos" cuál es cuál sin tener que ir al PC).
 *
 * <p>Máximo 5 tokens activos por asesoría — lo aplica DeviceTokenService
 * al emparejar el sexto.
 */
public record DeviceToken(
        String id,
        String companyId,
        String tokenHash,
        String tokenPrefix,
        String name,
        Instant pairedAt,
        String pairedByUserId,
        Instant lastSeenAt,
        Instant revokedAt,
        String revokedByUserId
) {
    public boolean isActive() {
        return revokedAt == null;
    }
}
