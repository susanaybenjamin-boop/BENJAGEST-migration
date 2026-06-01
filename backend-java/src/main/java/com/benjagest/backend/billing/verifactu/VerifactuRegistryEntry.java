package com.benjagest.backend.billing.verifactu;

import java.time.Instant;

/**
 * Una entrada de la tabla verifactu_registry. Representa el "fingerprint"
 * inmutable de una factura validada bajo VeriFactu.
 */
public record VerifactuRegistryEntry(
        String id,
        String companyId,
        String invoiceId,
        String invoiceNumber,
        String mode,
        String hashCurrent,
        String hashPrevious,
        Instant generatedAt,
        Instant sentAt,
        Instant ackAt,
        String status,
        int retryCount,
        String lastError,
        Instant signedAt,
        String signatureData
) {
}
