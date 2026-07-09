package com.benjagest.backend.tenant;

import com.benjagest.backend.auth.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Interceptor que se ejecuta antes de cada controller para poner el
 * company_id activo en el TenantContext. Lee dos fuentes en orden:
 *
 *   1) El header X-Company-Id (la asesoría "actuando como cliente").
 *   2) El JWT validado por JwtAuthenticationFilter (claim
 *      activeCompanyId del usuario autenticado).
 *
 * Si ninguno esta presente, el TenantContext mantiene el fallback a
 * la empresa demo (RequestScopedTenantContext).
 *
 * <p>AUDIT-T1 (2026-07-09) — el header ya NO se confía a ciegas para
 * usuarios autenticados. Antes, cualquier usuario (p.ej. un empresario
 * OWNER de su propia empresa) podía poner X-Company-Id de OTRA empresa
 * y operar sobre ella con su rol propio. Ahora, si el header apunta a
 * una empresa distinta de la del JWT, se exige UNA de estas dos cosas:
 * <ul>
 *   <li>membership ACTIVA del usuario en esa empresa
 *       (company_memberships), o</li>
 *   <li>que esa empresa sea un cliente de la asesoría del usuario
 *       (companies.parent_company_id = su activeCompanyId — mismo
 *       patrón que CertificateService.verifyAdvisoryLink).</li>
 * </ul>
 * Con esto el empresario sigue entrando a lo suyo, la asesoría sigue
 * actuando sobre su cartera, y nadie salta a empresas ajenas.
 * (La validación de 2026-06-09 falló por comprobar SOLO memberships:
 * la asesoría no tiene membership en sus clientes. Esta incluye el
 * vínculo de cartera.)
 *
 * <p>Peticiones NO autenticadas conservan el comportamiento anterior
 * (el header se acepta): los endpoints protegidos ya exigen JWT, y los
 * públicos (login/register) no dependen del tenant.
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    private final TenantContext tenantContext;
    private final JdbcTemplate jdbcTemplate;

    public TenantInterceptor(TenantContext tenantContext, JdbcTemplate jdbcTemplate) {
        this.tenantContext = tenantContext;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader("X-Company-Id");
        AuthenticatedUser user = null;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser au) {
            user = au;
        }
        String jwtCompanyId = user == null ? null : user.activeCompanyId();

        if (header != null && !header.isBlank()) {
            String target = header.trim();
            if (user != null && !target.equals(jwtCompanyId)) {
                requireAccess(user, target);
            }
            tenantContext.setCurrentCompanyId(target);
            return true;
        }
        if (jwtCompanyId != null && !jwtCompanyId.isBlank()) {
            tenantContext.setCurrentCompanyId(jwtCompanyId);
        }
        return true;
    }

    /** 403 si el usuario no tiene membership en la empresa ni la lleva en cartera. */
    private void requireAccess(AuthenticatedUser user, String targetCompanyId) {
        Integer allowed = jdbcTemplate.queryForObject("""
                SELECT (SELECT COUNT(*) FROM company_memberships m
                         WHERE m.user_id = ? AND m.company_id = ? AND m.active = TRUE)
                     + (SELECT COUNT(*) FROM companies c
                         WHERE c.id = ? AND c.parent_company_id = ?)
                """, Integer.class,
                user.userId(), targetCompanyId,
                targetCompanyId, user.activeCompanyId());
        if (allowed == null || allowed == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No tienes acceso a esa empresa (ni membership ni vínculo de cartera).");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                  Object handler, Exception ex) {
        // Limpia el ThreadLocal para evitar fugas entre peticiones que
        // comparten thread en el pool de Tomcat.
        tenantContext.setCurrentCompanyId(null);
    }
}
