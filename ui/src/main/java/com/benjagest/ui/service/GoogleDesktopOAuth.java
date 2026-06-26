package com.benjagest.ui.service;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * REG-3 — Flujo OAuth de Google para app de ESCRITORIO (loopback + PKCE).
 *
 * <p>Abre el navegador del sistema a la pantalla de Google, levanta un mini
 * servidor HTTP en {@code http://127.0.0.1:PUERTO} para recibir el redirect con
 * el {@code code}, y lo devuelve junto al {@code code_verifier} (PKCE) y la
 * redirect URI. El intercambio del code por el token lo hace el BACKEND (el
 * secreto nunca está en el cliente).
 */
public final class GoogleDesktopOAuth {

    private GoogleDesktopOAuth() {}

    public record Result(String code, String codeVerifier, String redirectUri) {}

    /** Login básico (openid/email/profile, sin refresh token). */
    public static Result authorize(String clientId) throws Exception {
        return authorize(clientId, "openid email profile", false);
    }

    /**
     * Lanza el flujo con los SCOPES indicados. Si {@code offline} es true pide
     * acceso sin conexión (access_type=offline + prompt=consent) para que Google
     * devuelva un REFRESH TOKEN — necesario para enviar por Gmail / Calendar.
     */
    public static Result authorize(String clientId, String scopes, boolean offline) throws Exception {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("Falta el Client ID de Google de esta instalación.");
        }
        String verifier = randomUrlSafe(64);
        String challenge = base64Url(sha256(verifier));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String redirectUri = "http://127.0.0.1:" + port;
        CompletableFuture<String> codeFuture = new CompletableFuture<>();

        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String code = paramOf(query, "code");
            String error = paramOf(query, "error");
            // La pestaña intenta cerrarse sola (best-effort: algunos navegadores lo
            // permiten con el truco window.open('','_self')). Si no, queda un mensaje
            // limpio. No se puede forzar el cierre del navegador del sistema.
            String closeScript = "<script>setTimeout(function(){"
                    + "try{window.open('','_self');window.close();}catch(e){}},600);</script>";
            String html = code != null
                    ? "<!doctype html><html><head><meta charset='utf-8'><title>BENJAGEST</title></head>"
                      + "<body style='font-family:system-ui,sans-serif;text-align:center;margin:0;"
                      + "background:#f1f5f9;color:#1e293b'>"
                      + "<div style='margin-top:18vh'><div style='font-size:42px'>✓</div>"
                      + "<h2>Has entrado en la aplicación</h2>"
                      + "<p style='color:#475569'>Ya puedes cerrar esta pestaña y volver a la aplicación.</p>"
                      + "</div>" + closeScript + "</body></html>"
                    : "<!doctype html><html><head><meta charset='utf-8'><title>Error</title></head>"
                      + "<body style='font-family:system-ui,sans-serif;text-align:center;margin:0;"
                      + "background:#f1f5f9;color:#1e293b'>"
                      + "<div style='margin-top:18vh'><h2>No se pudo completar el acceso</h2>"
                      + "<p style='color:#475569'>Vuelve a la aplicación e inténtalo de nuevo.</p></div></body></html>";
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
            if (code != null) codeFuture.complete(code);
            else codeFuture.completeExceptionally(new IOException(
                    "Google devolvió un error" + (error == null ? "" : ": " + error)));
        });
        server.start();

        String authUrl = "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc(scopes == null || scopes.isBlank() ? "openid email profile" : scopes)
                + "&code_challenge=" + challenge
                + "&code_challenge_method=S256"
                + (offline ? "&access_type=offline&prompt=consent" : "&prompt=select_account");
        try {
            openBrowser(authUrl);
            String code = codeFuture.get(180, TimeUnit.SECONDS);
            return new Result(code, verifier, redirectUri);
        } finally {
            server.stop(0);
        }
    }

    // ---- helpers ----

    private static void openBrowser(String url) throws IOException {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // cae al fallback de Windows
        }
        // Fallback Windows (sin AWT): rundll32 abre la URL en el navegador por defecto.
        new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
    }

    private static String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] sha256(String s) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static String base64Url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String paramOf(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
