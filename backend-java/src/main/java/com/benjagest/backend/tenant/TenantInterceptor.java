package com.benjagest.backend.tenant;

import com.benjagest.backend.auth.AuthRepository;
import com.benjagest.backend.auth.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Interceptor que se ejecuta antes de cada controller para poner el
 * company_id activo en el TenantContext. Lee dos fuentes en orden:
 *
 *   1) El header X-Company-Id, PERO solo si el usuario autenticado
 *      tiene membership en esa empresa (defensa contra UI con caché
 *      obsoleto que apunte a una empresa donde el user ya no tiene
 *      acceso — bug Benjamin 2026-06-09: la UI mandaba X-Company-Id
 *      obsoleto y el backend lo aceptaba ciegamente → ningún
 *      endpoint devolvía datos del usuario logueado).
 *   2) El JWT validado por JwtAuthenticationFilter (claim
 *      activeCompanyId del usuario autenticado).
 *
 * Si ninguno esta presente, el TenantContext mantiene el fallback a
 * la empresa demo (RequestScopedTenantContext).
 *
 * Para curl/testing sin auth, el header SÍ se acepta (no hay JWT que
 * validar contra). Eso preserva la utilidad original del header.
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    private final TenantContext tenantContext;
    private final AuthRepository authRepository;

    public TenantInterceptor(TenantContext tenantContext,
                              AuthRepository authRepository) {
        this.tenantContext = tenantContext;
        this.authRepository = authRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = null;
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser u) {
            user = u;
        }

        String header = request.getHeader("X-Company-Id");
        if (header != null && !header.isBlank()) {
            String headerValue = header.trim();
            if (user == null) {
                // Sin auth (curl, test). Aceptamos el header como antes.
                tenantContext.setCurrentCompanyId(headerValue);
                return true;
            }
            // Auth presente: solo aceptar el header si el user tiene
            // membership en esa empresa. Si no, IGNORAR y caer al JWT.
            if (authRepository.findMembership(user.userId(), headerValue).isPresent()) {
                tenantContext.setCurrentCompanyId(headerValue);
                return true;
            }
            // Header inválido — log para diagnóstico.
            System.err.println("[TenantInterceptor] WARN: X-Company-Id="
                    + headerValue + " ignorado para user=" + user.email()
                    + " (sin membership) — usando activeCompanyId del JWT="
                    + user.activeCompanyId());
        }

        if (user != null && user.activeCompanyId() != null
                && !user.activeCompanyId().isBlank()) {
            tenantContext.setCurrentCompanyId(user.activeCompanyId());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                  Object handler, Exception ex) {
        // Limpia el ThreadLocal para evitar fugas entre peticiones que
        // comparten thread en el pool de Tomcat.
        tenantContext.setCurrentCompanyId(null);
    }
}
