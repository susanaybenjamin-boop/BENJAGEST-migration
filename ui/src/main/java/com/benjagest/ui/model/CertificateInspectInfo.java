package com.benjagest.ui.model;

import java.time.Instant;
import java.util.List;

/**
 * Resultado de inspeccionar un .p12 antes de subirlo. La UI usa estos
 * valores para auto-rellenar el formulario.
 */
public record CertificateInspectInfo(
        String subjectName,
        String subjectTaxIdentifier,
        String issuer,
        String certificateTypeGuess,
        Instant validFrom,
        Instant validTo,
        List<String> aliasesInKeystore,
        String subjectDnRaw,
        String issuerDnRaw,
        String serialNumberHex
) {
}
