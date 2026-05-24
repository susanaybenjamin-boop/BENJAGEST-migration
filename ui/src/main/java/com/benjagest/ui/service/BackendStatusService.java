package com.benjagest.ui.service;

import com.benjagest.ui.model.BackendStatus;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class BackendStatusService {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";

    private final HttpClient httpClient;
    private final URI healthUri;

    public BackendStatusService() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), apiBaseUrl());
    }

    BackendStatusService(HttpClient httpClient, String apiBaseUrl) {
        this.httpClient = httpClient;
        this.healthUri = URI.create(apiBaseUrl.replaceAll("/+$", "") + "/health");
    }

    public BackendStatus fetchHealth() {
        HttpRequest request = HttpRequest.newBuilder(healthUri)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            String message = ok
                    ? "Servicio disponible (" + response.statusCode() + ")"
                    : "El servicio responde con HTTP " + response.statusCode();
            return new BackendStatus(ok, response.statusCode(), message);
        } catch (IOException exception) {
            return new BackendStatus(false, 0, "Servicio no disponible: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new BackendStatus(false, 0, "Comprobacion interrumpida");
        }
    }

    private static String apiBaseUrl() {
        return System.getenv().getOrDefault("BENJAGEST_API_BASE_URL", DEFAULT_API_BASE_URL);
    }
}
