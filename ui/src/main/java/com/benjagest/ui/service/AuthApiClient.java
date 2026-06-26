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
     * Bloque REGISTRO — alta de cuenta (asesoría o empresa) + auto-login.
     * Tras un alta OK el backend devuelve un LoginResponse igual que /login,
     * así que reutilizamos {@link #storeResponse} y el usuario entra directo.
     */
    public void register(String accountType, String legalName, String taxId,
                         String addressLine, String city, String province, String postalCode,
                         String displayName, String email, String password)
            throws IOException, InterruptedException {
        String body = "{"
                + "\"accountType\":\"" + escape(accountType) + "\","
                + "\"legalName\":\"" + escape(legalName) + "\","
                + "\"taxIdentifier\":\"" + escape(taxId) + "\","
                + "\"addressLine\":\"" + escape(addressLine) + "\","
                + "\"city\":\"" + escape(city) + "\","
                + "\"province\":\"" + escape(province == null ? "" : province) + "\","
                + "\"postalCode\":\"" + escape(postalCode == null ? "" : postalCode) + "\","
                + "\"displayName\":\"" + escape(displayName) + "\","
                + "\"email\":\"" + escape(email) + "\","
                + "\"password\":\"" + escape(password) + "\""
                + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/register"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(extractError(response.body(),
                    "El registro falló (HTTP " + response.statusCode() + ")"));
        }
        storeResponse(response.body());
    }

    /** Saca el "message" del JSON de error de Spring; si no, usa el por defecto. */
    private String extractError(String body, String fallback) {
        if (body == null) return fallback;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"message\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(body);
        if (m.find()) {
            String msg = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
            if (!msg.isBlank() && !"No message available".equals(msg)) return msg;
        }
        return fallback;
    }

    /**
     * ¿Hay alguna cuenta en el sistema? Si no (instalación nueva), la UI muestra
     * el REGISTRO en vez del login. Defensivo: ante cualquier fallo devuelve
     * {@code true} (mostrar login), nunca fuerza el registro por error de red.
     */
    public boolean hasAccounts() {
        return bootstrapStatus().hasAccounts();
    }

    /** Estado de arranque: si hay cuentas y si hay asesoría (multi-puesto). */
    public record BootstrapStatus(boolean hasAccounts, boolean hasAdvisory) {}

    public BootstrapStatus bootstrapStatus() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/bootstrap-status"))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new BootstrapStatus(true, true);
            }
            String b = response.body() == null ? "" : response.body();
            boolean hasAccounts = !b.contains("\"hasAccounts\":false");
            boolean hasAdvisory = !b.contains("\"hasAdvisory\":false");
            return new BootstrapStatus(hasAccounts, hasAdvisory);
        } catch (Exception ex) {
            return new BootstrapStatus(true, true);
        }
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

    // ============================================================
    // L4-3 — Acceso por PIN multi-puesto
    // ============================================================

    /**
     * Empareja el PC actual con la asesoría del OWNER. Devuelve el
     * resultado en bruto para que la UI lo persista vía DeviceConfig.
     * NO toca AuthSession — el emparejado es independiente del login.
     */
    public PairResult pairDevice(String email, String password, String deviceName)
            throws IOException, InterruptedException {
        String body = "{"
                + "\"email\":\"" + escape(email) + "\","
                + "\"password\":\"" + escape(password) + "\","
                + "\"deviceName\":\"" + escape(deviceName) + "\""
                + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/devices/pair"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            throw new IOException("Credenciales no válidas");
        }
        if (response.statusCode() == 403) {
            throw new IOException("Solo el Propietario de una asesoría puede emparejar dispositivos");
        }
        if (response.statusCode() == 409) {
            // Mensaje específico del backend (5 ya activos).
            throw new IOException(extractMessage(response.body(),
                    "Has alcanzado el límite de dispositivos. Revoca uno desde 'Mis equipos'."));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Emparejado falló: HTTP " + response.statusCode());
        }
        return new PairResult(
                textField(response.body(), "deviceId"),
                textField(response.body(), "deviceSecret"),
                textField(response.body(), "companyId"),
                textField(response.body(), "companyName")
        );
    }

    /**
     * Login por PIN. Si el backend acepta el par (deviceSecret, pin),
     * el LoginResponse se persiste en AuthSession como cualquier login.
     */
    public void pinLogin(String deviceSecret, String pin)
            throws IOException, InterruptedException {
        String body = "{"
                + "\"deviceSecret\":\"" + escape(deviceSecret) + "\","
                + "\"pin\":\"" + escape(pin) + "\""
                + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/pin-login"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            // Distinguimos "device no reconocido" de "PIN incorrecto" por
            // el mensaje, para que la UI pueda forzar re-emparejado si
            // el device.json local quedó obsoleto.
            String msg = extractMessage(response.body(), "PIN incorrecto");
            if (msg.toLowerCase().contains("dispositivo")) {
                throw new IOException("DEVICE_NOT_RECOGNIZED");
            }
            throw new IOException("PIN incorrecto");
        }
        if (response.statusCode() == 429) {
            // Bloqueado por intentos fallidos. Mantenemos el mensaje
            // del backend (lleva los segundos restantes).
            throw new IOException(extractMessage(response.body(),
                    "Demasiados intentos. Espera unos segundos."));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Login PIN falló: HTTP " + response.statusCode());
        }
        storeResponse(response.body());
    }

    /** Revoca el emparejado actual (Olvidar este equipo). */
    public void revokeDevice(String deviceId) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create(baseUrl + "/auth/devices/" + deviceId))
                .timeout(Duration.ofSeconds(8))
                .DELETE();
        AuthSession.get().authorize(builder);
        HttpResponse<Void> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("No se pudo revocar el dispositivo: HTTP " + response.statusCode());
        }
    }

    /**
     * Extrae el campo {@code message} de un body de error Spring estándar.
     * Si no hay match, devuelve el fallback proporcionado.
     */
    private String extractMessage(String body, String fallback) {
        if (body == null || body.isBlank()) return fallback;
        String v = textField(body, "message");
        return v == null || v.isBlank() ? fallback : v;
    }

    /** Resultado del emparejado — la UI lo pasa a DeviceConfig.save. */
    public record PairResult(String deviceId, String deviceSecret,
                              String companyId, String companyName) {}

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
