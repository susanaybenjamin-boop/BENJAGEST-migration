package com.benjagest.ui.service;

import com.benjagest.ui.model.CertificateOption;
import com.benjagest.ui.model.InvoiceLineDraft;
import com.benjagest.ui.model.InvoiceTexts;
import com.benjagest.ui.model.SalesInvoiceSummary;
import com.benjagest.ui.model.SeriesEntry;
import com.benjagest.ui.model.SifEventEntry;
import com.benjagest.ui.model.SifEventIntegrityResult;
import com.benjagest.ui.model.VerifactuConfig;
import com.benjagest.ui.model.VerifactuIntegrityResult;
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
                                                  String invoiceTypeFilter,
                                                  int limit) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl + "/billing/invoices?limit=" + limit);
        if (statusFilter != null && !statusFilter.isBlank()) {
            url.append("&status=").append(URLEncoder.encode(statusFilter, StandardCharsets.UTF_8));
        }
        if (paymentStatusFilter != null && !paymentStatusFilter.isBlank()) {
            url.append("&paymentStatus=").append(URLEncoder.encode(paymentStatusFilter, StandardCharsets.UTF_8));
        }
        if (invoiceTypeFilter != null && !invoiceTypeFilter.isBlank()) {
            url.append("&invoiceType=").append(URLEncoder.encode(invoiceTypeFilter, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(8))
                .GET());
        ensureOk(response);
        return parseInvoices(response.body());
    }

    /** REFLEJO — facturas emitidas reflejadas como gasto: [{salesInvoiceId, companyName}]. */
    public List<java.util.Map<String, String>> listInvoiceReflections()
            throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(
                HttpRequest.newBuilder(URI.create(baseUrl + "/billing/invoices/reflections"))
                        .timeout(Duration.ofSeconds(8)).GET());
        ensureOk(response);
        List<java.util.Map<String, String>> out = new java.util.ArrayList<>();
        // Objetos planos (sin anidar) → regex simple es suficiente.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{[^{}]*\\}")
                .matcher(response.body() == null ? "" : response.body());
        while (m.find()) {
            String obj = m.group();
            out.add(java.util.Map.of(
                    "salesInvoiceId", textField(obj, "salesInvoiceId"),
                    "companyName", textField(obj, "companyName")));
        }
        return out;
    }

    // MIG-3 — baselines de migración guardadas + PDF de prueba.
    public record BaselineEntry(String id, String seriesCode, String fullNumber, String number,
                                String date, String customerName, String total, boolean hasEvidence,
                                String createdAt) {}

    public List<BaselineEntry> listMigrationBaselines() throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(
                URI.create(baseUrl + "/billing/migration-baseline"))
                .timeout(Duration.ofSeconds(10)).GET());
        ensureOk(response);
        List<BaselineEntry> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{[^{}]*\\}")
                .matcher(response.body() == null ? "" : response.body());
        while (m.find()) {
            String o = m.group();
            out.add(new BaselineEntry(
                    textField(o, "id"), textField(o, "declaredSeriesCode"),
                    textField(o, "declaredFullNumber"), numField(o, "declaredNumber"),
                    textField(o, "declaredDate"), textField(o, "customerName"),
                    numField(o, "totalAmount"),
                    o.contains("\"hasEvidence\":true"), textField(o, "createdAt")));
        }
        return out;
    }

    public byte[] getMigrationEvidence(String id) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(
                baseUrl + "/billing/migration-baseline/" + id + "/evidence"))
                .timeout(Duration.ofSeconds(20)).GET();
        AuthSession.get().authorize(b);
        HttpResponse<byte[]> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + " al descargar el PDF de prueba");
        }
        return r.body();
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

    // ============================================================
    //  MULTI-ALLOCATION — un pago salda varias facturas (V102)
    // ============================================================

    public record AllocationDraft(String invoiceId, java.math.BigDecimal amount) {}

    /**
     * Registra un pago real que se reparte entre varias facturas
     * VALIDATED con pago pendiente. Devuelve el paymentId comun.
     */
    public String registerMultiAllocation(
            String paymentDateIso, java.math.BigDecimal totalAmount,
            String paymentMethod, String reference, String notes,
            java.util.List<AllocationDraft> allocations)
            throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{");
        body.append("\"paymentDate\":\"").append(paymentDateIso).append("\",");
        body.append("\"totalAmount\":").append(totalAmount.toPlainString()).append(',');
        if (paymentMethod != null && !paymentMethod.isBlank()) {
            body.append(field("paymentMethod", paymentMethod)).append(",");
        }
        if (reference != null && !reference.isBlank()) {
            body.append(field("reference", reference)).append(",");
        }
        if (notes != null && !notes.isBlank()) {
            body.append(field("notes", notes)).append(",");
        }
        body.append("\"allocations\":[");
        for (int i = 0; i < allocations.size(); i++) {
            if (i > 0) body.append(',');
            AllocationDraft a = allocations.get(i);
            body.append("{\"invoiceId\":\"").append(a.invoiceId()).append("\",")
                    .append("\"amount\":").append(a.amount().toPlainString()).append('}');
        }
        body.append("]}");

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(
                baseUrl + "/billing/payments/multi-allocation"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        HttpResponse<String> r = sendAuthorized(b);
        ensureOk(r);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"paymentId\"\\s*:\\s*\"([^\"]+)\"").matcher(r.body());
        return m.find() ? m.group(1) : null;
    }

    // ============================================================
    //  TPB-3 — Aceptación factura-a-factura por el cliente
    // ============================================================

    public List<SalesInvoiceSummary> listPendingClientApproval()
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create(baseUrl + "/billing/invoices/pending-client-approval"))
                .timeout(Duration.ofSeconds(15))
                .GET();
        HttpResponse<String> r = sendAuthorized(b);
        ensureOk(r);
        return parseInvoices(r.body());
    }

    public SalesInvoiceSummary clientApprove(String id)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create(baseUrl + "/billing/invoices/" + id + "/client-approve"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> r = sendAuthorized(b);
        ensureOk(r);
        return parseInvoiceHeader(r.body());
    }

    public SalesInvoiceSummary clientReject(String id, String reason)
            throws IOException, InterruptedException {
        String body = "{\"reason\":\""
                + (reason == null ? ""
                    : reason.replace("\\", "\\\\").replace("\"", "\\\""))
                + "\"}";
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create(baseUrl + "/billing/invoices/" + id + "/client-reject"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = sendAuthorized(b);
        ensureOk(r);
        return parseInvoiceHeader(r.body());
    }

    /**
     * Valida un lote de facturas DRAFT (multiselección). Devuelve el JSON
     * crudo del resumen — la UI lo parsea para mostrar cuántas pasaron.
     */
    public String validateBatch(java.util.List<String> ids) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{\"ids\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) body.append(',');
            body.append('"').append(ids.get(i)).append('"');
        }
        body.append("]}");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/billing/invoices/validate-batch"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        HttpResponse<String> response = sendAuthorized(builder);
        ensureOk(response);
        return response.body();
    }

    /**
     * Descarga el PDF de una factura (F4b). Devuelve los bytes para que
     * la UI los guarde donde quiera (FileChooser) o los abra en visor
     * del sistema.
     */
    public byte[] downloadInvoicePdf(String id) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/billing/invoices/" + id + "/pdf"))
                .timeout(Duration.ofSeconds(20))
                .GET();
        AuthSession.get().authorize(builder);
        HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " al descargar el PDF");
        }
        return response.body();
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

    /**
     * Convierte una proforma DRAFT en factura NORMAL DRAFT (validate=false)
     * o en NORMAL VALIDATED (validate=true). El backend cambia el
     * invoice_type, asigna la serie STANDARD y, si validate=true, emite
     * el número correspondiente.
     */
    public SalesInvoiceSummary convertProformaToStandard(String id, boolean validate) throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(
                baseUrl + "/billing/invoices/" + id + "/convert-to-standard?validate=" + validate))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody()));
        ensureOk(response);
        return parseInvoiceHeader(response.body());
    }

    /**
     * Fuerza la generación y guardado del PDF en la ruta configurada
     * por la empresa ({@code {root}/{companyId}/{YYYY}/T{q}/{nº}.pdf}).
     * El backend crea las subcarpetas de año y trimestre si no existen.
     * Devuelve la ruta absoluta para que la UI la muestre en el mensaje
     * de éxito.
     */
    /**
     * Catálogo de tipos impositivos (IVA + IRPF) para la empresa
     * activa. Si {@code all=true} incluye los inactivos (la pantalla
     * de configuración los necesita); si {@code all=false} solo los
     * activos (el editor de facturas solo quiere los vigentes).
     */
    public List<com.benjagest.ui.model.VatRateEntry> listVatRates(boolean all) throws IOException, InterruptedException {
        String url = baseUrl + "/billing/vat-rates" + (all ? "?all=true" : "");
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET());
        ensureOk(response);
        java.util.List<com.benjagest.ui.model.VatRateEntry> list = new ArrayList<>();
        for (String obj : splitJsonObjects(response.body())) {
            if (!obj.contains("\"kind\"")) continue;
            list.add(new com.benjagest.ui.model.VatRateEntry(
                    textField(obj, "id"),
                    textField(obj, "kind"),
                    textField(obj, "code"),
                    textField(obj, "label"),
                    decimalField(obj, "percent"),
                    boolField(obj, "isDefault"),
                    boolField(obj, "active"),
                    textField(obj, "notes")
            ));
        }
        return list;
    }

    public com.benjagest.ui.model.VatRateEntry createVatRate(String kind, String code, String label,
                                                              java.math.BigDecimal percent,
                                                              boolean isDefault) throws IOException, InterruptedException {
        String body = "{"
                + field("kind", kind) + ","
                + field("code", code) + ","
                + field("label", label) + ","
                + "\"percent\":" + percent.toPlainString() + ","
                + "\"isDefault\":" + isDefault + ","
                + "\"active\":true"
                + "}";
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(
                URI.create(baseUrl + "/billing/vat-rates"))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        ensureOk(response);
        return parseSingleVatRate(response.body());
    }

    public com.benjagest.ui.model.VatRateEntry updateVatRate(String id, String kind, String code,
                                                              String label, java.math.BigDecimal percent,
                                                              boolean isDefault, boolean active) throws IOException, InterruptedException {
        String body = "{"
                + field("kind", kind) + ","
                + field("code", code) + ","
                + field("label", label) + ","
                + "\"percent\":" + percent.toPlainString() + ","
                + "\"isDefault\":" + isDefault + ","
                + "\"active\":" + active
                + "}";
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(
                URI.create(baseUrl + "/billing/vat-rates/" + id))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
        ensureOk(response);
        return parseSingleVatRate(response.body());
    }

    public void deleteVatRate(String id) throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(
                URI.create(baseUrl + "/billing/vat-rates/" + id))
                .timeout(Duration.ofSeconds(8))
                .DELETE());
        ensureOk(response);
    }

    private com.benjagest.ui.model.VatRateEntry parseSingleVatRate(String json) {
        return new com.benjagest.ui.model.VatRateEntry(
                textField(json, "id"),
                textField(json, "kind"),
                textField(json, "code"),
                textField(json, "label"),
                decimalField(json, "percent"),
                boolField(json, "isDefault"),
                boolField(json, "active"),
                textField(json, "notes")
        );
    }

    /**
     * Declaración responsable del fabricante del SIF (RD 1007/2023).
     * Endpoint informativo — devuelve el JSON con los datos del
     * productor + producto + fecha + compromiso.
     */
    public String fetchManufacturerDeclaration() throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(
                URI.create(baseUrl + "/billing/manufacturer-declaration"))
                .timeout(Duration.ofSeconds(10))
                .GET());
        ensureOk(response);
        return response.body();
    }

    public String storeInvoicePdf(String id) throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(
                URI.create(baseUrl + "/billing/invoices/" + id + "/store-pdf"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody()));
        ensureOk(response);
        String path = textField(response.body(), "pdfPath");
        return path.isEmpty() ? "" : path;
    }

    public void deleteInvoice(String id) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/billing/invoices/" + id))
                .timeout(Duration.ofSeconds(8))
                .DELETE();
        HttpResponse<String> response = sendAuthorized(builder);
        ensureOk(response);
    }

    /**
     * Envia la factura al email del cliente (o al override que se
     * pase). Devuelve "recipient" leido del JSON de respuesta para
     * que la UI pueda mostrar "Enviada a foo@bar".
     */
    public String sendInvoiceByEmail(String id, String recipientOverrideOrNull) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{");
        if (recipientOverrideOrNull != null && !recipientOverrideOrNull.isBlank()) {
            body.append(field("recipient", recipientOverrideOrNull.trim()));
        }
        body.append("}");
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(
                URI.create(baseUrl + "/billing/invoices/" + id + "/email"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
        ensureOk(response);
        String recipient = textField(response.body(), "recipient");
        return recipient.isEmpty() ? null : recipient;
    }

    private SalesInvoiceSummary parseInvoiceHeader(String json) {
        // Toma SOLO los campos del header (los de antes de "lines"), por
        // si vienen lineas embebidas con campos que colisionan en regex.
        int linesIdx = json.indexOf("\"lines\"");
        String header = linesIdx > 0 ? json.substring(0, linesIdx) : json;
        // pdfPath del header (no es directamente pdfStored — el backend
        // expone pdf_path como string en el JSON; lo traducimos a un
        // boolean "tiene PDF guardado" para que la UI no tenga que
        // hilar fino entre rutas).
        String pdfPath = textField(header, "pdfPath");
        boolean pdfStored = pdfPath != null && !pdfPath.isBlank();
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
                textField(header, "originalInvoiceId"),
                pdfStored
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

    /**
     * Pide al backend la serie que usaria al validar (incluye logica
     * TPB-2 sin que la UI tenga que adivinar). Devuelve la serie como
     * SeriesEntry para reusar el formateador {@code previewNextNumber}.
     */
    public SeriesEntry previewNextSeries(String invoiceKind)
            throws IOException, InterruptedException {
        HttpResponse<String> r = sendAuthorized(HttpRequest.newBuilder(URI.create(
                baseUrl + "/billing/series/preview-next?invoiceKind="
                        + URLEncoder.encode(invoiceKind, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(8)).GET());
        ensureOk(r);
        String body = r.body();
        return new SeriesEntry(
                textField(body, "id"),
                textField(body, "code"),
                "STANDARD",
                null,
                textField(body, "formatTemplate"),
                intFieldOrZero(body, "nextNumber"),
                intField(body, "currentYear"),
                false,
                true,
                textField(body, "expeditedByCompanyId"));
    }

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

    /**
     * Lanza la verificación de integridad de la cadena hash VeriFactu para
     * la empresa actual en el modo indicado. El backend recorre todas las
     * facturas validadas en orden cronológico y recalcula su huella; si
     * algo no cuadra devuelve la primera factura sospechosa.
     *
     * Respuesta JSON minimal — la parseamos a mano para no traer Jackson
     * en este punto: {"ok":bool,"totalChecked":N,"brokenInvoiceId":"…",
     * "brokenInvoiceNumber":"…","reason":"…"}.
     */
    public VerifactuIntegrityResult verifyVerifactuChain(String mode) throws IOException, InterruptedException {
        String url = baseUrl + "/billing/verifactu-registry/verify?mode=" + (mode == null ? "TEST" : mode);
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET());
        ensureOk(response);
        String body = response.body();
        boolean ok = boolField(body, "ok");
        int total = intFieldOrZero(body, "totalChecked");
        String brokenId = textField(body, "brokenInvoiceId");
        String brokenNumber = textField(body, "brokenInvoiceNumber");
        String reason = textField(body, "reason");
        return new VerifactuIntegrityResult(
                ok, total,
                brokenId.isEmpty() ? null : brokenId,
                brokenNumber.isEmpty() ? null : brokenNumber,
                reason.isEmpty() ? null : reason);
    }

    // -------- SIF event registry (VF-EVENTS) --------

    /**
     * Lista de eventos del Registro de Eventos del SIF (cadena de
     * trazabilidad obligatoria en NO VeriFactu). Filtros opcionales:
     * eventType, limit (1..500). Solo devuelve los de la empresa actual.
     */
    /**
     * Descarga el PDF/CSV verificable del Registro de Eventos del SIF.
     * RD 1007/2023 + Orden HAC/1177/2024 (evento 9: exportación).
     */
    public byte[] exportSifEvents(String format, String fromIso, String toIso,
                                    String eventTypeFilter) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/billing/sif-events/export.").append(format)
                .append("?from=").append(fromIso)
                .append("&to=").append(toIso);
        if (eventTypeFilter != null && !eventTypeFilter.isBlank()) {
            url.append("&eventType=").append(java.net.URLEncoder.encode(
                    eventTypeFilter, java.nio.charset.StandardCharsets.UTF_8));
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(60)).GET();
        AuthSession.get().authorize(builder);
        HttpResponse<byte[]> r = HttpClient.newHttpClient().send(builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
        return r.body();
    }

    public List<SifEventEntry> listSifEvents(String eventTypeFilter) throws IOException, InterruptedException {
        String url = baseUrl + "/billing/sif-events?limit=200"
                + (eventTypeFilter == null || eventTypeFilter.isBlank() ? ""
                        : "&eventType=" + eventTypeFilter.trim());
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET());
        ensureOk(response);
        List<SifEventEntry> list = new ArrayList<>();
        for (String obj : splitJsonObjects(response.body())) {
            if (!obj.contains("\"eventType\"")) continue;
            list.add(new SifEventEntry(
                    textField(obj, "id"),
                    textField(obj, "eventType"),
                    textField(obj, "payload"),
                    textField(obj, "generatedAt"),
                    textField(obj, "hashCurrent"),
                    textField(obj, "hashPrevious"),
                    textField(obj, "status")
            ));
        }
        return list;
    }

    /**
     * Verifica la integridad de la cadena hash de eventos del SIF.
     * Mismo patron que verifyVerifactuChain pero sobre la otra cadena.
     */
    public SifEventIntegrityResult verifySifEventChain() throws IOException, InterruptedException {
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(
                URI.create(baseUrl + "/billing/sif-events/verify"))
                .timeout(Duration.ofSeconds(20))
                .GET());
        ensureOk(response);
        String body = response.body();
        boolean ok = boolField(body, "ok");
        int total = intFieldOrZero(body, "totalChecked");
        String brokenId = textField(body, "brokenEventId");
        String brokenType = textField(body, "brokenEventType");
        String reason = textField(body, "reason");
        return new SifEventIntegrityResult(
                ok, total,
                brokenId.isEmpty() ? null : brokenId,
                brokenType.isEmpty() ? null : brokenType,
                reason.isEmpty() ? null : reason);
    }

    public VerifactuConfig updateVerifactuConfig(String modality, String mode,
                                                 String certificateIdOrNull,
                                                 String footerTemplate,
                                                 String invoiceStorageRoot) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{");
        body.append(field("modality", modality));
        body.append(",").append(field("mode", mode));
        if (certificateIdOrNull != null && !certificateIdOrNull.isBlank()) {
            body.append(",").append(field("certificateId", certificateIdOrNull));
        }
        if (footerTemplate != null) {
            body.append(",").append(field("invoiceFooterTemplate", footerTemplate));
        }
        if (invoiceStorageRoot != null) {
            body.append(",").append(field("invoiceStorageRoot", invoiceStorageRoot));
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

    // -------- MIG — migración: importar última factura emitida --------

    public record MigrationExtracted(String emitterNif, String customerNif, String customerName,
                                     String invoiceNumber, String invoiceDateIso,
                                     String totalAmount, String confidence, String seriesCodeGuess) {}

    /** Sube el PDF y devuelve los campos detectados por OCR para confirmar. */
    public MigrationExtracted extractMigrationBaseline(byte[] pdf) throws IOException, InterruptedException {
        String b64 = java.util.Base64.getEncoder().encodeToString(pdf);
        String body = "{\"pdfBase64\":\"" + b64 + "\"}";
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(
                URI.create(baseUrl + "/billing/migration-baseline/extract"))
                .timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        ensureOk(response);
        String j = response.body();
        return new MigrationExtracted(
                textField(j, "emitterNif"), textField(j, "customerNif"), textField(j, "customerName"),
                textField(j, "invoiceNumber"), textField(j, "invoiceDateIso"),
                numField(j, "totalAmount"), textField(j, "confidence"), textField(j, "seriesCodeGuess"));
    }

    /** Confirma: fija next_number + guarda el PDF como prueba y la declaración firmada. */
    public void confirmMigrationBaseline(String seriesId, String declaredSeriesCode, String declaredFullNumber,
                                         Integer declaredNumber, String declaredDate, Integer declaredYear,
                                         String emitterNif, String customerNif, String customerName,
                                         String totalAmount, String ocrConfidence, boolean signed,
                                         String declarationText, byte[] pdf)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        b.append(field("seriesId", seriesId)).append(',');
        b.append(field("declaredSeriesCode", declaredSeriesCode)).append(',');
        b.append(field("declaredFullNumber", declaredFullNumber)).append(',');
        b.append("\"declaredNumber\":").append(declaredNumber == null ? "null" : declaredNumber).append(',');
        b.append(field("declaredDate", declaredDate)).append(',');
        b.append("\"declaredYear\":").append(declaredYear == null ? "null" : declaredYear).append(',');
        b.append(field("emitterNif", emitterNif)).append(',');
        b.append(field("customerNif", customerNif)).append(',');
        b.append(field("customerName", customerName)).append(',');
        b.append("\"totalAmount\":").append(totalAmount == null || totalAmount.isBlank() ? "null" : totalAmount).append(',');
        b.append(field("ocrConfidence", ocrConfidence)).append(',');
        b.append("\"declarationSigned\":").append(signed).append(',');
        b.append(field("declarationText", declarationText)).append(',');
        b.append("\"pdfBase64\":").append(pdf == null ? "null"
                : "\"" + java.util.Base64.getEncoder().encodeToString(pdf) + "\"");
        b.append('}');
        HttpResponse<String> response = sendAuthorized(HttpRequest.newBuilder(
                URI.create(baseUrl + "/billing/migration-baseline/confirm"))
                .timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(b.toString())));
        ensureOk(response);
    }

    private String numField(String json, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + name + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(json == null ? "" : json);
        return m.find() ? m.group(1) : null;
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
            // Incluimos invoiceType y originalInvoiceId para que la UI pueda
            // distinguir, p.ej., rectificativas (botón Anular no debe
            // activarse sobre ellas — una rectificativa ES la anulación).
            list.add(new SalesInvoiceSummary(
                    textField(obj, "id"),
                    textField(obj, "invoiceNumber"),
                    textField(obj, "customerLegalName"),
                    textField(obj, "invoiceDate"),
                    textField(obj, "dueDate"),
                    textField(obj, "status"),
                    textField(obj, "paymentStatus"),
                    decimalField(obj, "total"),
                    decimalField(obj, "paidAmount"),
                    textField(obj, "invoiceType"),
                    textField(obj, "originalInvoiceId"),
                    !textField(obj, "pdfPath").isEmpty()
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
                    boolField(obj, "active"),
                    textField(obj, "expeditedByCompanyId")
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
                textField(json, "modality"),
                textField(json, "mode"),
                textField(json, "certificateId"),
                textField(json, "certificateAlias"),
                textField(json, "invoiceFooterTemplate"),
                textField(json, "invoiceStorageRoot")
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
