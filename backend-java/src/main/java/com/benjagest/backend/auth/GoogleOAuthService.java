package com.benjagest.backend.auth;

import com.benjagest.backend.auth.dto.LoginResponse;
import com.benjagest.backend.auth.dto.RegisterRequest;
import com.benjagest.backend.modules.RequiresModule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.jasypt.encryption.StringEncryptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REG-3 — Google OAuth POR INSTALACIÓN. El admin configura el Client ID + Secret
 * de SU propio proyecto Google (tipo "Aplicación de escritorio"). El cliente de
 * escritorio abre el navegador a Google (loopback + PKCE) y obtiene un "code";
 * el BACKEND intercambia ese code por el token con Google (el secreto NUNCA sale
 * al cliente), decodifica el id_token (viene directo de Google por HTTPS → de
 * confianza) y hace login/alta.
 */
@Service
public class GoogleOAuthService {

    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

    private final JdbcTemplate jdbc;
    private final StringEncryptor encryptor;
    private final ObjectMapper objectMapper;
    private final AuthService authService;
    private final RegisterService registerService;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    // Credenciales CENTRALES embebidas de BENJAGEST (modelo híbrido): por defecto
    // el usuario no configura nada. Una instalación puede sobreescribirlas con las
    // suyas en Configuración → Integraciones (opción avanzada). Vienen de la config
    // de la app (no las teclea el usuario); vacías hasta que se publique la app.
    @org.springframework.beans.factory.annotation.Value("${benjagest.google.client-id:}")
    private String centralClientId;
    @org.springframework.beans.factory.annotation.Value("${benjagest.google.client-secret:}")
    private String centralClientSecret;

