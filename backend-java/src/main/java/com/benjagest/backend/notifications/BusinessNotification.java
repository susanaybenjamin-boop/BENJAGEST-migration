package com.benjagest.backend.notifications;

import java.time.Instant;

/**
 * Notificación de la bandeja del empresario (modo BUSINESS).
 * Catálogo abierto de {@link #notificationType()}:
 * <ul>
 *   <li>{@code TAX_FILING_DUE_SOON} — vencimiento modelo AEAT.</li>
 *   <li>{@code ADVISORY_MESSAGE} — mensaje recibido de la asesoría.</li>
 *   <li>{@code ADVISORY_DOCUMENT} — documento subido por la asesoría.</li>
 *   <li>{@code INVOICE_OVERDUE} — factura emitida sin cobrar.</li>
 *   <li>{@code SIF_ANOMALY} — anomalía en la cadena hash propia.</li>
 * </ul>
 */
public record BusinessNotification(
        String id,
        String businessCompanyId,
        String relatedCompanyId,
        String notificationType,
        String severity,
        String title,
        String message,
        String entityRef,
        Instant readAt,
        Instant dismissedAt,
        Instant createdAt
) {}
