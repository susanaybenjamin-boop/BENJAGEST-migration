package com.benjagest.backend.system;

import com.benjagest.backend.config.BenjagestHome;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * UPD-3 (2026-07-15) — La actualización la hace EL SERVICIO, no la app.
 *
 * <p><b>Por qué existe esto.</b> El actualizador rompió la instalación de
 * Benjamin TRES veces el mismo día. La causa real, probada mirando
 * {@code PendingFileRenameOperations} del registro (no deducida): cuando
 * msiexec iba a copiar, quedaban 41 ficheros en uso — <b>39 jar de {@code app\}
 * y el {@code runtime\lib\modules}</b>. Es decir, el que bloqueaba NO era el
 * servicio (donde yo había puesto todo el arreglo anterior): era <b>la propia
 * app que se estaba actualizando</b>. Windows aplazaba el reemplazo al
 * reinicio, marcaba la instalación como correcta, y dejaba el JRE a medias:
 * "Failed setting boot class path" y app muerta.
 *
 * <p><b>La solución.</b> El servicio corre como LocalSystem: <b>ya es
 * administrador</b>. Si actualiza él:
 * <ul>
 *   <li><b>No hay UAC</b> (petición expresa de Benjamin).</li>
 *   <li>La app está <b>muerta</b> antes de que empiece la copia — el helper
 *       espera a que desaparezca, así que no puede bloquear sus propios jar.</li>
 * </ul>
 *
 * <p><b>Seguridad: el servicio SE DESCARGA el MSI.</b> El endpoint NO acepta una
 * ruta. Si la aceptara, cualquiera que llegue al backend — que escucha en
 * 0.0.0.0:8080, o sea, accesible desde la LAN — podría hacer que un servicio
 * LocalSystem ejecutase un instalador arbitrario: elevación de privilegios de
 * manual. Aquí la URL sale del repo oficial, fijado en el código.
 *
 * <p><b>Y deja LOG.</b> El script anterior corría oculto y mudo, y por eso
 * fuimos a ciegas tres veces. Este escribe cada paso en
 * {@code %ProgramData%/BENJAGEST/logs/update.log} desde la primera línea.
 * (Barras normales a propósito: una barra invertida seguida de "u" es un escape
 * unicode para javac INCLUSO dentro de un comentario, y la ruta con barras de
 * Windows no compilaba. Escribir aquí el ejemplo literal tampoco compila.)
 *
 * <p><b>Sesión 0</b>: lo que lanza un servicio no tiene escritorio, así que el
 * helper NO puede enseñar ventanas ni reabrir la app. De reabrirla se encarga un
 * vigía que la propia app deja corriendo en la sesión del usuario antes de
 * cerrarse (ver {@code UpdateService} en el UI).
 */
