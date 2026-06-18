package com.benjagest.backend.config;

import com.benjagest.backend.auth.RoleInterceptor;
import com.benjagest.backend.modules.ModuleAccessInterceptor;
import com.benjagest.backend.tenant.TenantInterceptor;
import com.benjagest.backend.timeclock.kiosk.KioskTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registra los interceptors HTTP en este orden:
 *
 *   1) TenantInterceptor - lee X-Company-Id y lo pone en TenantContext.
 *   2) ModuleAccessInterceptor - lee @RequiresModule del handler y
 *      devuelve 403 si la empresa no tiene el modulo activo.
 *   3) RoleInterceptor - lee @RequiresRole del handler y devuelve 403
 *      si el rol del usuario en la empresa no esta en la whitelist.
 *
 * El orden importa: el de modulo va antes que el de rol para que, si
 * la empresa no tiene el modulo, no exponga ni siquiera el chequeo de
 * rol como senal indirecta.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;
    private final ModuleAccessInterceptor moduleAccessInterceptor;
    private final RoleInterceptor roleInterceptor;
    private final KioskTokenInterceptor kioskTokenInterceptor;

    public WebMvcConfig(TenantInterceptor tenantInterceptor,
                        ModuleAccessInterceptor moduleAccessInterceptor,
                        RoleInterceptor roleInterceptor,
                        KioskTokenInterceptor kioskTokenInterceptor) {
        this.tenantInterceptor = tenantInterceptor;
        this.moduleAccessInterceptor = moduleAccessInterceptor;
        this.roleInterceptor = roleInterceptor;
        this.kioskTokenInterceptor = kioskTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(moduleAccessInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(roleInterceptor)
                .addPathPatterns("/api/**");
        // FM-2: valida el KioskToken de la sesión de kiosco y fija el tenant
        // desde el dispositivo. Va DESPUÉS de TenantInterceptor para ganar. No
        // aplica a /activate (aún no hay token).
        registry.addInterceptor(kioskTokenInterceptor)
                .addPathPatterns("/api/public/kiosk/**")
                .excludePathPatterns("/api/public/kiosk/activate");
    }
}
