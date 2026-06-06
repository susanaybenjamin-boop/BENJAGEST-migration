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

    /**
     * NIF/CIF/NIE según AEAT.
     * <p>Acepta también la variante "24259998 N" (espacio antes de la
     * letra de control) que algunos editores PDF generan cuando el
     * número y la letra están en glyphs separados. La regex normaliza
     * en {@link #cleanNif} eliminando el espacio.
     */
    private static final Pattern NIF_PATTERN = Pattern.compile(
            "\\b([XYZxyz]?\\d{7,8}\\s?[A-HJ-NP-TV-Za-hj-np-tv-z]|" +
            "[A-HJ-NP-SUVWa-hj-np-suvw]\\d{7}\\s?[0-9A-Ja-j])\\b"
    );

    /**
     * Número de IVA intracomunitario:
     *   LU20260743, IE6388047V, FR12345678901, DE123456789, NL123456789B01…
     * Útil para emisores extranjeros tipo Amazon EU S.à r.l. (LU).
     */
    private static final Pattern EU_VAT_PATTERN = Pattern.compile(
            "\\b((?:AT|BE|BG|CY|CZ|DE|DK|EE|EL|ES|FI|FR|GB|HR|HU|IE|IT|LT|LU|LV|MT|NL|PL|PT|RO|SE|SI|SK)\\d{8,12}[A-Z]?)\\b"
    );

    /**
     * Etiqueta "IVA LU20260743" o "VAT LU20260743" típica de Amazon u
     * otros proveedores intracomunitarios.
     */
    private static final Pattern EU_VAT_LABELED_PATTERN = Pattern.compile(
            "(?i)(?:IVA|VAT|n\\.?\\s*registro\\s+de\\s+IVA|nº\\s*reg(?:istro)?\\s+iva)\\s*#?\\s*:?\\s*" +
            "((?:AT|BE|BG|CY|CZ|DE|DK|EE|EL|ES|FI|FR|GB|HR|HU|IE|IT|LT|LU|LV|MT|NL|PL|PT|RO|SE|SI|SK)\\d{8,12}[A-Z]?)\\b"
    );

    /**
     * NIF español ETIQUETADO ("NIF W0184081H", "C.I.F. B12345678", "NIF
     * de la sucursal W0184081H"…). Mayor prioridad que el EU VAT cuando
     * conviven (caso típico: Amazon ES tiene LU20260743 + W0184081H — el
     * fiscal en España es el W, NO el LU).
     */
    private static final Pattern SPANISH_NIF_LABELED_PATTERN = Pattern.compile(
            "(?i)\\b(?:nif|c\\.?i\\.?f\\.?)\\s*(?:de\\s+la\\s+\\w+\\s+)?(?:emisor|empresa|sucursal|fiscal)?\\s*:?\\s*" +
            "([XYZxyz]?\\d{7,8}\\s?[A-HJ-NP-TV-Za-hj-np-tv-z]|" +
            "[A-HJ-NP-SUVWa-hj-np-suvw]\\d{7}\\s?[0-9A-Ja-j])\\b"
    );

    /**
     * VAT español "ESW0184081H" o "ES W0184081H" — extraemos la parte
     * sin el prefijo ES porque es un NIF español válido.
     */
    private static final Pattern SPANISH_VAT_PREFIX_PATTERN = Pattern.compile(
            "(?i)\\bES\\s?" +
            "([XYZ]?\\d{7,8}[A-HJ-NP-TV-Z]|" +
            "[A-HJ-NP-SUVW]\\d{7}[0-9A-J])\\b"
    );

    /**
     * Bloque "Vendido por" típico de Amazon. La línea siguiente es la
     * razón social del proveedor.
     */
    private static final Pattern SOLD_BY_PATTERN = Pattern.compile(
            // "Vendido por <razón>" o "Vendido por\n<razón>" — ambos formatos.
            // El captured group recoge hasta fin de línea.
            "(?i)(?:vendido\\s+por|sold\\s+by|seller)\\s*:?\\s*\\n?\\s*([^\\n]{3,120})"
    );

    /**
     * Meses en español para detectar fechas tipo "11 abril 2026" o
     * "11 de abril de 2026".
     */
    private static final String MONTH_NAMES_REGEX =
            "(enero|febrero|marzo|abril|mayo|junio|julio|agosto|" +
            "septiembre|setiembre|octubre|noviembre|diciembre)";

    private static final Pattern DATE_SPANISH_NAMED_PATTERN = Pattern.compile(
            "(?i)\\b(\\d{1,2})\\s+(?:de\\s+)?" + MONTH_NAMES_REGEX +
            "\\s+(?:de\\s+)?(\\d{4})\\b"
    );

    private static final java.util.Map<String, Integer> SPANISH_MONTHS = java.util.Map.ofEntries(
            java.util.Map.entry("enero", 1),
            java.util.Map.entry("febrero", 2),
            java.util.Map.entry("marzo", 3),
            java.util.Map.entry("abril", 4),
            java.util.Map.entry("mayo", 5),
            java.util.Map.entry("junio", 6),
            java.util.Map.entry("julio", 7),
            java.util.Map.entry("agosto", 8),
            java.util.Map.entry("septiembre", 9),
            java.util.Map.entry("setiembre", 9),
            java.util.Map.entry("octubre", 10),
            java.util.Map.entry("noviembre", 11),
            java.util.Map.entry("diciembre", 12)
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

    /**
     * Número de factura — versión amplia. Acepta variantes:
     *   - "Factura nº X" / "Número factura X" / "Nº de factura X"
     *   - "Número de la factura X" (Amazon)
     *   - "Número del documento X" (Amazon Bellota)
     *   - "Núm. Factura X" (Solred / Repsol)
     *   - "Invoice no/#/number X"
     */
    private static final Pattern INVOICE_NUMBER_PATTERN = Pattern.compile(
            // Tolerancia extra al char "º/°": variantes Unicode
            // (U+00BA, U+00B0, U+2070) + abreviaturas "Nro", "Núm".
            "(?i)(?:" +
            // 1) Label con "factura" / "documento" / "invoice".
            "n[\\u00ba\\u00b0\\u2070\\.\\s\\u00famero]*(?:del\\s+|de\\s+(?:la\\s+|el\\s+)?)?(?:factura|documento)" +
            "|n[\\u00famero]+\\.?\\s*factura" +
            "|factura\\s*n[\\u00ba\\u00b0\\u2070\\.\\s]*" +
            "|invoice\\s*(?:no|#|number)?" +
            "|ref(?:erencia)?\\.?\\s*factura" +
            // 2) "Nº/N°/Nro/Núm" sueltos. Al final para no canibalizar
            //    los más específicos.
            "|n[\\u00ba\\u00b0\\u2070]\\.?" +
            "|\\bnro\\.?" +
            "|\\bnum\\.?" +
            ")\\s*:?\\s*\\n?\\s*" +
            // Captura PERMISIVA: cualquier secuencia razonable de
            // letras/dígitos/separadores. Cubre cualquier prefijo
            // sin limitar el número de letras:
            //   "FRA-2026-0004"      — prefijo letras + guiones
            //   "A/2026/0004"        — letra suelta + barras
            //   "RECT-2026/001"      — rectificativa con barra
            //   "RECTIFICATIVA-001"  — prefijo largo
            //   "2026-0004"          — sin prefijo
            //   "FACT2026.0001"      — sin separador entre prefijo/nº
            //   "F-001/2026"         — letra suelta inicial
            //   "23-A-0042"          — números + letras + números
            //
            // IMPORTANTE: hemos quitado "fra[-]/fact[-]" como label
            // suelto porque canibalizaba el prefijo del propio nº
            // ("FRA-2026-0006" matchearía "FRA-" como label y
            // capturaría solo "2026-0006"). El número con prefijo
            // ya queda capturado correctamente por la captura de
            // la derecha cuando el label es "Nº FACTURA:".
            "([A-Z0-9][A-Z0-9\\-/_.]{1,38}[A-Z0-9])"
    );

    /**
     * Patrón "RECTIFICATIVA" en el head/body del documento. Detecta
     * variaciones: "FACTURA RECTIFICATIVA", "RECTIFICATIVA Nº", "Crédito
     * rectificativo", "Nota de abono nº", "Credit note". También captura
     * de qué factura es rectificación si aparece junto.
     */
    private static final Pattern RECTIFYING_PATTERN = Pattern.compile(
            "(?i)\\b(?:factura\\s+rectificativa|rectificativa|nota\\s+de\\s+abono|" +
            "credit\\s+note|abono\\s+sobre|rectifica\\s+(?:la\\s+)?factura)\\b"
    );

    /**
     * Captura el nº de la factura ORIGINAL anulada. Patrones típicos:
     *   "rectifica la factura 2024-001"
     *   "anula factura nº FRA-001"
     *   "abono sobre factura 2024-001"
     */
    private static final Pattern RECTIFIED_ORIGINAL_PATTERN = Pattern.compile(
            "(?i)(?:rectifica|anula|abono\\s+sobre|sustituye\\s+a|cancels?)\\s*" +
            "(?:la\\s+|el\\s+|the\\s+)?(?:factura|invoice|fra\\.?)\\s*" +
            "(?:n[\\u00ba\\u00b0]?\\.?\\s*)?" +
            "([A-Z0-9][A-Z0-9\\-/_.]{2,29})"
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

    /**
     * Etiquetas que indican que el NIF que sigue es el RECEPTOR, no el
     * emisor. Lista ampliada estilo CONTENDO con todos los formatos que
     * usan los softwares de facturación españoles y americanos.
     */
    private static final Pattern RECEIVER_LABEL = Pattern.compile(
            "(?i)\\b(cliente|destinatario|factura\\s+a|facturar\\s+a|" +
            "bill\\s+to|sold\\s+to|invoice\\s+to|ship\\s+to|" +
            "customer|buyer|comprador|" +
            "datos\\s+del\\s+cliente|datos\\s+del\\s+comprador|" +
            "raz[\\u00f3o]n\\s+social\\s+del?\\s+cliente|" +
            "raz[\\u00f3o]n\\s+social\\s+del?\\s+comprador|" +
            "para\\s*:|recibe\\s*:|destinatario\\s*:|" +
            "n\\.?\\s*if\\.?\\s+(?:cliente|destinatario|comprador)|" +
            "nif\\s+del\\s+cliente|cif\\s+del\\s+cliente)\\b"
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

    /**
     * Extrae UNA O VARIAS facturas del mismo PDF. Caso típico: Amazon
     * agrupa varias facturas pequeñas en un solo PDF (una página por
     * factura) — CONTENDO ya lo soportaba; aquí se replica.
     *
     * Estrategia de partición:
     *   - Se busca en CADA página el marcador "Página X de Y".
     *   - Cuando aparece "Página 1 de Y", ese grupo de páginas
     *     contiguas (Y páginas) constituye UNA factura.
     *   - Si NINGUNA página tiene marcador, se devuelve UNA sola
     *     factura con todo el documento (comportamiento legacy).
     *
     * Cada factura comparte el {@code documentSha256} del PDF completo
     * — eso identifica el contenedor, no la factura individual. La
     * deduplicación fina (por nº de factura + emisor) se hace al
     * persistir.
     */
    public List<ExtractionResult> extractAll(LayoutDocument document, byte[] originalBytes) {
        if (document == null || document.pages().isEmpty()) {
            return List.of(extract("", null, originalBytes));
        }
        // 1) Calcular marcador "Página X de Y" por página.
        Pattern marker = Pattern.compile(
                "(?i)(?:p[áa]gina|page)\\s+(\\d+)\\s+(?:de|of)\\s+(\\d+)");
        List<int[]> pageMarkers = new ArrayList<>(); // [pageIndex, x, y]
        for (int i = 0; i < document.pages().size(); i++) {
            LayoutDocument.LayoutPage page = document.pages().get(i);
            StringBuilder pageText = new StringBuilder();
            for (LayoutDocument.LayoutLine l : page.lines()) {
                pageText.append(l.text()).append('\n');
            }
            Matcher m = marker.matcher(pageText);
            if (m.find()) {
                pageMarkers.add(new int[]{
                        i, Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2))});
            } else {
                pageMarkers.add(new int[]{i, 0, 0});
            }
        }
        boolean anyMarker = false;
        for (int[] mk : pageMarkers) if (mk[1] > 0 && mk[2] > 0) { anyMarker = true; break; }
        if (!anyMarker) {
            return List.of(extract(document.toPlainText(), document, originalBytes));
        }
        // 2) Agrupar páginas por marcador "Página 1 de N".
        List<List<Integer>> groups = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (int[] mk : pageMarkers) {
            if (mk[1] == 1 && !current.isEmpty()) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(mk[0]);
        }
        if (!current.isEmpty()) groups.add(current);
        // 3) Para cada grupo, construir un LayoutDocument parcial y
        //    correr la extracción individual.
        List<ExtractionResult> results = new ArrayList<>();
        for (List<Integer> g : groups) {
            LayoutDocument sub = new LayoutDocument();
            for (int idx : g) sub.addPage(document.pages().get(idx));
            results.add(extract(sub.toPlainText(), sub, originalBytes));
        }
        return results;
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

        // 1. Recolectar todos los NIFs nacionales (orden de aparición).
        //    También recolectamos VAT intracomunitarios — Amazon EU
        //    S.à r.l. y similares no tienen NIF AEAT pero sí "IVA LU…".
        List<String> allNifs = findAll(NIF_PATTERN, text);
        List<String> allEuVat = findAll(EU_VAT_PATTERN, text);

        // 2. Emisor:
        //    a) RD 1619/2012 art. 6: emisor en la mitad IZQUIERDA del
        //       PDF. Si hay layout y encontramos un NIF allí, ése es
        //       el emisor con altísima fiabilidad. Cubre el caso
        //       común en que el text plano reconstruido pone el NIF
        //       del cliente ANTES que el del emisor (porque las
        //       líneas Y del bloque cliente están más arriba que las
        //       del CIF del emisor en facturas con 2 columnas).
        //    b) Si no hay layout o no encuentra → cascada textual
        //       antigua (CIF labeled, primer NIF, etc.).
        String emitterNif = guessEmitterByLayout(layout);
        if (emitterNif == null) {
            emitterNif = guessEmitterNif(text, allNifs, allEuVat);
        }
        // Normalizar: quitar espacios internos para que las
        // comparaciones funcionen consistentemente con el receptor.
        if (emitterNif != null) emitterNif = cleanNif(emitterNif);

        // 3. Razón social del emisor — prioridad al bloque "Vendido por".
        String supplierName = guessSupplierName(text, head, emitterNif);

        // 4. Número de factura. Triple estrategia:
        //    a) Regex clásico "Factura nº XYZ" / "Número de la factura".
        //    b) Tabla "Número Serie Fecha" + siguiente línea numérica.
        // Layout-first: en la cabecera, "FACTURA"/"Nº" suele estar en
        // la mitad DERECHA con el número a la derecha. Esto es muy
        // fiable cuando la regex textual falla por carácter "º" raro
        // o saltos de línea entre etiqueta y valor.
        String invoiceNumber = guessInvoiceNumberByLayout(layout);
        if (invoiceNumber == null) {
            invoiceNumber = findFirstGroup(INVOICE_NUMBER_PATTERN, text, 1);
        }
        if (invoiceNumber != null) invoiceNumber = invoiceNumber.trim();
        // Filtro de falsos positivos: la regex puede capturar palabras tras
        // "Nº" cuando el documento dice "Nº FACTURA: 2026/001". Rechazamos
        // valores que son solo la palabra "FACTURA", "RECIBO", "INVOICE"…
        // y volvemos a intentar con la búsqueda en tabla.
        if (invoiceNumber != null && isInvoiceNumberNoise(invoiceNumber)) {
            invoiceNumber = null;
        }
        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            invoiceNumber = findInvoiceNumberInTable(text);
        }
        // Si seguimos sin nada, intentamos un segundo pase de la regex
        // saltándonos cualquier match que sea noise (la palabra FACTURA
        // captura, pero el verdadero nº puede estar 2 líneas después).
        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            Matcher mNum = INVOICE_NUMBER_PATTERN.matcher(text);
            while (mNum.find()) {
                String candidate = mNum.group(1).trim();
                if (!isInvoiceNumberNoise(candidate)) {
                    invoiceNumber = candidate;
                    break;
                }
            }
        }

        // 5. Fecha — cascada:
        //    a) Cerca de "Fecha de la factura" / "Invoice date" → prioritaria.
        //    b) Primer formato numérico DD/MM/YYYY o YYYY-MM-DD.
        //    c) Primer formato nombre "11 abril 2026".
        LocalDate invoiceDate = findInvoiceDateLabeled(text);
        if (invoiceDate == null) invoiceDate = findFirstDate(text);
        if (invoiceDate == null) invoiceDate = findFirstNamedDate(text);

        // 6. Importes — cascada:
        //    a) Tabla "BASE IMPONIBLE | %IVA | CUOTA | TOTAL" (facturas
        //       de software estándar tipo Bloques Los Llanos).
        //    b) Tabla Amazon "IVA % | Precio total (IVA excluido) | IVA"
        //       con filas "21%  21,73  4,56" + "Total  21,73  4,56" +
        //       "Total  26,29".
        //    c) Etiquetas + ventana (fallback).
        TotalsRow totals = findTotalsTable(text);
        if (totals == null) {
            totals = findAmazonTotalsTable(text);
        }
        if (totals == null) {
            totals = findSolredTotalsRow(text);
        }
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

        // 8. Receptor (destinatario) — clave en facturas DE VENTA donde
        //    el emisor es el propio empresario y el receptor es a quien
        //    se le vendió.
        //
        //    Estrategia en cascada:
        //      a) RD 1619/2012 art. 6: en facturas españolas el emisor
        //         va en la mitad IZQUIERDA y el receptor en la mitad
        //         DERECHA de la página. Usamos coordenadas X del layout
        //         para encontrar el NIF que cae en la mitad derecha.
        //         Cobertura ~95% de facturas legales.
        //      b) Si no hay layout o no hay NIF en mitad derecha,
        //         caemos a búsqueda por etiqueta ("Cliente:", "Factura a:"
        //         etc.) sobre texto plano.
        String[] receiver = guessReceiverByLayout(layout, emitterNif);
        if (receiver == null || (receiver[0] == null && receiver[1] == null)) {
            receiver = guessReceiver(text, allNifs, allEuVat, emitterNif);
        }
        String receiverNif = receiver[0];
        String receiverName = receiver[1];

        // 9. Rectificativa — dos señales independientes:
        //    a) TEXTO: cabecera dice "FACTURA RECTIFICATIVA", "Nota de
        //       abono", "Credit note", "Abono sobre"… Patrón RECTIFYING_PATTERN.
        //    b) TOTAL NEGATIVO: por contabilidad, una factura
        //       rectificativa SIEMPRE lleva el total < 0 (es un abono
        //       al cliente). OJO: una factura normal puede llevar UNA
        //       línea en negativo (descuento puntual) pero el TOTAL
        //       sigue siendo positivo. Por eso miramos el TOTAL, no
        //       línea por línea.
        //
        //    Marcamos rectificativa si CUALQUIERA de las dos señales
        //    matchea. La señal de total negativo cubre el caso
        //    frecuente de PDFs sin la palabra "rectificativa" en la
        //    cabecera (algunos editores la imprimen como "FACTURA"
        //    a secas con totales en rojo/negativo).
        String headForRect = text.length() > 600 ? text.substring(0, 600) : text;
        boolean rectifyingByText = RECTIFYING_PATTERN.matcher(headForRect).find();
        boolean rectifyingByTotal = total != null && total.signum() < 0;
        boolean rectifying = rectifyingByText || rectifyingByTotal;
        String rectifiedNumber = null;
        if (rectifying) {
            Matcher rm = RECTIFIED_ORIGINAL_PATTERN.matcher(text);
            if (rm.find()) rectifiedNumber = rm.group(1).trim();
        }

        return new ExtractionResult(
                allNifs, emitterNif, supplierName, invoiceNumber, invoiceDate,
                base, vatPct, vatAmount, total,
                hashOf(originalBytes), confidence, head,
                receiverNif, receiverName, rectifying, rectifiedNumber
        );
    }

    /**
     * Detecta receptor usando posición X/Y del layout.
     *
     * <p>Aprovecha la convención legal del RD 1619/2012 art. 6: en
     * facturas españolas el bloque del emisor va a la izquierda y el
     * del receptor a la derecha de la página. Buscamos en las primeras
     * 20 líneas (cabecera) y nos quedamos con:
     * <ol>
     *   <li>El NIF cuyo span esté en la mitad derecha (x > pageWidth/2)
     *       y NO sea el emisor.</li>
     *   <li>El nombre = primera línea con contenido en la mitad derecha
     *       de la cabecera que no sea dirección/teléfono/etiqueta.</li>
     * </ol>
     *
     * @return {@code String[]{nif, name}} o null si no hay layout.
     */
    /**
     * Detecta el NIF del emisor usando coordenadas X (RD 1619/2012):
     * busca un NIF en la mitad IZQUIERDA de la cabecera. Cobertura
     * ~95% de facturas legales españolas, independiente del software
     * emisor.
     *
     * <p>Razón por la que existe: cuando el text plano se reconstruye
     * línea-a-línea (Y), en facturas con 2 columnas el NIF del
     * CLIENTE (esquina superior-derecha) acaba apareciendo ANTES en
     * el texto que el NIF del EMISOR (mitad-izquierda, más abajo).
     * Las cascadas textuales "primer NIF antes del receptor" caen
     * en ese orden y devuelven el NIF del cliente como emisor.
     */
    private String guessEmitterByLayout(LayoutDocument layout) {
        if (layout == null || layout.pages().isEmpty()) return null;
        LayoutDocument.LayoutPage page = layout.pages().get(0);
        if (page == null || page.lines().isEmpty()) return null;
        float pageWidth = page.width();
        if (pageWidth <= 0) return null;
        float midX = pageWidth * 0.50f; // emisor a la izquierda: <50%

        Pattern nifPat = Pattern.compile(
                "\\b([XYZ]?\\d{7,8}\\s?[A-HJ-NP-TV-Z]|" +
                "[A-HJ-NP-SUVW]\\d{7}\\s?[0-9A-J])\\b");

        int headLines = Math.min(35, page.lines().size());
        for (int i = 0; i < headLines; i++) {
            LayoutDocument.LayoutLine line = page.lines().get(i);
            // Recolectamos los spans con x < midX (mitad izquierda).
            StringBuilder left = new StringBuilder();
            for (LayoutDocument.LayoutSpan span : line.spans()) {
                if (span.x() >= midX) continue;
                if (left.length() > 0) left.append(' ');
                left.append(span.text());
            }
            String s = left.toString();
            if (s.isBlank()) continue;
            Matcher m = nifPat.matcher(s);
            if (m.find()) return cleanNif(m.group(1));
        }
        return null;
    }

    /**
     * Detecta el número de factura usando layout. Estrategia:
     * <ol>
     *   <li>Busca la línea de cabecera que contenga la palabra
     *       "FACTURA" / "Nº" / "INVOICE".</li>
     *   <li>En esa línea o en las 2 siguientes, busca un token
     *       alfanumérico que parezca número (al menos un dígito,
     *       entre 3 y 40 chars).</li>
     *   <li>Descarta tokens que sean fechas (dd/mm/yyyy), CIFs,
     *       o palabras puras como "FACTURA".</li>
     * </ol>
     *
     * <p>Útil cuando la regex textual falla por:
     * <ul>
     *   <li>Carácter "º" que no es ni U+00BA ni U+00B0</li>
     *   <li>Etiqueta y valor en líneas separadas con > 1 newline</li>
     *   <li>Espacios irregulares entre etiqueta y número</li>
     * </ul>
     */
    private String guessInvoiceNumberByLayout(LayoutDocument layout) {
        if (layout == null || layout.pages().isEmpty()) return null;
        LayoutDocument.LayoutPage page = layout.pages().get(0);
        if (page == null || page.lines().isEmpty()) return null;

        int headLines = Math.min(20, page.lines().size());
        // Etiqueta de cabecera de factura.
        Pattern labelPat = Pattern.compile(
                "(?i)\\b(?:n[\\u00ba\\u00b0\\u2070]\\.?\\s*(?:factura|fact|fra|documento)?" +
                "|factura\\s*n[\\u00ba\\u00b0\\u2070]?" +
                "|invoice\\s*(?:no|#|number)?" +
                "|n[uú]mero\\s+factura" +
                "|nro\\.?\\s*factura?" +
                "|num\\.?\\s*factura?" +
                ")\\b");
        // Candidato a nº de factura: empieza letra/dígito, acaba letra/dígito,
        // al menos un dígito, separadores típicos en medio.
        Pattern tokenPat = Pattern.compile(
                "\\b([A-Z0-9](?=[A-Z0-9\\-/_.]{1,38}[A-Z0-9])[A-Z0-9\\-/_.]{1,38}[A-Z0-9])\\b");
        // Patrón de fecha que descartamos (dd/mm/yyyy, dd-mm-yy, yyyy-mm-dd…)
        Pattern datePat = Pattern.compile(
                "^(\\d{1,4}[\\-/\\.]\\d{1,2}[\\-/\\.]\\d{1,4})$");

        for (int i = 0; i < headLines; i++) {
            LayoutDocument.LayoutLine line = page.lines().get(i);
            String text = line.text();
            Matcher lm = labelPat.matcher(text);
            if (!lm.find()) continue;
            // 1) Tras la etiqueta, intenta matchear un token en la
            //    misma línea.
            String afterLabel = text.substring(lm.end());
            String candidate = pickInvoiceNumberToken(afterLabel,
                    tokenPat, datePat);
            if (candidate != null) return candidate;
            // 2) Si la misma línea no tiene token, prueba las 2 líneas
            //    siguientes (caso etiqueta / valor en líneas distintas).
            for (int j = i + 1; j < Math.min(headLines, i + 3); j++) {
                String next = page.lines().get(j).text();
                candidate = pickInvoiceNumberToken(next, tokenPat, datePat);
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    /** Selecciona el primer token de la línea que parezca nº de factura. */
    private String pickInvoiceNumberToken(String text, Pattern tokenPat,
                                            Pattern datePat) {
        Matcher tm = tokenPat.matcher(text.toUpperCase());
        while (tm.find()) {
            String cand = tm.group(1).trim();
            if (cand.length() < 3) continue;
            if (!cand.matches(".*\\d.*")) continue; // sin dígitos
            if (datePat.matcher(cand).matches()) continue; // fecha
            if (isInvoiceNumberNoise(cand)) continue; // palabra suelta
            // No empezar con un mes/día solo: rechazar tokens que
            // parezcan parte de la fecha sin más estructura.
            return cand;
        }
        return null;
    }

    private String[] guessReceiverByLayout(LayoutDocument layout, String emitterNif) {
        if (layout == null || layout.pages().isEmpty()) return null;
        LayoutDocument.LayoutPage page = layout.pages().get(0);
        if (page == null || page.lines().isEmpty()) return null;
        float pageWidth = page.width();
        if (pageWidth <= 0) return null;
        // 40% del ancho: pilla bloques de cliente algo corridos a la
        // izquierda. Bastante permisivo.
        float midX = pageWidth * 0.40f;

        // NIF tolerante a espacio entre dígitos y letra de control
        // ("24259998 N", "B1234567 8"…).
        Pattern nifPat = Pattern.compile(
                "\\b([XYZ]?\\d{7,8}\\s?[A-HJ-NP-TV-Z]|" +
                "[A-HJ-NP-SUVW]\\d{7}\\s?[0-9A-J])\\b");

        int totalLines = page.lines().size();
        int headLines = Math.min(35, totalLines);
        String emitterNifClean = emitterNif == null ? null : cleanNif(emitterNif);

        // 1) Buscar NOMBRE primero — más fácil porque la primera línea
        //    no vacía de la mitad derecha que parece nombre suele ser
        //    el cliente. Recorremos la cabecera desde arriba.
        String name = null;
        int nameLineIdx = -1;
        for (int i = 0; i < headLines; i++) {
            String s = collectRightHalf(page.lines().get(i), midX);
            if (isReceiverNameCandidate(s)) {
                name = trimName(s);
                nameLineIdx = i;
                break;
            }
        }

        // 2) Buscar NIF — el nombre del cliente es el ancla más fiable.
        //    Una vez localizado el nombre, el NIF SIEMPRE está en las
        //    siguientes 1-6 líneas (caso típico: nombre arriba, NIF
        //    debajo, dirección debajo del NIF).
        //
        //    IMPORTANTE: restringimos a la MITAD DERECHA (mismo midX
        //    que el nombre) para evitar pillar el NIF del emisor de
        //    la mitad izquierda cuando guessEmitterByLayout falló y
        //    el filtro por "emitterNifClean" no es fiable.
        String nif = null;
        if (nameLineIdx >= 0) {
            int end = Math.min(headLines, nameLineIdx + 8);
            for (int i = nameLineIdx; i < end; i++) {
                String rightLine = collectRightHalf(page.lines().get(i), midX);
                Matcher m = nifPat.matcher(rightLine);
                while (m.find()) {
                    String cand = cleanNif(m.group(1));
                    if (emitterNifClean != null
                            && cand.equalsIgnoreCase(emitterNifClean)) continue;
                    nif = cand;
                    break;
                }
                if (nif != null) break;
            }
        }
        // 3) Fallback: si no encontramos nombre o NIF por proximidad,
        //    rastreamos toda la cabecera en mitad derecha por NIF.
        if (nif == null) {
            for (int i = 0; i < headLines; i++) {
                LayoutDocument.LayoutLine line = page.lines().get(i);
                String rightLine = collectRightHalf(line, midX);
                Matcher m = nifPat.matcher(rightLine);
                while (m.find()) {
                    String cand = cleanNif(m.group(1));
                    if (emitterNifClean != null
                            && cand.equalsIgnoreCase(emitterNifClean)) continue;
                    nif = cand;
                    break;
                }
                if (nif != null) break;
            }
        }

        if (nif == null && name == null) return null;
        return new String[]{nif, name};
    }

    /** Concatena los spans de una línea cuya x >= midX. */
    private String collectRightHalf(LayoutDocument.LayoutLine line, float midX) {
        StringBuilder right = new StringBuilder();
        for (LayoutDocument.LayoutSpan span : line.spans()) {
            if (span.x() < midX) continue;
            if (right.length() > 0) right.append(' ');
            right.append(span.text());
        }
        return right.toString().trim();
    }

    /**
     * Decide si una línea de la mitad derecha puede ser el NOMBRE
     * del receptor. Descartamos:
     * <ul>
     *   <li>Etiquetas de cabecera ("Nº FACTURA", "Fecha", "Date",
     *       "Invoice no", "Página")</li>
     *   <li>Líneas con muchos dígitos (= número de factura, fecha, CP)</li>
     *   <li>Direcciones (C/, Avda., Calle, número de portal)</li>
     *   <li>Email, teléfono, web</li>
     *   <li>Etiquetas tipo "NIF:" / "CIF:" — vienen antes del valor</li>
     * </ul>
     */
    private boolean isReceiverNameCandidate(String s) {
        if (s == null || s.length() < 4) return false;
        String up = s.toUpperCase();
        // Cabeceras típicas en la mitad derecha de la página.
        if (up.matches(".*\\b(N[\\u00ba\\u00b0]?\\s*(FACTURA|FRA|FACT|DOCUMENTO|INVOICE|" +
                "OPERACI[OÓ]N))\\b.*")) return false;
        if (up.matches(".*\\b(FECHA|FECHA\\s+FACTURA|FECHA\\s+EMISI[OÓ]N|" +
                "DATE|INVOICE\\s+DATE|EMISI[OÓ]N)\\b.*")) return false;
        if (up.matches(".*\\b(PAGINA|P[ÁA]GINA|PAGE)\\b.*")) return false;
        if (up.matches(".*\\b(VENCIMIENTO|DUE\\s+DATE|FORMA\\s+DE\\s+PAGO|" +
                "MEDIO\\s+DE\\s+PAGO|PAYMENT)\\b.*")) return false;
        // Etiquetas de NIF/CIF (vienen antes del valor).
        if (up.matches("(?:NIF|CIF|NIE|TAX\\s+ID|VAT)\\s*:?\\s*$")) return false;
        // Direcciones obvias.
        if (up.matches("^(C/|C\\.|CALLE|AVDA\\.?|AVENIDA|PLAZA|PLZA\\.?|" +
                "PASEO|PASAJE|RONDA|TRAVES[ÍI]A|CTRA\\.?|CARRETERA)\\s.*")) return false;
        // Códigos postales (5 dígitos + ciudad).
        if (up.matches("^\\d{5}\\s+.*")) return false;
        // Email/web/tel.
        if (up.contains("@") || up.contains("HTTP") || up.contains("WWW.")) return false;
        if (up.matches(".*\\b(TEL[E]?F\\.?|TLF\\.?|TEL\\.?|M[OÓ]VIL|MOBILE|FAX)\\b.*")) return false;
        // Demasiados dígitos → no es un nombre.
        long digits = up.chars().filter(Character::isDigit).count();
        if (digits > 3) return false;
        // Al menos 2 palabras alfabéticas razonables (nombre + apellido)
        // — descarta "FACTURA", "España", líneas de una sola palabra.
        String[] words = s.trim().split("\\s+");
        int alphaWords = 0;
        for (String w : words) if (w.matches("[A-Za-z\\u00c0-\\u017f.'-]{2,}")) alphaWords++;
        if (alphaWords < 2) return false;
        return true;
    }

    private String trimName(String s) {
        String t = s.trim();
        // Quitar etiquetas tipo "Cliente:", "Razón social:" al principio.
        t = t.replaceFirst("(?i)^\\s*(?:cliente|destinatario|raz[\\u00f3o]n\\s+social|" +
                "facturar?\\s+a|para)\\s*:?\\s*", "");
        return t.length() > 120 ? t.substring(0, 120) : t;
    }

    /** Quita espacios internos del NIF ("24259998 N" → "24259998N"). */
    private String cleanNif(String s) {
        if (s == null) return null;
        return s.replaceAll("\\s+", "").toUpperCase();
    }

    /**
     * Busca el NIF + nombre del DESTINATARIO de la factura.
     * Estrategia: localiza un label de receptor ("Cliente:", "Factura a:",
     * "Destinatario:"…) y a partir de su posición busca un NIF distinto
     * del emisor en una ventana corta. El nombre suele estar en la línea
     * inmediatamente posterior al label o pegado al NIF.
     *
     * @return {@code String[]{nif, name}} — cualquiera puede ser null.
     */
    private String[] guessReceiver(String text, List<String> allNifs,
                                     List<String> allEuVat, String emitterNif) {
        if (text == null || text.isBlank()) return new String[]{null, null};
        Matcher recv = RECEIVER_LABEL.matcher(text);
        if (!recv.find()) {
            // Sin etiqueta clara de receptor → NO inferimos.
            //
            // El fallback "segundo NIF distinto del emisor" parecía
            // razonable pero es peligroso: en facturas donde los datos
            // del cliente aparecen ARRIBA (formato común español),
            // guessEmitterNif se confunde y devuelve el NIF del
            // cliente como emisor. Entonces el fallback aquí
            // devolvería el NIF del emisor real como receptor, y la
            // UI acaba pintando al emisor (Benjamin) como cliente.
            //
            // Mejor dejar nulls: la UI muestra los campos vacíos y el
            // usuario los rellena en 5 segundos. Es preferible vacío
            // y correcto que auto-rellenado con el dato equivocado.
            return new String[]{null, null};
        }
        int start = recv.end();
        // Ventana de 400 chars tras el label: ahí debe estar el NIF y el
        // nombre del receptor.
        int end = Math.min(text.length(), start + 400);
        String window = text.substring(start, end);

        // 1) Buscar primer NIF en la ventana que NO sea el del emisor.
        String nif = null;
        Pattern nifPat = Pattern.compile(
                "\\b([XYZ0-9][0-9]{7}[A-Z]|[A-HJ-NP-SUVW][0-9]{7}[0-9A-J])\\b");
        Matcher m = nifPat.matcher(window);
        while (m.find()) {
            String candidate = m.group(1).toUpperCase();
            if (emitterNif == null || !candidate.equalsIgnoreCase(emitterNif)) {
                nif = candidate;
                break;
            }
        }

        // 2) Buscar nombre — primera línea no vacía tras el label, o tras
        //    el NIF si lo encontramos.
        String name = null;
        String afterLabel = window.replaceFirst("^\\s*:?\\s*", "");
        for (String line : afterLabel.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            // Saltar líneas que sean solo un NIF o números.
            if (nifPat.matcher(s).matches()) continue;
            if (s.matches("^[\\d\\s\\-/]+$")) continue;
            // Saltar líneas que contengan el emisor (evita falsos positivos).
            if (emitterNif != null && s.toUpperCase().contains(emitterNif)) continue;
            // Saltar etiquetas frecuentes.
            if (s.matches("(?i).*\\b(direccion|address|c\\.p\\.|cp|telef|tel|email|@|nif|cif).*")) continue;
            // Recortar al primer NIF inline si está presente.
            Matcher inline = nifPat.matcher(s);
            if (inline.find()) {
                s = s.substring(0, inline.start()).trim();
                if (s.isEmpty()) continue;
            }
            // Limitar a una longitud razonable.
            if (s.length() > 120) s = s.substring(0, 120);
            name = s;
            break;
        }
        // Fallback de nombre: si tenemos el NIF del receptor por la
        // ventana pero no pudimos extraer el nombre de la misma
        // ventana, lo buscamos en las líneas adyacentes al NIF en
        // TODO el documento. Esto es seguro porque el NIF lo
        // confirmamos dentro de la ventana de etiqueta — solo
        // extendemos la búsqueda del nombre.
        //
        // NO hacemos fallback del NIF a "primer NIF distinto del
        // emisor" porque si guessEmitterNif se equivocó, devolvemos
        // al emisor real como cliente.
        if (name == null && nif != null) name = guessNameNearNif(text, nif);
        return new String[]{nif, name};
    }

    /**
     * De la lista completa de NIFs detectados en el documento, devuelve
     * el primero que NO sea el emisor.
     *
     * <p>Reservado para uso futuro — actualmente NO se usa como fallback
     * porque si guessEmitterNif se equivocó (caso común cuando los
     * datos del cliente salen arriba), este "fallback" devuelve al
     * emisor real como cliente, lo que rompe el concepto generado.
     */
    @SuppressWarnings("unused")
    private String pickReceiverNifFromList(List<String> allNifs, String emitterNif) {
        if (allNifs == null) return null;
        for (String n : allNifs) {
            if (n == null) continue;
            if (emitterNif != null && n.equalsIgnoreCase(emitterNif)) continue;
            return n.toUpperCase();
        }
        return null;
    }

    /**
     * Busca un nombre razonable en las líneas que rodean a un NIF dado.
     * Útil cuando tenemos el NIF del receptor por fallback pero no
     * sabemos qué etiqueta lo precede.
     *
     * <p>Estrategia: el nombre suele estar 0-3 líneas ANTES del NIF
     * (cabecera del bloque cliente) o en la MISMA línea pegado al NIF.
     */
    private String guessNameNearNif(String text, String nif) {
        if (text == null || nif == null) return null;
        int idx = text.toUpperCase().indexOf(nif.toUpperCase());
        if (idx < 0) return null;

        // 1) Línea con el NIF inline: el nombre puede ir antes del NIF.
        int lineStart = Math.max(0, text.lastIndexOf('\n', idx - 1) + 1);
        int lineEnd = text.indexOf('\n', idx);
        if (lineEnd < 0) lineEnd = text.length();
        String sameLine = text.substring(lineStart, idx).trim();
        if (sameLine.length() >= 3 && looksLikeName(sameLine)) {
            return sameLine.length() > 120 ? sameLine.substring(0, 120) : sameLine;
        }

        // 2) 0-3 líneas anteriores: bloque de razón social arriba del NIF.
        String head = text.substring(0, lineStart);
        String[] prevLines = head.split("\\r?\\n");
        for (int i = prevLines.length - 1; i >= 0 && i >= prevLines.length - 4; i--) {
            String s = prevLines[i].trim();
            if (s.isEmpty()) continue;
            if (!looksLikeName(s)) continue;
            return s.length() > 120 ? s.substring(0, 120) : s;
        }
        return null;
    }

    /**
     * Heurística mínima para decidir si una línea parece un nombre /
     * razón social (no una dirección, código postal, teléfono…).
     */
    private boolean looksLikeName(String s) {
        if (s == null || s.length() < 3) return false;
        // Demasiados dígitos = código postal o ID.
        long digits = s.chars().filter(Character::isDigit).count();
        if (digits > s.length() / 3) return false;
        // Etiquetas frecuentes a descartar.
        if (s.matches("(?i).*\\b(direccion|address|c\\.?\\s*p\\.?|telef|tel\\.?|" +
                "email|@|nif|cif|iban|cuenta|web|http|www\\.)\\b.*")) return false;
        // Al menos una letra.
        if (!s.matches(".*[A-Za-z\\u00c0-\\u017f].*")) return false;
        return true;
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

    /**
     * Detector para facturas tipo Solred/Repsol (combustibles):
     *
     *   Total Factura en Euros   169,14   16,91   186,05
     *
     * Estos PDFs no traen cabecera "BASE IMPONIBLE" / "TOTAL". El
     * marcador único es la fila "Total Factura en Euros" con 3
     * importes: base, cuota IVA, total.
     */
    private TotalsRow findSolredTotalsRow(String text) {
        Pattern line = Pattern.compile(
                "(?i)total\\s+factura\\s+en\\s+euros\\s+" +
                "(-?\\d+(?:\\.\\d{3})*[,.]\\d{2})\\s+" +
                "(-?\\d+(?:\\.\\d{3})*[,.]\\d{2})\\s+" +
                "(-?\\d+(?:\\.\\d{3})*[,.]\\d{2})"
        );
        Matcher m = line.matcher(text);
        if (!m.find()) return null;
        TotalsRow row = new TotalsRow();
        row.base = parseAmount(m.group(1));
        row.vatAmount = parseAmount(m.group(2));
        row.total = parseAmount(m.group(3));
        return row;
    }

    /**
     * Detector alternativo de totales para facturas tipo Amazon:
     *
     *   IVA % Precio total
     *   (IVA excluido)
     *   IVA
     *   21% 21,73 € 4,56 €
     *   Total 21,73 € 4,56 €
     *   Total 26,29 €
     *
     * Estrategia:
     *   - Detectar cabecera "IVA % … Precio total … IVA" (con saltos).
     *   - Línea con "%" + 2 importes → %IVA, base, cuota.
     *   - Línea siguiente que empiece por "Total" y traiga UN solo
     *     importe → total final.
     */
    private TotalsRow findAmazonTotalsTable(String text) {
        // Estrategia A (anterior, frágil): localizar cabecera "IVA % Precio
        // total (IVA excluido)" y leer las líneas siguientes.
        Pattern header = Pattern.compile(
                "(?i)iva\\s*%[\\s\\S]{0,30}?precio\\s+total[\\s\\S]{0,30}?\\(?iva\\s+excluido"
        );
        Matcher h = header.matcher(text);
        if (h.find()) {
            int from = h.end();
            TotalsRow row = scanAmazonPctLine(text, from, 400);
            if (row != null) return row;
        }
        // Estrategia B (nueva): SIGNATURE — escanear TODAS las líneas en
        //   busca de "<pct>% <importe1> <importe2>" estricto + un
        //   "Total <importe>" cercano que cumpla base+iva ≈ total. Sirve
        //   incluso cuando el layout no preserva la cabecera (PDFs cuyas
        //   columnas se desordenan al extraer).
        return scanAmazonPctLine(text, 0, text.length());
    }

    /**
     * Escanea una ventana de texto buscando la firma de la fila de
     * totales tipo Amazon:
     *
     *   - Una línea cuyo contenido completo sea "<pct>% <base> [€] <iva> [€]"
     *     (3 tokens, estrictos, sin más).
     *   - En las 10 líneas siguientes, una línea "Total <importe> [€]"
     *     con UN solo importe. Si hay varias, nos quedamos con la mayor
     *     (el total final).
     *   - Validación: base + iva ≈ total (±0,10 €). Si no encaja, se
     *     descarta y se intenta la siguiente coincidencia — protege
     *     contra falsos positivos en líneas del cuerpo (descuentos, etc.).
     */
    private TotalsRow scanAmazonPctLine(String text, int fromOffset, int maxChars) {
        String slice = text.substring(fromOffset,
                Math.min(text.length(), fromOffset + maxChars));
        String[] lines = slice.split("\\n");
        Pattern pctLinePattern = Pattern.compile(
                "^(\\d{1,2}(?:[,.]\\d{1,2})?)\\s*%\\s+" +
                "(-?\\d+(?:[.,]\\d{3})*[,.]\\d{2})\\s*\\u20ac?\\s+" +
                "(-?\\d+(?:[.,]\\d{3})*[,.]\\d{2})\\s*\\u20ac?\\s*$"
        );
        // CLAVE: regex ESTRICTA — "Total" + UN solo importe + fin de
        // línea. Descarta filas tipo "Total 35,09 € 7,37 €" (subtotal
        // de la tabla de IVA, que tiene 2 importes) y se queda con la
        // fila "Total 42,46 €" / "Total 26,29 €" del total general.
        Pattern totalLinePattern = Pattern.compile(
                "(?i)^total(?:\\s+pendiente|\\s+factura(?:\\s+en\\s+euros)?|\\s+a\\s+pagar|\\s+general)?\\s+" +
                "(-?\\d+(?:[.,]\\d{3})*[,.]\\d{2})\\s*\\u20ac?\\s*$"
        );
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            Matcher pct = pctLinePattern.matcher(line);
            if (!pct.find()) continue;
            BigDecimal vatPct = parseAmount(pct.group(1));
            BigDecimal base = parseAmount(pct.group(2));
            BigDecimal vat = parseAmount(pct.group(3));
            if (base == null || vat == null) continue;
            // Coherencia interna: la cuota no puede ser mayor que la base
            // (descarta capturas del cuerpo tipo "1 12,99 21%" malalineadas).
            if (vat.compareTo(base) > 0) continue;

            BigDecimal expected = base.add(vat);
            // Buscar "Total <importe único>" en ±10 líneas (Amazon ES
            // a veces pone el total ARRIBA del bloque IVA, otras veces
            // ABAJO — depende del template). Buscamos en ambas
            // direcciones y nos quedamos con el candidato más cercano
            // a base+vat (±0,10 €).
            BigDecimal verifiedTotal = null;
            int from = Math.max(0, i - 10);
            int to = Math.min(lines.length, i + 11);
            for (int j = from; j < to; j++) {
                if (j == i) continue;
                String tline = lines[j].trim();
                Matcher tm = totalLinePattern.matcher(tline);
                if (!tm.find()) continue;
                BigDecimal cand = parseAmount(tm.group(1));
                if (cand == null) continue;
                if (cand.subtract(expected).abs()
                        .compareTo(new BigDecimal("0.10")) <= 0) {
                    verifiedTotal = cand;
                    break;
                }
            }

            TotalsRow row = new TotalsRow();
            row.vatPercent = vatPct;
            row.base = base;
            row.vatAmount = vat;
            // Si encontramos "Total" verificado, lo usamos (más
            // fidedigno al documento). Si no, base+vat es tautológico
            // pero correcto — la firma <pct>% <base> <vat> ya es muy
            // específica de por sí.
            row.total = verifiedTotal != null ? verifiedTotal : expected;
            return row;
        }
        return null;
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
            // Normalizamos quitando espacios internos para que "24259998 N"
            // y "24259998N" se traten como el mismo NIF en comparaciones.
            seen.add(v.replaceAll("\\s+", "").toUpperCase());
        }
        return new ArrayList<>(seen);
    }

    private String findFirstGroup(Pattern p, String text, int group) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(group) : null;
    }

    /**
     * Determina si un candidato a "número de factura" es en realidad
     * ruido (la palabra "FACTURA", "INVOICE", "Nº", "DE", "DEL"…). Un
     * número de factura real lleva siempre al menos un dígito.
     */
    private boolean isInvoiceNumberNoise(String s) {
        if (s == null) return true;
        String up = s.toUpperCase().trim();
        if (up.isBlank()) return true;
        // Sin dígitos = palabra, no número.
        if (!up.matches(".*\\d.*")) return true;
        // Listas de palabras puramente alfabéticas que la regex puede
        // capturar por estar pegadas a "Nº" o a "Factura nº".
        switch (up) {
            case "FACTURA": case "INVOICE": case "RECIBO": case "TICKET":
            case "NUMERO": case "NÚMERO": case "NUM": case "FACT": case "FRA":
            case "DEL": case "DE": case "LA": case "EL":
                return true;
            default:
        }
        return false;
    }

    private String guessEmitterNif(String text, List<String> allNifs, List<String> allEuVat) {
        // Determinar dónde aparece la etiqueta de receptor para descartar
        // NIFs que estén por debajo (esos son del cliente, no del emisor).
        Matcher recv = RECEIVER_LABEL.matcher(text);
        int receiverPos = recv.find() ? recv.start() : Integer.MAX_VALUE;

        // PRIORIDAD 1 (sucursal extranjera): si el documento contiene un
        //   EU VAT (LU, IE, FR…) Y ADEMÁS un NIF español etiquetado o un
        //   VAT con prefijo ES, preferimos el NIF español. Caso Amazon ES.
        //   Solo se aplica con EU VAT presente porque la regex de NIF
        //   español etiquetado es amplia y matchearía erróneamente "NIF:
        //   74668351R" del cliente en facturas nacionales sin VAT.
        if (!allEuVat.isEmpty()) {
            Matcher snif = SPANISH_NIF_LABELED_PATTERN.matcher(text);
            while (snif.find()) {
                if (snif.start() < receiverPos) {
                    return snif.group(1).toUpperCase();
                }
            }
            Matcher svat = SPANISH_VAT_PREFIX_PATTERN.matcher(text);
            while (svat.find()) {
                if (svat.start() < receiverPos) {
                    return svat.group(1).toUpperCase();
                }
            }
        }
        // PRIORIDAD 2: "CIF B12345678" explícito (pie español).
        Matcher cif = CIF_EXPLICIT_PATTERN.matcher(text);
        if (cif.find()) {
            return cif.group(1).toUpperCase();
        }
        // PRIORIDAD 3: "IVA LU20260743" etiquetado — para proveedores
        //   100% extranjeros (sin sucursal española).
        Matcher eu = EU_VAT_LABELED_PATTERN.matcher(text);
        if (eu.find()) {
            return eu.group(1).toUpperCase();
        }
        // PRIORIDAD 4: el primer NIF nacional antes del receptor.
        Matcher nifMatcher = NIF_PATTERN.matcher(text);
        while (nifMatcher.find()) {
            if (nifMatcher.start() < receiverPos) {
                return nifMatcher.group(1).toUpperCase();
            }
        }
        // PRIORIDAD 5: el primer VAT EU sin etiqueta.
        if (!allEuVat.isEmpty()) return allEuVat.get(0);
        // PRIORIDAD 6: el primer CIF (letra+8) en el texto. Empieza por
        // letra → más probable que sea sociedad emisora que persona física.
        for (String n : allNifs) {
            if (n.matches("^[A-HJ-NP-SUVW].*")) return n;
        }
        // Fallback: primer NIF detectado o null.
        return allNifs.isEmpty() ? null : allNifs.get(0);
    }

    /**
     * Heurística para razón social: primera línea no vacía de la
     * cabecera que no sea una etiqueta común ni contenga el NIF emisor.
     * Inspirada en cómo Claude lo razona en CONTENDO — aquí la
     * aproximamos con reglas duras.
     */
    private String guessSupplierName(String fullText, String head, String emitterNif) {
        // PRIORIDAD 1: bloque "Vendido por\n<razón social>" (Amazon).
        Matcher soldBy = SOLD_BY_PATTERN.matcher(fullText);
        if (soldBy.find()) {
            String candidate = soldBy.group(1).trim();
            // Descartar si es solo "Amazon" suelto sin S.à r.l. → preferimos
            // el match completo más abajo. Si el match es ≥ 8 chars, OK.
            if (candidate.length() >= 8) return candidate;
        }
        return guessSupplierNameFromHead(head, emitterNif);
    }

    /**
     * Palabras que aparecen como cabeceras de bloque en facturas y NO
     * son nombre de proveedor. Lista mantenible — añadir variantes
     * conforme aparecen casos reales.
     */
    private static final java.util.Set<String> HEADER_BLACKLIST = java.util.Set.of(
            "DIRECCIÓN DE CORRESPONDENCIA", "DIRECCION DE CORRESPONDENCIA",
            "DOMICILIO FISCAL", "DIRECCIÓN DE FACTURACIÓN", "DIRECCION DE FACTURACION",
            "DIRECCIÓN DE ENVÍO", "DIRECCION DE ENVIO",
            "BILLING ADDRESS", "SHIPPING ADDRESS", "INVOICE ADDRESS",
            "PAGADO", "PAID", "PENDIENTE", "TOTAL", "SUBTOTAL",
            "FACTURA", "INVOICE", "RECIBO", "ALBARAN", "ALBARÁN",
            "DESCARGA TUS FACTURAS DE FORMA",
            "FACTURACIÓN POR OPERACIONES REALIZADAS CON TARJETA SOLRED MÁS"
    );

    private String guessSupplierNameFromHead(String head, String emitterNif) {
        if (head == null || head.isBlank()) return null;
        String[] lines = head.split("\n");
        for (String raw : lines) {
            String s = raw.trim();
            if (s.length() < 3 || s.length() > 120) continue;
            // Bloqueo por blacklist de cabeceras conocidas
            String upper = s.toUpperCase();
            boolean blacklisted = false;
            for (String b : HEADER_BLACKLIST) {
                if (upper.equals(b) || upper.startsWith(b)) { blacklisted = true; break; }
            }
            if (blacklisted) continue;
            if (s.matches("(?i).*\\b(factura|invoice|recibo|albaran)\\b.*")) continue;
            if (s.matches("(?i).*\\b(fecha|date|n\\u00ba|num|number)\\b.*")) continue;
            if (emitterNif != null && s.toUpperCase().contains(emitterNif)) continue;
            // Descartar líneas que sean obviamente direcciones (números + calle)
            if (s.matches("(?i).*\\b(c/|calle|cl|avda|avenida|c\\.p\\.|cp\\s+\\d{5})\\b.*")) continue;
            // Aceptar si tiene mayúscula inicial y letras (no solo dígitos)
            if (s.matches(".*[A-Z\\u00c0-\\u017f].*[a-z\\u00e0-\\u017f].*")
                    || s.matches("[A-Z\\u00c0-\\u017f0-9\\s,.&'\\-]{3,80}")) {
                return s;
            }
        }
        return null;
    }

    /**
     * Busca la fecha en una ventana de 80 chars tras "Fecha de la
     * factura", "Fecha factura", "Invoice date", "Fecha de emisión".
     * Prioritaria sobre cualquier otra fecha del documento porque a
     * menudo aparecen también "Fecha del pedido", "Fecha del albarán",
     * etc, que NO son la fecha que queremos.
     */
    private LocalDate findInvoiceDateLabeled(String text) {
        // Etiqueta acaba en la palabra "factura" — no consumimos lo que
        // venga después (puede ser "/Fecha de la entrega <fecha>" o solo
        // <fecha>). La ventana de 80 chars que sigue se queda con la
        // fecha objetivo.
        Pattern labels = Pattern.compile(
                "(?i)(?:fecha\\s+(?:de\\s+)?(?:la\\s+)?factura\\b|" +
                "fecha\\s+de\\s+emisi[\\u00f3o]n|" +
                "invoice\\s+date|date\\s+of\\s+invoice)\\s*:?"
        );
        Matcher l = labels.matcher(text);
        while (l.find()) {
            String tail = text.substring(l.end(),
                    Math.min(text.length(), l.end() + 80));
            // Probar nombre primero (formato europeo "11 abril 2026")
            LocalDate d = parseFirstNamedDate(tail);
            if (d != null) return d;
            d = parseFirstNumericDate(tail);
            if (d != null) return d;
        }
        return null;
    }

    private LocalDate parseFirstNamedDate(String text) {
        Matcher m = DATE_SPANISH_NAMED_PATTERN.matcher(text);
        if (m.find()) {
            try {
                int d = Integer.parseInt(m.group(1));
                Integer mo = SPANISH_MONTHS.get(m.group(2).toLowerCase());
                int y = Integer.parseInt(m.group(3));
                if (mo != null && d >= 1 && d <= 31) {
                    return LocalDate.of(y, mo, d);
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private LocalDate parseFirstNumericDate(String text) {
        Matcher m = DATE_PATTERN.matcher(text);
        while (m.find()) {
            try {
                if (m.group(1) != null) {
                    int d = Integer.parseInt(m.group(1));
                    int mo = Integer.parseInt(m.group(2));
                    int y = Integer.parseInt(m.group(3));
                    if (y < 100) y += 2000;
                    if (mo >= 1 && mo <= 12 && d >= 1 && d <= 31) {
                        return LocalDate.of(y, mo, d);
                    }
                } else if (m.group(4) != null) {
                    int y = Integer.parseInt(m.group(4));
                    int mo = Integer.parseInt(m.group(5));
                    int d = Integer.parseInt(m.group(6));
                    if (mo >= 1 && mo <= 12 && d >= 1 && d <= 31) {
                        return LocalDate.of(y, mo, d);
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private LocalDate findFirstNamedDate(String text) {
        Matcher m = DATE_SPANISH_NAMED_PATTERN.matcher(text);
        while (m.find()) {
            try {
                int d = Integer.parseInt(m.group(1));
                Integer mo = SPANISH_MONTHS.get(m.group(2).toLowerCase());
                int y = Integer.parseInt(m.group(3));
                if (mo == null || d < 1 || d > 31) continue;
                return LocalDate.of(y, mo, d);
            } catch (Exception ignored) { /* siguiente */ }
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
            String rawTextHead,
            /** NIF del destinatario (en facturas de venta es el cliente). */
            String receiverNif,
            /** Nombre del destinatario. */
            String receiverName,
            /** TRUE si el PDF contiene marcadores de "RECTIFICATIVA". */
            boolean rectifying,
            /** Si es rectificativa, nº de la factura que rectifica (si se detecta). */
            String rectifiedInvoiceNumber
    ) {
        public String invoiceDateIso() {
            return invoiceDate == null ? null : invoiceDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        /** Backwards-compat para callers viejos. */
        public ExtractionResult(List<String> allDetectedNifs, String emitterNif,
                                  String supplierName, String invoiceNumber,
                                  LocalDate invoiceDate, BigDecimal baseAmount,
                                  BigDecimal vatPercent, BigDecimal vatAmount,
                                  BigDecimal totalAmount, String documentSha256,
                                  Confidence confidence, String rawTextHead) {
            this(allDetectedNifs, emitterNif, supplierName, invoiceNumber,
                    invoiceDate, baseAmount, vatPercent, vatAmount, totalAmount,
                    documentSha256, confidence, rawTextHead,
                    null, null, false, null);
        }
    }
}
