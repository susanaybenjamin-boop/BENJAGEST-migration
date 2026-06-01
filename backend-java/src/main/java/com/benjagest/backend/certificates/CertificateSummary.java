package com.benjagest.backend.certificates;

import java.time.Instant;

/**
 * Vista cliente de un certificado SIN los campos sensibles. Lo que ve
 * un OWNER/ADMIN en la pantalla de gestion de certificados.
 *
 * passwordConfigured permite a la UI saber si hay password guardada
 * sin tener que exponerla; certificateDataPresent idem para el binario.
 */
public record CertificateSummary(
        String id,
        String alias,
        String certificateType,
        String subjectName,
        String subjectTaxIdentifier,
        boolean passwordConfigured,
        boolean certificateDataPresent,
        Instant validFrom,
        Instant validTo,
        boolean active
) {
}
