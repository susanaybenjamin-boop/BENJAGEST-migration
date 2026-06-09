package com.benjagest.backend.labor.workcal;

import com.benjagest.backend.purchases.pdfimport.PdfTextExtractor;
import java.io.IOException;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Extractor de festivos desde PDFs de calendario laboral.
 *
 * <p>Pensado para los PDFs que publica cada CCAA y que el usuario
 * descarga (BOE, BOJA, BOPV, DOGC, etc.) o el PDF del convenio
 * colectivo de su empresa. Reproduce el flujo de CONTENDO:
 * {@code calendarioParser.v3.js} → ocrEngine.
 *
 * <h2>Estrategia</h2>
 * <ol>
 *   <li>Reutiliza {@link PdfTextExtractor#extract(byte[])} para sacar
 *       el texto plano.</li>
 *   <li>Detecta el año del calendario por contexto (primer año YYYY
 *       que aparezca, fallback al año actual).</li>
 *   <li>Lee línea a línea y busca patrones de fecha en español:
 *       <ul>
 *         <li>{@code 1 de enero}, {@code 15 agosto}, {@code 6/12/2026}.</li>
 *         <li>{@code 2026-12-25}, {@code 25/12/26}, {@code 25-12-2026}.</li>
 *       </ul></li>
 *   <li>Captura el texto cercano como descripción del festivo.</li>
 *   <li>Clasifica scope (NATIONAL/CCAA/LOCAL) por keywords:
 *       <ul>
 *         <li>"nacional" / "estatal" → NATIONAL.</li>
 *         <li>"autonómic*" / "comunidad" / nombres de CCAA → CCAA.</li>
 *         <li>"local" / "municipal" / "patrón" / "patrona" → LOCAL.</li>
 *         <li>Por defecto: CCAA (lo más común en PDFs de convenio).</li>
 *       </ul></li>
 *   <li>Asigna confidence HIGH/MEDIUM/LOW para que la UI pinte
 *       badges y el usuario sepa a qué prestar atención.</li>
 * </ol>
 *
 * <p>NO impone tope de 14 — eso lo hace el Service al volcar. Aquí
 * extraemos TODO lo que parece festivo; el usuario corregirá en el
 * modal antes de persistir.
 */
@Service
public class HolidayPdfExtractor {

    private final PdfTextExtractor pdf;

    public HolidayPdfExtractor(PdfTextExtractor pdf) {
        this.pdf = pdf;
    }

    /** Resultado de un PDF entero. */
    public record ExtractionResult(
            int year,
            List<DetectedHoliday> holidays,
            String rawTextHead,
            int totalLines
    ) {}

    /**
     * Una fila candidata para volcar al calendario. La UI presenta
     * estas filas en el panel "Detectados (lo que ha sacado el
     * importador)"; el usuario puede editar inline antes del volcado.
     */
    public record DetectedHoliday(
            LocalDate date,
            String name,
            String scope,
            String confidence,
            String rawSourceLine
    ) {}

    public ExtractionResult extract(byte[] pdfBytes) throws IOException {
        String text = pdf.extract(pdfBytes);
        if (text == null || text.isBlank()) {
            return new ExtractionResult(LocalDate.now().getYear(),
                    List.of(), "", 0);
        }
        int year = detectYear(text);
        String[] lines = text.split("\\r?\\n");
        List<DetectedHoliday> out = new ArrayList<>();
        Set<LocalDate> seen = new HashSet<>();  // dedup por fecha
        for (String raw : lines) {
            String line = clean(raw);
            if (line.isEmpty()) continue;
            // Buscar fecha en la línea — distintos patrones, en
            // orden de prioridad (los más explícitos primero).
            LocalDate date = parseDateAny(line, year);
            if (date == null) continue;
            if (seen.contains(date)) continue;
            seen.add(date);
            String name = extractName(line, date);
            String scope = classifyScope(line, name);
            String confidence = computeConfidence(line, name);
            out.add(new DetectedHoliday(date, name, scope, confidence, line));
        }
        return new ExtractionResult(year, out,
                text.length() > 500 ? text.substring(0, 500) : text,
                lines.length);
    }

    // ============================================================
    //  Detección de año
    // ============================================================

    private static final Pattern YEAR_PATTERN =
            Pattern.compile("\\b(20\\d{2})\\b");

    private int detectYear(String text) {
        Matcher m = YEAR_PATTERN.matcher(text);
        // El primer año que aparezca suele ser el del título; nos
        // quedamos con él. Si no hay, año actual.
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException ex) { /* fallthrough */ }
        }
        return LocalDate.now().getYear();
    }

    // ============================================================
    //  Parsers de fecha
    // ============================================================

    /** Captura "15/8/2026", "15-08-2026", "2026-08-15", "15/08/26". */
    private static final Pattern NUMERIC_DATE =
            Pattern.compile("(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{2,4})|"
                    + "(\\d{4})[/\\-.](\\d{1,2})[/\\-.](\\d{1,2})");

    /** Captura "15 de enero", "1 enero", "15 de agosto de 2026". */
    private static final Pattern SPANISH_DATE =
            Pattern.compile("(\\d{1,2})\\s+(?:de\\s+)?(enero|febrero|marzo|abril|mayo|junio|"
                    + "julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre)"
                    + "(?:\\s+(?:de\\s+)?(\\d{4}))?",
                    Pattern.CASE_INSENSITIVE);

    private LocalDate parseDateAny(String line, int contextYear) {
        // Spanish dates como "1 de enero" son más fiables porque
        // garantizan que es una fecha; los numéricos pueden chocar
        // con códigos postales, NIFs, etc.
        Matcher es = SPANISH_DATE.matcher(line);
        if (es.find()) {
            try {
                int day = Integer.parseInt(es.group(1));
                Month month = monthFromSpanish(es.group(2));
                int year = es.group(3) != null
                        ? Integer.parseInt(es.group(3)) : contextYear;
                if (day >= 1 && day <= 31 && month != null) {
                    return LocalDate.of(year, month, day);
                }
            } catch (Exception ex) { /* fall through */ }
        }
        Matcher num = NUMERIC_DATE.matcher(line);
        if (num.find()) {
            try {
                int day, month, year;
                if (num.group(1) != null) {
                    // DD/MM/YYYY o DD/MM/YY
                    day = Integer.parseInt(num.group(1));
                    month = Integer.parseInt(num.group(2));
                    String yStr = num.group(3);
                    year = yStr.length() == 2
                            ? 2000 + Integer.parseInt(yStr)
                            : Integer.parseInt(yStr);
                } else {
                    // YYYY-MM-DD
                    year = Integer.parseInt(num.group(4));
                    month = Integer.parseInt(num.group(5));
                    day = Integer.parseInt(num.group(6));
                }
                if (day >= 1 && day <= 31 && month >= 1 && month <= 12
                        && year >= 2020 && year <= 2050) {
                    return LocalDate.of(year, month, day);
                }
            } catch (Exception ex) { /* fall through */ }
        }
        return null;
    }

    private Month monthFromSpanish(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "enero" -> Month.JANUARY;
            case "febrero" -> Month.FEBRUARY;
            case "marzo" -> Month.MARCH;
            case "abril" -> Month.APRIL;
            case "mayo" -> Month.MAY;
            case "junio" -> Month.JUNE;
            case "julio" -> Month.JULY;
            case "agosto" -> Month.AUGUST;
            case "septiembre", "setiembre" -> Month.SEPTEMBER;
            case "octubre" -> Month.OCTOBER;
            case "noviembre" -> Month.NOVEMBER;
            case "diciembre" -> Month.DECEMBER;
            default -> null;
        };
    }

    // ============================================================
    //  Extracción de nombre
    // ============================================================

    private String extractName(String line, LocalDate date) {
        // Estrategia: quitar la fecha de la línea y limpiar lo que queda.
        // Si la línea trae "1 de enero — Año Nuevo", nos queda "Año Nuevo".
        String cleaned = line
                .replaceAll("(?i)\\b(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{2,4})\\b", "")
                .replaceAll("(?i)\\b(\\d{4})[/\\-.](\\d{1,2})[/\\-.](\\d{1,2})\\b", "")
                .replaceAll("(?i)(\\d{1,2})\\s+(?:de\\s+)?(enero|febrero|marzo|abril|mayo|"
                        + "junio|julio|agosto|septiembre|setiembre|octubre|noviembre|"
                        + "diciembre)(?:\\s+(?:de\\s+)?\\d{4})?", "")
                .replaceAll("[\\-•·●▪►:]+", " ")  // separadores
                .replaceAll("\\s+", " ")
                .trim();
        // Si lo que queda es muy corto, intentar deducir un nombre
        // por la fecha (fallback con clásicos).
        if (cleaned.length() < 3) {
            return guessNameByDate(date);
        }
        // Si lo que queda empieza con minúscula y la palabra siguiente
        // empieza con mayúscula, asumimos que la primera palabra es
        // un separador ("día Año Nuevo" → "Año Nuevo").
        if (cleaned.length() > 5
                && Character.isLowerCase(cleaned.charAt(0))
                && cleaned.contains(" ")) {
            int sp = cleaned.indexOf(' ');
            String tail = cleaned.substring(sp + 1).trim();
            if (!tail.isEmpty() && Character.isUpperCase(tail.charAt(0))) {
                return tail;
            }
        }
        return cleaned;
    }

    /** Conocidos: si solo tenemos la fecha y no hay texto cercano. */
    private String guessNameByDate(LocalDate d) {
        return switch (d.getMonth()) {
            case JANUARY -> d.getDayOfMonth() == 1 ? "Año Nuevo"
                    : (d.getDayOfMonth() == 6 ? "Reyes" : "");
            case MAY -> d.getDayOfMonth() == 1 ? "Día del Trabajo" : "";
            case AUGUST -> d.getDayOfMonth() == 15 ? "Asunción de la Virgen" : "";
            case OCTOBER -> d.getDayOfMonth() == 12 ? "Fiesta Nacional" : "";
            case NOVEMBER -> d.getDayOfMonth() == 1 ? "Todos los Santos" : "";
            case DECEMBER -> switch (d.getDayOfMonth()) {
                case 6 -> "Día de la Constitución";
                case 8 -> "Inmaculada Concepción";
                case 25 -> "Navidad";
                default -> "";
            };
            default -> "";
        };
    }

    // ============================================================
    //  Clasificación de scope
    // ============================================================

    private static final Set<String> NATIONAL_KEYWORDS = Set.of(
            "nacional", "estatal", "estado", "españa");
    private static final Set<String> LOCAL_KEYWORDS = Set.of(
            "local", "municipal", "patrón", "patrona", "feria", "fiesta del pueblo",
            "patronal");
    private static final Set<String> CCAA_KEYWORDS = Set.of(
            "autonómico", "autonomico", "comunidad", "autonómica", "autonomica",
            "andalucía", "andalucia", "aragón", "aragon", "asturias", "baleares",
            "canarias", "cantabria", "castilla", "cataluña", "catalunya", "cataluna",
            "valencia", "valenciana", "extremadura", "galicia", "madrid", "murcia",
            "navarra", "vasco", "euskadi", "rioja", "ceuta", "melilla");

    private String classifyScope(String line, String name) {
        String low = (line + " " + name).toLowerCase(Locale.ROOT);
        if (NATIONAL_KEYWORDS.stream().anyMatch(low::contains)) {
            return WorkCalendar.SCOPE_NATIONAL;
        }
        if (LOCAL_KEYWORDS.stream().anyMatch(low::contains)) {
            return WorkCalendar.SCOPE_LOCAL;
        }
        if (CCAA_KEYWORDS.stream().anyMatch(low::contains)) {
            return WorkCalendar.SCOPE_CCAA;
        }
        // Default CCAA — los PDFs de convenio típicamente listan
        // autonómicos. Si el extractor se equivoca, el usuario corrige.
        return WorkCalendar.SCOPE_CCAA;
    }

    // ============================================================
    //  Confianza
    // ============================================================

    /**
     * Confidence ayuda al UI a pintar badges:
     *   HIGH: la línea contiene fecha completa + nombre razonable.
     *   MEDIUM: fecha clara pero nombre dudoso o muy corto.
     *   LOW: fallback (solo fecha sin contexto).
     */
    private String computeConfidence(String line, String name) {
        if (name == null || name.isBlank()) return "LOW";
        if (name.length() < 3) return "LOW";
        if (line.length() > 30 && name.length() > 5) return "HIGH";
        return "MEDIUM";
    }

    // ============================================================
    //  Utilidad
    // ============================================================

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace(' ', ' ')  // NBSP
                .replace('–', '-')   // en-dash
                .replace('—', '-')   // em-dash
                .replace('’', '\'')  // typographic apostrophe
                .trim();
    }
}
