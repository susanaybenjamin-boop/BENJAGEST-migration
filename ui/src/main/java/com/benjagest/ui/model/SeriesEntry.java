package com.benjagest.ui.model;

public record SeriesEntry(
        String id,
        String code,
        String invoiceKind,
        String numberingType,
        String formatTemplate,
        int nextNumber,
        Integer currentYear,
        boolean locked,
        boolean active,
        // TPB-2 — Si la serie es de tipo "emitida por tercero", aquí
        // viene el ID de la asesoría que la expide. Cuando es null, la
        // serie es del propio titular (caso normal). El editor usa este
        // campo para preferir la serie TPB cuando la sesión está en
        // modo "actuar como cliente" con acuerdo activo.
        String expeditedByCompanyId
) {
    // Sobrecarga retro-compatible para no romper callers existentes.
    public SeriesEntry(String id, String code, String invoiceKind,
                        String numberingType, String formatTemplate,
                        int nextNumber, Integer currentYear,
                        boolean locked, boolean active) {
        this(id, code, invoiceKind, numberingType, formatTemplate,
                nextNumber, currentYear, locked, active, null);
    }
}
