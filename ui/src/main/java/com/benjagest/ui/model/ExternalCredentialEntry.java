package com.benjagest.ui.model;

/**
 * Credencial externa cifrada. La password NUNCA se transporta en GET
 * — solo el booleano passwordConfigured. La UI muestra una checkmark
 * y un input write-only para reemplazarla.
 */
public record ExternalCredentialEntry(
        String id,
        String systemCode,
        String label,
        String username,
        boolean passwordConfigured,
        String authUrl,
        String notes,
        boolean active,
        String lastUsedAt
) {}
