package com.benjagest.backend.purchases.pdfimport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Extractor de campos de factura. Versión 2 (2026-06-04).
 *
 * Cambios respecto a la v1:
 *
 *   - Trabaja con {@link LayoutDocument} (X/Y por span) además del
 *     texto plano. Esto permite encontrar valores "a la derecha" de su
 *     etiqueta cuando comparten línea pero con tab/columna entre medias.
 *   - Pre-normalización (NBSP, em/en dash, comillas inteligentes,
 *     OCR fixes típicos) similar al calendarioParser.v3 de CONTENDO.
 *   - Diccionario de etiquetas ampliado en ES + EN.
 *   - Detección de PROVEEDOR como bloque cabecera (primeras N líneas,
 *     descartando "Factura A:" y similares).
 *   - Validación cruzada: si base + IVA ≈ total (±0,02 €), la
 *     confianza del campo total sube a HIGH; si no, baja a LOW.
 *   - SHA-256 del PDF para que el caller detecte duplicados sin
 *     re-procesar.
 *
 * Sin IA — solo expresiones regulares y heurísticas. Pretende cubrir
 * ~85% de facturas comunes; los casos límite los corrige el operador
 * en la UI antes de guardar.
 */
@Service
public class InvoiceFieldsExtractor {

    // ====================================================================
    //  Normalización OCR (mismo enfoque que CONTENDO calendarioParser.v3)
    // ====================================================================

    private static final List<Pattern[]> OCR_FIXES = List.of(
            new Pattern[]{Pattern.compile("\\u00a0"), Pattern.compile(" ")}, // NBSP
            new Pattern[]{Pattern.compile("[\\u2014\\u2013]"), Pattern.compile("-")}, // em/en dash
            new Pattern[]{Pattern.compile("[\\u201c\\u201d]"), Pattern.compile("\"")}, // " smart
            new Pattern[]{Pattern.compile("[\\u2018\\u2019]"), Pattern.compile("'")}  // ' smart
    );

    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");

    // ====================================================================
    //  Patrones reutilizables
    // ====================================================================

    /** NIF/CIF/NIE según AEAT. */
    private static final Pattern NIF_PATTERN = Pattern.compile(
            "\\b([XYZxyz]?\\d{7,8}[A-HJ-NP-TV-Za-hj-np-tv-z]|" +
            "[A-HJ-NP-SUVWa-hj-np-suvw]\\d{7}[0-9A-Ja-j])\\b"
    );

    /**
     * Importes. Soporta:
     *   1.234,56   1234,56   1234.56   1,234.56   1234   -1.234,56
     *   con o sin símbolo €/EUR/EURO/Euros.
     */
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(-?\\d{1,3}(?:\\.\\d{3})+,\\d{2}" +   // 1.234,56
            "|-?\\d{1,3}(?:,\\d{3})+\\.\\d{2}" +    // 1,234.56 (EN)
            "|-?\\d+,\\d{2}" +                       // 1234,56
            "|-?\\d+\\.\\d{2})" +                    // 1234.56
            "\\s*(?:€|EUR|EURO|EUROS)?"
    );

