package com.benjagest.ui.support;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formato de moneda y valores para la UI. Extraido del God Object en UIR-3. */
public final class Formatters {

    private Formatters() {}

    public static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    public static final NumberFormat CURRENCY_FORMAT =
            NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-ES"));

    public static String money(BigDecimal value) {
        return CURRENCY_FORMAT.format(value == null ? BigDecimal.ZERO : value);
    }

    public static String money(String value) {
        if (value == null || value.isBlank()) {
            return CURRENCY_FORMAT.format(BigDecimal.ZERO);
        }
        try {
            return CURRENCY_FORMAT.format(new BigDecimal(value.replace(",", "")));
        } catch (NumberFormatException exception) {
            return value + " €";
        }
    }

    public static String displayValue(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.endsWith(" EUR")) {
            return money(value.substring(0, value.length() - 4));
        }
        if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return LocalDate.parse(value).format(DISPLAY_DATE);
        }
        return value;
    }

    /**
     * Instante ISO -> "yyyy-MM-dd HH:mm:ss" en hora LOCAL. Si trae zona
     * (sufijo Z u offset) lo convierte a la zona del sistema; si no, recorta.
     */
    public static String shortIso(String iso) {
        if (iso == null || iso.length() < 19) {
            return iso == null ? "" : iso;
        }
        try {
            if (iso.endsWith("Z") || iso.matches(".*[+-]\\d\\d:?\\d\\d$")) {
                return java.time.OffsetDateTime.parse(iso)
                        .atZoneSameInstant(java.time.ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
        } catch (Exception ignored) {
            // formato inesperado -> recorte simple de abajo
        }
        return iso.substring(0, 10) + " " + iso.substring(11, 19);
    }
}
