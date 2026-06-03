package com.benjagest.ui.service;

import com.benjagest.ui.model.CertificateOption;
import com.benjagest.ui.model.InvoiceLineDraft;
import com.benjagest.ui.model.InvoiceTexts;
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

    public SalesInvoiceSummary getInvoiceById(String id) throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/billing/invoices/" + id))
                .timeout(Duration.ofSeconds(8))
                .GET());
        ensureOk(response);
        // Para la pantalla de edicion devolvemos la cabecera; las lineas
        // se piden y parsean aparte en getInvoiceLines() para no
        // contaminar SalesInvoiceSummary.
        return parseInvoiceHeader(response.body());
    }

    public List<InvoiceLineDraft> getInvoiceLines(String id) throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/billing/invoices/" + id))
                .timeout(Duration.ofSeconds(8))
                .GET());
        ensureOk(response);
        return parseInvoiceLines(response.body());
    }

    public SalesInvoiceSummary createInvoice(String customerId, String seriesId, String invoiceType,
                                             String invoiceDateIso, String dueDateIso, String notes,
                                             List<InvoiceLineDraft> lines) throws IOException, InterruptedException {
        return upsertInvoice(null, customerId, seriesId, invoiceType, invoiceDateIso, dueDateIso, notes, lines);
    }

    public SalesInvoiceSummary updateInvoice(String id, String customerId, String seriesId, String invoiceType,
                                             String invoiceDateIso, String dueDateIso, String notes,
                                             List<InvoiceLineDraft> lines) throws IOException, InterruptedException {
        return upsertInvoice(id, customerId, seriesId, invoiceType, invoiceDateIso, dueDateIso, notes, lines);
    }

    private SalesInvoiceSummary upsertInvoice(String idOrNull, String customerId, String seriesId, String invoiceType,
                                              String invoiceDateIso, String dueDateIso, String notes,
                                              List<InvoiceLineDraft> lines) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{");
        body.append(field("customerId", customerId)).append(",");
        body.append(field("seriesId", seriesId)).append(",");
        body.append(field("invoiceType", invoiceType == null ? "NORMAL" : invoiceType)).append(",");
        if (invoiceDateIso != null && !invoiceDateIso.isBlank()) {
            body.append(field("invoiceDate", invoiceDateIso)).append(",");
        }
        if (dueDateIso != null && !dueDateIso.isBlank()) {
            body.append(field("dueDate", dueDateIso)).append(",");
        }
        if (notes != null && !notes.isBlank()) {
            body.append(field("notes", notes)).append(",");
        }
        body.append("\"lines\":[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) body.append(",");
            InvoiceLineDraft line = lines.get(i);
            body.append("{")
                    .append(field("description", line.getDescription())).append(",")
                    .append("\"quantity\":").append(line.getQuantity().toPlainString()).append(",")
                    .append("\"unitPrice\":").append(line.getUnitPrice().toPlainString()).append(",")
                    .append("\"vatPercent\":").append(line.getVatPercent().toPlainString()).append(",")
                    .append("\"retentionPercent\":").append(line.getRetentionPercent().toPlainString())
                    .append("}");
        }
        body.append("]}");

        URI uri = idOrNull == null
                ? URI.create(baseUrl + "/billing/invoices")
                : URI.create(baseUrl + "/billing/invoices/" + idOrNull);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (idOrNull == null) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            builder.PUT(HttpRequest.BodyPublishers.ofString(body.toString()));
        }
        HttpResponse<String> response = sendAuthorized(builder);
        ensureOk(response);
        return parseInvoiceHeader(response.body());
    }

    public SalesInvoiceSummary validateInvoice(String id) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/billing/invoices/" + id + "/validate"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> response = sendAuthorized(builder);
        ensureOk(response);
        return parseInvoiceHeader(response.body());
    }

    /**
     * Anulacion con vinculo: el server crea un borrador RECTIFYING enlazado
     * a la factura original (que sigue VALIDATED). Devolvemos el header del
     * borrador para que la UI lo pueda abrir o mostrar su id.
     */
    public SalesInvoiceSummary voidInvoice(String id) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/billing/invoices/" + id + "/void"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> response = sendAuthorized(builder);
        ensureOk(response);
        return parseInvoiceHeader(response.body());
    }

    public void deleteInvoice(String id) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/billing/invoices/" + id))
                .timeout(Duration.ofSeconds(8))
                .DELETE();
        HttpResponse<String> response = sendAuthorized(builder);
        ensureOk(response);
    }

    private SalesInvoiceSummary parseInvoiceHeader(String json) {
        // Toma SOLO los campos del header (los de antes de "lines"), por
        // si vienen lineas embebidas con campos que colisionan en regex.
        int linesIdx = json.indexOf("\"lines\"");
        String header = linesIdx > 0 ? json.substring(0, linesIdx) : json;
        return new SalesInvoiceSummary(
                textField(header, "id"),
                textField(header, "invoiceNumber"),
                textField(header, "customerLegalName"),
                textField(header, "invoiceDate"),
                textField(header, "dueDate"),
                textField(header, "status"),
                textField(header, "paymentStatus"),
                decimalField(header, "total"),
                decimalField(header, "paidAmount"),
                textField(header, "invoiceType"),
                textField(header, "originalInvoiceId")
        );
    }

    private List<InvoiceLineDraft> parseInvoiceLines(String json) {
        List<InvoiceLineDraft> result = new java.util.ArrayList<>();
        int linesIdx = json.indexOf("\"lines\"");
        if (linesIdx < 0) {
            return result;
        }
        int arrStart = json.indexOf('[', linesIdx);
        if (arrStart < 0) {
            return result;
        }
        // Tomamos el slice de '[' hasta el ']' que cierra el array,
        // respetando llaves y comillas (no usamos indexOf(']') porque
        // podria caer dentro de un string como "talla 4 x 4]").
        String slice = extractJsonArraySlice(json, arrStart);
        for (String obj : splitJsonObjects(slice)) {
            if (!obj.contains("\"description\"")) continue;
            result.add(new InvoiceLineDraft(
                    textField(obj, "description"),
                    decimalField(obj, "quantity"),
                    decimalField(obj, "unitPrice"),
                    decimalField(obj, "vatPercent"),
                    decimalField(obj, "retentionPercent")
            ));
        }
        return result;
    }

    /**
     * Devuelve el substring de un array JSON desde su '[' hasta el ']' que
     * lo cierra, contando corchetes y respetando los strings. Sirve para
     * aislar el contenido de "lines":[...] aunque el resto del JSON tenga
     * mas arrays/strings con corchetes despues.
     */
    private String extractJsonArraySlice(String json, int arrayStart) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = arrayStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return json.substring(arrayStart, i + 1);
                }
            }
        }
        return json.substring(arrayStart);
    }

    // -------- series --------

    public List<SeriesEntry> listSeries() throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/billing/series"))
                .timeout(Duration.ofSeconds(8))
                .GET());
        ensureOk(response);
        return parseSeries(response.body());
    }

    public SeriesEntry createSeries(String code, String invoiceKind, String numberingType,
                                    String formatTemplate, Integer initialNextNumber,
                                    boolean locked) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{");
        body.append(field("code", code)).append(",");
        body.append(field("invoiceKind", invoiceKind)).append(",");
        body.append(field("numberingType", numberingType));
        if (formatTemplate != null && !formatTemplate.isBlank()) {
            body.append(",").append(field("formatTemplate", formatTemplate));
        }
        if (initialNextNumber != null) {
            body.append(",\"initialNextNumber\":").append(initialNextNumber);
        }
        body.append(",\"locked\":").append(locked);
        body.append("}");
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/billing/series"))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
        ensureOk(response);
        return parseOneSeries(response.body());
    }

    public SeriesEntry updateSeries(String id, String code, String invoiceKind, String numberingType,
                                    String formatTemplate, boolean locked) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{");
        body.append(field("code", code)).append(",");
        body.append(field("invoiceKind", invoiceKind)).append(",");
        body.append(field("numberingType", numberingType));
        if (formatTemplate != null && !formatTemplate.isBlank()) {
            body.append(",").append(field("formatTemplate", formatTemplate));
        }
        body.append(",\"locked\":").append(locked);
        body.append("}");
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/billing/series/" + id))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString())));
        ensureOk(response);
        return parseOneSeries(response.body());
    }

    public void deleteSeries(String id) throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/billing/series/" + id))
                .timeout(Duration.ofSeconds(8))
                .DELETE());
        ensureOk(response);
    }

    private SeriesEntry parseOneSeries(String body) {
        return new SeriesEntry(
                textField(body, "id"),
                textField(body, "code"),
                textField(body, "invoiceKind"),
                textField(body, "numberingType"),
                textField(body, "formatTemplate"),
                intFieldOrZero(body, "nextNumber"),
                intField(body, "currentYear"),
                boolField(body, "locked"),
                boolField(body, "active")
        );
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

    // -------- invoice texts --------

    public InvoiceTexts getInvoiceTexts() throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/billing/invoice-texts"))
                .timeout(Duration.ofSeconds(8))
                .GET());
        ensureOk(response);
        return parseInvoiceTexts(response.body());
    }

    public InvoiceTexts updateInvoiceTexts(InvoiceTexts texts) throws IOException, InterruptedException {
        String body = "{"
                + field("pie", texts.pie()) + ","
                + field("exempt", texts.exempt()) + ","
                + field("reverseCharge", texts.reverseCharge()) + ","
                + field("reducedVat", texts.reducedVat()) + ","
                + field("rectifying", texts.rectifying()) + ","
                + field("legalTerms", texts.legalTerms()) + ","
                + "\"showIban\":" + texts.showIban()
                + "}";
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/billing/invoice-texts"))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
        ensureOk(response);
        return parseInvoiceTexts(response.body());
    }

    private InvoiceTexts parseInvoiceTexts(String json) {
        return new InvoiceTexts(
                textField(json, "pie"),
                textField(json, "exempt"),
                textField(json, "reverseCharge"),
                textField(json, "reducedVat"),
                textField(json, "rectifying"),
                textField(json, "legalTerms"),
                boolField(json, "showIban")
        );
    }

    // -------- series migrate --------

    public SeriesEntry migrateSeries(String seriesId, int nextNumber, boolean acknowledged) throws IOException, InterruptedException {
        String body = "{\"nextNumber\":" + nextNumber + ",\"acknowledged\":" + acknowledged + "}";
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(baseUrl + "/billing/series/" + seriesId + "/migrate"))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        ensureOk(response);
        String r = response.body();
        return new SeriesEntry(
                textField(r, "id"),
                textField(r, "code"),
                textField(r, "invoiceKind"),
                textField(r, "numberingType"),
                textField(r, "formatTemplate"),
                intFieldOrZero(r, "nextNumber"),
                intField(r, "currentYear"),
                boolField(r, "locked"),
                boolField(r, "active")
        );
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
        for (String obj : splitJsonObjects(json)) {
            if (!obj.contains("\"invoiceType\"")) continue;
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
        for (String obj : splitJsonObjects(json)) {
            if (!obj.contains("\"invoiceKind\"")) continue;
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

    /**
     * Trocea un JSON (array u objeto) en sus objetos top-level,
     * contando llaves pero respetando lo que hay dentro de los strings.
     * Imprescindible cuando un campo string contiene '{' o '}' (caso
     * tipico: formatTemplate de una serie con placeholders {CODE},
     * {YYYY}, {0000} rompia el regex previo [^{}] porque la clase de
     * caracteres no distingue entre llaves dentro y fuera de strings).
     *
     * No es un parser JSON completo; basta para los endpoints actuales
     * que devuelven array de objetos planos sin objetos anidados.
     */
    private List<String> splitJsonObjects(String json) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
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
        for (String obj : splitJsonObjects(json)) {
            if (!obj.contains("\"alias\"")) continue;
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
