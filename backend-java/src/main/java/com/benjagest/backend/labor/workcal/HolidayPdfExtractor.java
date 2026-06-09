package com.benjagest.backend.labor.workcal;

import com.benjagest.backend.purchases.pdfimport.PdfTextExtractor;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Extractor de festivos desde PDFs de calendario laboral.
 *
 * <h2>Port fiel del parser de CONTENDO</h2>
 *
 * <p>Este código es un port a Java de
 * {@code backend/src/services/ocr/calendarioParser.v3.js} de CONTENDO
 * (RUTA: {@code C:\Proyectos\CONTENDO GESTIONES}). La versión inicial
 * que escribí buscaba fechas explícitas tipo {@code "1 de enero"} o
 * {@code "15/8/2026"}, que casi nunca aparecen en los PDFs reales que
 * los usuarios descargan del Boletín Oficial Provincial / CCAA — esos
 * PDFs están organizados por cabecera de mes ({@code ENERO}) seguidas
 * de líneas con día de la semana ({@code Miércoles 1 Día de Año
 * Nuevo}). Benjamin lo destapó subiendo un PDF real (2026-06-09).
 *
 * <h2>Algoritmo</h2>
 * <ol>
 *   <li>Normaliza texto: aplica {@link #OCR_FIXES} (taborables→laborables,
 *       convento→convenio, etc.) y caracteres tipográficos.</li>
 *   <li>Adivina el año: cuenta apariciones de {@code 20XX} y se queda
 *       con el más frecuente.</li>
 *   <li>Recorre línea por línea:
 *     <ul>
 *       <li>Si la línea es cabecera de mes ({@code ENERO}, {@code
 *           FEBRERO}…) guarda {@code currentMonth} y entra en modo
 *           "parsing".</li>
 *       <li>Si está en sección "stop" (acuerdo de jornada, notas, etc.)
 *           y ya había empezado parsing, corta.</li>
 *       <li>Si la línea empieza con día de la semana ({@code Miércoles
 *           1 …}), extrae día + descripción.</li>
 *       <li>Cualquier otra línea se ignora — por eso el ruido tipo
 *           "BBoolleettíínn" no entra como falso positivo.</li>
 *     </ul>
 *   </li>
 *   <li>Clasifica cada item por keywords (nacional/autonómico/local/
 *       cierre/convenio/laborable_extra).</li>
 *   <li>Dedupe por fecha priorizando mayor confianza + rank de tipo.</li>
 * </ol>
 *
 * <p>Mapeo al modelo BENJAGEST: como BENJAGEST solo tiene scope
 * NATIONAL/CCAA/LOCAL, mapeamos:
 * <ul>
 *   <li>label=nacional → {@code NATIONAL}</li>
 *   <li>label=autonómico → {@code CCAA}</li>
 *   <li>label=local → {@code LOCAL}</li>
 *   <li>cierre_empresa → {@code LOCAL} (cierre propio de la empresa)</li>
 *   <li>cualquier otro → {@code LOCAL}</li>
 * </ul>
 *
 * <p>Solo los items con {@code es_laborable=false} (festivos reales y
 * cierres de empresa) se devuelven por defecto. Los ajustes de convenio
 * y laborables extra el usuario los añade manualmente en el modal si
 * los quiere — el sistema actual de BENJAGEST solo modela festivos.
 */
@Service
public class HolidayPdfExtractor {

    private final PdfTextExtractor pdf;

    public HolidayPdfExtractor(PdfTextExtractor pdf) {
        this.pdf = pdf;
    }

    public record ExtractionResult(
            int year,
            List<DetectedHoliday> holidays,
            String rawTextHead,
            int totalLines
    ) {}

    public record DetectedHoliday(
            LocalDate date,
            String name,
            String scope,
            String confidence,
            String rawSourceLine
    ) {}

    public ExtractionResult extract(byte[] pdfBytes) throws IOException {
        String raw = pdf.extract(pdfBytes);
        if (raw == null || raw.isBlank()) {
            return new ExtractionResult(LocalDate.now().getYear(), List.of(), "", 0);
        }
        String normalized = normalizeRaw(raw);
        int year = guessYear(normalized);

        String[] rawLines = normalized.split("\\r?\\n");
        List<String> lines = new ArrayList<>();
        for (String l : rawLines) {
            String c = cleanLine(l);
            if (!c.isEmpty()) lines.add(c);
        }

        Integer currentMonth = null;
        boolean parsingEvents = false;
        List<DetectedHoliday> items = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String lower = line.toLowerCase(Locale.ROOT);

            // Stop sections: rompen el parsing si ya estábamos parseando.
            if (isStopSection(lower)) {
                if (parsingEvents) break;
                continue;
            }

            // Cabecera de mes ("ENERO", "FEBRERO"…).
            Integer mh = findMonthHeader(lower);
            if (mh != null) {
                currentMonth = mh;
                parsingEvents = true;
                continue;
            }

            // Hasta no detectar mes, no parseamos nada.
            if (currentMonth == null) continue;

            ParsedDay parsed = parseDayLine(line);
            if (parsed == null) continue;

            Classification cls = classify(parsed.desc);
            // Solo nos quedamos con eventos no laborables (festivos
            // reales y cierres). Los ajustes de convenio y laborables
            // extra los descartamos del preview — el usuario los añade
            // manualmente si los quiere.
            if (cls.isWorkable) continue;

            String name = buildDescription(parsed.desc);
            String scope = mapScopeForBenjagest(cls);
            String confidence = bandFromScore(cls.confidence);

            LocalDate date;
            try {
                date = LocalDate.of(year, currentMonth, parsed.day);
            } catch (Exception ex) {
                continue;
            }

            items.add(new DetectedHoliday(
                    date,
                    name == null || name.isBlank() ? "(sin descripción)" : name,
                    scope,
                    confidence,
                    line
            ));
        }

