package com.benjagest.ui.model;

/** Cliente gestionado por la asesoria actual. */
public record ManagedClientEntry(
        String id,
        String legalName,
        String tradeName,
        String taxIdentifier,
        String companyType,
        String email,
        String phone,
        String city,
        String province
) {}
