package com.benjagest.ui.model;

/**
 * SUPERV — estado de una tarea automatica (GET /api/supervision/jobs).
 *
 * <p>Las fechas llegan como texto ISO. {@code maxDaysWithoutSuccess} a 0
 * significa que de esa tarea NO se vigila la ausencia, solo los fallos: es el
 * caso de la comprobacion de actualizaciones, que solo corre cuando alguien
 * abre la aplicacion y avisar de que "lleva 5 dias sin comprobarse" seria
 * mentira si simplemente ha sido fin de semana.
 */
public record JobStatusEntry(
        String jobName,
        String label,
        String lastRunAt,
        String lastStatus,
        String lastSuccessAt,
        String lastMessage,
        int maxDaysWithoutSuccess
) {
}
