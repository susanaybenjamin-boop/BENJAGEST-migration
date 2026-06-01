package com.benjagest.ui.service;

import com.benjagest.ui.model.Membership;
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
 * Cliente HTTP del modulo de auth. Hace:
 *   - POST /api/auth/login   -> guarda tokens y snapshot en AuthSession.
 *   - POST /api/auth/switch-company/{id}  -> renueva tokens y snapshot.
 *
 * El refresh automatico cuando el access caduca se anadira en un
 * slice posterior. Por ahora si el access caduca el usuario tiene que
 * re-loguearse.
 */
public class AuthApiClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";

    private final HttpClient httpClient;
    private final URI loginUri;
    private final String baseUrl;

    public AuthApiClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), apiBaseUrl());
    }

    AuthApiClient(HttpClient httpClient, String apiBaseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = apiBaseUrl.replaceAll("/+$", "");
        this.loginUri = URI.create(this.baseUrl + "/auth/login");
    }

    public void login(String email, String password) throws IOException, InterruptedException {
        String body = "{"
                + "\"email\":\"" + escape(email) + "\","
                + "\"password\":\"" + escape(password) + "\""
                + "}";
        HttpRequest request = HttpRequest.newBuilder(loginUri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            throw new IOException("Credenciales no validas");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Login fallo con HTTP " + response.statusCode());
        }
        storeResponse(response.body());
    }

    /**
     * Revoca el refresh token actual en el backend (denylist). Si la
     * llamada falla (sin red, backend caido, etc.) la tragamos: el
     * cleanup local del cliente (clear()) se hace de todos modos para
     * no dejar al usuario logueado en la UI.
     */
    public void logout() {
        try {
            String refreshToken = AuthSession.get().refreshToken();
            if (refreshToken == null || refreshToken.isBlank()) {
                return;
            }
            String body = "{\"refreshToken\":\"" + escape(refreshToken) + "\"}";
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/logout"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // El logout local debe seguir ocurriendo aunque el backend
            // no responda. Si el token sigue siendo valido cuando el
            // backend vuelva, caducara solo en su TTL.
        }
    }

    public void switchCompany(String companyId) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/auth/switch-company/" + companyId);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody());
        AuthSession.get().authorize(builder);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Cambio de empresa fallo: HTTP " + response.statusCode());
        }
        storeResponse(response.body());
    }

    private void storeResponse(String json) {
        String accessToken = textField(json, "accessToken");
        String refreshToken = textField(json, "refreshToken");
        String userId = textField(json, "userId");
        String email = textField(json, "email");
        String displayName = textField(json, "displayName");
        String globalRole = textField(json, "globalRole");
        String activeCompanyId = textField(json, "activeCompanyId");
        String roleInActiveCompany = textField(json, "roleInActiveCompany");
        List<Membership> memberships = parseMemberships(json);

        // legalName / type de la empresa activa salen de la membership correspondiente
        String activeLegalName = "";
        String activeCompanyType = "";
        for (Membership m : memberships) {
            if (m.companyId().equals(activeCompanyId)) {
                activeLegalName = m.companyLegalName();
                activeCompanyType = m.companyType();
                break;
            }
        }

        AuthSession.get().update(
                accessToken, refreshToken,
                userId, displayName, email, globalRole,
                activeCompanyId, activeLegalName, activeCompanyType, roleInActiveCompany,
                memberships
        );
    }

    private List<Membership> parseMemberships(String json) {
        List<Membership> list = new ArrayList<>();
        // Buscamos cada objeto dentro del array memberships
        int arrayStart = json.indexOf("\"memberships\"");
        if (arrayStart < 0) {
            return list;
        }
        int bracketStart = json.indexOf('[', arrayStart);
        int bracketEnd = json.indexOf(']', bracketStart);
        if (bracketStart < 0 || bracketEnd < 0) {
            return list;
        }
        String slice = json.substring(bracketStart, bracketEnd + 1);
        Matcher matcher = Pattern.compile("\\{[^{}]*\"companyId\"[^{}]*\\}").matcher(slice);
        while (matcher.find()) {
            String obj = matcher.group();
            list.add(new Membership(
                    textField(obj, "companyId"),
                    textField(obj, "companyLegalName"),
                    textField(obj, "companyTradeName"),
                    textField(obj, "companyType"),
                    textField(obj, "roleName")
            ));
        }
        return list;
    }

    private String textField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|\"((?:\\\\.|[^\"])*)\")");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || "null".equals(matcher.group(1))) {
            return "";
        }
        return unescape(matcher.group(2));
    }

    private String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
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
