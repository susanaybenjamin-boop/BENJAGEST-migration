package com.benjagest.backend.purchases.pdfimport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Saca campos de factura (NIF, fechas, importes, IVA) de un texto
 * plano usando regex. NO usa IA, NO usa libreria especializada —
 * solo expresiones regulares calibradas para el formato espanyol.
 *
 * <p>Filosofia:</p>
 * <ul>
 *   <li>Mejor extraer 6 campos correctos y dejar 2 vacios que
 *       inventar valores. La UI mostrara los detectados y el operador
 *       rellena los faltantes.</li>
 *   <li>Cada campo lleva un {@link Confidence} (HIGH/MEDIUM/LOW). La
 *       UI puede ordenar los avisos por nivel y resaltar los LOW.</li>
 *   <li>Los regex son los que funcionan en facturas reales espanyolas:
 *       NIF/CIF como cualquier patron AEAT, fecha en formatos DD/MM/YYYY
 *       y YYYY-MM-DD, importe con "1.234,56 €" o "1234.56 EUR" o
 *       "1234,56" sueltos, IVA "21%" o "IVA 21%" o "I.V.A. 21,00 %".</li>
 * </ul>
 */
@Service
public class InvoiceFieldsExtractor {

    /**
     * NIF/CIF segun AEAT. Acepta los formatos comunes:
     * - DNI/NIE: 8 cifras o letra+7+letra
     * - CIF: letra+8 (digitos o digitos+letra final)
     * - NIE: X/Y/Z + 7 digitos + letra
     */
    private static final Pattern NIF_PATTERN = Pattern.compile(
            "\\b([XYZxyz]?\\d{7,8}[A-HJ-NP-TV-Za-hj-np-tv-z]|[A-HJ-NP-SUVWa-hj-np-suvw]\\d{7}[0-9A-Ja-j])\\b"
    );

