package com.benjagest.ui.service;

import com.benjagest.ui.model.CertificateInspectInfo;
import com.benjagest.ui.model.CertificateSummaryEntry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente HTTP para /api/certificates. Suficiente regex JSON para
 * mantener el patrón del resto de ApiClients (sin Jackson en UI).
 *
 * Tres operaciones:
 *   - list(): tabla de certs del tenant actual.
 *   - inspect(base64, password): metadatos sin persistir.
 *   - upload(...): persiste el .p12 + metadatos.
 *   - delete(id): soft delete.
 */
public class CertificateApiClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String baseUrl;

    public CertificateApiClient() {
        this(System.getenv().getOrDefault("BENJAGEST_API_BASE_URL", DEFAULT_API_BASE_URL));
    }

    public CertificateApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public List<CertificateSummaryEntry> list() throws IOException, InterruptedException {
        String body = doGet("/certificates");
        return parseList(body);
    }

    public CertificateInspectInfo inspect(String certificateDataBase64, String password)
            throws IOException, InterruptedException {
        String json = "{"
                + "\"certificateDataBase64\":\"" + escape(certificateDataBase64) + "\","
                + "\"password\":" + (password == null ? "null" : "\"" + escape(password) + "\"")
                + "}";
        String body = doPost("/certificates/inspect", json);
        return parseInspect(body);
    }

    public CertificateSummaryEntry upload(String alias, String certificateType,
                                            String subjectName, String subjectTaxIdentifier,
                                            String password, String certificateDataBase64,
                                            Instant validFrom, Instant validTo)
            throws IOException, InterruptedException {
        StringBuilder json = new StringBuilder("{");
        json.append("\"alias\":\"").append(escape(alias)).append("\",");
        json.append("\"certificateType\":\"").append(escape(certificateType)).append("\"");
        if (subjectName != null) json.append(",\"subjectName\":\"").append(escape(subjectName)).append("\"");
        if (subjectTaxIdentifier != null) json.append(",\"subjectTaxIdentifier\":\"").append(escape(subjectTaxIdentifier)).append("\"");
        if (password != null) json.append(",\"password\":\"").append(escape(password)).append("\"");
        if (certificateDataBase64 != null) json.append(",\"certificateDataBase64\":\"").append(escape(certificateDataBase64)).append("\"");
        if (validFrom != null) json.append(",\"validFrom\":\"").append(validFrom.toString()).append("\"");
        if (validTo != null) json.append(",\"validTo\":\"").append(validTo.toString()).append("\"");
        json.append("}");
        String body = doPost("/certificates", json.toString());
        return parseOne(body);
    }

    public void delete(String id) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/certificates/" + id))
                .timeout(Duration.ofSeconds(10))
                .DELETE();
        AuthSession.get().authorize(builder);
        HttpResponse<String> r = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    private String doGet(String path) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10)).GET();
        AuthSession.get().authorize(builder);
        HttpResponse<String> r = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    private String doPost(String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        AuthSession.get().authorize(builder);
        HttpResponse<String> r = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    private List<CertificateSummaryEntry> parseList(String arrayJson) {
        List<CertificateSummaryEntry> out = new ArrayList<>();
        // Partición top-level por llaves (mismo patrón que PDF-MULTI).
        int depth = 0;
        int start = -1;
        boolean inString = false, escape = false;
        for (int i = 0; i < arrayJson.length(); i++) {
            char c = arrayJson.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(parseOne(arrayJson.substring(start, i + 1)));
                    start = -1;
                }
            }
        }
        return out;
    }

    private CertificateSummaryEntry parseOne(String json) {
        return new CertificateSummaryEntry(
                strField(json, "id"),
                strField(json, "alias"),
                strField(json, "certificateType"),
                strField(json, "subjectName"),
                strField(json, "subjectTaxIdentifier"),
                boolField(json, "passwordConfigured"),
                boolField(json, "certificateDataPresent"),
                instantField(json, "validFrom"),
                instantField(json, "validTo"),
                boolField(json, "active"),
                strField(json, "uploadedByUserId"),
                strField(json, "uploadedByCompanyId"),
                boolField(json, "uploadedByAdvisory")
        );
    }

    private CertificateInspectInfo parseInspect(String json) {
        // aliasesInKeystore: array de strings → parse simple
        List<String> aliases = new ArrayList<>();
        Matcher arr = Pattern.compile("\"aliasesInKeystore\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (arr.find()) {
            Matcher s = Pattern.compile("\"((?:\\\\.|[^\"])*)\"").matcher(arr.group(1));
            while (s.find()) aliases.add(s.group(1).replace("\\\"", "\""));
        }
        return new CertificateInspectInfo(
                strField(json, "subjectName"),
                strField(json, "subjectTaxIdentifier"),
                strField(json, "issuer"),
                strField(json, "certificateTypeGuess"),
                instantField(json, "validFrom"),
                instantField(json, "validTo"),
                aliases,
                strField(json, "subjectDnRaw"),
                strField(json, "issuerDnRaw"),
                strField(json, "serialNumberHex")
        );
    }

    private String strField(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(json);
        if (!m.find()) return null;
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private boolean boolField(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() && "true".equals(m.group(1));
    }

    private Instant instantField(String json, String key) {
        String v = strField(json, key);
        if (v == null || v.isBlank()) return null;
        try { return Instant.parse(v); } catch (Exception ex) { return null; }
    }

    private static String escape(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
