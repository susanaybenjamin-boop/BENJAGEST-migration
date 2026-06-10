package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * PORT-4 CLI — Ficha completa de cliente con dirección postal, datos
 * fiscales avanzados, código interno y modo por defecto.
 */
public record CustomerExtendedEntry(
        String id,
        String legalName,
        String tradeName,
        String taxIdentifier,
        String customerType,
        String fiscalType,
        String billingEmail,
        String billingPhone,
        BigDecimal defaultVatPercent,
        BigDecimal defaultRetentionPercent,
        boolean vatExempt,
        String paymentMethod,
        String iban,
        String address,
        String city,
        String province,
        String postalCode,
        String country,
        String internalCode,
        String defaultMode,
        String phone,
        String email,
        String website,
        String notes
) {}
