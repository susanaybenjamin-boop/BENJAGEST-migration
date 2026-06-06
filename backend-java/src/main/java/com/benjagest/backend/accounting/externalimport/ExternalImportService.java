package com.benjagest.backend.accounting.externalimport;

import com.benjagest.backend.accounting.ManualJournalEntryService;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.io.BufferedReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Importación desde otros programas de gestión.
 *
 * <p>Formatos:
 * <ul>
 *   <li><b>CSV</b> genérico con cabecera nombrada. Es el formato más
 *       común porque cualquier ERP lo exporta. Para cada {@code target_kind}
 *       hay un mapeo esperado de columnas (documentado en cada importador).</li>
 *   <li><b>CONTASOL</b> formato TXT: cuentas con líneas fijas + diario con
 *       líneas fijas. Solo formato moderno (Contasol 2020+).</li>
 *   <li><b>JSON_BENJAGEST</b>: nuestro propio formato canónico (útil para
 *       migrar entre instalaciones BenjaGest y para restaurar desde EXPORT).</li>
 *   <li><b>A3 / SAGE / XML_ESPI</b>: documentados como esqueleto, pendientes
 *       de muestras reales del cliente para finalizar parser.</li>
 * </ul>
 *
 * <p>Targets soportados:
 * <ul>
 *   <li>{@code ACCOUNTS} — añade cuentas al PGC de la empresa.</li>
 *   <li>{@code JOURNAL_ENTRIES} — crea asientos. Usa
 *       {@link ManualJournalEntryService} para que pasen las mismas
 *       validaciones (balance, fiscal guard, etc.).</li>
 *   <li>{@code CUSTOMERS} y {@code SUPPLIERS} — añaden a tablas
 *       correspondientes.</li>
 * </ul>
 */
