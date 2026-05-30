package com.benjagest.ui.model;

/**
 * Datos que la UI envia al backend al crear o actualizar un emisor.
 * Mismo conjunto de campos que IssuerCreateRequest en backend pero
 * representado en el dominio de la UI (sin anotaciones de validacion;
 * la validacion la hace el backend al recibir).
 */
public record IssuerCreateRequest(
        String legalName,
        String taxIdentifier,
        String addressLine,
        String city,
        String province,
        String postalCode,
        String country,
        String email,
        String phone,
        String iban,
        String registryInformation,
        String legalTerms,
        String invoiceFooter
) {
}
