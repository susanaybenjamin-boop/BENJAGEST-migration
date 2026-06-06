package com.benjagest.ui.service;

import com.benjagest.ui.model.AccountingModels;
import com.benjagest.ui.model.AccountingModels.AccountSummary;
import com.benjagest.ui.model.AccountingModels.DiaryEntry;
import com.benjagest.ui.model.AccountingModels.JournalEntryDetail;
import com.benjagest.ui.model.AccountingModels.JournalLine;
import com.benjagest.ui.model.AccountingModels.LearningRule;
import com.benjagest.ui.model.AccountingModels.RecurringTask;
import com.benjagest.ui.model.AccountingModels.RecurringTaskRun;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente HTTP del módulo Contabilidad. Envuelve los endpoints REST
 * añadidos en los slices ACC-MANUAL, ACC-BOOKS, ACC-LEARN, RECURRING.
 *
 * <p>Mismo patrón que el resto de ApiClients (parser regex JSON ligero,
 * autorización via {@link AuthSession}).
 */
public class AccountingApiClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String baseUrl;

    public AccountingApiClient() {
        this(System.getenv().getOrDefault("BENJAGEST_API_BASE_URL", DEFAULT_API_BASE_URL));
    }

    public AccountingApiClient(String baseUrl) { this.baseUrl = baseUrl; }

    // ====================================================================
    //  Libro Diario
    // ====================================================================

    public List<DiaryEntry> diary(LocalDate from, LocalDate to,
                                    String status, String sourceType,
                                    Integer limit) throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        if (from != null) append(q, "from", from.toString());
        if (to != null)   append(q, "to", to.toString());
        if (status != null && !status.isBlank()) append(q, "status", status);
        if (sourceType != null && !sourceType.isBlank()) append(q, "sourceType", sourceType);
        if (limit != null) append(q, "limit", limit.toString());
        String json = get("/accounting/diary" + q);
        List<DiaryEntry> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) {
            out.add(parseDiary(obj));
        }
        return out;
    }

    public JournalEntryDetail getEntry(String id) throws IOException, InterruptedException {
        String json = get("/accounting/journal-entries/" + id);
        return parseEntryDetail(json);
    }

    public JournalEntryDetail createEntry(LocalDate entryDate, String concept,
                                            List<JournalLine> lines, boolean postNow)
            throws IOException, InterruptedException {
        String body = buildEntryRequest(entryDate, concept, lines, postNow);
        String resp = postRaw("/accounting/journal-entries", body);
        return parseEntryDetail(resp);
    }

    public JournalEntryDetail updateEntry(String id, LocalDate entryDate, String concept,
                                            List<JournalLine> lines, boolean postNow)
            throws IOException, InterruptedException {
        String body = buildEntryRequest(entryDate, concept, lines, postNow);
        String resp = put("/accounting/journal-entries/" + id, body);
        return parseEntryDetail(resp);
    }

    public JournalEntryDetail postEntry(String id) throws IOException, InterruptedException {
        String resp = postRaw("/accounting/journal-entries/" + id + "/post", "{}");
        return parseEntryDetail(resp);
    }

    public void voidEntry(String id, String reason) throws IOException, InterruptedException {
        String path = "/accounting/journal-entries/" + id;
        if (reason != null && !reason.isBlank()) {
            path += "?reason=" + URLEncoder.encode(reason, StandardCharsets.UTF_8);
        }
        delete(path);
    }

    public List<AccountSummary> listAccounts(String search) throws IOException, InterruptedException {
        // Endpoint genérico de cuentas: el workspace devuelve /api/workspace/accounts
        // o /api/accounting/accounts. Usamos el query genérico del workspace
        // si el endpoint principal no existe, y normalizamos.
        try {
            String json = get("/accounting/accounts" + (search == null || search.isBlank()
                    ? "" : "?search=" + URLEncoder.encode(search, StandardCharsets.UTF_8)));
            return parseAccountList(json);
        } catch (Exception ex) {
            // Fallback: workspace catalog
            try {
                String json = get("/workspace/accounting/accounts" + (search == null || search.isBlank()
                        ? "" : "?search=" + URLEncoder.encode(search, StandardCharsets.UTF_8)));
                return parseAccountList(json);
            } catch (Exception ex2) {
                return List.of();
            }
        }
    }

    // ====================================================================
    //  Aprendizaje contable
    // ====================================================================

    public List<LearningRule> listRules(String kind, Boolean activeOnly)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        if (kind != null && !kind.isBlank()) append(q, "kind", kind);
        if (activeOnly != null) append(q, "active", activeOnly.toString());
        String json = get("/accounting/learning/rules" + q);
        List<LearningRule> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) out.add(parseRule(obj));
        return out;
    }

    public void setRuleActive(String ruleId, boolean active)
            throws IOException, InterruptedException {
        put("/accounting/learning/rules/" + ruleId,
                "{\"active\":" + active + "}");
    }

    public void deleteRule(String ruleId) throws IOException, InterruptedException {
        delete("/accounting/learning/rules/" + ruleId);
    }

    public void acceptEntry(String entryId, List<String> appliedRuleIds)
            throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{\"appliedRuleIds\":[");
        if (appliedRuleIds != null) {
            for (int i = 0; i < appliedRuleIds.size(); i++) {
                if (i > 0) body.append(',');
                body.append('"').append(escape(appliedRuleIds.get(i))).append('"');
            }
        }
        body.append("]}");
        postRaw("/accounting/learning/entries/" + entryId + "/accept", body.toString());
    }

    public void recordCorrection(String entryId, String lineId,
                                  String fromAccountId, String toAccountId, String toAccountCode,
                                  String supplierNif, String customerNif, String keyword,
                                  String originalRuleId)
            throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{");
        appendKV(body, "journalEntryId", entryId, true);
        appendKV(body, "lineId", lineId, false);
        appendKV(body, "fromAccountId", fromAccountId, false);
        appendKV(body, "toAccountId", toAccountId, false);
        appendKV(body, "toAccountCode", toAccountCode, false);
        appendKV(body, "supplierNif", supplierNif, false);
        appendKV(body, "customerNif", customerNif, false);
        appendKV(body, "keyword", keyword, false);
        appendKV(body, "originalRuleId", originalRuleId, false);
        body.append("}");
        postRaw("/accounting/learning/corrections", body.toString());
    }

    // ====================================================================
    //  Tareas recurrentes
    // ====================================================================

    public List<RecurringTask> listRecurring(String kind, Boolean activeOnly)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        if (kind != null && !kind.isBlank()) append(q, "kind", kind);
        if (activeOnly != null) append(q, "active", activeOnly.toString());
        String json = get("/accounting/recurring" + q);
        List<RecurringTask> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) out.add(parseRecurring(obj));
        return out;
    }

    public List<RecurringTaskRun> listRecurringRuns(String id, int limit)
            throws IOException, InterruptedException {
        String json = get("/accounting/recurring/" + id + "/runs?limit=" + limit);
        List<RecurringTaskRun> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) out.add(parseRecurringRun(obj));
        return out;
    }

    public void setRecurringActive(String id, boolean active)
            throws IOException, InterruptedException {
        put("/accounting/recurring/" + id + "/active",
                "{\"active\":" + active + "}");
    }

    public void deleteRecurring(String id) throws IOException, InterruptedException {
        delete("/accounting/recurring/" + id);
    }

    public void runRecurringNow(String id, LocalDate date)
            throws IOException, InterruptedException {
        String path = "/accounting/recurring/" + id + "/run-now"
                + (date == null ? "" : "?date=" + date);
        postRaw(path, "{}");
    }

    // ====================================================================
    //  Backfill — regenerar asientos faltantes para facturas existentes
    // ====================================================================

    /**
     * Llama al endpoint {@code POST /api/accounting/backfill/run}. Recorre
     * todas las facturas recibidas con {@code journal_entry_id IS NULL} y
     * todas las facturas emitidas VALIDATED sin asiento {@code SALES_INVOICE},
     * e invoca el service auto-generador. Devuelve resumen estructurado.
     */
    public BackfillResult runBackfill() throws IOException, InterruptedException {
        String json = postRaw("/accounting/backfill/run", "{}");
        return new BackfillResult(
                intField(json, "purchasesProcessed"),
                intField(json, "purchasesPosted"),
                intField(json, "purchasesSkipped"),
                intField(json, "salesProcessed"),
                intField(json, "salesPosted"),
                intField(json, "salesSkipped"));
    }

    public record BackfillResult(
            int purchasesProcessed, int purchasesPosted, int purchasesSkipped,
            int salesProcessed, int salesPosted, int salesSkipped
    ) {
        public int totalPosted() { return purchasesPosted + salesPosted; }
        public int totalProcessed() { return purchasesProcessed + salesProcessed; }
    }

    // ====================================================================
    //  Bancos
    // ====================================================================

    public List<AccountingModels.BankAccountView> listBankAccounts(Boolean activeOnly)
            throws IOException, InterruptedException {
        String json = get("/accounting/bank-accounts"
                + (activeOnly == null ? "" : "?active=" + activeOnly));
        List<AccountingModels.BankAccountView> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) {
            out.add(new AccountingModels.BankAccountView(
                    strField(obj, "id"),
                    strField(obj, "alias"),
                    strField(obj, "iban"),
                    strField(obj, "bankName"),
                    strField(obj, "currency"),
                    bdField(obj, "openingBalance"),
                    boolField(obj, "active")));
        }
        return out;
    }

    public List<AccountingModels.BankMovementRow> listBankMovements(
            String bankAccountId, String status, LocalDate from, LocalDate to)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        if (bankAccountId != null) append(q, "bankAccountId", bankAccountId);
        if (status != null && !status.isBlank()) append(q, "status", status);
        if (from != null) append(q, "from", from.toString());
        if (to != null) append(q, "to", to.toString());
        String json = get("/accounting/bank-movements" + q);
        List<AccountingModels.BankMovementRow> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) {
            out.add(new AccountingModels.BankMovementRow(
                    strField(obj, "id"),
                    strField(obj, "bankAccountId"),
                    localDateField(obj, "operationDate"),
                    strField(obj, "description"),
                    strField(obj, "counterpartyName"),
                    strField(obj, "counterpartyNif"),
                    bdField(obj, "amount"),
                    bdField(obj, "balanceAfter"),
                    strField(obj, "status"),
                    strField(obj, "linkedInvoiceId"),
                    strField(obj, "linkedInvoiceKind")));
        }
        return out;
    }

    // ====================================================================
    //  Préstamos
    // ====================================================================

    public List<AccountingModels.LoanView> listLoans(String status)
            throws IOException, InterruptedException {
        String json = get("/accounting/loans"
                + (status == null || status.isBlank() ? "" : "?status=" + status));
        List<AccountingModels.LoanView> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) {
            out.add(new AccountingModels.LoanView(
                    strField(obj, "id"),
                    strField(obj, "code"),
                    strField(obj, "description"),
                    strField(obj, "lenderName"),
                    bdField(obj, "principalAmount"),
                    bdField(obj, "interestRate"),
                    intField(obj, "termMonths"),
                    localDateField(obj, "startDate"),
                    bdField(obj, "installmentAmount"),
                    strField(obj, "method"),
                    strField(obj, "status")));
        }
        return out;
    }

    public List<AccountingModels.InstallmentView> listInstallments(String loanId)
            throws IOException, InterruptedException {
        String json = get("/accounting/loans/" + loanId + "/installments");
        List<AccountingModels.InstallmentView> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) {
            out.add(new AccountingModels.InstallmentView(
                    strField(obj, "id"),
                    intField(obj, "installmentNumber"),
                    localDateField(obj, "dueDate"),
                    bdField(obj, "principalAmount"),
                    bdField(obj, "interestAmount"),
                    bdField(obj, "totalAmount"),
                    bdField(obj, "remainingPrincipal"),
                    strField(obj, "status")));
        }
        return out;
    }

    public void payInstallment(String installmentId, LocalDate paymentDate)
            throws IOException, InterruptedException {
        String body = paymentDate == null ? "{}"
                : "{\"paymentDate\":\"" + paymentDate + "\"}";
        postRaw("/accounting/loans/installments/" + installmentId + "/pay", body);
    }

    // ====================================================================
    //  Inmovilizado (lectura — el alta vive en otro flujo)
    // ====================================================================

    public List<AccountingModels.FixedAssetRow> listFixedAssets()
            throws IOException, InterruptedException {
        // Endpoint del módulo accounting clásico (V34).
        try {
            String json = get("/accounting/fixed-assets");
            return parseAssetList(json);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<AccountingModels.FixedAssetRow> parseAssetList(String json) {
        List<AccountingModels.FixedAssetRow> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) {
            out.add(new AccountingModels.FixedAssetRow(
                    strField(obj, "id"),
                    strField(obj, "code"),
                    strField(obj, "name"),
                    strField(obj, "category"),
                    localDateField(obj, "acquisitionDate"),
                    bdField(obj, "acquisitionCost"),
                    bdField(obj, "usefulLifeYears"),
                    strField(obj, "depreciationMethod"),
                    boolField(obj, "active")));
        }
        return out;
    }

    public void postAssetDepreciationEntry(String assetId, int year, Integer month)
            throws IOException, InterruptedException {
        String path = "/accounting/assets/" + assetId + "/depreciation-entry?year=" + year
                + (month == null ? "" : "&month=" + month);
        postRaw(path, "{}");
    }

    // ====================================================================
    //  HTTP helpers
    // ====================================================================

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15)).GET();
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        checkAuth(r);
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    private String postRaw(String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        checkAuth(r);
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    private String put(String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        checkAuth(r);
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    private void delete(String path) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15)).DELETE();
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        checkAuth(r);
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    /**
     * Detecta 401 (token inválido) y 403 con cuerpo de Spring Security
     * por defecto ({@code "error":"Forbidden"} sin mensaje de permisos)
     * — son síntomas típicos de JWT expirado. Lanza {@link
     * SessionExpiredException} para que la UI muestre "Sesión expirada"
     * en vez del JSON crudo.
     *
     * <p>Si el 403 trae mensaje de permisos específico ("Tu rol no tiene
     * permiso", "El modulo X no esta activo") lo dejamos pasar para que
     * la UI muestre el mensaje real.
     */
    private void checkAuth(HttpResponse<String> r) {
        if (r.statusCode() == 401) {
            throw new SessionExpiredException("HTTP 401");
        }
        if (r.statusCode() == 403) {
            String body = r.body() == null ? "" : r.body();
            // Spring Security default = JSON {"error":"Forbidden",...} sin más.
            // Si NO contiene mensaje de permisos específico, asumimos sesión.
            if (!body.contains("rol")
                    && !body.contains("modulo")
                    && !body.contains("módulo")
                    && !body.contains("role")
                    && !body.contains("module")) {
                throw new SessionExpiredException("HTTP 403");
            }
        }
    }

    // ====================================================================
    //  Parsers
    // ====================================================================

    private DiaryEntry parseDiary(String json) {
        return new DiaryEntry(
                strField(json, "id"),
                intField(json, "entryNumber"),
                localDateField(json, "entryDate"),
                strField(json, "concept"),
                strField(json, "sourceType"),
                strField(json, "status"),
                boolField(json, "autoProposed"),
                bdField(json, "proposedConfidence"),
                bdField(json, "totalDebit"),
                bdField(json, "totalCredit"),
                intField(json, "numLines"));
    }

    private JournalEntryDetail parseEntryDetail(String json) {
        List<JournalLine> lines = new ArrayList<>();
        String linesArray = extractArrayField(json, "lines");
        if (linesArray != null) {
            for (String obj : splitJsonArray(linesArray)) {
                lines.add(new JournalLine(
                        strField(obj, "id"),
                        strField(obj, "accountId"),
                        strField(obj, "accountCode"),
                        strField(obj, "accountName"),
                        strField(obj, "description"),
                        bdField(obj, "debit"),
                        bdField(obj, "credit")));
            }
        }
        return new JournalEntryDetail(
                strField(json, "id"),
                intField(json, "entryNumber"),
                localDateField(json, "entryDate"),
                strField(json, "concept"),
                strField(json, "sourceType"),
                strField(json, "status"),
                boolField(json, "autoProposed"),
                bdField(json, "proposedConfidence"),
                lines);
    }

    private List<AccountSummary> parseAccountList(String json) {
        List<AccountSummary> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) {
            out.add(new AccountSummary(
                    strField(obj, "id"),
                    strField(obj, "code"),
                    strField(obj, "name"),
                    strField(obj, "accountType")));
        }
        return out;
    }

    private LearningRule parseRule(String json) {
        return new LearningRule(
                strField(json, "id"),
                strField(json, "ruleKind"),
                strField(json, "matchSupplierNif"),
                strField(json, "matchCustomerNif"),
                strField(json, "matchKeyword"),
                strField(json, "targetAccountCode"),
                intField(json, "timesApplied"),
                intField(json, "timesOverridden"),
                bdField(json, "confidence"),
                boolField(json, "active"));
    }

    private RecurringTask parseRecurring(String json) {
        return new RecurringTask(
                strField(json, "id"),
                strField(json, "kind"),
                strField(json, "name"),
                strField(json, "description"),
                strField(json, "frequency"),
                intFieldOrNull(json, "dayOfMonth"),
                intFieldOrNull(json, "dayOfWeek"),
                intField(json, "monthsBetween"),
                localDateField(json, "nextRunDate"),
                localDateField(json, "lastRunDate"),
                strField(json, "lastRunStatus"),
                intField(json, "timesRun"),
                intField(json, "timesFailed"),
                boolField(json, "active"));
    }

    private RecurringTaskRun parseRecurringRun(String json) {
        return new RecurringTaskRun(
                strField(json, "id"),
                localDateField(json, "scheduledDate"),
                strField(json, "status"),
                strField(json, "generatedId"),
                strField(json, "generatedKind"),
                strField(json, "message"),
                intField(json, "durationMs"));
    }

    // ====================================================================
    //  JSON helpers (parser regex muy simple — el patrón del proyecto)
    // ====================================================================

    private String strField(String json, String key) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(json);
        if (!m.find()) {
            // null literal
            Matcher m2 = Pattern.compile("\"" + key + "\"\\s*:\\s*null").matcher(json);
            return m2.find() ? null : null;
        }
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\r", "");
    }

    private int intField(String json, String key) {
        if (json == null) return 0;
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private Integer intFieldOrNull(String json, String key) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+|null)").matcher(json);
        if (!m.find()) return null;
        String v = m.group(1);
        return "null".equals(v) ? null : Integer.parseInt(v);
    }

    private BigDecimal bdField(String json, String key) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?|null)").matcher(json);
        if (!m.find()) return null;
        String v = m.group(1);
        return "null".equals(v) ? null : new BigDecimal(v);
    }

    private boolean boolField(String json, String key) {
        if (json == null) return false;
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() && "true".equals(m.group(1));
    }

    private LocalDate localDateField(String json, String key) {
        String v = strField(json, key);
        if (v == null || v.isBlank()) return null;
        try { return LocalDate.parse(v); } catch (Exception ex) { return null; }
    }

    private List<String> splitJsonArray(String json) {
        List<String> out = new ArrayList<>();
        if (json == null) return out;
        String s = json.trim();
        if (!s.startsWith("[")) {
            if (s.startsWith("{")) { out.add(s); }
            return out;
        }
        int depth = 0, start = -1;
        boolean inString = false, escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(s.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return out;
    }

    /** Extrae el contenido del array indicado por la key (incluyendo []) o null. */
    private String extractArrayField(String json, String key) {
        if (json == null) return null;
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int bracket = json.indexOf('[', idx);
        if (bracket < 0) return null;
        int depth = 0;
        boolean inString = false, escape = false;
        for (int i = bracket; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return json.substring(bracket, i + 1);
            }
        }
        return null;
    }

    private String buildEntryRequest(LocalDate entryDate, String concept,
                                       List<JournalLine> lines, boolean postNow) {
        StringBuilder b = new StringBuilder("{");
        appendKV(b, "entryDate", entryDate == null ? null : entryDate.toString(), true);
        appendKV(b, "concept", concept, false);
        b.append(",\"postNow\":").append(postNow);
        b.append(",\"lines\":[");
        if (lines != null) {
            for (int i = 0; i < lines.size(); i++) {
                JournalLine l = lines.get(i);
                if (i > 0) b.append(',');
                b.append("{");
                appendKV(b, "accountId", l.accountId(), true);
                appendKV(b, "description", l.description(), false);
                b.append(",\"debit\":").append(l.debit() == null ? "0" : l.debit().toPlainString());
                b.append(",\"credit\":").append(l.credit() == null ? "0" : l.credit().toPlainString());
                b.append("}");
            }
        }
        b.append("]}");
        return b.toString();
    }

    private void appendKV(StringBuilder b, String key, String value, boolean first) {
        if (!first) b.append(',');
        if (value == null) b.append('"').append(key).append("\":null");
        else b.append('"').append(key).append("\":\"").append(escape(value)).append('"');
    }

    private void append(StringBuilder q, String key, String value) {
        q.append(q.length() == 0 ? '?' : '&').append(key).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static String escape(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
