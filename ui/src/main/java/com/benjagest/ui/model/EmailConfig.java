package com.benjagest.ui.model;

/**
 * Configuracion SMTP tal como la devuelve GET /api/settings/email-config.
 * passwordConfigured es informativo: indica si hay una password guardada,
 * pero NUNCA exponemos la password real al cliente.
 */
public record EmailConfig(
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
