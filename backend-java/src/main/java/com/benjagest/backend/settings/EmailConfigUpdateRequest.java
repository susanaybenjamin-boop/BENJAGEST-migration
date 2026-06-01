package com.benjagest.backend.settings;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Payload de PUT /api/settings/email-config.
 *
 * Convencion sobre la password: si viene null o vacia, la password
 * existente se conserva. Si viene con valor, se cifra y reemplaza.
 * Asi la UI puede dejar el campo en blanco al editar el resto del
 * formulario sin perder la password configurada.
 */
public record EmailConfigUpdateRequest(
        @Size(max = 180) String smtpHost,
        @Min(1) @Max(65535) Integer smtpPort,
        @Size(max = 180) String smtpUser,
        @Size(max = 200) String smtpPassword,
        @Email @Size(max = 180) String fromAddress,
        @Size(max = 180) String fromName,
        @Email @Size(max = 180) String replyTo,
        Boolean tlsEnabled,
        Boolean authRequired
) {
}
