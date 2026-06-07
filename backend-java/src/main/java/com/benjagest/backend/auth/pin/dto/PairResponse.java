package com.benjagest.backend.auth.pin.dto;

/**
 * Respuesta del emparejado. {@code deviceSecret} es el ÚNICO momento en
 * que se devuelve en plano: la UI debe guardarlo en su config local
 * inmediatamente; el backend solo conserva su hash.
 */
public record PairResponse(
        String deviceId,
        String deviceSecret,
        String companyId,
        String companyName,
        String pairedByUserId
) {}
