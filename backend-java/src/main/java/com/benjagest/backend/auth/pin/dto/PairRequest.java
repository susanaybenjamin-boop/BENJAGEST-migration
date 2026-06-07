package com.benjagest.backend.auth.pin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body de POST /api/auth/devices/pair. El OWNER introduce sus
 * credenciales SaaS clásicas y el nombre que quiere darle al PC
 * que está emparejando ("Mostrador", "Despacho Marta", "Portátil").
 */
public record PairRequest(
        @NotBlank String email,
        @NotBlank String password,
        @NotBlank String deviceName
) {}
