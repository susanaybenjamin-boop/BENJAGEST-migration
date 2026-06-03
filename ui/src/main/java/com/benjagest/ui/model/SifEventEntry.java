package com.benjagest.ui.model;

/**
 * Una fila de la tabla sif_event_registry vista desde la UI. Los
 * campos son los que pinta la pestaña "Auditoría SIF" en Configuración
 * Facturación (slice VF-EVENTS).
 *
 *   - eventType: codigo tecnico (SYSTEM_START, INVOICE_VALIDATED, ...).
 *   - generatedAtIso: marca temporal serializada como string ISO; la UI
 *                     la formatea para mostrar y la deja tal cual para
 *                     copiar/exportar.
 *   - hashCurrent / hashPrevious: SHA-256 hex en MAYUSCULAS.
 *   - status: PENDING / SIGNED / EXPORTED.
 */
public record SifEventEntry(
        String id,
        String eventType,
        String payload,
        String generatedAtIso,
        String hashCurrent,
        String hashPrevious,
        String status
) {
}
