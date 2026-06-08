package com.benjagest.ui.service;

import com.benjagest.ui.model.Membership;
import java.net.http.HttpRequest;
import java.util.List;

/**
 * Singleton que guarda el estado de la sesion del usuario logueado:
 *   - access token (JWT) -> se anade automaticamente en cada
 *     llamada HTTP de los ApiClients.
 *   - refresh token -> se usa para renovar el access sin re-loguear.
 *   - snapshot del usuario y la empresa activa.
 *   - lista de memberships (para el selector de empresa).
 *
 * Todos los ApiClients (Customer, Issuer, Workspace, ModuleCatalog)
 * leen de aqui. La instancia se obtiene con AuthSession.get().
 *
 * Hilo unico: la UI JavaFX corre en su hilo dedicado, asi que no
 * hay condiciones de carrera reales hoy. Si en el futuro hay tareas
 * en background que tocan AuthSession, sincronizar.
 */
public final class AuthSession {

    private static volatile AuthSession instance;

    private String accessToken;
    private String refreshToken;
    private String userId;
    private String userDisplayName;
    private String userEmail;
    private String globalRole;
    private String activeCompanyId;
    private String activeCompanyLegalName;
    private String activeCompanyType;
    private String roleInActiveCompany;
    private List<Membership> memberships = List.of();

    /**
     * Override transitorio del X-Company-Id que se envía en el header.
     * Lo usa el modo asesoría: cuando un asesor abre la pantalla de
     * gestión de un cliente, las llamadas van con X-Company-Id=cliente
     * pero el activeCompanyId real (la asesoría) NO cambia. Así el
     * asesor nunca pierde su contexto y el sidebar sigue siendo el suyo.
     *
     * Cuando es null, se usa activeCompanyId (comportamiento normal).
     */
    private String actingForCompanyId;

    // EMP-SCOPED-UI — Scope del usuario en la asesoría logueada.
    // Cargado tras el login (BenjagestUiApplication.loadUserScope).
    // Si es null, todavía no se ha cargado o el endpoint falló — el UI
    // debe asumir "full access" defensivamente para no bloquear al
    // OWNER en caso de un transitorio.
    private UserScope userScope;

    private AuthSession() {
    }

    public static AuthSession get() {
        AuthSession local = instance;
        if (local == null) {
            synchronized (AuthSession.class) {
                if (instance == null) {
                    instance = new AuthSession();
                }
                local = instance;
            }
        }
        return local;
    }

    public boolean isAuthenticated() {
        return accessToken != null && !accessToken.isBlank();
    }

    /**
     * Anade el header Authorization: Bearer ... al builder si hay
     * sesion activa. Lo usan todos los ApiClients antes de construir
     * cada peticion.
     */
    public HttpRequest.Builder authorize(HttpRequest.Builder builder) {
        if (isAuthenticated()) {
            builder.header("Authorization", "Bearer " + accessToken);
            // X-Company-Id: si hay un override de "acting for" (asesor
            // gestionando un cliente) lo usamos. Si no, el activeCompanyId
            // normal de la sesión.
            String header = (actingForCompanyId != null && !actingForCompanyId.isBlank())
                    ? actingForCompanyId
                    : activeCompanyId;
            if (header != null && !header.isBlank()) {
                builder.header("X-Company-Id", header);
            }
        } else {
            // Diagnóstico: si una petición sale SIN token, es indicio de
            // que la sesión se reseteó (clear()) o que el ApiClient se
            // instanció antes de login. Imprimimos para detectarlo en
            // los logs del usuario cuando reporten un 403.
            System.err.println("[AuthSession] WARN: peticion saliendo SIN token "
                    + "(accessToken=null). activeCompanyId="
                    + (activeCompanyId == null ? "null" : activeCompanyId)
                    + " actingFor="
                    + (actingForCompanyId == null ? "null" : actingForCompanyId));
        }
        return builder;
    }

    /**
     * Variante de {@link #authorize} que IGNORA el override
     * {@code actingForCompanyId} y manda siempre el {@code activeCompanyId}
     * real de la sesión.
     *
     * <p>Pensado para llamadas que tienen que ejecutarse "como la
     * asesoría" incluso cuando la pantalla activa está operando en
     * nombre de un cliente vinculado. Caso típico: polling que detecta
     * cambios en la cartera (incluida la desvinculación del propio
     * cliente que se está viendo). Si esas llamadas usaran el
     * X-Company-Id del cliente, el endpoint {@code /api/advisory/...}
     * devolvería 403 (el cliente no tiene módulo advisory) y el polling
     * dejaría de detectar nada en silencio.
     */
    public HttpRequest.Builder authorizeAsOwner(HttpRequest.Builder builder) {
        if (isAuthenticated()) {
            builder.header("Authorization", "Bearer " + accessToken);
            if (activeCompanyId != null && !activeCompanyId.isBlank()) {
                builder.header("X-Company-Id", activeCompanyId);
            }
        }
        return builder;
    }

    /**
     * Cambia la empresa activa de la sesion en caliente. Lo usa el
     * switcher de cliente en el modulo Asesoria. No invalida el JWT
     * — solo redirige las peticiones siguientes al tenant indicado.
     */
    public void setActiveCompanyId(String companyId) {
        this.activeCompanyId = companyId;
    }

