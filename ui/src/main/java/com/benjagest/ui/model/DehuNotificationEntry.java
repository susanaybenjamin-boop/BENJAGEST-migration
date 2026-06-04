package com.benjagest.ui.model;

/** Notificacion DEHu (mirror del backend). */
public record DehuNotificationEntry(
        String id,
        String dehuId,
        String nifReceiver,
        String organismName,
        String organismCode,
        String procedureName,
        String procedureCode,
        String subject,
        String issuedAt,
        String expiresAt,
        String accessedAt,
        String readAt,
        String status,
        String csv,
        String contentUrl,
        String localPdfPath,
        String notes
) {}
