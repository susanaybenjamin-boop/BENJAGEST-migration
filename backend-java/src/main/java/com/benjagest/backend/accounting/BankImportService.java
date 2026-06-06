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

        // Auto-conciliación: best-effort sobre los nuevos movimientos.
        int autoMatched = autoReconcileBatch(batchId);
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

    private List<ParsedRow> parseN43(String content) {
        List<ParsedRow> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new StringReader(content))) {
            String line;
            ParsedRow current = null;
            while ((line = br.readLine()) != null) {
                if (line.length() < 2) continue;
                String code = line.substring(0, 2);
                switch (code) {
                    case "22" -> {
                        if (line.length() < 80) continue;
                        current = new ParsedRow();
                        // bytes 11-16: fecha operación (yyMMdd)
                        // bytes 17-22: fecha valor (yyMMdd)
                        // bytes 23-25: clave operación
                        // bytes 28-40: importe (sin punto, dos decimales)
                        // bytes 41-50: ref propia
                        // bytes 51-62: ref entidad
                        try {
                            current.operationDate = LocalDate.parse(line.substring(10, 16), N43_DATE);
                        } catch (Exception ex) { current.operationDate = LocalDate.now(); }
                        try {
                            current.valueDate = LocalDate.parse(line.substring(16, 22), N43_DATE);
                        } catch (Exception ex) { /* opcional */ }
                        String signChar = line.substring(27, 28);
                        String amountStr = line.substring(28, 40).trim();
                        BigDecimal amt;
                        try {
                            amt = new BigDecimal(amountStr).movePointLeft(2);
                        } catch (Exception ex) { continue; }
                        if ("1".equals(signChar)) amt = amt.negate();
                        current.amount = amt;
                        current.externalRef = line.length() >= 62
                                ? line.substring(40, 62).trim() : "";
                        out.add(current);
                    }
                    case "23" -> {
                        if (current != null && line.length() > 5) {
                            String extra = line.substring(5).trim();
                            current.description = (current.description == null ? "" : current.description + " ") + extra;
                            // Heurística: si hay NIF español al final, lo extraemos.
                            String maybeNif = extractSpanishNif(extra);
                            if (maybeNif != null && current.counterpartyNif == null) {
                                current.counterpartyNif = maybeNif;
                            }
                        }
                    }
                    default -> {
                        /* 11, 33, 88, 00 → no mov */
                    }
                }
            }
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Error al parsear N43: " + ex.getMessage());
        }
        return out;
    }

    // ====================================================================
    //  Parser CSV (formato homogéneo: fecha;valor;concepto;importe;saldo)
    // ====================================================================

    private List<ParsedRow> parseCsv(String content) {
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

    private LocalDate parseDate(String s) {
        s = s.strip();
        try { return LocalDate.parse(s, ISO); } catch (Exception ignored) {}
        try { return LocalDate.parse(s, ES); } catch (Exception ignored) {}
        try { return LocalDate.parse(s, DateTimeFormatter.ofPattern("d/M/yyyy")); } catch (Exception ignored) {}
        throw new IllegalArgumentException("Fecha no parseable: " + s);
    }

    private BigDecimal parseNumber(String s) {
        s = s.strip().replace(".", "").replace(",", ".").replace("€", "").replace(" ", "");
        return new BigDecimal(s);
    }

    private static final java.util.regex.Pattern NIF_PATTERN = java.util.regex.Pattern.compile(
            "\\b([A-HJNP-SUVW]\\d{8}|\\d{8}[A-Z]|[XYZ]\\d{7}[A-Z])\\b");

    private String extractSpanishNif(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = NIF_PATTERN.matcher(text.toUpperCase());
        return m.find() ? m.group(1) : null;
    }

    private String extractCounterparty(String description) {
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
            String bankAccountId, String format, String fileName, String content
    ) {}

    public record BatchResult(
            String batchId, int rowsTotal, int rowsImported,
            int rowsSkipped, int rowsAutoMatched
    ) {}

    private static class ParsedRow {
        LocalDate operationDate; LocalDate valueDate;
        String description; String counterpartyName; String counterpartyNif;
        BigDecimal amount; BigDecimal balanceAfter;
        String externalRef;
    }
}
