package com.benjagest.backend.timeclock;

import java.time.Instant;

/**
 * Un evento de fichaje (entrada/salida/inicio descanso/fin descanso).
 * Inmutable por construcción (RD 8/2019 art. 34.9): no se modifica el
 * original; las correcciones van como filas separadas en
 * {@code time_clock_corrections}.
 */
public record TimeClockEvent(
        String id,
        String companyId,
        String employeeId,
        String customerId,
        String eventType,
        Instant eventTime,
        String origin,
        String status,
        Instant createdAt
) {
}
