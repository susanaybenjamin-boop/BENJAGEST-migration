package com.benjagest.ui.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Preview del modal CAL-IMPORT. Refleja el record backend
 * {@code HolidayPdfExtractor.ExtractionResult}.
 *
 * <p>Se puebla con lo que el extractor saca del PDF; la UI lo muestra
 * en el panel izquierdo (read-only para no perder lo original) y lo
 * copia al panel derecho editable. El usuario corrige, añade,
 * elimina y luego pulsa "Volcar al calendario".
 */
public record HolidayPdfPreview(
        int year,
        List<DetectedHoliday> holidays
) {
    public record DetectedHoliday(
            LocalDate date,
            String name,
            String scope,
            String confidence,
            String rawSourceLine
    ) {}
}
