package com.benjagest.backend.timeclock.kiosk;

import com.benjagest.backend.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * FM-2 — Valida la cabecera {@code KioskToken} de los endpoints de sesión del
 * kiosco ({@code /api/public/kiosk/**}, salvo {@code /activate}). El kiosco NO
 * tiene JWT de usuario: se autentica con el token secreto del dispositivo
 * (alta entropía → se guarda y compara por SHA-256, no bcrypt).
 *
 * <p>Si el token es válido, fija el {@code TenantContext} a la empresa del
 * dispositivo y deja el id del dispositivo en un atributo de la request para
 * que el controller lo use. Si no, responde 401.
 */
@Component
public class KioskTokenInterceptor implements HandlerInterceptor {

    public static final String ATTR_DEVICE_ID = "kioskDeviceId";

    private final KioskService kioskService;
    private final TenantContext tenantContext;

    public KioskTokenInterceptor(KioskService kioskService, TenantContext tenantContext) {
        this.kioskService = kioskService;
        this.tenantContext = tenantContext;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
        String token = request.getHeader("KioskToken");
        if (token == null || token.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Falta KioskToken.");
            return false;
        }
        KioskService.DeviceRef device = kioskService.resolveActiveByToken(token.trim());
        if (device == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Dispositivo de kiosco no válido o desactivado.");
            return false;
        }
        tenantContext.setCurrentCompanyId(device.companyId());
        request.setAttribute(ATTR_DEVICE_ID, device.id());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        tenantContext.setCurrentCompanyId(null);
    }
}
