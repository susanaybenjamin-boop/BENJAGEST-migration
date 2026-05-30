package com.benjagest.backend.issuer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Datos que llegan al backend cuando se crea o se actualiza un emisor.
 * Se usa para POST y PUT — la operación cambia, los datos son los mismos.
 *
 * Las anotaciones de Jakarta hacen la validacion automatica antes de que
 * el controller siquiera ejecute: si falta legalName o el email no es
 * valido, Spring devuelve 400 sin tocar el service.
 */
public record IssuerCreateRequest(
        @NotBlank @Size(max = 180) String legalName,
        @NotBlank @Size(max = 32) String taxIdentifier,
        @Size(max = 220) String addressLine,
        @Size(max = 100) String city,
        @Size(max = 100) String province,
        @Size(max = 20) String postalCode,
        @Size(max = 80) String country,
        @Email @Size(max = 180) String email,
        @Size(max = 40) String phone,
        @Size(max = 34) String iban,
        String registryInformation,
        String legalTerms,
        String invoiceFooter
) {
}