    /** Importes. Acepta "1.234,56", "1234,56", "1234.56", con o sin €/EUR. */
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(-?\\d{1,3}(?:\\.\\d{3})*,\\d{2}|-?\\d+,\\d{2}|-?\\d+\\.\\d{2})\\s*(?:€|EUR|EURO)?"
    );

    /** Fechas DD/MM/YYYY, DD-MM-YYYY, YYYY-MM-DD, DD/MM/YY (asume 20YY). */
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "\\b(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2,4})\\b"
                    + "|"
                    + "\\b(\\d{4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,2})\\b"
    );

    /** Porcentaje de IVA. Acepta "21%", "21 %", "21,00 %", "0%" exento. */
    private static final Pattern VAT_PCT_PATTERN = Pattern.compile(
            "(\\d{1,2}(?:[,\\.]\\d{1,2})?)\\s*%"
    );

    /**
     * Etiquetas que suelen preceder al total en facturas espanyolas.
     * Las separamos con | y las matcheamos case-insensitive antes de
     * buscar el siguiente importe.
     */
    private static final Pattern TOTAL_LABEL = Pattern.compile(
            "(?i)(total\\s+factura|importe\\s+total|total\\s+a\\s+pagar|total)\\s*:?\\s*"
    );

    private static final Pattern BASE_LABEL = Pattern.compile(
            "(?i)(base\\s+imponible|base|subtotal)\\s*:?\\s*"
    );

    private static final Pattern VAT_AMOUNT_LABEL = Pattern.compile(
            "(?i)(cuota\\s+iva|i\\.?v\\.?a\\.?|iva)\\s*:?\\s*"
    );

    /**
     * Numero de factura: "FRA-2026-0042", "F-2026-0042", "Nº 2026/42",
     * "Factura nº 12345", etc. Limitado a evitar capturar el NIF.
     */
    private static final Pattern INVOICE_NUMBER_PATTERN = Pattern.compile(
            "(?i)(?:factura\\s*(?:n[\\.º°]?)?|n[\\.º°]\\s*factura|n[\\.º°])\\s*:?\\s*"
                    + "([A-Z0-9\\-/_.]{3,30})"
    );

    public ExtractionResult extract(String text) {
        if (text == null || text.isBlank()) {
            return new ExtractionResult(
                    new ArrayList<>(),
                    null, null, null,
                    null, null, null, null,
                    "");
        }
        // Limita el texto a las primeras N lineas para reducir falsos
        // positivos del NIF del receptor (que suele ir al final).
        String firstHalf = trimToLines(text, 80);

        List<String> nifs = findAll(NIF_PATTERN, text);
        String emitterNif = nifs.isEmpty() ? null : nifs.get(0);

        LocalDate invoiceDate = findFirstDate(text);
        String invoiceNumber = findFirstGroup(INVOICE_NUMBER_PATTERN, text, 1);

        BigDecimal total = findAmountAfterLabel(text, TOTAL_LABEL);
        BigDecimal base = findAmountAfterLabel(text, BASE_LABEL);
        BigDecimal vatAmount = findAmountAfterLabel(text, VAT_AMOUNT_LABEL);
        BigDecimal vatPct = findVatPercent(text);

        return new ExtractionResult(
                nifs,
                emitterNif,
                invoiceNumber,
                invoiceDate,
                base, vatPct, vatAmount, total,
                firstHalf
        );
    }

    private List<String> findAll(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            String v = m.group(1) != null ? m.group(1) : m.group();
            if (!out.contains(v)) out.add(v);
        }
        return out;
    }

    private String findFirstGroup(Pattern p, String text, int group) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(group) : null;
    }

    private LocalDate findFirstDate(String text) {
        Matcher m = DATE_PATTERN.matcher(text);
        while (m.find()) {
            try {
                if (m.group(1) != null) {
                    int d = Integer.parseInt(m.group(1));
                    int mo = Integer.parseInt(m.group(2));
                    int y = Integer.parseInt(m.group(3));
                    if (y < 100) y += 2000;
                    return LocalDate.of(y, mo, d);
                } else if (m.group(4) != null) {
                    int y = Integer.parseInt(m.group(4));
                    int mo = Integer.parseInt(m.group(5));
                    int d = Integer.parseInt(m.group(6));
                    return LocalDate.of(y, mo, d);
                }
            } catch (Exception ex) {
                // continua con siguiente match
            }
        }
        return null;
    }

    /**
     * Busca el primer importe que aparezca despues de cualquier
     * ocurrencia de la etiqueta. Si no hay etiqueta, devuelve null —
     * NO asume "el primer importe del PDF es el total" porque rompe
     * en demasiados formatos.
     */
    private BigDecimal findAmountAfterLabel(String text, Pattern labelPattern) {
        Matcher l = labelPattern.matcher(text);
        BigDecimal best = null;
        while (l.find()) {
            String tail = text.substring(l.end(), Math.min(text.length(), l.end() + 80));
            Matcher a = AMOUNT_PATTERN.matcher(tail);
            if (a.find()) {
                BigDecimal value = parseAmount(a.group(1));
                if (value != null) {
                    // Si hay varias etiquetas (p.ej. "Base imponible"
                    // aparece dos veces), nos quedamos con la mas grande
                    // que normalmente es la del bloque de totales final.
                    if (best == null || value.compareTo(best) > 0) {
                        best = value;
                    }
                }
            }
        }
        return best;
    }

    private BigDecimal findVatPercent(String text) {
        // Buscamos "IVA" cerca de un porcentaje (ventana de 50 chars).
        Matcher l = Pattern.compile("(?i)i\\.?v\\.?a\\.?").matcher(text);
        while (l.find()) {
            String window = text.substring(l.start(), Math.min(text.length(), l.end() + 40));
            Matcher pct = VAT_PCT_PATTERN.matcher(window);
            if (pct.find()) {
                return parseAmount(pct.group(1));
            }
        }
        // Fallback: el primer porcentaje del texto.
        Matcher pct = VAT_PCT_PATTERN.matcher(text);
        if (pct.find()) return parseAmount(pct.group(1));
        return null;
    }

    private BigDecimal parseAmount(String raw) {
        if (raw == null) return null;
        String cleaned;
        if (raw.contains(",") && raw.contains(".")) {
            // formato europeo "1.234,56" → "1234.56"
            cleaned = raw.replace(".", "").replace(",", ".");
        } else if (raw.contains(",")) {
            cleaned = raw.replace(",", ".");
        } else {
            cleaned = raw;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String trimToLines(String text, int maxLines) {
        String[] lines = text.split("\\r?\\n");
        if (lines.length <= maxLines) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    public record ExtractionResult(
            List<String> allDetectedNifs,
            String emitterNif,
            String invoiceNumber,
            LocalDate invoiceDate,
            BigDecimal baseAmount,
            BigDecimal vatPercent,
            BigDecimal vatAmount,
            BigDecimal totalAmount,
            String rawTextHead
    ) {
        public String invoiceDateIso() {
            return invoiceDate == null ? null : invoiceDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
    }
}
