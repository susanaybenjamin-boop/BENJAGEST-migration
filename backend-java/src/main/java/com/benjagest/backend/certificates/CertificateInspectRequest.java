package com.benjagest.backend.certificates;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload de POST /api/certificates/inspect. El cliente envia el
 * contenido del .p12/.pfx en base64 + la password; el backend abre
 * el keystore y devuelve los metadatos del certificado SIN persistir
 * nada. Sirve para auto-rellenar el formulario de subida (subject,
 * NIF, validez) antes de que el usuario confirme.
 *
 * Es una operacion idempotente y sin efectos secundarios.
 */
public record CertificateInspectRequest(
        @NotBlank @Size(max = 12_000_000) String certificateDataBase64,
        @Size(max = 200) String password
) {
}
