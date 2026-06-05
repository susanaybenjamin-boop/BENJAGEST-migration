package com.benjagest.ui.service;

import com.benjagest.ui.model.AdvisoryInvitationEntry;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente HTTP para /api/advisory/invitations (asesoría) y
 * /api/advisory/my-invitations (empresario).
 */
public class AdvisoryInvitationApiClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String baseUrl;

    public AdvisoryInvitationApiClient() {
        this(System.getenv().getOrDefault("BENJAGEST_API_BASE_URL", DEFAULT_API_BASE_URL));
    }

    public AdvisoryInvitationApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    // ==================== ASESORÍA ====================

    public AdvisoryInvitationEntry create(String invitedEmail, String invitedNif,
                                            String invitedCompanyName, String notes)
            throws IOException, InterruptedException {
        StringBuilder json = new StringBuilder("{");
        appendStr(json, "invitedEmail", invitedEmail, true);
        appendStr(json, "invitedNif", invitedNif, false);
        appendStr(json, "invitedCompanyName", invitedCompanyName, false);
        appendStr(json, "notes", notes, false);
        json.append("}");
        return postOne("/advisory/invitations", json.toString(), 201);
    }

    public List<AdvisoryInvitationEntry> listMine() throws IOException, InterruptedException {
        return parseList(get("/advisory/invitations"));
    }

    public void revoke(String id) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/advisory/invitations/" + id))
                .timeout(Duration.ofSeconds(10)).DELETE();
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    // ==================== EMPRESARIO ====================

    public List<AdvisoryInvitationEntry> listPending() throws IOException, InterruptedException {
        return parseList(get("/advisory/my-invitations"));
    }

    public AdvisoryInvitationEntry accept(String token) throws IOException, InterruptedException {
        return postOne("/advisory/my-invitations/accept?token=" + enc(token), "", 200);
    }

    public void reject(String token) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(
                        baseUrl + "/advisory/my-invitations/reject?token=" + enc(token)))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody());
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    public LinkedAdvisory getLinkedAdvisory() throws IOException, InterruptedException {
        String body = get("/advisory/my-invitations/linked");
        if (body == null || body.isBlank() || body.equals("null")) return null;
        return new LinkedAdvisory(
                strField(body, "id"),
                strField(body, "legalName"),
                strField(body, "tradeName"),
                strField(body, "taxIdentifier"),
                strField(body, "email")
        );
    }

    public void unlink() throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/advisory/my-invitations/unlink"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody());
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    // ==================== HELPERS ====================

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10)).GET();
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    private AdvisoryInvitationEntry postOne(String path, String body, int expectStatus)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        if (body == null || body.isEmpty()) {
            b.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            b.POST(HttpRequest.BodyPublishers.ofString(body));
        }
        AuthSession.get().authorize(b);
        HttpResponse<String> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != expectStatus
                && (r.statusCode() < 200 || r.statusCode() >= 300)) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return parseOne(r.body());
    }

    private List<AdvisoryInvitationEntry> parseList(String arrayJson) {
        List<AdvisoryInvitationEntry> out = new ArrayList<>();
        if (arrayJson == null) return out;
        String s = arrayJson.trim();
        if (s.isEmpty()) return out;
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

    private AdvisoryInvitationEntry parseOne(String json) {
        return new AdvisoryInvitationEntry(
                strField(json, "id"),
                strField(json, "advisoryCompanyId"),
                strField(json, "invitedEmail"),
                strField(json, "invitedNif"),
                strField(json, "invitedCompanyName"),
                strField(json, "invitedCompanyId"),
                strField(json, "token"),
                strField(json, "status"),
                instantField(json, "expiresAt"),
                instantField(json, "createdAt"),
                instantField(json, "acceptedAt")
        );
    }

    private String strField(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(json);
        if (!m.find()) return null;
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private Instant instantField(String json, String key) {
        String v = strField(json, key);
        if (v == null || v.isBlank()) return null;
        try { return Instant.parse(v); } catch (Exception ex) { return null; }
    }

    private void appendStr(StringBuilder json, String key, String value, boolean first) {
        if (!first) json.append(",");
        if (value == null) json.append("\"").append(key).append("\":null");
        else json.append("\"").append(key).append("\":\"").append(escape(value)).append("\"");
    }

    private static String escape(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    public record LinkedAdvisory(
            String id, String legalName, String tradeName,
            String taxIdentifier, String email
    ) {}
}
