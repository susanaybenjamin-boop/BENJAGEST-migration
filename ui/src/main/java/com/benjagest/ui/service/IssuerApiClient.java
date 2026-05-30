package com.benjagest.ui.service;

import com.benjagest.ui.model.IssuerCreateRequest;
import com.benjagest.ui.model.IssuerSummary;
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
 * Cliente HTTP que la UI usa para hablar con /api/issuers del backend.
 * Misma filosofia que CustomerApiClient: HTTP nativo de Java (no
 * dependencias extra) y JSON manual con regex (suficiente para los
 * campos que devolvemos hoy).
 *
 * Soporta CRUD completo: list, create, update, delete (a diferencia
 * del cliente de clientes, que hoy solo tiene list y create).
 */
public class IssuerApiClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";

    private final HttpClient httpClient;
    private final URI issuersUri;

    public IssuerApiClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), apiBaseUrl());
    }

    IssuerApiClient(HttpClient httpClient, String apiBaseUrl) {
        this.httpClient = httpClient;
        this.issuersUri = URI.create(apiBaseUrl.replaceAll("/+$", "") + "/issuers");
    }

    public List<IssuerSummary> list() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(issuersUri)
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureOk(response);
        return parseList(response.body());
    }

    public IssuerSummary create(IssuerCreateRequest request) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(issuersUri)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(request)))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        ensureOk(response);
        return parseIssuer(response.body());
    }

    public IssuerSummary update(String id, IssuerCreateRequest request) throws IOException, InterruptedException {
        URI uri = URI.create(issuersUri.toString() + "/" + id);
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(toJson(request)))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        ensureOk(response);
        return parseIssuer(response.body());
    }

    public void delete(String id) throws IOException, InterruptedException {
        URI uri = URI.create(issuersUri.toString() + "/" + id);
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        ensureOk(response);
    }

    public IssuerSummary markAsDefault(String id) throws IOException, InterruptedException {
        URI uri = URI.create(issuersUri.toString() + "/" + id + "/default");
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        ensureOk(response);
        return parseIssuer(response.body());
    }

    /**
     * Devuelve el emisor activo de la empresa, o null si el backend
     * responde 404 (todavia no hay ninguno marcado como activo).
     * Diferente del resto de metodos: 404 no se traduce a IOException,
     * porque "no hay activo" es un estado normal del sistema.
     */
    public IssuerSummary getDefault() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(issuersUri.toString() + "/default"))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        ensureOk(response);
        return parseIssuer(response.body());
    }

    private void ensureOk(HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("El servicio respondio con HTTP " + response.statusCode());
        }
    }

    private String toJson(IssuerCreateRequest request) {
        return "{"
                + field("legalName", request.legalName()) + ","
                + field("taxIdentifier", request.taxIdentifier()) + ","
                + field("addressLine", request.addressLine()) + ","
                + field("city", request.city()) + ","
                + field("province", request.province()) + ","
                + field("postalCode", request.postalCode()) + ","
                + field("country", request.country()) + ","
                + field("email", request.email()) + ","
                + field("phone", request.phone()) + ","
                + field("iban", request.iban()) + ","
                + field("registryInformation", request.registryInformation()) + ","
                + field("legalTerms", request.legalTerms()) + ","
                + field("invoiceFooter", request.invoiceFooter())
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

    private List<IssuerSummary> parseList(String json) {
        List<IssuerSummary> issuers = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\{[^{}]*\"legalName\"\\s*:\\s*\"").matcher(json);
        int start = 0;
        while (matcher.find(start)) {
            int objectStart = matcher.start();
            int objectEnd = json.indexOf('}', objectStart);
            if (objectEnd < 0) {
                break;
            }
            issuers.add(parseIssuer(json.substring(objectStart, objectEnd + 1)));
            start = objectEnd + 1;
        }
        return issuers;
    }

    private IssuerSummary parseIssuer(String json) {
        return new IssuerSummary(
                textField(json, "id"),
                textField(json, "legalName"),
                textField(json, "taxIdentifier"),
                textField(json, "addressLine"),
                textField(json, "city"),
                textField(json, "province"),
                textField(json, "postalCode"),
                textField(json, "country"),
                textField(json, "email"),
                textField(json, "phone"),
                textField(json, "iban"),
                textField(json, "registryInformation"),
                textField(json, "legalTerms"),
                textField(json, "invoiceFooter"),
                boolField(json, "active"),
                boolField(json, "isDefault")
        );
    }

    private String textField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|\"((?:\\\\.|[^\"])*)\")");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || "null".equals(matcher.group(1))) {
            return "";
        }
        return unescape(matcher.group(2));
    }

    private boolean boolField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(true|false)");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() && "true".equals(matcher.group(1));
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
