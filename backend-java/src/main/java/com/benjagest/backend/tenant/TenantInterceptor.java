package com.benjagest.backend.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Interceptor que se ejecuta antes de cada controller. Lee el header
 * X-Company-Id de la peticion y lo deja en el TenantContext.
 *
 * Si el header falta o esta vacio, el TenantContext mantiene su
 * fallback a la empresa demo (definido en RequestScopedTenantContext).
 *
 * Registrado en WebMvcConfig para que se aplique a /api/**.
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
        }
        return true;
    }
}
