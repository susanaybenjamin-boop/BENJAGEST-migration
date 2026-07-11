package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.io.BufferedReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Importador de extracto bancario.
 *
 * <p>Formatos soportados:
 * <ul>
 *   <li><b>N43</b>: cuaderno 43 AEB español. Línea de 11 (cuenta) + 22
 *       (movimiento) + 23 (concepto adicional opcional, 1 a 5 líneas) +
 *       33 (final cuenta) + 88 (final archivo).</li>
 *   <li><b>CSV</b>: fecha;valor;concepto;importe;saldo (Caixabank/BBVA/etc.
 *       homogeneizado).</li>
 * </ul>
 *
 * <p>Idempotencia: el INSERT respeta el UK
 * (company_id, bank_account_id, operation_date, amount, external_ref).
 * Las filas ya importadas se cuentan como "skipped" en el batch.
 *
 * <p>Auto-conciliación: tras importar, intenta matchear automáticamente
 * con facturas por importe+fecha+counterparty. Si encuentra match único,
 * crea el asiento (delega en {@link BankMovementService#linkToInvoice}).
 */
@Service
public class BankImportService {

    private static final DateTimeFormatter N43_DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter ES = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final BankMovementService bankMovements;
    private final CurrentUserService currentUserService;

    public BankImportService(JdbcTemplate jdbcTemplate, TenantContext tenantContext,
                               BankMovementService bankMovements,
                               CurrentUserService currentUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.bankMovements = bankMovements;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public BatchResult importContent(ImportRequest req) {
        if (req.content() == null || req.content().isBlank()) {
            throw bad("Contenido vacío.");
        }
        if (req.bankAccountId() == null) throw bad("Falta cuenta bancaria.");
        if (req.format() == null) throw bad("Falta formato.");

        String companyId = tenantContext.getCurrentCompanyId();
        String batchId = UUID.randomUUID().toString();
        String userId = safeUserId();

        List<ParsedRow> parsed = switch (req.format().toUpperCase()) {
            case "N43" -> parseN43(req.content());
            case "CSV" -> parseCsv(req.content());
            // F1-BANCO: extracto Excel (BBVA no da N43). El binario viaja en
            // Base64 dentro de content.
            case "XLSX" -> parseXlsxBank(decodeBase64(req.content()));
            default -> throw bad("Formato no soportado: " + req.format());
        };

        int imported = 0;
        int skipped = 0;
        LocalDate periodFrom = null;
        LocalDate periodTo = null;

        for (ParsedRow row : parsed) {
            try {
                String id = UUID.randomUUID().toString();
                jdbcTemplate.update("""
                        INSERT INTO bank_movements (
                            id, company_id, bank_account_id,
                            operation_date, value_date,
                            description, counterparty_name, counterparty_nif,
                            amount, balance_after, external_ref,
                            import_batch_id, status
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNRECONCILED')
                        """,
                        id, companyId, req.bankAccountId(),
                        Date.valueOf(row.operationDate),
                        row.valueDate == null ? null : Date.valueOf(row.valueDate),
                        truncate(row.description, 240),
                        truncate(row.counterpartyName, 180),
                        row.counterpartyNif,
                        row.amount, row.balanceAfter,
                        row.externalRef, batchId);
                imported++;
                if (periodFrom == null || row.operationDate.isBefore(periodFrom)) periodFrom = row.operationDate;
                if (periodTo == null || row.operationDate.isAfter(periodTo)) periodTo = row.operationDate;
            } catch (DuplicateKeyException dup) {
                skipped++;
            }
        }

        jdbcTemplate.update("""
                INSERT INTO bank_import_batches (
                    id, company_id, bank_account_id, source_format, file_name,
                    rows_total, rows_imported, rows_skipped, rows_auto_matched,
                    period_from, period_to, imported_by_user_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                """,
                batchId, companyId, req.bankAccountId(),
                req.format().toUpperCase(), truncate(req.fileName(), 240),
                parsed.size(), imported, skipped,
                periodFrom == null ? null : Date.valueOf(periodFrom),
                periodTo == null ? null : Date.valueOf(periodTo),
                userId);

        // Auto-conciliación: solo si se pide explícitamente. Por defecto los
        // movimientos quedan UNRECONCILED y se concilian desde la pantalla de
        // revisión (F1-BANCO-REVIEW), donde el usuario ve el estado de cada
        // factura y su pago existente antes de generar ningún asiento.
        int autoMatched = Boolean.TRUE.equals(req.autoReconcile())
                ? autoReconcileBatch(batchId) : 0;
        if (autoMatched > 0) {
            jdbcTemplate.update("""
                    UPDATE bank_import_batches SET rows_auto_matched = ?
                     WHERE id = ?
                    """, autoMatched, batchId);
        }
        return new BatchResult(batchId, parsed.size(), imported, skipped, autoMatched);
    }

    /**
     * Para cada movimiento UNRECONCILED del batch, busca match único y si
     * existe, llama a linkToInvoice. Si hay más de uno (ambiguo) lo deja
     * UNRECONCILED para revisión manual.
     */
    private int autoReconcileBatch(String batchId) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM bank_movements
                 WHERE company_id = ? AND import_batch_id = ?
                   AND status = 'UNRECONCILED'
                """, (rs, n) -> rs.getString("id"),
                tenantContext.getCurrentCompanyId(), batchId);
        int matched = 0;
        for (String movId : ids) {
            try {
                List<BankMovementService.MatchCandidate> candidates =
                        bankMovements.suggestMatches(movId);
                if (candidates.size() == 1) {
                    BankMovementService.MatchCandidate c = candidates.get(0);
                    bankMovements.linkToInvoice(movId, new BankMovementService.LinkRequest(
                            c.invoiceKind(), c.invoiceId(), null));
                    matched++;
                }
            } catch (Exception ex) {
                // Si falla (fecha en periodo cerrado, etc.), seguimos.
            }
        }
        return matched;
    }

    // ====================================================================
    //  Parser N43
    // ====================================================================

    /**
     * F1-N43 (2026-07-10) — parser del Cuaderno 43 AEB, ESTÁTICO y testeable.
     * Corregido contra la norma (registro 22, posiciones 1-based):
     * 11-16 fecha operación · 17-22 fecha valor · 23-24 concepto común ·
     * 25-27 concepto propio · 28 clave debe(1)/haber(2) · <b>29-42 importe
     * (14 dígitos, 2 decimales)</b> · 43-52 nº documento · 53-64 referencia 1 ·
     * 65-80 referencia 2. El parser anterior cortaba el importe en 12 dígitos
     * (substring 28,40): perdía los CÉNTIMOS y dividía el importe entre 100.
     * En el registro 23 el concepto empieza en la posición 5 (índice 4); antes
     * se comía el primer carácter.
     *
     * <p>Integridad: si el fichero trae registros 33 (final de cuenta), se
     * valida que la suma de movimientos cuadre con los totales declarados
     * (nº de apuntes e importes al debe y al haber). Si no cuadra → 400,
     * mejor rechazar que importar cifras malas.
     *
     * <p>N43-SPEC (2026-07-10, contrastado con el importador OCA l10n-spain,
     * referencia en producción): posiciones de los registros 22/23/33
     * CONFIRMADAS idénticas. Endurecimientos añadidos de su experiencia real:
     * (1) BOM UTF-8 al inicio (desplaza todas las posiciones de la 1ª línea);
     * (2) carácter EOF 0x1A (Ctrl-Z, convención DOS) al final; (3) registro 88
     * — nº total de registros en posiciones 21-26 (tras 18 nueves), validado
     * con tolerancia ±1 (hay bancos que cuentan el propio 88 y otros no).
     */
    static List<ParsedRow> parseN43(String content) {
        List<ParsedRow> out = new ArrayList<>();
        int count33Debe = 0, count33Haber = 0;
        BigDecimal total33Debe = BigDecimal.ZERO, total33Haber = BigDecimal.ZERO;
        boolean saw33 = false;
        int recordCount = 0;
        Integer declared88 = null;
        // BOM UTF-8: algunos bancos lo emiten y desplaza las posiciones fijas.
        if (content.startsWith("﻿")) content = content.substring(1);
        try (BufferedReader br = new BufferedReader(new StringReader(content))) {
            String line;
            ParsedRow current = null;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == 0x1A) continue; // EOF DOS (^Z)
                if (line.length() < 2) continue;
                recordCount++;
                String code = line.substring(0, 2);
                switch (code) {
                    case "22" -> {
                        if (line.length() < 80) continue;
                        current = new ParsedRow();
                        try {
                            current.operationDate = LocalDate.parse(line.substring(10, 16), N43_DATE);
                        } catch (Exception ex) { current.operationDate = LocalDate.now(); }
                        try {
                            current.valueDate = LocalDate.parse(line.substring(16, 22), N43_DATE);
                        } catch (Exception ex) { /* opcional */ }
                        String signChar = line.substring(27, 28);
                        String amountStr = line.substring(28, 42).trim();
                        BigDecimal amt;
                        try {
                            amt = new BigDecimal(amountStr).movePointLeft(2);
                        } catch (Exception ex) { continue; }
                        if ("1".equals(signChar)) amt = amt.negate();
                        current.amount = amt;
                        // nº documento (43-52) + referencia 1 (53-64) como ref
                        // externa para la idempotencia del import.
                        current.externalRef = line.substring(42, Math.min(64, line.length())).trim();
                        // Referencia 2 (65-80): muchos bancos ponen ahí el texto
                        // del ordenante; sirve de descripción si no llegan 23s.
                        if (line.length() > 64) {
                            String ref2 = line.substring(64).trim();
                            if (!ref2.isEmpty()) current.description = ref2;
                        }
                        out.add(current);
                    }
                    case "23" -> {
                        // 3-4 código de dato; el concepto empieza en la pos. 5.
                        if (current != null && line.length() > 4) {
                            String extra = line.substring(4).trim();
                            current.description = (current.description == null ? "" : current.description + " ") + extra;
                            // Heurística: si hay NIF español al final, lo extraemos.
                            String maybeNif = extractSpanishNif(extra);
                            if (maybeNif != null && current.counterpartyNif == null) {
                                current.counterpartyNif = maybeNif;
                            }
                        }
                    }
                    case "33" -> {
                        // 21-25 nº apuntes debe · 26-39 total debe ·
                        // 40-44 nº apuntes haber · 45-58 total haber.
                        if (line.length() < 58) continue;
                        try {
                            count33Debe += Integer.parseInt(line.substring(20, 25).trim());
                            total33Debe = total33Debe.add(
                                    new BigDecimal(line.substring(25, 39).trim()).movePointLeft(2));
                            count33Haber += Integer.parseInt(line.substring(39, 44).trim());
                            total33Haber = total33Haber.add(
                                    new BigDecimal(line.substring(44, 58).trim()).movePointLeft(2));
                            saw33 = true;
                        } catch (NumberFormatException ex) { /* 33 malformado: sin validación */ }
                    }
                    case "88" -> {
                        // Fin de fichero: 3-20 nueves (18) · 21-26 nº total de
                        // registros. Se valida al final con tolerancia ±1.
                        if (line.length() >= 26) {
                            try {
                                declared88 = Integer.parseInt(line.substring(20, 26).trim());
                            } catch (NumberFormatException ex) { /* sin validación */ }
                        }
                    }
                    default -> {
                        /* 11, 24, 00 → no mov */
                    }
                }
            }
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Error al parsear N43: " + ex.getMessage());
        }
        for (ParsedRow r : out) {
            if (r.counterpartyName == null) r.counterpartyName = extractCounterparty(r.description);
        }
        if (saw33) {
            long debe = out.stream().filter(r -> r.amount.signum() < 0).count();
            long haber = out.size() - debe;
            BigDecimal sumDebe = out.stream().map(r -> r.amount).filter(a -> a.signum() < 0)
                    .map(BigDecimal::negate).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sumHaber = out.stream().map(r -> r.amount).filter(a -> a.signum() >= 0)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (debe != count33Debe || haber != count33Haber
                    || sumDebe.compareTo(total33Debe) != 0 || sumHaber.compareTo(total33Haber) != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El fichero N43 no cuadra con sus totales (registro 33): "
                        + "movimientos " + debe + "D/" + haber + "H = " + sumDebe + "/" + sumHaber
                        + " vs declarados " + count33Debe + "D/" + count33Haber + "H = "
                        + total33Debe + "/" + total33Haber + ". Fichero rechazado.");
            }
        }
        // Registro 88: nº total de registros con tolerancia ±1 (algunos bancos
        // cuentan el propio 88 y otros no — criterio del importador OCA).
        if (declared88 != null && Math.abs(declared88 - recordCount) > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El fichero N43 declara " + declared88 + " registros (registro 88) pero contiene "
                    + recordCount + ". Fichero rechazado.");
        }
        return out;
    }

    // ====================================================================
    //  Parser XLSX (extracto Excel — layout BBVA y compatibles)
    // ====================================================================

    /**
     * F1-BANCO (2026-07-10) — Extracto .xlsx de banca online. Contrastado con
     * el export REAL de BBVA ("Últimos movimientos"): filas de título antes de
     * la cabecera, cabecera con columnas
     * {@code F.Valor | Fecha | Concepto | Movimiento | Importe | Divisa |
     * Disponible | Divisa | Observaciones}, fechas dd/MM/yyyy como texto e
     * importes como número con punto decimal. El parser localiza la cabecera
     * por NOMBRE de columna (tolera columnas movidas o extra) y por eso vale
     * también para otros bancos con export equivalente.
     *
     * <p>Idempotencia: sin nº de documento, dos movimientos idénticos el mismo
     * día son legítimos (p.ej. dos pagos iguales de tarjeta) — la referencia
     * externa se construye con el SALDO POSTERIOR ("SALDO:…"), que los
     * distingue y es estable si se reimporta el mismo fichero.
     */
    static List<ParsedRow> parseXlsxBank(byte[] xlsx) {
        List<String[]> rows;
        try {
            rows = XlsxLite.rows(xlsx);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudo leer el .xlsx: " + ex.getMessage());
        }
        // Cabecera: primera fila con una columna "importe" y alguna de fecha.
        int headerIdx = -1;
        java.util.Map<String, Integer> col = new java.util.HashMap<>();
        for (int i = 0; i < rows.size() && headerIdx < 0; i++) {
            java.util.Map<String, Integer> m = new java.util.HashMap<>();
            String[] r = rows.get(i);
            for (int j = 0; j < r.length; j++) {
                String k = normalizeHeader(r[j]);
                if (!k.isEmpty()) m.putIfAbsent(k, j);
            }
            if (m.containsKey("importe") && (m.containsKey("fecha") || m.containsKey("fvalor"))) {
                headerIdx = i;
                col = m;
            }
        }
        if (headerIdx < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El .xlsx no tiene una cabecera reconocible (se esperan columnas "
                    + "tipo F.Valor/Fecha/Concepto/Importe/Disponible, como el export de BBVA).");
        }
        Integer cFecha = col.getOrDefault("fecha", col.get("fvalor"));
        Integer cValor = col.getOrDefault("fvalor", cFecha);
        Integer cImporte = col.get("importe");
        Integer cConcepto = col.get("concepto");
        Integer cMovimiento = col.get("movimiento");
        Integer cDisponible = col.get("disponible");
        Integer cObs = col.get("observaciones");

        List<ParsedRow> out = new ArrayList<>();
        for (int i = headerIdx + 1; i < rows.size(); i++) {
            String[] r = rows.get(i);
            String importe = at(r, cImporte);
            String fecha = at(r, cFecha);
            if (importe.isBlank() || fecha.isBlank()) continue;
            ParsedRow p = new ParsedRow();
            try {
                p.operationDate = parseDate(fecha);
            } catch (RuntimeException ex) { continue; /* fila de pie/resumen */ }
            try {
                p.valueDate = parseDate(at(r, cValor));
            } catch (RuntimeException ex) { p.valueDate = p.operationDate; }
            try {
                p.amount = parseCellNumber(importe);
            } catch (RuntimeException ex) { continue; }
            String disponible = at(r, cDisponible);
            try {
                if (!disponible.isBlank()) p.balanceAfter = parseCellNumber(disponible);
            } catch (RuntimeException ex) { /* opcional */ }
            String concepto = at(r, cConcepto).trim();
            String movimiento = at(r, cMovimiento).trim();
            String obs = at(r, cObs).trim();
            String desc = (concepto + (movimiento.isEmpty() ? "" : " · " + movimiento)).trim();
            p.description = desc.isEmpty() ? obs : desc;
            p.counterpartyName = extractCounterparty(
                    (concepto + " " + movimiento).trim().isEmpty() ? obs : concepto + " " + movimiento);
            p.counterpartyNif = extractSpanishNif(obs.isEmpty() ? desc : obs);
            p.externalRef = p.balanceAfter != null
                    ? "SALDO:" + p.balanceAfter.toPlainString() : "";
            out.add(p);
        }
        return out;
    }

    /** "F.Valor " → "fvalor", "Observaciones" → "observaciones". */
    private static String normalizeHeader(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-záéíóúñ]", "")
                .replace("á", "a").replace("é", "e").replace("í", "i")
                .replace("ó", "o").replace("ú", "u");
    }

    private static String at(String[] row, Integer idx) {
        return idx == null || idx >= row.length || row[idx] == null ? "" : row[idx];
    }

    /** Número de celda xlsx: nativo con punto decimal ("-60.47") o texto en
     *  formato español ("1.234,56"). */
    private static BigDecimal parseCellNumber(String s) {
        s = s.strip();
        if (s.matches("-?\\d+(\\.\\d+)?([eE]-?\\d+)?")) return new BigDecimal(s);
        return parseNumber(s);
    }

    private static byte[] decodeBase64(String content) {
        try {
            return java.util.Base64.getDecoder().decode(
                    content.replaceAll("\\s", ""));
        } catch (IllegalArgumentException ex) {
            throw bad("El contenido XLSX debe venir en Base64.");
        }
    }

    // ====================================================================
    //  Parser CSV (formato homogéneo: fecha;valor;concepto;importe;saldo)
    // ====================================================================

    static List<ParsedRow> parseCsv(String content) {
        List<ParsedRow> out = new ArrayList<>();
        boolean firstLine = true;
        try (BufferedReader br = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                String sep = line.contains(";") ? ";" : ",";
                String[] parts = line.split(java.util.regex.Pattern.quote(sep), -1);
                if (parts.length < 4) { firstLine = false; continue; }
                // Detectar cabecera por valores no numéricos en columna importe.
                if (firstLine) {
                    firstLine = false;
                    try { parseNumber(parts[3]); }
                    catch (Exception ex) { continue; /* cabecera */ }
                }
                ParsedRow r = new ParsedRow();
                r.operationDate = parseDate(parts[0]);
                try { r.valueDate = parseDate(parts[1]); } catch (Exception ex) { r.valueDate = r.operationDate; }
                r.description = parts[2];
                r.amount = parseNumber(parts[3]);
                if (parts.length > 4) {
                    try { r.balanceAfter = parseNumber(parts[4]); } catch (Exception ex) { /* opcional */ }
                }
                r.counterpartyName = extractCounterparty(r.description);
                r.counterpartyNif = extractSpanishNif(r.description);
                r.externalRef = "";
                out.add(r);
            }
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Error al parsear CSV: " + ex.getMessage());
        }
        return out;
    }

    private static LocalDate parseDate(String s) {
        s = s.strip();
        try { return LocalDate.parse(s, ISO); } catch (Exception ignored) {}
        try { return LocalDate.parse(s, ES); } catch (Exception ignored) {}
        try { return LocalDate.parse(s, DateTimeFormatter.ofPattern("d/M/yyyy")); } catch (Exception ignored) {}
        throw new IllegalArgumentException("Fecha no parseable: " + s);
    }

    private static BigDecimal parseNumber(String s) {
        s = s.strip().replace(".", "").replace(",", ".").replace("€", "").replace(" ", "");
        return new BigDecimal(s);
    }

    private static final java.util.regex.Pattern NIF_PATTERN = java.util.regex.Pattern.compile(
            "\\b([A-HJNP-SUVW]\\d{8}|\\d{8}[A-Z]|[XYZ]\\d{7}[A-Z])\\b");

    // F1-BANCO: los extractos bancarios formatean el DNI con puntos y guion
    // ("74.668.351-R"); se normaliza a 8 dígitos + letra.
    private static final java.util.regex.Pattern NIF_FORMATTED_PATTERN = java.util.regex.Pattern.compile(
            "\\b(\\d{2})\\.(\\d{3})\\.(\\d{3})-?([A-Z])\\b");

    private static String extractSpanishNif(String text) {
        if (text == null) return null;
        String upper = text.toUpperCase();
        java.util.regex.Matcher m = NIF_PATTERN.matcher(upper);
        if (m.find()) return m.group(1);
        java.util.regex.Matcher f = NIF_FORMATTED_PATTERN.matcher(upper);
        if (f.find()) return f.group(1) + f.group(2) + f.group(3) + f.group(4);
        return null;
    }

    private static String extractCounterparty(String description) {
        if (description == null) return null;
        String s = description.replaceAll("(?i)(transf|recibo|tarjeta|nomina|impuesto|comision|cobro|pago|de:|para:)\\s*", "")
                .trim();
        if (s.length() > 60) s = s.substring(0, 60);
        return s.isBlank() ? null : s;
    }

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // ====================================================================
    //  DTOs
    // ====================================================================

    public record ImportRequest(
            String bankAccountId, String format, String fileName, String content,
            // F1-BANCO-REVIEW: por defecto NO se auto-postea al importar. La
            // conciliación pasa por la pantalla de revisión (checkbox + estado),
            // que evita duplicar pagos ya contabilizados. true = comportamiento
            // antiguo (auto-conciliar match único) para compatibilidad/API.
            Boolean autoReconcile
    ) {}

    public record BatchResult(
            String batchId, int rowsTotal, int rowsImported,
            int rowsSkipped, int rowsAutoMatched
    ) {}

    // F1-N43: visibilidad de package para poder fijar el parser en tests.
    static class ParsedRow {
        LocalDate operationDate; LocalDate valueDate;
        String description; String counterpartyName; String counterpartyNif;
        BigDecimal amount; BigDecimal balanceAfter;
        String externalRef;
    }
}