@Service
public class SystemUpdateService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SystemUpdateService.class);

    private static final String RELEASES_LATEST =
            "https://api.github.com/repos/susanaybenjamin-boop/BENJAGEST-migration/releases/latest";

    /** Marcador que el helper crea al terminar: lo vigila el relanzador de la app. */
    public static final String DONE_MARKER = "update-done.marker";

    public enum State { IDLE, DOWNLOADING, INSTALLING, FAILED }

    public record Status(State state, long bytesDone, long bytesTotal, String message) {}

    private final AtomicReference<Status> status =
            new AtomicReference<>(new Status(State.IDLE, 0, 0, null));

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public Status status() {
        return status.get();
    }

    /**
     * Arranca la actualización en segundo plano: descarga el MSI de la release
     * oficial y lanza el helper. Devuelve enseguida; el progreso se consulta con
     * {@link #status()}.
     *
     * <p>Idempotente: si ya hay una en marcha, no arranca otra.
     */
    public synchronized void start() {
        // GUARD (2026-07-15, tras la primera prueba): SOLO actualiza el SERVICIO.
        //
        // Se descubrió probando: un backend de DESARROLLO, apuntando a una BD de
        // pruebas, se puso a actualizar la instalación REAL — porque el servicio,
        // el MSI y C:\Program Files\BENJAGEST son los de la máquina, da igual
        // contra qué base de datos apunte el backend. Es decir: cualquier backend
        // que alguien arranque a mano (Benjamin lo hace desde VS Code) exponía un
        // endpoint capaz de reinstalarle la app por debajo mientras trabaja.
        //
        // Un backend de desarrollo no tiene por qué tocar la instalación jamás.
        if (!runningAsService()) {
            log.warn("UPD-3: este backend NO es el servicio; me niego a actualizar. "
                    + "Actualizar solo tiene sentido desde la instalación real.");
            fail("Este backend no es el servicio instalado: la actualización solo "
                    + "se puede lanzar desde la instalación real.");
            return;
        }
        State s = status.get().state();
        if (s == State.DOWNLOADING || s == State.INSTALLING) {
            log.info("UPD-3: ya hay una actualización en marcha ({}); no arranco otra.", s);
            return;
        }
        status.set(new Status(State.DOWNLOADING, 0, 0, null));
        Thread t = new Thread(this::run, "benjagest-update");
        t.setDaemon(false); // que no la mate el cierre del contexto
        t.start();
    }

    private void run() {
        try {
            Path dir = BenjagestHome.resolve("updates");
            Files.createDirectories(dir);
            Files.deleteIfExists(dir.resolve(DONE_MARKER));

            String msiUrl = findMsiUrl();
            if (msiUrl == null) {
                fail("No encuentro el .msi en la última release.");
                return;
            }
            Path msi = dir.resolve("BENJAGEST-update.msi");
            download(msiUrl, msi);

            status.set(new Status(State.INSTALLING, 0, 0, null));
            launchHelper(msi, dir);
            // A partir de aquí manda el helper: para el servicio (nos mata) y
            // sigue solo. No hay más que hacer desde aquí.
        } catch (Exception ex) {
            log.warn("UPD-3: la actualización falló: {}", ex.toString());
            fail(ex.getMessage());
        }
    }

    private void fail(String msg) {
        status.set(new Status(State.FAILED, 0, 0, msg));
    }

    private String findMsiUrl() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(RELEASES_LATEST))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("GitHub HTTP " + r.statusCode());
        // Solo el MSI de Asesoría: "BENJAGEST-x.y.z.msi". El de Puesto se llama
        // "BENJAGEST Puesto-..." y no lleva backend — instalarlo aquí sería un
        // destrozo.
        Matcher m = Pattern.compile(
                "\"browser_download_url\"\\s*:\\s*\"([^\"]*/BENJAGEST-[0-9][^\"/]*\\.msi)\"")
                .matcher(r.body());
        return m.find() ? m.group(1) : null;
    }

    private void download(String url, Path out) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(20)).GET().build();
        HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("Descarga HTTP " + r.statusCode());
        }
        long total = r.headers().firstValueAsLong("content-length").orElse(-1L);
        try (InputStream in = r.body(); OutputStream os = Files.newOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                status.set(new Status(State.DOWNLOADING, done, total, null));
            }
        }
        log.info("UPD-3: MSI descargado en {} ({} bytes)", out, Files.size(out));
    }

    /**
     * Lanza el helper DESLIGADO: tiene que sobrevivir al {@code Stop-Service}
     * que él mismo va a hacer (que nos mata a nosotros). Por eso va con
     * {@code cmd /c start}, que corta el vínculo con este proceso.
     */
    private void launchHelper(Path msi, Path dir) throws IOException {
        Path installDir = installDir();
        Path ps1 = dir.resolve("update-helper.ps1");
        Files.writeString(ps1, buildHelperScript(msi, installDir, dir.resolve(DONE_MARKER),
                BenjagestHome.resolve("logs").resolve("update.log")));
        log.info("UPD-3: lanzando el helper {} (install dir: {})", ps1, installDir);
        new ProcessBuilder("cmd", "/c", "start", "/b", "powershell",
                "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                "-File", ps1.toString())
                .start();
    }

    /**
     * Carpeta de instalación: la del backend.jar que estamos ejecutando. El
     * servicio arranca con {@code -jar "%BASE%\backend.jar"}, así que el
     * code source apunta ahí.
     */
    /**
     * ¿Somos el backend del SERVICIO instalado, o un backend de desarrollo?
     *
     * <p>Se mira que estemos corriendo desde un {@code backend.jar} que viva
     * junto a un {@code BENJAGEST.exe} — es decir, dentro de una instalación de
     * verdad. En desarrollo el código sale de {@code target/classes} o de un jar
     * en {@code target/}, y ahí no hay ningún BENJAGEST.exe al lado.
     *
     * <p>Ante la duda, NO. Negarse de más solo obliga a instalar a mano; decir
     * que sí de más te reinstala la app por debajo mientras programas.
     */
    static boolean runningAsService() {
        try {
            Path src = Path.of(SystemUpdateService.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (!src.toString().toLowerCase().endsWith(".jar")) return false; // target/classes
            Path dir = src.getParent();
            return dir != null && Files.exists(dir.resolve("BENJAGEST.exe"));
        } catch (Exception ex) {
            log.warn("UPD-3: no pude saber si soy el servicio ({}); me niego por si acaso.",
                    ex.getMessage());
            return false;
        }
    }

    static Path installDir() {
        try {
            Path jar = Path.of(SystemUpdateService.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            // Spring Boot fat jar: .../BENJAGEST/backend.jar -> el padre.
            Path p = jar.getParent();
            return p == null ? Path.of("C:\\Program Files\\BENJAGEST") : p;
        } catch (Exception ex) {
            log.warn("UPD-3: no pude resolver la carpeta de instalación ({}); uso la de por defecto.",
                    ex.getMessage());
            return Path.of("C:\\Program Files\\BENJAGEST");
        }
    }

    /**
     * El helper. Extraído para poder validarlo con el parser de PowerShell sin
     * actualizar de verdad — un error de sintaxis aquí dejaría la app cerrada y
     * sin actualizar, que es justo lo que pasó hoy tres veces.
     */
    static String buildHelperScript(Path msi, Path installDir, Path marker, Path logFile) {
        return String.join("\r\n",
                "$ErrorActionPreference = 'Continue'",
                "$msi = '" + q(msi) + "'",
                "$dir = '" + q(installDir) + "'",
                "$marker = '" + q(marker) + "'",
                "$logFile = '" + q(logFile) + "'",
                "New-Item -ItemType Directory -Force -Path (Split-Path $logFile) | Out-Null",
                "function L($m) {",
                "  $line = '[{0}] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $m",
                "  Add-Content -LiteralPath $logFile -Value $line",
                "}",
                "L '===== UPD-3: arranca el helper (lo lanza el SERVICIO, sin UAC) ====='",
                "L (\"msi: $msi\"); L (\"dir: $dir\")",
                "",
                "# 1) Esperar a que MUERA LA APP. Esto es lo que faltaba: los 39 jar de",
                "#    app\\ y el runtime\\lib\\modules los tenia abiertos ELLA, no el",
                "#    servicio. Se espera POR NOMBRE: la ruta ($_.Path) de los procesos",
                "#    de otra sesion/usuario NO se puede leer y dejaba la comprobacion",
                "#    ciega (comprobado el 2026-07-15).",
                "for ($i = 0; $i -lt 60; $i++) {",
                "  $app = @(Get-Process -Name 'BENJAGEST' -ErrorAction SilentlyContinue)",
                "  if ($app.Count -eq 0) { break }",
                "  if ($i -eq 0) { L (\"esperando a que se cierre la app (PIDs: $($app.Id -join ','))\") }",
                "  Start-Sleep -Seconds 1",
                "}",
                "$app = @(Get-Process -Name 'BENJAGEST' -ErrorAction SilentlyContinue)",
                "if ($app.Count -gt 0) {",
                "  L 'la app sigue abierta tras 60s; la cierro yo (si no, msiexec aplaza al reinicio)'",
                "  $app | ForEach-Object { try { $_.Kill() } catch { L (\"no pude cerrarla: $_\") } }",
                "  Start-Sleep -Seconds 3",
                "} else { L 'la app ya esta cerrada' }",
                "",
                "# 2) Parar el servicio. Nos mata a nosotros (somos su hijo), pero este",
                "#    proceso va desligado con 'cmd /c start' y sobrevive.",
                "$svc = Get-Service -Name BenjagestBackend -ErrorAction SilentlyContinue",
                "if ($svc) {",
                "  $svcPid = 0",
                "  try { $svcPid = (Get-CimInstance Win32_Service -Filter \"Name='BenjagestBackend'\").ProcessId } catch {}",
                "  L (\"parando el servicio (PID $svcPid)\")",
                "  Stop-Service -Name BenjagestBackend -Force -ErrorAction SilentlyContinue",
                "  try { (Get-Service BenjagestBackend).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(90)) } catch { L \"WaitForStatus: $_\" }",
                "  if ($svcPid -gt 0) { try { Wait-Process -Id $svcPid -Timeout 60 -ErrorAction SilentlyContinue } catch {} }",
                "  L 'servicio parado'",
                "} else { L 'no hay servicio instalado' }",
                "",
                "# 3) Y a que muera su java (tiene mapeado runtime\\lib\\modules, el fichero",
                "#    de 119 MB que rompio la app dos veces).",
                "for ($i = 0; $i -lt 60; $i++) {",
                "  $j = @(Get-Process -Name 'java','benjagest-backend' -ErrorAction SilentlyContinue)",
                "  if ($j.Count -eq 0) { break }",
                "  if ($i -eq 0) { L (\"esperando a java/benjagest-backend (PIDs: $($j.Id -join ','))\") }",
                "  Start-Sleep -Seconds 1",
                "}",
                "$j = @(Get-Process -Name 'java','benjagest-backend' -ErrorAction SilentlyContinue)",
                "if ($j.Count -gt 0) { L (\"OJO: siguen vivos java/benjagest-backend: $($j.Id -join ',')\") }",
                "Start-Sleep -Seconds 3",
                "",
                "# 4) Instalar. /norestart: no reiniciamos por nuestra cuenta; si Windows lo",
                "#    pide es que algo seguia en uso -> queremos enterarnos, no ocultarlo.",
                "L 'lanzando msiexec'",
                "$p = Start-Process msiexec -ArgumentList '/i', \"`\"$msi`\"\", '/qn', '/norestart' -Wait -PassThru",
                "$code = $p.ExitCode",
                "L (\"msiexec termino con codigo $code\")",
                "if ($code -eq 3010) { L 'AVISO: 3010 = habia ficheros en uso y Windows aplazo al reinicio. ESTO ES EL BUG.' }",
                "",
                "# 5) Verificar, no suponer.",
                "$modules = Join-Path $dir 'runtime\\lib\\modules'",
                "if (Test-Path $modules) {",
                "  L (\"OK: runtime\\lib\\modules presente ({0:N1} MB)\" -f ((Get-Item $modules).Length/1MB))",
                "} else {",
                "  L 'ERROR: FALTA runtime\\lib\\modules -> el JRE quedo roto y la app NO arrancara'",
                "}",
                "# La cola de borrado al reiniciar es la que dejo la app muerta el 15-jul.",
                "$pending = (Get-ItemProperty 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\Session Manager' -Name PendingFileRenameOperations -ErrorAction SilentlyContinue)",
                "if ($pending) {",
                "  $n = @($pending.PendingFileRenameOperations | Where-Object { $_ -match 'BENJAGEST' }).Count",
                "  if ($n -gt 0) { L (\"AVISO: quedan $n ficheros de BENJAGEST en la cola de borrado al reiniciar\") }",
                "}",
                "",
                "# 6) Arrancar el servicio y avisar al vigia de que ya puede reabrir la app.",
                "if ($svc) {",
                "  Start-Service -Name BenjagestBackend -ErrorAction SilentlyContinue",
                "  L (\"servicio: \" + (Get-Service BenjagestBackend).Status)",
                "}",
                "Set-Content -LiteralPath $marker -Value $code",
                "L '===== fin ====='",
                "");
    }

    private static String q(Path p) {
        return p.toString().replace("'", "''");
    }
}
