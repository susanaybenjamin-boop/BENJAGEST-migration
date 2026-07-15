package com.benjagest.ui;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * DEPLOY-PKG — Punto de entrada del INSTALABLE autocontenido (app-image jpackage).
 *
 * <p>Esta clase NO extiende {@code javafx.application.Application} a propósito: si
 * la clase main de un app de JavaFX lanzado por classpath extiende Application, la
 * JVM intenta arrancarlo por la vía modular de JavaFX y falla con "JavaFX runtime
 * components are missing". Con un main "normal" que llama después a
 * {@link BenjagestUiApplication#main(String[])}, JavaFX arranca desde el classpath
 * sin ese problema.
 *
 * <p>Cuando se arranca con {@code -Dbenjagest.launch.backend=true} (lo pone el
 * instalable vía {@code --java-options}), antes de abrir la UI arranca el backend
 * Spring Boot como PROCESO HIJO usando la MariaDB embebida y la JRE empaquetada,
 * espera a que la API responda y registra un hook para pararlo al cerrar. En
 * desarrollo (sin esa propiedad) se comporta como un main normal: solo abre la UI
 * y se asume un backend ya levantado (localhost:8080 por defecto).
 */
public final class Launcher {

    private static final String HEALTH_URL = "http://localhost:8080/api/health";
    private static Process backend;

    public static void main(String[] args) {
        if (Boolean.getBoolean("benjagest.launch.backend")) {
            // WINSVC (2026-07-12): en la instalación de Asesoría el backend corre
            // como SERVICIO de Windows (arranca en el boot, antes del login). Si ya
            // está sirviendo en 8080, la UI NO debe lanzar otro: sería un segundo
            // proceso peleando por el puerto 13307 y el lock del data dir.
            //
            // LAUNCH-1 (2026-07-15) — Bug de Benjamin al reinstalar la 0.1.39: "levanta
            // el backend pero no levanta la bd"; a la segunda entraba. Aquí había UNA
            // sonda de 1 segundo, y el servicio tarda ~6 s en responder (arranca su
            // MariaDB embebida primero). Si abrías la app dentro de esa ventana, la
            // sonda fallaba y se lanzaba un backend HIJO con el usuario de escritorio.
            // Ese hijo NO PUEDE FUNCIONAR NUNCA en una instalación con servicio: el
            // data dir (%ProgramData%\BENJAGEST\mariadb-data) es de LocalSystem y su
            // MariaDB moría con "Can't create/write to file '.\ddl_recovery.log'
            // (Errcode: 13 Permission denied)" -> DataSource -> Application run failed.
            //
            // Por eso: si el servicio ESTÁ INSTALADO, jamás lanzamos backend propio.
            // Esperamos a que responda. Solo se auto-arranca el backend cuando no hay
            // servicio (instalación sin servicio / desarrollo).
            if (windowsServiceInstalled()) {
                System.out.println("[Launcher] Servicio BenjagestBackend instalado: "
                        + "espero a que responda (no lanzo backend propio).");
                if (waitForApi(SERVICE_WAIT_SECONDS)) {
                    System.out.println("[Launcher] Backend del servicio listo en " + HEALTH_URL);
                } else {
                    System.err.println("[Launcher] El servicio BenjagestBackend no respondió en "
                            + SERVICE_WAIT_SECONDS + " s. Abro la UI igualmente; si no puedes entrar, "
                            + "arranca el servicio (services.msc -> BenjagestBackend) y reabre. "
                            + "NO lanzo un backend propio: no podría abrir la base de datos del "
                            + "servicio y moriría con 'Permission denied'.");
                }
            } else if (isBackendUp()) {
                System.out.println("[Launcher] Backend ya en marcha en " + HEALTH_URL + "; no lo lanzo.");
            } else {
                try {
                    startEmbeddedBackend();
                } catch (Exception ex) {
                    System.err.println("[Launcher] No se pudo arrancar el backend embebido: " + ex);
                }
            }
        }
        BenjagestUiApplication.main(args);
    }

    /** Cuánto esperamos al servicio antes de abrir la UI igualmente. */
    private static final int SERVICE_WAIT_SECONDS = 90;

    /**
     * Carpeta de instalación (la que contiene BENJAGEST.exe, runtime\ y backend.jar),
     * o null si no se puede resolver (p.ej. corriendo desde el IDE).
     *
     * <p>UPD-1: la usa {@code UpdateService.launchInstaller} para esperar a que nadie
     * esté ejecutando nada desde ahí antes de dejar entrar al MSI. Misma resolución
     * que {@link #startEmbeddedBackend()}: el jar vive en {@code <root>\app\}.
     */
    public static java.nio.file.Path installDir() {
        try {
            Path appDir = Paths.get(Launcher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParent();
            return appDir == null ? null : appDir.getParent();
        } catch (Exception ex) {
            System.err.println("[Launcher] No pude resolver la carpeta de instalación: " + ex);
            return null;
        }
    }

    /**
     * ¿Está instalado el servicio de Windows del backend? Se pregunta a Windows
     * ({@code sc query}) en vez de mirar si existe {@code benjagest-backend.exe}
     * junto a la app: ese fichero lo trae el instalable de Asesoría SIEMPRE, pero
     * el servicio solo existe si se llegó a ejecutar install-service.
     *
     * <p>Cualquier estado vale (RUNNING, START_PENDING, STOPPED): si el servicio
     * existe, el data dir es suyo y un backend nuestro no podría abrirlo.
     * Ante la duda devolvemos false (comportamiento anterior).
     */
    private static boolean windowsServiceInstalled() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return false;
        try {
            Process p = new ProcessBuilder("sc", "query", "BenjagestBackend")
                    .redirectErrorStream(true).start();
            boolean done = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return false; }
            // exit 0 = el servicio existe; 1060 = no existe.
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("[Launcher] No pude consultar el servicio (" + ex + "); "
                    + "sigo por el camino sin servicio.");
            return false;
        }
    }

    /** Sonda rápida: ¿responde ya algo en el 8080? */
    private static boolean isBackendUp() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(HEALTH_URL))
                .timeout(Duration.ofSeconds(1)).GET().build();
        try {
            client.send(req, HttpResponse.BodyHandlers.discarding());
            return true; // cualquier respuesta HTTP = Tomcat sirviendo
        } catch (IOException connecting) {
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void startEmbeddedBackend() throws Exception {
        Path appDir = Paths.get(
                Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
        Path root = appDir.getParent();
        Path backendJar = root.resolve("backend.jar");
        Path javaExe = root.resolve("runtime").resolve("bin").resolve("java.exe");

        if (!Files.exists(backendJar)) {
            System.err.println("[Launcher] No encuentro backend.jar en " + backendJar + " — sigo solo con la UI.");
            return;
        }
        String javaCmd = Files.exists(javaExe) ? javaExe.toString() : "java";

        Path home = Paths.get(System.getProperty("user.home"), ".benjagest");
        Files.createDirectories(home);
        Path logFile = home.resolve("backend.log");

        System.out.println("[Launcher] Arrancando backend embebido: " + backendJar);
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javaCmd);
        cmd.add("-Dbenjagest.db.embedded=true");
        // tessdata empaquetado (idiomas del OCR del bloque MIG), si viene en el instalable.
        Path tessdata = root.resolve("tessdata");
        if (Files.isDirectory(tessdata)) {
            cmd.add("-Dbenjagest.tessdata=" + tessdata);
        }
        cmd.add("-jar");
        cmd.add(backendJar.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());
        backend = pb.start();

        Runtime.getRuntime().addShutdownHook(new Thread(Launcher::stopBackend));

        if (waitForApi(180)) {
            System.out.println("[Launcher] Backend listo en " + HEALTH_URL);
        } else {
            System.err.println("[Launcher] El backend no respondió a tiempo; abro la UI igualmente.");
        }
    }

    /**
     * Espera hasta {@code seconds} a que el 8080 responda, sondeando cada 2 s.
     *
     * <p>LAUNCH-1: lo usan los DOS caminos — esperar al servicio de Windows (que
     * puede tardar ~6 s en levantar su MariaDB embebida, y bastante más si el
     * equipo acaba de arrancar) y esperar al backend hijo que lanzamos nosotros.
     * Si tenemos hijo y muere, se corta antes: no tiene sentido seguir sondeando.
     *
     * @return true si el backend respondió.
     */
    private static boolean waitForApi(int seconds) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(HEALTH_URL))
                .timeout(Duration.ofSeconds(2)).GET().build();
        int attempts = Math.max(1, seconds / 2);
        for (int i = 0; i < attempts; i++) {
            if (backend != null && !backend.isAlive()) {
                System.err.println("[Launcher] El backend terminó inesperadamente (código "
                        + backend.exitValue() + "). Revisa ~/.benjagest/backend.log");
                return false;
            }
            try {
                client.send(req, HttpResponse.BodyHandlers.discarding());
                return true; // cualquier respuesta HTTP (incl. 404) = Tomcat sirviendo
            } catch (IOException connecting) {
                // aún no escucha: esperar y reintentar
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void stopBackend() {
        if (backend == null) return;
        // Capturar los hijos (mariadbd embebido) ANTES de matar el backend para
        // cerrarlos también. En Windows Process.destroy() es forzoso: mata el
        // backend sin que corra su @PreDestroy, y mariadbd quedaría huérfano
        // reteniendo el data dir -> el siguiente arranque falla ("ibdata1 must
        // be writable"). Matando los descendientes evitamos el huérfano.
        java.util.List<ProcessHandle> children =
                backend.descendants().collect(java.util.stream.Collectors.toList());
        backend.destroy();
        try {
            backend.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (backend.isAlive()) backend.destroyForcibly();
        for (ProcessHandle child : children) {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    private Launcher() {
    }
}
