package com.benjagest.backend.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion del JWT: secreto compartido y tiempos de vida.
 * Se lee de application.yml bajo "benjagest.jwt.*".
 *
 * En produccion el secret DEBE venir por env var BENJAGEST_JWT_SECRET;
 * el default que esta en application.yml es solo para desarrollo.
 */
@ConfigurationProperties(prefix = "benjagest.jwt")
public record JwtProperties(
        String secret,
        long accessTtlMinutes,
        long refreshTtlMinutes
) {
}
