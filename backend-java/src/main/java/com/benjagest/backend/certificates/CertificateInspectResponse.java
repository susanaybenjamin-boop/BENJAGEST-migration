package com.benjagest.backend.certificates;

import java.time.Instant;
import java.util.List;

/**
 * Resultado de inspeccionar un .p12 sin persistirlo. Lo que la UI usa
 * para auto-rellenar los campos del diálogo de alta.
 *
 *   - subjectName: CN del DN del certificado (ej. "RECIO LOPEZ BENJAMIN").
 *   - subjectTaxIdentifier: NIF extraído del serialNumber o CN
 *     (FNMT y similares lo ponen como "IDCES-W0184081H" o "12345678Z").
 *   - issuer: organización emisora ("FNMT-RCM", "Camerfirma SA"…).
 *   - certificateTypeGuess: PERSONA_FISICA / REPRESENTANTE / OTRO
 *     según patrones reconocidos.
 *   - validFrom / validTo: NotBefore / NotAfter del certificado X.509.
 *   - aliasesInKeystore: alias internos del .p12 (algunos keystores
 *     tienen varios, mostramos solo el primero pero los exponemos).
 */
public record CertificateInspectResponse(
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
