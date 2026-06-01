package com.benjagest.backend.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload de actualizacion de los datos de la empresa activa.
 * legalName es obligatorio (la empresa siempre tiene razon social);
 * el resto son opcionales y se guardan como NULL si vienen vacios.
 *
 * Tras la unificacion V10, incluye tambien los campos de emisor de
 * facturas (direccion, IBAN, registro mercantil, textos de pie y
 * condiciones legales) que antes vivian en `issuers`.
 */
public record CompanyDataUpdateRequest(
        @NotBlank @Size(max = 180) String legalName,
        @Size(max = 180) String tradeName,
        @Size(max = 32) String taxIdentifier,
        @Size(max = 180) String email,
        @Size(max = 40) String phone,
        @Size(max = 180) String website,
        @Size(max = 220) String addressLine,
        @Size(max = 100) String city,
        @Size(max = 100) String province,
        @Size(max = 20) String postalCode,
        @Size(max = 80) String country,
        @Size(max = 34) String iban,
        String registryInformation,
        String legalTerms,
        String invoiceFooter
) {
}
