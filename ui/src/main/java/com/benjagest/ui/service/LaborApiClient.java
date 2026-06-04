package com.benjagest.ui.service;

import com.benjagest.ui.model.ContractEntry;
import com.benjagest.ui.model.DehuNotificationEntry;
import com.benjagest.ui.model.DehuSummary;
import com.benjagest.ui.model.EmployeeEntry;
import com.benjagest.ui.model.RetaBaseChangeEntry;
import com.benjagest.ui.model.RetaProfileEntry;
import com.benjagest.ui.model.RetaTramoSuggestion;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente HTTP unificado para los modulos laboral, RETA y DEHu.
 * Mismo patron que AltaApiClient (regex JSON, HttpClient + AuthSession).
 */
public class LaborApiClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";

    private final HttpClient httpClient;
    private final String baseUrl;

    public LaborApiClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), apiBaseUrl());
    }

    LaborApiClient(HttpClient httpClient, String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    // ====================================================================
    //  Empleados
    // ====================================================================

    public List<EmployeeEntry> listEmployees(boolean includeInactive) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/employees?includeInactive=" + includeInactive).GET());
        return parseObjects(r.body(), "fullName", this::mapEmployee);
    }

    public EmployeeEntry createEmployee(EmployeeEntry e) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/employees")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(employeeBody(e))));
        return mapEmployee(r.body());
    }

    public EmployeeEntry updateEmployee(String id, EmployeeEntry e) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/employees/" + id)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(employeeBody(e))));
        return mapEmployee(r.body());
    }

    public void deleteEmployee(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/labor/employees/" + id).DELETE());
    }

    private String employeeBody(EmployeeEntry e) {
        StringBuilder b = new StringBuilder("{");
        b.append(field("fullName", e.fullName())).append(",");
        b.append(field("taxIdentifier", e.taxIdentifier())).append(",");
        b.append(field("socialSecurityNumber", e.socialSecurityNumber())).append(",");
        b.append(field("email", e.email())).append(",");
        b.append(field("phone", e.phone())).append(",");
        b.append(field("birthDate", e.birthDate() == null ? null : e.birthDate().toString())).append(",");
        b.append(field("gender", e.gender())).append(",");
        b.append(field("maritalStatus", e.maritalStatus())).append(",");
        b.append(intField("dependentChildren", e.dependentChildren())).append(",");
        b.append(intField("dependentDisabled", e.dependentDisabled())).append(",");
        b.append(field("addressLine", e.addressLine())).append(",");
        b.append(field("city", e.city())).append(",");
        b.append(field("province", e.province())).append(",");
        b.append(field("postalCode", e.postalCode())).append(",");
        b.append(field("country", e.country())).append(",");
        b.append(field("iban", e.iban())).append(",");
        b.append(field("workType", e.workType())).append(",");
        b.append(field("ssRegime", e.ssRegime())).append(",");
        b.append(field("hireDate", e.hireDate() == null ? null : e.hireDate().toString())).append(",");
        b.append(field("terminationDate", e.terminationDate() == null ? null : e.terminationDate().toString())).append(",");
        b.append(field("terminationReason", e.terminationReason())).append(",");
        b.append("\"active\":").append(e.active());
        b.append("}");
        return b.toString();
    }

    private EmployeeEntry mapEmployee(String obj) {
        return new EmployeeEntry(
                textField(obj, "id"),
                textField(obj, "fullName"),
                textField(obj, "taxIdentifier"),
                textField(obj, "socialSecurityNumber"),
                textField(obj, "email"),
                textField(obj, "phone"),
                parseDate(textField(obj, "birthDate")),
                textField(obj, "gender"),
                textField(obj, "maritalStatus"),
                intFieldOrNull(obj, "dependentChildren"),
                intFieldOrNull(obj, "dependentDisabled"),
                textField(obj, "addressLine"),
                textField(obj, "city"),
                textField(obj, "province"),
                textField(obj, "postalCode"),
                textField(obj, "country"),
                textField(obj, "iban"),
                textField(obj, "workType"),
                textField(obj, "ssRegime"),
                parseDate(textField(obj, "hireDate")),
                parseDate(textField(obj, "terminationDate")),
                textField(obj, "terminationReason"),
                boolField(obj, "active")
        );
    }

    // ====================================================================
    //  Contratos
    // ====================================================================

    public List<ContractEntry> listContracts(String employeeId) throws IOException, InterruptedException {
        String url = baseUrl + "/labor/contracts" + (employeeId == null || employeeId.isBlank()
                ? "" : "?employeeId=" + employeeId);
        HttpResponse<String> r = send(req(url).GET());
        return parseObjects(r.body(), "contractType", this::mapContract);
    }

    public ContractEntry createContract(ContractEntry c) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/contracts")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(contractBody(c))));
        return mapContract(r.body());
    }

    public ContractEntry updateContract(String id, ContractEntry c) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/contracts/" + id)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(contractBody(c))));
        return mapContract(r.body());
    }

    public void deleteContract(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/labor/contracts/" + id).DELETE());
    }

    private String contractBody(ContractEntry c) {
        StringBuilder b = new StringBuilder("{");
        b.append(field("employeeId", c.employeeId())).append(",");
        b.append(field("contractType", c.contractType())).append(",");
        b.append(field("sepeContractCode", c.sepeContractCode())).append(",");
        b.append(field("collectiveAgreement", c.collectiveAgreement())).append(",");
        b.append(field("professionalCategory", c.professionalCategory())).append(",");
        b.append(field("professionalGroup", c.professionalGroup())).append(",");
        b.append(field("startDate", c.startDate() == null ? null : c.startDate().toString())).append(",");
        b.append(field("endDate", c.endDate() == null ? null : c.endDate().toString())).append(",");
        b.append(decField("weeklyHours", c.weeklyHours())).append(",");
        b.append(decField("grossSalary", c.grossSalary())).append(",");
        b.append(intField("annualBonuses", c.annualBonuses())).append(",");
        b.append(intField("vacationDays", c.vacationDays())).append(",");
        b.append(decField("irpfPercent", c.irpfPercent())).append(",");
        b.append(field("workplaceAddress", c.workplaceAddress())).append(",");
        b.append(field("status", c.status())).append(",");
        b.append(field("terminationReason", c.terminationReason()));
        b.append("}");
        return b.toString();
    }

    private ContractEntry mapContract(String obj) {
        return new ContractEntry(
                textField(obj, "id"),
                textField(obj, "employeeId"),
                textField(obj, "contractType"),
                textField(obj, "sepeContractCode"),
                textField(obj, "collectiveAgreement"),
                textField(obj, "professionalCategory"),
                textField(obj, "professionalGroup"),
                parseDate(textField(obj, "startDate")),
                parseDate(textField(obj, "endDate")),
                bigDec(obj, "weeklyHours"),
                bigDec(obj, "grossSalary"),
                intFieldOrNull(obj, "annualBonuses"),
                intFieldOrNull(obj, "vacationDays"),
                bigDec(obj, "irpfPercent"),
                textField(obj, "workplaceAddress"),
                textField(obj, "status"),
                textField(obj, "terminationReason")
        );
    }

    // ====================================================================
    //  RETA
    // ====================================================================

    public List<RetaProfileEntry> listRetaProfiles(boolean includeInactive) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/reta/profiles?includeInactive=" + includeInactive).GET());
        return parseObjects(r.body(), "fullName", this::mapReta);
    }

    public RetaProfileEntry createRetaProfile(RetaProfileEntry p) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/reta/profiles")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(retaBody(p))));
        return mapReta(r.body());
    }

    public RetaProfileEntry updateRetaProfile(String id, RetaProfileEntry p) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/reta/profiles/" + id)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(retaBody(p))));
        return mapReta(r.body());
    }

    public void deleteRetaProfile(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/reta/profiles/" + id).DELETE());
    }

    public List<RetaBaseChangeEntry> listRetaChanges(String profileId, int year) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/reta/profiles/" + profileId + "/changes?year=" + year).GET());
        return parseObjects(r.body(), "effectiveDate", obj -> new RetaBaseChangeEntry(
                textField(obj, "id"),
                textField(obj, "profileId"),
                parseDate(textField(obj, "effectiveDate")),
                textField(obj, "changeReason"),
                bigDec(obj, "newBase"),
                bigDec(obj, "newQuota"),
                bigDec(obj, "expectedNetIncome"),
                boolField(obj, "submittedToSs"),
                textField(obj, "notes")
        ));
    }

    public RetaBaseChangeEntry createRetaChange(String profileId, RetaBaseChangeEntry ch)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        b.append(field("effectiveDate", ch.effectiveDate() == null ? null : ch.effectiveDate().toString())).append(",");
        b.append(field("changeReason", ch.changeReason())).append(",");
        b.append(decField("newBase", ch.newBase())).append(",");
        b.append(decField("newQuota", ch.newQuota())).append(",");
        b.append(decField("expectedNetIncome", ch.expectedNetIncome())).append(",");
        b.append("\"submittedToSs\":").append(ch.submittedToSs()).append(",");
        b.append(field("notes", ch.notes()));
        b.append("}");
        HttpResponse<String> r = send(req(baseUrl + "/reta/profiles/" + profileId + "/changes")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(b.toString())));
        String body = r.body();
        return new RetaBaseChangeEntry(
                textField(body, "id"), textField(body, "profileId"),
                parseDate(textField(body, "effectiveDate")), textField(body, "changeReason"),
                bigDec(body, "newBase"), bigDec(body, "newQuota"),
                bigDec(body, "expectedNetIncome"),
                boolField(body, "submittedToSs"), textField(body, "notes"));
    }

    public RetaTramoSuggestion suggestRetaTramo(BigDecimal annualNet) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/reta/tramos/suggest?annualNetIncome=" + annualNet.toPlainString()).GET());
        String body = r.body();
        return new RetaTramoSuggestion(
                textField(body, "tramoLabel"),
                bigDec(body, "baseMinima"), bigDec(body, "baseMaxima"),
                bigDec(body, "cuotaMinima"),
                bigDec(body, "annualNetIncome"), bigDec(body, "monthlyIncome")
        );
    }

    private String retaBody(RetaProfileEntry p) {
        StringBuilder b = new StringBuilder("{");
        b.append(field("ownerId", p.ownerId())).append(",");
        b.append(field("employeeId", p.employeeId())).append(",");
        b.append(field("fullName", p.fullName())).append(",");
        b.append(field("taxIdentifier", p.taxIdentifier())).append(",");
        b.append(field("socialSecurityNumber", p.socialSecurityNumber())).append(",");
        b.append(field("retaStartDate", p.retaStartDate() == null ? null : p.retaStartDate().toString())).append(",");
        b.append(field("retaEndDate", p.retaEndDate() == null ? null : p.retaEndDate().toString())).append(",");
        b.append("\"pluriactividad\":").append(p.pluriactividad()).append(",");
        b.append("\"tarifaPlana\":").append(p.tarifaPlana()).append(",");
        b.append(field("tarifaPlanaUntil", p.tarifaPlanaUntil() == null ? null : p.tarifaPlanaUntil().toString())).append(",");
        b.append(field("activityCode", p.activityCode())).append(",");
        b.append(field("activityDescription", p.activityDescription())).append(",");
        b.append(field("iaeEpigraph", p.iaeEpigraph())).append(",");
        b.append(decField("expectedNetIncome", p.expectedNetIncome())).append(",");
        b.append(decField("currentBase", p.currentBase())).append(",");
        b.append(decField("currentQuota", p.currentQuota())).append(",");
        b.append(field("notes", p.notes())).append(",");
        b.append("\"active\":").append(p.active());
        b.append("}");
        return b.toString();
    }

    private RetaProfileEntry mapReta(String obj) {
        return new RetaProfileEntry(
                textField(obj, "id"),
                textField(obj, "ownerId"),
                textField(obj, "employeeId"),
                textField(obj, "fullName"),
                textField(obj, "taxIdentifier"),
                textField(obj, "socialSecurityNumber"),
                parseDate(textField(obj, "retaStartDate")),
                parseDate(textField(obj, "retaEndDate")),
                boolField(obj, "pluriactividad"),
                boolField(obj, "tarifaPlana"),
                parseDate(textField(obj, "tarifaPlanaUntil")),
                textField(obj, "activityCode"),
                textField(obj, "activityDescription"),
                textField(obj, "iaeEpigraph"),
                bigDec(obj, "expectedNetIncome"),
                bigDec(obj, "currentBase"),
                bigDec(obj, "currentQuota"),
                textField(obj, "notes"),
                boolField(obj, "active")
        );
    }

    // ====================================================================
    //  DEHu
    // ====================================================================

    public List<DehuNotificationEntry> listDehu(String status, int limit) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl + "/notifications/dehu?limit=" + limit);
        if (status != null && !status.isBlank()) url.append("&status=").append(status);
        HttpResponse<String> r = send(req(url.toString()).GET());
        return parseObjects(r.body(), "subject", this::mapDehu);
    }

    public DehuSummary dehuSummary() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/notifications/dehu/summary").GET());
        return new DehuSummary(
                intFieldOrZero(r.body(), "pending"),
                intFieldOrZero(r.body(), "expiringSoon"),
                intFieldOrZero(r.body(), "expired"));
    }

    public DehuNotificationEntry createDehu(DehuNotificationEntry e) throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        b.append(field("dehuId", e.dehuId())).append(",");
        b.append(field("nifReceiver", e.nifReceiver())).append(",");
        b.append(field("organismName", e.organismName())).append(",");
        b.append(field("organismCode", e.organismCode())).append(",");
        b.append(field("procedureName", e.procedureName())).append(",");
        b.append(field("procedureCode", e.procedureCode())).append(",");
        b.append(field("subject", e.subject())).append(",");
        b.append(field("issuedAt", e.issuedAt())).append(",");
        b.append(field("expiresAt", e.expiresAt())).append(",");
        b.append(field("csv", e.csv())).append(",");
        b.append(field("contentUrl", e.contentUrl())).append(",");
        b.append(field("notes", e.notes()));
        b.append("}");
        HttpResponse<String> r = send(req(baseUrl + "/notifications/dehu")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(b.toString())));
        return mapDehu(r.body());
    }

    public DehuNotificationEntry markDehuRead(String id) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/notifications/dehu/" + id + "/read")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}")));
        return mapDehu(r.body());
    }

    public DehuNotificationEntry dismissDehu(String id) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/notifications/dehu/" + id + "/dismiss")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}")));
        return mapDehu(r.body());
    }

    public void deleteDehu(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/notifications/dehu/" + id).DELETE());
    }

    private DehuNotificationEntry mapDehu(String obj) {
        return new DehuNotificationEntry(
                textField(obj, "id"),
                textField(obj, "dehuId"),
                textField(obj, "nifReceiver"),
                textField(obj, "organismName"),
                textField(obj, "organismCode"),
                textField(obj, "procedureName"),
                textField(obj, "procedureCode"),
                textField(obj, "subject"),
                textField(obj, "issuedAt"),
                textField(obj, "expiresAt"),
                textField(obj, "accessedAt"),
                textField(obj, "readAt"),
                textField(obj, "status"),
                textField(obj, "csv"),
                textField(obj, "contentUrl"),
                textField(obj, "localPdfPath"),
                textField(obj, "notes")
        );
    }

    // ====================================================================
    //  Infra
    // ====================================================================

    private HttpRequest.Builder req(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        AuthSession.get().authorize(builder);
        HttpResponse<String> r = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r;
    }

    private interface Mapper<T> { T map(String obj); }

    private <T> List<T> parseObjects(String json, String discriminator, Mapper<T> mapper) {
        List<T> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\{[^{}]*\"" + discriminator + "\"\\s*:\\s*(?:\"[^\"]*\"|\\d+)[^{}]*\\}")
                .matcher(json);
        while (m.find()) out.add(mapper.map(m.group()));
        return out;
    }

    private String field(String name, String value) {
        return "\"" + name + "\":" + (value == null ? "null" : "\"" + escape(value) + "\"");
    }

    private String intField(String name, Integer value) {
        return "\"" + name + "\":" + (value == null ? "null" : value);
    }

    private String decField(String name, BigDecimal value) {
        return "\"" + name + "\":" + (value == null ? "null" : value.toPlainString());
    }

    private String escape(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private String textField(String json, String f) {
        Pattern p = Pattern.compile("\"" + f + "\"\\s*:\\s*(null|\"((?:\\\\.|[^\"])*)\")");
        Matcher m = p.matcher(json);
        if (!m.find() || "null".equals(m.group(1))) return "";
        return m.group(2).replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }

    private boolean boolField(String json, String f) {
        Matcher m = Pattern.compile("\"" + f + "\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() && "true".equals(m.group(1));
    }

    private Integer intFieldOrNull(String json, String f) {
        Matcher m = Pattern.compile("\"" + f + "\"\\s*:\\s*(null|(-?\\d+))").matcher(json);
        if (!m.find() || "null".equals(m.group(1))) return null;
        try { return Integer.parseInt(m.group(2)); } catch (NumberFormatException e) { return null; }
    }

    private int intFieldOrZero(String json, String f) {
        Integer v = intFieldOrNull(json, f);
        return v == null ? 0 : v;
    }

    private BigDecimal bigDec(String json, String f) {
        Matcher m = Pattern.compile("\"" + f + "\"\\s*:\\s*(null|(-?\\d+(?:\\.\\d+)?))").matcher(json);
        if (!m.find() || "null".equals(m.group(1))) return null;
        try { return new BigDecimal(m.group(2)); } catch (NumberFormatException e) { return null; }
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            // Soporta tanto "2026-06-04" como "2026-06-04T..." (ISO instant)
            String iso = s.length() >= 10 ? s.substring(0, 10) : s;
            return LocalDate.parse(iso);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String apiBaseUrl() {
        return System.getenv().getOrDefault("BENJAGEST_API_BASE_URL", DEFAULT_API_BASE_URL);
    }
}
