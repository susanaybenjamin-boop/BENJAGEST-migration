package com.benjagest.ui.model;

/** Una linea del log de uso de certificado digital. */
public record CertificateUsageEntry(
        String id,
        String certificateId,
        String userId,
        String usedAt,
        String purpose,
        String targetUrl,
        boolean success,
        String errorMessage,
        String ipAddress
) {}
