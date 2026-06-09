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

    /**
     * Resultado extendido para diagnóstico. Incluye TODO el texto
     * extraído por PDFBox + las líneas que el parser consideró pero
     * NO encontró cabecera de mes / patrón día-semana. Útil para
     * cuando un PDF no rinde y queremos entender por qué.
     */
    public record DebugResult(
            ExtractionResult result,
            String fullText,
            List<String> ignoredLines
    ) {}

    /** Versión debug del extractor — mismo análisis + texto crudo. */
    public DebugResult extractWithDebug(byte[] pdfBytes) throws IOException {
        String raw = pdf.extract(pdfBytes);
        ExtractionResult res = extract(pdfBytes);
        // Recoger las líneas ignoradas para diagnóstico — limitado a
        // 100 para no saturar la respuesta.
        List<String> ignored = new ArrayList<>();
        if (raw != null) {
            String normalized = normalizeRaw(raw);
            String[] lines = normalized.split("\\r?\\n");
            boolean afterFirstMonth = false;
            for (String l : lines) {
                String c = cleanLine(l);
                if (c.isEmpty()) continue;
                String lower = c.toLowerCase(Locale.ROOT);
                if (findMonthHeader(lower) != null) { afterFirstMonth = true; continue; }
                if (!afterFirstMonth) continue;
                // Es una línea de evento o ruido. ¿Empezó por día semana?
                boolean isDay = false;
                for (String wd : WEEKDAYS) {
                    if (lower.startsWith(wd)) { isDay = true; break; }
                }
                if (!isDay && ignored.size() < 100) ignored.add(c);
            }
        }
        return new DebugResult(res, raw == null ? "" : raw, ignored);
    }

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

        // Pre-pass de unión de líneas partidas: PDFBox suele cortar
        // descripciones largas cuando las columnas izda/dcha del PDF se
        // entrelazan. Detectamos el patrón "Por el d[ií]a N, weekday
        // (descripción..." sin paréntesis de cierre y unimos hasta
        // encontrar el ')'. Esto convierte el bloque:
        //   "Por el día 1, domingo (Fiesta de todos los"
        //   " Lunes 2"
        //   "Santos)"
        // en dos líneas usables:
        //   "Lunes 2 Por el día 1, domingo (Fiesta de todos los Santos)"
        lines = joinSplitParentheses(lines);

        Integer currentMonth = null;
        boolean parsingEvents = false;
        List<DetectedHoliday> items = new ArrayList<>();
        // Festivos detectados a la espera de descripción (líneas
        // "weekday N" sin desc adyacente). Se rellenan con la siguiente
        // línea descriptiva (no-weekday, no-cabecera).
        List<Integer> pendingNoDescIdx = new ArrayList<>();

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
                // Antes de cambiar de mes, descartamos los pendientes
                // sin descripción del mes anterior — no tenemos cómo
                // nombrarlos. Los marcamos como null para purgar al
                // final (no se puede remove en medio sin desordenar
                // los índices).
                for (int idx : pendingNoDescIdx) {
                    if (idx < items.size()
                            && "(sin descripción)".equals(items.get(idx).name())) {
                        items.set(idx, null);
                    }
                }
                currentMonth = mh;
                parsingEvents = true;
                pendingNoDescIdx.clear();
                continue;
            }

            // Hasta no detectar mes, no parseamos nada.
            if (currentMonth == null) continue;

            ParsedDay parsed = parseDayLine(line);
            if (parsed == null) {
                // Línea no es "weekday N ...". Puede ser descripción
                // huérfana de un día previo sin desc. Si hay pendientes,
                // asignar al ÚLTIMO añadido (LIFO: la desc suele ir
                // pegada al día más cercano).
                if (!pendingNoDescIdx.isEmpty() && isDescriptionLine(line)) {
                    int idx = pendingNoDescIdx.remove(pendingNoDescIdx.size() - 1);
                    DetectedHoliday prev = items.get(idx);
                    Classification cls2 = classify(line);
                    // Remapeo de fecha: si la desc trae "Por el día N"
                    // / "Por el domingo N", la fecha real es ese N. Se
                    // usa en PDFs andaluces para compensar festivos
                    // dominicales.
                    LocalDate finalDate = prev.date();
                    Matcher pem2 = Pattern.compile(
                            "por\\s+el\\s+(?:d[ií]a\\s+)?(?:domingo|s[áa]bado|lunes|martes|mi[ée]rcoles|jueves|viernes)?\\s*(\\d{1,2})\\s*(?:,|\\()",
                            Pattern.CASE_INSENSITIVE).matcher(line);
                    if (pem2.find()) {
                        try {
                            int alt = Integer.parseInt(pem2.group(1));
                            if (alt >= 1 && alt <= 31) {
                                finalDate = LocalDate.of(year, prev.date().getMonthValue(), alt);
                            }
                        } catch (Exception ignored) { /* keep prev date */ }
                    }
                    // No reasignamos si la línea descriptiva es claramente
                    // un ajuste/convenio y el día no era ajuste — eso
                    // sería ensuciar. Mantenemos lo previo en ese caso.
                    if (!isAdjustment(cls2) || isAdjustment(classify(prev.name()))) {
                        items.set(idx, new DetectedHoliday(
                                finalDate,
                                buildDescription(line),
                                mapScopeForBenjagest(cls2),
                                bandFromScore(cls2.confidence * 0.85),
                                prev.rawSourceLine() + " | " + line
                        ));
                    }
                }
                continue;
            }

            Classification cls = classify(parsed.desc);

            // Filtramos solo los TOTALMENTE irrelevantes: laborables
            // extra explícitos y fallback indeterminado. Los ajustes
            // de jornada SÍ los devolvemos como "convenio" (LOCAL,
            // LOW confidence) — el usuario decide si los quiere en el
            // calendario laboral (Benjamin 2026-06-09).
            if ("laborable_extra".equals(cls.tipo)) continue;
            if ("fallback".equals(cls.reason) && (parsed.desc == null || parsed.desc.isBlank())) {
                // weekday + día sin desc en absoluto — lo dejamos como
                // pendiente para asignar desc adyacente.
                LocalDate dateP;
                try { dateP = LocalDate.of(year, currentMonth, parsed.day); }
                catch (Exception ex) { continue; }
                // Patrón "Por el d[ií]a N" en la propia desc no aplica
                // (desc vacía). Lo añadimos como pendiente.
                items.add(new DetectedHoliday(dateP, "(sin descripción)",
                        WorkCalendar.SCOPE_LOCAL, "LOW", line));
                pendingNoDescIdx.add(items.size() - 1);
                continue;
            }
            if ("fallback".equals(cls.reason)) continue;

            String name = buildDescription(parsed.desc);
            String scope = mapScopeForBenjagest(cls);
            String confidence = bandFromScore(cls.confidence);

            // Patrón "Por el d[ií]a N (...)": la fecha REAL es la del
            // patrón, no la del weekday adyacente. Lo usan los PDFs
            // andaluces para compensar festivos que cayeron en domingo.
            // Ejemplo: "Lunes 7 Por el domingo 6 (Constitución
            // Española)" → la Constitución es el día 6, no el 7.
            int realDay = parsed.day;
            Matcher pem = Pattern.compile(
                    "por\\s+el\\s+(?:d[ií]a\\s+)?(?:domingo|s[áa]bado|lunes|martes|mi[ée]rcoles|jueves|viernes)?\\s*(\\d{1,2})\\s*(?:,|\\()",
                    Pattern.CASE_INSENSITIVE).matcher(parsed.desc);
            if (pem.find()) {
                try {
                    int alt = Integer.parseInt(pem.group(1));
                    if (alt >= 1 && alt <= 31) realDay = alt;
                } catch (NumberFormatException ignored) { /* keep parsed.day */ }
            }

            LocalDate date;
            try {
                date = LocalDate.of(year, currentMonth, realDay);
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

        // Limpieza final: si quedaron pendientes sin descripción al
        // terminar el bucle, los descartamos (no tenemos cómo nombrarlos
        // de forma útil — el usuario los añadiría a ciegas).
        for (int idx : pendingNoDescIdx) {
            if (idx < items.size() && items.get(idx) != null
                    && "(sin descripción)".equals(items.get(idx).name())) {
                items.set(idx, null);
            }
        }
        items.removeIf(java.util.Objects::isNull);

        items = dedupe(items);
        return new ExtractionResult(year, items,
                raw.length() > 500 ? raw.substring(0, 500) : raw,
                lines.size());
    }

    /**
     * Une líneas partidas por columnas dentro de un paréntesis abierto.
     * Caso típico Granada-2026:
     * <pre>
     * Por el día 1, domingo (Fiesta de todos los
     *  Lunes 2
     * Santos)
     * </pre>
     * Sale como una línea "Por el día 1, domingo (Fiesta de todos los
     * Santos)" + la línea " Lunes 2" se mantiene aparte (el parser
     * intentará asociar después). Si la siguiente "weekday N" hace de
     * cuña, la dejamos para que parsedDayLine la pille como día sin
     * desc — el patrón "Por el día N" del bucle principal hará el
     * remapeo a la fecha correcta.
     */
    private List<String> joinSplitParentheses(List<String> input) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            String line = input.get(i);
            int op = line.indexOf('(');
            int cl = line.indexOf(')');
            if (op >= 0 && cl < op) {
                // Tiene '(' sin ')' — buscar cierre en líneas siguientes
                // (máx 3 hacia delante) y juntar todo en una línea final.
                StringBuilder merged = new StringBuilder(line);
                List<String> orphans = new ArrayList<>();
                boolean closed = false;
                int j = i + 1;
                int look = Math.min(input.size(), i + 4);
                for (; j < look; j++) {
                    String next = input.get(j);
                    if (findMonthHeader(next.toLowerCase(Locale.ROOT)) != null) break;
                    if (next.contains(")")) {
                        merged.append(" ").append(next);
                        closed = true;
                        i = j; // saltar las líneas absorbidas
                        break;
                    }
                    // Líneas intermedias: si son "weekday N" las
                    // dejamos como huérfanas (irán al output tras la
                    // unida), si son texto se incorporan a la unión.
                    String nlow = next.toLowerCase(Locale.ROOT);
                    boolean isDayLine = false;
                    for (String wd : WEEKDAYS) {
                        if (nlow.startsWith(wd)) { isDayLine = true; break; }
                    }
                    if (isDayLine) {
                        orphans.add(next);
                    } else {
                        merged.append(" ").append(next);
                    }
                }
                if (closed) {
                    // Emitimos los orfanos PRIMERO. Si la línea unida
                    // viene después, el bucle principal verá primero
                    // el "Lunes 2" y lo dejará como pending; al llegar
                    // la línea unida (no-weekday) la asignará por
                    // look-back al pending — capturando "Todos los
                    // Santos" para el 2 nov.
                    out.addAll(orphans);
                    out.add(merged.toString());
                    continue;
                }
            }
            out.add(line);
        }
        return out;
    }

    /** Una línea es "descriptiva" si no es cabecera mes ni weekday ni stop. */
    private boolean isDescriptionLine(String line) {
        if (line == null) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        if (isStopSection(lower)) return false;
        if (findMonthHeader(lower) != null) return false;
        for (String wd : WEEKDAYS) {
            if (lower.startsWith(wd)) return false;
        }
        // Filtros de ruido típico: cabeceras de boletín, paginación.
        if (lower.contains("boletin oficial") || lower.contains("bolet")
                && lower.contains("provincia")) return false;
        if (lower.matches(".*p.gina\\s+\\d+.*")) return false;
        if (lower.matches("^n.\\s*\\d+.*")) return false;
        return line.length() >= 4;
    }

    private boolean isAdjustment(Classification cls) {
        return cls != null && "convenio".equals(cls.tipo);
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

    /**
     * Adivina el año del calendario.
     *
     * <p>Estrategia mejorada respecto al port directo de CONTENDO:
     * <ol>
     *   <li>Si aparece "calendario laboral YYYY" (o variantes) en el
     *       texto, usar ESE año. Es la pista más fiable.</li>
     *   <li>Si no, usar el MAYOR año encontrado (no el más frecuente).
     *       Razón: un calendario 2026 a menudo cita 2025 (año previo)
     *       o decretos de años pasados (RD 8/2019, RDL 2/2015) y
     *       quedarse con el más frecuente nos llevaba a 2025 cuando
     *       el calendario era de 2026.</li>
     * </ol>
     */
    private int guessYear(String text) {
        // Pista 1: "calendario laboral YYYY" o "calendario de YYYY".
        Matcher hint = Pattern.compile(
                "calendario\\s+(?:laboral|de|del|para\\s+el\\s+a[ñn]o)?\\s*(20\\d{2})",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (hint.find()) {
            try { return Integer.parseInt(hint.group(1)); }
            catch (Exception ex) { /* fallthrough */ }
        }
        // Pista 2: mayor año encontrado.
        Matcher m = Pattern.compile("\\b(20\\d{2})\\b").matcher(text);
        int max = 0;
        while (m.find()) {
            try {
                int y = Integer.parseInt(m.group(1));
                if (y > max) max = y;
            } catch (Exception ignored) { /* skip */ }
        }
        return max > 0 ? max : LocalDate.now().getYear();
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
        // Mejora sobre CONTENDO v3: si la descripción contiene un
        // nombre canónico de festivo conocido (Epifanía, Inmaculada,
        // Asunción, etc.), lo aceptamos como festivo aunque no
        // contenga "fiesta" o "día de". Confidence MEDIUM (0.7) para
        // que se vea distinto del HIGH explícito.
        String dNoAcc = stripAccents(d).replace('�', ' ').replaceAll("\\s+", " ").trim();
        for (var entry : CANONICAL_HOLIDAYS.entrySet()) {
            if (dNoAcc.contains(entry.getKey())) {
                String labelFromCanonical = entry.getValue();
                return new Classification("festivo_local", false,
                        label != null ? label : labelFromCanonical,
                        0.7,
                        "canonical:" + entry.getKey());
            }
        }
        return new Classification("convenio", true, "indeterminado", 0.35, "fallback");
    }

    /**
     * Nombres canónicos de festivos españoles (calendario civil/religioso)
     * que cuando aparecen en la descripción nos permiten clasificar como
     * festivo aunque no diga la palabra "fiesta" / "día de".
     *
     * <p>Mejora conservadora sobre CONTENDO v3 — en CONTENDO,
     * "Epifanía del Señor" cae a fallback (laborable indeterminado) y se
     * descarta. Aquí lo pillamos.
     *
     * <p>Las claves se buscan en la descripción tras
     * {@link #stripAccents(String)} (sin tildes, lowercase) para soportar
     * PDFs con encoding roto que reemplazan tildes por U+FFFD.
     */
    private static final Map<String, String> CANONICAL_HOLIDAYS = new LinkedHashMap<>();
    static {
        // Festivos nacionales del Estado.
        CANONICAL_HOLIDAYS.put("ano nuevo",          "nacional"); // 1 ene
        CANONICAL_HOLIDAYS.put("epifania",           "nacional"); // 6 ene
        CANONICAL_HOLIDAYS.put("reyes magos",        "nacional");
        CANONICAL_HOLIDAYS.put("jueves santo",       "nacional");
        CANONICAL_HOLIDAYS.put("viernes santo",      "nacional");
        CANONICAL_HOLIDAYS.put("asuncion de la virgen", "nacional"); // 15 ago
        CANONICAL_HOLIDAYS.put("asuncion",           "nacional");
        CANONICAL_HOLIDAYS.put("hispanidad",         "nacional"); // 12 oct
        CANONICAL_HOLIDAYS.put("todos los santos",   "nacional"); // 1 nov
        CANONICAL_HOLIDAYS.put("constitucion espanola", "nacional"); // 6 dic
        CANONICAL_HOLIDAYS.put("constitucion",       "nacional");
        CANONICAL_HOLIDAYS.put("inmaculada concepcion", "nacional"); // 8 dic
        CANONICAL_HOLIDAYS.put("inmaculada",         "nacional");
        CANONICAL_HOLIDAYS.put("natividad del senor","nacional"); // 25 dic
        CANONICAL_HOLIDAYS.put("natividad",          "nacional");
        CANONICAL_HOLIDAYS.put("navidad",            "nacional");
        // Algunos autonómicos comunes.
        CANONICAL_HOLIDAYS.put("dia de andalucia",   "autonomico"); // 28 feb
        CANONICAL_HOLIDAYS.put("san jorge",          "autonomico"); // 23 abr Aragón
        CANONICAL_HOLIDAYS.put("dia de galicia",     "autonomico");
        CANONICAL_HOLIDAYS.put("diada",              "autonomico");
        CANONICAL_HOLIDAYS.put("corpus christi",     "autonomico");
        CANONICAL_HOLIDAYS.put("san fermin",         "local");
    }

    /** Quita tildes (NFD-strip) y baja a lowercase para matching robusto. */
    private static String stripAccents(String s) {
        if (s == null) return "";
        String norm = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        return norm.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT);
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
