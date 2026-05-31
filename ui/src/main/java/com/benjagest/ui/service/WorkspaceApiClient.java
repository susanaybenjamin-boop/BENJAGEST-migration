package com.benjagest.ui.service;

import com.benjagest.ui.model.DashboardData;
import com.benjagest.ui.model.DashboardItem;
import com.benjagest.ui.model.ModuleData;
import com.benjagest.ui.model.ModuleRow;
import com.benjagest.ui.model.SessionInfo;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WorkspaceApiClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";

    private final HttpClient httpClient;
    private final String apiBaseUrl;

    public WorkspaceApiClient() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.apiBaseUrl = System.getenv().getOrDefault("BENJAGEST_API_BASE_URL", DEFAULT_API_BASE_URL).replaceAll("/+$", "");
    }

    public SessionInfo loginWithPin(String pin) throws IOException, InterruptedException {
        String body = "{\"pin\":\"" + escape(pin) + "\"}";
        String json = send("POST", "/auth/pin", body);
        return new SessionInfo(
                textField(json, "employeeId"),
                textField(json, "employeeName"),
                textField(json, "companyId"),
                textField(json, "companyName"),
                textField(json, "role"),
                textField(json, "token"),
                textField(json, "defaultMode")
        );
    }

    public DashboardData dashboard(String mode) throws IOException, InterruptedException {
        String json = send("GET", "/dashboard?mode=" + mode, null);
        return new DashboardData(
                valueField(json, "customers"),
                valueField(json, "invoices"),
                valueField(json, "employees"),
                valueField(json, "openAlerts"),
                valueField(json, "billed"),
                valueField(json, "pending"),
                valueField(json, "expenses"),
                valueField(json, "payrollNet"),
                dashboardItems(json, "latestInvoices"),
                dashboardItems(json, "alerts"),
                dashboardItems(json, "calendar")
        );
    }

    public ModuleData module(String module, String mode) throws IOException, InterruptedException {
        String json = send("GET", "/modules/" + module + "?mode=" + mode, null);
        return new ModuleData(textField(json, "module"), textField(json, "title"), rows(json));
    }

    public ModuleRow create(String module, Map<String, String> fields) throws IOException, InterruptedException {
        String json = send("POST", "/modules/" + module, toJson(fields));
        List<ModuleRow> rows = rows("{\"records\":[" + json + "]}");
        return rows.isEmpty() ? new ModuleRow("", Map.of("estado", "Creado")) : rows.getFirst();
    }

    public ModuleRow update(String module, String id, Map<String, String> fields) throws IOException, InterruptedException {
        String json = send("PUT", "/modules/" + module + "/" + id, toJson(fields));
        List<ModuleRow> rows = rows("{\"records\":[" + json + "]}");
        return rows.isEmpty() ? new ModuleRow(id, Map.of("estado", "Actualizado")) : rows.getFirst();
    }

    public void delete(String module, String id) throws IOException, InterruptedException {
        send("DELETE", "/modules/" + module + "/" + id, null);
    }

    private String send(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBaseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        } else if ("PUT".equals(method)) {
            builder.PUT(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.GET();
        }
        AuthSession.get().authorize(builder);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private String toJson(Map<String, String> fields) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(entry.getKey()).append("\":");
            if (entry.getKey().equals("amount") || entry.getKey().equals("vatPercent") || entry.getKey().equals("minutes")) {
                json.append(entry.getValue().isBlank() ? "null" : entry.getValue());
            } else if (entry.getKey().equals("date") && entry.getValue().isBlank()) {
                json.append("null");
            } else {
                json.append('"').append(escape(entry.getValue())).append('"');
            }
        }
        return json.append('}').toString();
    }

    private List<ModuleRow> rows(String json) {
        List<ModuleRow> result = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\{\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"fields\"\\s*:\\s*\\{([^}]*)}}");
        Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            result.add(new ModuleRow(unescape(matcher.group(1)), fields(matcher.group(2))));
        }
        return result;
    }

    private List<DashboardItem> dashboardItems(String json, String field) {
        List<DashboardItem> result = new ArrayList<>();
        Pattern arrayPattern = Pattern.compile("\"" + field + "\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
        Matcher arrayMatcher = arrayPattern.matcher(json);
        if (!arrayMatcher.find()) {
            return result;
        }
        Matcher objectMatcher = Pattern.compile("\\{([^{}]*)}").matcher(arrayMatcher.group(1));
        while (objectMatcher.find()) {
            String object = objectMatcher.group(0);
            result.add(new DashboardItem(
                    textField(object, "title"),
                    textField(object, "subtitle"),
                    textField(object, "value")
            ));
        }
        return result;
    }

    private Map<String, String> fields(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        Pattern pattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(null|\"((?:\\\\.|[^\"])*)\"|-?\\d+(?:\\.\\d+)?)");
        Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            String rawValue = matcher.group(2);
            String value = "null".equals(rawValue) ? "" : matcher.group(3) == null ? rawValue : unescape(matcher.group(3));
            result.put(unescape(matcher.group(1)), value);
        }
        return result;
    }

    private String textField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|\"((?:\\\\.|[^\"])*)\")");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || "null".equals(matcher.group(1))) {
            return "";
        }
        return unescape(matcher.group(2));
    }

    private String valueField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|\"((?:\\\\.|[^\"])*)\"|-?\\d+(?:\\.\\d+)?)");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || "null".equals(matcher.group(1))) {
            return "0";
        }
        String value = matcher.group(1);
        return value.startsWith("\"") ? unescape(matcher.group(2)) : value;
    }

    private String escape(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private String unescape(String value) {
        return value
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
