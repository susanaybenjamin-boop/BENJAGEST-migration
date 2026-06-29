package com.benjagest.ui.service;

import com.benjagest.ui.model.CompanyGroupEntry;
import com.benjagest.ui.model.CompanyRef;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** CONSOL-1 — Cliente de /api/company-groups (grupos empresariales). */
public class ConsolidationApiClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";
    private final HttpClient httpClient;
    private final String base;

    public ConsolidationApiClient() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.base = apiBaseUrl().replaceAll("/+$", "") + "/company-groups";
    }

    public List<CompanyGroupEntry> listGroups() throws IOException, InterruptedException {
        String body = send(get(base));
        List<CompanyGroupEntry> out = new ArrayList<>();
        for (String o : objects(body)) {
            out.add(new CompanyGroupEntry(textField(o, "id"), textField(o, "name"),
                    (int) longField(o, "memberCount")));
        }
        return out;
    }

    public List<CompanyRef> listCandidates() throws IOException, InterruptedException {
        return refs(send(get(base + "/candidates")));
    }

    public List<CompanyRef> listMembers(String groupId) throws IOException, InterruptedException {
        return refs(send(get(base + "/" + groupId + "/members")));
    }

    public void createGroup(String name) throws IOException, InterruptedException {
        send(post(base, "{\"name\":\"" + escape(name) + "\"}"));
    }

    public void deleteGroup(String groupId) throws IOException, InterruptedException {
        send(delete(base + "/" + groupId));
    }

    public void addMember(String groupId, String companyId) throws IOException, InterruptedException {
        send(post(base + "/" + groupId + "/members", "{\"companyId\":\"" + escape(companyId) + "\"}"));
    }

    public void removeMember(String groupId, String companyId) throws IOException, InterruptedException {
        send(delete(base + "/" + groupId + "/members/" + companyId));
    }

    /** CONSOL-2 — balance de comprobación agregado del grupo a una fecha. */
    public com.benjagest.ui.model.ConsolidatedBalance trialBalance(String groupId, String asOf)
            throws IOException, InterruptedException {
        String url = base + "/" + groupId + "/trial-balance" + (asOf == null ? "" : "?asOf=" + asOf);
        String body = send(get(url));
        java.util.List<com.benjagest.ui.model.ConsolidatedBalance.TrialLine> lines = new ArrayList<>();
        for (String o : objects(body)) {
            lines.add(new com.benjagest.ui.model.ConsolidatedBalance.TrialLine(
                    textField(o, "code"), textField(o, "name"),
                    decimalField(o, "debit"), decimalField(o, "credit")));
        }
        return new com.benjagest.ui.model.ConsolidatedBalance(
                textField(body, "asOf"), lines,
                decimalField(body, "totalDebit"), decimalField(body, "totalCredit"),
                decimalField(body, "resultado"), (int) longField(body, "companyCount"));
    }

    /** CONSOL-4 — PDF del balance consolidado. */
    public byte[] consolidatedPdf(String groupId, String asOf) throws IOException, InterruptedException {
        String url = base + "/" + groupId + "/consolidated.pdf" + (asOf == null ? "" : "?asOf=" + asOf);
        HttpResponse<byte[]> r = httpClient.send(get(url).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
        return r.body();
    }

    private java.math.BigDecimal decimalField(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|-?[0-9]+(?:\\.[0-9]+)?)").matcher(json);
        if (!m.find() || "null".equals(m.group(1))) return java.math.BigDecimal.ZERO;
        try { return new java.math.BigDecimal(m.group(1)); } catch (NumberFormatException e) { return java.math.BigDecimal.ZERO; }
    }

    /** Devuelve el contenido de un array JSON de nivel superior: ...,"field":[ AQUÍ ],... */
    private String arraySlice(String json, String field) {
        int i = json.indexOf("\"" + field + "\"");
        if (i < 0) return "";
        int open = json.indexOf('[', i);
        if (open < 0) return "";
        int close = json.indexOf(']', open);
        return close < 0 ? "" : json.substring(open + 1, close);
    }

    /** CONSOL-3 — balance consolidado (agregado con eliminaciones intragrupo). */
    public com.benjagest.ui.model.ConsolidatedResult consolidated(String groupId, String asOf)
            throws IOException, InterruptedException {
        String url = base + "/" + groupId + "/consolidated" + (asOf == null ? "" : "?asOf=" + asOf);
        String body = send(get(url));
        java.util.List<com.benjagest.ui.model.ConsolidatedBalance.TrialLine> lines = new ArrayList<>();
        for (String o : objects(arraySlice(body, "lines"))) {
            lines.add(new com.benjagest.ui.model.ConsolidatedBalance.TrialLine(
                    textField(o, "code"), textField(o, "name"),
                    decimalField(o, "debit"), decimalField(o, "credit")));
        }
        java.util.List<com.benjagest.ui.model.ConsolidatedResult.EliminationRow> elims = new ArrayList<>();
        for (String o : objects(arraySlice(body, "eliminations"))) {
            elims.add(new com.benjagest.ui.model.ConsolidatedResult.EliminationRow(
                    textField(o, "companyName"), textField(o, "code"), textField(o, "name"),
                    textField(o, "terceroNif"), textField(o, "terceroType"), decimalField(o, "balance")));
        }
        return new com.benjagest.ui.model.ConsolidatedResult(
                textField(body, "asOf"), lines,
                decimalField(body, "totalDebit"), decimalField(body, "totalCredit"),
                decimalField(body, "resultado"), (int) longField(body, "companyCount"),
                elims, decimalField(body, "receivablesEliminated"), decimalField(body, "payablesEliminated"));
    }

    // ---- helpers HTTP ----
    private HttpRequest.Builder get(String url) { return auth(HttpRequest.newBuilder(URI.create(url)).GET()); }
    private HttpRequest.Builder delete(String url) { return auth(HttpRequest.newBuilder(URI.create(url)).DELETE()); }
    private HttpRequest.Builder post(String url, String json) {
        return auth(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }
    private HttpRequest.Builder auth(HttpRequest.Builder b) {
        b.timeout(Duration.ofSeconds(8));
        AuthSession.get().authorize(b);
        return b;
    }
    private String send(HttpRequest.Builder b) throws IOException, InterruptedException {
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
        return r.body();
    }

    private List<CompanyRef> refs(String body) {
        List<CompanyRef> out = new ArrayList<>();
        for (String o : objects(body)) {
            out.add(new CompanyRef(textField(o, "companyId"), textField(o, "legalName"),
                    textField(o, "taxIdentifier")));
        }
        return out;
    }

    // ---- parse (objetos planos) ----
    private List<String> objects(String json) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(json == null ? "" : json);
        while (m.find()) out.add(m.group());
        return out;
    }
    private String textField(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|\"((?:\\\\.|[^\"])*)\")").matcher(json);
        if (!m.find() || "null".equals(m.group(1))) return "";
        return m.group(2).replace("\\\"", "\"").replace("\\\\", "\\");
    }
    private long longField(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?[0-9]+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }
    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    private static String apiBaseUrl() {
        return System.getenv().getOrDefault("BENJAGEST_API_BASE_URL", DEFAULT_API_BASE_URL);
    }
}
