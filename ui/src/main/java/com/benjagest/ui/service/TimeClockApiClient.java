package com.benjagest.ui.service;

import com.benjagest.ui.model.TimeClockEntry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente HTTP minimo para el modulo Fichajes (RD 8/2019).
 *
 * No hereda del BillingApiClient porque vive en otro modulo
 * funcional. Comparte el patron de envio: HttpClient + Bearer del
 * AuthSession + parseo JSON via regex.
 */
public class TimeClockApiClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String baseUrl;

    public TimeClockApiClient() {
        this(System.getenv().getOrDefault("BENJAGEST_API_BASE_URL", DEFAULT_API_BASE_URL));
    }

    public TimeClockApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Registra un fichaje y devuelve el CSV emitido por el backend.
     * Si todo va bien, el dialog de la UI lo muestra al trabajador
     * para que lo guarde (mail, capturilla, etc).
     */
    public String punch(String employeeId, String eventType) throws IOException, InterruptedException {
        String body = "{\"employeeId\":\"" + employeeId
                + "\",\"eventType\":\"" + eventType
                + "\",\"origin\":\"WEB\"}";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/timeclock/punch"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        AuthSession.get().authorize(builder);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        Matcher m = Pattern.compile("\"csv\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
        return m.find() ? m.group(1) : "";
    }

    public List<TimeClockEntry> recent(String employeeId, int limit) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                baseUrl + "/timeclock/employee/" + employeeId + "/recent?limit=" + limit))
                .timeout(Duration.ofSeconds(10))
                .GET();
        AuthSession.get().authorize(builder);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return parseEntries(response.body());
    }

    private List<TimeClockEntry> parseEntries(String json) {
        // Parser minimal por regex — el endpoint devuelve un array JSON
        // plano de objetos sin anidamientos.
        List<TimeClockEntry> list = new ArrayList<>();
        Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(json);
        while (m.find()) {
            String obj = m.group();
            if (!obj.contains("\"eventType\"")) continue;
            list.add(new TimeClockEntry(
                    extract(obj, "id"),
                    extract(obj, "employeeId"),
                    extract(obj, "eventType"),
                    extract(obj, "eventTime"),
                    extract(obj, "origin"),
                    extract(obj, "status")
            ));
        }
        return list;
    }

    private String extract(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }
}
