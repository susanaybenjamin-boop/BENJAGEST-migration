package com.benjagest.ui.service;

import com.benjagest.ui.model.AccountingModels;
import com.benjagest.ui.model.AccountingModels.AccountSummary;
import com.benjagest.ui.model.AccountingModels.DiaryEntry;
import com.benjagest.ui.model.AccountingModels.JournalEntryDetail;
import com.benjagest.ui.model.AccountingModels.JournalLine;
import com.benjagest.ui.model.AccountingModels.LearningRule;
import com.benjagest.ui.model.AccountingModels.RecurringCandidate;
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

    /**
     * KPIs rápidos del trimestre/rango para "Ventas y Gastos":
     * ventas, gastos, IVA repercutido, IVA soportado, Modelo 303
     * estimado, asientos DRAFT pendientes.
     */
    public SalesAndExpensesKpis kpisSalesAndExpenses(LocalDate from, LocalDate to)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        if (from != null) append(q, "from", from.toString());
        if (to != null)   append(q, "to", to.toString());
        String json = get("/accounting/kpis/sales-and-expenses" + q);
        return new SalesAndExpensesKpis(
                decField(json, "salesTotal"),
                intField(json, "salesCount"),
                decField(json, "expensesTotal"),
                intField(json, "expensesCount"),
                decField(json, "vatCharged"),
                decField(json, "vatBorne"),
                decField(json, "model303Estimated"),
                intField(json, "draftCount"));
    }

    /**
     * Actualiza el concepto de un asiento existente.
     * @return true si el backend confirmó la edición.
     */
    public boolean updateEntryConcept(String entryId, String concept)
            throws IOException, InterruptedException {
        String body = "{\"concept\":\"" + (concept == null ? "" :
                concept.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"}";
        String json = postRaw("/accounting/journal-entries/" + entryId + "/update-concept",
                body);
        return json.contains("\"updated\":true");
    }

    /**
     * Re-extrae los campos desde el PDF asociado al asiento usando la
     * regex/heurística actual. Devuelve los campos como sugerencia —
     * NO actualiza nada en el backend.
     */
    public java.util.Map<String, String> reExtractEntryFromPdf(String entryId)
            throws IOException, InterruptedException {
        String json = postRaw("/accounting/journal-entries/" + entryId + "/re-extract", "");
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String field : new String[]{"invoiceNumber", "invoiceDate",
                "supplierName", "emitterNif", "receiverName", "receiverNif",
                "baseAmount", "vatAmount", "totalAmount"}) {
            String v = strField(json, field);
            if (v != null) out.put(field, v);
        }
        return out;
    }

    /**
     * Borra una lista de asientos importados (duplicados). Solo borra
     * los que tienen source_type='SALES_PDF_IMPORT' o 'PURCHASE_INVOICE'.
     * Devuelve cuántos se borraron efectivamente.
     */
    public int deleteImportedEntries(List<String> ids) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{\"ids\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) body.append(',');
            body.append('"').append(ids.get(i)).append('"');
        }
        body.append("]}");
        String json = postRaw("/accounting/duplicates/delete", body.toString());
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"deleted\"\\s*:\\s*(\\d+)")
                .matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    public record SalesAndExpensesKpis(
            java.math.BigDecimal salesTotal,
            int salesCount,
            java.math.BigDecimal expensesTotal,
            int expensesCount,
            java.math.BigDecimal vatCharged,
            java.math.BigDecimal vatBorne,
            java.math.BigDecimal model303Estimated,
            int draftCount) {}

    private java.math.BigDecimal decField(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
                .matcher(json);
        if (!m.find()) return java.math.BigDecimal.ZERO;
        try { return new java.math.BigDecimal(m.group(1)); }
        catch (NumberFormatException e) { return java.math.BigDecimal.ZERO; }
    }

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
        return postEntry(id, false);
    }

    /** DUP-VALIDAR: force=true valida aunque el backend detecte un posible duplicado. */
    public JournalEntryDetail postEntry(String id, boolean force) throws IOException, InterruptedException {
        String resp = postRaw("/accounting/journal-entries/" + id + "/post"
                + (force ? "?force=true" : ""), "{}");
        return parseEntryDetail(resp);
    }

    /** DUP-VALIDAR: elimina un gasto duplicado confirmado por el usuario. */
    public void deleteDuplicateExpense(String purchaseInvoiceId) throws IOException, InterruptedException {
        postRaw("/purchases/invoices/" + purchaseInvoiceId + "/delete-duplicate", "{}");
    }

    /** DUP-SCAN: duplicados POSTED pendientes de revisar — [deleteId, detalle]. */
    public java.util.List<String[]> getExpenseDuplicates() throws IOException, InterruptedException {
        String json = get("/accounting/duplicates/expenses");
        java.util.List<String[]> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\{[^{}]*\\}").matcher(json == null ? "" : json);
        while (m.find()) {
            String obj = m.group();
            java.util.regex.Matcher del = java.util.regex.Pattern
                    .compile("\"deleteId\"\\s*:\\s*\"([^\"]*)\"").matcher(obj);
            java.util.regex.Matcher det = java.util.regex.Pattern
                    .compile("\"detail\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(obj);
            out.add(new String[]{ del.find() ? del.group(1) : "",
                    det.find() ? det.group(1).replace("\\\"", "\"") : "" });
        }
        return out;
    }

    /**
     * Valida un lote de asientos DRAFT. El usuario hace Ctrl+click /
     * Shift+click para seleccionar varios en el tab "Por validar" y
     * pulsa "Validar seleccionados". Devuelve {@link BatchPostResult}
     * con desglose total/posted/skipped/errors para mostrarlo al usuario.
     */
    public BatchPostResult postBatchEntries(java.util.List<String> ids)
            throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{\"ids\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) body.append(',');
            body.append('"').append(ids.get(i)).append('"');
        }
        body.append("]}");
        String json = postRaw("/accounting/journal-entries/post-batch", body.toString());
        return new BatchPostResult(
                intField(json, "total"),
                intField(json, "posted"),
                intField(json, "skipped"),
                intField(json, "errors"));
    }

    public record BatchPostResult(int total, int posted, int skipped, int errors) {}

    /**
     * Reclasifica todos los asientos DRAFT del cliente activo aplicando el
     * chain histórico → classifier para la cuenta 6xx/7xx. Solo toca líneas
     * con cuenta genérica (600/629/700/759). Idempotente. Devuelve
     * {@link ReclassifyResult} con conteos.
     */
    public ReclassifyResult reclassifyDrafts() throws IOException, InterruptedException {
        String json = postRaw("/accounting/reclassify", "{}");
        return new ReclassifyResult(
                intField(json, "entriesScanned"),
                intField(json, "linesUpdated"));
    }

    public record ReclassifyResult(int entriesScanned, int linesUpdated) {}

    // ------------------------------------------------------------------
    //  Config sub-cuenta de tercero (longitud + modo)
    // ------------------------------------------------------------------

    /** Lee la config actual (length 6-12, mode SEQUENTIAL|BY_NIF). */
    public TerceroConfig getTerceroConfig() throws IOException, InterruptedException {
        String json = get("/accounting/tercero-config");
        return new TerceroConfig(
                intField(json, "length"),
                strField(json, "mode"));
    }

    /** Actualiza la config. Devuelve la config tras el PUT. */
    public TerceroConfig updateTerceroConfig(int length, String mode)
            throws IOException, InterruptedException {
        String body = "{\"length\":" + length
                + ",\"mode\":\"" + (mode == null ? "SEQUENTIAL" : mode) + "\"}";
        String json = put("/accounting/tercero-config", body);
        return new TerceroConfig(
                intField(json, "length"),
                strField(json, "mode"));
    }

    public record TerceroConfig(int length, String mode) {}

    // ------------------------------------------------------------------
    //  Multi-import de PDFs → asientos directos
    // ------------------------------------------------------------------

    /**
     * Crea un asiento de venta DRAFT directo desde un PDF importado. El
     * backend resuelve la sub-cuenta del cliente, propone la cuenta 7xx
     * con el classifier y guarda el PDF para revisión posterior en la
     * pestaña "Por validar".
     *
     * @param pdfBytes contenido binario del PDF (puede ser null si no
     *                 quieres archivar el PDF — el asiento se crea igual).
     */
    public SalesImportResult importSalesFromPdf(
            String customerNif, String customerName,
            java.time.LocalDate invoiceDate,
            java.math.BigDecimal baseAmount,
            java.math.BigDecimal vatPercent,
            java.math.BigDecimal vatAmount,
            java.math.BigDecimal retentionAmount,
            java.math.BigDecimal totalAmount,
            String invoiceNumber, String concept,
            byte[] pdfBytes,
            boolean rectifying,
            String rectifiedInvoiceNumber) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{");
        appendKV(body, "customerNif", customerNif, true);
        appendKV(body, "customerName", customerName, false);
        appendKV(body, "invoiceDate", invoiceDate == null ? null : invoiceDate.toString(), false);
        appendNumKV(body, "baseAmount", baseAmount);
        appendNumKV(body, "vatPercent", vatPercent);
        appendNumKV(body, "vatAmount", vatAmount);
        appendNumKV(body, "retentionAmount", retentionAmount);
        appendNumKV(body, "totalAmount", totalAmount);
        appendKV(body, "invoiceNumber", invoiceNumber, false);
        appendKV(body, "concept", concept, false);
        body.append(",\"rectifying\":").append(rectifying);
        appendKV(body, "rectifiedInvoiceNumber", rectifiedInvoiceNumber, false);
        if (pdfBytes != null && pdfBytes.length > 0) {
            String b64 = java.util.Base64.getEncoder().encodeToString(pdfBytes);
            body.append(",\"pdfBase64\":\"").append(b64).append("\"");
        }
        body.append("}");
        String json = postRaw("/accounting/import-pdf/sales", body.toString());
        return new SalesImportResult(
                strField(json, "journalEntryId"),
                strField(json, "pdfSha256"));
    }

    /**
     * Wrapper de retrocompatibilidad para llamadas que no propagan
     * rectificativa (no es factura de anulación).
     */
    public SalesImportResult importSalesFromPdf(
            String customerNif, String customerName,
            java.time.LocalDate invoiceDate,
            java.math.BigDecimal baseAmount,
            java.math.BigDecimal vatPercent,
            java.math.BigDecimal vatAmount,
            java.math.BigDecimal retentionAmount,
            java.math.BigDecimal totalAmount,
            String invoiceNumber, String concept,
            byte[] pdfBytes) throws IOException, InterruptedException {
        return importSalesFromPdf(customerNif, customerName, invoiceDate,
                baseAmount, vatPercent, vatAmount, retentionAmount,
                totalAmount, invoiceNumber, concept, pdfBytes,
                false, null);
    }

    public record SalesImportResult(String journalEntryId, String pdfSha256) {}

    /** Descarga el PDF asociado al asiento (para el visor en pestaña pending). */
    public byte[] downloadEntrySourcePdf(String entryId)
            throws IOException, InterruptedException {
        return getBytes("/accounting/journal-entries/" + entryId + "/source-pdf");
    }

    private void appendNumKV(StringBuilder b, String key, java.math.BigDecimal v) {
        b.append(",\"").append(key).append("\":");
        if (v == null) b.append("null");
        else b.append(v.toPlainString());
    }

    /**
     * ASI-4 — Reclasifica la cuenta de una línea de un asiento VALIDADO: el
     * original se anula y nace el asiento correcto. Con {@code saveRule} además
     * aprende la cuenta para ese tercero.
     */
    public void reclassifyAccount(String entryId, String lineId, String newAccountId,
                                    String reason, boolean saveRule)
            throws IOException, InterruptedException {
        String body = "{\"lineId\":\"" + escape(lineId) + "\""
                + ",\"newAccountId\":\"" + escape(newAccountId) + "\""
                + ",\"reason\":\"" + escape(reason) + "\""
                + ",\"saveRule\":" + saveRule + "}";
        postRaw("/accounting/journal-entries/" + entryId + "/reclassify-account", body);
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

    /**
     * Lista candidatos a recurrencia para el banner del Slice 3D.
     * Llama al endpoint backend que detecta facturas repetidas con
     * mismo NIF + mismo importe en una ventana de tiempo.
     */
    /**
     * Slice 3P — ¿Existe ya una recurrente activa que cubra este
     * tercero + importe? El UI lo usa tras validar una factura para
     * no preguntar "¿hacer recurrente?" si ya está cubierta.
     */
    public boolean recurringAlreadyCovers(String kind, String partyName,
                                           java.math.BigDecimal amount)
            throws IOException, InterruptedException {
        return recurringAlreadyCovers(kind, null, partyName, amount);
    }

    /** Variante con NIF: "covered" incluye ahora también los candidatos SILENCIADOS
     *  (el usuario ya dijo "no" para ese tercero+importe). */
    public boolean recurringAlreadyCovers(String kind, String partyNif, String partyName,
                                           java.math.BigDecimal amount)
            throws IOException, InterruptedException {
        if (partyName == null || partyName.isBlank() || amount == null) return false;
        String path = "/accounting/recurring/already-covers"
                + "?kind=" + kind
                + (partyNif == null || partyNif.isBlank() ? ""
                        : "&nif=" + java.net.URLEncoder.encode(partyNif,
                                java.nio.charset.StandardCharsets.UTF_8))
                + "&partyName=" + java.net.URLEncoder.encode(partyName,
                        java.nio.charset.StandardCharsets.UTF_8)
                + "&amount=" + amount.toPlainString();
        String json = get(path);
        return json != null && json.contains("\"covered\":true");
    }

    /**
     * Slice 3S-3 — Check determinista: ¿esta factura/asiento la generó
     * el cron de recurrentes? Mira recurring_task_runs.generated_id, 100%
     * fiable. Si TRUE, no preguntamos "¿hacer recurrente?" al validar.
     */
    public boolean isFromRecurring(String kind, String generatedId)
            throws IOException, InterruptedException {
        if (generatedId == null || generatedId.isBlank()) return false;
        String path = "/accounting/recurring/" + kind + "/from-recurring/" + generatedId;
        String json = get(path);
        return json != null && json.contains("\"fromRecurring\":true");
    }

    public List<RecurringCandidate> listRecurringCandidates(String kind, Integer windowDays)
            throws IOException, InterruptedException {
        String path = "/accounting/recurring/candidates?kind=" + kind
                + (windowDays == null ? "" : "&windowDays=" + windowDays);
        String json = get(path);
        List<RecurringCandidate> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) {
            out.add(new RecurringCandidate(
                    strField(obj, "kind"),
                    strField(obj, "partyId"),
                    strField(obj, "partyNif"),
                    strField(obj, "partyName"),
                    decField(obj, "totalAmount"),
                    intField(obj, "occurrences"),
                    localDateField(obj, "firstDate"),
                    localDateField(obj, "lastDate"),
                    strField(obj, "sampleInvoiceId"),
                    strField(obj, "suggestedFrequency")));
        }
        return out;
    }

    /**
     * Crea una tarea recurrente. El cuerpo se construye en el caller
     * porque cada {@code kind} (SALES_INVOICE, PURCHASE, JOURNAL_ENTRY)
     * lleva un payload distinto y no merece la pena reflejar toda esa
     * jerarquía en records UI. El JSON debe respetar el shape de
     * {@code RecurringTaskRequest} del backend.
     */
    public RecurringTask createRecurring(String jsonBody)
            throws IOException, InterruptedException {
        String json = postRaw("/accounting/recurring", jsonBody);
        return parseRecurring(json);
    }

    /**
     * Actualiza una tarea recurrente existente. Mismo shape que
     * {@link #createRecurring(String)}.
     */
    public RecurringTask updateRecurring(String id, String jsonBody)
            throws IOException, InterruptedException {
        String json = put("/accounting/recurring/" + id, jsonBody);
        return parseRecurring(json);
    }

    // ====================================================================
    //  REC-IGNORE — candidatos silenciados
    // ====================================================================

    /**
     * Silencia un candidato de recurrencia para que el detector no lo
     * vuelva a proponer hasta {@code ignoreUntil} (NULL = indefinido).
     */
    public com.benjagest.ui.model.RecurringCandidateIgnoredEntry ignoreRecurringCandidate(
            String kind, String partyNif, String partyName,
            java.math.BigDecimal totalAmount,
            java.time.LocalDate ignoreUntil, String reason)
            throws IOException, InterruptedException {
        String body = "{"
                + "\"kind\":" + jq(kind) + ","
                + "\"partyNif\":" + jq(partyNif == null ? "" : partyNif) + ","
                + "\"partyName\":" + jq(partyName == null ? "" : partyName) + ","
                + "\"totalAmount\":" + (totalAmount == null ? "0" : totalAmount.toPlainString()) + ","
                + "\"ignoreUntil\":" + (ignoreUntil == null ? "null" : jq(ignoreUntil.toString())) + ","
                + "\"reason\":" + (reason == null || reason.isBlank() ? "null" : jq(reason))
                + "}";
        String json = postRaw("/accounting/recurring/candidates/ignore", body);
        return mapIgnoredEntry(json);
    }

    public List<com.benjagest.ui.model.RecurringCandidateIgnoredEntry> listIgnoredRecurringCandidates()
            throws IOException, InterruptedException {
        String json = get("/accounting/recurring/candidates/ignored");
        List<com.benjagest.ui.model.RecurringCandidateIgnoredEntry> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) {
            out.add(mapIgnoredEntry(obj));
        }
        return out;
    }

    public void unignoreRecurringCandidate(String id) throws IOException, InterruptedException {
        delete("/accounting/recurring/candidates/ignored/" + id);
    }

    private static String jq(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private com.benjagest.ui.model.RecurringCandidateIgnoredEntry mapIgnoredEntry(String obj) {
        return new com.benjagest.ui.model.RecurringCandidateIgnoredEntry(
                strField(obj, "id"),
                strField(obj, "kind"),
                strField(obj, "partyNif"),
                strField(obj, "partyNameNorm"),
                decField(obj, "totalAmount"),
                localDateField(obj, "ignoreUntil"),
                strField(obj, "reason"),
                strField(obj, "createdAt"));
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

    /**
     * F1-BANCO-CUENTA — crea una cuenta bancaria (alias + IBAN + banco). El
     * resto de campos (572, divisa, saldo) los resuelve/deja el backend por
     * defecto: si no se asigna un 572 explícito, usa la 572 genérica de la
     * empresa al conciliar.
     */
    public AccountingModels.BankAccountView createBankAccount(
            String alias, String iban, String bankName)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        appendKV(b, "alias", alias, true);
        appendKV(b, "iban", iban, false);
        appendKV(b, "bankName", bankName, false);
        b.append("}");
        String json = postRaw("/accounting/bank-accounts", b.toString());
        return new AccountingModels.BankAccountView(
                strField(json, "id"), strField(json, "alias"), strField(json, "iban"),
                strField(json, "bankName"), strField(json, "currency"),
                bdField(json, "openingBalance"), boolField(json, "active"));
    }

    /** F1-BANCO-IGNORE — marca un movimiento como IGNORED (comisión sin factura). */
    public void ignoreMovement(String movementId, String reason)
            throws IOException, InterruptedException {
        String path = "/accounting/bank-movements/"
                + URLEncoder.encode(movementId, StandardCharsets.UTF_8) + "/ignore";
        if (reason != null && !reason.isBlank()) {
            path += "?reason=" + URLEncoder.encode(reason, StandardCharsets.UTF_8);
        }
        postRaw(path, "{}");
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
    //  Cierre de ejercicio (CONS-CIERRE)
    // ====================================================================

    /** Lista los cierres de ejercicio de la empresa (más reciente primero). */
    public List<com.benjagest.ui.model.FiscalYearCloseEntry> listYearCloses()
            throws IOException, InterruptedException {
        String json = get("/accounting/year-close");
        List<com.benjagest.ui.model.FiscalYearCloseEntry> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) out.add(mapClose(obj));
        return out;
    }

    /** Precalcula ingresos/gastos/resultado/IS estimado del ejercicio (deja PRE_CLOSE). */
    public com.benjagest.ui.model.FiscalYearCloseEntry precalculateYear(int year)
            throws IOException, InterruptedException {
        return mapClose(postRaw("/accounting/year-close/" + year + "/precalculate", "{}"));
    }

    /**
     * Vista previa del asiento de regularización (6x/7x → 129) sin crear
     * ningún asiento. Útil para enseñar el resultado antes de confirmar el cierre.
     */
    public com.benjagest.ui.model.RegularizationPreviewEntry previewRegularization(int year)
            throws IOException, InterruptedException {
        String json = get("/accounting/year-close/" + year + "/preview-regularization");
        return new com.benjagest.ui.model.RegularizationPreviewEntry(
                intField(json, "periodYear") == 0 ? year : intField(json, "periodYear"),
                decField(json, "expensesTotal"),
                decField(json, "incomesTotal"),
                decField(json, "resultAmount"));
    }

    /** Cierra el ejercicio aplicando el resultado a reservas/dividendos/pérdidas. */
    public com.benjagest.ui.model.FiscalYearCloseEntry closeYear(int year,
            BigDecimal reserves, BigDecimal dividends, BigDecimal losses, String notes)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        b.append("\"reservesAllocation\":").append(reserves == null ? "0" : reserves.toPlainString()).append(',');
        b.append("\"dividendsAllocation\":").append(dividends == null ? "0" : dividends.toPlainString()).append(',');
        b.append("\"accumulatedLossesAllocation\":").append(losses == null ? "0" : losses.toPlainString()).append(',');
        b.append("\"notes\":\"").append(escape(notes == null ? "" : notes)).append("\"}");
        return mapClose(put("/accounting/year-close/" + year + "/close", b.toString()));
    }

    /** Reabre un ejercicio cerrado (acción de OWNER, queda en auditoría). */
    public com.benjagest.ui.model.FiscalYearCloseEntry reopenYear(int year, String reason)
            throws IOException, InterruptedException {
        String body = "{\"reason\":\"" + escape(reason == null ? "" : reason) + "\"}";
        return mapClose(put("/accounting/year-close/" + year + "/reopen", body));
    }

    private com.benjagest.ui.model.FiscalYearCloseEntry mapClose(String obj) {
        return new com.benjagest.ui.model.FiscalYearCloseEntry(
                strField(obj, "id"),
                intField(obj, "periodYear"),
                strField(obj, "status"),
                decField(obj, "incomeTotal"),
                decField(obj, "expenseTotal"),
                decField(obj, "resultAmount"),
                decField(obj, "taxAmount"),
                decField(obj, "resultAfterTax"),
                decField(obj, "reservesAllocation"),
                decField(obj, "dividendsAllocation"),
                decField(obj, "accumulatedLossesAllocation"),
                strField(obj, "closedAt"),
                strField(obj, "reopenedAt"),
                strField(obj, "notes"));
    }

    // ====================================================================
    //  BANK-IMPORT / EXPORT-CONTABLE
    // ====================================================================

    /** Importa un extracto bancario (N43 o CSV) a una cuenta. */
    public AccountingModels.ImportResult importBankExtract(
            String bankAccountId, String format, String fileName, String content)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        appendKV(b, "bankAccountId", bankAccountId, true);
        appendKV(b, "format", format, false);
        appendKV(b, "fileName", fileName, false);
        appendKV(b, "content", content, false);
        b.append("}");
        String json = postRaw("/accounting/bank-imports", b.toString());
        return new AccountingModels.ImportResult(
                intField(json, "rowsTotal"), intField(json, "rowsImported"),
                intField(json, "rowsSkipped"), intField(json, "rowsAutoMatched"));
    }

    /**
     * F1-BANCO-REVIEW — filas de revisión de conciliación de una cuenta: cada
     * movimiento pendiente con su factura candidata, estado y pagos existentes.
     */
    public List<AccountingModels.BankReconcileRow> reconcileReview(String bankAccountId)
            throws IOException, InterruptedException {
        String json = get("/accounting/bank-movements/reconcile-review?bankAccountId="
                + URLEncoder.encode(bankAccountId, StandardCharsets.UTF_8));
        List<AccountingModels.BankReconcileRow> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) {
            List<AccountingModels.ExistingPayment> pays = new ArrayList<>();
            String arr = extractArrayField(obj, "existingPayments");
            if (arr != null) {
                for (String p : splitJsonArray(arr)) {
                    pays.add(new AccountingModels.ExistingPayment(
                            strField(p, "paySource"), localDateField(p, "payDate"),
                            bdField(p, "payAmount"), strField(p, "payMethod"),
                            intFieldOrNull(p, "payEntryNumber"), strField(p, "payReference")));
                }
            }
            out.add(new AccountingModels.BankReconcileRow(
                    strField(obj, "movementId"), localDateField(obj, "operationDate"),
                    strField(obj, "description"), strField(obj, "counterpartyName"),
                    bdField(obj, "amount"), strField(obj, "invoiceKind"),
                    strField(obj, "invoiceId"), strField(obj, "invoiceNumber"),
                    strField(obj, "invoiceCounterparty"), bdField(obj, "invoiceAmount"),
                    localDateField(obj, "invoiceDate"), strField(obj, "state"),
                    boolField(obj, "suggested"), pays));
        }
        return out;
    }

    /** F1-BANCO-REVIEW — concilia solo los movimientos marcados (éxito parcial). */
    public AccountingModels.ReconcileResult reconcileSelected(
            List<AccountingModels.BankReconcileRow> selected)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < selected.size(); i++) {
            AccountingModels.BankReconcileRow r = selected.get(i);
            if (i > 0) b.append(',');
            b.append("{");
            appendKV(b, "movementId", r.movementId(), true);
            appendKV(b, "invoiceKind", r.invoiceKind(), false);
            appendKV(b, "invoiceId", r.invoiceId(), false);
            b.append("}");
        }
        b.append("]");
        String json = postRaw("/accounting/bank-movements/reconcile", b.toString());
        return new AccountingModels.ReconcileResult(
                intField(json, "reconciled"), intField(json, "failed"));
    }

    /** Importación contable inversa (Contasol/A3/Sage/CSV) hacia un targetKind. */
    public AccountingModels.ImportResult importExternal(
            String format, String targetKind, String fileName, String content)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        appendKV(b, "format", format, true);
        appendKV(b, "targetKind", targetKind, false);
        appendKV(b, "fileName", fileName, false);
        appendKV(b, "content", content, false);
        b.append("}");
        String json = postRaw("/accounting/external-imports", b.toString());
        // OJO: el BatchResult del import contable usa total/imported/skipped/errors
        // (distinto del bancario). El 4º campo aquí son ERRORES, no auto-matched.
        return new AccountingModels.ImportResult(
                intField(json, "total"), intField(json, "imported"),
                intField(json, "skipped"), intField(json, "errors"));
    }

    /** IMP-H — Importación del diario histórico CONTENDO (reconstruye asientos,
     *  facturas de venta/gasto, terceros y cobros/pagos de un único fichero). */
    public AccountingModels.ContendoImportResult importContendo(
            String fileName, String content)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        appendKV(b, "fileName", fileName, true);
        appendKV(b, "content", content, false);
        b.append("}");
        String json = postRaw("/accounting/external-imports/contendo", b.toString());
        return new AccountingModels.ContendoImportResult(
                intField(json, "asientosTotal"), intField(json, "asientosImportados"),
                intField(json, "asientosSaltados"), intField(json, "facturasVenta"),
                intField(json, "rectificativas"), intField(json, "gastos"),
                intField(json, "clientesCreados"), intField(json, "proveedoresCreados"),
                intField(json, "cuentasCreadas"), intField(json, "cobrosVinculados"),
                intField(json, "pagosVinculados"), intField(json, "errores"),
                parseStringArray(extractArrayField(json, "avisos")));
    }

    /** Parsea un array JSON de strings simples (["a","b"]) respetando escapes. */
    private List<String> parseStringArray(String arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        boolean inString = false, escape = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (inString) {
                if (escape) {
                    switch (c) {
                        case 'n' -> cur.append('\n');
                        case 'r' -> cur.append('\r');
                        case 't' -> cur.append('\t');
                        default -> cur.append(c);
                    }
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inString = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inString = true;
            }
        }
        return out;
    }

    /** Exportación contable: devuelve el contenido (CSV/texto) del export. */
    public String exportAccounting(String format, String targetKind,
            LocalDate from, LocalDate to, boolean includeDrafts)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        append(q, "format", format);
        append(q, "targetKind", targetKind);
        if (from != null) append(q, "from", from.toString());
        if (to != null)   append(q, "to", to.toString());
        append(q, "includeDrafts", String.valueOf(includeDrafts));
        return get("/accounting/exports" + q);
    }

    // ====================================================================
    //  REPORTS-UI — Mayor, Sumas y Saldos, Balance de Situación, PyG
    // ====================================================================

    /** Libro Mayor de una cuenta (saldo apertura + movimientos + saldo final). */
    public AccountingModels.LedgerView ledger(String accountId, LocalDate from, LocalDate to)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        if (from != null) append(q, "from", from.toString());
        if (to != null)   append(q, "to", to.toString());
        String json = get("/accounting/ledger/"
                + URLEncoder.encode(accountId, StandardCharsets.UTF_8) + q);
        List<AccountingModels.LedgerLineView> moves = new ArrayList<>();
        String arr = extractArrayField(json, "movements");
        if (arr != null) {
            for (String o : splitJsonArray(arr)) {
                moves.add(new AccountingModels.LedgerLineView(
                        strField(o, "entryId"), intField(o, "entryNumber"),
                        localDateField(o, "entryDate"), strField(o, "concept"),
                        strField(o, "status"), strField(o, "lineDescription"),
                        bdField(o, "debit"), bdField(o, "credit"), bdField(o, "runningBalance")));
            }
        }
        return new AccountingModels.LedgerView(
                strField(json, "accountId"), strField(json, "accountCode"),
                strField(json, "accountName"), bdField(json, "openingBalance"),
                bdField(json, "closingBalance"), moves);
    }

    /** Balance de Sumas y Saldos (debe/haber/saldo por cuenta). */
    public List<AccountingModels.TrialBalanceRow> trialBalance(LocalDate from, LocalDate to, String prefix)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        if (from != null) append(q, "from", from.toString());
        if (to != null)   append(q, "to", to.toString());
        if (prefix != null && !prefix.isBlank()) append(q, "prefix", prefix);
        String json = get("/accounting/balance" + q);
        List<AccountingModels.TrialBalanceRow> out = new ArrayList<>();
        for (String o : splitJsonArray(json)) {
            out.add(new AccountingModels.TrialBalanceRow(
                    strField(o, "accountId"), strField(o, "code"), strField(o, "name"),
                    bdField(o, "totalDebit"), bdField(o, "totalCredit"),
                    bdField(o, "saldoDeudor"), bdField(o, "saldoAcreedor")));
        }
        return out;
    }

    /** Balance de Situación a una fecha. */
    public AccountingModels.BalanceSheetView balanceSheet(LocalDate asOf)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        if (asOf != null) append(q, "asOf", asOf.toString());
        String json = get("/accounting/reports/balance-sheet" + q);
        return new AccountingModels.BalanceSheetView(
                localDateField(json, "asOf"),
                parseReportSections(extractArrayField(json, "activo")), bdField(json, "totalActivo"),
                parseReportSections(extractArrayField(json, "pasivo")), bdField(json, "totalPasivo"));
    }

    /** Cuenta de Pérdidas y Ganancias de un periodo. */
    public AccountingModels.ProfitAndLossView profitAndLoss(LocalDate from, LocalDate to)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        if (from != null) append(q, "from", from.toString());
        if (to != null)   append(q, "to", to.toString());
        String json = get("/accounting/reports/profit-and-loss" + q);
        return new AccountingModels.ProfitAndLossView(
                localDateField(json, "from"), localDateField(json, "to"),
                parseReportSections(extractArrayField(json, "ingresos")), bdField(json, "totalIngresos"),
                parseReportSections(extractArrayField(json, "gastos")), bdField(json, "totalGastos"),
                bdField(json, "resultadoExplotacion"));
    }

    /** ECPN — Estado de Cambios en el Patrimonio Neto de un periodo. */
    public List<AccountingModels.EquityMovementRow> equityChanges(LocalDate from, LocalDate to)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        if (from != null) append(q, "from", from.toString());
        if (to != null)   append(q, "to", to.toString());
        String json = get("/accounting/reports/equity-changes" + q);
        List<AccountingModels.EquityMovementRow> out = new ArrayList<>();
        for (String o : splitJsonArray(json)) {
            out.add(new AccountingModels.EquityMovementRow(
                    strField(o, "code"), strField(o, "name"),
                    bdField(o, "openingBalance"), bdField(o, "closingBalance"),
                    bdField(o, "variation")));
        }
        return out;
    }

    /** Parsea las secciones (masas) anidadas de un informe Balance/PyG. */
    private List<AccountingModels.ReportSection> parseReportSections(String arr) {
        List<AccountingModels.ReportSection> out = new ArrayList<>();
        if (arr == null) return out;
        for (String sec : splitJsonArray(arr)) {
            List<AccountingModels.ReportItem> items = new ArrayList<>();
            String itemsArr = extractArrayField(sec, "items");
            if (itemsArr != null) {
                for (String it : splitJsonArray(itemsArr)) {
                    items.add(new AccountingModels.ReportItem(
                            strField(it, "code"), strField(it, "name"), bdField(it, "amount")));
                }
            }
            out.add(new AccountingModels.ReportSection(
                    strField(sec, "name"), items, bdField(sec, "total")));
        }
        return out;
    }

    // ====================================================================
    //  ACC-TEMPLATES — Plantillas de asiento manual recurrente
    // ====================================================================

    /** Lista las plantillas de la empresa (opcionalmente por categoría / solo activas). */
    public List<AccountingModels.EntryTemplate> listTemplates(String category, Boolean activeOnly)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        if (category != null && !category.isBlank()) append(q, "category", category);
        if (activeOnly != null) append(q, "active", activeOnly.toString());
        String json = get("/accounting/templates" + q);
        List<AccountingModels.EntryTemplate> out = new ArrayList<>();
        for (String obj : splitJsonArray(json)) out.add(parseTemplate(obj));
        return out;
    }

    public AccountingModels.EntryTemplate createTemplate(AccountingModels.EntryTemplate tpl)
            throws IOException, InterruptedException {
        return parseTemplate(postRaw("/accounting/templates", buildTemplateRequest(tpl)));
    }

    public AccountingModels.EntryTemplate updateTemplate(String id, AccountingModels.EntryTemplate tpl)
            throws IOException, InterruptedException {
        return parseTemplate(put("/accounting/templates/" + id, buildTemplateRequest(tpl)));
    }

    /** Archiva (desactiva) la plantilla. El backend hace setActive(false). */
    public void archiveTemplate(String id) throws IOException, InterruptedException {
        delete("/accounting/templates/" + id);
    }

    /**
     * Aplica la plantilla generando un asiento (DRAFT o POSTED según postNow).
     * {@code variables} mapea variableName → importe para las líneas
     * VARIABLE/FORMULA. Devuelve el id del asiento creado.
     */
    public String applyTemplate(String templateId, LocalDate entryDate, String concept,
                                 java.util.Map<String, BigDecimal> variables, boolean postNow)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        appendKV(b, "entryDate", entryDate == null ? null : entryDate.toString(), true);
        appendKV(b, "concept", concept, false);
        b.append(",\"postNow\":").append(postNow);
        b.append(",\"variables\":{");
        if (variables != null) {
            boolean first = true;
            for (var e : variables.entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append('"').append(escape(e.getKey())).append("\":")
                        .append(e.getValue() == null ? "0" : e.getValue().toPlainString());
            }
        }
        b.append("}}");
        String json = postRaw("/accounting/templates/" + templateId + "/apply", b.toString());
        return strField(json, "id");
    }

    private AccountingModels.EntryTemplate parseTemplate(String json) {
        List<AccountingModels.EntryTemplateLine> lines = new ArrayList<>();
        String arr = extractArrayField(json, "lines");
        if (arr != null) {
            for (String o : splitJsonArray(arr)) {
                lines.add(new AccountingModels.EntryTemplateLine(
                        strField(o, "accountCode"), strField(o, "description"),
                        strField(o, "side"), strField(o, "amountKind"),
                        decField(o, "fixedAmount"), strField(o, "formula"),
                        strField(o, "variableName")));
            }
        }
        return new AccountingModels.EntryTemplate(
                strField(json, "id"), strField(json, "code"), strField(json, "name"),
                strField(json, "category"), strField(json, "defaultConcept"),
                strField(json, "description"), boolField(json, "active"),
                intField(json, "timesUsed"), strField(json, "lastUsedAt"), lines);
    }

    private String buildTemplateRequest(AccountingModels.EntryTemplate tpl) {
        StringBuilder b = new StringBuilder("{");
        appendKV(b, "code", tpl.code(), true);
        appendKV(b, "name", tpl.name(), false);
        appendKV(b, "category", tpl.category(), false);
        appendKV(b, "defaultConcept", tpl.defaultConcept(), false);
        appendKV(b, "description", tpl.description(), false);
        b.append(",\"lines\":[");
        List<AccountingModels.EntryTemplateLine> lines = tpl.lines();
        for (int i = 0; i < lines.size(); i++) {
            AccountingModels.EntryTemplateLine l = lines.get(i);
            if (i > 0) b.append(',');
            b.append("{");
            appendKV(b, "accountCode", l.accountCode(), true);
            appendKV(b, "description", l.description(), false);
            appendKV(b, "side", l.side(), false);
            appendKV(b, "amountKind", l.amountKind(), false);
            b.append(",\"fixedAmount\":")
                    .append(l.fixedAmount() == null ? "null" : l.fixedAmount().toPlainString());
            appendKV(b, "formula", l.formula(), false);
            appendKV(b, "variableName", l.variableName(), false);
            b.append("}");
        }
        b.append("]}");
        return b.toString();
    }

    // ====================================================================
    //  FIN-1 — Cuadro de mando financiero del cliente
    // ====================================================================

    public AccountingModels.ClientFinancials clientFinancials(LocalDate from, LocalDate to)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        append(q, "from", from.toString());
        append(q, "to", to.toString());
        String json = get("/accounting/financials" + q);
        return new AccountingModels.ClientFinancials(
                from, to,
                decField(json, "income"), decField(json, "expenses"), decField(json, "result"),
                decField(json, "personnelCost"), decField(json, "vatCharged"),
                decField(json, "vatBorne"), decField(json, "model303Estimated"),
                decField(json, "pendingCollections"), intField(json, "overdueInvoices"),
                decField(json, "pendingPayments"),
                decField(json, "marginPct"), decField(json, "expenseRatioPct"),
                decField(json, "personnelRatioPct"), intField(json, "draftCount"));
    }

    public List<AccountingModels.MonthPoint> financialsMonthly(int year)
            throws IOException, InterruptedException {
        String json = get("/accounting/financials/monthly?year=" + year);
        List<AccountingModels.MonthPoint> out = new ArrayList<>();
        for (String o : splitJsonArray(json)) {
            out.add(new AccountingModels.MonthPoint(
                    intField(o, "month"), decField(o, "income"),
                    decField(o, "expenses"), decField(o, "result")));
        }
        return out;
    }

    public AccountingModels.ClosingProjection financialsProjection(int year)
            throws IOException, InterruptedException {
        String json = get("/accounting/financials/projection?year=" + year);
        return new AccountingModels.ClosingProjection(
                intField(json, "year"), intField(json, "monthsElapsed"),
                decField(json, "resultToDate"), decField(json, "projectedResult"),
                decField(json, "estimatedCorporateTax"), decField(json, "projectedAfterTax"));
    }

    /** FIN-5 — descarga el PDF del cuadro de mando (bytes). */
    public byte[] financialsPdf(LocalDate from, LocalDate to, int year)
            throws IOException, InterruptedException {
        return getBytes("/accounting/financials/export.pdf?from=" + from
                + "&to=" + to + "&year=" + year);
    }

    /** ME-2 — facturas pendientes del tercero de una cuenta (43x/40x). */
    public List<AccountingModels.OpenInvoice> openInvoicesForAccount(String accountCode)
            throws IOException, InterruptedException {
        String json = get("/accounting/assist/open-invoices?accountCode="
                + java.net.URLEncoder.encode(accountCode, java.nio.charset.StandardCharsets.UTF_8));
        List<AccountingModels.OpenInvoice> out = new ArrayList<>();
        for (String o : splitJsonArray(json)) {
            out.add(new AccountingModels.OpenInvoice(
                    strField(o, "kind"), strField(o, "number"), localDateField(o, "date"),
                    decField(o, "total"), decField(o, "pending")));
        }
        return out;
    }

    /** ME-3 — cuentas sugeridas para una cuenta ancla (histórico + reglas). */
    public List<AccountingModels.SuggestedAccount> suggestAccounts(String anchor, List<String> exclude)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder("/accounting/assist/suggest?anchor=");
        q.append(java.net.URLEncoder.encode(anchor, java.nio.charset.StandardCharsets.UTF_8));
        if (exclude != null && !exclude.isEmpty()) {
            q.append("&exclude=").append(java.net.URLEncoder.encode(
                    String.join(",", exclude), java.nio.charset.StandardCharsets.UTF_8));
        }
        String json = get(q.toString());
        List<AccountingModels.SuggestedAccount> out = new ArrayList<>();
        for (String o : splitJsonArray(json)) {
            out.add(new AccountingModels.SuggestedAccount(
                    strField(o, "code"), strField(o, "name"),
                    intField(o, "score"), strField(o, "reason")));
        }
        return out;
    }

    /** Export PDF del Libro Mayor de una cuenta. */
    public byte[] ledgerPdf(String accountId, LocalDate from, LocalDate to)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        if (from != null) append(q, "from", from.toString());
        if (to != null)   append(q, "to", to.toString());
        return getBytes("/accounting/ledger/"
                + URLEncoder.encode(accountId, StandardCharsets.UTF_8) + "/export.pdf" + q);
    }

    /** Export PDF del Balance de Sumas y Saldos. */
    public byte[] trialBalancePdf(LocalDate from, LocalDate to, String prefix)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        if (from != null) append(q, "from", from.toString());
        if (to != null)   append(q, "to", to.toString());
        if (prefix != null && !prefix.isBlank()) append(q, "prefix", prefix);
        return getBytes("/accounting/balance/export.pdf" + q);
    }

    /** Export PDF del Balance de Situación. */
    public byte[] balanceSheetPdf(LocalDate asOf) throws IOException, InterruptedException {
        return getBytes("/accounting/reports/balance-sheet/export.pdf?asOf=" + asOf);
    }

    /** Export PDF de la cuenta de Pérdidas y Ganancias. */
    public byte[] profitAndLossPdf(LocalDate from, LocalDate to)
            throws IOException, InterruptedException {
        return getBytes("/accounting/reports/profit-and-loss/export.pdf?from=" + from + "&to=" + to);
    }

    // ====================================================================
    //  HTTP helpers
    // ====================================================================

    // ====================================================================
    //  COMP- Compensación (netting) de facturas
    // ====================================================================

    /** Propuestas de compensación (terceros con ventas y compras pendientes). */
    public List<AccountingModels.CompensationProposal> listCompensationSuggestions()
            throws IOException, InterruptedException {
        String json = get("/accounting/compensation/suggestions");
        List<AccountingModels.CompensationProposal> out = new ArrayList<>();
        for (String o : splitJsonArray(json)) {
            out.add(new AccountingModels.CompensationProposal(
                    strField(o, "nif"), strField(o, "counterpartyName"),
                    bdField(o, "salesPending"), bdField(o, "purchasePending"),
                    bdField(o, "compensable")));
        }
        return out;
    }

    /** Facturas pendientes (ventas + compras) de un tercero por NIF. */
    public List<AccountingModels.CompensationInvoice> listCompensationInvoices(String nif)
            throws IOException, InterruptedException {
        String json = get("/accounting/compensation/invoices?nif="
                + URLEncoder.encode(nif == null ? "" : nif, StandardCharsets.UTF_8));
        List<AccountingModels.CompensationInvoice> out = new ArrayList<>();
        for (String o : splitJsonArray(json)) {
            out.add(new AccountingModels.CompensationInvoice(
                    strField(o, "invoiceKind"), strField(o, "invoiceId"),
                    strField(o, "invoiceNumber"), localDateField(o, "invoiceDate"),
                    bdField(o, "total"), bdField(o, "pending"), boolField(o, "due")));
        }
        return out;
    }

    /** Ejecuta una compensación (asiento 400/430 + saldado). */
    public AccountingModels.CompensationExecResult executeCompensation(
            List<String> salesIds, List<String> purchaseIds)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{\"salesInvoiceIds\":");
        appendIdArray(b, salesIds);
        b.append(",\"purchaseInvoiceIds\":");
        appendIdArray(b, purchaseIds);
        b.append("}");
        String json = postRaw("/accounting/compensation/execute", b.toString());
        return new AccountingModels.CompensationExecResult(
                strField(json, "compensationId"), intField(json, "entryNumber"),
                bdField(json, "compensated"));
    }

    /** Compensaciones ya ejecutadas. */
    public List<AccountingModels.CompensationRow> listCompensations()
            throws IOException, InterruptedException {
        String json = get("/accounting/compensation/list");
        List<AccountingModels.CompensationRow> out = new ArrayList<>();
        for (String o : splitJsonArray(json)) {
            out.add(new AccountingModels.CompensationRow(
                    strField(o, "id"), strField(o, "nif"), strField(o, "counterpartyName"),
                    localDateField(o, "date"), bdField(o, "amount"), strField(o, "status"),
                    intField(o, "entryNumber")));
        }
        return out;
    }

    /** Revierte una compensación (contraasiento + facturas a pendiente). */
    public void reverseCompensation(String id) throws IOException, InterruptedException {
        postRaw("/accounting/compensation/" + id + "/reverse", "");
    }

    /** Justificante PDF (acuerdo de compensación). */
    public byte[] compensationPdf(String id) throws IOException, InterruptedException {
        return getBytes("/accounting/compensation/" + id + "/pdf");
    }

    private static void appendIdArray(StringBuilder b, List<String> ids) {
        b.append('[');
        if (ids != null) {
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) b.append(',');
                b.append('"').append(ids.get(i)).append('"');
            }
        }
        b.append(']');
    }

    private String get(String path) throws IOException, InterruptedException {
        return executeWithRetry(() -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15)).GET();
            AuthSession.get().authorize(b);
            return b;
        });
    }

    /** GET que devuelve bytes raw (PDFs / imágenes). Sin refresh retry porque
     *  el handler de body de bytes no es compatible con el wrapper String. */
    private byte[] getBytes(String path) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30)).GET();
        AuthSession.get().authorize(b);
        HttpResponse<byte[]> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
        return r.body();
    }

    private String postRaw(String path, String body) throws IOException, InterruptedException {
        return executeWithRetry(() -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            AuthSession.get().authorize(b);
            return b;
        });
    }

    private String put(String path, String body) throws IOException, InterruptedException {
        return executeWithRetry(() -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body));
            AuthSession.get().authorize(b);
            return b;
        });
    }

    private void delete(String path) throws IOException, InterruptedException {
        executeWithRetry(() -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15)).DELETE();
            AuthSession.get().authorize(b);
            return b;
        });
    }

    /**
     * Ejecuta la petición y, si recibe 401/403 indicativo de sesión
     * expirada, intenta UN refresh transparente y reintenta una vez.
     * Si la segunda intentona también falla, propaga.
     */
    @FunctionalInterface
    private interface RequestSupplier { HttpRequest.Builder build(); }

    private String executeWithRetry(RequestSupplier supplier) throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= 2; attempt++) {
            HttpRequest.Builder b = supplier.build();
            HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
            try {
                checkAuth(r);
            } catch (RetryAfterRefreshException retry) {
                if (attempt == 1) continue; // reconstruye con nuevo token
                throw new SessionExpiredException("HTTP " + r.statusCode());
            }
            if (r.statusCode() < 200 || r.statusCode() >= 300) {
                throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
            }
            return r.body();
        }
        throw new SessionExpiredException("retry exhausted");
    }

    /**
     * Detecta 401/403 sin mensaje específico (JWT expirado vs sin permisos).
     * En caso de sesión, intenta UN refresh silencioso del access token
     * usando el refreshToken. Si el refresh va OK, lanza
     * {@link RetryAfterRefreshException} para que el caller reintente.
     * Si no, lanza {@link SessionExpiredException}.
     */
    private void checkAuth(HttpResponse<String> r) {
        if (r.statusCode() == 401 || isSessionExpired403(r)) {
            // Intento refresh transparente: si el refreshToken sigue vivo,
            // el caller puede repetir la petición sin que el usuario se
            // entere de nada.
            if (tryRefreshAccessToken()) {
                throw new RetryAfterRefreshException();
            }
            throw new SessionExpiredException("HTTP " + r.statusCode());
        }
    }

    private boolean isSessionExpired403(HttpResponse<String> r) {
        if (r.statusCode() != 403) return false;
        String body = r.body() == null ? "" : r.body();
        // Si el cuerpo trae un mensaje específico de permisos/módulo lo
        // dejamos pasar (es un 403 real, no expiración).
        if (body.contains("rol")
                || body.contains("modulo")
                || body.contains("módulo")
                || body.contains("role")
                || body.contains("module")
                || body.contains("permiso")) {
            return false;
        }
        return true;
    }

    /**
     * Llama a {@code POST /api/auth/refresh} con el refreshToken actual.
     * Si va OK, actualiza AuthSession con el nuevo access token y devuelve
     * true. Si falla, devuelve false (la sesión está realmente caducada).
     */
    private boolean tryRefreshAccessToken() {
        String refresh = AuthSession.get().refreshToken();
        if (refresh == null || refresh.isBlank()) return false;
        try {
            String body = "{\"refreshToken\":\"" + refresh + "\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/refresh"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() < 200 || r.statusCode() >= 300) return false;
            // Extraer nuevo accessToken del JSON (parser simple).
            String json = r.body();
            int i = json.indexOf("\"accessToken\"");
            if (i < 0) return false;
            int s = json.indexOf('"', i + 14);
            int e = json.indexOf('"', s + 1);
            if (s < 0 || e < 0) return false;
            String newToken = json.substring(s + 1, e);
            // Actualiza solo el accessToken — el resto del estado sigue igual.
            // (AuthSession no expone setAccessToken; usamos el método clear+update
            // sólo si no hay alternativa. Aquí leemos campos actuales y los
            // reescribimos con el accessToken nuevo.)
            AuthSession s2 = AuthSession.get();
            s2.update(newToken, refresh,
                    s2.userId(), s2.userDisplayName(), s2.userEmail(),
                    s2.globalRole(),
                    s2.activeCompanyId(), s2.activeCompanyLegalName(),
                    s2.activeCompanyType(), s2.roleInActiveCompany(),
                    s2.memberships());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** Excepción interna para indicar al wrapper que reintente. */
    private static class RetryAfterRefreshException extends RuntimeException {}

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
                intField(json, "numLines"),
                strField(json, "sourcePdfSha256"));
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
                boolField(json, "active"),
                strField(json, "payloadJson"),
                boolField(json, "createdByAdvisor"));
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
                // accountCode permite al backend resolver por código y, si
                // empieza por 4000/4300 y no existe, auto-crear la
                // sub-cuenta de tercero a partir del concepto del asiento.
                appendKV(b, "accountCode", l.accountCode(), false);
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
