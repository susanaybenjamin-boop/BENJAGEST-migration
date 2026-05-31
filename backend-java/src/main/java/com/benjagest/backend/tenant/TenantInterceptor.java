package com.benjagest.backend.tenant;

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
 *   1) El header X-Company-Id (override de testing/curl).
 *   2) El JWT validado por JwtAuthenticationFilter (claim
 *      activeCompanyId del usuario autenticado).
 *
 * Si ninguno esta presente, el TenantContext mantiene el fallback a
 * la empresa demo (RequestScopedTenantContext).
 *
 * Orden de prioridad: el header SIEMPRE gana cuando esta presente.
 * Esto permite probar endpoints con curl simulando empresas
 * distintas sin necesidad de loguearse.
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    private final TenantContext tenantContext;

    public TenantInterceptor(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader("X-Company-Id");
        if (header != null && !header.isBlank()) {
            tenantContext.setCurrentCompanyId(header.trim());
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser user
                && user.activeCompanyId() != null
                && !user.activeCompanyId().isBlank()) {
            tenantContext.setCurrentCompanyId(user.activeCompanyId());
        }
        return true;
    }
}
