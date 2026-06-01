package com.benjagest.backend.auth.dto;

/**
 * Payload del POST /api/auth/logout. El cliente envia el refresh
 * token actual para revocarlo. Es opcional (no anotado @NotBlank)
 * porque si el cliente lo perdio, el endpoint sigue siendo idempotente:
 * marca el token como revocado solo si llega.
 */
public record LogoutRequest(String refreshToken) {
}