    /** Fechas DD/MM/YYYY DD-MM-YYYY DD.MM.YYYY DD/MM/YY YYYY-MM-DD. */
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "\\b(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2,4})\\b" +
            "|" +
            "\\b(\\d{4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,2})\\b"
    );

    /** "21%", "21 %", "21,00 %", "0%". */
    private static final Pattern VAT_PCT_PATTERN = Pattern.compile(
            "(\\d{1,2}(?:[,.]\\d{1,2})?)\\s*%"
    );

    /** Número de factura — versión amplia. */
    private static final Pattern INVOICE_NUMBER_PATTERN = Pattern.compile(
            "(?i)(?:n[\\u00ba\\u00b0\\.\\s]*(?:de\\s+)?factura|" +
            "factura\\s*n[\\u00ba\\u00b0\\.\\s]*|" +
            "invoice\\s*(?:no|#|number)?|" +
            "ref(?:erencia)?\\.?\\s*factura)\\s*:?\\s*([A-Z0-9][A-Z0-9\\-/_.]{2,29})"
    );

    /**
     * Tabla de cabecera tipo:
     *   Número   Serie   Fecha       Cliente
     *   263274   1       31-05-2026  11755
     *
     * Detectamos la cabecera y leemos la siguiente línea con dígitos. El
     * primer entero de esa línea es el número de factura.
     */
    private static final Pattern INVOICE_NUMBER_TABLE_HEADER = Pattern.compile(
            "(?i)\\bn[\\u00fa\\u00fa\\u00famero]*\\b[\\s\\S]{0,40}?\\bserie\\b[\\s\\S]{0,40}?\\bfecha\\b"
    );

    /**
     * CIF explícito en pie de factura / mercantil:
     *   "CIF B12345678", "C.I.F.: B12345678", "NIF B12345678".
     * Mayor prioridad que NIFs sueltos en el texto.
     */
    private static final Pattern CIF_EXPLICIT_PATTERN = Pattern.compile(
            "(?i)\\b(?:cif|c\\.i\\.f\\.|nif\\s+empresa)\\s*:?\\s*" +
            "([A-HJ-NP-SUVWa-hj-np-suvw]\\d{7}[0-9A-Ja-j])\\b"
    );

    /**
     * Línea de cabecera de tabla de TOTALES tipo:
     *   SUMA IMPORTES % DTO DTO BASE IMPONIBLE % IVA CUOTA TOTAL A PAGAR
     * Detectamos varias etiquetas juntas y luego leemos la siguiente
     * línea numérica para asignar BASE / %IVA / CUOTA / TOTAL por orden.
     */
    private static final Pattern TOTALS_TABLE_HEADER = Pattern.compile(
            // Importante: NO usar \b antes de % o números (no es boundary
            // válido). Usamos lookarounds simples y espacios.
            "(?i)base\\s+imp(?:onible)?[\\s\\S]{0,60}?%\\s*iva[\\s\\S]{0,60}?" +
            "cuota[\\s\\S]{0,60}?total"
    );

    /** Importes/números genéricos en una línea (también enteros sueltos). */
    private static final Pattern NUMBER_TOKEN = Pattern.compile(
            "-?\\d{1,3}(?:\\.\\d{3})+,\\d{2}" +   // 1.234,56
            "|-?\\d+,\\d{2}" +                     // 1234,56
            "|-?\\d+\\.\\d{2}" +                   // 1234.56
            "|-?\\d{1,3}(?:\\.\\d{3})+" +          // 1.234 (sin decimales)
            "|-?\\d+"                              // entero suelto (21)
    );

    /** Etiquetas para etiquetas de "TOTAL" (ES + EN). */
    private static final Pattern TOTAL_LABEL = Pattern.compile(
            "(?i)\\b(total\\s+factura|total\\s+a\\s+pagar|importe\\s+total|" +
            "total\\s+con\\s+iva|total\\s+general|gran\\s+total|" +
            "amount\\s+due|total\\s+due|grand\\s+total|" +
            "total)\\b\\s*:?\\s*"
    );

    /** Base imponible / subtotal / neto. */
    private static final Pattern BASE_LABEL = Pattern.compile(
            "(?i)\\b(base\\s+imponible|base\\s+imp\\.?|b\\.?\\s*imp\\.?|" +
            "subtotal(?:\\s+sin\\s+iva)?|importe\\s+sin\\s+iva|" +
            "importe\\s+neto|s/iva|s\\.\\s*iva|" +
            "net\\s+amount|subtotal|" +
            "base)\\b\\s*:?\\s*"
    );

    /** Cuota / importe IVA. */
    private static final Pattern VAT_AMOUNT_LABEL = Pattern.compile(
            "(?i)\\b(cuota\\s+iva|importe\\s+iva|total\\s+iva|" +
            "iva\\s+repercutido|" +
            "vat\\s+amount|tax\\s+amount|" +
            "i\\.?\\s*v\\.?\\s*a\\.?)\\b\\s*:?\\s*"
    );

    /** Retención IRPF. */
    private static final Pattern RETENTION_LABEL = Pattern.compile(
            "(?i)\\b(retenci\\u00f3n|retencion|" +
            "irpf|i\\.?\\s*r\\.?\\s*p\\.?\\s*f\\.?|" +
            "withholding)\\b\\s*:?\\s*"
    );

    /** Etiquetas que indican que el NIF que sigue es el RECEPTOR, no el emisor. */
    private static final Pattern RECEIVER_LABEL = Pattern.compile(
            "(?i)\\b(cliente|destinatario|factura\\s+a|bill\\s+to|customer|" +
            "comprador|n\\.?\\s*if\\.?\\s+cliente)\\b"
    );

    // ====================================================================
    //  API pública
    // ====================================================================

    public ExtractionResult extract(String plainText) {
        return extract(plainText, null, null);
    }

    public ExtractionResult extractFromLayout(LayoutDocument document) {
        if (document == null) return extract("", null, null);
        return extract(document.toPlainText(), document, null);
    }

    public ExtractionResult extractFromLayout(LayoutDocument document, byte[] originalBytes) {
        if (document == null) return extract("", null, originalBytes);
        return extract(document.toPlainText(), document, originalBytes);
    }

    private ExtractionResult extract(String rawText, LayoutDocument layout, byte[] originalBytes) {
        String text = normalize(rawText);
        if (text.isBlank()) {
            return new ExtractionResult(List.of(),
                    null, null, null, null, null, null, null, null,
                    hashOf(originalBytes), Confidence.LOW, "");
        }

        // Cabecera (primeras 25 líneas) para detectar el emisor.
        String head = trimToLines(text, 25);

        // 1. Recolectar todos los NIFs (orden de aparición)
        List<String> allNifs = findAll(NIF_PATTERN, text);

        // 2. Emisor: PRIORIDAD ALTA → si encontramos "CIF B12345678"
        //    explícito (en pie o cualquier sitio), ese es el emisor.
        //    Sin etiqueta explícita, caemos a la heurística de receptor.
        String emitterNif = guessEmitterNif(text, allNifs);

        // 3. Razón social del emisor
        String supplierName = guessSupplierName(head, emitterNif);

        // 4. Número de factura. Doble estrategia:
        //    a) Regex clásico "Factura nº XYZ".
        //    b) Tabla "Número Serie Fecha" + siguiente línea numérica.
        String invoiceNumber = findFirstGroup(INVOICE_NUMBER_PATTERN, text, 1);
        if (invoiceNumber != null) invoiceNumber = invoiceNumber.trim();
        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            invoiceNumber = findInvoiceNumberInTable(text);
        }

        // 5. Fecha
        LocalDate invoiceDate = findFirstDate(text);

        // 6. Importes — primero intentamos la TABLA DE TOTALES tipo
        //    "BASE IMPONIBLE | %IVA | CUOTA | TOTAL" con fila de datos.
        //    Es el caso más fiable cuando existe (facturas de software
        //    estándar). Si no, caemos a las heurísticas por etiqueta.
        TotalsRow totals = findTotalsTable(text);
        BigDecimal base, vatPct, vatAmount, total;
        if (totals != null && totals.total != null) {
            base = totals.base;
            vatPct = totals.vatPercent;
            vatAmount = totals.vatAmount;
            total = totals.total;
        } else {
            total = findAmountForLabel(layout, text, TOTAL_LABEL, true);
            base = findAmountForLabel(layout, text, BASE_LABEL, true);
            vatAmount = findAmountForLabel(layout, text, VAT_AMOUNT_LABEL, false);
            vatPct = findVatPercent(text);
        }
        BigDecimal retentionAmount = findAmountForLabel(layout, text, RETENTION_LABEL, false);

        // 7. Validación cruzada base + iva ≈ total (±0,02 €)
        Confidence confidence = crossCheck(base, vatAmount, total, retentionAmount);

        return new ExtractionResult(
                allNifs, emitterNif, supplierName, invoiceNumber, invoiceDate,
                base, vatPct, vatAmount, total,
                hashOf(originalBytes), confidence, head
        );
    }

    /**
     * Busca el número de factura en estructura tabla:
     *   Número  Serie  Fecha  Cliente
     *   263274  1      31-05-2026  11755
     *
     * Devuelve el primer entero de la línea siguiente a la cabecera.
     */
    private String findInvoiceNumberInTable(String text) {
        Matcher h = INVOICE_NUMBER_TABLE_HEADER.matcher(text);
        if (!h.find()) return null;
        // Ventana de 200 chars tras la cabecera (puede haber un par de
        // saltos hasta la fila de datos).
        int from = h.end();
        String tail = text.substring(from, Math.min(text.length(), from + 200));
        // Saltar la primera línea (probablemente continuación de cabecera)
        // y buscar la primera línea que empiece por dígitos.
        String[] lines = tail.split("\\n");
        for (String raw : lines) {
            String line = raw.trim();
            Matcher digits = Pattern.compile("^(\\d{2,12})\\b").matcher(line);
            if (digits.find()) {
                return digits.group(1);
            }
        }
        return null;
    }

    /**
     * Detecta la fila de TOTALES bajo una cabecera tipo
     * "SUMA IMPORTES % DTO DTO BASE IMPONIBLE % IVA CUOTA TOTAL A PAGAR".
     *
     * Tras la cabecera (en las siguientes ~3 líneas), busca una línea
     * con varios números separados por espacios y los asigna a base /
     * %iva / cuota / total. La estrategia es robusta porque estos
     * datos siempre están en la última sección del documento y siguen
     * un orden estándar en facturas españolas.
     *
     * @return {@code null} si no hay match; un {@link TotalsRow} con
     *         los campos detectados si encaja.
     */
    private TotalsRow findTotalsTable(String text) {
        Matcher h = TOTALS_TABLE_HEADER.matcher(text);
        if (!h.find()) return null;
        int from = h.end();
        String tail = text.substring(from, Math.min(text.length(), from + 300));
        // La fila de datos suele estar en la primera línea no vacía tras
        // la cabecera. Iteramos por líneas.
        String[] lines = tail.split("\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            // Tokenizar todos los números de la línea
            List<String> tokens = new ArrayList<>();
            Matcher m = NUMBER_TOKEN.matcher(line);
            while (m.find()) tokens.add(m.group());
            if (tokens.size() < 3) continue;

            // Heurística: en facturas españolas con cabecera completa
            // (SUMA IMPORTES, % DTO, DTO, BASE IMPONIBLE, % IVA, CUOTA,
            // TOTAL A PAGAR), los campos clave son SIEMPRE los últimos:
            //   ... [N-4]=BASE  [N-3]=%IVA  [N-2]=CUOTA  [N-1]=TOTAL
            //
            // Si la línea tiene menos tokens (sin descuentos), el orden
            // es: BASE %IVA CUOTA TOTAL.
            TotalsRow row = new TotalsRow();
            int n = tokens.size();
            row.total = parseAmount(tokens.get(n - 1));
            row.vatAmount = parseAmount(tokens.get(n - 2));
            row.vatPercent = parseAmount(tokens.get(n - 3));
            row.base = parseAmount(tokens.get(n - 4));
            // Validar coherencia: total > 0 y total = base + vatAmount (±0,05)
            if (row.total == null || row.base == null) continue;
            if (row.total.signum() <= 0) continue;
            if (row.vatAmount != null) {
                BigDecimal sum = row.base.add(row.vatAmount);
                if (sum.subtract(row.total).abs()
                        .compareTo(new BigDecimal("0.05")) > 0) {
                    // Los 4 últimos números no son base/%iva/cuota/total.
                    // Probemos otra alineación (3 tokens BASE + CUOTA + TOTAL
                    // sin %).
                    if (n >= 3) {
                        BigDecimal t = parseAmount(tokens.get(n - 1));
                        BigDecimal q = parseAmount(tokens.get(n - 2));
                        BigDecimal b = parseAmount(tokens.get(n - 3));
                        if (t != null && q != null && b != null
                                && b.add(q).subtract(t).abs()
                                        .compareTo(new BigDecimal("0.05")) <= 0) {
                            row.base = b;
                            row.vatAmount = q;
                            row.total = t;
                            row.vatPercent = null;
                            return row;
                        }
                    }
                    continue;
                }
            }
            return row;
        }
        return null;
    }

    private static final class TotalsRow {
        BigDecimal base;
        BigDecimal vatPercent;
        BigDecimal vatAmount;
        BigDecimal total;
    }

    // ====================================================================
    //  Heurísticas
    // ====================================================================

    /** Normaliza unicode + OCR fixes + colapsa multispace. */
    private String normalize(String text) {
        if (text == null) return "";
        String s = text;
        for (Pattern[] pair : OCR_FIXES) {
            s = pair[0].matcher(s).replaceAll(pair[1].pattern());
        }
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        s = s.replace("\r\n", "\n");
        return s;
    }

    private String trimToLines(String text, int maxLines) {
        String[] lines = text.split("\n");
        int n = Math.min(lines.length, maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(lines[i]).append('\n');
        return sb.toString();
    }

    private List<String> findAll(Pattern p, String text) {
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            String v = m.group(1) != null ? m.group(1) : m.group();
            seen.add(v.toUpperCase());
        }
        return new ArrayList<>(seen);
    }

    private String findFirstGroup(Pattern p, String text, int group) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(group) : null;
    }

    private String guessEmitterNif(String text, List<String> allNifs) {
        if (allNifs.isEmpty()) return null;
        // PRIORIDAD 1: "CIF B12345678" explícito (pie de factura).
        //   En facturas españolas, el CIF de la sociedad emisora suele
        //   aparecer en el pie ("Inscrita en el Registro Mercantil…
        //   CIF B12345678") porque arriba la empresa pone solo el nombre.
        Matcher cif = CIF_EXPLICIT_PATTERN.matcher(text);
        if (cif.find()) {
            return cif.group(1).toUpperCase();
        }
        // PRIORIDAD 2: el primer NIF que aparezca antes de cualquier
        // etiqueta receptor explícita ("Cliente:", "Bill to", etc).
        Matcher recv = RECEIVER_LABEL.matcher(text);
        int receiverPos = recv.find() ? recv.start() : Integer.MAX_VALUE;
        Matcher nifMatcher = NIF_PATTERN.matcher(text);
        while (nifMatcher.find()) {
            if (nifMatcher.start() < receiverPos) {
                return nifMatcher.group(1).toUpperCase();
            }
        }
        // PRIORIDAD 3: el primer CIF (letra+8) en el texto. Empieza por
        // letra → más probable que sea sociedad emisora que persona física.
        for (String n : allNifs) {
            if (n.matches("^[A-HJ-NP-SUVW].*")) return n;
        }
        // Fallback: primer NIF detectado.
        return allNifs.get(0);
    }

    /**
     * Heurística para razón social: primera línea no vacía de la
     * cabecera que no sea una etiqueta común ni contenga el NIF emisor.
     * Inspirada en cómo Claude lo razona en CONTENDO — aquí la
     * aproximamos con reglas duras.
     */
    private String guessSupplierName(String head, String emitterNif) {
        if (head == null || head.isBlank()) return null;
        String[] lines = head.split("\n");
        for (String raw : lines) {
            String s = raw.trim();
            if (s.length() < 3 || s.length() > 120) continue;
            if (s.matches("(?i).*\\b(factura|invoice|recibo|albaran)\\b.*")) continue;
            if (s.matches("(?i).*\\b(fecha|date|n\\u00ba|num|number)\\b.*")) continue;
            if (emitterNif != null && s.toUpperCase().contains(emitterNif)) continue;
            // Descartar líneas que sean obviamente direcciones (números + calle)
            if (s.matches("(?i).*\\b(c/|calle|avda|avenida|c\\.p\\.|cp\\s+\\d{5})\\b.*")) continue;
            // Aceptar si tiene mayúscula inicial y letras (no solo dígitos)
            if (s.matches(".*[A-Z\\u00c0-\\u017f].*[a-z\\u00e0-\\u017f].*")
                    || s.matches("[A-Z\\u00c0-\\u017f0-9\\s,.&'\\-]{3,80}")) {
                return s;
            }
        }
        return null;
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
                    if (mo < 1 || mo > 12 || d < 1 || d > 31) continue;
                    return LocalDate.of(y, mo, d);
                } else if (m.group(4) != null) {
                    int y = Integer.parseInt(m.group(4));
                    int mo = Integer.parseInt(m.group(5));
                    int d = Integer.parseInt(m.group(6));
                    if (mo < 1 || mo > 12 || d < 1 || d > 31) continue;
                    return LocalDate.of(y, mo, d);
                }
            } catch (Exception ignored) { /* siguiente match */ }
        }
        return null;
    }

    /**
     * Busca el importe asociado a una etiqueta usando dos estrategias:
     *
     *   1) Con layout: busca todas las líneas que contengan la etiqueta;
     *      en esa línea (sus spans), el último span numérico a la derecha
     *      es el valor. Maneja perfectamente "Base imponible | | 1.234,56".
     *   2) Sin layout o si la estrategia 1 falla: ventana de 120 chars
     *      tras la etiqueta en el texto plano.
     *
     * El parámetro {@code preferLargestWhenMultiple} hace que, si la
     * misma etiqueta aparece varias veces (típico de TOTAL — uno por
     * línea + uno final), se quede con el mayor (el final).
     */
    private BigDecimal findAmountForLabel(LayoutDocument layout, String text,
                                           Pattern labelPattern,
                                           boolean preferLargestWhenMultiple) {
        List<BigDecimal> candidates = new ArrayList<>();

        if (layout != null) {
            for (LayoutDocument.LayoutPage page : layout.pages()) {
                for (LayoutDocument.LayoutLine line : page.lines()) {
                    String lineText = line.text();
                    Matcher m = labelPattern.matcher(lineText);
                    if (!m.find()) continue;
                    // Búsqueda en la propia línea, después de la etiqueta
                    String tail = lineText.substring(m.end());
                    Matcher a = AMOUNT_PATTERN.matcher(tail);
                    BigDecimal lastInLine = null;
                    while (a.find()) {
                        BigDecimal v = parseAmount(a.group(1));
                        if (v != null) lastInLine = v;
                    }
                    if (lastInLine != null) {
                        candidates.add(lastInLine);
                    } else {
                        // El valor puede estar en la siguiente línea
                        // (tablas con cabecera Base | IVA | Total y datos
                        // debajo). Buscamos primer importe en la línea
                        // siguiente alineado en X similar.
                        // Por simplicidad: primer importe global tras esta
                        // posición en el texto plano.
                    }
                }
            }
        }

        // Fallback: estrategia v1 de "etiqueta + ventana de 120 chars".
        Matcher l = labelPattern.matcher(text);
        while (l.find()) {
            int end = l.end();
            String tail = text.substring(end, Math.min(text.length(), end + 120));
            Matcher a = AMOUNT_PATTERN.matcher(tail);
            if (a.find()) {
                BigDecimal v = parseAmount(a.group(1));
                if (v != null && !candidates.contains(v)) candidates.add(v);
            }
        }

        if (candidates.isEmpty()) return null;
        if (!preferLargestWhenMultiple) return candidates.get(0);
        BigDecimal max = candidates.get(0);
        for (BigDecimal c : candidates) {
            if (c.compareTo(max) > 0) max = c;
        }
        return max;
    }

    private BigDecimal findVatPercent(String text) {
        // Ventana cerca de "IVA": 50 chars
        Matcher l = Pattern.compile("(?i)\\b(i\\.?\\s*v\\.?\\s*a\\.?|vat|tax)\\b").matcher(text);
        while (l.find()) {
            String window = text.substring(l.start(),
                    Math.min(text.length(), l.end() + 50));
            Matcher pct = VAT_PCT_PATTERN.matcher(window);
            while (pct.find()) {
                BigDecimal v = parseAmount(pct.group(1));
                if (v != null && v.compareTo(BigDecimal.ZERO) >= 0
                        && v.compareTo(BigDecimal.valueOf(50)) <= 0) {
                    return v;
                }
            }
        }
        // Fallback: primer % razonable (0-50)
        Matcher pct = VAT_PCT_PATTERN.matcher(text);
        while (pct.find()) {
            BigDecimal v = parseAmount(pct.group(1));
            if (v != null && v.compareTo(BigDecimal.valueOf(50)) <= 0) {
                return v;
            }
        }
        return null;
    }

    /**
     * Compara base + iva (- retención) ≈ total con tolerancia de 0,02 €.
     * Si encaja, confianza HIGH; si los 3 existen pero no encajan, LOW;
     * si faltan campos, MEDIUM.
     */
    private Confidence crossCheck(BigDecimal base, BigDecimal vat,
                                    BigDecimal total, BigDecimal retention) {
        if (base == null || vat == null || total == null) return Confidence.MEDIUM;
        BigDecimal sum = base.add(vat);
        if (retention != null) sum = sum.subtract(retention);
        BigDecimal diff = sum.subtract(total).abs();
        return diff.compareTo(new BigDecimal("0.02")) <= 0
                ? Confidence.HIGH
                : Confidence.LOW;
    }

    /**
     * Convierte "1.234,56" / "1,234.56" / "1234,56" / "1234.56" a BigDecimal.
     * Heurística: el último separador (, o .) seguido de 2 cifras es el
     * decimal; el otro es separador de miles.
     */
    private BigDecimal parseAmount(String raw) {
        if (raw == null) return null;
        String s = raw.replace(" ", "").replace("€", "")
                .replace("EUR", "").replace("EURO", "");
        if (s.isEmpty()) return null;
        int lastComma = s.lastIndexOf(',');
        int lastDot = s.lastIndexOf('.');
        String cleaned;
        if (lastComma > -1 && lastDot > -1) {
            // Ambos: el último es decimal
            if (lastComma > lastDot) {
                cleaned = s.replace(".", "").replace(",", ".");
            } else {
                cleaned = s.replace(",", "");
            }
        } else if (lastComma > -1) {
            // Solo coma: asumir decimal europeo (1234,56)
            cleaned = s.replace(",", ".");
        } else {
            cleaned = s;
        }
        try {
            return new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** SHA-256 del PDF para que el caller pueda detectar duplicados. */
    private String hashOf(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            return null;
        }
    }

    // ====================================================================
    //  Salida
    // ====================================================================

    public enum Confidence { HIGH, MEDIUM, LOW }

    public record ExtractionResult(
            List<String> allDetectedNifs,
            String emitterNif,
            String supplierName,
            String invoiceNumber,
            LocalDate invoiceDate,
            BigDecimal baseAmount,
            BigDecimal vatPercent,
            BigDecimal vatAmount,
            BigDecimal totalAmount,
            String documentSha256,
            Confidence confidence,
            String rawTextHead
    ) {
        public String invoiceDateIso() {
            return invoiceDate == null ? null : invoiceDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
    }
}
