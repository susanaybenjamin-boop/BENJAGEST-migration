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
        /**
         * Tipo: FESTIVO (festivo legal no laborable) | AJUSTE (día de
         * ajuste de jornada del convenio colectivo) | CIERRE (cierre
         * propio de la empresa). Solo los FESTIVO consumen el tope
         * legal de 14/año (Art. 37.2 ET).
         */
        String holidayType,
        Instant createdAt
) {
    public static final String TYPE_FESTIVO = "FESTIVO";
    public static final String TYPE_AJUSTE = "AJUSTE";
    public static final String TYPE_CIERRE = "CIERRE";
}