    public GoogleOAuthService(JdbcTemplate jdbc, StringEncryptor encryptor, ObjectMapper objectMapper,
                              AuthService authService, RegisterService registerService) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
        this.objectMapper = objectMapper;
        this.authService = authService;
        this.registerService = registerService;
    }

    // ---- Config (por instalación) ----------------------------------------

    public record ConfigView(String clientId, boolean enabled) {}

    public ConfigView config() {
        // Per-instalación (override) o, si no, la app CENTRAL embebida.
        PerInstall p = perInstall();
        if (p != null && p.enabled && p.clientId != null) return new ConfigView(p.clientId, true);
        if (centralPresent()) return new ConfigView(centralClientId, true);
        return new ConfigView(p == null ? null : p.clientId, false);
    }

    private boolean centralPresent() {
        return centralClientId != null && !centralClientId.isBlank()
                && centralClientSecret != null && !centralClientSecret.isBlank();
    }

    private PerInstall perInstall() {
        return jdbc.query("""
                SELECT client_id, client_secret_encrypted, enabled FROM google_oauth_config WHERE id = 1
                """, rs -> rs.next()
                ? new PerInstall(rs.getString("client_id"), rs.getString("client_secret_encrypted"), rs.getBoolean("enabled"))
                : null);
    }

    private record PerInstall(String clientId, String secretCipher, boolean enabled) {}

    public void save(String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank()) throw bad("Client ID requerido");
        // Si no se reenvía el secreto (campo vacío), se conserva el guardado.
        String secretCipher = (clientSecret == null || clientSecret.isBlank())
                ? currentSecretCipher()
                : encryptor.encrypt(clientSecret.trim());
        if (secretCipher == null) throw bad("Client Secret requerido la primera vez");
        jdbc.update("""
                UPDATE google_oauth_config
                   SET client_id = ?, client_secret_encrypted = ?, enabled = TRUE
                 WHERE id = 1
                """, clientId.trim(), secretCipher);
    }

    public void disable() {
        jdbc.update("UPDATE google_oauth_config SET enabled = FALSE WHERE id = 1");
    }

    private String currentSecretCipher() {
        return jdbc.query("SELECT client_secret_encrypted FROM google_oauth_config WHERE id = 1",
                rs -> rs.next() ? rs.getString(1) : null);
    }

    /** Credenciales efectivas (secreto en CLARO): per-instalación o, si no, central. */
    private Creds creds() {
        PerInstall p = perInstall();
        if (p != null && p.enabled && p.clientId != null && p.secretCipher != null) {
            return new Creds(p.clientId, encryptor.decrypt(p.secretCipher));
        }
        if (centralPresent()) {
            return new Creds(centralClientId, centralClientSecret);
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El acceso con Google no está disponible (ni central ni configurado en esta instalación).");
    }

    private record Creds(String clientId, String secret) {}

    // ---- Intercambio del code + identidad --------------------------------

    public record GoogleUser(String email, String name, String googleId, boolean emailVerified) {}

    /** Cambia el authorization code por el id_token con Google y extrae la identidad. */
    public GoogleUser exchange(String code, String codeVerifier, String redirectUri) {
        if (code == null || code.isBlank()) throw bad("Falta el código de Google");
        Creds c = creds();
        String secret = c.secret;
        String form = "code=" + enc(code)
                + "&client_id=" + enc(c.clientId)
                + "&client_secret=" + enc(secret)
                + "&redirect_uri=" + enc(redirectUri)
                + "&grant_type=authorization_code"
                + (codeVerifier == null || codeVerifier.isBlank() ? "" : "&code_verifier=" + enc(codeVerifier));
        HttpResponse<String> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo contactar con Google: " + ex.getMessage());
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Google rechazó el intercambio (revisa Client ID/Secret y la redirect URI).");
        }
        try {
            JsonNode root = objectMapper.readTree(resp.body());
            String idToken = root.path("id_token").asText(null);
            if (idToken == null) throw bad("Google no devolvió id_token");
            // El id_token viene DIRECTO de Google por HTTPS → de confianza: basta
            // decodificar el payload (no hace falta verificar la firma con JWKS).
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) throw bad("id_token mal formado");
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode p = objectMapper.readTree(payloadJson);
            String email = p.path("email").asText(null);
            if (email == null) throw bad("Google no devolvió email (¿faltó el scope 'email'?)");
            return new GoogleUser(
                    email.toLowerCase(),
                    p.path("name").asText(p.path("given_name").asText("")),
                    p.path("sub").asText(null),
                    p.path("email_verified").asBoolean(false));
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Error procesando la respuesta de Google: " + ex.getMessage());
        }
    }

    // ---- Conexión de APIs por empresa (Gmail / Calendar) -----------------

    /** POST genérico al token endpoint; devuelve el JSON de respuesta. */
    private JsonNode tokenPost(String form) {
        HttpResponse<String> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo contactar con Google: " + ex.getMessage());
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Google rechazó la petición (revisa credenciales/scopes/redirect).");
        }
        try {
            return objectMapper.readTree(resp.body());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Respuesta de Google ilegible: " + ex.getMessage());
        }
    }

    /** Decodifica el email del id_token (viene directo de Google → de confianza). */
    private String emailFromIdToken(JsonNode tokenResponse) {
        String idToken = tokenResponse.path("id_token").asText(null);
        if (idToken == null) return null;
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) return null;
        try {
            JsonNode p = objectMapper.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            String email = p.path("email").asText(null);
            return email == null ? null : email.toLowerCase();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Conecta Gmail (y/o Calendar) para una EMPRESA: intercambia el code con
     * acceso offline, guarda el REFRESH TOKEN cifrado para poder enviar/leer luego.
     */
    public void connectApi(String companyId, GoogleAuthRequest req, boolean gmail, boolean calendar) {
        Creds c = creds();
        String secret = c.secret;
        String form = "code=" + enc(req.code()) + "&client_id=" + enc(c.clientId)
                + "&client_secret=" + enc(secret) + "&redirect_uri=" + enc(req.redirectUri())
                + "&grant_type=authorization_code"
                + (req.codeVerifier() == null || req.codeVerifier().isBlank() ? "" : "&code_verifier=" + enc(req.codeVerifier()));
        JsonNode root = tokenPost(form);
        String refresh = root.path("refresh_token").asText(null);
        if (refresh == null || refresh.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Google no devolvió refresh token. Asegúrate de aceptar los permisos (y quitar el acceso previo en tu cuenta para forzar el consentimiento).");
        }
        String email = emailFromIdToken(root);
        jdbc.update("""
                INSERT INTO google_api_connections
                    (company_id, google_email, refresh_token_encrypted, gmail_enabled, calendar_enabled)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    google_email = VALUES(google_email),
                    refresh_token_encrypted = VALUES(refresh_token_encrypted),
                    gmail_enabled = gmail_enabled OR VALUES(gmail_enabled),
                    calendar_enabled = calendar_enabled OR VALUES(calendar_enabled)
                """, companyId, email, encryptor.encrypt(refresh), gmail, calendar);
    }

    /** Access token fresco para una empresa (refresh_token → access_token). */
    public String freshAccessToken(String companyId) {
        String cipher = jdbc.query("SELECT refresh_token_encrypted FROM google_api_connections WHERE company_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, companyId);
        if (cipher == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "La empresa no tiene Google conectado.");
        Creds c = creds();
        String form = "client_id=" + enc(c.clientId) + "&client_secret=" + enc(c.secret)
                + "&refresh_token=" + enc(encryptor.decrypt(cipher)) + "&grant_type=refresh_token";
        return tokenPost(form).path("access_token").asText(null);
    }

    public ApiConnection apiConnection(String companyId) {
        return jdbc.query("""
                SELECT google_email, gmail_enabled, calendar_enabled FROM google_api_connections WHERE company_id = ?
                """, rs -> rs.next()
                ? new ApiConnection(true, rs.getString("google_email"),
                        rs.getBoolean("gmail_enabled"), rs.getBoolean("calendar_enabled"))
                : new ApiConnection(false, null, false, false), companyId);
    }

    public boolean gmailEnabled(String companyId) {
        Boolean b = jdbc.query("SELECT gmail_enabled FROM google_api_connections WHERE company_id = ?",
                rs -> rs.next() && rs.getBoolean(1), companyId);
        return Boolean.TRUE.equals(b);
    }

    public void disconnectApi(String companyId) {
        jdbc.update("DELETE FROM google_api_connections WHERE company_id = ?", companyId);
    }

    public record ApiConnection(boolean connected, String email, boolean gmail, boolean calendar) {}

    // ---- Login / Alta ----------------------------------------------------

    /** Login con Google: la cuenta debe existir (si no, 404 → que se registre). */
    public LoginResponse login(GoogleAuthRequest req) {
        GoogleUser g = exchange(req.code(), req.codeVerifier(), req.redirectUri());
        String userId = jdbc.query("SELECT id FROM user_accounts WHERE email = ? AND active = TRUE LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, g.email());
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No hay ninguna cuenta con ese email de Google. Regístrate primero.");
        }
        // Vincula el google_id si la cuenta no lo tenía (alta previa por email).
        jdbc.update("UPDATE user_accounts SET google_id = COALESCE(google_id, ?) WHERE id = ?",
                g.googleId(), userId);
        return authService.issueSession(g.email());
    }

    /** Alta con Google: company data del formulario + email/identidad de Google. */
    public LoginResponse register(GoogleRegisterRequest req) {
        GoogleUser g = exchange(req.code(), req.codeVerifier(), req.redirectUri());
        RegisterRequest companyData = new RegisterRequest(
                req.accountType(), req.legalName(), req.taxIdentifier(), req.addressLine(),
                req.city(), req.province(), req.postalCode(),
                g.email(), blankOr(req.displayName(), g.name()), "GOOGLE_NO_PASSWORD_PLACEHOLDER");
        return registerService.registerWithGoogle(companyData, g.email(), g.googleId());
    }

    // ---- helpers / DTOs --------------------------------------------------

    private static String enc(String v) { return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8); }
    private static String blankOr(String a, String b) { return a == null || a.isBlank() ? b : a; }
    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }

    public record GoogleAuthRequest(String code, String codeVerifier, String redirectUri) {}

    public record GoogleRegisterRequest(
            String code, String codeVerifier, String redirectUri,
            String accountType, String legalName, String taxIdentifier,
            String addressLine, String city, String province, String postalCode,
            String displayName) {}

    public record SaveConfigRequest(String clientId, String clientSecret) {}

    // ---- Controllers -----------------------------------------------------

    /** Endpoints PÚBLICOS (pre-JWT): config (clientId) + login/alta con Google. */
    @RestController
    @RequestMapping("/api/auth/google")
    public static class AuthController {
        private final GoogleOAuthService service;
        public AuthController(GoogleOAuthService service) { this.service = service; }

        @GetMapping("/config")
        public ConfigView config() { return service.config(); }

        @PostMapping("/login")
        public LoginResponse login(@RequestBody GoogleAuthRequest req) { return service.login(req); }

        @PostMapping("/register")
        public LoginResponse register(@RequestBody GoogleRegisterRequest req) { return service.register(req); }
    }

    /** Endpoint de ADMIN: configurar las credenciales de la instalación. */
    @RestController
    @RequestMapping("/api/settings/google-oauth")
    @RequiresModule("settings")
    @com.benjagest.backend.auth.RequiresRole({"OWNER", "ADMIN"})
    public static class SettingsController {
        private final GoogleOAuthService service;
        private final com.benjagest.backend.tenant.TenantContext tenant;
        public SettingsController(GoogleOAuthService service,
                                  com.benjagest.backend.tenant.TenantContext tenant) {
            this.service = service;
            this.tenant = tenant;
        }

        @GetMapping
        public ConfigView get() { return service.config(); }

        @PostMapping
        public ConfigView save(@RequestBody SaveConfigRequest req) {
            service.save(req.clientId(), req.clientSecret());
            return service.config();
        }

        @PostMapping("/disable")
        public ConfigView disable() { service.disable(); return service.config(); }

        // ---- Conexión Gmail/Calendar de la empresa actual ----

        @GetMapping("/gmail-status")
        public ApiConnection gmailStatus() {
            return service.apiConnection(tenant.getCurrentCompanyId());
        }

        @PostMapping("/connect-gmail")
        public ApiConnection connectGmail(@RequestBody GoogleAuthRequest req) {
            service.connectApi(tenant.getCurrentCompanyId(), req, true, false);
            return service.apiConnection(tenant.getCurrentCompanyId());
        }

        @PostMapping("/disconnect-gmail")
        public ApiConnection disconnectGmail() {
            service.disconnectApi(tenant.getCurrentCompanyId());
            return service.apiConnection(tenant.getCurrentCompanyId());
        }
    }
}
