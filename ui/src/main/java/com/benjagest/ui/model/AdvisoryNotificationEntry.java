package com.benjagest.ui.model;

/**
 * Notificación de la bandeja del asesor.
 *
 * <p>{@code severity}: INFO / WARNING / URGENT (color del icono).
 * {@code notificationType}: catálogo abierto (ver
 * {@code AdvisoryNotification} backend) — el UI solo lo muestra como
 * etiqueta secundaria.
 */
public record AdvisoryNotificationEntry(
        String id,
        String clientCompanyId,
        String notificationType,
        String severity,
        String title,
        String message,
        String entityRef,
        String readAt,
        String createdAt
) {
    public static final String SEVERITY_INFO    = "INFO";
    public static final String SEVERITY_WARNING = "WARNING";
    public static final String SEVERITY_URGENT  = "URGENT";

    public boolean unread() {
        return readAt == null || readAt.isBlank();
    }
}