        items = dedupe(items);
        return new ExtractionResult(year, items,
                raw.length() > 500 ? raw.substring(0, 500) : raw,
                lines.size());
    }

    // ============================================================
    //  Constantes (port directo de CONTENDO)
    // ============================================================

    private static final Map<String, Integer> MONTHS = new LinkedHashMap<>();
    static {
        MONTHS.put("enero", 1);
        MONTHS.put("febrero", 2);
        MONTHS.put("marzo", 3);
        MONTHS.put("abril", 4);
        MONTHS.put("mayo", 5);
        MONTHS.put("junio", 6);
        MONTHS.put("julio", 7);
        MONTHS.put("agosto", 8);
        MONTHS.put("septiembre", 9);
        MONTHS.put("setiembre", 9);
        MONTHS.put("octubre", 10);
        MONTHS.put("noviembre", 11);
        MONTHS.put("diciembre", 12);
    }

    private static final String[] WEEKDAYS = {
            "lunes", "martes", "miércoles", "miercoles",
            "jueves", "viernes", "sábado", "sabado", "domingo"
    };

    /** Correcciones OCR específicas (port directo de calendarioParser.v3.js). */
    private static final List<Pattern[]> OCR_FIXES;
    static {
        // Cada par: [regex, reemplazo] (el "reemplazo" lo guardo como
        // segundo Pattern.compile dummy con pattern() = la string).
        // En CONTENDO son tuples [regex, replacement].
        OCR_FIXES = List.of(
                new Pattern[]{Pattern.compile("\\btaborables\\b", Pattern.CASE_INSENSITIVE), Pattern.compile("laborables", Pattern.LITERAL)},
                new Pattern[]{Pattern.compile("\\bconvento\\b", Pattern.CASE_INSENSITIVE), Pattern.compile("convenio", Pattern.LITERAL)},
                new Pattern[]{Pattern.compile("\\bTed(os)?\\b"), Pattern.compile("Todos", Pattern.LITERAL)},
                new Pattern[]{Pattern.compile("\\blocaliclad\\b", Pattern.CASE_INSENSITIVE), Pattern.compile("localidad", Pattern.LITERAL)},
                new Pattern[]{Pattern.compile("\\bfimantes\\b", Pattern.CASE_INSENSITIVE), Pattern.compile("firmantes", Pattern.LITERAL)},
                new Pattern[]{Pattern.compile("[—–]"), Pattern.compile("-", Pattern.LITERAL)}
        );
    }

    // ============================================================
    //  Normalización
    // ============================================================

    private String normalizeRaw(String text) {
        String t = text == null ? "" : text;
        for (Pattern[] fix : OCR_FIXES) {
            t = fix[0].matcher(t).replaceAll(fix[1].pattern());
        }
        t = t.replace(' ', ' ')
             .replace('“', '"').replace('”', '"')
             .replace('’', '\'').replace('‘', '\'')
             .replaceAll("\\s+\\n", "\n")
             .replaceAll("\\n\\s+", "\n");
        return t;
    }

    private String cleanLine(String line) {
        if (line == null) return "";
        String s = line.trim();
        s = s.replaceAll("\\s*[>»]+.*$", "");
        s = s.replaceAll("\\s*[íÍ]\\s*»\\s*[A-Z]\\s*$", "");
        // Letra suelta al final (1 char) la quitamos — ruido OCR.
        s = s.replaceAll("\\s+[A-Z]$", "");
        s = s.replaceAll("\\s*[-=]{2,}\\s*$", "");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() < 4) return "";
        if (s.matches("^[\\-\\=\\>\\.\\,\\s]+$")) return "";
        return s;
    }

    private int guessYear(String text) {
        Matcher m = Pattern.compile("\\b(20\\d{2})\\b").matcher(text);
        Map<String, Integer> freq = new HashMap<>();
        String first = null;
        while (m.find()) {
            String y = m.group(1);
            if (first == null) first = y;
            freq.merge(y, 1, Integer::sum);
        }
        if (freq.isEmpty()) return LocalDate.now().getYear();
        String best = first;
        int bestN = 0;
        for (var e : freq.entrySet()) {
            if (e.getValue() > bestN) {
                best = e.getKey();
                bestN = e.getValue();
            }
        }
        try { return Integer.parseInt(best); }
        catch (Exception ex) { return LocalDate.now().getYear(); }
    }

    private Integer findMonthHeader(String lineLower) {
        for (var e : MONTHS.entrySet()) {
            if (lineLower.startsWith(e.getKey())) return e.getValue();
        }
        return null;
    }

    private boolean isStopSection(String lineLower) {
        return lineLower.startsWith("(*)")
                || lineLower.contains("acuerdo de jornada")
                || lineLower.contains("este calendario es de aplicación")
                || lineLower.contains("las partes firmantes")
                || lineLower.contains("jornada de 1736")
                || lineLower.contains("u.g.t.")
                || lineLower.contains("informa");
    }

    private record ParsedDay(int day, String desc) {}

    private ParsedDay parseDayLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        String matchedWd = null;
        for (String wd : WEEKDAYS) {
            if (lower.startsWith(wd)) { matchedWd = wd; break; }
        }
        if (matchedWd == null) return null;

        String rest = lower.substring(matchedWd.length()).trim()
                .replaceFirst("^[:,.-]\\s*", "");
        Matcher m = Pattern.compile("^(\\d{1,2})\\b").matcher(rest);
        if (!m.find()) return null;
        int day;
        try { day = Integer.parseInt(m.group(1)); }
        catch (NumberFormatException ex) { return null; }
        if (day < 1 || day > 31) return null;

        // Descripción original (case-sensitive del usuario) sin el
        // prefijo "Miércoles 16 -".
        String desc = line.replaceFirst(
                "(?i)^\\s*" + Pattern.quote(matchedWd) + "\\s+\\d{1,2}\\s*[-:.,]?\\s*",
                "").trim();
        return new ParsedDay(day, desc);
    }

    // ============================================================
    //  Clasificación (port directo)
    // ============================================================

    private record Classification(
            String tipo,
            boolean isWorkable,
            String label,
            double confidence,
            String reason
    ) {}

    private Classification classify(String descOriginal) {
        String d = descOriginal == null ? "" : descOriginal.toLowerCase(Locale.ROOT);
        String label = null;
        if (d.contains("fiesta nacional") || d.contains("festivo nacional")
                || d.contains("nacional")) {
            label = "nacional";
        } else if (d.contains("auton")) {
            label = "autonómico";
        } else if (d.contains("local")) {
            label = "local";
        }
        if (d.contains("cierre") || d.contains("cerrado")) {
            return new Classification("cierre_empresa", false, "cierre", 0.9, "keyword:cierre");
        }
        if (d.contains("laborable") && (d.contains("extra") || d.contains("adicional"))) {
            return new Classification("laborable_extra", true, "extra", 0.85, "keyword:laborable_extra");
        }
        if (d.contains("ajuste") || d.contains("convenio") || d.contains("jornada")) {
            return new Classification("convenio", true, "convenio", 0.8, "keyword:convenio_ajuste");
        }
        if (d.contains("festivo") || d.contains("fiesta") || d.contains("día de")
                || d.contains("dia de")) {
            return new Classification("festivo_local", false,
                    label != null ? label : "festivo",
                    label != null ? 0.95 : 0.75,
                    label != null ? "festivo:subtipo" : "festivo:heuristica");
        }
        return new Classification("convenio", true, "indeterminado", 0.35, "fallback");
    }

    private String buildDescription(String descOriginal) {
        if (descOriginal == null) return null;
        String s = descOriginal.replace("*", "").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    private String mapScopeForBenjagest(Classification cls) {
        if ("nacional".equals(cls.label)) return WorkCalendar.SCOPE_NATIONAL;
        if ("autonómico".equals(cls.label) || "autonomico".equals(cls.label))
            return WorkCalendar.SCOPE_CCAA;
        return WorkCalendar.SCOPE_LOCAL;  // local, cierre, festivo sin label, etc.
    }

    private String bandFromScore(double score) {
        if (score >= 0.85) return "HIGH";
        if (score >= 0.6) return "MEDIUM";
        return "LOW";
    }

    // ============================================================
    //  Dedupe (port directo)
    // ============================================================

    private List<DetectedHoliday> dedupe(List<DetectedHoliday> items) {
        Map<LocalDate, DetectedHoliday> map = new LinkedHashMap<>();
        for (DetectedHoliday it : items) {
            DetectedHoliday prev = map.get(it.date());
            if (prev == null) {
                map.put(it.date(), it);
                continue;
            }
            int prevConf = confidenceWeight(prev.confidence());
            int curConf = confidenceWeight(it.confidence());
            if (curConf > prevConf) map.put(it.date(), it);
        }
        List<DetectedHoliday> out = new ArrayList<>(map.values());
        out.sort(Comparator.comparing(DetectedHoliday::date));
        return out;
    }

    private int confidenceWeight(String band) {
        return switch (band == null ? "" : band) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }
}
