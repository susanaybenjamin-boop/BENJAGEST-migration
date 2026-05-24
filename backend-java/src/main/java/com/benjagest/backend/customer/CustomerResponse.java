package com.benjagest.backend.customer;

import java.time.Instant;

public record CustomerResponse(
        String id,
        String legalName,
        String tradeName,
        String taxIdentifier,
        String primaryContactName,
        String email,
        String phone,
        Instant createdAt
) {
}
