package com.benjagest.ui.model;

public record CustomerSummary(
        String id,
        String legalName,
        String taxIdentifier,
        String email,
        String phone
) {
}
