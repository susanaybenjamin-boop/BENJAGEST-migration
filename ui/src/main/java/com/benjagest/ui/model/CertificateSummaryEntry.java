package com.benjagest.ui.model;

import java.time.Instant;

/**
 * Vista UI de un certificado digital.
 *
 * uploadedByAdvisory: true si la asesoría lo subió en nombre del
 * cliente. La UI muestra un badge "Gestionado por tu asesoría".
 */
public record CertificateSummaryEntry(
        String id,
        String alias,
        String certificateType,
        String subjectName,
        String subjectTaxIdentifier,
        boolean passwordConfigured,
        boolean certificateDataPresent,
        Instant validFrom,
        Instant validTo,
        boolean active,
        String uploadedByUserId,
        String uploadedByCompanyId,
        boolean uploadedByAdvisory
) {
}
