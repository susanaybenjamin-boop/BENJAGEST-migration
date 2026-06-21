package com.benjagest.ui.service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * NOTIF-RT — Cliente SSE del escritorio. Mantiene una conexión a
 * {@code /api/realtime/stream} y entrega cada evento (tipo, datos) al callback,
 * que el llamante marshalla al hilo de JavaFX. Reemplaza el sondeo periódico:
 * el backend empuja y la UI refresca solo lo afectado.
 *
 * <p>Reconecta solo si la conexión cae (backoff fijo). Hilo daemon: no impide
 * cerrar la app. Best-effort: si falla, la UI sigue funcionando (con su refresco
 * manual / pollers de respaldo).
 */
public class RealtimeClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";

    private final String baseUrl;
    private final BiConsumer<String, String> onEvent;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private volatile boolean running;
    private Thread thread;

    public RealtimeClient(BiConsumer<String, String> onEvent) {
        this.baseUrl = System.getenv().getOrDefault("BENJAGEST_API_BASE_URL", DEFAULT_API_BASE_URL);
        this.onEvent = onEvent;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "sse-realtime-client");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) { thread.interrupt(); thread = null; }
    }

    private void loop() {
        while (running) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/realtime/stream"))
                        .header("Accept", "text/event-stream")
                        .GET();
                AuthSession.get().authorize(builder);
                HttpResponse<InputStream> resp = httpClient.send(
                        builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    backoff();
                    continue;
                }
                readStream(resp.body());
            } catch (Exception e) {
                // conexión caída o sin backend: reintentar tras pausa.
            }
            if (running) backoff();
        }
    }

    /** Parsea el stream SSE línea a línea (event:/data:/línea en blanco = fin de evento). */
    private void readStream(InputStream body) throws Exception {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            String event = "message";
            StringBuilder data = new StringBuilder();
            while (running && (line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0 || !"message".equals(event)) {
                        try { onEvent.accept(event, data.toString()); } catch (Exception ignored) {}
                    }
                    event = "message";
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                }
                // líneas que empiezan por ':' son comentarios/heartbeat: se ignoran.
            }
        }
    }

    private void backoff() {
        try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
