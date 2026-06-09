package com.benjagest.ui.model;

import java.time.Instant;
import java.util.List;

/**
 * L3-4 — Vista UI de un calendario laboral.
 *
 * <p>Refleja directamente el record backend {@code WorkCalendar} con la
 * lista de festivos opcionalmente cargada en {@link #holidays()}. El
 * listado principal no carga los festivos (solo metadata); el detalle
 * sí, para no traerlos N veces al pintar la tabla.
 */
public record WorkCalendarEntry(
        String id,
        String companyId,
        int year,
        String regionCcaa,
        String regionMunicipality,
        String name,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        List<HolidayEntry> holidays
) {
    /** Devuelve solo metadata sin festivos. */
    public WorkCalendarEntry withoutHolidays() {
        return new WorkCalendarEntry(id, companyId, year, regionCcaa,
                regionMunicipality, name, active, createdAt, updatedAt, List.of());
    }
}
