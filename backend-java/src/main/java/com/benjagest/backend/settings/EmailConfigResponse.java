package com.benjagest.backend.settings;

/**
 * Vista cliente de la configuracion SMTP. Importante: NUNCA devuelve
 * la password (ni cifrada ni descifrada). En su lugar, expone
 * passwordConfigured = TRUE/FALSE para que la UI sepa si hay password
 * guardada o no, sin filtrarla.
 *
 * Cuando el usuario quiere cambiar la password, escribe en el campo
 * y lo envia en el request; si lo deja vacio en el PUT, se mantiene
 * la guardada (Service decide).
 */
public record EmailConfigResponse(
        String smtpHost,
        Integer smtpPort,
        String smtpUser,
        boolean passwordConfigured,
        String fromAddress,
        String fromName,
        String replyTo,
        boolean tlsEnabled,
        boolean authRequired
) {
}
