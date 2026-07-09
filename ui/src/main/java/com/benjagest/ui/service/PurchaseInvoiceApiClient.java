package com.benjagest.ui.service;

import com.benjagest.ui.model.PurchaseInvoiceEntry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente HTTP para /api/purchases/invoices (PURCHASES-PERSIST).
 *
 * Notable: el método {@link #save(SavePayload)} maneja
 * explícitamente HTTP 409 Conflict devolviendo {@link SaveOutcome} con
 * el flag {@code duplicate=true} y la factura ya existente en BD para
 * que la UI pueda redirigir al detalle en lugar de mostrar un error.
 */
public class PurchaseInvoiceApiClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String baseUrl;

    public PurchaseInvoiceApiClient() {
        this(System.getenv().getOrDefault("BENJAGEST_API_BASE_URL", DEFAULT_API_BASE_URL));
    }

    public PurchaseInvoiceApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public SaveOutcome save(SavePayload p) throws IOException, InterruptedException {
        StringBuilder json = new StringBuilder("{");
        appendStr(json, "supplierNif", p.supplierNif, true);
        appendStr(json, "supplierName", p.supplierName, false);
        appendStr(json, "invoiceNumber", p.invoiceNumber, false);
        if (p.invoiceDate != null) appendStr(json, "invoiceDate", p.invoiceDate.toString(), false);
        appendNum(json, "baseAmount", p.baseAmount);
        appendNum(json, "vatPercent", p.vatPercent);
        appendNum(json, "vatAmount", p.vatAmount);
        appendNum(json, "totalAmount", p.totalAmount);
        appendStr(json, "documentSha256", p.documentSha256, false);
        json.append(",\"invoiceIndexInPdf\":").append(p.invoiceIndexInPdf);
        appendStr(json, "concept", p.concept, false);
        appendStr(json, "notes", p.notes, false);
        appendStr(json, "expenseAccountCode", p.expenseAccountCode, false);
        json.append(",\"postJournalDirectly\":").append(p.postJournalDirectly);
        json.append("}");

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/purchases/invoices"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.toString()));
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 201) {
            return new SaveOutcome(parseOne(r.body()), false);
        }
        if (r.statusCode() == 409) {
            return new SaveOutcome(parseOne(r.body()), true);
        }
        throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
    }

    /**
     * GAS-2 — Registra el pago de un gasto (segundo asiento). Un 409 del
     * backend (ya pagado) llega como IOException con el mensaje en el body;
     * la UI lo muestra tal cual.
     */
    public void pay(String id, LocalDate paymentDate, String bankAccountCode)
            throws IOException, InterruptedException {
        StringBuilder json = new StringBuilder("{");
        json.append("\"paymentDate\":")
                .append(paymentDate == null ? "null" : "\"" + paymentDate + "\"");
        json.append(",\"bankAccountCode\":")
                .append(bankAccountCode == null ? "null" : "\"" + escape(bankAccountCode) + "\"");
        json.append("}");
        HttpRequest.Builder b = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/purchases/invoices/" + id + "/pay"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.toString()));
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException(r.body());
        }
    }

    /**
     * OPTYPE — clasificación fiscal de un gasto: [operationType,
     * vatDeductiblePercent, expenseDeductible("true"/"false"),
     * investmentGood("true"/"false")].
     */
    public String[] getClassification(String id) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/purchases/invoices/" + id + "/classification"))
                .timeout(Duration.ofSeconds(10)).GET();
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) throw new IOException(r.body());
        String j = r.body();
        java.math.BigDecimal pct = bdField(j, "vatDeductiblePercent");
        return new String[]{ strField(j, "operationType"),
                pct == null ? "100" : pct.toPlainString(),
                j.contains("\"expenseDeductible\":true") ? "true" : "false",
                j.contains("\"investmentGood\":true") ? "true" : "false" };
    }

    public void setClassification(String id, String operationType, String vatDeductiblePercent,
                                  boolean expenseDeductible, boolean investmentGood)
            throws IOException, InterruptedException {
        String body = "{\"operationType\":\"" + operationType + "\",\"vatDeductiblePercent\":"
                + (vatDeductiblePercent == null || vatDeductiblePercent.isBlank() ? "100" : vatDeductiblePercent)
                + ",\"expenseDeductible\":" + expenseDeductible
                + ",\"investmentGood\":" + investmentGood + "}";
        HttpRequest.Builder b = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/purchases/invoices/" + id + "/classification"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) throw new IOException(r.body());
    }

    /**
     * IRPF-DED — "Crear regla" desde un gasto: manda el proveedor de esta
     * factura a {@code accountCode} (reclasifica este asiento + aprende la
     * regla + sincroniza la deducibilidad IRPF).
     */
    public void createExpenseRule(String id, String accountCode)
            throws IOException, InterruptedException {
        String body = "{\"accountCode\":\"" + accountCode + "\"}";
        HttpRequest.Builder b = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/purchases/invoices/" + id + "/expense-rule"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) throw new IOException(r.body());
    }

    public List<PurchaseInvoiceEntry> list(Integer year, String status, String supplierNif)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        if (year != null) q.append(q.length() == 0 ? "?" : "&").append("year=").append(year);
        if (status != null && !status.isBlank())
            q.append(q.length() == 0 ? "?" : "&").append("status=")
                    .append(URLEncoder.encode(status, StandardCharsets.UTF_8));
        if (supplierNif != null && !supplierNif.isBlank())
            q.append(q.length() == 0 ? "?" : "&").append("supplierNif=")
                    .append(URLEncoder.encode(supplierNif, StandardCharsets.UTF_8));

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/purchases/invoices" + q))
                .timeout(Duration.ofSeconds(15)).GET();
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return parseList(r.body());
    }

    /**
     * Valida un lote de gastos DRAFT (multiselección). El servidor pasa
     * cada uno a POSTED y genera el asiento contable correspondiente.
     * Devuelve el JSON crudo del resumen — la UI lo parsea para mostrar.
     */
    public String validateBatch(List<String> ids) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{\"ids\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) body.append(',');
            body.append('"').append(ids.get(i)).append('"');
        }
        body.append("]}");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/purchases/invoices/validate-batch"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    public void deleteInvoice(String id) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/purchases/invoices/" + id))
                .timeout(Duration.ofSeconds(10)).DELETE();
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    private void appendStr(StringBuilder json, String key, String value, boolean first) {
        if (!first && json.length() > 1) json.append(",");
        else if (!first) json.append(",");
        if (first && json.length() > 1) json.append(",");
        if (value == null) {
            json.append("\"").append(key).append("\":null");
        } else {
            json.append("\"").append(key).append("\":\"").append(escape(value)).append("\"");
        }
    }

    private void appendNum(StringBuilder json, String key, BigDecimal value) {
        if (json.length() > 1) json.append(",");
        if (value == null) {
            json.append("\"").append(key).append("\":null");
        } else {
            json.append("\"").append(key).append("\":").append(value.toPlainString());
        }
    }

    private List<PurchaseInvoiceEntry> parseList(String arrayJson) {
        List<PurchaseInvoiceEntry> out = new ArrayList<>();
        if (arrayJson == null) return out;
        String s = arrayJson.trim();
        if (s.startsWith("{")) { out.add(parseOne(s)); return out; }
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
                    out.add(parseOne(s.substring(start, i + 1)));
                    start = -1;
                }
            }
        }
        return out;
    }

    private PurchaseInvoiceEntry parseOne(String json) {
        return new PurchaseInvoiceEntry(
                strField(json, "id"),
                strField(json, "supplierNif"),
                strField(json, "supplierName"),
                strField(json, "invoiceNumber"),
                localDateField(json, "invoiceDate"),
                bdField(json, "baseAmount"),
                bdField(json, "vatPercent"),
                bdField(json, "vatAmount"),
                bdField(json, "totalAmount"),
                strField(json, "documentSha256"),
                intField(json, "invoiceIndexInPdf"),
                strField(json, "status"),
                strField(json, "journalEntryId"),
                strField(json, "notes"),
                instantField(json, "createdAt")
        );
    }

    private String strField(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(json);
        if (!m.find()) return null;
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private BigDecimal bdField(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? new BigDecimal(m.group(1)) : null;
    }

    private int intField(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private LocalDate localDateField(String json, String key) {
        String v = strField(json, key);
        if (v == null || v.isBlank()) return null;
        try { return LocalDate.parse(v); } catch (Exception ex) { return null; }
    }

    private Instant instantField(String json, String key) {
        String v = strField(json, key);
        if (v == null || v.isBlank()) return null;
        try { return Instant.parse(v); } catch (Exception ex) { return null; }
    }

    private static String escape(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    // ====================================================================
    //  DTOs públicos
    // ====================================================================

    public static final class SavePayload {
        public String supplierNif;
        public String supplierName;
        public String invoiceNumber;
        public LocalDate invoiceDate;
        public BigDecimal baseAmount;
        public BigDecimal vatPercent;
        public BigDecimal vatAmount;
        public BigDecimal totalAmount;
        public String documentSha256;
        public int invoiceIndexInPdf;
        public String concept;
        public String notes;
        // GAS-1: cuenta de gasto (6xx) fijada por el usuario; null = cascada.
        public String expenseAccountCode;
        // GAS-7: TRUE en el alta manual -> asiento validado directo.
        public boolean postJournalDirectly;
    }

    public record SaveOutcome(PurchaseInvoiceEntry invoice, boolean duplicate) {}
}
