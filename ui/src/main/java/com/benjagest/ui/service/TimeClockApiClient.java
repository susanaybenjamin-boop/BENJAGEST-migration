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
     * Resuelve el {@code employees.id} del usuario logueado en la
     * empresa activa (tenant) antes de fichar. Lanza
     * {@link NotEnrolledException} si el backend devuelve 404 (el
     * usuario no tiene ficha de empleado en esta empresa).
     */
    public MyEmployee me() throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/timeclock/me/employee"))
                .timeout(Duration.ofSeconds(10)).GET();
        AuthSession.get().authorize(builder);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            // Mensaje legible viene del backend ("Tu usuario no tiene
            // ficha de empleado en esta empresa…"). Lo expone tal cual.
            throw new NotEnrolledException(extractMessage(response.body()));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return new MyEmployee(
                extract(response.body(), "employeeId"),
                extract(response.body(), "fullName"));
    }

    public record MyEmployee(String employeeId, String fullName) {}

    /** El usuario no tiene fila en {@code employees} para el tenant activo. */
    public static class NotEnrolledException extends IOException {
        public NotEnrolledException(String msg) { super(msg); }
    }

    /** Extrae el campo "message" de un body JSON de error de Spring. */
    private String extractMessage(String body) {
        Matcher m = Pattern.compile("\"message\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(body == null ? "" : body);
        return m.find() ? m.group(1).replace("\\\"", "\"") : "Sin ficha de empleado";
    }

    /**
     * Registra un fichaje y devuelve el CSV emitido por el backend.
     * Si todo va bien, el dialog de la UI lo muestra al trabajador
     * para que lo guarde (mail, capturilla, etc).
     */
    public PunchOutcome punch(String employeeId, String eventType) throws IOException, InterruptedException {
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
        String responseBody = response.body();
        Matcher m = Pattern.compile("\"csv\"\\s*:\\s*\"([^\"]+)\"").matcher(responseBody);
        String csv = m.find() ? m.group(1) : "";
        // TC-CAL — parsear holidayWarning si el backend lo devolvio.
        // Estructura JSON: "holidayWarning":{"holidayName":"...","holidayType":"FESTIVO","scope":"NATIONAL"}
        String holidayName = null, holidayType = null, holidayScope = null;
        Matcher mw = Pattern.compile(
                "\"holidayWarning\"\\s*:\\s*\\{([^}]*)\\}").matcher(responseBody);
        if (mw.find()) {
            String inner = mw.group(1);
            Matcher mn = Pattern.compile("\"holidayName\"\\s*:\\s*\"([^\"]*)\"").matcher(inner);
            if (mn.find()) holidayName = mn.group(1);
            Matcher mt = Pattern.compile("\"holidayType\"\\s*:\\s*\"([^\"]*)\"").matcher(inner);
            if (mt.find()) holidayType = mt.group(1);
            Matcher ms = Pattern.compile("\"scope\"\\s*:\\s*\"([^\"]*)\"").matcher(inner);
            if (ms.find()) holidayScope = ms.group(1);
        }
        return new PunchOutcome(csv, holidayName, holidayType, holidayScope);
    }

    /** TC-CAL — Resultado de un fichaje: CSV + warning opcional. */
    public record PunchOutcome(String csv, String holidayName,
                                String holidayType, String holidayScope) {
        public boolean hasHolidayWarning() { return holidayName != null; }
    }

    /**
     * Descarga el PDF verificable de fichajes en un rango. Devuelve
     * los bytes para que la UI los guarde con FileChooser.
     */
    public byte[] exportPdf(String fromIso, String toIso, String employeeId)
            throws IOException, InterruptedException {
        return doExport("pdf", fromIso, toIso, employeeId);
    }

    public byte[] exportCsv(String fromIso, String toIso, String employeeId)
            throws IOException, InterruptedException {
        return doExport("csv", fromIso, toIso, employeeId);
    }

    private byte[] doExport(String format, String fromIso, String toIso, String employeeId)
            throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/timeclock/export.").append(format)
                .append("?from=").append(fromIso).append("&to=").append(toIso);
        if (employeeId != null && !employeeId.isBlank()) {
            url.append("&employeeId=").append(employeeId);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(30)).GET();
        AuthSession.get().authorize(builder);
        HttpResponse<byte[]> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * FJ-2/FJ-3 — Sugerencia de "qué toca fichar ahora" según la jornada
     * asignada. Reutiliza el endpoint del portal del empleado, que resuelve
     * el empleado del JWT (en escritorio = el propio usuario logueado).
     */
    public Suggestion suggestion() throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/empleado/fichaje/sugerencia"))
                .timeout(Duration.ofSeconds(10)).GET();
        AuthSession.get().authorize(builder);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        String b = response.body();
        return new Suggestion(
                bool(b, "hasSchedule"),
                emptyToNull(extract(b, "action")),
                emptyToNull(extract(b, "scheduledTime")),
                emptyToNull(extract(b, "windowFrom")),
                emptyToNull(extract(b, "windowTo")),
                bool(b, "inWindow"));
    }

    /** Sugerencia de fichaje por jornada (FJ). {@code action} null = jornada completa. */
    public record Suggestion(boolean hasSchedule, String action, String scheduledTime,
                             String windowFrom, String windowTo, boolean inWindow) {
        public boolean hasAction() { return action != null && !action.isBlank(); }
    }

    private boolean bool(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() && "true".equals(m.group(1));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
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
