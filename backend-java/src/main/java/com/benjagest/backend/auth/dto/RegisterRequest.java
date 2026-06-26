package com.benjagest.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta de cuenta (bloque REGISTRO). El titular crea su empresa + OWNER.
 *
 * <p>{@code accountType}: "ADVISORY" (asesoría — gestiona cartera) o "BUSINESS"
 * (empresa/autónomo — solo su gestión). Los datos de facturación (razón social,
 * NIF, domicilio) son obligatorios y se vuelcan a Configuración.
 */
public record RegisterRequest(
        @NotBlank @Size(max = 20) String accountType,
        @NotBlank @Size(max = 180) String legalName,
        @NotBlank @Size(max = 32) String taxIdentifier,
        @NotBlank @Size(max = 220) String addressLine,
        @NotBlank @Size(max = 120) String city,
        @Size(max = 120) String province,
        @Size(max = 10) String postalCode,
        @NotBlank @Email @Size(max = 180) String email,
        @NotBlank @Size(max = 160) String displayName,
        @NotBlank @Size(min = 8, max = 200) String password
) {
}
