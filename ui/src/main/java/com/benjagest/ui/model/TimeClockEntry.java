package com.benjagest.ui.model;

/**
 * Una fila del listado de fichajes vista desde la UI. Los campos son
 * exactamente los que necesita la tabla del módulo Fichajes:
 *   - eventTimeIso: instante del fichaje en ISO (lo formatea la UI).
 *   - eventType: IN / OUT / BREAK_START / BREAK_END.
 *   - origin: WEB / KIOSK / MOBILE / etc.
 *   - status: VALID / CORRECTED / VOIDED.
 */
public record TimeClockEntry(
        String id,
        String employeeId,
        String eventType,
        String eventTimeIso,
        String origin,
        String status
) {
}
