package com.benjagest.ui.service;

import com.benjagest.ui.model.DueDateEntry;
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

/**
 * PAGO-PROVEEDOR (PV-4) — cliente del API de vencimientos
 * ({@code /api/due-dates}). Mismo patrón compacto que el resto de clientes
 * de la UI (HttpClient + AuthSession).
 */
public class DueDateApiClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String baseUrl;

    public DueDateApiClient() {
        this(System.getenv().getOrDefault("BENJAGEST_API_BASE_URL", DEFAULT_API_BASE_URL));
    }

    public DueDateApiClient(String baseUrl) { this.baseUrl = baseUrl; }

    public List<DueDateEntry> list(String invoiceKind, String invoiceId)
            throws IOException, InterruptedException {
        String q = "?invoiceKind=" + enc(invoiceKind) + "&invoiceId=" + enc(invoiceId);
        return parseList(get("/due-dates" + q));
    }

    public List<DueDateEntry> replace(String invoiceKind, String invoiceId,
                                       List<DueDateReq> rows) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            DueDateReq r = rows.get(i);
            if (i > 0) body.append(',');
            body.append("{\"dueDate\":")
                    .append(r.dueDate() == null ? "null" : "\"" + r.dueDate() + "\"")
                    .append(",\"amount\":").append(r.amount() == null ? "0" : r.amount().toPlainString())
                    .append(",\"notes\":").append(jsonStr(r.notes()))
                    .append("}");
        }
        body.append("]");
        String q = "?invoiceKind=" + enc(invoiceKind) + "&invoiceId=" + enc(invoiceId);
        return parseList(send("PUT", "/due-dates" + q, body.toString()));
    }

    public DueDateEntry pay(String id, String treasuryAccountCode, LocalDate paidDate, String method)
            throws IOException, InterruptedException {
        return pay(id, treasuryAccountCode, paidDate, method, null);
    }

    /**
     * COB-3 — {@code concept} opcional: si es null/blanco el backend deja el
     * concepto automático ("Cobro vto. N" / "Pago vto. N").
     */
    public DueDateEntry pay(String id, String treasuryAccountCode, LocalDate paidDate,
                            String method, String concept)
            throws IOException, InterruptedException {
        String body = "{\"treasuryAccountCode\":" + jsonStr(treasuryAccountCode)
                + ",\"paidDate\":" + (paidDate == null ? "null" : "\"" + paidDate + "\"")
                + ",\"paymentMethod\":" + jsonStr(method)
                + ",\"concept\":" + jsonStr(concept) + "}";
        return parseOne(send("POST", "/due-dates/" + id + "/pay", body));
    }

    public DueDateEntry unpay(String id) throws IOException, InterruptedException {
        return parseOne(send("POST", "/due-dates/" + id + "/unpay", ""));
    }

    public record DueDateReq(LocalDate dueDate, BigDecimal amount, String notes) {}

    // ----- HTTP -----

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15)).GET();
        AuthSession.get().authorize(b);
        return exec(b);
    }

    private String send(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        AuthSession.get().authorize(b);
        return exec(b);
    }

    private String exec(HttpRequest.Builder b) throws IOException, InterruptedException {
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    // ----- parse -----

    private List<DueDateEntry> parseList(String json) {
        List<DueDateEntry> out = new ArrayList<>();
        for (String o : splitArray(json)) out.add(parseOne(o));
        return out;
    }

    private DueDateEntry parseOne(String o) {
        return new DueDateEntry(
                str(o, "id"), str(o, "invoiceId"), str(o, "invoiceKind"),
                intf(o, "seq"), date(o, "dueDate"), dec(o, "amount"),
                str(o, "status"), date(o, "paidDate"), str(o, "paymentMethod"),
                str(o, "treasuryAccountCode"), str(o, "journalEntryId"), str(o, "notes"));
    }

    private List<String> splitArray(String json) {
        List<String> out = new ArrayList<>();
        if (json == null) return out;
        String s = json.trim();
        if (s.startsWith("{")) { out.add(s); return out; }
        if (!s.startsWith("[")) return out;
        int depth = 0, start = -1; boolean inStr = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && start >= 0) { out.add(s.substring(start, i + 1)); start = -1; } }
        }
        return out;
    }

    private String str(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    private int intf(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?[0-9]+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private BigDecimal dec(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)").matcher(json);
        return m.find() ? new BigDecimal(m.group(1)) : BigDecimal.ZERO;
    }

    private LocalDate date(String json, String key) {
        String v = str(json, key);
        if (v == null || v.isBlank()) return null;
        try { return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v); }
        catch (Exception e) { return null; }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String jsonStr(String v) {
        if (v == null) return "null";
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
