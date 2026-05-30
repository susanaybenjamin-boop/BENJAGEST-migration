package com.benjagest.ui.model;

/**
 * Lo que la UI necesita saber de un emisor para listarlo o editarlo.
 * No incluye los campos de texto largo (registro mercantil, condiciones
 * legales, pie de factura) — esos se cargaran solo cuando se abra el
 * dialogo de edicion individual.
 */
public record IssuerSummary(
        String id,
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
        String invoiceFooter,
        boolean active
) {
}