@Service
public class ExternalImportService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter ES = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;
    private final ManualJournalEntryService manualEntries;

    public ExternalImportService(JdbcTemplate jdbcTemplate, TenantContext tenantContext,
                                   CurrentUserService currentUserService,
                                   ManualJournalEntryService manualEntries) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.manualEntries = manualEntries;
    }

    @Transactional
    public BatchResult importData(ImportRequest req) {
        if (req.content() == null || req.content().isBlank()) throw bad("Contenido vacío.");
        if (req.format() == null) throw bad("Formato obligatorio.");
        if (req.targetKind() == null) throw bad("Target obligatorio.");

        String batchId = UUID.randomUUID().toString();
        String userId = safeUserId();
        StringBuilder errors = new StringBuilder();

        ImportTally tally = switch (req.format().toUpperCase()) {
            case "CSV" -> importCsv(req, errors);
            case "CONTASOL" -> importContasol(req, errors);
            case "JSON_BENJAGEST" -> importJsonBenjagest(req, errors);
            case "A3", "SAGE", "XML_ESPI" ->
                    throw bad("Formato " + req.format() + " todavía no implementado. "
                            + "Envía un fichero de muestra para terminar el parser.");
            default -> throw bad("Formato desconocido: " + req.format());
        };

        jdbcTemplate.update("""
                INSERT INTO external_import_batches (
                    id, company_id, source_format, target_kind, file_name,
                    file_size_bytes, rows_total, rows_imported, rows_skipped,
                    rows_errors, error_log, imported_by_user_id, notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                batchId, tenantContext.getCurrentCompanyId(),
                req.format().toUpperCase(), req.targetKind().toUpperCase(),
                truncate(req.fileName(), 240),
                req.content().getBytes().length,
                tally.total, tally.imported, tally.skipped, tally.errors,
                errors.length() == 0 ? null : truncate(errors.toString(), 4000),
                userId, blank(req.notes()));
        return new BatchResult(batchId, tally.total, tally.imported,
                tally.skipped, tally.errors,
                errors.length() == 0 ? null : errors.toString());
    }

    // ====================================================================
    //  CSV genérico
    // ====================================================================

    private ImportTally importCsv(ImportRequest req, StringBuilder errors) {
        return switch (req.targetKind().toUpperCase()) {
            case "ACCOUNTS" -> importCsvAccounts(req.content(), errors);
            case "JOURNAL_ENTRIES" -> importCsvJournalEntries(req.content(), errors);
            case "CUSTOMERS" -> importCsvParties(req.content(), errors, false);
            case "SUPPLIERS" -> importCsvParties(req.content(), errors, true);
            default -> throw bad("CSV no implementado para target " + req.targetKind());
        };
    }

    /** CSV cuentas. Cabecera esperada: code;name;account_type. */
    private ImportTally importCsvAccounts(String content, StringBuilder errors) {
        ImportTally t = new ImportTally();
        List<Map<String, String>> rows = parseCsvWithHeader(content);
        String companyId = tenantContext.getCurrentCompanyId();
        for (Map<String, String> row : rows) {
            t.total++;
            String code = trim(row.get("code"));
            String name = trim(row.get("name"));
            String type = trim(row.getOrDefault("account_type", "OTHER"));
            if (code == null || name == null) {
                t.errors++;
                appendErr(errors, "Fila sin code/name: " + row);
                continue;
            }
            try {
                int updated = jdbcTemplate.update("""
                        INSERT IGNORE INTO accounting_accounts (
                            id, company_id, code, name, account_type, active
                        ) VALUES (?, ?, ?, ?, ?, TRUE)
                        """,
                        UUID.randomUUID().toString(), companyId, code, name, type);
                if (updated > 0) t.imported++;
                else t.skipped++;
            } catch (DataAccessException ex) {
                t.errors++;
                appendErr(errors, "Code " + code + ": " + ex.getMessage());
            }
        }
        return t;
    }

    /**
     * CSV asientos. Cada línea es UN apunte. Se agrupa por entry_number
     * (o por (date+concept+seq) si no hay número). Cabecera esperada:
     *
     * <pre>entry_number;date;concept;account_code;description;debit;credit</pre>
     *
     * El servicio valida que cada asiento esté equilibrado tras la
     * agrupación. Los que no cuadran van al error_log.
     */
    private ImportTally importCsvJournalEntries(String content, StringBuilder errors) {
        ImportTally t = new ImportTally();
        List<Map<String, String>> rows = parseCsvWithHeader(content);
        // Agrupar.
        Map<String, EntryGroup> grouped = new java.util.LinkedHashMap<>();
        int seq = 0;
        for (Map<String, String> row : rows) {
            String num = trim(row.get("entry_number"));
            String date = trim(row.get("date"));
            String concept = trim(row.get("concept"));
            String key = num != null ? num : (date + "|" + concept + "|" + (++seq / 2));
            grouped.computeIfAbsent(key, k -> new EntryGroup(date, concept))
                    .lines.add(row);
        }

        for (Map.Entry<String, EntryGroup> e : grouped.entrySet()) {
            t.total++;
            EntryGroup g = e.getValue();
            try {
                LocalDate entryDate = parseDate(g.date);
                List<ManualJournalEntryService.LineRequest> lines = new ArrayList<>();
                for (Map<String, String> r : g.lines) {
                    String code = trim(r.get("account_code"));
                    String accountId = resolveAccount(code);
                    if (accountId == null) throw new IllegalStateException("Cuenta no encontrada: " + code);
                    BigDecimal debit = parseAmount(r.get("debit"));
                    BigDecimal credit = parseAmount(r.get("credit"));
                    lines.add(new ManualJournalEntryService.LineRequest(
                            accountId, trim(r.get("description")), debit, credit));
                }
                if (lines.size() < 2) throw new IllegalStateException("Mínimo 2 líneas");
                manualEntries.createDraft(new ManualJournalEntryService.ManualEntryRequest(
                        entryDate, g.concept == null ? "Importado" : g.concept,
                        lines, false));
                t.imported++;
            } catch (Exception ex) {
                t.errors++;
                appendErr(errors, "Asiento " + e.getKey() + ": " + ex.getMessage());
            }
        }
        return t;
    }

    /** CSV clientes/proveedores. Cabecera: nif;name;email;phone;address. */
    private ImportTally importCsvParties(String content, StringBuilder errors, boolean supplier) {
        ImportTally t = new ImportTally();
        List<Map<String, String>> rows = parseCsvWithHeader(content);
        String companyId = tenantContext.getCurrentCompanyId();
        for (Map<String, String> row : rows) {
            t.total++;
            String nif = trim(row.get("nif"));
            String name = trim(row.get("name"));
            if (nif == null || name == null) {
                t.errors++;
                appendErr(errors, "Falta nif/name: " + row);
                continue;
            }
            try {
                // Estructura básica: usamos parties si existe, si no
                // dejamos placeholder (el slice PARTIES dedicado lo
                // formalizará).
                int updated = supplier
                        ? jdbcTemplate.update("""
                                INSERT IGNORE INTO suppliers (
                                    id, company_id, nif, name, email, phone, address, active
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)
                                """,
                                UUID.randomUUID().toString(), companyId, nif.toUpperCase(),
                                name, trim(row.get("email")), trim(row.get("phone")), trim(row.get("address")))
                        : jdbcTemplate.update("""
                                INSERT IGNORE INTO customers (
                                    id, company_id, nif, legal_name, email, phone, address, active
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)
                                """,
                                UUID.randomUUID().toString(), companyId, nif.toUpperCase(),
                                name, trim(row.get("email")), trim(row.get("phone")), trim(row.get("address")));
                if (updated > 0) t.imported++;
                else t.skipped++;
            } catch (DataAccessException ex) {
                t.errors++;
                appendErr(errors, "NIF " + nif + ": " + ex.getMessage());
            }
        }
        return t;
    }

    // ====================================================================
    //  Contasol TXT
    // ====================================================================

    private ImportTally importContasol(ImportRequest req, StringBuilder errors) {
        return switch (req.targetKind().toUpperCase()) {
            case "ACCOUNTS" -> importContasolAccounts(req.content(), errors);
            case "JOURNAL_ENTRIES" -> importContasolJournal(req.content(), errors);
            default -> throw bad("Contasol no implementado para target " + req.targetKind());
        };
    }

    /**
     * Contasol cuentas TXT (formato: cada línea = una cuenta).
     * Posiciones aproximadas: 1-15 code, 16-65 name.
     */
    private ImportTally importContasolAccounts(String content, StringBuilder errors) {
        ImportTally t = new ImportTally();
        String companyId = tenantContext.getCurrentCompanyId();
        try (BufferedReader br = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                t.total++;
                try {
                    String code = line.length() >= 15 ? line.substring(0, 15).trim() : line.trim();
                    String name = line.length() >= 65 ? line.substring(15, 65).trim()
                            : (line.length() > 15 ? line.substring(15).trim() : code);
                    if (code.isBlank() || name.isBlank()) { t.errors++; continue; }
                    int updated = jdbcTemplate.update("""
                            INSERT IGNORE INTO accounting_accounts (
                                id, company_id, code, name, account_type, active
                            ) VALUES (?, ?, ?, ?, 'OTHER', TRUE)
                            """,
                            UUID.randomUUID().toString(), companyId, code, name);
                    if (updated > 0) t.imported++; else t.skipped++;
                } catch (Exception ex) {
                    t.errors++;
                    appendErr(errors, "Línea: " + line.substring(0, Math.min(40, line.length())));
                }
            }
        } catch (Exception ex) {
            throw bad("Error parseando Contasol: " + ex.getMessage());
        }
        return t;
    }

    /**
     * Contasol diario TXT — formato ASCII por línea fija.
     * Cada línea es un apunte: yyyyMMdd code amount D/H concepto.
     */
    private ImportTally importContasolJournal(String content, StringBuilder errors) {
        ImportTally t = new ImportTally();
        Map<String, EntryGroup> grouped = new java.util.LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new StringReader(content))) {
            String line;
            int seq = 0;
            while ((line = br.readLine()) != null) {
                if (line.isBlank() || line.length() < 25) continue;
                String date = line.substring(0, 8);
                String code = line.substring(8, 18).trim();
                String amountStr = line.substring(18, 30).trim();
                String side = line.substring(30, 31);
                String concept = line.length() > 31 ? line.substring(31).trim() : "";
                String key = date + "|" + (seq / 2);
                seq++;
                Map<String, String> row = new HashMap<>();
                row.put("account_code", code);
                row.put("description", concept);
                if ("D".equalsIgnoreCase(side)) {
                    row.put("debit", amountStr);
                    row.put("credit", "0");
                } else {
                    row.put("debit", "0");
                    row.put("credit", amountStr);
                }
                grouped.computeIfAbsent(key, k -> new EntryGroup(date, concept))
                        .lines.add(row);
            }
        } catch (Exception ex) {
            throw bad("Error parseando Contasol diario: " + ex.getMessage());
        }
        for (Map.Entry<String, EntryGroup> e : grouped.entrySet()) {
            t.total++;
            EntryGroup g = e.getValue();
            try {
                LocalDate entryDate = LocalDate.parse(g.date, DateTimeFormatter.BASIC_ISO_DATE);
                List<ManualJournalEntryService.LineRequest> lines = new ArrayList<>();
                for (Map<String, String> r : g.lines) {
                    String accountId = resolveAccount(r.get("account_code"));
                    if (accountId == null) throw new IllegalStateException("Cuenta " + r.get("account_code"));
                    lines.add(new ManualJournalEntryService.LineRequest(
                            accountId, r.get("description"),
                            parseAmount(r.get("debit")), parseAmount(r.get("credit"))));
                }
                manualEntries.createDraft(new ManualJournalEntryService.ManualEntryRequest(
                        entryDate, g.concept, lines, false));
                t.imported++;
            } catch (Exception ex) {
                t.errors++;
                appendErr(errors, "Asiento " + e.getKey() + ": " + ex.getMessage());
            }
        }
        return t;
    }

    // ====================================================================
    //  JSON BENJAGEST (formato propio canónico)
    // ====================================================================

    private ImportTally importJsonBenjagest(ImportRequest req, StringBuilder errors) {
        // Sub-slice: el formato JSON canónico se define junto con
        // EXPORT-CONTABLE. Por ahora dejamos el esqueleto para que el
        // endpoint exista y la UI pueda enseñarlo "próximamente".
        throw bad("JSON_BENJAGEST se habilita junto con EXPORT-CONTABLE. Esqueleto listo.");
    }

    // ====================================================================
    //  Histórico de batches
    // ====================================================================

    public List<BatchView> listBatches(String targetKind, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, company_id, source_format, target_kind, file_name,
                       rows_total, rows_imported, rows_skipped, rows_errors,
                       imported_by_user_id, imported_at, notes
                  FROM external_import_batches
                 WHERE company_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (targetKind != null && !targetKind.isBlank()) {
            sql.append(" AND target_kind = ?");
            args.add(targetKind);
        }
        sql.append(" ORDER BY imported_at DESC LIMIT ?");
        args.add(limit <= 0 ? 50 : limit);
        return jdbcTemplate.query(sql.toString(), this::mapBatch, args.toArray());
    }

    private BatchView mapBatch(ResultSet rs, int n) throws SQLException {
        java.sql.Timestamp ia = rs.getTimestamp("imported_at");
        return new BatchView(
                rs.getString("id"), rs.getString("company_id"),
                rs.getString("source_format"), rs.getString("target_kind"),
                rs.getString("file_name"),
                rs.getInt("rows_total"), rs.getInt("rows_imported"),
                rs.getInt("rows_skipped"), rs.getInt("rows_errors"),
                rs.getString("imported_by_user_id"),
                ia == null ? null : ia.toInstant(),
                rs.getString("notes"));
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private List<Map<String, String>> parseCsvWithHeader(String content) {
        List<Map<String, String>> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new StringReader(content))) {
            String headerLine = br.readLine();
            if (headerLine == null) return out;
            String sep = headerLine.contains(";") ? ";" : ",";
            String[] headers = headerLine.split(java.util.regex.Pattern.quote(sep), -1);
            for (int i = 0; i < headers.length; i++) headers[i] = headers[i].trim().toLowerCase();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(java.util.regex.Pattern.quote(sep), -1);
                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < headers.length && i < parts.length; i++) {
                    row.put(headers[i], parts[i].trim());
                }
                out.add(row);
            }
        } catch (Exception ex) {
            throw bad("Error parseando CSV: " + ex.getMessage());
        }
        return out;
    }

    private String resolveAccount(String code) {
        if (code == null || code.isBlank()) return null;
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND code = ? AND active = TRUE LIMIT 1
                """, (rs, n) -> rs.getString("id"),
                tenantContext.getCurrentCompanyId(), code);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private LocalDate parseDate(String s) {
        if (s == null) throw new IllegalArgumentException("Fecha vacía");
        s = s.trim();
        try { return LocalDate.parse(s, ISO); } catch (Exception ignored) {}
        try { return LocalDate.parse(s, ES); } catch (Exception ignored) {}
        try { return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE); } catch (Exception ignored) {}
        throw new IllegalArgumentException("Fecha no parseable: " + s);
    }

    private BigDecimal parseAmount(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        return new BigDecimal(s.trim().replace(".", "").replace(",", ".")
                .replace("€", "").replace(" ", ""));
    }

    private static String trim(String s) { return s == null ? null : s.trim().isEmpty() ? null : s.trim(); }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
    private static void appendErr(StringBuilder sb, String s) {
        if (sb.length() > 3500) return;
        sb.append(s).append('\n');
    }

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // ====================================================================
    //  DTOs
    // ====================================================================

    public record ImportRequest(
            String format, String targetKind, String fileName,
            String content, String notes
    ) {}

    public record BatchResult(
            String batchId, int total, int imported, int skipped, int errors,
            String errorLog
    ) {}

    public record BatchView(
            String id, String companyId, String sourceFormat, String targetKind,
            String fileName,
            int rowsTotal, int rowsImported, int rowsSkipped, int rowsErrors,
            String importedByUserId, Instant importedAt, String notes
    ) {}

    private static class ImportTally {
        int total; int imported; int skipped; int errors;
    }

    private static class EntryGroup {
        final String date; final String concept;
        final List<Map<String, String>> lines = new ArrayList<>();
        EntryGroup(String d, String c) { this.date = d; this.concept = c; }
    }
}
