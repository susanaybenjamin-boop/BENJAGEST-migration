package com.benjagest.ui.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * L3-4 — Vista UI de un festivo del calendario laboral.
 *
 * <p>Refleja el record backend {@code Holiday}. {@link #scope()} puede
 * ser NATIONAL, CCAA o LOCAL — usado para badges visuales y filtros.
 */
public record HolidayEntry(
        String id,
        String workCalendarId,
        LocalDate holidayDate,
        String name,
        String scope,
        boolean isPaid,
        String notes,
        Instant createdAt
) {}
