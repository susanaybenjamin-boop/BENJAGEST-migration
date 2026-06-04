package com.benjagest.ui.service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;

/**
 * Cliente HTTP para el endpoint de extraccion de PDF de compras (C3).
 *
 * El endpoint backend es multipart/form-data — usamos un envio
 * artesanal porque JDK HttpClient no incluye builder multipart, y
 * meter una dependencia (Apache HttpClient) para una sola llamada no
 * compensa.
 *
 * Envia el PDF entero por la red. Si el archivo es muy grande se
 * podria streamear, pero hoy las facturas tipicas son &lt;500 KB.
 */
public class PdfImportApiClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String baseUrl;

    public PdfImportApiClient() {
        this(System.getenv().getOrDefault("BENJAGEST_API_BASE_URL", DEFAULT_API_BASE_URL));
    }

    public PdfImportApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Sube el PDF al backend y devuelve el JSON con los campos
     * extraidos. La UI parsea el JSON con regex.
     */
    public String uploadAndExtract(File pdfFile) throws IOException, InterruptedException {
        String boundary = "----benjagest-" + UUID.randomUUID();
        byte[] body = buildMultipartBody(pdfFile, boundary);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/purchases/pdf-import"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        AuthSession.get().authorize(builder);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    /**
     * Guarda/actualiza la plantilla por NIF de proveedor con las
     * correcciones del usuario. El JSON se compone en la UI con los
     * valores corregidos (supplierName y/o vatPercent fijos).
     */
    public void saveTemplate(String supplierNif, String supplierName, String rulesJson)
            throws IOException, InterruptedException {
        String body = "{"
                + "\"supplierNif\":\"" + escape(supplierNif) + "\","
                + "\"supplierName\":" + (supplierName == null ? "null" : "\"" + escape(supplierName) + "\"") + ","
                + "\"rulesJson\":" + (rulesJson == null ? "null" : "\"" + escape(rulesJson) + "\"")
                + "}";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/purchases/extraction-templates"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        AuthSession.get().authorize(builder);
        HttpResponse<String> r = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    private static String escape(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private byte[] buildMultipartBody(File file, String boundary) throws IOException {
        String prefix = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n";
        String suffix = "\r\n--" + boundary + "--\r\n";

        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);

        byte[] out = new byte[prefixBytes.length + fileBytes.length + suffixBytes.length];
        System.arraycopy(prefixBytes, 0, out, 0, prefixBytes.length);
        System.arraycopy(fileBytes, 0, out, prefixBytes.length, fileBytes.length);
        System.arraycopy(suffixBytes, 0, out, prefixBytes.length + fileBytes.length, suffixBytes.length);
        return out;
    }
}
