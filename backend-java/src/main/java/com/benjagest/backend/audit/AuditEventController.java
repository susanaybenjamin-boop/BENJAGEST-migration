package com.benjagest.backend.audit;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Listado de eventos de auditoria de la empresa activa.
 *
 * Filtros opcionales:
 *   - eventType (LOGIN_OK, MODULE_DISABLED, etc.)
 *   - sinceIso (ISO-8601, p.ej. "2026-06-01T00:00:00Z")
 *   - limit (1-500, default 100)
 *
 * @RequiresModule("settings") + @RequiresRole(OWNER, ADMIN): vivir bajo
 * Configuracion como cuarta pestana evita exponer trazas a roles
 * inferiores.
 */
@RestController
@RequestMapping("/api/settings/audit-events")
@RequiresModule("settings")
@RequiresRole({"OWNER", "ADMIN"})
public class AuditEventController {

    private final AuditEventRepository repository;
    private final TenantContext tenantContext;

    public AuditEventController(AuditEventRepository repository, TenantContext tenantContext) {
        this.repository = repository;
        this.tenantContext = tenantContext;
    }

    @GetMapping
    public List<AuditEventResponse> list(@RequestParam(value = "eventType", required = false) String eventType,
                                         @RequestParam(value = "sinceIso", required = false) String sinceIso,
                                         @RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {
        Instant since = null;
        if (sinceIso != null && !sinceIso.isBlank()) {
            try {
                since = Instant.parse(sinceIso.trim());
            } catch (Exception ignored) {
                // Fecha invalida: la ignoramos en silencio en lugar de 400
                // para que el cliente pueda usar valores parciales.
            }
        }
        return repository.findForCompany(tenantContext.getCurrentCompanyId(), eventType, since, limit)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private AuditEventResponse toResponse(AuditEvent event) {
        return new AuditEventResponse(
                event.id(),
                event.companyId(),
                event.userId(),
                event.eventType(),
                event.entityType(),
                event.entityId(),
                event.result(),
                event.ipAddress(),
                event.userAgent(),
                event.details(),
                event.createdAt() == null ? null : event.createdAt().toString()
        );
    }
}
