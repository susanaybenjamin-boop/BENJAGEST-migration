package com.benjagest.ui.model;

/**
 * Documento compartido asesoría↔cliente.
 * {@code status}: UPLOADED → REVIEWED → ACCEPTED | REJECTED.
 * {@code direction}: A2C (asesoría subió) o C2A (cliente subió).
 */
public record AdvisoryDocumentEntry(
        String id,
        String advisoryCompanyId,
        String clientCompanyId,
        String direction,
        String title,
        String filePath,
        long fileSizeBytes,
        String mimeType,
        String status,
        String note,
        String createdAt,
        String reviewedAt
) {
    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_REVIEWED = "REVIEWED";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";
}
