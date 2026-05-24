package com.benjagest.ui.model;

public record CustomerCreateRequest(
        String legalName,
        String tradeName,
        String taxIdentifier,
        String contactName,
        String email,
        String phone
) {
}
