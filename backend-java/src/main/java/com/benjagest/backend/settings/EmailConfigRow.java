package com.benjagest.backend.settings;

/**
 * Representacion interna de una fila de company_email_config (con la
 * password ya en formato ciphertext). No sale al cliente — eso lo hace
 * EmailConfigResponse, que oculta el password.
 */
public record EmailConfigRow(
        String smtpHost,
        Integer smtpPort,
        String smtpUser,
        String passwordCiphertext,
        String fromAddress,
        String fromName,
        String replyTo,
        boolean tlsEnabled,
        boolean authRequired
) {
}