    /**
     * Setea el override "acting for" para que las próximas llamadas
     * envíen X-Company-Id={clientId} sin cambiar el activeCompanyId
     * real. Sirve a la asesoría para abrir la pantalla de un cliente.
     */
    public void setActingForCompanyId(String clientId) {
        this.actingForCompanyId = clientId;
    }

    public String getActingForCompanyId() {
        return actingForCompanyId;
    }

    public boolean isActingForClient() {
        return actingForCompanyId != null && !actingForCompanyId.isBlank();
    }

    public void update(
            String accessToken,
            String refreshToken,
            String userId,
            String userDisplayName,
            String userEmail,
            String globalRole,
            String activeCompanyId,
            String activeCompanyLegalName,
            String activeCompanyType,
            String roleInActiveCompany,
            List<Membership> memberships
    ) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.userId = userId;
        this.userDisplayName = userDisplayName;
        this.userEmail = userEmail;
        this.globalRole = globalRole;
        this.activeCompanyId = activeCompanyId;
        this.activeCompanyLegalName = activeCompanyLegalName;
        this.activeCompanyType = activeCompanyType;
        this.roleInActiveCompany = roleInActiveCompany;
        this.memberships = memberships == null ? List.of() : List.copyOf(memberships);
    }

    /**
     * Actualiza la razon social de la empresa activa sin tocar tokens
     * ni memberships. Lo usa el refresh silencioso tras editar la
     * pestana Empresa en Configuracion.
     */
    public void updateActiveCompanyLegalName(String newLegalName) {
        if (newLegalName == null || newLegalName.isBlank()) {
            return;
        }
        this.activeCompanyLegalName = newLegalName;
    }

    public void clear() {
        accessToken = null;
        refreshToken = null;
        userId = null;
        userDisplayName = null;
        userEmail = null;
        globalRole = null;
        activeCompanyId = null;
        activeCompanyLegalName = null;
        activeCompanyType = null;
        roleInActiveCompany = null;
        actingForCompanyId = null;
        memberships = List.of();
        userScope = null;
    }

    public String accessToken() { return accessToken; }
    public String refreshToken() { return refreshToken; }
    public String userId() { return userId; }
    public String userDisplayName() { return userDisplayName; }
    public String userEmail() { return userEmail; }
    public String globalRole() { return globalRole; }
    public String activeCompanyId() { return activeCompanyId; }
    public String activeCompanyLegalName() { return activeCompanyLegalName; }
    public String activeCompanyType() { return activeCompanyType; }
    public String roleInActiveCompany() { return roleInActiveCompany; }
    public List<Membership> memberships() { return memberships; }

    /**
     * EMP-SCOPED-UI — TRUE si el usuario es OWNER o ADMIN de la empresa
     * activa. Más confiable que userScope.isFullAccess() porque está
     * disponible inmediatamente tras el login, sin depender de un
     * endpoint async. Se usa en el sidebar para decidir si filtrar.
     */
    public boolean isOwnerOrAdmin() {
        if (roleInActiveCompany == null) return false;
        String r = roleInActiveCompany.toUpperCase();
        return "OWNER".equals(r) || "ADMIN".equals(r);
    }

    // ================================================================
    //  EMP-SCOPED-UI — Helpers de scope del usuario
    // ================================================================

    public void setUserScope(UserScope scope) { this.userScope = scope; }
    public UserScope userScope() { return userScope; }

    /**
     * TRUE si el usuario debe ver TODO el sidebar y todos los clientes.
     * Devuelve TRUE si:
     *   - El scope no se ha cargado todavía (defensivo — no bloquear
     *     al OWNER si el endpoint tardó o falló).
     *   - El backend reportó isFullAccess=true (OWNER o ADMIN).
     */
    public boolean isFullAccess() {
        return userScope == null || userScope.isFullAccess();
    }

    /**
     * TRUE si el usuario puede ver/usar un módulo concreto al entrar a un
     * cliente. Con isFullAccess=true siempre TRUE. En caso contrario,
     * TRUE solo si el slug está en la unión o si alguna asignación es
     * abierta (contiene "*").
     */
    public boolean canAccessModule(String moduleSlug) {
        if (isFullAccess()) return true;
        if (moduleSlug == null) return false;
        var slugs = userScope.moduleSlugs();
        return slugs.contains("*") || slugs.contains(moduleSlug);
    }

    /**
     * TRUE si el usuario puede ver un cliente concreto en "Mis clientes".
     * Con isFullAccess=true siempre TRUE.
     */
    public boolean canSeeCustomer(String customerCompanyId) {
        if (isFullAccess()) return true;
        if (customerCompanyId == null) return false;
        return userScope.customerIds().contains(customerCompanyId);
    }

    /**
     * Scope del usuario logueado dentro de su asesoría. Espejo del
     * DTO backend TeamScopeService.MyScope.
     */
    public record UserScope(
            boolean isFullAccess,
            List<String> customerIds,
            List<String> moduleSlugs
    ) {
        public UserScope {
            customerIds = customerIds == null ? List.of() : List.copyOf(customerIds);
            moduleSlugs = moduleSlugs == null ? List.of() : List.copyOf(moduleSlugs);
        }
    }
}
