package com.benjagest.ui.service;

import com.benjagest.ui.model.CustomerCreateRequest;
import com.benjagest.ui.model.CustomerSummary;
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

public class CustomerApiClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";

    private final HttpClient httpClient;
    private final URI customersUri;

    public CustomerApiClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), apiBaseUrl());
    }

    CustomerApiClient(HttpClient httpClient, String apiBaseUrl) {
        this.httpClient = httpClient;
        this.customersUri = URI.create(apiBaseUrl.replaceAll("/+$", "") + "/customers");
    }

    public CustomerSummary create(CustomerCreateRequest request) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(customersUri)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(request)));
        AuthSession.get().authorize(builder);

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("El servicio respondio con HTTP " + response.statusCode());
        }

        return parseCustomer(response.body());
    }

    public List<CustomerSummary> list() throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(customersUri)
                .timeout(Duration.ofSeconds(8))
                .GET();
        AuthSession.get().authorize(builder);

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("El servicio respondio con HTTP " + response.statusCode());
        }

        return parseCustomers(response.body());
    }

    private String toJson(CustomerCreateRequest request) {
        return "{"
                + field("legalName", request.legalName()) + ","
                + field("tradeName", request.tradeName()) + ","
                + field("taxIdentifier", request.taxIdentifier()) + ","
                + field("contactName", request.contactName()) + ","
                + field("email", request.email()) + ","
                + field("phone", request.phone())
                + "}";
    }

    private String field(String name, String value) {
        return "\"" + name + "\":\"" + escape(value == null ? "" : value) + "\"";
    }

    private String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private List<CustomerSummary> parseCustomers(String json) {
        List<CustomerSummary> customers = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\{[^{}]*\"legalName\"\\s*:\\s*\"").matcher(json);
        int start = 0;
        while (matcher.find(start)) {
            int objectStart = matcher.start();
            int objectEnd = json.indexOf('}', objectStart);
            if (objectEnd < 0) {
                break;
            }
            customers.add(parseCustomer(json.substring(objectStart, objectEnd + 1)));
            start = objectEnd + 1;
        }
        return customers;
    }

    private CustomerSummary parseCustomer(String json) {
        return new CustomerSummary(
                textField(json, "id"),
                textField(json, "legalName"),
                textField(json, "taxIdentifier"),
                textField(json, "email"),
                textField(json, "phone"),
                textField(json, "address"),
                textField(json, "city"),
                textField(json, "province"),
                textField(json, "postalCode"),
                textField(json, "country"),
                decimalField(json, "defaultVatPercent"),
                decimalField(json, "defaultRetentionPercent")
        );
    }

    /** Lee un campo numérico (no entrecomillado). Devuelve null si es null/ausente. */
    private java.math.BigDecimal decimalField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|-?[0-9]+(?:\\.[0-9]+)?)");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || "null".equals(matcher.group(1))) {
            return null;
        }
        try {
            return new java.math.BigDecimal(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String textField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|\"((?:\\\\.|[^\"])*)\")");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || "null".equals(matcher.group(1))) {
            return "";
        }
        return unescape(matcher.group(2));
    }

    private String unescape(String value) {
        return value
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static String apiBaseUrl() {
        return System.getenv().getOrDefault("BENJAGEST_API_BASE_URL", DEFAULT_API_BASE_URL);
    }
}
