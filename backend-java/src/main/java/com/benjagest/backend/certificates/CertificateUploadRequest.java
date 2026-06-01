package com.benjagest.backend.certificates;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Payload de POST /api/certificates. El cliente envia el contenido del
 * .p12/.pfx en base64 + la password en claro (sobre TLS). El backend
 * lo cifra antes de persistir.
 *
 * Importante: la password viaja en claro en el cuerpo HTTPS, no en la
 * URL. Asegurar que el TLS no se desactiva en produccion.
 */
public record CertificateUploadRequest(
        @NotBlank @Size(max = 160) String alias,
        @NotBlank @Size(max = 40) String certificateType,
        @Size(max = 220) String subjectName,
        @Size(max = 32) String subjectTaxIdentifier,
        @Size(max = 200) String password,
        @Size(max = 12_000_000) String certificateDataBase64,
        Instant validFrom,
        Instant validTo
) {
}
