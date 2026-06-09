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

    // ============================================================
    // CTR-2 — Catálogos de contrato (solo lectura)
    //   /api/contracts/catalog/*
    // ============================================================

    public List<com.benjagest.ui.model.ContractCatalog.SepeType> listSepeContractTypes()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/contracts/catalog/sepe").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        // Usamos splitTopLevelObjects en lugar de parseObjects: aunque
        // SepeType no lleva arrays anidados, sus descripciones pueden
        // contener caracteres que rompan el regex `[^{}]*`. Es robusto
        // por defecto.
        List<com.benjagest.ui.model.ContractCatalog.SepeType> out = new ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.ContractCatalog.SepeType(
                    textField(obj, "code"),
                    textField(obj, "family"),
                    textField(obj, "workingDay"),
                    textField(obj, "description"),
                    textField(obj, "legalBasis"),
                    boolField(obj, "unifiedModel2022"),
                    boolField(obj, "active")
            ));
        }
        return out;
    }

    public List<com.benjagest.ui.model.ContractCatalog.Agreement> listCollectiveAgreements()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/contracts/catalog/agreements").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        // NO se puede usar parseObjects() aquí: su regex `[^{}]*` solo
        // matchea objetos planos sin llaves anidadas — pero cada convenio
        // lleva categories:[{...},...] dentro y el regex falla devolviendo
        // lista vacía. Usamos un parser por balance de llaves que
        // funciona con anidamiento de cualquier nivel.
        List<com.benjagest.ui.model.ContractCatalog.Agreement> out = new ArrayList<>();
        for (String agreementObj : splitTopLevelObjects(r.body())) {
            List<com.benjagest.ui.model.ContractCatalog.Category> cats =
                    parseCategoriesFromAgreement(agreementObj);
            out.add(new com.benjagest.ui.model.ContractCatalog.Agreement(
                    textField(agreementObj, "id"),
                    textField(agreementObj, "code"),
                    textField(agreementObj, "name"),
                    textField(agreementObj, "scope"),
                    textField(agreementObj, "boeReference"),
                    cats,
                    boolField(agreementObj, "active")
            ));
        }
        return out;
    }

    /**
     * Divide un array JSON {@code [{...},{...},...]} en los objetos de
     * primer nivel. A diferencia de {@link #parseObjects}, balancea
     * llaves y respeta strings con escapes, así que funciona aunque
     * los objetos lleven arrays / sub-objetos anidados (caso de los
     * convenios con sus categorías).
     */
    private List<String> splitTopLevelObjects(String json) {
        List<String> out = new ArrayList<>();
        if (json == null) return out;
        int depth = 0, objStart = -1;
        boolean inString = false, escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    out.add(json.substring(objStart, i + 1));
                    objStart = -1;
                }
            }
        }
        return out;
    }

    public List<com.benjagest.ui.model.ContractCatalog.ClauseTemplate> listClauseTemplates()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/contracts/catalog/clauses").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        // splitTopLevelObjects es OBLIGATORIO aquí: el campo `body` lleva
        // texto legal largo con saltos de línea reales y caracteres
        // especiales que romperían el regex `[^{}]*` de parseObjects.
        List<com.benjagest.ui.model.ContractCatalog.ClauseTemplate> out = new ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.ContractCatalog.ClauseTemplate(
                    textField(obj, "id"),
                    textField(obj, "code"),
                    textField(obj, "title"),
                    textField(obj, "body"),
                    textField(obj, "category"),
                    textField(obj, "legalBasis"),
                    boolField(obj, "isBuiltIn"),
                    textField(obj, "companyId"),
                    boolField(obj, "active")
            ));
        }
        return out;
    }

    /**
     * Extrae el array "categories":[...] anidado dentro del JSON de un
     * convenio y devuelve cada Category. Parser minimalista para no
     * añadir Jackson en la UI.
     */
    private List<com.benjagest.ui.model.ContractCatalog.Category> parseCategoriesFromAgreement(
            String agreementJson) {
        List<com.benjagest.ui.model.ContractCatalog.Category> out = new java.util.ArrayList<>();
        int idx = agreementJson.indexOf("\"categories\"");
        if (idx < 0) return out;
        int bracketStart = agreementJson.indexOf('[', idx);
        if (bracketStart < 0) return out;
        // Recorrer el array balanceando {} para extraer cada objeto.
        int depth = 0, objStart = -1;
        for (int i = bracketStart; i < agreementJson.length(); i++) {
            char c = agreementJson.charAt(i);
            if (c == '[') { /* outer bracket */ }
            else if (c == ']' && depth == 0) break;
            else if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String obj = agreementJson.substring(objStart, i + 1);
                    out.add(new com.benjagest.ui.model.ContractCatalog.Category(
                            textField(obj, "id"),
                            textField(obj, "collectiveAgreementId"),
                            textField(obj, "groupCode"),
                            textField(obj, "categoryName"),
                            decimalFieldOrNull(obj, "minAnnualSalary"),
                            decimalFieldOrNull(obj, "minMonthlySalary"),
                            decimalFieldOrNull(obj, "maxWeeklyHours"),
                            intFieldOrNull(obj, "probationDays"),
                            intFieldOrNull(obj, "yearPublished"),
                            boolField(obj, "active")
                    ));
                    objStart = -1;
                }
            }
        }
        return out;
    }

    private java.math.BigDecimal decimalFieldOrNull(String obj, String field) {
        String raw = textField(obj, field);
        if (raw == null || raw.isBlank()) {
            // Puede venir sin comillas (número JSON). Buscar directo.
            int idx = obj.indexOf("\"" + field + "\"");
            if (idx < 0) return null;
            int colon = obj.indexOf(':', idx);
            if (colon < 0) return null;
            // Extraer hasta la siguiente coma o llave.
            StringBuilder sb = new StringBuilder();
            for (int i = colon + 1; i < obj.length(); i++) {
                char c = obj.charAt(i);
                if (c == ',' || c == '}' || c == ']') break;
                if (!Character.isWhitespace(c)) sb.append(c);
            }
            String num = sb.toString();
            if (num.isBlank() || "null".equals(num)) return null;
            try { return new java.math.BigDecimal(num); }
            catch (NumberFormatException ex) { return null; }
        }
        try { return new java.math.BigDecimal(raw); }
        catch (NumberFormatException ex) { return null; }
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
        // El backend devuelve [{"assignment": {...}, "moduleSlugs": [...]}].
        // NO se puede usar parseObjects: su regex `[^{}]*` solo matchea
        // objetos planos. Cada item tiene un sub-objeto `assignment`
        // anidado que rompe el regex y devuelve lista vacía (mismo bug
        // que con los convenios del wizard de contratos). Usamos
        // splitTopLevelObjects que balancea llaves correctamente.
        List<com.benjagest.ui.model.TeamAssignment> out = new ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            String aBlock = extractJsonObject(obj, "assignment");
            if (aBlock == null) continue;
            List<String> mods = extractJsonStringArray(obj, "moduleSlugs");
            out.add(new com.benjagest.ui.model.TeamAssignment(
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
                    mods == null ? List.of() : mods));
        }
        return out;
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
     * Variante de {@link #send} para descargas binarias (PDF, XML…).
     * Usa byte array body handler en lugar de string. Necesario para
     * CTR-4/CTR-5 (descarga PDF + XML contrato).
     */
    private HttpResponse<byte[]> sendBytes(HttpRequest.Builder builder) throws IOException, InterruptedException {
        AuthSession.get().authorize(builder);
        HttpResponse<byte[]> r = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
        return r;
    }

    /**
     * Variante de {@link #send} para respuestas sin cuerpo (DELETE,
     * POST 204, etc.). Descarta el body para no consumirlo.
     */
    private HttpResponse<Void> sendDiscarding(HttpRequest.Builder builder) throws IOException, InterruptedException {
        AuthSession.get().authorize(builder);
        HttpResponse<Void> r = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
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
     * Itera por objetos JSON de primer nivel en el body.
     *
     * <p>El {@code discriminator} debe ser un campo que SIEMPRE aparezca
     * en los objetos para distinguirlos del envoltorio si lo hay.
     *
     * <p>POLISH 2026-06-09: el implementación previa usaba el regex
     * {@code \{[^{}]*\}} que solo matchea objetos planos y fallaba
     * silenciosamente con objetos que tuvieran sub-objetos. Esto mordió
     * tres veces (convenios CTR-2, asignaciones EMP-SCOPE, assignment
     * matrix). Ahora delegamos en {@link #splitTopLevelObjects} que
     * balancea llaves y filtra los que contengan el discriminator —
     * mismo comportamiento para objetos planos pero seguro con anidados.
     */
    private <T> List<T> parseObjects(String json, String discriminator, Mapper<T> mapper) {
        List<T> out = new ArrayList<>();
        String discToken = "\"" + discriminator + "\"";
        for (String obj : splitTopLevelObjects(json)) {
            if (obj.contains(discToken)) {
                out.add(mapper.map(obj));
            }
        }
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

    // ============================================================
    // CTR-3 — Plantillas de contrato
    //   /api/contracts/templates
    // ============================================================

    public List<com.benjagest.ui.model.ContractTemplate> listContractTemplates()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/contracts/templates").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        List<com.benjagest.ui.model.ContractTemplate> out = new ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(mapTemplate(obj));
        }
        return out;
    }

    public com.benjagest.ui.model.ContractTemplate createContractTemplate(
            com.benjagest.ui.model.ContractTemplate tpl) throws IOException, InterruptedException {
        String json = templateToJson(tpl);
        HttpResponse<String> r = send(req(baseUrl + "/contracts/templates")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json"));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return mapTemplate(r.body());
    }

    public void deleteContractTemplate(String id) throws IOException, InterruptedException {
        sendDiscarding(req(baseUrl + "/contracts/templates/" + id).DELETE());
    }

    private com.benjagest.ui.model.ContractTemplate mapTemplate(String obj) {
        return new com.benjagest.ui.model.ContractTemplate(
                textField(obj, "id"),
                textField(obj, "name"),
                textField(obj, "description"),
                textField(obj, "sepeContractCode"),
                textField(obj, "contractType"),
                textField(obj, "collectiveAgreementId"),
                textField(obj, "professionalCategoryId"),
                textField(obj, "professionalGroup"),
                bigDecField(obj, "weeklyHours"),
                bigDecField(obj, "grossSalary"),
                intFieldOrNull(obj, "annualBonuses"),
                intFieldOrNull(obj, "vacationDays"),
                bigDecField(obj, "irpfPercent"),
                intFieldOrNull(obj, "probationDays"),
                textField(obj, "workplaceAddress"),
                textField(obj, "clauseCodes"),
                textField(obj, "pdfModel"),
                boolField(obj, "isBuiltIn"),
                boolField(obj, "active")
        );
    }

    private String templateToJson(com.benjagest.ui.model.ContractTemplate t) {
        StringBuilder sb = new StringBuilder("{");
        appendStr(sb, "name", t.name());
        appendStr(sb, "description", t.description());
        appendStr(sb, "sepeContractCode", t.sepeContractCode());
        appendStr(sb, "contractType", t.contractType());
        appendStr(sb, "collectiveAgreementId", t.collectiveAgreementId());
        appendStr(sb, "professionalCategoryId", t.professionalCategoryId());
        appendStr(sb, "professionalGroup", t.professionalGroup());
        appendDec(sb, "weeklyHours", t.weeklyHours());
        appendDec(sb, "grossSalary", t.grossSalary());
        appendInt(sb, "annualBonuses", t.annualBonuses());
        appendInt(sb, "vacationDays", t.vacationDays());
        appendDec(sb, "irpfPercent", t.irpfPercent());
        appendInt(sb, "probationDays", t.probationDays());
        appendStr(sb, "workplaceAddress", t.workplaceAddress());
        appendStr(sb, "clauseCodes", t.clauseCodes());
        appendStr(sb, "pdfModel", t.pdfModel());
        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        sb.append('}');
        return sb.toString();
    }

    private static void appendStr(StringBuilder sb, String k, String v) {
        if (v == null) return;
        sb.append('"').append(k).append("\":\"").append(escJ(v)).append("\",");
    }
    private static void appendDec(StringBuilder sb, String k, BigDecimal v) {
        if (v == null) return;
        sb.append('"').append(k).append("\":").append(v.toPlainString()).append(',');
    }
    private static void appendInt(StringBuilder sb, String k, Integer v) {
        if (v == null) return;
        sb.append('"').append(k).append("\":").append(v).append(',');
    }
    private static String escJ(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    // ============================================================
    // CTR-6 — Alertas de vencimiento
    //   /api/contracts/alerts
    // ============================================================

    public List<com.benjagest.ui.model.ContractAlert> listContractAlerts()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/contracts/alerts").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        List<com.benjagest.ui.model.ContractAlert> out = new ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.ContractAlert(
                    textField(obj, "id"),
                    textField(obj, "kind"),
                    textField(obj, "title"),
                    textField(obj, "message"),
                    textField(obj, "severity"),
                    textField(obj, "firesAt"),
                    boolField(obj, "seen"),
                    textField(obj, "contractId"),
                    textField(obj, "employeeId"),
                    textField(obj, "employeeName")
            ));
        }
        return out;
    }

    public void dismissContractAlert(String id) throws IOException, InterruptedException {
        sendDiscarding(req(baseUrl + "/contracts/alerts/" + id + "/dismiss")
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    public void rescanContractAlerts() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/contracts/alerts/rescan")
                .POST(HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    // ============================================================
    // CTR-7 — Anexos (cláusulas vinculadas + free clauses)
    //   /api/contracts/{id}/annexes
    // ============================================================

    public void linkContractAnnex(String contractId, String clauseTemplateId)
            throws IOException, InterruptedException {
        String json = "{\"clauseTemplateId\":\"" + escJ(clauseTemplateId) + "\"}";
        HttpResponse<String> r = send(req(baseUrl + "/contracts/" + contractId + "/annexes/linked")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json"));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    public void addContractFreeClause(String contractId, String title, String body)
            throws IOException, InterruptedException {
        String json = "{\"title\":\"" + escJ(title) + "\",\"body\":\"" + escJ(body) + "\"}";
        HttpResponse<String> r = send(req(baseUrl + "/contracts/" + contractId + "/annexes/free")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json"));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    // ============================================================
    // CTR-4 / CTR-5 — Descarga PDF + XML
    //   /api/contracts/{id}/{pdf|xml}
    // ============================================================

    /**
     * CTR-7 — Crea (POST) o actualiza (PUT) una cláusula custom del tenant.
     * Solo cláusulas con is_built_in=FALSE son editables; el backend
     * rechaza intentos de tocar built-in.
     */
    public void upsertClauseTemplate(String existingId, String json)
            throws IOException, InterruptedException {
        HttpRequest.Builder b;
        if (existingId == null) {
            b = req(baseUrl + "/contracts/catalog/clauses")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
        } else {
            b = req(baseUrl + "/contracts/catalog/clauses/" + existingId)
                    .PUT(HttpRequest.BodyPublishers.ofString(json));
        }
        b.header("Content-Type", "application/json");
        HttpResponse<String> r = send(b);
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    public byte[] downloadContractPdf(String contractId, String model)
            throws IOException, InterruptedException {
        String url = baseUrl + "/contracts/" + contractId + "/pdf"
                + (model == null ? "" : "?model=" + model);
        return sendBytes(req(url).GET()).body();
    }

    public byte[] downloadContractXml(String contractId)
            throws IOException, InterruptedException {
        return sendBytes(req(baseUrl + "/contracts/" + contractId + "/xml").GET()).body();
    }

    // ============================================================
    // EMP-SCOPED-UI — Scope del usuario en la asesoría
    //   GET /api/team/me/scope
    // ============================================================

    /**
     * Carga el scope del usuario logueado: si tiene acceso completo
     * (OWNER/ADMIN) o, si no, qué clientes ve y qué módulos puede
     * tocar (unión de assignment_modules de sus client_assignments).
     * El UI guarda el resultado en {@link AuthSession#setUserScope}.
     */
    public AuthSession.UserScope loadMyScope() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/team/me/scope").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        String body = r.body();
        boolean full = boolField(body, "isFullAccess");
        List<String> customers = parseStringArray(body, "customerIds");
        List<String> modules = parseStringArray(body, "moduleSlugs");
        return new AuthSession.UserScope(full, customers, modules);
    }

    /**
     * Parser minimalista para arrays JSON de strings:
     *   "customerIds":["uuid-1","uuid-2"]
     * Devuelve lista vacía si el campo no aparece o el array está vacío.
     */
    private List<String> parseStringArray(String json, String field) {
        List<String> out = new ArrayList<>();
        if (json == null) return out;
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return out;
        int bracketStart = json.indexOf('[', idx);
        if (bracketStart < 0) return out;
        int bracketEnd = json.indexOf(']', bracketStart);
        if (bracketEnd < 0) return out;
        String inner = json.substring(bracketStart + 1, bracketEnd);
        // Tokenizar por comas respetando comillas con escape.
        boolean inString = false, escape = false;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (escape) { buf.append(c); escape = false; continue; }
            if (c == '\\') { buf.append(c); escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (!inString && c == ',') {
                String tok = buf.toString().trim();
                if (!tok.isEmpty()) out.add(tok);
                buf.setLength(0);
                continue;
            }
            if (inString) buf.append(c);
        }
        String tail = buf.toString().trim();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }

    // ============================================================
    // L3-4 — CALENDARIO LABORAL (/api/labor/work-calendars)
    // ============================================================

    public List<com.benjagest.ui.model.WorkCalendarEntry> listWorkCalendars()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/work-calendars").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        List<com.benjagest.ui.model.WorkCalendarEntry> out = new ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(mapWorkCalendar(obj));
        }
        return out;
    }

    public List<com.benjagest.ui.model.HolidayEntry> listHolidaysFor(String calendarId)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl
                + "/labor/work-calendars/" + calendarId + "/holidays").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        List<com.benjagest.ui.model.HolidayEntry> out = new ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(mapHoliday(obj));
        }
        return out;
    }

    /**
     * L3-3 — Crea calendario y lo siembra desde el catálogo BOE/CCAA.
     * El backend solo soporta year=2026 por ahora; otros años → 501.
     */
    public com.benjagest.ui.model.WorkCalendarEntry bootstrapWorkCalendar(
            int year, String regionCcaa, String regionMunicipality, String name)
            throws IOException, InterruptedException {
        String body = "{"
                + "\"year\":" + year + ","
                + field("regionCcaa", regionCcaa) + ","
                + field("regionMunicipality", regionMunicipality) + ","
                + field("name", name)
                + "}";
        HttpResponse<String> r = send(req(baseUrl + "/labor/work-calendars/bootstrap")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        // BootstrapResult tiene 'calendar' (objeto) y 'holidays' (array).
        String calObj = extractJsonObject(r.body(), "calendar");
        if (calObj == null) {
            throw new IOException("Bootstrap sin calendar en respuesta");
        }
        com.benjagest.ui.model.WorkCalendarEntry cal = mapWorkCalendar(calObj);
        // No mapeamos los festivos aquí porque la UI los recarga
        // explícitamente con listHolidaysFor() tras el refresh — más
        // simple y evita parseo de array anidado.
        return cal;
    }

    public void deleteWorkCalendar(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/labor/work-calendars/" + id).DELETE());
    }

    public com.benjagest.ui.model.HolidayEntry addHoliday(
            String calendarId, java.time.LocalDate date, String name,
            String scope, boolean isPaid, String notes)
            throws IOException, InterruptedException {
        String body = "{"
                + "\"holidayDate\":\"" + date.toString() + "\","
                + field("name", name) + ","
                + field("scope", scope) + ","
                + "\"isPaid\":" + isPaid + ","
                + field("notes", notes)
                + "}";
        HttpResponse<String> r = send(req(baseUrl
                + "/labor/work-calendars/" + calendarId + "/holidays")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return mapHoliday(r.body());
    }

    public void removeHoliday(String calendarId, String holidayId)
            throws IOException, InterruptedException {
        send(req(baseUrl
                + "/labor/work-calendars/" + calendarId + "/holidays/" + holidayId)
                .DELETE());
    }

    /**
     * CAL-IMPORT — extrae festivos de un PDF de calendario laboral.
     * Multipart artesanal porque JDK HttpClient no incluye builder
     * multipart (mismo patrón que PdfImportApiClient).
     *
     * <p>Devuelve un record plano con year + lista de detectados
     * que la UI usará para popular el modal side-by-side.
     */
    public com.benjagest.ui.model.HolidayPdfPreview extractHolidaysFromPdf(java.io.File pdfFile)
            throws IOException, InterruptedException {
        String boundary = "----benjagest-" + java.util.UUID.randomUUID();
        byte[] body = buildMultipartBody(pdfFile, boundary);
        HttpResponse<String> r = send(req(baseUrl + "/labor/work-calendars/extract-pdf")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        String json = r.body();
        int year = intField(json, "year");
        List<com.benjagest.ui.model.HolidayPdfPreview.DetectedHoliday> holidays = new ArrayList<>();
        // El array está dentro de "holidays":[{...},{...}]
        int arrStart = json.indexOf("\"holidays\"");
        if (arrStart >= 0) {
            int bracketStart = json.indexOf('[', arrStart);
            int bracketEnd = matchingBracket(json, bracketStart);
            if (bracketEnd > bracketStart) {
                String inner = json.substring(bracketStart + 1, bracketEnd);
                for (String obj : splitTopLevelObjects(inner)) {
                    holidays.add(new com.benjagest.ui.model.HolidayPdfPreview.DetectedHoliday(
                            parseLocalDate(textField(obj, "date")),
                            textField(obj, "name"),
                            textField(obj, "scope"),
                            textField(obj, "confidence"),
                            textField(obj, "rawSourceLine")));
                }
            }
        }
        return new com.benjagest.ui.model.HolidayPdfPreview(year, holidays);
    }

    /** Encuentra el ']' que cierra el '[' en startBracket. -1 si no. */
    private int matchingBracket(String s, int startBracket) {
        if (startBracket < 0 || startBracket >= s.length()
                || s.charAt(startBracket) != '[') return -1;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = startBracket; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private byte[] buildMultipartBody(java.io.File file, String boundary) throws IOException {
        String prefix = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n";
        String suffix = "\r\n--" + boundary + "--\r\n";
        byte[] pre = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] content = java.nio.file.Files.readAllBytes(file.toPath());
        byte[] suf = suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] body = new byte[pre.length + content.length + suf.length];
        System.arraycopy(pre, 0, body, 0, pre.length);
        System.arraycopy(content, 0, body, pre.length, content.length);
        System.arraycopy(suf, 0, body, pre.length + content.length, suf.length);
        return body;
    }

    /**
     * Reemplaza todos los festivos del calendario por la lista dada.
     * Usado por el modal CAL-IMPORT tras validar los detectados.
     */
    public void replaceHolidaysInCalendar(String calendarId,
                                            List<com.benjagest.ui.model.HolidayEntry> holidays)
            throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < holidays.size(); i++) {
            if (i > 0) sb.append(",");
            com.benjagest.ui.model.HolidayEntry h = holidays.get(i);
            sb.append("{")
              .append("\"holidayDate\":\"").append(h.holidayDate().toString()).append("\",")
              .append(field("name", h.name())).append(",")
              .append(field("scope", h.scope())).append(",")
              .append("\"isPaid\":").append(h.isPaid()).append(",")
              .append(field("notes", h.notes()))
              .append("}");
        }
        sb.append("]");
        HttpResponse<String> r = send(req(baseUrl
                + "/labor/work-calendars/" + calendarId + "/holidays/replace")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString())));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    private com.benjagest.ui.model.WorkCalendarEntry mapWorkCalendar(String obj) {
        return new com.benjagest.ui.model.WorkCalendarEntry(
                textField(obj, "id"),
                textField(obj, "companyId"),
                intField(obj, "year"),
                textField(obj, "regionCcaa"),
                textField(obj, "regionMunicipality"),
                textField(obj, "name"),
                boolField(obj, "active"),
                parseInstant(textField(obj, "createdAt")),
                parseInstant(textField(obj, "updatedAt")),
                List.of()
        );
    }

    private com.benjagest.ui.model.HolidayEntry mapHoliday(String obj) {
        return new com.benjagest.ui.model.HolidayEntry(
                textField(obj, "id"),
                textField(obj, "workCalendarId"),
                parseLocalDate(textField(obj, "holidayDate")),
                textField(obj, "name"),
                textField(obj, "scope"),
                boolField(obj, "isPaid"),
                textField(obj, "notes"),
                parseInstant(textField(obj, "createdAt"))
        );
    }

    private int intField(String json, String field) {
        if (json == null) return 0;
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return 0;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return 0;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) i++;
        if (start == i) return 0;
        try { return Integer.parseInt(json.substring(start, i)); }
        catch (NumberFormatException ex) { return 0; }
    }

    private java.time.Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return java.time.Instant.parse(s); }
        catch (Exception ex) { return null; }
    }
}
