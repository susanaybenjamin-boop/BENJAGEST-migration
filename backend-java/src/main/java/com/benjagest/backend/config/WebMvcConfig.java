package com.benjagest.backend.config;

import com.benjagest.backend.modules.ModuleAccessInterceptor;
import com.benjagest.backend.tenant.TenantInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registra los interceptors HTTP en este orden:
 *
 *   1) TenantInterceptor - lee X-Company-Id y lo pone en TenantContext.
 *   2) ModuleAccessInterceptor - lee @RequiresModule del handler y
 *      devuelve 403 si la empresa no tiene el modulo activo.
 *
 * El orden importa: el segundo necesita que el primero haya corrido.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;
    private final ModuleAccessInterceptor moduleAccessInterceptor;

    public WebMvcConfig(TenantInterceptor tenantInterceptor, ModuleAccessInterceptor moduleAccessInterceptor) {
        this.tenantInterceptor = tenantInterceptor;
        this.moduleAccessInterceptor = moduleAccessInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(moduleAccessInterceptor)
                .addPathPatterns("/api/**");
    }
}
