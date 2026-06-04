package com.benjagest.backend.settings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload de actualizacion de los datos de la empresa activa.
 * legalName es obligatorio (la empresa siempre tiene razon social);
 * el resto son opcionales y se guardan como NULL si vienen vacios.
 *
 * Tras V10 incluyo los campos administrativos de emisor (direccion,
 * IBAN, registro mercantil). Tras V22 (consolidacion 2026-06-04), los
 * campos "de factura" (legal_terms, invoice_footer) han salido de esta
 * tabla y viven solo en `invoice_texts`; se editan en
 * Facturacion -> Configuracion -> Textos legales.
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
        String registryInformation
) {
}
