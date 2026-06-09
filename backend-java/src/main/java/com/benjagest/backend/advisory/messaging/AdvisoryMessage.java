package com.benjagest.backend.advisory.messaging;

import java.time.Instant;

/**
 * Mensaje del canal asesoría ↔ cliente.
 *
 * <p>{@link #direction()} = "A2C" (asesoría → cliente) o "C2A" (cliente
 * → asesoría). El frontend renderiza el timeline en orden cronológico
 * mezclado, con burbujas a la izquierda o derecha según dirección
 * (UX clásica tipo chat).
 *
 * <p>{@link #attachmentPath()} es opcional y apunta a un archivo en el
 * mismo storage que se usa para PDFs de facturas. El UI ofrece botón
 * "Adjuntar" en el editor del mensaje.
 *
 * <p>{@link #readAt()} se marca cuando el destinatario abre el thread.
 * NULL = no leído. El backend devuelve el contador agregado por
 * thread para el badge del sidebar.
 */
public record AdvisoryMessage(
        String id,
        String advisoryCompanyId,
        String clientCompanyId,
        String direction,
        String fromUserId,
        String body,
        String attachmentPath,
        Instant readAt,
        Instant createdAt
) {
    public static final String DIRECTION_A2C = "A2C";
    public static final String DIRECTION_C2A = "C2A";
}
