package com.benjagest.ui.support;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Comparadores de columnas de tabla reutilizables. Extraido del God Object en UIR-5c. */
public final class Comparators {

    private Comparators() {}

    /** Ordena strings que representan importes por su valor numerico (null al final). */
    public static final Comparator<String> NUMERIC_STRING_COMPARATOR = (a, b) -> {
        BigDecimal va = parseDecimal(a);
        BigDecimal vb = parseDecimal(b);
        if (va == null && vb == null) return 0;
        if (va == null) return 1;
        if (vb == null) return -1;
        return va.compareTo(vb);
    };

    /** Ordena fechas ISO ("yyyy-MM-dd"); null/vacios al final. */
    public static final Comparator<String> ISO_DATE_COMPARATOR = (a, b) -> {
        boolean ea = a == null || a.isBlank();
        boolean eb = b == null || b.isBlank();
        if (ea && eb) return 0;
        if (ea) return 1;
        if (eb) return -1;
        return a.compareTo(b);
    };

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Matcher m = Pattern
                .compile("-?\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d+)?|-?\\d+(?:[.,]\\d+)?")
                .matcher(raw);
        if (!m.find()) return null;
        String token = m.group();
        if (token.contains(",") && token.contains(".")) {
            token = token.replace(".", "").replace(",", ".");
        } else if (token.contains(",")) {
            token = token.replace(",", ".");
        }
        try {
            return new BigDecimal(token);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
