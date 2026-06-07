package com.benjagest.backend.labor.workcal;

import java.time.Instant;

/**
 * L3-1 — Calendario laboral activo de una empresa para un año.
 *
 * <p>Cada calendario agrupa los festivos efectivos del centro de trabajo:
 * la suma de los nacionales (BOE), los autonómicos de su CCAA y los
 * locales del municipio. El campo {@code active} permite archivar
 * calendarios antiguos sin perderlos — son materia de auditoría
 * laboral.
 */
public record WorkCalendar(
        String id,
        String companyId,
        int year,
        /** Código ISO 3166-2:ES sin "ES-" (MD, CT, AN, …). Null si solo
         *  se quieren los nacionales. */
        String regionCcaa,
        String regionMunicipality,
        String name,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String SCOPE_NATIONAL = "NATIONAL";
    public static final String SCOPE_CCAA = "CCAA";
    public static final String SCOPE_LOCAL = "LOCAL";
}
