package com.benjagest.ui.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
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
    public static final String APP_VERSION = "0.1.60";

    private static final String DEFAULT_URL =
            "https://api.github.com/repos/susanaybenjamin-boop/BENJAGEST-migration/releases/latest";

    /** UPD-3: la misma resolución que usan los ApiClient del UI. */
    public static String apiBaseUrl() {
        return System.getenv().getOrDefault("BENJAGEST_API_BASE_URL", "http://localhost:8080/api");
    }

    /**
     * UPD-3 — El marcador que crea el helper del servicio al terminar; lo vigila
     * el relanzador para reabrir la app.
     *
     * <p>Tiene que ser LA MISMA ruta que usa {@code SystemUpdateService} en el
     * backend ({@code BenjagestHome.resolve("updates")}). Se repite aquí porque
     * el UI no depende del backend, y {@code BenjagestHome} vive allí. Si algún
     * día cambia la raíz de datos, hay que tocar los dos sitios — por eso lo
     * dice este comentario.
     */
    public static Path doneMarkerPath() {
        String override = System.getProperty("benjagest.home");
        Path root;
        if (override != null && !override.isBlank()) {
            root = Paths.get(override);
        } else {
            String programData = System.getenv("ProgramData");
            root = (programData == null || programData.isBlank())
                    ? Paths.get(System.getProperty("user.home"), ".benjagest")
                    : Paths.get(programData, "BENJAGEST");
        }
        return root.resolve("updates").resolve("update-done.marker");
    }

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

    /** Avisa del avance de la descarga. {@code total} es -1 si el servidor no lo dice. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long bytesDone, long bytesTotal);
    }

    // ---- UPD-3: la actualización la hace el SERVICIO ------------------------

    /** Estado de la actualización que lleva el servicio. */
    public record ServiceUpdateStatus(String state, long bytesDone, long bytesTotal, String message) {}

    /**
     * Le pide al SERVICIO que se actualice él (descarga + instala). Sin UAC: ya
     * corre como LocalSystem. No acepta rutas a propósito — el servicio se baja
     * el MSI de la release oficial; si aceptara un path, cualquiera con acceso al
     * 8080 (que escucha en toda la LAN) podría hacerle ejecutar un instalador
     * arbitrario como SYSTEM.
     *
     * @return true si el servicio cogió el encargo.
     */
    public boolean startServiceUpdate(String apiBaseUrl, String bearer) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/system/update"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + bearer)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return r.statusCode() >= 200 && r.statusCode() < 300;
        } catch (Exception ex) {
            System.err.println("[UpdateService] el servicio no cogió la actualización: " + ex);
            return false;
        }
    }

    /** Progreso que reporta el servicio, para la barra. */
    public ServiceUpdateStatus serviceUpdateStatus(String apiBaseUrl, String bearer) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/system/update/status"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + bearer)
                    .GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            String b = r.body();
            return new ServiceUpdateStatus(field(b, "state"),
                    longField(b, "bytesDone"), longField(b, "bytesTotal"), field(b, "message"));
        } catch (Exception ex) {
            return null;
        }
    }

    private static long longField(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    /**
     * Descarga el instalador a un temporal y devuelve la ruta.
     *
     * <p>UPD-2 (2026-07-15, pedido de Benjamin): informa del avance. Antes usaba
     * {@code BodyHandlers.ofFile}, que no da progreso: el usuario aceptaba
     * "descargando…" y se quedaba varios minutos sin ver NADA mientras bajaban
     * 302 MB, con la app usable, hasta que se cerraba sola de golpe. Ahora se
     * copia el stream a mano contando bytes para poder pintar una barra.
     */
    public Path download(String url, ProgressListener listener)
            throws IOException, InterruptedException {
        Path out = Paths.get(System.getProperty("java.io.tmpdir"), "BENJAGEST-update.msi");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(15)).GET().build();
        HttpResponse<java.io.InputStream> r =
                http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
        long total = r.headers().firstValueAsLong("content-length").orElse(-1L);
        try (java.io.InputStream in = r.body();
             java.io.OutputStream os = java.nio.file.Files.newOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (listener != null) listener.onProgress(done, total);
            }
        }
        return out;
    }

    /** Atajo sin progreso. */
    public Path download(String url) throws IOException, InterruptedException {
        return download(url, null);
    }

    /**
     * UPD-3 (2026-07-15) — Deja un VIGÍA corriendo en la sesión del usuario que
     * reabrirá la app cuando el servicio termine de actualizar.
     *
     * <p>Hace falta porque lo que lanza un servicio corre en la <b>sesión 0</b>,
     * que no tiene escritorio: el helper del servicio no puede abrir ventanas ni
     * relanzar la app de forma visible. Este vigía sí — lo lanza la app, así que
     * hereda la sesión del usuario.
     *
     * <p><b>UPD-3b: el vigía NO puede quedarse dentro de la carpeta de
     * instalación.</b> {@code ProcessBuilder} sin {@code directory()} hereda el
     * directorio de trabajo del padre, y el acceso directo lanza la app con
     * {@code WorkingDirectory=C:\Program Files\BENJAGEST} (comprobado en el .lnk).
     * Es decir: el vigía se plantaba justo en la carpeta que msiexec va a
     * reemplazar, y durante los 20 minutos enteros. Medido: un proceso ahí NO
     * impide reemplazar ficheros ni borrar subcarpetas — solo impide renombrar o
     * borrar la carpeta raíz — así que no era la causa del 3010; pero en este
     * módulo, que ya rompió la instalación tres veces por procesos agarrados a
     * esa carpeta, no se deja al azar. Por eso el {@code directory(...)} de abajo
     * es obligatorio y no un detalle de estilo.
     *
     * @param marker fichero que el helper del servicio crea al terminar.
     */
    public void spawnRelauncher(Path installDir, Path marker) throws IOException {
        if (installDir == null) return;
        Path exe = installDir.resolve("BENJAGEST.exe");
        String cmd = "$m='" + psQuote(marker.toString()) + "';"
                // 20 min de margen: la descarga ya la hizo el servicio, pero la
                // instalación de 300 MB en un disco lento no es instantánea.
                + "for($i=0;$i -lt 600;$i++){ if(Test-Path -LiteralPath $m){break}; Start-Sleep -Seconds 2 };"
                + "if(Test-Path -LiteralPath $m){ Start-Sleep -Seconds 2; "
                + "Start-Process '" + psQuote(exe.toString()) + "' }";
        new ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass", "-Command", cmd)
                .directory(neutralWorkingDir(installDir).toFile())
                .start();
    }

    /**
     * Una carpeta de trabajo para el vigía que NO esté dentro de la instalación
     * (ver UPD-3b en {@link #spawnRelauncher}). Se prefiere el temporal del
     * usuario; si no sirviera, cualquier cosa menos {@code installDir}.
     */
    private static Path neutralWorkingDir(Path installDir) {
        Path canon = installDir == null ? null : installDir.toAbsolutePath().normalize();
        for (String candidate : new String[] {
                System.getProperty("java.io.tmpdir"),
                System.getenv("TEMP"),
                System.getProperty("user.home")}) {
            if (candidate == null || candidate.isBlank()) continue;
            Path p = Paths.get(candidate).toAbsolutePath().normalize();
            // startsWith: no vale con que sea distinta, no puede estar DENTRO.
            if (canon != null && p.startsWith(canon)) continue;
            if (Files.isDirectory(p)) return p;
        }
        // Último recurso: la raíz del disco. Feo, pero nunca bloquea la instalación.
        Path root = canon == null ? null : canon.getRoot();
        return root != null ? root : Paths.get(".").toAbsolutePath().normalize();
    }

    /**
     * @deprecated UPD-3 — La actualización la hace ahora el SERVICIO
     *     ({@code POST /api/system/update}), que ya es LocalSystem: sin UAC y,
     *     sobre todo, con la app muerta antes de que empiece la copia.
     *     Este camino elevaba un PowerShell desde la app y falló TRES veces el
     *     2026-07-15 — no por el servicio (que sí paraba), sino porque
     *     <b>la propia app</b> mantenía abiertos sus 39 jar de {@code app/} y el
     *     runtime; msiexec aplazaba el reemplazo al reinicio y dejaba el JRE a
     *     medias. Se conserva como plan B para instalaciones SIN servicio.
     */
    @Deprecated
    public void launchInstaller(Path msi, Path installDir) throws IOException {
        Path ps1 = java.nio.file.Files.createTempFile("benjagest-update", ".ps1");
        java.nio.file.Files.writeString(ps1, buildUpdateScript(msi, installDir));
        // UPD-2 (pedido de Benjamin): la ventana del actualizador va OCULTA y en
        // segundo plano. Antes salía al frente y era una consola normal: cerrarla
        // sin querer a mitad deja el servicio parado, el JRE sin verificar y la
        // app sin reabrir. Ahora no hay ventana que cerrar.
        //
        // Oculta NO significa mudo: msiexec /qb enseña su propia barra de progreso,
        // y si algo falla el script saca un MessageBox (que sí se ve aunque la
        // consola esté oculta — por eso se cambió el Read-Host, que en una consola
        // oculta habría esperado para siempre sin que nadie lo viera).
        new ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
                "Start-Process powershell -Verb RunAs -WindowStyle Hidden -ArgumentList "
                        + "'-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File','\""
                        + ps1 + "\"'")
                .start();
    }

    /**
     * Construye el PowerShell del actualizador. Extraído de
     * {@link #launchInstaller} para poder verificarlo sin actualizar de verdad:
     * un error de sintaxis aquí significa que la actualización no haría nada y
     * la app se cerraría igualmente, dejando al usuario sin app y sin pista.
     *
     * <p>El módulo ui no tiene junit configurado, así que en la 0.1.40 esto se
     * validó a mano: volcando el script y pasándolo por el parser real de
     * PowerShell ({@code [Parser]::ParseFile}). Si algún día se añaden tests al
     * ui, este método es el primer candidato.
     */
    static String buildUpdateScript(Path msi, Path installDir) {
        String dir = installDir == null ? "" : installDir.toString();
        return String.join("\r\n",
                "$ErrorActionPreference = 'Continue'",
                "$msi = '" + psQuote(msi.toString()) + "'",
                "$dir = '" + psQuote(dir) + "'",
                "Add-Type -AssemblyName System.Windows.Forms -ErrorAction SilentlyContinue",
                "function Aviso($texto, $icono) {",
                "  try { [System.Windows.Forms.MessageBox]::Show($texto, 'BENJAGEST', 'OK', $icono) | Out-Null }",
                "  catch { Write-Host $texto }",
                "}",
                "",
                "# 1) Parar el servicio (si existe). Pedimos su PID ANTES: es el java.exe",
                "#    que tiene mapeado runtime\\lib\\modules y hay que verlo MORIR.",
                "$svc = Get-Service -Name BenjagestBackend -ErrorAction SilentlyContinue",
                "$svcPid = 0",
                "if ($svc) {",
                "  try { $svcPid = (Get-CimInstance Win32_Service -Filter \"Name='BenjagestBackend'\").ProcessId } catch { $svcPid = 0 }",
                "  Write-Host 'Parando el servicio...'",
                "  Stop-Service -Name BenjagestBackend -Force -ErrorAction SilentlyContinue",
                "  try { (Get-Service BenjagestBackend).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60)) } catch {}",
                "}",
                "",
                "# 2) UPD-1: esperar a que el proceso MUERA de verdad. Esto es lo que",
                "#    faltaba y lo que rompio el JRE en la 0.1.39: 'net stop' vuelve cuando",
                "#    el SCM marca el servicio parado, pero java.exe tarda un instante mas en",
                "#    salir y en que el kernel desmapee modules -> msiexec lo encontraba en",
                "#    uso -> aplazaba a reinicio -> runtime a medias.",
                "if ($svcPid -gt 0) {",
                "  Write-Host \"Esperando a que termine el proceso del servicio (PID $svcPid)...\"",
                "  try { Wait-Process -Id $svcPid -Timeout 60 -ErrorAction SilentlyContinue } catch {}",
                "}",
                "",
                "# 3) Y a que no quede NADA corriendo desde la carpeta de instalacion (p.ej.",
                "#    un backend hijo lanzado por la UI, que el servicio no controla).",
                "#    Best-effort: si no podemos leer la ruta de un proceso, no lo sabremos;",
                "#    por eso ademas hay una espera fija abajo, que no depende de esto.",
                "function Holders {",
                "  if (-not $dir) { return @() }",
                "  Get-Process -ErrorAction SilentlyContinue | Where-Object {",
                "    try { $_.Path -and $_.Path.StartsWith($dir, 'OrdinalIgnoreCase') } catch { $false } }",
                "}",
                "for ($i = 0; $i -lt 60; $i++) {",
                "  $h = @(Holders)",
                "  if ($h.Count -eq 0) { break }",
                "  Write-Host \"Esperando a que se cierren: $(($h | ForEach-Object { $_.ProcessName }) -join ', ')\"",
                "  Start-Sleep -Seconds 1",
                "}",
                "$h = @(Holders)",
                "if ($h.Count -gt 0) {",
                "  Write-Host 'Siguen abiertos; los cierro para que el instalador pueda reemplazar el JRE.'",
                "  $h | ForEach-Object { try { $_.Kill() } catch {} }",
                "  Start-Sleep -Seconds 3",
                "}",
                "",
                "# 4) Colchon fijo: aunque creamos que no queda nadie, dar tiempo a que",
                "#    Windows suelte los ficheros mapeados. Barato comparado con dejar el",
                "#    JRE roto.",
                "Start-Sleep -Seconds 3",
                "",
                "# 5) Instalar. /norestart: NUNCA reiniciar por nuestra cuenta; si hiciera",
                "#    falta reiniciar es que algo sigue en uso -> preferimos enterarnos.",
                "#    /qb: el instalador enseña su propia barra (la consola va oculta).",
                "Write-Host 'Instalando...'",
                "$p = Start-Process msiexec -ArgumentList '/i', \"`\"$msi`\"\", '/qb', '/norestart' -Wait -PassThru",
                "$code = $p.ExitCode",
                "",
                "# 6) Verificar en vez de suponer.",
                "$modules = Join-Path $dir 'runtime\\lib\\modules'",
                "$ok = (Test-Path $modules)",
                "# La consola va OCULTA (UPD-2), asi que los avisos van por MessageBox:",
                "# un Read-Host aqui esperaria para siempre sin que nadie lo viera.",
                "if (-not $ok) {",
                "  Aviso \"La actualizacion no se completo bien: falta el runtime de Java.`n`nBENJAGEST no arrancara. Ejecuta el instalador a mano:`n$msi`n`n(codigo del instalador: $code)\" 'Error'",
                "} elseif ($code -eq 3010) {",
                "  Aviso 'La actualizacion se aplico, pero Windows pide reiniciar porque habia ficheros en uso. Si BENJAGEST no arranca, reinicia el equipo.' 'Warning'",
                "}",
                "",
                "# 7) Arrancar el servicio y volver a abrir la app.",
                "if ($svc) { Start-Service -Name BenjagestBackend -ErrorAction SilentlyContinue }",
                "$exe = Join-Path $dir 'BENJAGEST.exe'",
                "if ($ok -and (Test-Path $exe)) { Start-Process $exe }",
                "");
    }

    /** Escapa comillas simples para incrustar una ruta en un literal de PowerShell. */
    private static String psQuote(String s) {
        return s.replace("'", "''");
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
