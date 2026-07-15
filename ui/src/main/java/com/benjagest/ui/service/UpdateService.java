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
    public static final String APP_VERSION = "0.1.41";

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

    /** Avisa del avance de la descarga. {@code total} es -1 si el servidor no lo dice. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long bytesDone, long bytesTotal);
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
     * Lanza el instalador. El caller debe cerrar la app a continuación.
     *
     * <p><b>Para el servicio BenjagestBackend ANTES de instalar</b> (y lo arranca
     * después). Motivo (incidente 0.1.36, 2026-07-12): si el MSI corre con el
     * servicio en marcha, el {@code java.exe} tiene bloqueado
     * {@code runtime\lib\modules} (~125 MB) → el MSI no puede reemplazarlo en
     * caliente y lo aplaza al reinicio ("a reboot will be required"); esa
     * operación diferida puede quedar a medias y dejar el JRE roto ("Failed
     * setting boot class path").
     *
     * <p><b>UPD-1 (2026-07-15) — parar el servicio NO bastaba.</b> La actualización
     * 0.1.38→0.1.39 volvió a romper el JRE de Benjamin. Evidencia del visor de
     * eventos (no deducción): la transacción de {@code Temp\BENJAGEST-update.msi}
     * acabó en <i>Product: BENJAGEST -- Installation failed</i> (evento 11708) y
     * en <i>"Windows Installer requiere un reinicio del sistema … Motivo del
     * reinicio: 1"</i> (evento 1038) = <b>ficheros en uso</b>. Tras el reinicio el
     * runtime quedó sin {@code lib\modules} y la app no arrancaba ni como admin.
     *
     * <p>Por qué no bastaba: el servicio corre con <b>el propio JRE que el MSI
     * reemplaza</b> ({@code <executable>%BASE%\runtime\bin\java.exe</executable>}
     * en benjagest-backend.xml), y aquí no había NINGUNA espera entre el
     * {@code net stop} y el {@code msiexec}. {@code net stop} vuelve cuando el SCM
     * marca el servicio como parado, pero el {@code java.exe} tarda un instante más
     * en morir y en que el kernel desmapee {@code modules}. msiexec llegaba a
     * tiempo de encontrarlo en uso. Y hay un segundo tenedor que {@code net stop}
     * NO toca: el backend hijo que la UI podía lanzar (ver LAUNCH-1 en
     * {@code Launcher}), que corre con ese mismo java.exe.
     *
     * <p>Ahora el script ESPERA a que no quede ningún proceso corriendo desde la
     * carpeta de instalación, y VERIFICA el resultado en vez de darlo por bueno:
     * detecta el 3010 de msiexec (= "reboot requerido" = la trampa) y comprueba que
     * {@code runtime\lib\modules} exista al terminar. Si algo va mal, deja el aviso
     * en pantalla en vez de dejar una app que no arranca sin explicación.
     *
     * <p>Todo en un único proceso elevado (una sola UAC). Tolerante: si el servicio
     * no existe (variante Puesto) los stop/start se saltan y el resto sigue igual.
     *
     * @param installDir carpeta de instalación (la que el MSI va a reemplazar).
     */
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
