package com.benjagest.ui.service;

import com.benjagest.ui.model.AuditEvent;
import com.benjagest.ui.model.CompanyData;
import com.benjagest.ui.model.CompanyModuleEntry;
import com.benjagest.ui.model.EmailConfig;
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

/**
 * Cliente HTTP de las 3 pestanas de "Configuracion":
 *   - /api/settings/company        (GET/PUT)
 *   - /api/settings/email-config   (GET/PUT + POST test-email)
 *   - /api/settings/modules        (GET + PUT por slug)
 *
 * Mismo estilo que IssuerApiClient: HTTP nativo + JSON manual con regex.
 * Todas las llamadas pasan por AuthSession.authorize() para enviar el
 * Bearer; sin sesion, el backend respondera 401.
 */
public class SettingsApiClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";

    private final HttpClient httpClient;
    private final String baseUrl;

    public SettingsApiClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), apiBaseUrl());
    }

    SettingsApiClient(HttpClient httpClient, String apiBaseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = apiBaseUrl.replaceAll("/+$", "");
    }

    // -------- empresa --------

    public CompanyData getCompany() throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/settings/company"))
                .timeout(Duration.ofSeconds(8))
                .GET());
        ensureOk(response);
        return parseCompany(response.body());
    }

    public CompanyData updateCompany(CompanyData data) throws IOException, InterruptedException {
        String body = "{"
                + field("legalName", data.legalName()) + ","
                + field("tradeName", data.tradeName()) + ","
                + field("taxIdentifier", data.taxIdentifier()) + ","
                + field("email", data.email()) + ","
                + field("phone", data.phone()) + ","
                + field("website", data.website()) + ","
                + field("addressLine", data.addressLine()) + ","
                + field("city", data.city()) + ","
                + field("province", data.province()) + ","
                + field("postalCode", data.postalCode()) + ","
                + field("country", data.country()) + ","
                + field("iban", data.iban()) + ","
                + field("registryInformation", data.registryInformation())
                + "}";
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/settings/company"))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
        ensureOk(response);
        return parseCompany(response.body());
    }

    // -------- email config --------

    public EmailConfig getEmailConfig() throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/settings/email-config"))
                .timeout(Duration.ofSeconds(8))
                .GET());
        ensureOk(response);
        return parseEmailConfig(response.body());
    }

    public EmailConfig updateEmailConfig(String smtpHost,
                                         Integer smtpPort,
                                         String smtpUser,
                                         String smtpPasswordOrNull,
                                         String fromAddress,
                                         String fromName,
                                         String replyTo,
                                         boolean tlsEnabled,
                                         boolean authRequired) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{");
        body.append(field("smtpHost", smtpHost)).append(",");
        body.append(intField("smtpPort", smtpPort)).append(",");
        body.append(field("smtpUser", smtpUser)).append(",");
        // Solo enviamos password si el usuario la ha tocado (la UI pasa null si esta vacia)
        if (smtpPasswordOrNull != null && !smtpPasswordOrNull.isBlank()) {
            body.append(field("smtpPassword", smtpPasswordOrNull)).append(",");
        }
        body.append(field("fromAddress", fromAddress)).append(",");
        body.append(field("fromName", fromName)).append(",");
        body.append(field("replyTo", replyTo)).append(",");
        body.append("\"tlsEnabled\":").append(tlsEnabled).append(",");
        body.append("\"authRequired\":").append(authRequired);
        body.append("}");

        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/settings/email-config"))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString())));
        ensureOk(response);
        return parseEmailConfig(response.body());
    }

    public void sendTestEmail(String recipient) throws IOException, InterruptedException {
        String body = "{" + field("recipient", recipient) + "}";
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/settings/email-config/test-email"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        ensureOk(response);
    }

    // -------- modulos --------

    public List<CompanyModuleEntry> listModules() throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/settings/modules"))
                .timeout(Duration.ofSeconds(8))
                .GET());
        ensureOk(response);
        return parseModules(response.body());
    }

    public List<CompanyModuleEntry> setModuleActive(String slug, boolean active) throws IOException, InterruptedException {
        String body = "{\"active\":" + active + "}";
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/settings/modules/" + slug))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
        ensureOk(response);
        return parseModules(response.body());
    }

    // -------- audit --------

    /**
     * Lista los ultimos eventos de auditoria de la empresa activa.
     * Filtros opcionales: eventType (null para todos), sinceIso (null
     * = ultimos), limit (1-500).
     */
    /**
     * Descarga el export verificable de auditoría general (PDF o CSV).
     * Endpoint /api/settings/audit-events/export.{pdf|csv}.
     */
    public byte[] exportAuditEvents(String format, String fromIso, String toIso,
                                      String eventTypePrefix) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/settings/audit-events/export.").append(format)
                .append("?from=").append(fromIso)
                .append("&to=").append(toIso);
        if (eventTypePrefix != null && !eventTypePrefix.isBlank()) {
            url.append("&eventTypePrefix=").append(java.net.URLEncoder.encode(
                    eventTypePrefix, java.nio.charset.StandardCharsets.UTF_8));
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(60)).GET();
        AuthSession.get().authorize(builder);
        HttpResponse<byte[]> r = HttpClient.newHttpClient().send(builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
        return r.body();
    }

    /**
     * Llama al endpoint que recorre la cadena de hashes de la empresa
     * activa y devuelve OK o el id del primer evento corrupto. Lo usa
     * el botón "Verificar cadena" de la pestaña Auditoría.
     */
    public ChainVerification verifyAuditChain() throws IOException, InterruptedException {
        HttpResponse<String> r = sendAuthorized(HttpRequest.newBuilder(
                        URI.create(baseUrl + "/settings/audit-events/verify"))
                .timeout(Duration.ofSeconds(20))
                .GET());
        ensureOk(r);
        String body = r.body();
        boolean valid = body.contains("\"valid\":true");
        String brokenAt = extractStr(body, "brokenAtId");
        long count = extractLong(body, "count");
        String message = extractStr(body, "message");
        return new ChainVerification(valid, brokenAt, count, message);
    }

    private static String extractStr(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + key + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"") : null;
    }

    private static long extractLong(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    public record ChainVerification(boolean valid, String brokenAtId, long count, String message) {}

    public List<AuditEvent> listAuditEvents(String eventType, String sinceIso, int limit) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl + "/settings/audit-events?limit=" + limit);
        if (eventType != null && !eventType.isBlank()) {
            url.append("&eventType=").append(java.net.URLEncoder.encode(eventType, java.nio.charset.StandardCharsets.UTF_8));
        }
        if (sinceIso != null && !sinceIso.isBlank()) {
            url.append("&sinceIso=").append(java.net.URLEncoder.encode(sinceIso, java.nio.charset.StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(8))
                .GET());
        ensureOk(response);
        return parseAuditEvents(response.body());
    }

    private List<AuditEvent> parseAuditEvents(String json) {
        List<AuditEvent> list = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\{[^{}]*\"eventType\"\\s*:\\s*\"[^\"]+\"[^{}]*\\}").matcher(json);
        while (matcher.find()) {
            String obj = matcher.group();
            // sequenceNumber y eventHash son nullable (eventos pre-V44
            // o LOGIN_FAIL sin companyId no forman cadena).
            Long seq = null;
            java.util.regex.Matcher seqM = java.util.regex.Pattern.compile(
                    "\"sequenceNumber\"\\s*:\\s*(\\d+)").matcher(obj);
            if (seqM.find()) seq = Long.parseLong(seqM.group(1));
            list.add(new AuditEvent(
                    textField(obj, "id"),
                    textField(obj, "companyId"),
                    textField(obj, "userId"),
                    textField(obj, "userName"),
                    textField(obj, "eventType"),
                    textField(obj, "entityType"),
                    textField(obj, "entityId"),
                    textField(obj, "result"),
                    textField(obj, "ipAddress"),
                    textField(obj, "userAgent"),
                    textField(obj, "details"),
                    textField(obj, "createdAt"),
                    seq,
                    textField(obj, "eventHash")
            ));
        }
        return list;
    }

    /**
     * Lista los modulos activos para la empresa actual segun el catalogo.
     * Lo usa el sidebar dinamico: cada entrada de la lista que cumple la
     * whitelist KNOWN_VIEWS se renderiza como un boton del nav.
     *
     * El endpoint devuelve solo activos, por eso parseamos con active=true
     * implicito (CompanyModuleEntry tiene el campo, pero todos seran true).
     */
    public List<CompanyModuleEntry> listActiveCatalog() throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/modules-catalog/active"))
                .timeout(Duration.ofSeconds(8))
                .GET());
        ensureOk(response);
        return parseModules(response.body());
    }

    // -------- infra --------

    private HttpResponse<String> sendAuthorized(HttpRequest.Builder builder) throws IOException, InterruptedException {
        AuthSession.get().authorize(builder);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void ensureOk(HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private CompanyData parseCompany(String json) {
        return new CompanyData(
                textField(json, "id"),
                textField(json, "legalName"),
                textField(json, "tradeName"),
                textField(json, "taxIdentifier"),
                textField(json, "companyType"),
                textField(json, "email"),
                textField(json, "phone"),
                textField(json, "website"),
                textField(json, "addressLine"),
                textField(json, "city"),
                textField(json, "province"),
                textField(json, "postalCode"),
                textField(json, "country"),
                textField(json, "iban"),
                textField(json, "registryInformation")
        );
    }

    private EmailConfig parseEmailConfig(String json) {
        return new EmailConfig(
                textField(json, "smtpHost"),
                intField(json, "smtpPort"),
                textField(json, "smtpUser"),
                boolField(json, "passwordConfigured"),
                textField(json, "fromAddress"),
                textField(json, "fromName"),
                textField(json, "replyTo"),
                boolField(json, "tlsEnabled"),
                boolField(json, "authRequired")
        );
    }

    private List<CompanyModuleEntry> parseModules(String json) {
        List<CompanyModuleEntry> list = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\{[^{}]*\"slug\"\\s*:\\s*\"[^\"]+\"[^{}]*\\}").matcher(json);
        while (matcher.find()) {
            String obj = matcher.group();
            list.add(new CompanyModuleEntry(
                    textField(obj, "slug"),
                    textField(obj, "label"),
                    textField(obj, "description"),
                    textField(obj, "parentSlug"),
                    textField(obj, "requiresSlug"),
                    textField(obj, "icon"),
                    intFieldOrZero(obj, "displayOrder"),
                    boolField(obj, "advisoryOnly"),
                    boolField(obj, "active")
            ));
        }
        return list;
    }

    private String field(String name, String value) {
        return "\"" + name + "\":" + (value == null ? "null" : "\"" + escape(value) + "\"");
    }

    private String intField(String name, Integer value) {
        return "\"" + name + "\":" + (value == null ? "null" : value);
    }

    private String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private String textField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|\"((?:\\\\.|[^\"])*)\")");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || "null".equals(matcher.group(1))) {
            return "";
        }
        return unescape(matcher.group(2));
    }

    private Integer intField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|(-?\\d+))");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || "null".equals(matcher.group(1))) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int intFieldOrZero(String json, String field) {
        Integer value = intField(json, field);
        return value == null ? 0 : value;
    }

    private boolean boolField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(true|false)");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() && "true".equals(matcher.group(1));
    }

    private String unescape(String value) {
        return value
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static String apiBaseUrl() {
        return System.getenv().getOrDefault("BENJAGEST_API_BASE_URL", DEFAULT_API_BASE_URL);
    }
}
