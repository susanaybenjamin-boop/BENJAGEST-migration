package com.benjagest.ui.model;

public record InvoiceTexts(
        String pie,
        String exempt,
        String reverseCharge,
        String reducedVat,
        String rectifying,
        String legalTerms,
        boolean showIban
) {
}
