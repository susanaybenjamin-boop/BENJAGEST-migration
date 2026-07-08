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
    // GESTOR-NAVEGADOR — sesion de certificado (Fase 2)
    //   /api/certificates/browser/{open,close}
    // ============================================================

    /**
     * Importa el .p12 del cliente activo (X-Company-Id) al almacen de
     * Windows para que Chromium lo ofrezca en su dialogo nativo. Devuelve
     * la huella, o null si el cliente no tiene certificado (HTTP 204): en
     * ese caso el gestor abre igual y el usuario usa su almacen del sistema.
     */
    public String openBrowserCertSession() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/certificates/browser/open")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() == 204) {
            return null;
        }
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return textField(r.body(), "thumbprint");
    }

    /** Quita la huella del almacen de Windows al cerrar el gestor. */
    public void closeBrowserCertSession(String thumbprint)
            throws IOException, InterruptedException {
        if (thumbprint == null || thumbprint.isBlank()) {
            return;
        }
        HttpResponse<String> r = send(req(baseUrl + "/certificates/browser/close")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"thumbprint\":" + jsonString(thumbprint) + "}"))
                .header("Content-Type", "application/json"));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
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
                            intFieldOrNull(obj, "ssContributionGroup"),
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
    /**
     * Activa los módulos operativos que falten en el cliente actual (el
     * X-Company-Id de la sesión, ya fijado al entrar al cliente). Evita el
     * error "módulo no activo" al cargar las pestañas operativas. Idempotente.
     */
    public void ensureOperativaModules() throws IOException, InterruptedException {
        send(req(baseUrl + "/settings/modules/ensure-operativa")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
    }

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

    // ---- AEAT-ED-1: modelo 347 (operaciones con terceros) ----

    /** Calcula el 347 del año desde facturas/compras (no persiste): filas por NIF. */
    public java.util.List<com.benjagest.ui.model.Aeat347Row> preview347(int year)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/aeat/extras/347/" + year + "/preview").GET());
        return parse347Rows(r.body());
    }

    /** Parsea las filas del 347 desde el JSON guardado en tax_filings.data. */
    public java.util.List<com.benjagest.ui.model.Aeat347Row> parse347(String json) {
        return parse347Rows(json);
    }

    private java.util.List<com.benjagest.ui.model.Aeat347Row> parse347Rows(String json) {
        java.util.List<com.benjagest.ui.model.Aeat347Row> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        int idx = json.indexOf("\"rows\"");
        String region = idx >= 0 ? json.substring(idx) : json;
        for (String obj : splitTopLevelObjects(region)) {
            if (!obj.contains("\"operationType\"") && !obj.contains("\"nif\"")) continue;
            out.add(new com.benjagest.ui.model.Aeat347Row(
                    textField(obj, "operationType"),
                    textField(obj, "nif"),
                    textField(obj, "name"),
                    numberField(obj, "q1"), numberField(obj, "q2"),
                    numberField(obj, "q3"), numberField(obj, "q4")));
        }
        return out;
    }

    // ---- AEAT-ED-3: modelo 190 (retenciones IRPF) ----

    public java.util.List<com.benjagest.ui.model.Aeat190Row> preview190(int year)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/aeat/extras/190/" + year + "/preview").GET());
        return parse190Rows(r.body());
    }

    public java.util.List<com.benjagest.ui.model.Aeat190Row> parse190(String json) {
        return parse190Rows(json);
    }

    private java.util.List<com.benjagest.ui.model.Aeat190Row> parse190Rows(String json) {
        java.util.List<com.benjagest.ui.model.Aeat190Row> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        int idx = json.indexOf("\"rows\"");
        String region = idx >= 0 ? json.substring(idx) : json;
        for (String obj : splitTopLevelObjects(region)) {
            if (!obj.contains("\"nif\"") && !obj.contains("\"retencion\"")) continue;
            String clave = textField(obj, "clave");
            if (clave.isBlank()) clave = textField(obj, "subclave");
            out.add(new com.benjagest.ui.model.Aeat190Row(
                    clave, textField(obj, "nif"), textField(obj, "name"),
                    numberField(obj, "base"), numberField(obj, "retencion")));
        }
        return out;
    }

    /** Extrae un campo numérico (sin comillas) como texto; "0" si null/ausente. */
    private String numberField(String json, String field) {
        String v = numberFieldOrNull(json, field);
        return v == null ? "0" : v;
    }

    private String numberFieldOrNull(String json, String field) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Primer campo numérico presente entre varias claves; "0" si ninguna. */
    private String coalesceNum(String json, String... fields) {
        for (String f : fields) {
            String v = numberFieldOrNull(json, f);
            if (v != null) return v;
        }
        return "0";
    }

    // ---- AEAT-ED-2: modelo 390 (resumen anual IVA) ----

    /** Calcula el 390 del año desde facturas/compras (no persiste): bases por tipo. */
    public com.benjagest.ui.model.Aeat390Data preview390(int year)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/aeat/extras/390/" + year + "/preview").GET());
        String j = r.body();
        com.benjagest.ui.model.Aeat390Data d = new com.benjagest.ui.model.Aeat390Data();
        d.baseDev4 = numberField(j, "01_base04");
        d.baseDev10 = numberField(j, "02_base10");
        d.baseDev21 = numberField(j, "03_base21");
        d.baseDed4 = numberField(j, "22_base_soportada_04");
        d.baseDed10 = numberField(j, "24_base_soportada_10");
        d.baseDed21 = numberField(j, "28_base_soportada_21");
        return d;
    }

    /** MOD-PREFILL — prefill del 303 desde las facturas del trimestre. */
    public com.benjagest.ui.model.Aeat303Data preview303(int year, int quarter)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/aeat/extras/303/" + year + "/" + quarter + "/preview").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        String j = r.body();
        com.benjagest.ui.model.Aeat303Data d = new com.benjagest.ui.model.Aeat303Data();
        d.base4 = numberField(j, "base_4");
        d.base10 = numberField(j, "base_10");
        d.base21 = numberField(j, "base_21");
        d.baseSoportada = numberField(j, "base_soportado");
        d.cuotaSoportada = numberField(j, "cuota_soportada");
        // IVA-COMP: casilla 110 arrastrada por el backend.
        d.compensacionPrevia = numberField(j, "110_compensar_anteriores");
        return d;
    }

    // ---- IVA-COMP: saldo inicial de cuotas de IVA a compensar (303) ----

    /** Devuelve [openingBalance, asOfYear, asOfQuarter] o null si no hay saldo configurado. */
    public String[] getVatCompensationBaseline() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/aeat/extras/303/compensation-baseline").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        String j = r.body();
        if (j == null || j.isBlank() || "null".equals(j.trim())) return null;
        return new String[]{ numberField(j, "openingBalance"),
                numberField(j, "asOfYear"), numberField(j, "asOfQuarter") };
    }

    public void setVatCompensationBaseline(String openingBalance, int asOfYear, int asOfQuarter)
            throws IOException, InterruptedException {
        String body = "{\"openingBalance\":" + (openingBalance == null || openingBalance.isBlank() ? "0" : openingBalance)
                + ",\"asOfYear\":" + asOfYear + ",\"asOfQuarter\":" + asOfQuarter + "}";
        HttpResponse<String> r = send(req(baseUrl + "/aeat/extras/303/compensation-baseline")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    /** MOD-PREFILL — prefill del 130 (acumulado del año hasta el trimestre). */
    public com.benjagest.ui.model.Aeat130Data preview130(int year, int quarter)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/aeat/extras/130/" + year + "/" + quarter + "/preview").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        String j = r.body();
        com.benjagest.ui.model.Aeat130Data d = new com.benjagest.ui.model.Aeat130Data();
        d.ingresos = numberField(j, "ingresos");
        d.gastos = numberField(j, "gastos");
        d.retenciones = numberField(j, "retenciones");
        d.pagosPrevios = numberField(j, "pagosPrevios");
        return d;
    }

    /** Lee el 390 del JSON guardado (mis claves planas) o, si es uno ya generado,
     *  de las casillas del backend. */
    public com.benjagest.ui.model.Aeat390Data parse390(String json) {
        com.benjagest.ui.model.Aeat390Data d = new com.benjagest.ui.model.Aeat390Data();
        if (json == null || json.isBlank()) return d;
        d.baseDev4 = coalesceNum(json, "baseDev4", "01_base04");
        d.baseDev10 = coalesceNum(json, "baseDev10", "02_base10");
        d.baseDev21 = coalesceNum(json, "baseDev21", "03_base21");
        d.baseDed4 = coalesceNum(json, "baseDed4", "22_base_soportada_04");
        d.baseDed10 = coalesceNum(json, "baseDed10", "24_base_soportada_10");
        d.baseDed21 = coalesceNum(json, "baseDed21", "28_base_soportada_21");
        d.exentas = numberField(json, "exentas");
        d.intracom = numberField(json, "intracom");
        d.compensaciones = numberField(json, "compensaciones");
        return d;
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
    // DR-2 — Declaración responsable del fabricante (RD 1007/2023)
    //   GET /api/billing/manufacturer-declaration/text
    //   GET /api/billing/manufacturer-declaration/pdf
    // Visible en Configuración → Acerca de (la Orden HAC/1177/2024
    // exige que conste en el propio sistema en cada versión).
    // ============================================================

    public String getManufacturerDeclarationText(String version)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/billing/manufacturer-declaration/text"
                + versionParam(version)).GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    public byte[] downloadManufacturerDeclarationPdf(String version)
            throws IOException, InterruptedException {
        return sendBytes(req(baseUrl + "/billing/manufacturer-declaration/pdf"
                + versionParam(version)).GET()).body();
    }

    private static String versionParam(String version) {
        return version == null || version.isBlank() ? ""
                : "?version=" + java.net.URLEncoder.encode(version, java.nio.charset.StandardCharsets.UTF_8);
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
     * Crea un calendario VACÍO (sin festivos). El usuario lo puebla
     * después con el botón 'Importar desde PDF' que sube el calendario
     * oficial de su CCAA — único camino vinculante.
     */
    public com.benjagest.ui.model.WorkCalendarEntry createEmptyWorkCalendar(
            int year, String regionCcaa, String regionMunicipality, String name)
            throws IOException, InterruptedException {
        String body = "{"
                + "\"year\":" + year + ","
                + field("regionCcaa", regionCcaa) + ","
                + field("regionMunicipality", regionMunicipality) + ","
                + field("name", name)
                + "}";
        HttpResponse<String> r = send(req(baseUrl + "/labor/work-calendars")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return mapWorkCalendar(r.body());
    }

    public void deleteWorkCalendar(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/labor/work-calendars/" + id).DELETE());
    }

    /**
     * Vuelca los festivos/ajustes/cierres del calendario laboral a la
     * Agenda general. Devuelve el número de eventos volcados.
     * Idempotente — la segunda llamada reemplaza, no duplica.
     */
    public int dumpWorkCalendarToAgenda(String calendarId)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/labor/work-calendars/" + calendarId + "/dump-to-agenda")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        // Body: {"events": N}
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"events\"\\s*:\\s*(\\d+)").matcher(r.body());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * PORT-5 CAL-B — Inversa de {@link #dumpWorkCalendarToAgenda}.
     * Quita de la Agenda general los eventos que se hayan volcado
     * desde este calendario laboral. Idempotente.
     */
    public int removeWorkCalendarFromAgenda(String calendarId)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/labor/work-calendars/" + calendarId + "/dump-to-agenda").DELETE());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"events\"\\s*:\\s*(\\d+)").matcher(r.body());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * PORT-5 CAL-C — Carga los 10 festivos nacionales fijos del año del
     * calendario seleccionado. Idempotente: solo añade los que faltan.
     */
    public int loadNationalHolidays(String calendarId)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/labor/work-calendars/" + calendarId + "/load-national-holidays")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"inserted\"\\s*:\\s*(\\d+)").matcher(r.body());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // ============================================================
    //  PORT-1 — Portal del empleado /api/portal/*
    // ============================================================

    public List<com.benjagest.ui.model.PortalEvent> listPortalCalendar(
            java.time.LocalDate from, java.time.LocalDate to)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/portal/calendar?from=" + from + "&to=" + to).GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.List<com.benjagest.ui.model.PortalEvent> out = new java.util.ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.PortalEvent(
                    textField(obj, "id"),
                    textField(obj, "date"),
                    textField(obj, "title"),
                    textField(obj, "detail"),
                    textField(obj, "eventType"),
                    textField(obj, "kind"),
                    textField(obj, "sourceType")));
        }
        return out;
    }

    public List<com.benjagest.ui.model.PortalPayslip> listPortalPayslips()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/portal/payslips").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.List<com.benjagest.ui.model.PortalPayslip> out = new java.util.ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.PortalPayslip(
                    textField(obj, "id"),
                    intFieldOrZero(obj, "year"),
                    intFieldOrZero(obj, "month"),
                    bigDecField(obj, "grossAmount"),
                    bigDecField(obj, "netAmount"),
                    textField(obj, "status"),
                    textField(obj, "pdfPath")));
        }
        return out;
    }

    public List<com.benjagest.ui.model.PortalNotification> listPortalNotifications()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/portal/notifications").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.List<com.benjagest.ui.model.PortalNotification> out = new java.util.ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.PortalNotification(
                    textField(obj, "id"),
                    textField(obj, "severity"),
                    textField(obj, "title"),
                    textField(obj, "body"),
                    textField(obj, "createdAt"),
                    boolField(obj, "read")));
        }
        return out;
    }

    public List<com.benjagest.ui.model.PortalJob> listPortalJobs()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/portal/jobs").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.List<com.benjagest.ui.model.PortalJob> out = new java.util.ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.PortalJob(
                    textField(obj, "id"),
                    textField(obj, "title"),
                    textField(obj, "date"),
                    textField(obj, "status")));
        }
        return out;
    }

    // ============================================================
    //  Centros de trabajo /api/labor/work-centers (V89)
    // ============================================================

    public List<com.benjagest.ui.model.WorkCenterEntry> listWorkCenters()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/work-centers").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.List<com.benjagest.ui.model.WorkCenterEntry> out = new java.util.ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.WorkCenterEntry(
                    textField(obj, "id"),
                    textField(obj, "name"),
                    textField(obj, "address"),
                    textField(obj, "city"),
                    textField(obj, "province"),
                    textField(obj, "postalCode"),
                    bigDecField(obj, "lat"),
                    bigDecField(obj, "lng"),
                    intFieldOrZero(obj, "radioM"),
                    textField(obj, "geoPolicy"),
                    textField(obj, "notes"),
                    boolField(obj, "active")));
        }
        return out;
    }

    public com.benjagest.ui.model.WorkCenterEntry saveWorkCenter(
            String id, String name, String address, String city,
            String province, String postalCode,
            java.math.BigDecimal lat, java.math.BigDecimal lng,
            Integer radioM, String geoPolicy, String notes)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        b.append("\"name\":").append(jsonString(name));
        b.append(",\"address\":").append(jsonString(address == null ? "" : address));
        b.append(",\"city\":").append(jsonString(city == null ? "" : city));
        b.append(",\"province\":").append(jsonString(province == null ? "" : province));
        b.append(",\"postalCode\":").append(jsonString(postalCode == null ? "" : postalCode));
        if (lat != null) b.append(",\"lat\":").append(lat.toPlainString());
        if (lng != null) b.append(",\"lng\":").append(lng.toPlainString());
        if (radioM != null) b.append(",\"radioM\":").append(radioM);
        b.append(",\"geoPolicy\":").append(jsonString(geoPolicy == null ? "info" : geoPolicy));
        b.append(",\"notes\":").append(jsonString(notes == null ? "" : notes));
        b.append('}');
        String url = id == null || id.isBlank()
                ? baseUrl + "/labor/work-centers"
                : baseUrl + "/labor/work-centers/" + id;
        java.net.http.HttpRequest.Builder builder = req(url)
                .header("Content-Type", "application/json");
        if (id == null || id.isBlank()) {
            builder.POST(java.net.http.HttpRequest.BodyPublishers.ofString(b.toString()));
        } else {
            builder.PUT(java.net.http.HttpRequest.BodyPublishers.ofString(b.toString()));
        }
        HttpResponse<String> r = send(builder);
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        String obj = r.body();
        return new com.benjagest.ui.model.WorkCenterEntry(
                textField(obj, "id"),
                textField(obj, "name"),
                textField(obj, "address"),
                textField(obj, "city"),
                textField(obj, "province"),
                textField(obj, "postalCode"),
                bigDecField(obj, "lat"),
                bigDecField(obj, "lng"),
                intFieldOrZero(obj, "radioM"),
                textField(obj, "geoPolicy"),
                textField(obj, "notes"),
                boolField(obj, "active"));
    }

    public void deleteWorkCenter(String id) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/work-centers/" + id).DELETE());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    /**
     * CENTROS-GEOCODE — backend hace la llamada a Nominatim y devuelve
     * lat/lng/displayName. Búsqueda estructurada (street + cp + city +
     * state) es mucho más precisa que la libre.
     */
    public GeocodeResult geocodeWorkCenter(String street, String postalCode,
                                             String city, String state)
            throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl + "/labor/work-centers/geocode?");
        java.util.function.BiConsumer<String, String> add = (k, v) -> {
            if (v == null || v.isBlank()) return;
            url.append("&").append(k).append("=").append(
                    java.net.URLEncoder.encode(v.trim(),
                            java.nio.charset.StandardCharsets.UTF_8));
        };
        add.accept("street", street);
        add.accept("postalcode", postalCode);
        add.accept("city", city);
        add.accept("state", state);
        String finalUrl = url.toString().replaceFirst("\\?&", "?");
        HttpResponse<String> r = send(req(finalUrl).GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.regex.Matcher mLat = java.util.regex.Pattern
                .compile("\"lat\"\\s*:\\s*([-0-9.]+)").matcher(r.body());
        java.util.regex.Matcher mLng = java.util.regex.Pattern
                .compile("\"lng\"\\s*:\\s*([-0-9.]+)").matcher(r.body());
        if (!mLat.find() || !mLng.find()) {
            throw new IOException("Respuesta sin coordenadas: " + r.body());
        }
        return new GeocodeResult(
                new java.math.BigDecimal(mLat.group(1)),
                new java.math.BigDecimal(mLng.group(1)),
                textField(r.body(), "displayName"));
    }

    public record GeocodeResult(java.math.BigDecimal lat,
                                 java.math.BigDecimal lng,
                                 String displayName) {}

    // ============================================================
    //  PORT-2 — Work logs /api/work-logs
    // ============================================================

    public List<com.benjagest.ui.model.WorkLogEntry> listWorkLogs(
            java.time.LocalDate from, java.time.LocalDate to,
            String customerId, String status, boolean billableUnbilledOnly)
            throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl + "/work-logs?from=" + from + "&to=" + to);
        if (customerId != null && !customerId.isBlank()) {
            url.append("&customerId=").append(java.net.URLEncoder.encode(customerId, java.nio.charset.StandardCharsets.UTF_8));
        }
        if (status != null && !status.isBlank()) url.append("&status=").append(status);
        if (billableUnbilledOnly) url.append("&billableUnbilledOnly=true");
        HttpResponse<String> r = send(req(url.toString()).GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.List<com.benjagest.ui.model.WorkLogEntry> out = new java.util.ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) out.add(parseWorkLog(obj));
        return out;
    }

    public com.benjagest.ui.model.WorkLogEntry createWorkLog(
            com.benjagest.ui.model.WorkLogEntry e) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/work-logs")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(workLogBody(e))));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return parseWorkLog(r.body());
    }

    public com.benjagest.ui.model.WorkLogEntry updateWorkLog(
            String id, com.benjagest.ui.model.WorkLogEntry e) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/work-logs/" + id)
                .header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(workLogBody(e))));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return parseWorkLog(r.body());
    }

    public void deleteWorkLog(String id) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/work-logs/" + id).DELETE());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    /** TRB-3 — factura los trabajos seleccionados; devuelve el id de la factura creada. */
    public String billWorkLogs(java.util.List<String> ids, boolean merge, String mergedConcept)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{\"merge\":").append(merge);
        if (mergedConcept != null && !mergedConcept.isBlank()) {
            b.append(",\"mergedConcept\":").append(jsonString(mergedConcept));
        }
        b.append(",\"ids\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) b.append(',');
            b.append(jsonString(ids.get(i)));
        }
        b.append("]}");
        HttpResponse<String> r = send(req(baseUrl + "/work-logs/bill")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(b.toString())));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return textField(r.body(), "invoiceId");
    }

    /** Marca trabajos como FACTURADOS enlazados a una factura ya creada (editor). */
    public void markWorksBilled(java.util.List<String> ids, String invoiceId)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{\"invoiceId\":").append(jsonString(invoiceId)).append(",\"ids\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) b.append(',');
            b.append(jsonString(ids.get(i)));
        }
        b.append("]}");
        HttpResponse<String> r = send(req(baseUrl + "/work-logs/mark-billed")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(b.toString())));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    public void setWorkLogStatus(String id, String status) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/work-logs/" + id + "/status?status=" + status)
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    // ----- TRB-4: tarifas de trabajo -----

    public java.util.List<com.benjagest.ui.model.WorkRateEntry> listWorkRates(
            String customerId, boolean effective) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(baseUrl + "/work-rates?effective=" + effective);
        if (customerId != null && !customerId.isBlank()) {
            url.append("&customerId=").append(java.net.URLEncoder.encode(customerId, java.nio.charset.StandardCharsets.UTF_8));
        }
        HttpResponse<String> r = send(req(url.toString()).GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        java.util.List<com.benjagest.ui.model.WorkRateEntry> out = new java.util.ArrayList<>();
        for (String o : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.WorkRateEntry(
                    textField(o, "id"), textField(o, "customerId"), textField(o, "unit"),
                    textField(o, "concept"), bigDecField(o, "price"), boolField(o, "active")));
        }
        return out;
    }

    public void createWorkRate(String customerId, String unit, String concept, java.math.BigDecimal price)
            throws IOException, InterruptedException {
        send(req(baseUrl + "/work-rates").header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(workRateBody(customerId, unit, concept, price))));
    }

    public void updateWorkRate(String id, String customerId, String unit, String concept, java.math.BigDecimal price)
            throws IOException, InterruptedException {
        send(req(baseUrl + "/work-rates/" + id).header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(workRateBody(customerId, unit, concept, price))));
    }

    public void deleteWorkRate(String id) throws IOException, InterruptedException {
        send(req(baseUrl + "/work-rates/" + id).DELETE());
    }

    private String workRateBody(String customerId, String unit, String concept, java.math.BigDecimal price) {
        StringBuilder b = new StringBuilder("{");
        if (customerId != null && !customerId.isBlank()) b.append("\"customerId\":").append(jsonString(customerId)).append(',');
        b.append("\"unit\":").append(jsonString(unit));
        b.append(",\"concept\":").append(jsonString(concept));
        b.append(",\"price\":").append(price == null ? "0" : price.toPlainString());
        b.append('}');
        return b.toString();
    }

    private String workLogBody(com.benjagest.ui.model.WorkLogEntry e) {
        StringBuilder b = new StringBuilder("{");
        b.append("\"employeeId\":").append(jsonString(e.employeeId()));
        b.append(",\"logDate\":").append(jsonString(e.logDate()));
        b.append(",\"minutesWorked\":").append(e.minutesWorked());
        if (e.customerId() != null && !e.customerId().isBlank()) {
            b.append(",\"customerId\":").append(jsonString(e.customerId()));
        }
        if (e.description() != null && !e.description().isBlank()) {
            b.append(",\"description\":").append(jsonString(e.description()));
        }
        b.append(",\"isBillable\":").append(e.billable());
        if (e.billingUnit() != null && !e.billingUnit().isBlank()) {
            b.append(",\"billingUnit\":").append(jsonString(e.billingUnit()));
        }
        if (e.quantity() != null) b.append(",\"quantity\":").append(e.quantity().toPlainString());
        if (e.unitPrice() != null) b.append(",\"unitPrice\":").append(e.unitPrice().toPlainString());
        if (e.billableAmount() != null) b.append(",\"billableAmount\":").append(e.billableAmount().toPlainString());
        b.append('}');
        return b.toString();
    }

    private com.benjagest.ui.model.WorkLogEntry parseWorkLog(String obj) {
        return new com.benjagest.ui.model.WorkLogEntry(
                textField(obj, "id"), textField(obj, "employeeId"), textField(obj, "employeeName"),
                textField(obj, "logDate"), intFieldOrZero(obj, "minutesWorked"),
                textField(obj, "customerId"), textField(obj, "customerName"),
                textField(obj, "description"), boolField(obj, "billable"),
                textField(obj, "billingUnit"), bigDecField(obj, "quantity"), bigDecField(obj, "unitPrice"),
                bigDecField(obj, "billableAmount"), textField(obj, "status"),
                textField(obj, "billedInvoiceLineId"));
    }

    // ============================================================
    //  PORT-4 SESSION — Sesion (timeout + PIN + salvapantallas)
    //                   /api/settings/session
    // ============================================================

    public com.benjagest.ui.model.SessionStatusEntry getSessionStatus()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/settings/session").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        String obj = r.body();
        return new com.benjagest.ui.model.SessionStatusEntry(
                intFieldOrZero(obj, "pinTimeoutMin"),
                textField(obj, "screensaverStyle"),
                boolField(obj, "pinConfigured"));
    }

    public com.benjagest.ui.model.SessionStatusEntry saveSessionSettings(
            int pinTimeoutMin, String screensaverStyle)
            throws IOException, InterruptedException {
        String body = "{\"pinTimeoutMin\":" + pinTimeoutMin
                + ",\"screensaverStyle\":" + jsonString(screensaverStyle) + "}";
        HttpResponse<String> r = send(req(baseUrl + "/settings/session")
                .header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        String obj = r.body();
        return new com.benjagest.ui.model.SessionStatusEntry(
                intFieldOrZero(obj, "pinTimeoutMin"),
                textField(obj, "screensaverStyle"),
                boolField(obj, "pinConfigured"));
    }

    public void setSessionPin(String currentPin, String newPin)
            throws IOException, InterruptedException {
        String body = "{\"currentPin\":" + jsonString(currentPin == null ? "" : currentPin)
                + ",\"newPin\":" + jsonString(newPin) + "}";
        HttpResponse<String> r = send(req(baseUrl + "/settings/session/pin")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    public void deleteSessionPin(String currentPin)
            throws IOException, InterruptedException {
        String body = "{\"currentPin\":" + jsonString(currentPin == null ? "" : currentPin)
                + ",\"newPin\":\"\"}";
        HttpResponse<String> r = send(req(baseUrl + "/settings/session/pin")
                .header("Content-Type", "application/json")
                .method("DELETE", java.net.http.HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    public boolean verifySessionPin(String pin)
            throws IOException, InterruptedException {
        String body = "{\"pin\":" + jsonString(pin) + "}";
        HttpResponse<String> r = send(req(baseUrl + "/settings/session/pin/verify")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        // La respuesta es {"valid":true} (booleano, sin comillas); textField solo
        // matchea strings entrecomillados -> daba siempre false ("PIN incorrecto").
        return boolField(r.body(), "valid");
    }

    // ============================================================
    //  UI asesoría↔cliente — Mensajes /api/advisory/messages
    //  (backend V77 — sesión 2026-06-09)
    // ============================================================

    public List<com.benjagest.ui.model.AdvisoryThreadSummary> listAdvisoryThreads()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/advisory/messages/threads").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.List<com.benjagest.ui.model.AdvisoryThreadSummary> out = new java.util.ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.AdvisoryThreadSummary(
                    textField(obj, "otherCompanyId"),
                    textField(obj, "lastAt"),
                    intFieldOrZero(obj, "unreadCount"),
                    intFieldOrZero(obj, "totalCount")));
        }
        return out;
    }

    public List<com.benjagest.ui.model.AdvisoryMessageEntry> listAdvisoryThread(
            String otherCompanyId) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/advisory/messages/threads/" + otherCompanyId).GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.List<com.benjagest.ui.model.AdvisoryMessageEntry> out = new java.util.ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.AdvisoryMessageEntry(
                    textField(obj, "id"),
                    textField(obj, "advisoryCompanyId"),
                    textField(obj, "clientCompanyId"),
                    textField(obj, "direction"),
                    textField(obj, "fromUserId"),
                    textField(obj, "body"),
                    textField(obj, "attachmentPath"),
                    textField(obj, "readAt"),
                    textField(obj, "createdAt")));
        }
        return out;
    }

    public com.benjagest.ui.model.AdvisoryMessageEntry sendAdvisoryMessage(
            String otherCompanyId, String body, String attachmentPath)
            throws IOException, InterruptedException {
        String json = "{\"body\":" + jsonString(body)
                + ",\"attachmentPath\":" + jsonString(attachmentPath == null ? "" : attachmentPath)
                + "}";
        HttpResponse<String> r = send(req(
                baseUrl + "/advisory/messages/threads/" + otherCompanyId + "/send")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        String obj = r.body();
        return new com.benjagest.ui.model.AdvisoryMessageEntry(
                textField(obj, "id"),
                textField(obj, "advisoryCompanyId"),
                textField(obj, "clientCompanyId"),
                textField(obj, "direction"),
                textField(obj, "fromUserId"),
                textField(obj, "body"),
                textField(obj, "attachmentPath"),
                textField(obj, "readAt"),
                textField(obj, "createdAt"));
    }

    public int markAdvisoryThreadRead(String otherCompanyId)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/advisory/messages/threads/" + otherCompanyId + "/mark-read")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"markedRead\"\\s*:\\s*(\\d+)").matcher(r.body());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // ============================================================
    //  UI asesoría↔cliente — Documentos /api/advisory/documents
    //  (backend V78 + upload multipart 2026-06-10 noche)
    // ============================================================

    public List<com.benjagest.ui.model.AdvisoryDocumentEntry> listAdvisoryDocuments(
            String otherCompanyId) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/advisory/documents/threads/" + otherCompanyId).GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.List<com.benjagest.ui.model.AdvisoryDocumentEntry> out = new java.util.ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.AdvisoryDocumentEntry(
                    textField(obj, "id"),
                    textField(obj, "advisoryCompanyId"),
                    textField(obj, "clientCompanyId"),
                    textField(obj, "direction"),
                    textField(obj, "title"),
                    textField(obj, "filePath"),
                    longFieldOrZero(obj, "fileSizeBytes"),
                    textField(obj, "mimeType"),
                    textField(obj, "status"),
                    textField(obj, "note"),
                    textField(obj, "createdAt"),
                    textField(obj, "reviewedAt")));
        }
        return out;
    }

    public com.benjagest.ui.model.AdvisoryDocumentEntry uploadAdvisoryDocument(
            String otherCompanyId, java.io.File file, String title)
            throws IOException, InterruptedException {
        String boundary = "----benjagest-doc-" + System.currentTimeMillis();
        String filename = file.getName().replace("\"", "");
        byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes());
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\n").getBytes());
        body.write(("Content-Type: application/octet-stream\r\n\r\n").getBytes());
        body.write(fileBytes);
        body.write(("\r\n").getBytes());
        if (title != null && !title.isBlank()) {
            body.write(("--" + boundary + "\r\n").getBytes());
            body.write(("Content-Disposition: form-data; name=\"title\"\r\n\r\n").getBytes());
            body.write(title.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            body.write(("\r\n").getBytes());
        }
        body.write(("--" + boundary + "--\r\n").getBytes());
        HttpResponse<String> r = send(req(
                baseUrl + "/advisory/documents/threads/" + otherCompanyId + "/upload")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        String obj = r.body();
        return new com.benjagest.ui.model.AdvisoryDocumentEntry(
                textField(obj, "id"),
                textField(obj, "advisoryCompanyId"),
                textField(obj, "clientCompanyId"),
                textField(obj, "direction"),
                textField(obj, "title"),
                textField(obj, "filePath"),
                longFieldOrZero(obj, "fileSizeBytes"),
                textField(obj, "mimeType"),
                textField(obj, "status"),
                textField(obj, "note"),
                textField(obj, "createdAt"),
                textField(obj, "reviewedAt"));
    }

    public byte[] downloadAdvisoryDocument(String id)
            throws IOException, InterruptedException {
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(baseUrl + "/advisory/documents/" + id + "/download")).GET();
        com.benjagest.ui.service.AuthSession.get().authorize(b);
        java.net.http.HttpResponse<byte[]> r = httpClient.send(b.build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
        return r.body();
    }

    public com.benjagest.ui.model.AdvisoryDocumentEntry reviewAdvisoryDocument(
            String id, String status, String note)
            throws IOException, InterruptedException {
        String json = "{\"status\":" + jsonString(status)
                + ",\"note\":" + jsonString(note == null ? "" : note) + "}";
        HttpResponse<String> r = send(req(
                baseUrl + "/advisory/documents/" + id + "/review")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        String obj = r.body();
        return new com.benjagest.ui.model.AdvisoryDocumentEntry(
                textField(obj, "id"),
                textField(obj, "advisoryCompanyId"),
                textField(obj, "clientCompanyId"),
                textField(obj, "direction"),
                textField(obj, "title"),
                textField(obj, "filePath"),
                longFieldOrZero(obj, "fileSizeBytes"),
                textField(obj, "mimeType"),
                textField(obj, "status"),
                textField(obj, "note"),
                textField(obj, "createdAt"),
                textField(obj, "reviewedAt"));
    }

    private long longFieldOrZero(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    // ============================================================
    //  Advisory Notifications (bandeja del asesor)
    //  Backend: /api/advisory/notifications
    // ============================================================

    public int countUnreadAdvisoryNotifications() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/advisory/notifications/count-unread").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"unread\"\\s*:\\s*(\\d+)").matcher(r.body());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    public List<com.benjagest.ui.model.AdvisoryNotificationEntry>
            listAdvisoryNotifications(boolean onlyUnread)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/advisory/notifications?onlyUnread=" + onlyUnread).GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        List<com.benjagest.ui.model.AdvisoryNotificationEntry> out = new ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.AdvisoryNotificationEntry(
                    textField(obj, "id"),
                    textField(obj, "clientCompanyId"),
                    textField(obj, "notificationType"),
                    textField(obj, "severity"),
                    textField(obj, "title"),
                    textField(obj, "message"),
                    textField(obj, "entityRef"),
                    textField(obj, "readAt"),
                    textField(obj, "createdAt")));
        }
        return out;
    }

    public void markAdvisoryNotificationRead(String id)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/advisory/notifications/" + id + "/read")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
    }

    public void dismissAdvisoryNotification(String id)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/advisory/notifications/" + id + "/dismiss")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
    }

    public int markAllAdvisoryNotificationsRead() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/advisory/notifications/mark-all-read")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"markedRead\"\\s*:\\s*(\\d+)").matcher(r.body());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // ============================================================
    //  Business Notifications (bandeja del empresario)
    //  Backend: /api/business/notifications — mismo shape
    // ============================================================

    public int countUnreadBusinessNotifications() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/business/notifications/count-unread").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"unread\"\\s*:\\s*(\\d+)").matcher(r.body());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    public List<com.benjagest.ui.model.AdvisoryNotificationEntry>
            listBusinessNotifications(boolean onlyUnread)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/business/notifications?onlyUnread=" + onlyUnread).GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        List<com.benjagest.ui.model.AdvisoryNotificationEntry> out = new ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            // Reusa AdvisoryNotificationEntry — campos coinciden con
            // BusinessNotification salvo relatedCompanyId ↔ clientCompanyId.
            // El UI sólo los usa como tracking interno.
            out.add(new com.benjagest.ui.model.AdvisoryNotificationEntry(
                    textField(obj, "id"),
                    textField(obj, "relatedCompanyId"),
                    textField(obj, "notificationType"),
                    textField(obj, "severity"),
                    textField(obj, "title"),
                    textField(obj, "message"),
                    textField(obj, "entityRef"),
                    textField(obj, "readAt"),
                    textField(obj, "createdAt")));
        }
        return out;
    }

    public void markBusinessNotificationRead(String id)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/business/notifications/" + id + "/read")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
    }

    public void dismissBusinessNotification(String id)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/business/notifications/" + id + "/dismiss")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
    }

    public int markAllBusinessNotificationsRead() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/business/notifications/mark-all-read")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"markedRead\"\\s*:\\s*(\\d+)").matcher(r.body());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // ============================================================
    //  TPB — Acuerdo previo de facturación por tercero (RD 1619/2012)
    //  Backend: /api/billing/third-party-agreements
    // ============================================================

    /**
     * Reenvio del enlace de revocacion (V105): cliente perdio el email
     * original; la asesoria solicita reenvio al mismo email del SIGN.
     */
    public String tpbResendRevokeLink(String agreementId)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl
                + "/billing/third-party-agreements/" + agreementId + "/revoke-link/resend")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    /**
     * Magic Link (V104): la asesoria pide al backend enviar al email
     * del cliente un enlace de firma electronica simple. Devuelve el
     * body raw con email y fecha de expiracion.
     */
    public String tpbSendMagicLink(String agreementId, String email)
            throws IOException, InterruptedException {
        String body = "{\"email\":\""
                + (email == null ? "" : email.replace("\\", "\\\\").replace("\"", "\\\""))
                + "\"}";
        HttpResponse<String> r = send(req(baseUrl
                + "/billing/third-party-agreements/" + agreementId + "/magic-link/send")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    /**
     * Diagnostico/reparacion: garantiza la serie TPB del acuerdo dado.
     * Devuelve el JSON crudo con seriesId, code, nextNumber, created.
     * Si el backend rechaza (acuerdo no ACTIVE, no cubre ventas) lanza
     * IOException con el codigo HTTP + mensaje.
     */
    public String tpbEnsureSeries(String agreementId)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl
                + "/billing/third-party-agreements/" + agreementId + "/ensure-series")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }

    public com.benjagest.ui.model.TpbAgreementEntry tpbFindCurrent(String otherCompanyId)
            throws IOException, InterruptedException {
        String encoded = java.net.URLEncoder.encode(otherCompanyId,
                java.nio.charset.StandardCharsets.UTF_8);
        HttpResponse<String> r = send(req(
                baseUrl + "/billing/third-party-agreements/current?otherCompanyId=" + encoded).GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        String body = r.body();
        if (body == null || body.isBlank() || "null".equals(body.trim())) return null;
        return mapTpbAgreement(body);
    }

    public com.benjagest.ui.model.TpbAgreementEntry tpbPropose(
            String advisoryCompanyId, String clientCompanyId,
            boolean scopeSales, boolean scopePurchases, boolean scopeTaxModels)
            throws IOException, InterruptedException {
        String json = "{"
                + "\"advisoryCompanyId\":\"" + advisoryCompanyId + "\","
                + "\"clientCompanyId\":\"" + clientCompanyId + "\","
                + "\"scopeSales\":" + scopeSales + ","
                + "\"scopePurchases\":" + scopePurchases + ","
                + "\"scopeTaxModels\":" + scopeTaxModels
                + "}";
        HttpResponse<String> r = send(req(baseUrl + "/billing/third-party-agreements")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return mapTpbAgreement(r.body());
    }

    public com.benjagest.ui.model.TpbAgreementEntry tpbSignWithPin(String agreementId, String pin)
            throws IOException, InterruptedException {
        String json = "{\"pin\":\"" + (pin == null ? "" : pin) + "\"}";
        HttpResponse<String> r = send(req(
                baseUrl + "/billing/third-party-agreements/" + agreementId + "/sign-with-pin")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return mapTpbAgreement(r.body());
    }

    public com.benjagest.ui.model.TpbAgreementEntry tpbSignWithOfflinePdf(
            String agreementId, java.io.File pdf)
            throws IOException, InterruptedException {
        String boundary = "----benjagest-tpb-" + System.currentTimeMillis();
        String filename = pdf.getName().replace("\"", "");
        byte[] fileBytes = java.nio.file.Files.readAllBytes(pdf.toPath());
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes());
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\n").getBytes());
        body.write(("Content-Type: application/pdf\r\n\r\n").getBytes());
        body.write(fileBytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes());
        HttpResponse<String> r = send(req(
                baseUrl + "/billing/third-party-agreements/" + agreementId + "/sign-with-offline-pdf")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return mapTpbAgreement(r.body());
    }

    public byte[] tpbDownloadProposalPdf(String agreementId)
            throws IOException, InterruptedException {
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(baseUrl + "/billing/third-party-agreements/"
                        + agreementId + "/proposal-pdf"))
                .timeout(java.time.Duration.ofSeconds(30))
                .GET();
        com.benjagest.ui.service.AuthSession.get().authorize(b);
        HttpResponse<byte[]> r = httpClient.send(b.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
        return r.body();
    }

    public byte[] tpbDownloadSignedPdf(String agreementId)
            throws IOException, InterruptedException {
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(baseUrl + "/billing/third-party-agreements/"
                        + agreementId + "/signed-pdf"))
                .timeout(java.time.Duration.ofSeconds(30))
                .GET();
        com.benjagest.ui.service.AuthSession.get().authorize(b);
        HttpResponse<byte[]> r = httpClient.send(b.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
        return r.body();
    }

    public void tpbRevoke(String agreementId, String reason)
            throws IOException, InterruptedException {
        String url = baseUrl + "/billing/third-party-agreements/" + agreementId;
        if (reason != null && !reason.isBlank()) {
            url += "?reason=" + java.net.URLEncoder.encode(reason,
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        HttpResponse<String> r = send(req(url).DELETE());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    private com.benjagest.ui.model.TpbAgreementEntry mapTpbAgreement(String obj) {
        Boolean revokedByAdvisory = null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"revokedByAdvisory\"\\s*:\\s*(true|false|null)").matcher(obj);
        if (m.find() && !"null".equals(m.group(1))) {
            revokedByAdvisory = Boolean.parseBoolean(m.group(1));
        }
        return new com.benjagest.ui.model.TpbAgreementEntry(
                textField(obj, "id"),
                textField(obj, "advisoryCompanyId"),
                textField(obj, "clientCompanyId"),
                boolField(obj, "scopeSales"),
                boolField(obj, "scopePurchases"),
                boolField(obj, "scopeTaxModels"),
                textField(obj, "status"),
                boolField(obj, "initiatedByAdvisory"),
                textField(obj, "signedAt"),
                textField(obj, "signedMethod"),
                textField(obj, "signedPdfPath"),
                textField(obj, "revokedAt"),
                revokedByAdvisory,
                textField(obj, "revokedReason"),
                textField(obj, "createdAt"));
    }

    // ============================================================
    //  SIF — verificación a demanda (única operación legal)
    // ============================================================

    /** POST /api/billing/sif-events/verify-now. Dispara la detección
     *  de anomalías en todas las cadenas (facturas + eventos). */
    public void verifySifChainNow() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/billing/sif-events/verify-now")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    // ============================================================
    //  PANORAMA-ASESORIA — KPIs cartera
    // ============================================================

    public com.benjagest.ui.model.PortfolioFinancialsEntry getPortfolioFinancials()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/advisory/dashboard/portfolio-financials").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        String obj = r.body();
        return new com.benjagest.ui.model.PortfolioFinancialsEntry(
                decFieldOrZero(obj, "billedThisMonth"),
                decFieldOrZero(obj, "pendingPayment"),
                (int) longFieldOrZero(obj, "overdueInvoices"),
                (int) longFieldOrZero(obj, "activeClientsThisMonth"),
                (int) longFieldOrZero(obj, "pendingTpbApprovals"));
    }

    private java.math.BigDecimal decFieldOrZero(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*([-0-9.]+)").matcher(json);
        return m.find() ? new java.math.BigDecimal(m.group(1)) : java.math.BigDecimal.ZERO;
    }

    // ============================================================
    //  CAL-FISCAL — Calendario AEAT
    // ============================================================

    public List<com.benjagest.ui.model.TaxCalendarEventEntry> listUpcomingTaxCalendar(int days)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/fiscal/tax-calendar/upcoming?days=" + days).GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        List<com.benjagest.ui.model.TaxCalendarEventEntry> out = new ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(mapTaxCalendarEvent(obj));
        }
        return out;
    }

    public com.benjagest.ui.model.TaxCalendarEventEntry markTaxCalendarSubmitted(String id, String notes)
            throws IOException, InterruptedException {
        String url = baseUrl + "/fiscal/tax-calendar/" + id + "/mark-submitted";
        if (notes != null && !notes.isBlank()) {
            url += "?notes=" + java.net.URLEncoder.encode(notes,
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        HttpResponse<String> r = send(req(url)
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return mapTaxCalendarEvent(r.body());
    }

    private com.benjagest.ui.model.TaxCalendarEventEntry mapTaxCalendarEvent(String obj) {
        java.util.regex.Matcher mY = java.util.regex.Pattern
                .compile("\"fiscalYear\"\\s*:\\s*(\\d+)").matcher(obj);
        int year = mY.find() ? Integer.parseInt(mY.group(1)) : 0;
        return new com.benjagest.ui.model.TaxCalendarEventEntry(
                textField(obj, "id"),
                textField(obj, "companyId"),
                textField(obj, "modelCode"),
                textField(obj, "periodLabel"),
                textField(obj, "dueDate"),
                textField(obj, "description"),
                year,
                textField(obj, "status"),
                textField(obj, "submittedAt"),
                textField(obj, "notes"));
    }

    // ============================================================
    //  REC-BANCARIA — conciliacion bancaria /api/accounting
    // ============================================================

    public record BankSuggestionEntry(
            String movementId,
            String operationDate,
            String description,
            java.math.BigDecimal amount,
            String invoiceId,
            String invoiceNumber,
            String customerLegalName,
            java.math.BigDecimal pendingAmount,
            int dayDiff,
            int levDistance,
            double score) {}

    public List<BankSuggestionEntry> listBankSuggestions() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/accounting/bank-reconciliation/suggestions").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        List<BankSuggestionEntry> out = new ArrayList<>();
        for (String mvObj : splitTopLevelObjects(r.body())) {
            String movementId = textField(mvObj, "movementId");
            String operationDate = textField(mvObj, "operationDate");
            String description = textField(mvObj, "description");
            java.math.BigDecimal amount = decFieldOrZero(mvObj, "amount");
            java.util.regex.Matcher mArr = java.util.regex.Pattern
                    .compile("\"suggestions\"\\s*:\\s*\\[(.*?)\\](?=\\s*[,}])",
                            java.util.regex.Pattern.DOTALL).matcher(mvObj);
            if (!mArr.find()) continue;
            String arr = mArr.group(1);
            for (String sObj : splitTopLevelObjects("[" + arr + "]")) {
                out.add(new BankSuggestionEntry(
                        movementId, operationDate, description, amount,
                        textField(sObj, "invoiceId"),
                        textField(sObj, "invoiceNumber"),
                        textField(sObj, "customerLegalName"),
                        decFieldOrZero(sObj, "pendingAmount"),
                        intFieldRec(sObj, "dayDiff"),
                        intFieldRec(sObj, "levDistance"),
                        doubleFieldRec(sObj, "score")));
            }
        }
        return out;
    }

    public void linkBankMovement(String movementId, String invoiceId)
            throws IOException, InterruptedException {
        String body = "{\"invoiceKind\":\"SALES_INVOICE\",\"invoiceId\":\""
                + invoiceId + "\"}";
        HttpResponse<String> r = send(req(
                baseUrl + "/accounting/bank-movements/" + movementId + "/link")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    public void ignoreBankMovement(String movementId, String reason)
            throws IOException, InterruptedException {
        String url = baseUrl + "/accounting/bank-movements/" + movementId + "/ignore";
        if (reason != null && !reason.isBlank()) {
            url += "?reason=" + java.net.URLEncoder.encode(reason,
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        HttpResponse<String> r = send(req(url)
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    private int intFieldRec(String obj, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(obj);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private double doubleFieldRec(String obj, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(obj);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    // ============================================================
    //  BACKUP-LOCAL — backups locales /api/system/backup
    // ============================================================

    public record BackupInfoEntry(String filename, String fullPath,
                                   long sizeBytes, String lastModified) {}

    public List<BackupInfoEntry> listBackups() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/system/backup/list").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        List<BackupInfoEntry> out = new ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            java.util.regex.Matcher mSize = java.util.regex.Pattern
                    .compile("\"sizeBytes\"\\s*:\\s*(\\d+)").matcher(obj);
            long size = mSize.find() ? Long.parseLong(mSize.group(1)) : 0L;
            out.add(new BackupInfoEntry(
                    textField(obj, "filename"),
                    textField(obj, "fullPath"),
                    size,
                    textField(obj, "lastModified")));
        }
        return out;
    }

    public String runBackupNow() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/system/backup/run")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return textField(r.body(), "file");
    }

    /** Purga carpetas de ficheros de empresas que ya no están en la BD. Devuelve cuántas borró. */
    public int purgeOrphanFiles() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/system/backup/purge-orphans")
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return intField(r.body(), "deleted");
    }

    // ============================================================
    //  BOE-RSS — Alertas BOE /api/boe-alerts
    // ============================================================

    public List<com.benjagest.ui.model.BoeAlertEntry> listBoeAlerts(int days)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(
                baseUrl + "/boe-alerts?days=" + days).GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        List<com.benjagest.ui.model.BoeAlertEntry> out = new ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(new com.benjagest.ui.model.BoeAlertEntry(
                    textField(obj, "id"),
                    textField(obj, "alertDate"),
                    textField(obj, "boeId"),
                    textField(obj, "title"),
                    textField(obj, "url"),
                    textField(obj, "department"),
                    textField(obj, "keywordsMatched"),
                    textField(obj, "createdAt")));
        }
        return out;
    }

    public void runBoeAlertsNow(String dateIsoOrNull)
            throws IOException, InterruptedException {
        String url = baseUrl + "/boe-alerts/run-now";
        if (dateIsoOrNull != null && !dateIsoOrNull.isBlank()) {
            url += "?date=" + dateIsoOrNull;
        }
        HttpResponse<String> r = send(req(url)
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    // ============================================================
    //  PORT-4 LOGO — Logo de empresa /api/settings/company/logo
    // ============================================================

    /** Sube un PNG/JPG. Devuelve true si fue OK; false si HTTP error. */
    public boolean uploadCompanyLogo(java.io.File file)
            throws IOException, InterruptedException {
        if (file == null || !file.exists()) return false;
        String boundary = "----benjagest-logo-" + System.currentTimeMillis();
        String filename = file.getName().replace("\"", "");
        String ext = filename.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
        byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes());
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename + "\"\r\n").getBytes());
        body.write(("Content-Type: " + ext + "\r\n\r\n").getBytes());
        body.write(fileBytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes());
        HttpResponse<String> r = send(req(baseUrl + "/settings/company/logo")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return true;
    }

    /** Lee los bytes del logo actual. null si no hay (204). */
    public byte[] getCompanyLogoBytes() throws IOException, InterruptedException {
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(baseUrl + "/settings/company/logo")).GET();
        com.benjagest.ui.service.AuthSession.get().authorize(b);
        java.net.http.HttpResponse<byte[]> r = httpClient.send(b.build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() == 204) return null;
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode());
        }
        return r.body();
    }

    public void deleteCompanyLogo() throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/settings/company/logo").DELETE());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    // ============================================================
    //  PORT-4 CLI — Editor extendido de clientes /api/customers-extended
    // ============================================================

    public com.benjagest.ui.model.CustomerExtendedEntry getCustomerExtended(String id)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/customers-extended/" + id).GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return mapCustomerExtended(r.body());
    }

    private com.benjagest.ui.model.CustomerExtendedEntry mapCustomerExtended(String obj) {
        return new com.benjagest.ui.model.CustomerExtendedEntry(
                textField(obj, "id"),
                textField(obj, "legalName"),
                textField(obj, "tradeName"),
                textField(obj, "taxIdentifier"),
                textField(obj, "customerType"),
                textField(obj, "fiscalType"),
                textField(obj, "billingEmail"),
                textField(obj, "billingPhone"),
                bigDecField(obj, "defaultVatPercent"),
                bigDecField(obj, "defaultRetentionPercent"),
                boolField(obj, "vatExempt"),
                textField(obj, "paymentMethod"),
                textField(obj, "iban"),
                textField(obj, "address"),
                textField(obj, "city"),
                textField(obj, "province"),
                textField(obj, "postalCode"),
                textField(obj, "country"),
                textField(obj, "internalCode"),
                textField(obj, "defaultMode"),
                textField(obj, "phone"),
                textField(obj, "email"),
                textField(obj, "website"),
                textField(obj, "notes"));
    }

    /**
     * TPB-CLIENT-SETUP F1 — Crea un cliente-receptor extendido bajo el
     * tenant actual (POST). Devuelve la entidad creada con su id.
     */
    public com.benjagest.ui.model.CustomerExtendedEntry createCustomerExtended(
            com.benjagest.ui.model.CustomerExtendedEntry c)
            throws IOException, InterruptedException {
        String body = buildCustomerExtendedJson(c, null);
        HttpResponse<String> r = send(req(baseUrl + "/customers-extended")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return mapCustomerExtended(r.body());
    }

    /** Serializa una CustomerExtendedEntry a JSON. idOverride: id a usar
     *  (null = omite el campo id, para POST de creacion). */
    private String buildCustomerExtendedJson(
            com.benjagest.ui.model.CustomerExtendedEntry c, String idOverride) {
        StringBuilder b = new StringBuilder("{");
        if (idOverride != null) {
            b.append("\"id\":").append(jsonString(idOverride)).append(",");
        }
        b.append("\"legalName\":").append(jsonString(c.legalName()));
        b.append(",\"tradeName\":").append(jsonString(c.tradeName()));
        b.append(",\"taxIdentifier\":").append(jsonString(c.taxIdentifier()));
        b.append(",\"customerType\":").append(jsonString(c.customerType()));
        b.append(",\"fiscalType\":").append(jsonString(c.fiscalType()));
        b.append(",\"billingEmail\":").append(jsonString(c.billingEmail()));
        b.append(",\"billingPhone\":").append(jsonString(c.billingPhone()));
        b.append(",\"defaultVatPercent\":")
                .append(c.defaultVatPercent() == null ? "0" : c.defaultVatPercent().toPlainString());
        b.append(",\"defaultRetentionPercent\":")
                .append(c.defaultRetentionPercent() == null ? "0" : c.defaultRetentionPercent().toPlainString());
        b.append(",\"vatExempt\":").append(c.vatExempt());
        b.append(",\"paymentMethod\":").append(jsonString(c.paymentMethod()));
        b.append(",\"iban\":").append(jsonString(c.iban()));
        b.append(",\"address\":").append(jsonString(c.address()));
        b.append(",\"city\":").append(jsonString(c.city()));
        b.append(",\"province\":").append(jsonString(c.province()));
        b.append(",\"postalCode\":").append(jsonString(c.postalCode()));
        b.append(",\"country\":").append(jsonString(c.country()));
        b.append(",\"internalCode\":").append(jsonString(c.internalCode()));
        b.append(",\"defaultMode\":").append(jsonString(c.defaultMode()));
        b.append(",\"phone\":").append(jsonString(c.phone()));
        b.append(",\"email\":").append(jsonString(c.email()));
        b.append(",\"website\":").append(jsonString(c.website()));
        b.append(",\"notes\":").append(jsonString(c.notes()));
        b.append('}');
        return b.toString();
    }

    public com.benjagest.ui.model.CustomerExtendedEntry updateCustomerExtended(
            com.benjagest.ui.model.CustomerExtendedEntry c)
            throws IOException, InterruptedException {
        StringBuilder b = new StringBuilder("{");
        b.append("\"id\":").append(jsonString(c.id()));
        b.append(",\"legalName\":").append(jsonString(c.legalName()));
        b.append(",\"tradeName\":").append(jsonString(c.tradeName()));
        b.append(",\"taxIdentifier\":").append(jsonString(c.taxIdentifier()));
        b.append(",\"customerType\":").append(jsonString(c.customerType()));
        b.append(",\"fiscalType\":").append(jsonString(c.fiscalType()));
        b.append(",\"billingEmail\":").append(jsonString(c.billingEmail()));
        b.append(",\"billingPhone\":").append(jsonString(c.billingPhone()));
        b.append(",\"defaultVatPercent\":")
                .append(c.defaultVatPercent() == null ? "0" : c.defaultVatPercent().toPlainString());
        b.append(",\"defaultRetentionPercent\":")
                .append(c.defaultRetentionPercent() == null ? "0" : c.defaultRetentionPercent().toPlainString());
        b.append(",\"vatExempt\":").append(c.vatExempt());
        b.append(",\"paymentMethod\":").append(jsonString(c.paymentMethod()));
        b.append(",\"iban\":").append(jsonString(c.iban()));
        b.append(",\"address\":").append(jsonString(c.address()));
        b.append(",\"city\":").append(jsonString(c.city()));
        b.append(",\"province\":").append(jsonString(c.province()));
        b.append(",\"postalCode\":").append(jsonString(c.postalCode()));
        b.append(",\"country\":").append(jsonString(c.country()));
        b.append(",\"internalCode\":").append(jsonString(c.internalCode()));
        b.append(",\"defaultMode\":").append(jsonString(c.defaultMode()));
        b.append(",\"phone\":").append(jsonString(c.phone()));
        b.append(",\"email\":").append(jsonString(c.email()));
        b.append(",\"website\":").append(jsonString(c.website()));
        b.append(",\"notes\":").append(jsonString(c.notes()));
        b.append('}');
        HttpResponse<String> r = send(req(baseUrl + "/customers-extended/" + c.id())
                .header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(b.toString())));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return getCustomerExtended(c.id());
    }

    // ============================================================
    //  PORT-3 PERFIL — User settings /api/profile/settings
    // ============================================================

    public com.benjagest.ui.model.UserSettingsEntry getUserSettings()
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/profile/settings").GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        String obj = r.body();
        return new com.benjagest.ui.model.UserSettingsEntry(
                textField(obj, "userId"),
                textField(obj, "language"),
                intFieldOrZero(obj, "pinTimeoutMin"),
                textField(obj, "screensaverStyle"),
                boolField(obj, "aiEnabled"),
                textField(obj, "avatarPath"),
                textField(obj, "workdayTemplate"));
    }

    public com.benjagest.ui.model.UserSettingsEntry saveUserSettings(
            String language, int pinTimeoutMin, String screensaverStyle,
            boolean aiEnabled, String avatarPath, String workdayTemplate)
            throws IOException, InterruptedException {
        String body = "{\"language\":" + jsonString(language)
                + ",\"pinTimeoutMin\":" + pinTimeoutMin
                + ",\"screensaverStyle\":" + jsonString(screensaverStyle)
                + ",\"aiEnabled\":" + aiEnabled
                + ",\"avatarPath\":" + jsonString(avatarPath == null ? "" : avatarPath)
                + ",\"workdayTemplate\":" + jsonString(workdayTemplate == null ? "" : workdayTemplate)
                + "}";
        HttpResponse<String> r = send(req(baseUrl + "/profile/settings")
                .header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        String obj = r.body();
        return new com.benjagest.ui.model.UserSettingsEntry(
                textField(obj, "userId"),
                textField(obj, "language"),
                intFieldOrZero(obj, "pinTimeoutMin"),
                textField(obj, "screensaverStyle"),
                boolField(obj, "aiEnabled"),
                textField(obj, "avatarPath"),
                textField(obj, "workdayTemplate"));
    }

    // ============================================================
    //  Cotizaciones SS — /api/labor/social-security
    // ============================================================

    public List<com.benjagest.ui.model.SocialSecurityContributionEntry>
            listSocialSecurityContributions(Integer year, Integer month, String employeeId)
            throws IOException, InterruptedException {
        StringBuilder qs = new StringBuilder();
        if (year != null) qs.append(qs.length() == 0 ? "?" : "&").append("year=").append(year);
        if (month != null) qs.append(qs.length() == 0 ? "?" : "&").append("month=").append(month);
        if (employeeId != null && !employeeId.isBlank()) {
            qs.append(qs.length() == 0 ? "?" : "&").append("employeeId=").append(employeeId);
        }
        HttpResponse<String> r = send(req(baseUrl + "/labor/social-security" + qs).GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        List<com.benjagest.ui.model.SocialSecurityContributionEntry> out = new ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(mapSocialSecurityContribution(obj));
        }
        return out;
    }

    public void deleteSocialSecurityContribution(String id)
            throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/social-security/" + id).DELETE());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    private com.benjagest.ui.model.SocialSecurityContributionEntry
            mapSocialSecurityContribution(String obj) {
        return new com.benjagest.ui.model.SocialSecurityContributionEntry(
                textField(obj, "id"),
                textField(obj, "companyId"),
                textField(obj, "employeeId"),
                intField(obj, "periodYear"),
                intField(obj, "periodMonth"),
                textField(obj, "contributionType"),
                parseDecimal(textField(obj, "baseAmount")),
                parseDecimal(textField(obj, "contributionAmount")),
                textField(obj, "status"),
                parseInstant(textField(obj, "createdAt"))
        );
    }

    /** Parser numérico defensivo para BigDecimal desde texto JSON. */
    private static BigDecimal parseDecimal(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new BigDecimal(s.trim()); }
        catch (NumberFormatException ex) { return null; }
    }

    // ============================================================
    //  Bajas médicas (IT) — /api/labor/medical-leaves
    // ============================================================

    public List<com.benjagest.ui.model.MedicalLeaveEntry> listMedicalLeaves(
            String employeeId) throws IOException, InterruptedException {
        String url = baseUrl + "/labor/medical-leaves"
                + (employeeId == null || employeeId.isBlank()
                        ? "" : "?employeeId=" + employeeId);
        HttpResponse<String> r = send(req(url).GET());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        List<com.benjagest.ui.model.MedicalLeaveEntry> out = new ArrayList<>();
        for (String obj : splitTopLevelObjects(r.body())) {
            out.add(mapMedicalLeave(obj));
        }
        return out;
    }

    public com.benjagest.ui.model.MedicalLeaveEntry createMedicalLeave(
            String employeeId, String leaveType,
            java.time.LocalDate startDate, java.time.LocalDate endDate,
            String status, String notes)
            throws IOException, InterruptedException {
        String body = "{"
                + field("employeeId", employeeId) + ","
                + field("leaveType", leaveType) + ","
                + "\"startDate\":\"" + startDate.toString() + "\","
                + (endDate == null
                        ? "\"endDate\":null"
                        : "\"endDate\":\"" + endDate.toString() + "\"") + ","
                + (status == null || status.isBlank()
                        ? "\"status\":null"
                        : field("status", status)) + ","
                + field("notes", notes)
                + "}";
        HttpResponse<String> r = send(req(baseUrl + "/labor/medical-leaves")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return mapMedicalLeave(r.body());
    }

    public com.benjagest.ui.model.MedicalLeaveEntry updateMedicalLeave(
            String id, String leaveType,
            java.time.LocalDate startDate, java.time.LocalDate endDate,
            String status, String notes)
            throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder("{");
        if (leaveType != null) sb.append(field("leaveType", leaveType)).append(",");
        if (startDate != null) sb.append("\"startDate\":\"").append(startDate).append("\",");
        if (endDate != null) sb.append("\"endDate\":\"").append(endDate).append("\",");
        if (status != null) sb.append(field("status", status)).append(",");
        if (notes != null) sb.append(field("notes", notes)).append(",");
        if (sb.charAt(sb.length() - 1) == ',') sb.deleteCharAt(sb.length() - 1);
        sb.append("}");
        HttpResponse<String> r = send(req(baseUrl + "/labor/medical-leaves/" + id)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(sb.toString())));
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
        return mapMedicalLeave(r.body());
    }

    public void deleteMedicalLeave(String id) throws IOException, InterruptedException {
        HttpResponse<String> r = send(req(baseUrl + "/labor/medical-leaves/" + id).DELETE());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new IOException("HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    private com.benjagest.ui.model.MedicalLeaveEntry mapMedicalLeave(String obj) {
        return new com.benjagest.ui.model.MedicalLeaveEntry(
                textField(obj, "id"),
                textField(obj, "companyId"),
                textField(obj, "employeeId"),
                textField(obj, "leaveType"),
                parseLocalDate(textField(obj, "startDate")),
                parseLocalDate(textField(obj, "endDate")),
                textField(obj, "status"),
                textField(obj, "notes"),
                parseInstant(textField(obj, "createdAt")),
                parseInstant(textField(obj, "updatedAt"))
        );
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
                    String ht = textField(obj, "holidayType");
                    holidays.add(new com.benjagest.ui.model.HolidayPdfPreview.DetectedHoliday(
                            parseLocalDate(textField(obj, "date")),
                            textField(obj, "name"),
                            textField(obj, "scope"),
                            textField(obj, "confidence"),
                            textField(obj, "rawSourceLine"),
                            ht == null || ht.isBlank() ? "FESTIVO" : ht));
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
              .append(field("notes", h.notes())).append(",")
              .append(field("holidayType",
                      h.holidayType() == null ? "FESTIVO" : h.holidayType()))
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
        String ht = textField(obj, "holidayType");
        return new com.benjagest.ui.model.HolidayEntry(
                textField(obj, "id"),
                textField(obj, "workCalendarId"),
                parseLocalDate(textField(obj, "holidayDate")),
                textField(obj, "name"),
                textField(obj, "scope"),
                boolField(obj, "isPaid"),
                textField(obj, "notes"),
                ht == null || ht.isBlank() ? "FESTIVO" : ht,
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
