package com.benjagest.ui.model;

/**
 * Mensaje en el canal asesoría↔cliente.
 *
 * <p>{@code direction}: "A2C" (asesoría → cliente) o "C2A" (cliente →
 * asesoría). El UI pinta burbuja izquierda/derecha según si el mensaje
 * fue enviado por el usuario actual o por la otra parte.
 *
 * <p>{@code readAt} es ISO o vacío si no leído.
 */
public record AdvisoryMessageEntry(
        String id,
        String advisoryCompanyId,
        String clientCompanyId,
        String direction,
        String fromUserId,
        String body,
        String attachmentPath,
        String readAt,
        String createdAt
) {
    public static final String DIRECTION_A2C = "A2C";
    public static final String DIRECTION_C2A = "C2A";
}
