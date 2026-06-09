package com.benjagest.backend.advisory.notifications;

import java.time.Instant;

/**
 * Notificación interna de la bandeja del asesor. Distinto de
 * {@code audit_events} (forense): aquí solo hitos accionables.
 *
 * <p>Tipos en {@link #notificationType()} (catálogo abierto, vendrá
 * creciendo según se conecten hooks):
 * <ul>
 *   <li>{@code CLIENT_UPLOADED_DOC} — cliente subió un documento.</li>
 *   <li>{@code TAX_FILING_DUE_SOON} — vencimiento próximo de modelo AEAT.</li>
 *   <li>{@code CONTRACT_EXPIRING} — contrato del cliente termina.</li>
 *   <li>{@code INVITATION_ACCEPTED} — cliente aceptó invitación.</li>
 *   <li>{@code INVITATION_REJECTED} — cliente rechazó invitación.</li>
 *   <li>{@code INVOICE_OVERDUE} — factura del cliente vencida sin cobrar.</li>
 *   <li>{@code SIF_ANOMALY} — anomalía en la cadena hash del cliente.</li>
 * </ul>
 *
 * <p>{@link #entityRef()} formato libre {@code "tipo:uuid"} para
 * navegación contextual del UI (ej. {@code "contract:8a7b..."}).
 */
public record AdvisoryNotification(
        String id,
        String advisoryCompanyId,
        String clientCompanyId,
        String notificationType,
        String severity,
        String title,
        String message,
        String entityRef,
        Instant readAt,
        Instant dismissedAt,
        Instant createdAt
) {}
