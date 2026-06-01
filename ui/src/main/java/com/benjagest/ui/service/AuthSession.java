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
        }
        return builder;
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
        memberships = List.of();
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
}
