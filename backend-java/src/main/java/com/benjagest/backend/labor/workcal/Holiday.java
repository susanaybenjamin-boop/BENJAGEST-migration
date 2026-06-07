package com.benjagest.backend.labor.workcal;

import java.time.Instant;
import java.time.LocalDate;

/**
 * L3-1 — Festivo concreto dentro de un calendario laboral. El campo
 * {@code scope} es la garantía legal de cada fecha: si Inspección
 * pregunta por qué el 12 de octubre es no laborable, la respuesta es
 * "NATIONAL — Real Decreto del calendario laboral del año en curso".
 */
public record Holiday(
        String id,
        String workCalendarId,
        LocalDate holidayDate,
        String name,
        /** NATIONAL / CCAA / LOCAL — ver {@link WorkCalendar}. */
        String scope,
        boolean isPaid,
        String notes,
        Instant createdAt
) {
}
