package com.benjagest.backend.auth.pin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Body del login por PIN. {@code deviceSecret} es el secret guardado
 * en disco local del PC; el backend resuelve qué asesoría es. PIN
 * son 4-8 dígitos (acordado con Benjamin 2026-06-07).
 */
public record PinLoginRequest(
        @NotBlank String deviceSecret,
        @NotBlank @Pattern(regexp = "\\d{4,8}",
                message = "El PIN debe tener entre 4 y 8 dígitos") String pin
) {}
