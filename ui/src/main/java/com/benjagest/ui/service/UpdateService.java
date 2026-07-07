package com.benjagest.ui.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-actualización de BENJAGEST. Comprueba si hay una versión más nueva en el
 * canal de versiones (por defecto GitHub Releases del repo; configurable con la
 * variable de entorno {@code BENJAGEST_UPDATE_URL}) y, si la hay, descarga el
 * {@code .msi} y lanza el instalador (el .msi de jpackage actualiza ENCIMA: mismo
 * UpgradeCode → no hay que desinstalar).
 *
 * <p>Nunca rompe: si no hay red, no hay releases o el formato no encaja, devuelve
 * {@code null} y la app sigue como si nada.
 */
public class UpdateService {

    /** Versión instalada. BUMP en cada release (debe coincidir con --app-version). */
    public static final String APP_VERSION = "0.1.10";

    private static final String DEFAULT_URL =
            "https://api.github.com/repos/susanaybenjamin-boop/BENJAGEST-migration/releases/latest";

    // followRedirects: las descargas de assets de GitHub responden 302 hacia un
    // CDN; sin esto la descarga del .msi fallaría.
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record UpdateInfo(String latestVersion, String downloadUrl) {}

    private static String updateUrl() {
        return System.getenv().getOrDefault("BENJAGEST_UPDATE_URL", DEFAULT_URL);
    }

    /** Info si hay una versión MÁS NUEVA con .msi; {@code null} si no hay o no se pudo comprobar. */
    public UpdateInfo checkForUpdate() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(updateUrl()))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            String body = r.body();
            String tag = field(body, "tag_name");
            if (tag == null || tag.isBlank()) return null;
            String latest = tag.replaceFirst("^[vV]", "").trim();
            if (compareVersions(latest, APP_VERSION) <= 0) return null; // no hay nada más nuevo
            String msi = findMsiAsset(body);
            if (msi == null) return null;
            return new UpdateInfo(latest, msi);
        } catch (Exception e) {
            return null;
        }
    }

    /** Descarga el instalador a un temporal y devuelve la ruta. */
    public Path download(String url) throws IOException, InterruptedException {
        Path out = Paths.get(System.getProperty("java.io.tmpdir"), "BENJAGEST-update.msi");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(15)).GET().build();
        HttpResponse<Path> r = http.send(req, HttpResponse.BodyHandlers.ofFile(out));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
        return r.body();
    }

    /** Lanza el instalador (msiexec). El caller debe cerrar la app a continuación. */
    public void launchInstaller(Path msi) throws IOException {
        new ProcessBuilder("msiexec", "/i", msi.toString()).start();
    }

    // ---- versión: compara "0.1.10" vs "0.1.2" numéricamente por tramos ----
    static int compareVersions(String a, String b) {
        String[] pa = a.split("[.+\\-]");
        String[] pb = b.split("[.+\\-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = part(pa, i), y = part(pb, i);
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
    private static int part(String[] p, int i) {
        if (i >= p.length) return 0;
        String digits = p[i].replaceAll("\\D", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
    private static String findMsiAsset(String json) {
        Matcher m = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.msi)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
