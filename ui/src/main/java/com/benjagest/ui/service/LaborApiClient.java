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

    /**
     * L4-4: sobrecargas para pasar PIN + rol al backend en el mismo
     * upsert. {@code pin} null = no tocar; "" idem; "1234" → bcrypt
     * en backend. {@code roleInCompany} solo se aplica al provisionar
     * (primera vez que se marca app_access).
     */
    public EmployeeEntry createEmployee(EmployeeEntry e, Boolean appAccess,
                                          String pin, String roleInCompany)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/employees")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        employeeBody(e, appAccess, pin, roleInCompany))));
        return mapEmployee(r.body());
    }

    public EmployeeEntry updateEmployee(String id, EmployeeEntry e, Boolean appAccess,
                                          String pin, String roleInCompany)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/employees/" + id)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        employeeBody(e, appAccess, pin, roleInCompany))));
        return mapEmployee(r.body());
    }

    private String employeeBody(EmployeeEntry e) {
        // Compat: las llamadas antiguas no envían info de acceso/PIN —
        // null = backend no toca app_access ni pin existentes.
        return employeeBody(e, null, null, null);
    }

    private String employeeBody(EmployeeEntry e, Boolean appAccess,
                                  String pin, String roleInCompany) {
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
        b.append(field("workCalendarId", e.workCalendarId())).append(",");
        b.append(field("hireDate", e.hireDate() == null ? null : e.hireDate().toString())).append(",");
        b.append(field("terminationDate", e.terminationDate() == null ? null : e.terminationDate().toString())).append(",");
        b.append(field("terminationReason", e.terminationReason())).append(",");
        b.append("\"geolocationEnabled\":").append(e.geolocationEnabled()).append(",");
        b.append("\"active\":").append(e.active());
        // L4-4: campos opcionales de acceso. Si appAccess es null, no se
        // serializa → el backend (UpsertRequest.appAccess() == null) no
        // hace transición; deja el estado actual intacto.
        if (appAccess != null) {
            b.append(",\"appAccess\":").append(appAccess);
        }
        if (pin != null && !pin.isBlank()) {
            b.append(",").append(field("pin", pin));
        }
        if (roleInCompany != null && !roleInCompany.isBlank()) {
            b.append(",").append(field("roleInCompany", roleInCompany));
        }
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
                textField(obj, "workCalendarId"),
                parseDate(textField(obj, "hireDate")),
                parseDate(textField(obj, "terminationDate")),
                textField(obj, "terminationReason"),
                boolField(obj, "geolocationEnabled"),
                boolField(obj, "active"),
                // L4-4: 3 campos nuevos
                boolField(obj, "appAccess"),
                textField(obj, "userId"),
                boolField(obj, "hasPin")
        );
    }

    // ====================================================================
    //  Contratos
    // ====================================================================

    public List<ContractEntry> listContracts(String employeeId) throws IOException, InterruptedException {
        String url = baseUrl + "/labor/contracts" + (employeeId == null || employeeId.isBlank()
                ? "" : "?employeeId=" + employeeId);
        HttpResponse<String> r = send(req(url).GET());
        // Los contratos llevan un array anidado (salaryItems): el parser
        // plano no sirve; usamos el splitter por llaves balanceadas.
        java.util.List<ContractEntry> outc = new ArrayList<>();
        for (String objc : splitTopLevelObjects(r.body())) outc.add(mapContract(objc));
        return outc;
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

    /** VIG-3 (menor): ¿el contrato tiene nóminas? Para bloquear editar fechas. */
    public boolean contractHasPayslips(String id) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/contracts/" + id + "/has-payslips").GET());
        return r.body() != null && r.body().contains("true");
    }

    // ==== FM — Kioscos de fichaje (admin) ====

    public java.util.List<com.benjagest.ui.model.KioskDeviceEntry> listKioskDevices()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/kiosk/devices").GET());
        java.util.List<com.benjagest.ui.model.KioskDeviceEntry> out = new ArrayList<>();
        for (String o : splitTopLevelObjects(r.body())) out.add(mapKioskDevice(o));
        return out;
    }

    public com.benjagest.ui.model.KioskDeviceEntry createKioskDevice(
            String name, String workCenterId, boolean requirePhoto)
            throws IOException, InterruptedException {
        String body = "{" + field("name", name) + "," + field("workCenterId", workCenterId)
                + ",\"requirePhoto\":" + requirePhoto + "}";
        HttpResponse<String> r = send(req(baseUrl + "/kiosk/devices")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        return mapKioskDevice(r.body());
    }

    public void deleteKioskDevice(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/kiosk/devices/" + id).DELETE());
    }

    /** Genera el código de activación (para el QR / a teclear en el dispositivo). */
    public String generateKioskActivationToken(String id) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/kiosk/devices/" + id + "/activation-token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        return textField(r.body(), "activationToken");
    }

    /** IDs de los empleados asignados a un kiosco. */
    public java.util.List<String> listKioskEmployeeIds(String deviceId)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/kiosk/devices/" + deviceId + "/employees").GET());
        java.util.List<String> out = new ArrayList<>();
        for (String o : splitTopLevelObjects(r.body())) out.add(textField(o, "id"));
        return out;
    }

    public void assignKioskEmployees(String deviceId, java.util.List<String> employeeIds)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{\"employeeIds\":[");
        for (int i = 0; i < employeeIds.size(); i++) {
            if (i > 0) b.append(",");
            b.append("\"").append(employeeIds.get(i)).append("\"");
        }
        b.append("]}");
        send(req(baseUrl + "/kiosk/devices/" + deviceId + "/employees")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(b.toString())));
    }

    public void removeKioskEmployee(String deviceId, String employeeId)
            throws IOException, InterruptedException {
        send(req(baseUrl + "/kiosk/devices/" + deviceId + "/employees/" + employeeId).DELETE());
    }

    private com.benjagest.ui.model.KioskDeviceEntry mapKioskDevice(String o) {
        return new com.benjagest.ui.model.KioskDeviceEntry(
                textField(o, "id"), textField(o, "name"), textField(o, "workCenterId"),
                boolField(o, "requirePhoto"), intFieldOrZero(o, "photoRetentionDays"),
                boolField(o, "active"), boolField(o, "activated"));
    }

    // ===== MEMP-1: invitacion a la PWA del empleado =====

    public com.benjagest.ui.model.AppInvitationResult generateAppInvitation(String employeeId)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/employees/" + employeeId + "/app-invitation")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        String token = textField(r.body(), "token");
        String url = textField(r.body(), "url");
        int hours = intFieldOrZero(r.body(), "expiresInHours");
        // Si el backend devuelve URL absoluta (benjagest.public-base-url
        // configurado: tunel/dominio), usarla tal cual; si no, componer con
        // el host de la API local.
        String full = (url != null && url.startsWith("http"))
                ? url : baseUrl.replaceAll("/api/?$", "") + url;
        return new com.benjagest.ui.model.AppInvitationResult(token, full, hours);
    }

    // ===== JOR-1: jornada real desde fichajes =====

    public java.util.List<com.benjagest.ui.model.WorkdayEntry> listWorkdays(
            LocalDate from, LocalDate to, String employeeId)
            throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl + "/labor/workdays?from=" + from + "&to=" + to);
        if (employeeId != null && !employeeId.isBlank()) url.append("&employeeId=").append(employeeId);
        HttpResponse<String> r = send(req(url.toString()).GET());
        java.util.List<com.benjagest.ui.model.WorkdayEntry> out = new ArrayList<>();
        for (String o : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.WorkdayEntry(
                    textField(o, "employeeId"), textField(o, "employeeName"),
                    parseDateStr(textField(o, "date")),
                    textField(o, "firstIn"), textField(o, "lastOut"),
                    (int) longField(o, "workedMinutes"), (int) longField(o, "pauseMinutes"),
                    intFieldOrZero(o, "events")));
        }
        return out;
    }

    // ===== JOR-2: plantillas de horario (planificacion) =====

    public java.util.List<com.benjagest.ui.model.ScheduleTemplateEntry> listScheduleTemplates()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/schedule-templates").GET());
        java.util.List<com.benjagest.ui.model.ScheduleTemplateEntry> out = new ArrayList<>();
        for (String o : splitTopLevelObjects(r.body())) out.add(mapScheduleTemplate(o));
        return out;
    }

    public String createScheduleTemplate(String name, String description)
            throws IOException, InterruptedException {
        String body = "{" + field("name", name) + "," + field("description", description) + "}";
        HttpResponse<String> r = send(req(baseUrl + "/labor/schedule-templates")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        return textField(r.body(), "id");
    }

    public void updateScheduleTemplate(String id, String name, String description, boolean active)
            throws IOException, InterruptedException {
        String body = "{" + field("name", name) + "," + field("description", description)
                + ",\"active\":" + active + "}";
        send(req(baseUrl + "/labor/schedule-templates/" + id)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    public void deleteScheduleTemplate(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/labor/schedule-templates/" + id).DELETE());
    }

    /** Devuelve los bloques de una plantilla (la cabecera ya viene del listado). */
    public java.util.List<com.benjagest.ui.model.ScheduleBlockEntry> getScheduleBlocks(String id)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/schedule-templates/" + id).GET());
        String blocksArr = extractArray(r.body(), "blocks");
        java.util.List<com.benjagest.ui.model.ScheduleBlockEntry> out = new ArrayList<>();
        for (String o : splitTopLevelObjects(blocksArr)) {
            out.add(new com.benjagest.ui.model.ScheduleBlockEntry(
                    textField(o, "id"), intFieldOrZero(o, "weekday"),
                    textField(o, "blockType"), textField(o, "startTime"), textField(o, "endTime")));
        }
        return out;
    }

    public void replaceScheduleBlocks(String id,
            java.util.List<com.benjagest.ui.model.ScheduleBlockEntry> blocks)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < blocks.size(); i++) {
            var bl = blocks.get(i);
            if (i > 0) b.append(",");
            b.append("{\"weekday\":").append(bl.weekday()).append(",")
             .append(field("blockType", bl.blockType())).append(",")
             .append(field("startTime", bl.startTime())).append(",")
             .append(field("endTime", bl.endTime())).append("}");
        }
        b.append("]");
        send(req(baseUrl + "/labor/schedule-templates/" + id + "/blocks")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(b.toString())));
    }

    public java.util.List<com.benjagest.ui.model.ScheduleAssignmentEntry> listScheduleAssignments(String id)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/schedule-templates/" + id + "/assignments").GET());
        java.util.List<com.benjagest.ui.model.ScheduleAssignmentEntry> out = new ArrayList<>();
        for (String o : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.ScheduleAssignmentEntry(
                    textField(o, "id"), textField(o, "employeeId"), textField(o, "employeeName"),
                    parseDateStr(textField(o, "effectiveFrom")), parseDateStr(textField(o, "effectiveTo"))));
        }
        return out;
    }

    public void assignSchedule(String templateId, String employeeId, LocalDate from, LocalDate to)
            throws IOException, InterruptedException {
        String body = "{" + field("employeeId", employeeId) + ","
                + field("effectiveFrom", from == null ? null : from.toString()) + ","
                + field("effectiveTo", to == null ? null : to.toString()) + "}";
        send(req(baseUrl + "/labor/schedule-templates/" + templateId + "/assignments")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    public void removeScheduleAssignment(String assignmentId) throws IOException, InterruptedException {
        send(req(baseUrl + "/labor/schedule-templates/assignments/" + assignmentId).DELETE());
    }

    private com.benjagest.ui.model.ScheduleTemplateEntry mapScheduleTemplate(String o) {
        return new com.benjagest.ui.model.ScheduleTemplateEntry(
                textField(o, "id"), textField(o, "name"), textField(o, "description"),
                boolField(o, "active"), intFieldOrZero(o, "blocks"), intFieldOrZero(o, "assignments"));
    }

    /** Extrae el cuerpo de un array JSON ("campo":[...]) balanceando corchetes. */
    private String extractArray(String json, String field) {
        int k = json.indexOf("\"" + field + "\"");
        if (k < 0) return "";
        int lb = json.indexOf('[', k);
        if (lb < 0) return "";
        int depth = 0;
        for (int i = lb; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return json.substring(lb, i + 1); }
        }
        return json.substring(lb);
    }

    private long longField(String json, String f) {
        Matcher m = Pattern.compile("\"" + f + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!m.find()) return 0L;
        try { return Long.parseLong(m.group(1)); } catch (NumberFormatException e) { return 0L; }
    }

    /** Normaliza fecha (soporta ISO instant) a "yyyy-MM-dd" o "". */
    private String parseDateStr(String s) {
        LocalDate d = parseDate(s);
        return d == null ? "" : d.toString();
    }

    /**
     * VIG-3 — Ascenso/cambio de condiciones con fecha de efecto. Crea una
     * nueva vigencia del MISMO contrato (antigüedad intacta) y deja la fila
     * del contrato con las condiciones nuevas. Las nóminas de periodos
     * anteriores siguen usando la vigencia previa.
     */
    public ContractEntry promoteContract(String id, String effectiveFrom, String reason,
                                          ContractEntry c) throws IOException, InterruptedException {
        String body = "{"
                + field("effectiveFrom", effectiveFrom) + ","
                + field("reason", reason) + ","
                + "\"contract\":" + contractBody(c)
                + "}";
        HttpResponse<String> r = send(req(baseUrl + "/labor/contracts/" + id + "/promote")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        return mapContract(r.body());
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
        b.append(field("seniorityDate", c.seniorityDate() == null ? null : c.seniorityDate().toString())).append(",");
        b.append(field("endDate", c.endDate() == null ? null : c.endDate().toString())).append(",");
        b.append(decField("weeklyHours", c.weeklyHours())).append(",");
        b.append(decField("grossSalary", c.grossSalary())).append(",");
        b.append(intField("annualBonuses", c.annualBonuses())).append(",");
        b.append("\"extrasProrated\":").append(c.extrasProrated() != null && c.extrasProrated()).append(",");
        b.append(intField("vacationDays", c.vacationDays())).append(",");
        b.append(decField("irpfPercent", c.irpfPercent())).append(",");
        b.append(decField("atEpPercent", c.atEpPercent())).append(",");
        b.append(intField("ssContributionGroup", c.ssContributionGroup())).append(",");
        b.append(field("workplaceAddress", c.workplaceAddress())).append(",");
        b.append(field("status", c.status())).append(",");
        b.append(field("terminationReason", c.terminationReason())).append(",");
        b.append(intField("probationDays", c.probationDays())).append(",");
        b.append(field("pdfModel", c.pdfModel()));
        java.util.List<com.benjagest.ui.model.SalaryItemEntry> items = c.salaryItems();
        if (items == null) {
            b.append(",\"salaryItems\":null");
        } else {
            b.append(",\"salaryItems\":[");
            for (int i = 0; i < items.size(); i++) {
                var it = items.get(i);
                if (i > 0) b.append(",");
                b.append("{")
                 .append(field("conceptName", it.conceptName())).append(",")
                 .append(field("kind", it.kind())).append(",")
                 .append(decField("annualAmount", it.annualAmount())).append(",")
                 .append("\"cotizes\":").append(it.cotizes()).append(",")
                 .append("\"taxable\":").append(it.taxable())
                 .append("}");
            }
            b.append("]");
        }
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
                parseDate(textField(obj, "seniorityDate")),
                parseDate(textField(obj, "endDate")),
                bigDec(obj, "weeklyHours"),
                bigDec(obj, "grossSalary"),
                intFieldOrNull(obj, "annualBonuses"),
                boolField(obj, "extrasProrated"),
                intFieldOrNull(obj, "vacationDays"),
                bigDec(obj, "irpfPercent"),
                bigDec(obj, "atEpPercent"),
                intFieldOrNull(obj, "ssContributionGroup"),
                textField(obj, "workplaceAddress"),
                textField(obj, "status"),
                textField(obj, "terminationReason"),
                intFieldOrNull(obj, "probationDays"),
                textField(obj, "pdfModel"),
                parseSalaryItems(obj)
        );
    }

    /** Extrae el array anidado "salaryItems" de un objeto contrato. */
    private java.util.List<com.benjagest.ui.model.SalaryItemEntry> parseSalaryItems(String contractObj) {
        java.util.List<com.benjagest.ui.model.SalaryItemEntry> out = new ArrayList<>();
        int idx = contractObj.indexOf("\"salaryItems\"");
        if (idx < 0) return out;
        int lb = contractObj.indexOf('[', idx);
        if (lb < 0) return out;
        int depth = 0, end = -1;
        boolean inStr = false;
        char prev = 0;
        for (int i = lb; i < contractObj.length(); i++) {
            char ch = contractObj.charAt(i);
            if (inStr) { if (ch == '"' && prev != '\\') inStr = false; prev = ch; continue; }
            if (ch == '"') { inStr = true; prev = ch; continue; }
            if (ch == '[') depth++;
            else if (ch == ']') { depth--; if (depth == 0) { end = i; break; } }
            prev = ch;
        }
        if (end < 0) return out;
        for (String o : splitTopLevelObjects(contractObj.substring(lb, end + 1))) {
            out.add(new com.benjagest.ui.model.SalaryItemEntry(
                    textField(o, "id"), textField(o, "conceptName"), textField(o, "kind"),
                    bigDec(o, "annualAmount"), boolField(o, "cotizes"), boolField(o, "taxable")));
        }
        return out;
    }

    /** Divide los objetos {...} de primer nivel de un JSON, balanceando
     *  llaves y respetando strings (soporta objetos/arrays anidados). */
    private java.util.List<String> splitTopLevelObjects(String json) {
        java.util.List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inStr = false;
        char prev = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inStr) { if (c == '"' && prev != '\\') inStr = false; prev = c; continue; }
            if (c == '"') { inStr = true; prev = c; continue; }
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && start >= 0) { out.add(json.substring(start, i + 1)); start = -1; } }
            prev = c;
        }
        return out;
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
    //  Nominas (L4)
    // ====================================================================

    public java.util.List<com.benjagest.ui.model.PayslipEntry> listPayslips(Integer year, String status, String employeeId)
            throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl + "/labor/payslips?");
        if (year != null) url.append("year=").append(year).append("&");
        if (status != null && !status.isBlank()) url.append("status=").append(status).append("&");
        if (employeeId != null && !employeeId.isBlank()) url.append("employeeId=").append(employeeId);
        HttpResponse<String> r = send(req(url.toString()).GET());
        return parseObjects(r.body(), "employeeId", obj -> new com.benjagest.ui.model.PayslipEntry(
                textField(obj, "id"),
                textField(obj, "employeeId"),
                textField(obj, "employeeName"),
                textField(obj, "contractId"),
                intFieldOrZero(obj, "periodYear"),
                intFieldOrZero(obj, "periodMonth"),
                textField(obj, "payslipType"),
                bigDec(obj, "grossAmount"),
                bigDec(obj, "ssEmployeeAmount"),
                bigDec(obj, "irpfAmount"),
                bigDec(obj, "otherDeductions"),
                bigDec(obj, "netAmount"),
                textField(obj, "status"),
                textField(obj, "paidAt"),
                textField(obj, "pdfPath"),
                textField(obj, "notes"),
                textField(obj, "deliveredAt"),
                textField(obj, "deliveryMethod"),
                textField(obj, "acknowledgedAt")
        ));
    }

    /** Fila del reporte de coste de empresa por empleado (bloque NOM). */
    public record EmployerCostEntry(
            String employeeId, String employeeName,
            java.math.BigDecimal grossTotal, java.math.BigDecimal employerSsTotal,
            java.math.BigDecimal costTotal) {}

    public java.util.List<EmployerCostEntry> employerCost(int year)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/payslips/employer-cost?year=" + year).GET());
        return parseObjects(r.body(), "employeeId", obj -> new EmployerCostEntry(
                textField(obj, "employeeId"),
                textField(obj, "employeeName"),
                bigDec(obj, "grossTotal"),
                bigDec(obj, "employerSsTotal"),
                bigDec(obj, "costTotal")
        ));
    }

    // ==== Previsualización + objetivo de sueldo (PREVIEW / OBJETIVO) ====

    private String payslipBody(String employeeId, int year, int month, String type,
                               boolean extraProrated, java.math.BigDecimal otherDeductions, String notes,
                               java.util.List<com.benjagest.ui.model.SalaryItemEntry> extras,
                               String mode, java.math.BigDecimal target) {
        StringBuilder b = new StringBuilder("{");
        b.append(field("employeeId", employeeId)).append(",");
        b.append("\"year\":").append(year).append(",\"month\":").append(month).append(",");
        b.append(field("payslipType", type)).append(",");
        b.append("\"includeExtraProrated\":").append(extraProrated).append(",");
        b.append(decField("otherDeductions", otherDeductions)).append(",");
        b.append(field("notes", notes));
        if (mode != null) { b.append(",").append(field("mode", mode)); }
        if (target != null) { b.append(",").append(decField("target", target)); }
        b.append(",\"extraConcepts\":[");
        if (extras != null) {
            for (int i = 0; i < extras.size(); i++) {
                var ec = extras.get(i);
                if (i > 0) b.append(",");
                b.append("{")
                 .append(field("name", ec.conceptName())).append(",")
                 .append(decField("amount", ec.annualAmount())).append(",")
                 .append("\"cotizes\":").append(ec.cotizes()).append(",")
                 .append("\"taxable\":").append(ec.taxable())
                 .append("}");
            }
        }
        b.append("]}");
        return b.toString();
    }

    public com.benjagest.ui.model.PayslipPreview previewPayslip(String employeeId, int year, int month,
            String type, boolean extraProrated, java.math.BigDecimal otherDeductions,
            java.util.List<com.benjagest.ui.model.SalaryItemEntry> extras)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/payslips/preview")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        payslipBody(employeeId, year, month, type, extraProrated, otherDeductions, null, extras, null, null))));
        String o = r.body();
        return new com.benjagest.ui.model.PayslipPreview(
                bigDec(o, "gross"), bigDec(o, "cotizationBase"), bigDec(o, "ssEmployee"),
                bigDec(o, "irpf"), bigDec(o, "irpfPct"), bigDec(o, "otherDeductions"),
                bigDec(o, "net"), bigDec(o, "employerTotal"), bigDec(o, "employerCost"));
    }

    /** Devuelve el "plus" (mejora voluntaria) para llegar al objetivo. */
    public java.math.BigDecimal solveTargetPlus(String employeeId, int year, int month, String type,
            boolean extraProrated, String mode, java.math.BigDecimal target,
            java.util.List<com.benjagest.ui.model.SalaryItemEntry> extras)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/payslips/solve-target")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        payslipBody(employeeId, year, month, type, extraProrated, null, null, extras, mode, target))));
        return bigDec(r.body(), "plus");
    }

    /** Genera las nóminas mensuales de todos los empleados activos del mes. */
    public com.benjagest.ui.model.MonthlyRunEntry generateMonthPayslips(int year, int month)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/payslips/generate-month?year=" + year + "&month=" + month)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        String b = r.body();
        Integer gen = intFieldOrNull(b, "generated");
        Integer skip = intFieldOrNull(b, "skipped");
        java.util.List<String> errs = new ArrayList<>();
        int ei = b.indexOf("\"errors\"");
        if (ei >= 0) {
            int lb = b.indexOf('[', ei), rb = b.indexOf(']', lb);
            if (lb >= 0 && rb > lb) {
                Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(b.substring(lb + 1, rb));
                while (m.find()) errs.add(m.group(1).replace("\\\"", "\""));
            }
        }
        return new com.benjagest.ui.model.MonthlyRunEntry(gen == null ? 0 : gen, skip == null ? 0 : skip, errs);
    }

    // ==== Baja / despido (CV-ORQ) ====

    public com.benjagest.ui.model.TerminationPreviewEntry previewTermination(
            String employeeId, java.time.LocalDate ceseDate, String type, String extrasAccrual,
            java.math.BigDecimal otherDeductions, String notes) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/terminations/preview")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        terminationBody(employeeId, ceseDate, type, extrasAccrual, otherDeductions, notes))));
        String s = extractObject(r.body(), "settlement");
        String v = extractObject(r.body(), "severance");
        return new com.benjagest.ui.model.TerminationPreviewEntry(
                bigDec(s, "gross"), bigDec(s, "ssEmployee"), bigDec(s, "irpf"), bigDec(s, "net"),
                bigDec(s, "employerCost"),
                bigDec(v, "gross"), bigDec(v, "exempt"), bigDec(v, "taxable"),
                bigDec(v, "days"), bigDec(v, "antiquityYears"), bigDec(v, "dailySalary"),
                intVal(v, "antiqYears"), intVal(v, "antiqMonths"), intVal(v, "antiqDays"));
    }

    private int intVal(String json, String field) {
        Integer v = intFieldOrNull(json, field);
        return v == null ? 0 : v;
    }

    public void executeTermination(String employeeId, java.time.LocalDate ceseDate, String type,
            String extrasAccrual, java.math.BigDecimal otherDeductions, String notes)
            throws IOException, InterruptedException {
        send(req(baseUrl + "/labor/terminations/execute")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        terminationBody(employeeId, ceseDate, type, extrasAccrual, otherDeductions, notes))));
    }

    private String terminationBody(String employeeId, java.time.LocalDate ceseDate, String type,
            String accrual, java.math.BigDecimal other, String notes) {
        return "{" + field("employeeId", employeeId) + ","
                + field("ceseDate", ceseDate == null ? null : ceseDate.toString()) + ","
                + field("type", type) + ","
                + field("extrasAccrual", accrual) + ","
                + decField("otherDeductions", other) + ","
                + field("notes", notes) + "}";
    }

    /** Extrae el sub-objeto JSON "key":{...} balanceando llaves. */
    private String extractObject(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "{}";
        int lb = json.indexOf('{', i);
        if (lb < 0) return "{}";
        int depth = 0; boolean inStr = false; char prev = 0;
        for (int j = lb; j < json.length(); j++) {
            char ch = json.charAt(j);
            if (inStr) { if (ch == '"' && prev != '\\') inStr = false; }
            else if (ch == '"') inStr = true;
            else if (ch == '{') depth++;
            else if (ch == '}') { depth--; if (depth == 0) return json.substring(lb, j + 1); }
            prev = ch;
        }
        return "{}";
    }

    // ==== Vacaciones (CV-VAC) ====

    public java.util.List<com.benjagest.ui.model.VacationEntry> listVacations(String employeeId, Integer year)
            throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl + "/labor/vacations?");
        if (employeeId != null && !employeeId.isBlank()) url.append("employeeId=").append(employeeId).append("&");
        if (year != null) url.append("year=").append(year);
        HttpResponse<String> r = send(req(url.toString()).GET());
        java.util.List<com.benjagest.ui.model.VacationEntry> out = new ArrayList<>();
        for (String o : splitTopLevelObjects(r.body())) out.add(mapVacation(o));
        return out;
    }

    public void createVacation(com.benjagest.ui.model.VacationEntry v) throws IOException, InterruptedException {
        send(req(baseUrl + "/labor/vacations").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(vacationBody(v))));
    }

    public void updateVacation(String id, com.benjagest.ui.model.VacationEntry v)
            throws IOException, InterruptedException {
        send(req(baseUrl + "/labor/vacations/" + id).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(vacationBody(v))));
    }

    public void deleteVacation(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/labor/vacations/" + id).DELETE());
    }

    private String vacationBody(com.benjagest.ui.model.VacationEntry v) {
        return "{" + field("employeeId", v.employeeId()) + ","
                + field("startDate", v.startDate() == null ? null : v.startDate().toString()) + ","
                + field("endDate", v.endDate() == null ? null : v.endDate().toString()) + ","
                + decField("days", v.days()) + ","
                + field("status", v.status()) + ","
                + field("notes", v.notes()) + "}";
    }

    private com.benjagest.ui.model.VacationEntry mapVacation(String o) {
        return new com.benjagest.ui.model.VacationEntry(
                textField(o, "id"), textField(o, "employeeId"), textField(o, "employeeName"),
                parseDate(textField(o, "startDate")), parseDate(textField(o, "endDate")),
                bigDec(o, "days"), textField(o, "status"), textField(o, "notes"));
    }

    /** Calcula los conceptos de un finiquito (salario días trabajados +
     *  vacaciones no disfrutadas + prorrata pagas extra) para revisar/editar. */
    public java.util.List<com.benjagest.ui.model.SalaryItemEntry> settlementConcepts(
            String employeeId, int year, int month, int ceseDay,
            java.math.BigDecimal vacationDays, String extrasAccrual)
            throws IOException, InterruptedException {
        String body = "{" + field("employeeId", employeeId) + ","
                + intField("year", year) + "," + intField("month", month) + ","
                + intField("ceseDay", ceseDay) + ","
                + decField("vacationDays", vacationDays) + ","
                + field("extrasAccrual", extrasAccrual) + "}";
        HttpResponse<String> r = send(req(baseUrl + "/labor/payslips/settlement-concepts")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        java.util.List<com.benjagest.ui.model.SalaryItemEntry> out = new ArrayList<>();
        for (String o : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.SalaryItemEntry(
                    null, textField(o, "name"), "COMPLEMENT", bigDec(o, "amount"),
                    boolField(o, "cotizes"), boolField(o, "taxable")));
        }
        return out;
    }

    /** Guarda (o actualiza por nombre) un complemento MENSUAL recurrente en el
     *  contrato activo del empleado. La mejora de "llegar a un objetivo" vive en
     *  el contrato para anualizar en base SS e IRPF. */
    public void upsertRecurringComplement(String employeeId, String conceptName,
            java.math.BigDecimal monthlyAmount) throws IOException, InterruptedException {
        String body = "{" + field("employeeId", employeeId) + ","
                + field("conceptName", conceptName) + ","
                + decField("monthlyAmount", monthlyAmount) + "}";
        send(req(baseUrl + "/labor/contracts/recurring-complement")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    /** Claves de los parámetros IRPF editables por año (mínimos/reducciones). */
    public static final String[] IRPF_PARAM_KEYS = {
            "personalMin", "personalOver65", "personalOver75",
            "desc1", "desc2", "desc3", "desc4plus", "descUnder3",
            "ascOver65", "ascOver75", "disability33", "disability65", "disabilityMobility",
            "expenseDeduction", "workMax", "workThreshold1", "workThreshold2", "workFactor",
            "workMax2", "workThreshold3", "workFactor2",
            "moreThan2Desc", "limitRate", "limitIncomeCap"
    };

    /** Lee los parámetros (mínimos/reducciones) IRPF de un año. */
    public java.util.Map<String, java.math.BigDecimal> getIrpfParams(int year)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/irpf-params/params/" + year).GET());
        java.util.LinkedHashMap<String, java.math.BigDecimal> m = new java.util.LinkedHashMap<>();
        for (String k : IRPF_PARAM_KEYS) m.put(k, bigDec(r.body(), k));
        return m;
    }

    /** Guarda los parámetros (mínimos/reducciones) IRPF de un año. */
    public void saveIrpfParams(int year, java.util.Map<String, java.math.BigDecimal> p)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (String k : IRPF_PARAM_KEYS) {
            if (!first) b.append(",");
            first = false;
            java.math.BigDecimal v = p.get(k);
            b.append("\"").append(k).append("\":").append(v == null ? "null" : v.toPlainString());
        }
        b.append("}");
        send(req(baseUrl + "/labor/irpf-params/params/" + year)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(b.toString())));
    }

    // ==== Parámetros IRPF por año (escala) ====

    public java.util.List<Integer> listIrpfYears() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/irpf-params/years").GET());
        java.util.List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("-?\\d+").matcher(r.body());
        while (m.find()) out.add(Integer.parseInt(m.group()));
        return out;
    }

    /** Devuelve la escala de un año como pares [lowerLimit, rate]. */
    public java.util.List<double[]> listIrpfBrackets(int year) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/irpf-params/brackets/" + year).GET());
        java.util.List<double[]> out = new ArrayList<>();
        for (String o : splitTopLevelObjects(r.body())) {
            java.math.BigDecimal lo = bigDec(o, "lowerLimit");
            java.math.BigDecimal ra = bigDec(o, "rate");
            out.add(new double[]{lo == null ? 0 : lo.doubleValue(), ra == null ? 0 : ra.doubleValue()});
        }
        return out;
    }

    public void saveIrpfBrackets(int year, java.util.List<double[]> brackets)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < brackets.size(); i++) {
            if (i > 0) b.append(",");
            b.append("{\"lowerLimit\":").append(brackets.get(i)[0])
             .append(",\"rate\":").append(brackets.get(i)[1]).append("}");
        }
        b.append("]");
        send(req(baseUrl + "/labor/irpf-params/brackets/" + year)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(b.toString())));
    }

    public void cloneIrpfYear(int year) throws IOException, InterruptedException {
        send(req(baseUrl + "/labor/irpf-params/clone/" + year)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
    }

    // ==== RETA-0: tramos de cotización por año (editables, no-code) ====

    public java.util.List<Integer> listRetaTramoYears() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/reta/tramos/years").GET());
        java.util.List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("-?\\d+").matcher(r.body());
        while (m.find()) out.add(Integer.parseInt(m.group()));
        return out;
    }

    public java.util.List<com.benjagest.ui.model.RetaTramoEntry> listRetaTramos(int year)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/reta/tramos/" + year).GET());
        java.util.List<com.benjagest.ui.model.RetaTramoEntry> out = new ArrayList<>();
        for (String o : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.RetaTramoEntry(
                    textField(o, "label"),
                    bigDec(o, "incomeMaxMonthly"),
                    bigDec(o, "baseMin"),
                    bigDec(o, "baseMax"),
                    bigDec(o, "quotaMin")));
        }
        return out;
    }

    public void saveRetaTramos(int year, java.util.List<com.benjagest.ui.model.RetaTramoEntry> rows)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            var tr = rows.get(i);
            if (i > 0) b.append(",");
            String label = tr.label() == null ? "" :
                    tr.label().replace("\\", "\\\\").replace("\"", "\\\"");
            b.append("{\"label\":\"").append(label).append("\",")
             .append("\"incomeMaxMonthly\":").append(plain(tr.incomeMaxMonthly())).append(",")
             .append("\"baseMin\":").append(plain(tr.baseMin())).append(",")
             .append("\"baseMax\":").append(plain(tr.baseMax())).append(",")
             .append("\"quotaMin\":").append(plain(tr.quotaMin())).append("}");
        }
        b.append("]");
        send(req(baseUrl + "/reta/tramos/" + year)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(b.toString())));
    }

    public void cloneRetaTramos(int targetYear, int srcYear) throws IOException, InterruptedException {
        send(req(baseUrl + "/reta/tramos/" + targetYear + "/clone-from/" + srcYear)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
    }

    private static String plain(java.math.BigDecimal v) {
        return v == null ? "0" : v.toPlainString();
    }

    // ==== RETA-3: escaneo de regularización (base vs tramo por P&L real) ====

    public java.util.List<com.benjagest.ui.model.RetaRegularizationEntry> scanRetaRegularization(int year)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/reta/regularization/scan?year=" + year)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        java.util.List<com.benjagest.ui.model.RetaRegularizationEntry> out = new ArrayList<>();
        for (String o : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.RetaRegularizationEntry(
                    textField(o, "companyId"), textField(o, "companyName"),
                    textField(o, "profileId"), textField(o, "fullName"),
                    bigDec(o, "currentBase"), bigDec(o, "netIncome"),
                    textField(o, "tramoLabel"), bigDec(o, "baseMin"), bigDec(o, "baseMax"),
                    textField(o, "status")));
        }
        return out;
    }

    /** Sugiere el tramo (base mín/máx + cuota mín) para un rendimiento anual. */
    public com.benjagest.ui.model.RetaTramoEntry suggestRetaTramo(int year, java.math.BigDecimal annualNetIncome)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/reta/tramos/suggest?annualNetIncome="
                + (annualNetIncome == null ? "0" : annualNetIncome.toPlainString())
                + "&year=" + year).GET());
        String o = r.body();
        return new com.benjagest.ui.model.RetaTramoEntry(
                textField(o, "tramoLabel"), null,
                bigDec(o, "baseMinima"), bigDec(o, "baseMaxima"), bigDec(o, "cuotaMinima"));
    }

    /** Catálogo oficial de actividades (type = CNAE | IAE) como "código — descripción". */
    public java.util.List<String> activityCatalog(String type) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/reta/activity-catalog?type=" + type).GET());
        java.util.List<String> out = new ArrayList<>();
        for (String o : splitTopLevelObjects(r.body())) {
            String code = textField(o, "code");
            String desc = textField(o, "description");
            if (code == null) continue;
            out.add(desc == null || desc.isBlank() ? code : code + " — " + desc);
        }
        return out;
    }

    /** Valores ya usados para los combos del editor (código actividad, IAE, descripción). */
    public java.util.Map<String, java.util.List<String>> retaCatalogs()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/reta/catalogs").GET());
        java.util.Map<String, java.util.List<String>> out = new java.util.LinkedHashMap<>();
        for (String key : new String[]{"activityCodes", "iaeEpigraphs", "activityDescriptions"}) {
            out.put(key, parseStringArray(r.body(), key));
        }
        return out;
    }

    private java.util.List<String> parseStringArray(String json, String key) {
        java.util.List<String> out = new ArrayList<>();
        Matcher km = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(json);
        if (!km.find()) return out;
        Matcher vm = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(km.group(1));
        while (vm.find()) out.add(vm.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        return out;
    }

    // ==== AVISOS: tareas pendientes (per-empresa / cartera) ====

    public java.util.List<com.benjagest.ui.model.PendingTaskBucket> pendingTasks(boolean portfolio)
            throws IOException, InterruptedException {
        String url = baseUrl + "/pending-tasks" + (portfolio ? "/portfolio" : "");
        HttpResponse<String> r = send(req(url).GET());
        java.util.List<com.benjagest.ui.model.PendingTaskBucket> out = new ArrayList<>();
        for (String o : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.PendingTaskBucket(
                    textField(o, "type"), intFieldOrZero(o, "count"), textField(o, "severity")));
        }
        return out;
    }

    /** Crea los perfiles RETA que falten en la empresa actual (titulares RETA o,
     *  si la empresa es AUTONOMO, el perfil de la propia empresa). Idempotente. */
    public void ensureRetaProfiles() throws IOException, InterruptedException {
        send(req(baseUrl + "/reta/ensure-profiles")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
    }

    // ==== CLIENT-CONFIG: cifras manuales + config interna del cliente ====

    public java.util.List<com.benjagest.ui.model.ClientConfigModels.ManualFinancialEntry>
            listClientFinancials(Integer year) throws IOException, InterruptedException {
        String url = baseUrl + "/client-config/financials" + (year == null ? "" : "?year=" + year);
        HttpResponse<String> r = send(req(url).GET());
        java.util.List<com.benjagest.ui.model.ClientConfigModels.ManualFinancialEntry> out = new ArrayList<>();
        for (String o : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.ClientConfigModels.ManualFinancialEntry(
                    textField(o, "id"), intFieldOrZero(o, "periodYear"), intFieldOrZero(o, "periodQuarter"),
                    bigDec(o, "income"), bigDec(o, "expenses"), bigDec(o, "netResult"), textField(o, "notes")));
        }
        return out;
    }

    public void upsertClientFinancial(com.benjagest.ui.model.ClientConfigModels.ManualFinancialEntry f)
            throws IOException, InterruptedException {
        String body = "{"
                + (f.id() == null ? "" : "\"id\":\"" + f.id() + "\",")
                + "\"periodYear\":" + f.periodYear() + ","
                + "\"periodQuarter\":" + f.periodQuarter() + ","
                + "\"income\":" + plain(f.income()) + ","
                + "\"expenses\":" + plain(f.expenses()) + ","
                + "\"netResult\":" + (f.netResult() == null ? "null" : plain(f.netResult())) + ","
                + "\"notes\":" + jsonStr(f.notes()) + "}";
        send(req(baseUrl + "/client-config/financials")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    public void deleteClientFinancial(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/client-config/financials/" + id).DELETE());
    }

    public com.benjagest.ui.model.ClientConfigModels.AdvisoryConfigEntry getClientAdvisoryConfig()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/client-config/advisory").GET());
        String o = r.body();
        return new com.benjagest.ui.model.ClientConfigModels.AdvisoryConfigEntry(
                textField(o, "fiscalPeriod"), textField(o, "taxRegime"),
                textField(o, "contactChannel"), textField(o, "contactValue"), textField(o, "internalNotes"),
                textField(o, "legalForm"),
                !o.matches("(?s).*\"provisionExtraPay\"\\s*:\\s*false.*"));
    }

    public void saveClientAdvisoryConfig(com.benjagest.ui.model.ClientConfigModels.AdvisoryConfigEntry c)
            throws IOException, InterruptedException {
        String body = "{"
                + "\"fiscalPeriod\":" + jsonStr(c.fiscalPeriod()) + ","
                + "\"taxRegime\":" + jsonStr(c.taxRegime()) + ","
                + "\"contactChannel\":" + jsonStr(c.contactChannel()) + ","
                + "\"contactValue\":" + jsonStr(c.contactValue()) + ","
                + "\"internalNotes\":" + jsonStr(c.internalNotes()) + ","
                + "\"legalForm\":" + jsonStr(c.legalForm()) + ","
                + "\"provisionExtraPay\":" + c.provisionExtraPay() + "}";
        send(req(baseUrl + "/client-config/advisory")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    private static String jsonStr(String v) {
        if (v == null) return "null";
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    // ==== Modelo 145 (datos IRPF del empleado) ====

    public com.benjagest.ui.model.Modelo145Entry getIrpfData(String employeeId)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/irpf-data/" + employeeId).GET());
        String o = r.body();
        return new com.benjagest.ui.model.Modelo145Entry(
                intFieldOrZero(o, "familySituation"), textField(o, "spouseNif"),
                intFieldOrZero(o, "descendants"), intFieldOrZero(o, "descendantsUnder3"),
                intFieldOrZero(o, "descendantsDisability33"), intFieldOrZero(o, "descendantsDisability65"),
                boolField(o, "exclusiveCustody"),
                intFieldOrZero(o, "ascendantsOver65"), intFieldOrZero(o, "ascendantsOver75"),
                textField(o, "ownDisability"), boolField(o, "ownMobility"),
                boolField(o, "taxpayerOver65"), boolField(o, "taxpayerOver75"),
                boolField(o, "contractUnderYear"), boolField(o, "geographicMobility"),
                boolField(o, "mortgageBefore2013"));
    }

    public void saveIrpfData(String employeeId, com.benjagest.ui.model.Modelo145Entry m)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        b.append("\"familySituation\":").append(m.familySituation()).append(",");
        b.append(field("spouseNif", m.spouseNif())).append(",");
        b.append("\"descendants\":").append(m.descendants()).append(",");
        b.append("\"descendantsUnder3\":").append(m.descendantsUnder3()).append(",");
        b.append("\"descendantsDisability33\":").append(m.descendantsDisability33()).append(",");
        b.append("\"descendantsDisability65\":").append(m.descendantsDisability65()).append(",");
        b.append("\"exclusiveCustody\":").append(m.exclusiveCustody()).append(",");
        b.append("\"ascendantsOver65\":").append(m.ascendantsOver65()).append(",");
        b.append("\"ascendantsOver75\":").append(m.ascendantsOver75()).append(",");
        b.append(field("ownDisability", m.ownDisability())).append(",");
        b.append("\"ownMobility\":").append(m.ownMobility()).append(",");
        b.append("\"taxpayerOver65\":").append(m.taxpayerOver65()).append(",");
        b.append("\"taxpayerOver75\":").append(m.taxpayerOver75()).append(",");
        b.append("\"contractUnderYear\":").append(m.contractUnderYear()).append(",");
        b.append("\"geographicMobility\":").append(m.geographicMobility()).append(",");
        b.append("\"mortgageBefore2013\":").append(m.mortgageBefore2013());
        b.append("}");
        send(req(baseUrl + "/labor/irpf-data/" + employeeId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(b.toString())));
    }

    // ==== Tipos de cotización SS por año (PARAM-YEAR) ====

    public java.util.List<com.benjagest.ui.model.SsRateEntry> listSsRates()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/ss-rates").GET());
        return parseObjects(r.body(), "year", o -> new com.benjagest.ui.model.SsRateEntry(
                intFieldOrZero(o, "year"),
                bigDec(o, "eeCommon"), bigDec(o, "eeUnemployment"),
                bigDec(o, "eeTraining"), bigDec(o, "eeMei"),
                bigDec(o, "erCommon"), bigDec(o, "erUnemployment"), bigDec(o, "erFogasa"),
                bigDec(o, "erTraining"), bigDec(o, "erMei"), bigDec(o, "defaultAtEp"),
                bigDec(o, "baseMaxMonthly"), bigDec(o, "baseMinMonthly"),
                textField(o, "legalReference")));
    }

    public void upsertSsRate(com.benjagest.ui.model.SsRateEntry e)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        b.append("\"year\":").append(e.year()).append(",");
        b.append(decField("eeCommon", e.eeCommon())).append(",");
        b.append(decField("eeUnemployment", e.eeUnemployment())).append(",");
        b.append(decField("eeTraining", e.eeTraining())).append(",");
        b.append(decField("eeMei", e.eeMei())).append(",");
        b.append(decField("erCommon", e.erCommon())).append(",");
        b.append(decField("erUnemployment", e.erUnemployment())).append(",");
        b.append(decField("erFogasa", e.erFogasa())).append(",");
        b.append(decField("erTraining", e.erTraining())).append(",");
        b.append(decField("erMei", e.erMei())).append(",");
        b.append(decField("defaultAtEp", e.defaultAtEp())).append(",");
        b.append(decField("baseMaxMonthly", e.baseMaxMonthly())).append(",");
        b.append(decField("baseMinMonthly", e.baseMinMonthly())).append(",");
        b.append(field("legalReference", e.legalReference()));
        b.append("}");
        send(req(baseUrl + "/labor/ss-rates")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(b.toString())));
    }

    /**
     * IRPF-VOL — Tipo de retención sugerido (mínimo legal) para un empleado y un
     * bruto anual. El editor de contrato lo muestra y avisa si la retención
     * voluntaria es menor.
     */
    public java.math.BigDecimal suggestIrpfRate(String employeeId, java.math.BigDecimal annualGross, int year)
            throws IOException, InterruptedException {
        String url = baseUrl + "/labor/payslips/suggest-irpf?employeeId="
                + java.net.URLEncoder.encode(employeeId, java.nio.charset.StandardCharsets.UTF_8)
                + "&annualGross=" + (annualGross == null ? "0" : annualGross.toPlainString())
                + "&year=" + year;
        HttpResponse<String> r = send(req(url).GET());
        return bigDec(r.body(), "rate");
    }

    // ==== Topes de indemnización por año (V127, no-code) — N3(b) ====

    public java.util.List<com.benjagest.ui.model.SeveranceParamEntry> listSeveranceParams()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/severance-params").GET());
        return parseObjects(r.body(), "yearNumber", o -> new com.benjagest.ui.model.SeveranceParamEntry(
                intFieldOrZero(o, "yearNumber"),
                bigDec(o, "unfairDaysPerYear"), intFieldOrZero(o, "unfairCapDays"),
                bigDec(o, "unfairPre2012DaysPerYear"), intFieldOrZero(o, "unfairPre2012CapDays"),
                bigDec(o, "objectiveDaysPerYear"), intFieldOrZero(o, "objectiveCapDays"),
                bigDec(o, "endContractDaysPerYear"), bigDec(o, "irpfExemptCap"),
                textField(o, "legalReference")));
    }

    public void upsertSeveranceParam(com.benjagest.ui.model.SeveranceParamEntry e)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        b.append("\"yearNumber\":").append(e.yearNumber()).append(",");
        b.append(decField("unfairDaysPerYear", e.unfairDaysPerYear())).append(",");
        b.append("\"unfairCapDays\":").append(e.unfairCapDays()).append(",");
        b.append(decField("unfairPre2012DaysPerYear", e.unfairPre2012DaysPerYear())).append(",");
        b.append("\"unfairPre2012CapDays\":").append(e.unfairPre2012CapDays()).append(",");
        b.append(decField("objectiveDaysPerYear", e.objectiveDaysPerYear())).append(",");
        b.append("\"objectiveCapDays\":").append(e.objectiveCapDays()).append(",");
        b.append(decField("endContractDaysPerYear", e.endContractDaysPerYear())).append(",");
        b.append(decField("irpfExemptCap", e.irpfExemptCap())).append(",");
        b.append(field("legalReference", e.legalReference()));
        b.append("}");
        send(req(baseUrl + "/labor/severance-params")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(b.toString())));
    }

    // ==== Bases de cotización SS por GRUPO y año (V121, no-code) ====

    public java.util.List<Integer> listSsGroupBaseYears() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/ss-group-bases/years").GET());
        java.util.List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("-?\\d+").matcher(r.body());
        while (m.find()) out.add(Integer.parseInt(m.group()));
        return out;
    }

    public java.util.List<com.benjagest.ui.model.SsGroupBaseEntry> listSsGroupBases(int year)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/ss-group-bases/" + year).GET());
        java.util.List<com.benjagest.ui.model.SsGroupBaseEntry> out = new ArrayList<>();
        for (String o : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.SsGroupBaseEntry(
                    intFieldOrZero(o, "yearNumber"),
                    intFieldOrZero(o, "cotizGroup"),
                    textField(o, "label"),
                    bigDec(o, "baseMin"),
                    bigDec(o, "baseMax"),
                    boolField(o, "daily"),
                    boolField(o, "pendingValidation")));
        }
        return out;
    }

    public void saveSsGroupBases(int year, java.util.List<com.benjagest.ui.model.SsGroupBaseEntry> rows)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            var g = rows.get(i);
            if (i > 0) b.append(",");
            String label = g.label() == null ? "" :
                    g.label().replace("\\", "\\\\").replace("\"", "\\\"");
            b.append("{\"cotizGroup\":").append(g.cotizGroup()).append(",")
             .append("\"label\":\"").append(label).append("\",")
             .append("\"baseMin\":").append(plain(g.baseMin())).append(",")
             .append("\"baseMax\":").append(plain(g.baseMax())).append(",")
             .append("\"daily\":").append(g.daily()).append(",")
             .append("\"pendingValidation\":").append(g.pendingValidation()).append("}");
        }
        b.append("]");
        send(req(baseUrl + "/labor/ss-group-bases/" + year)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(b.toString())));
    }

    public void cloneSsGroupBases(int targetYear) throws IOException, InterruptedException {
        send(req(baseUrl + "/labor/ss-group-bases/clone?targetYear=" + targetYear)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
    }

    public com.benjagest.ui.model.PayslipEntry calculatePayslip(String employeeId, int year, int month,
                                                                  String type, boolean extraProrated,
                                                                  java.math.BigDecimal otherDeductions, String notes,
                                                                  java.util.List<com.benjagest.ui.model.SalaryItemEntry> extraConcepts)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        b.append(field("employeeId", employeeId)).append(",");
        b.append("\"year\":").append(year).append(",\"month\":").append(month).append(",");
        b.append(field("payslipType", type)).append(",");
        b.append("\"includeExtraProrated\":").append(extraProrated).append(",");
        b.append(decField("otherDeductions", otherDeductions)).append(",");
        b.append(field("notes", notes));
        b.append(",\"extraConcepts\":[");
        if (extraConcepts != null) {
            for (int i = 0; i < extraConcepts.size(); i++) {
                var ec = extraConcepts.get(i);
                if (i > 0) b.append(",");
                b.append("{")
                 .append(field("name", ec.conceptName())).append(",")
                 .append(decField("amount", ec.annualAmount())).append(",")
                 .append("\"cotizes\":").append(ec.cotizes()).append(",")
                 .append("\"taxable\":").append(ec.taxable())
                 .append("}");
            }
        }
        b.append("]");
        b.append("}");
        HttpResponse<String> r = send(req(baseUrl + "/labor/payslips/calculate")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(b.toString())));
        String obj = r.body();
        return new com.benjagest.ui.model.PayslipEntry(
                textField(obj, "id"), textField(obj, "employeeId"), textField(obj, "employeeName"),
                textField(obj, "contractId"),
                intFieldOrZero(obj, "periodYear"), intFieldOrZero(obj, "periodMonth"),
                textField(obj, "payslipType"),
                bigDec(obj, "grossAmount"), bigDec(obj, "ssEmployeeAmount"),
                bigDec(obj, "irpfAmount"), bigDec(obj, "otherDeductions"),
                bigDec(obj, "netAmount"), textField(obj, "status"),
                textField(obj, "paidAt"), textField(obj, "pdfPath"), textField(obj, "notes"),
                textField(obj, "deliveredAt"), textField(obj, "deliveryMethod"),
                textField(obj, "acknowledgedAt"));
    }

    public void markPayslipPaid(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/labor/payslips/" + id + "/pay")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{}")));
    }

    /** Registra la entrega del recibo al trabajador (fecha + vía). */
    public void markPayslipDelivered(String id, java.time.LocalDate deliveredAt, String method)
            throws IOException, InterruptedException {
        String body = "{\"deliveredAt\":" + (deliveredAt == null ? "null" : "\"" + deliveredAt + "\"")
                + ",\"method\":" + (method == null ? "null" : "\"" + method + "\"") + "}";
        send(req(baseUrl + "/labor/payslips/" + id + "/deliver")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    /** Registra el acuse de recibo (firma) del trabajador. */
    public void markPayslipAcknowledged(String id, java.time.LocalDate acknowledgedAt)
            throws IOException, InterruptedException {
        String body = "{\"acknowledgedAt\":" + (acknowledgedAt == null ? "null" : "\"" + acknowledgedAt + "\"") + "}";
        send(req(baseUrl + "/labor/payslips/" + id + "/acknowledge")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    public void deletePayslip(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/labor/payslips/" + id).DELETE());
    }

    /**
     * Descarga el PDF de la nomina. Devuelve los bytes para que el caller
     * los guarde en un fichero elegido por el usuario.
     */
    public byte[] downloadPayslipPdf(String id) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/labor/payslips/" + id + "/pdf"))
                .timeout(Duration.ofSeconds(30))
                .GET();
        AuthSession.get().authorize(b);
        HttpResponse<byte[]> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
        return r.body();
    }

    /** Descarga un documento de baja (carta de despido / certificado de empresa). */
    public byte[] downloadTerminationDoc(String which, String employeeId, java.time.LocalDate date, String type)
            throws IOException, InterruptedException {
        String url = baseUrl + "/labor/terminations/docs/" + which
                + "?employeeId=" + employeeId + "&date=" + date + "&type=" + type;
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30)).GET();
        AuthSession.get().authorize(b);
        HttpResponse<byte[]> r = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() < 200 || r.statusCode() >= 300) throw new IOException("HTTP " + r.statusCode());
        return r.body();
    }

    public void emailPayslipToEmployee(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/labor/payslips/" + id + "/email")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
    }

    public String resolveEmployeeIdForCurrentUser(String userId) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/payslips/resolve-self?userId=" + userId).GET());
        String body = r.body();
        if (body == null || body.isBlank() || body.equals("{}")) return null;
        return textField(body, "employeeId");
    }

    // ====================================================================
    //  Tipos de evento de fichaje (TC-CFG)
    // ====================================================================

    public java.util.List<com.benjagest.ui.model.TimeClockEventTypeEntry> listTimeClockEventTypes(boolean includeInactive)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/timeclock/event-types?includeInactive=" + includeInactive).GET());
        return parseObjects(r.body(), "code", this::mapEventType);
    }

    public com.benjagest.ui.model.TimeClockEventTypeEntry createEventType(
            String code, String labelEs, String labelEn, String icon,
            Integer displayOrder, boolean isWorkTime, boolean isPause, boolean active)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/timeclock/event-types")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(eventTypeBody(
                        code, labelEs, labelEn, icon, displayOrder, isWorkTime, isPause, active))));
        return mapEventType(r.body());
    }

    public com.benjagest.ui.model.TimeClockEventTypeEntry updateEventType(
            String id, String code, String labelEs, String labelEn, String icon,
            Integer displayOrder, boolean isWorkTime, boolean isPause, boolean active)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/timeclock/event-types/" + id)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(eventTypeBody(
                        code, labelEs, labelEn, icon, displayOrder, isWorkTime, isPause, active))));
        return mapEventType(r.body());
    }

    public void deleteEventType(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/timeclock/event-types/" + id).DELETE());
    }

    private String eventTypeBody(String code, String labelEs, String labelEn, String icon,
                                  Integer displayOrder, boolean isWorkTime, boolean isPause, boolean active) {
        StringBuilder b = new StringBuilder("{");
        b.append(field("code", code)).append(",");
        b.append(field("labelEs", labelEs)).append(",");
        b.append(field("labelEn", labelEn)).append(",");
        b.append(field("icon", icon)).append(",");
        b.append(intField("displayOrder", displayOrder)).append(",");
        b.append("\"isWorkTime\":").append(isWorkTime).append(",");
        b.append("\"isPause\":").append(isPause).append(",");
        b.append("\"active\":").append(active);
        b.append("}");
        return b.toString();
    }

    // ====================================================================
    //  Auditoría fichajes (TC-AUDIT)
    // ====================================================================

    public java.util.List<com.benjagest.ui.model.TimeClockAuditEntry> queryAudit(
            String fromIsoDate, String toIsoDate, String employeeId, String eventType)
            throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl + "/timeclock/audit?");
        if (fromIsoDate != null && !fromIsoDate.isBlank()) url.append("from=").append(fromIsoDate).append("&");
        if (toIsoDate != null && !toIsoDate.isBlank()) url.append("to=").append(toIsoDate).append("&");
        if (employeeId != null && !employeeId.isBlank()) url.append("employeeId=").append(employeeId).append("&");
        if (eventType != null && !eventType.isBlank()) url.append("eventType=").append(eventType);
        HttpResponse<String> r = send(req(url.toString()).GET());
        return parseObjects(r.body(), "eventType", obj -> new com.benjagest.ui.model.TimeClockAuditEntry(
                textField(obj, "id"),
                textField(obj, "employeeId"),
                textField(obj, "employeeName"),
                textField(obj, "eventType"),
                textField(obj, "eventTime"),
                textField(obj, "origin"),
                textField(obj, "status"),
                textField(obj, "createdAt"),
                textField(obj, "csv"),
                intFieldOrZero(obj, "correctionCount"),
                textField(obj, "lastCorrectionAt")
        ));
    }

    public java.util.List<com.benjagest.ui.model.TimeClockAuditSummary> auditSummary(
            String fromIsoDate, String toIsoDate) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl + "/timeclock/audit/summary?");
        if (fromIsoDate != null && !fromIsoDate.isBlank()) url.append("from=").append(fromIsoDate).append("&");
        if (toIsoDate != null && !toIsoDate.isBlank()) url.append("to=").append(toIsoDate);
        HttpResponse<String> r = send(req(url.toString()).GET());
        return parseObjects(r.body(), "employeeName", obj -> new com.benjagest.ui.model.TimeClockAuditSummary(
                textField(obj, "employeeId"),
                textField(obj, "employeeName"),
                intFieldOrZero(obj, "totalEvents"),
                textField(obj, "firstEvent"),
                textField(obj, "lastEvent"),
                intFieldOrZero(obj, "pauses"),
                intFieldOrZero(obj, "ins"),
                intFieldOrZero(obj, "outs"),
                intFieldOrZero(obj, "corrections"),
                boolField(obj, "hasIncidence")
        ));
    }

    private com.benjagest.ui.model.TimeClockEventTypeEntry mapEventType(String obj) {
        return new com.benjagest.ui.model.TimeClockEventTypeEntry(
                textField(obj, "id"),
                textField(obj, "code"),
                textField(obj, "labelEs"),
                textField(obj, "labelEn"),
                textField(obj, "icon"),
                intFieldOrZero(obj, "displayOrder"),
                boolField(obj, "isWorkTime"),
                boolField(obj, "isPause"),
                boolField(obj, "active")
        );
    }

    // ====================================================================
    //  Inmovilizado (C1)
    // ====================================================================

    public java.util.List<com.benjagest.ui.model.FixedAssetEntry> listAssets(boolean includeInactive)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/accounting/fixed-assets?includeInactive=" + includeInactive).GET());
        return parseObjects(r.body(), "code", this::mapAsset);
    }

    public com.benjagest.ui.model.FixedAssetEntry createAsset(com.benjagest.ui.model.FixedAssetEntry a)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/accounting/fixed-assets")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(assetBody(a))));
        return mapAsset(r.body());
    }

    public com.benjagest.ui.model.FixedAssetEntry updateAsset(String id, com.benjagest.ui.model.FixedAssetEntry a)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/accounting/fixed-assets/" + id)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(assetBody(a))));
        return mapAsset(r.body());
    }

    public void deleteAsset(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/accounting/fixed-assets/" + id).DELETE());
    }

    public int calculateMonthDepreciations(int year, int month) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/accounting/fixed-assets/calculate-month?year=" + year + "&month=" + month)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        try { return Integer.parseInt(r.body().trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private String assetBody(com.benjagest.ui.model.FixedAssetEntry a) {
        StringBuilder b = new StringBuilder("{");
        b.append(field("code", a.code())).append(",");
        b.append(field("name", a.name())).append(",");
        b.append(field("description", a.description())).append(",");
        b.append(field("category", a.category())).append(",");
        b.append(field("accountingAccountId", a.accountingAccountId())).append(",");
        b.append(field("acquisitionDate", a.acquisitionDate() == null ? null : a.acquisitionDate().toString())).append(",");
        b.append(decField("acquisitionCost", a.acquisitionCost())).append(",");
        b.append(decField("residualValue", a.residualValue())).append(",");
        b.append(decField("usefulLifeYears", a.usefulLifeYears())).append(",");
        b.append(field("depreciationMethod", a.depreciationMethod())).append(",");
        b.append(field("inServiceDate", a.inServiceDate() == null ? null : a.inServiceDate().toString())).append(",");
        b.append(field("supplierName", a.supplierName())).append(",");
        b.append(field("invoiceReference", a.invoiceReference())).append(",");
        b.append(field("notes", a.notes())).append(",");
        b.append("\"active\":").append(a.active());
        b.append("}");
        return b.toString();
    }

    private com.benjagest.ui.model.FixedAssetEntry mapAsset(String obj) {
        return new com.benjagest.ui.model.FixedAssetEntry(
                textField(obj, "id"), textField(obj, "code"), textField(obj, "name"),
                textField(obj, "description"), textField(obj, "category"),
                textField(obj, "accountingAccountId"),
                parseDate(textField(obj, "acquisitionDate")),
                bigDec(obj, "acquisitionCost"), bigDec(obj, "residualValue"),
                bigDec(obj, "usefulLifeYears"), textField(obj, "depreciationMethod"),
                parseDate(textField(obj, "inServiceDate")),
                parseDate(textField(obj, "disposedAt")),
                textField(obj, "disposalReason"), bigDec(obj, "disposalValue"),
                textField(obj, "supplierName"), textField(obj, "invoiceReference"),
                textField(obj, "notes"), boolField(obj, "active")
        );
    }

    // Cierre de ejercicio (CONS-CIERRE): los métodos viven ahora en
    // AccountingApiClient (es donde se consume, desde AccountingScreen).

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
