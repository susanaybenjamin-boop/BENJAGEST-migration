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
            // proceso peleando por el puerto 13307 y el lock del data dir. Solo se
            // auto-arranca si NADIE responde (instalación sin servicio, o servicio
            // aún no levantado).
            if (isBackendUp()) {
                System.out.println("[Launcher] Backend ya en marcha (servicio) en http://localhost:8080; no lo lanzo.");
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

    /** Sonda rápida: ¿responde ya algo en el 8080? (el servicio de Windows). */
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

        waitForApi();
    }

    private static void waitForApi() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(HEALTH_URL))
                .timeout(Duration.ofSeconds(2)).GET().build();
        for (int i = 0; i < 90; i++) {
            if (backend != null && !backend.isAlive()) {
                System.err.println("[Launcher] El backend terminó inesperadamente (código "
                        + backend.exitValue() + "). Revisa ~/.benjagest/backend.log");
                return;
            }
            try {
                client.send(req, HttpResponse.BodyHandlers.discarding());
                System.out.println("[Launcher] Backend listo en http://localhost:8080");
                return; // cualquier respuesta HTTP (incl. 404) = Tomcat sirviendo
            } catch (IOException connecting) {
                // aún no escucha: esperar y reintentar
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.err.println("[Launcher] El backend no respondió a tiempo; abro la UI igualmente.");
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
