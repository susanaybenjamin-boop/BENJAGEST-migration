package com.benjagest.ui.service;

import com.benjagest.ui.model.CertificateOption;
import com.benjagest.ui.model.SalesInvoiceSummary;
import com.benjagest.ui.model.SeriesEntry;
import com.benjagest.ui.model.VerifactuConfig;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente HTTP de las pantallas de Facturacion:
 *   - /api/billing/invoices         (GET listado con filtros)
 *   - /api/billing/series           (GET)
 *   - /api/billing/verifactu-config (GET/PUT)
 *   - /api/certificates             (GET listado para selector)
 *
 * Mismo estilo que el resto: HTTP nativo de Java, parsing manual con
 * regex. Suficiente para los listados de hoy; si en el futuro hace
 * falta serializacion compleja se cambia el plumbing.
 */
public class BillingApiClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";

    private final HttpClient httpClient;
    private final String baseUrl;

    public BillingApiClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), apiBaseUrl());
    }

    BillingApiClient(HttpClient httpClient, String apiBaseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = apiBaseUrl.replaceAll("/+$", "");
    }

    // -------- facturas --------

    public List<SalesInvoiceSummary> listInvoices(String statusFilter,
                                                  String paymentStatusFilter,
                                                  String customerIdFilter,
                                                  int limit) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl + "/billing/invoices?limit=" + limit);
        if (statusFilter != null && !statusFilter.isBlank()) {
            url.append("&status=").append(URLEncoder.encode(statusFilter, StandardCharsets.UTF_8));
        }
        if (paymentStatusFilter != null && !paymentStatusFilter.isBlank()) {
            url.append("&paymentStatus=").append(URLEncoder.encode(paymentStatusFilter, StandardCharsets.UTF_8));
        }
        if (customerIdFilter != null && !customerIdFilter.isBlank()) {
            url.append("&customerId=").append(URLEncoder.encode(customerIdFilter, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(8))
                .GET());
        ensureOk(response);
        return parseInvoices(response.body());
    }

    // -------- series --------

    public List<SeriesEntry> listSeries() throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/billing/series"))
                .timeout(Duration.ofSeconds(8))
                .GET());
        ensureOk(response);
        return parseSeries(response.body());
    }

    // -------- verifactu config --------

    public VerifactuConfig getVerifactuConfig() throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/billing/verifactu-config"))
                .timeout(Duration.ofSeconds(8))
                .GET());
        ensureOk(response);
        return parseVerifactuConfig(response.body());
    }

    public VerifactuConfig updateVerifactuConfig(String mode, String certificateIdOrNull,
                                                 String footerTemplate) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{");
        body.append(field("mode", mode));
        if (certificateIdOrNull != null && !certificateIdOrNull.isBlank()) {
            body.append(",").append(field("certificateId", certificateIdOrNull));
        }
        if (footerTemplate != null) {
            body.append(",").append(field("invoiceFooterTemplate", footerTemplate));
        }
        body.append("}");
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/billing/verifactu-config"))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString())));
        ensureOk(response);
        return parseVerifactuConfig(response.body());
    }

    // -------- certificados (para selector) --------

    public List<CertificateOption> listCertificateOptions() throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/certificates"))
                .timeout(Duration.ofSeconds(8))
                .GET());
        ensureOk(response);
        return parseCertificateOptions(response.body());
    }

    // -------- infra --------

    private HttpResponse<String> sendAuthorized(HttpRequest.Builder builder) throws IOException, InterruptedException {
        AuthSession.get().authorize(builder);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void ensureOk(HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private List<SalesInvoiceSummary> parseInvoices(String json) {
        List<SalesInvoiceSummary> list = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\{[^{}]*\"invoiceType\"[^{}]*\\}").matcher(json);
        while (matcher.find()) {
            String obj = matcher.group();
            list.add(new SalesInvoiceSummary(
                    textField(obj, "id"),
                    textField(obj, "invoiceNumber"),
                    textField(obj, "customerLegalName"),
                    textField(obj, "invoiceDate"),
                    textField(obj, "dueDate"),
                    textField(obj, "status"),
                    textField(obj, "paymentStatus"),
                    decimalField(obj, "total"),
                    decimalField(obj, "paidAmount")
            ));
        }
        return list;
    }

    private List<SeriesEntry> parseSeries(String json) {
        List<SeriesEntry> list = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\{[^{}]*\"invoiceKind\"[^{}]*\\}").matcher(json);
        while (matcher.find()) {
            String obj = matcher.group();
            list.add(new SeriesEntry(
                    textField(obj, "id"),
                    textField(obj, "code"),
                    textField(obj, "invoiceKind"),
                    textField(obj, "numberingType"),
                    textField(obj, "formatTemplate"),
                    intFieldOrZero(obj, "nextNumber"),
                    intField(obj, "currentYear"),
                    boolField(obj, "locked"),
                    boolField(obj, "active")
            ));
        }
        return list;
    }

    private VerifactuConfig parseVerifactuConfig(String json) {
        return new VerifactuConfig(
                textField(json, "mode"),
                textField(json, "certificateId"),
                textField(json, "certificateAlias"),
                textField(json, "invoiceFooterTemplate")
        );
    }

    private List<CertificateOption> parseCertificateOptions(String json) {
        List<CertificateOption> list = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\{[^{}]*\"alias\"[^{}]*\\}").matcher(json);
        while (matcher.find()) {
            String obj = matcher.group();
            list.add(new CertificateOption(
                    textField(obj, "id"),
                    textField(obj, "alias"),
                    textField(obj, "certificateType")
            ));
        }
        return list;
    }

    private String field(String name, String value) {
        return "\"" + name + "\":" + (value == null ? "null" : "\"" + escape(value) + "\"");
    }

    private String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private String textField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|\"((?:\\\\.|[^\"])*)\")");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || "null".equals(matcher.group(1))) {
            return "";
        }
        return unescape(matcher.group(2));
    }

    private Integer intField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|(-?\\d+))");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || "null".equals(matcher.group(1))) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int intFieldOrZero(String json, String field) {
        Integer value = intField(json, field);
        return value == null ? 0 : value;
    }

    private BigDecimal decimalField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|(-?\\d+(?:\\.\\d+)?))");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || "null".equals(matcher.group(1))) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(matcher.group(2));
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
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
