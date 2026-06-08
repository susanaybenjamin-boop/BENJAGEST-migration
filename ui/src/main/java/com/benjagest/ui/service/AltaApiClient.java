package com.benjagest.ui.service;

import com.benjagest.ui.model.CertificateUsageEntry;
import com.benjagest.ui.model.CompanyOwnerEntry;
import com.benjagest.ui.model.ExternalCredentialEntry;
import com.benjagest.ui.model.ManagedClientEntry;
import com.benjagest.ui.model.TaxDueDateEntry;
import com.benjagest.ui.model.TaxFilingEntry;
import com.benjagest.ui.model.TaxModelEntry;
import java.io.IOException;
import java.math.BigDecimal;
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
 * Cliente HTTP agrupado para los bloques ALTA: titulares, credenciales
 * externas, log uso certificados, asesoria multi-cliente, modelos
 * AEAT y calendario fiscal.
 *
 * Mismo patron que SettingsApiClient: HttpClient + AuthSession +
 * parseo manual con regex (no metemos Jackson solo para esto). Los
 * objetos siempre vienen del backend como records "planos" — sin
 * anidamientos — por lo que la regex por objeto basta.
 */
public class AltaApiClient {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:8080/api";

    private final HttpClient httpClient;
    private final String baseUrl;

    public AltaApiClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), apiBaseUrl());
    }

    AltaApiClient(HttpClient httpClient, String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    // ============================================================
    // TITULARES (/api/settings/owners)
    // ============================================================

    public List<CompanyOwnerEntry> listOwners() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/settings/owners").GET());
        return parseObjects(r.body(), "fullName", this::mapOwner);
    }

    public CompanyOwnerEntry createOwner(CompanyOwnerEntry o) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/settings/owners")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ownerBody(o))));
        return mapOwner(r.body());
    }

    public CompanyOwnerEntry updateOwner(String id, CompanyOwnerEntry o) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/settings/owners/" + id)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(ownerBody(o))));
        return mapOwner(r.body());
    }

    public void deleteOwner(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/settings/owners/" + id).DELETE());
    }

    private String ownerBody(CompanyOwnerEntry o) {
        return "{"
                + field("fullName", o.fullName()) + ","
                + field("taxIdentifier", o.taxIdentifier()) + ","
                + field("role", o.role()) + ","
                + field("ssRegime", o.ssRegime()) + ","
                + bigDecField("ownershipPercent", o.ownershipPercent()) + ","
                + field("appointmentDate", o.appointmentDate()) + ","
                + field("terminationDate", o.terminationDate()) + ","
                + field("email", o.email()) + ","
                + field("phone", o.phone()) + ","
                + field("notes", o.notes()) + ","
                + "\"active\":" + o.active()
                + "}";
    }

    private CompanyOwnerEntry mapOwner(String obj) {
        return new CompanyOwnerEntry(
                textField(obj, "id"),
                textField(obj, "fullName"),
                textField(obj, "taxIdentifier"),
                textField(obj, "role"),
                textField(obj, "ssRegime"),
                bigDecField(obj, "ownershipPercent"),
                textField(obj, "appointmentDate"),
                textField(obj, "terminationDate"),
                textField(obj, "email"),
                textField(obj, "phone"),
                textField(obj, "notes"),
                boolField(obj, "active")
        );
    }

    // ============================================================
    // CREDENCIALES EXTERNAS (/api/credentials/external)
    // ============================================================

    public List<ExternalCredentialEntry> listCredentials() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/credentials/external").GET());
        return parseObjects(r.body(), "systemCode", this::mapCredential);
    }

    public ExternalCredentialEntry createCredential(String systemCode, String label, String username,
                                                     String password, String authUrl, String notes,
                                                     boolean active) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/credentials/external")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        credBody(systemCode, label, username, password, authUrl, notes, active))));
        return mapCredential(r.body());
    }

    public ExternalCredentialEntry updateCredential(String id, String systemCode, String label,
                                                     String username, String passwordOrNull, String authUrl,
                                                     String notes, boolean active)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/credentials/external/" + id)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        credBody(systemCode, label, username, passwordOrNull, authUrl, notes, active))));
        return mapCredential(r.body());
    }

    public void deleteCredential(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/credentials/external/" + id).DELETE());
    }

    private String credBody(String systemCode, String label, String username, String passwordOrNull,
                             String authUrl, String notes, boolean active) {
        StringBuilder b = new StringBuilder("{");
        b.append(field("systemCode", systemCode)).append(",");
        b.append(field("label", label)).append(",");
        b.append(field("username", username)).append(",");
        if (passwordOrNull != null && !passwordOrNull.isBlank()) {
            b.append(field("password", passwordOrNull)).append(",");
        }
        b.append(field("authUrl", authUrl)).append(",");
        b.append(field("notes", notes)).append(",");
        b.append("\"active\":").append(active);
        b.append("}");
        return b.toString();
    }

    private ExternalCredentialEntry mapCredential(String obj) {
        return new ExternalCredentialEntry(
                textField(obj, "id"),
                textField(obj, "systemCode"),
                textField(obj, "label"),
                textField(obj, "username"),
                boolField(obj, "passwordConfigured"),
                textField(obj, "authUrl"),
                textField(obj, "notes"),
                boolField(obj, "active"),
                textField(obj, "lastUsedAt")
        );
    }

    // ============================================================
    // LOG DE USO DE CERTIFICADOS (/api/credentials/cert-usage-log)
    // ============================================================

    public List<CertificateUsageEntry> listCertUsage(String certificateIdOrNull, int limit)
            throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl + "/credentials/cert-usage-log?limit=" + limit);
        if (certificateIdOrNull != null && !certificateIdOrNull.isBlank()) {
            url.append("&certificateId=").append(certificateIdOrNull);
        }
        HttpResponse<String> r = send(req(url.toString()).GET());
        return parseObjects(r.body(), "purpose", obj -> new CertificateUsageEntry(
                textField(obj, "id"),
                textField(obj, "certificateId"),
                textField(obj, "userId"),
                textField(obj, "usedAt"),
                textField(obj, "purpose"),
                textField(obj, "targetUrl"),
                boolField(obj, "success"),
                textField(obj, "errorMessage"),
                textField(obj, "ipAddress")
        ));
    }

    // ============================================================
    // ASESORIA — CLIENTES GESTIONADOS (/api/advisory/clients)
    // ============================================================

    /**
     * Slice 3H-6 — Garantiza que la asesoría logueada tiene su
     * advisory_invitations status=ACCEPTED apuntando a sí misma.
     * Endpoint defensivo invocado antes de abrir "Mi empresa" para
     * cubrir asesorías que existían antes de V64 o cuando la
     * migración no se haya aplicado por cualquier razón.
     */
    public void ensureAdvisorySelfLink() throws IOException, InterruptedException {
        send(req(baseUrl + "/advisory/clients/ensure-self-link")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")));
    }

    public List<ManagedClientEntry> listManagedClients() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/advisory/clients").GET());
        return parseObjects(r.body(), "legalName", obj -> new ManagedClientEntry(
                textField(obj, "id"),
                textField(obj, "legalName"),
                textField(obj, "tradeName"),
                textField(obj, "taxIdentifier"),
                textField(obj, "companyType"),
                textField(obj, "email"),
                textField(obj, "phone"),
                textField(obj, "city"),
                textField(obj, "province")
        ));
    }

    /**
     * Portfolio unificado: customers de la asesoría con flag de
     * vinculación (linkedCompanyId NULL si no está vinculado) y de
     * invitación pendiente. Sustituye a listManagedClients en la
     * pantalla "Mis clientes" para fusionar cartera + vínculos.
     */
    public List<com.benjagest.ui.model.CustomerPortfolioEntry> listAdvisoryPortfolio()
            throws IOException, InterruptedException {
        // sendAsOwner: el polling necesita ejecutarse como la asesoría
        // incluso cuando la UI está actuando en nombre de un cliente.
        // De lo contrario el cliente recibe el X-Company-Id, el endpoint
        // /api/advisory devuelve 403 (sin módulo advisory) y el polling
        // deja de detectar desvinculaciones del propio cliente activo.
        HttpResponse<String> r = sendAsOwner(req(baseUrl + "/advisory/clients/portfolio").GET());
        return parseObjects(r.body(), "legalName", obj -> new com.benjagest.ui.model.CustomerPortfolioEntry(
                textField(obj, "customerId"),
                textField(obj, "legalName"),
                textField(obj, "tradeName"),
                textField(obj, "taxIdentifier"),
                textField(obj, "customerType"),
                textField(obj, "email"),
                textField(obj, "phone"),
                textField(obj, "city"),
                textField(obj, "linkedCompanyId"),
                boolField(obj, "hasPendingInvitation"),
                boolField(obj, "wasUnlinked"),
                boolField(obj, "fullyLinked")
        ));
    }

    /**
     * Inicia la gestión contable de un cliente NO vinculado. Devuelve el
     * id de la shadow company creada (o existente) para que la UI haga
     * acting-for y entre como ese cliente.
     */
    public String startClientManagement(String customerId)
            throws IOException, InterruptedException {
        HttpResponse<String> r = sendAsOwner(
                req(baseUrl + "/advisory/clients/" + customerId + "/start-management")
                        .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return textField(r.body(), "companyId");
    }

    // ============================================================
    // EQUIPO / Reparto de clientes — Slice 5C
    //   /api/advisory/team/assignments
    // ============================================================

    // ============================================================
    // L4-6/L4-7 — Colaboraciones inter-asesoría
    //   /api/advisory/collaborations/*
    // ============================================================

    public List<com.benjagest.ui.model.CollabEntry> listOutgoingCollabs()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl
                + "/advisory/collaborations/outgoing").GET());
        return parseCollabs(r);
    }

    public List<com.benjagest.ui.model.CollabEntry> listIncomingCollabs()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl
                + "/advisory/collaborations/incoming").GET());
        return parseCollabs(r);
    }

    public List<com.benjagest.ui.model.CollabEntry> listActiveCollabs()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl
                + "/advisory/collaborations/active").GET());
        return parseCollabs(r);
    }

    public com.benjagest.ui.model.CollabEntry inviteCollab(String email, String notes)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        b.append("\"email\":").append(jsonString(email));
        if (notes != null && !notes.isBlank()) {
            b.append(",\"notes\":").append(jsonString(notes));
        }
        b.append('}');
        HttpResponse<String> r = send(req(baseUrl + "/advisory/collaborations")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(b.toString()))
                .header("Content-Type", "application/json"));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return mapCollab(r.body());
    }

    public void acceptCollab(String id) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl
                + "/advisory/collaborations/" + id + "/accept")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    public void rejectCollab(String id) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl
                + "/advisory/collaborations/" + id + "/reject")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    public void revokeCollab(String id) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl
                + "/advisory/collaborations/" + id).DELETE());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    private List<com.benjagest.ui.model.CollabEntry> parseCollabs(HttpResponse<String> r)
            throws IOException {
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return parseObjects(r.body(), "invitedEmail", this::mapCollab);
    }

    private com.benjagest.ui.model.CollabEntry mapCollab(String obj) {
        return new com.benjagest.ui.model.CollabEntry(
                textField(obj, "id"),
                textField(obj, "advisoryCompanyId"),
                textField(obj, "partnerAdvisoryId"),
                textField(obj, "invitedEmail"),
                textField(obj, "status"),
                parseInstantOrNull(textField(obj, "invitedAt")),
                parseInstantOrNull(textField(obj, "acceptedAt")),
                parseInstantOrNull(textField(obj, "revokedAt")),
                textField(obj, "notes")
        );
    }

    private static java.time.Instant parseInstantOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return java.time.Instant.parse(s); }
        catch (Exception ex) { return null; }
    }

    /**
     * Slice 5C — Lista miembros activos de la asesoría logueada
     * (employees del equipo). Solo OWNER. Backend: 403 si no es OWNER.
     */
    public List<com.benjagest.ui.model.TeamMember> listTeamMembers()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl
                + "/advisory/team/assignments/members").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return parseObjects(r.body(), "userId", obj -> new com.benjagest.ui.model.TeamMember(
                textField(obj, "userId"),
                textField(obj, "email"),
                textField(obj, "displayName"),
                textField(obj, "roleName"),
                textField(obj, "globalRole"),
                boolField(obj, "active")
        ));
    }

    public List<com.benjagest.ui.model.TeamAssignment> listTeamAssignmentsWithModules()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl
                + "/advisory/team/assignments/with-modules").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        // El backend devuelve [{"assignment": {...}, "moduleSlugs": [...]}]
        return parseObjects(r.body(), "moduleSlugs", obj -> {
            String aBlock = extractJsonObject(obj, "assignment");
            if (aBlock == null) return null;
            List<String> mods = extractJsonStringArray(obj, "moduleSlugs");
            return new com.benjagest.ui.model.TeamAssignment(
                    textField(aBlock, "id"),
                    textField(aBlock, "advisoryCompanyId"),
                    textField(aBlock, "employeeUserId"),
                    textField(aBlock, "clientCompanyId"),
                    textField(aBlock, "roleInClient"),
                    boolField(aBlock, "active"),
                    textField(aBlock, "delegatedToUserId"),
                    parseLocalDate(textField(aBlock, "delegatedFrom")),
                    parseLocalDate(textField(aBlock, "delegatedUntil")),
                    textField(aBlock, "notes"),
                    mods == null ? List.of() : mods);
        });
    }

    /**
     * Slice 5C — Asignar en lote. {@code clientIds} marcados con
     * checkbox + {@code moduleSlugs} elegidos en el dialog.
     */
    public String bulkAssignClients(String employeeUserId,
                                     List<String> clientIds,
                                     String roleInClient,
                                     List<String> moduleSlugs,
                                     String notes)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        b.append("\"employeeUserId\":").append(jsonString(employeeUserId));
        b.append(",\"clientCompanyIds\":").append(jsonArrayOfStrings(clientIds));
        if (roleInClient != null && !roleInClient.isBlank()) {
            b.append(",\"roleInClient\":").append(jsonString(roleInClient));
        }
        b.append(",\"moduleSlugs\":").append(jsonArrayOfStrings(moduleSlugs));
        if (notes != null && !notes.isBlank()) {
            b.append(",\"notes\":").append(jsonString(notes));
        }
        b.append('}');
        HttpResponse<String> r = send(req(baseUrl
                + "/advisory/team/assignments/bulk")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(b.toString()))
                .header("Content-Type", "application/json"));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    /**
     * Slice 5E — Delegación temporal de una asignación. {@code toUserId}
     * y {@code from}/{@code until} obligatorios al crear o ampliar la
     * delegación; para CANCELARLA pasar {@code toUserId=null} (el backend
     * borra los tres campos).
     */
    public void delegateAssignment(String assignmentId,
                                    String toUserId,
                                    java.time.LocalDate from,
                                    java.time.LocalDate until)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        // toUserId null/blank = cancelación de delegación
        if (toUserId == null || toUserId.isBlank()) {
            b.append("\"toUserId\":null");
        } else {
            b.append("\"toUserId\":").append(jsonString(toUserId));
            b.append(",\"from\":").append(jsonString(from == null ? null : from.toString()));
            b.append(",\"until\":").append(jsonString(until == null ? null : until.toString()));
        }
        b.append('}');
        HttpResponse<String> r = send(req(baseUrl
                + "/advisory/team/assignments/" + assignmentId + "/delegate")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(b.toString()))
                .header("Content-Type", "application/json"));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    public void deleteTeamAssignment(String id)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl
                + "/advisory/team/assignments/" + id).DELETE());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    /**
     * Slice 5C — Módulos visibles para el user actual en un cliente.
     * Devuelve ["*"] si la lista es abierta (todos los módulos).
     */
    public List<String> myModulesInClient(String clientId)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl
                + "/advisory/team/assignments/mine/modules/" + clientId).GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            return List.of("*"); // fallback seguro: ve todo
        }
        List<String> mods = extractJsonStringArray(r.body(), "moduleSlugs");
        return mods == null ? List.of("*") : mods;
    }

    // ----- helpers JSON minimalistas para Slice 5C -----

    private static String jsonString(String raw) {
        if (raw == null) return "null";
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String jsonArrayOfStrings(List<String> items) {
        if (items == null || items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonString(items.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    /** Extrae el bloque JSON anidado bajo {@code "key": {...}}. */
    private static String extractJsonObject(String json, String key) {
        if (json == null) return null;
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int start = json.indexOf('{', idx);
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return null;
    }

    /** Extrae array de strings bajo {@code "key": ["a","b"]}. */
    private static List<String> extractJsonStringArray(String json, String key) {
        if (json == null) return null;
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int start = json.indexOf('[', idx);
        if (start < 0) return null;
        int end = json.indexOf(']', start);
        if (end < 0) return null;
        String body = json.substring(start + 1, end);
        if (body.isBlank()) return List.of();
        List<String> out = new java.util.ArrayList<>();
        for (String tok : body.split(",")) {
            String t = tok.trim();
            if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
                out.add(t.substring(1, t.length() - 1));
            }
        }
        return out;
    }

    private static java.time.LocalDate parseLocalDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return java.time.LocalDate.parse(s); }
        catch (Exception ex) { return null; }
    }

    // ============================================================
    // MODELOS AEAT (/api/tax)
    // ============================================================

    public List<TaxModelEntry> listTaxModels() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/tax/models").GET());
        return parseObjects(r.body(), "periodicity", obj -> new TaxModelEntry(
                textField(obj, "code"),
                textField(obj, "name"),
                textField(obj, "description"),
                textField(obj, "periodicity"),
                textField(obj, "infoUrl"),
                boolField(obj, "active")
        ));
    }

    public List<TaxFilingEntry> listFilings(Integer year, String status, String modelCode)
            throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl + "/tax/filings?");
        if (year != null) url.append("year=").append(year).append("&");
        if (status != null && !status.isBlank()) url.append("status=").append(status).append("&");
        if (modelCode != null && !modelCode.isBlank()) url.append("modelCode=").append(modelCode);
        HttpResponse<String> r = send(req(url.toString()).GET());
        return parseObjects(r.body(), "taxModelCode", this::mapFiling);
    }

    public TaxFilingEntry createFiling(String modelCode, int year, Integer quarter, Integer month,
                                        String status, String dataJson, BigDecimal totalAmount,
                                        String csvAeat, String notes)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/tax/filings")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        filingBody(modelCode, year, quarter, month, status, dataJson, totalAmount, csvAeat, notes))));
        return mapFiling(r.body());
    }

    public TaxFilingEntry updateFiling(String id, String modelCode, int year, Integer quarter, Integer month,
                                        String status, String dataJson, BigDecimal totalAmount,
                                        String csvAeat, String notes)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/tax/filings/" + id)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        filingBody(modelCode, year, quarter, month, status, dataJson, totalAmount, csvAeat, notes))));
        return mapFiling(r.body());
    }

    public void deleteFiling(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/tax/filings/" + id).DELETE());
    }

    public List<TaxDueDateEntry> calendar(int year) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/tax/calendar?year=" + year).GET());
        return parseObjects(r.body(), "taxModelCode", obj -> new TaxDueDateEntry(
                textField(obj, "taxModelCode"),
                textField(obj, "taxModelName"),
                intFieldOrZero(obj, "periodYear"),
                intFieldOrNull(obj, "periodQuarter"),
                intFieldOrNull(obj, "periodMonth"),
                textField(obj, "deadlineAt")
        ));
    }

    private String filingBody(String modelCode, int year, Integer quarter, Integer month,
                               String status, String dataJson, BigDecimal totalAmount,
                               String csvAeat, String notes) {
        StringBuilder b = new StringBuilder("{");
        b.append(field("taxModelCode", modelCode)).append(",");
        b.append("\"periodYear\":").append(year);
        if (quarter != null) b.append(",\"periodQuarter\":").append(quarter);
        if (month != null) b.append(",\"periodMonth\":").append(month);
        if (status != null) b.append(",").append(field("status", status));
        if (dataJson != null && !dataJson.isBlank()) {
            // data va como JSON crudo — el backend lo persiste tal cual
            b.append(",\"data\":").append(quoteForJson(dataJson));
        }
        if (totalAmount != null) b.append(",").append(bigDecField("totalAmount", totalAmount));
        if (csvAeat != null) b.append(",").append(field("csvAeat", csvAeat));
        if (notes != null) b.append(",").append(field("notes", notes));
        b.append("}");
        return b.toString();
    }

    /**
     * Envia el JSON de casillas como STRING dentro del body de la
     * declaracion. El backend lo persiste tal cual en la columna `data`
     * (tipo JSON en MariaDB). Lo enmarcamos entre comillas y escapamos.
     */
    private String quoteForJson(String raw) {
        return "\"" + escape(raw) + "\"";
    }

    private TaxFilingEntry mapFiling(String obj) {
        return new TaxFilingEntry(
                textField(obj, "id"),
                textField(obj, "taxModelCode"),
                intFieldOrZero(obj, "periodYear"),
                intFieldOrNull(obj, "periodQuarter"),
                intFieldOrNull(obj, "periodMonth"),
                textField(obj, "status"),
                bigDecField(obj, "totalAmount"),
                textField(obj, "deadlineAt"),
                textField(obj, "presentedAt"),
                textField(obj, "csvAeat"),
                textField(obj, "notes"),
                textField(obj, "data")
        );
    }

    // ============================================================
    // INFRA
    // ============================================================

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

    /**
     * Variante de {@link #send} que ignora el override actingFor y manda
     * el X-Company-Id real de la asesoría. Usar para llamadas que deben
     * ejecutarse "como la asesoría" incluso si la UI está actuando en
     * nombre de un cliente (polling de cartera, invitaciones).
     */
    private HttpResponse<String> sendAsOwner(HttpRequest.Builder builder) throws IOException, InterruptedException {
        AuthSession.get().authorizeAsOwner(builder);
        HttpResponse<String> r = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r;
    }

    private interface Mapper<T> { T map(String obj); }

    /**
     * Itera por objetos JSON de primer nivel (sin anidamientos) en el
     * body. `discriminator` debe ser un campo que SIEMPRE aparece en
     * los objetos para distinguirlos del envoltorio si lo hay.
     */
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

    private String bigDecField(String name, BigDecimal value) {
        return "\"" + name + "\":" + (value == null ? "null" : value.toPlainString());
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private String textField(String json, String field) {
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|\"((?:\\\\.|[^\"])*)\")");
        Matcher m = p.matcher(json);
        if (!m.find() || "null".equals(m.group(1))) return "";
        return m.group(2).replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }

    private boolean boolField(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() && "true".equals(m.group(1));
    }

    private Integer intFieldOrNull(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|(-?\\d+))").matcher(json);
        if (!m.find() || "null".equals(m.group(1))) return null;
        try { return Integer.parseInt(m.group(2)); } catch (NumberFormatException e) { return null; }
    }

    private int intFieldOrZero(String json, String field) {
        Integer v = intFieldOrNull(json, field);
        return v == null ? 0 : v;
    }

    private BigDecimal bigDecField(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|(-?\\d+(?:\\.\\d+)?))").matcher(json);
        if (!m.find() || "null".equals(m.group(1))) return null;
        try { return new BigDecimal(m.group(2)); } catch (NumberFormatException e) { return null; }
    }

    private static String apiBaseUrl() {
        return System.getenv().getOrDefault("BENJAGEST_API_BASE_URL", DEFAULT_API_BASE_URL);
    }
}
