package com.benjagest.ui.model;

/** MEMP-4 — Metadatos de un adjunto de una solicitud de ausencia (sin el contenido). */
public record LeaveAttachmentMeta(
        String id,
        String filename,
        String contentType,
        int sizeBytes
) {}
