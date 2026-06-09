package com.benjagest.backend.advisory.documents;

import java.time.Instant;

/**
 * Documento compartido en el canal asesoría ↔ cliente.
 *
 * <p>{@link #direction()} = "A2C" / "C2A" (quién subió).
 * {@link #status()}: UPLOADED → REVIEWED → ACCEPTED | REJECTED.
 * {@link #note()} guarda motivo de rechazo o comentario del revisor.
 */
public record AdvisoryDocument(
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
        String uploadedByUserId,
        String reviewedByUserId,
        Instant reviewedAt,
        Instant createdAt
) {
    public static final String STATUS_UPLOADED = "UPLOADED";
    public static final String STATUS_REVIEWED = "REVIEWED";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String DIRECTION_A2C = "A2C";
    public static final String DIRECTION_C2A = "C2A";
}
