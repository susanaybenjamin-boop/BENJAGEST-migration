package com.benjagest.backend.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload de actualizacion de los datos de la empresa activa.
 * legalName es obligatorio (la empresa siempre tiene razon social);
 * el resto son opcionales y se guardan como NULL si vienen vacios.
 */
public record CompanyDataUpdateRequest(
        @NotBlank @Size(max = 180) String legalName,
        @Size(max = 180) String tradeName,
        @Size(max = 32) String taxIdentifier,
        @Size(max = 180) String email,
        @Size(max = 40) String phone,
        @Size(max = 180) String website
) {
}
