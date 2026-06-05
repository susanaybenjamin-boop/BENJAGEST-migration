package com.benjagest.backend.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Fachada para escribir eventos de auditoria sin que cada caller tenga
 * que conocer la forma de la tabla. Los metodos record* son
 * idempotentes desde el punto de vista del flujo de negocio (un fallo
 * al insertar el evento NO debe romper la operacion principal — lo
 * tragamos como warning para no bloquear logins, guardados, etc.).
 *
 * IP y user agent se sacan del RequestContextHolder en cada llamada.
 * Si no hay request asociado (background task, test), quedan null.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public void recordLoginOk(String userId, String activeCompanyId) {
        write(activeCompanyId, userId, "LOGIN_OK", null, null, "OK", null);
    }

    public void recordLoginFail(String email, String reason) {
        // Sin companyId / userId porque el login fallo antes de resolverlos.
        write(null, null, "LOGIN_FAIL", "user_account", null, "FAIL",
                "{\"email\":\"" + escape(email) + "\",\"reason\":\"" + escape(reason) + "\"}");
    }

    public void recordCompanySwitched(String userId, String fromCompanyId, String toCompanyId) {
        write(toCompanyId, userId, "COMPANY_SWITCHED", "company", toCompanyId, "OK",
                fromCompanyId == null ? null : "{\"fromCompanyId\":\"" + escape(fromCompanyId) + "\"}");
    }

    public void recordModuleToggled(String userId, String companyId, String moduleSlug, boolean active) {
        write(companyId, userId,
                active ? "MODULE_ENABLED" : "MODULE_DISABLED",
                "module_catalog", moduleSlug, "OK", null);
    }

    public void recordCompanyDataUpdated(String userId, String companyId) {
        write(companyId, userId, "COMPANY_DATA_UPDATED", "company", companyId, "OK", null);
    }

    /**
     * Subida de certificado digital. byAdvisory=true cuando lo sube
     * una asesoría operando en nombre del cliente (V38).
     */
    public void recordCertificateUploaded(String userId, String companyId,
                                            String certificateId, boolean byAdvisory,
                                            String uploaderCompanyId) {
        String details = byAdvisory
                ? "{\"byAdvisory\":true,\"uploaderCompanyId\":\""
                        + escape(uploaderCompanyId) + "\"}"
                : null;
        write(companyId, userId, "CERTIFICATE_UPLOADED",
                "digital_certificate", certificateId, "OK", details);
    }

    public void recordCertificateDeleted(String userId, String companyId, String certificateId) {
        write(companyId, userId, "CERTIFICATE_DELETED",
                "digital_certificate", certificateId, "OK", null);
    }

    /**
     * Punto unico de escritura. Si la insercion falla, NO lanza:
     * loguear es deseable pero nunca debe tumbar la operacion principal.
     */
    private void write(String companyId, String userId, String eventType,
                       String entityType, String entityId, String result, String detailsJson) {
        try {
            HttpServletRequest request = currentRequest();
            String ip = request == null ? null : extractClientIp(request);
            String ua = request == null ? null : truncate(request.getHeader("User-Agent"), 500);

            repository.insert(new AuditEvent(
                    UUID.randomUUID().toString(),
                    companyId,
                    userId,
                    eventType,
                    entityType,
                    entityId,
                    result,
                    ip,
                    ua,
                    detailsJson,
                    null
            ));
        } catch (Exception ex) {
            // Tragamos: si auditoria falla no tiramos el login del usuario.
            // En produccion conviene wirearlo a un logger central; por
            // ahora System.err es suficiente para investigar en local.
            System.err.println("[audit] no se pudo escribir evento " + eventType + ": " + ex.getMessage());
        }
    }

    private HttpServletRequest currentRequest() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                return attrs.getRequest();
            }
        } catch (IllegalStateException ignored) {
            // sin scope de request (background)
        }
        return null;
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For puede traer varias IPs separadas por coma.
            int comma = forwarded.indexOf(',');
            return truncate((comma < 0 ? forwarded : forwarded.substring(0, comma)).trim(), 60);
        }
        return truncate(request.getRemoteAddr(), 60);
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
