package com.benjagest.backend.advisory.notifications;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Servicio de la bandeja de notificaciones del asesor.
 *
 * <p>Notificaciones internas que el asesor debe ver y descartar.
 * Distinto de {@code audit_events} (trail forense) — aquí solo hitos
 * accionables (vencimientos, uploads del cliente, invitaciones
 * aceptadas, etc.). Otros services (TaxFiling, Contract, Invitation)
 * llaman a {@link #emit(EmitRequest)} para crear filas; este servicio
 * provee la API REST de lectura/descarte.
 */
@Service
public class AdvisoryNotificationService {

    public static final String SEVERITY_INFO    = "INFO";
    public static final String SEVERITY_WARNING = "WARNING";
    public static final String SEVERITY_URGENT  = "URGENT";

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public AdvisoryNotificationService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    /** Listado de la bandeja del asesor: no descartadas, recientes primero. */
    public List<AdvisoryNotification> listInbox(boolean onlyUnread) {
        String companyId = tenant.getCurrentCompanyId();
        if (onlyUnread) {
            return jdbc.query("""
                    SELECT id, advisory_company_id, client_company_id, notification_type,
                           severity, title, message, entity_ref, read_at, dismissed_at,
                           created_at
                      FROM advisory_notifications
                     WHERE advisory_company_id = ?
                       AND read_at IS NULL AND dismissed_at IS NULL
                     ORDER BY created_at DESC
                     LIMIT 200
                    """, MAPPER, companyId);
        }
        return jdbc.query("""
                SELECT id, advisory_company_id, client_company_id, notification_type,
                       severity, title, message, entity_ref, read_at, dismissed_at,
                       created_at
                  FROM advisory_notifications
                 WHERE advisory_company_id = ?
                   AND dismissed_at IS NULL
                 ORDER BY created_at DESC
                 LIMIT 200
                """, MAPPER, companyId);
    }

    public int countUnread() {
        String companyId = tenant.getCurrentCompanyId();
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM advisory_notifications
                 WHERE advisory_company_id = ?
                   AND read_at IS NULL AND dismissed_at IS NULL
                """, Integer.class, companyId);
        return n == null ? 0 : n;
    }

    @Transactional
    public AdvisoryNotification emit(EmitRequest req) {
        if (req.advisoryCompanyId() == null || req.advisoryCompanyId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "advisoryCompanyId obligatorio");
        }
        if (req.title() == null || req.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title obligatorio");
        }
        if (req.notificationType() == null || req.notificationType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "notificationType obligatorio");
        }
        String severity = req.severity() == null || req.severity().isBlank()
                ? SEVERITY_INFO : req.severity();
        AdvisoryNotification n = new AdvisoryNotification(
                UUID.randomUUID().toString(),
                req.advisoryCompanyId(),
                req.clientCompanyId(),
                req.notificationType(),
                severity,
                req.title().trim(),
                req.message() == null || req.message().isBlank()
                        ? null : req.message().trim(),
                req.entityRef() == null || req.entityRef().isBlank()
                        ? null : req.entityRef().trim(),
                null,
                null,
                Instant.now()
        );
        jdbc.update("""
                INSERT INTO advisory_notifications
                       (id, advisory_company_id, client_company_id, notification_type,
                        severity, title, message, entity_ref, read_at, dismissed_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NOW())
                """,
                n.id(), n.advisoryCompanyId(), n.clientCompanyId(), n.notificationType(),
                n.severity(), n.title(), n.message(), n.entityRef());
        return n;
    }

    @Transactional
    public void markRead(String id) {
        AdvisoryNotification n = getById(id);
        if (n.readAt() != null) return;
        jdbc.update("""
                UPDATE advisory_notifications SET read_at = NOW() WHERE id = ?
                """, n.id());
    }

    @Transactional
    public void dismiss(String id) {
        AdvisoryNotification n = getById(id);
        if (n.dismissedAt() != null) return;
        jdbc.update("""
                UPDATE advisory_notifications SET dismissed_at = NOW() WHERE id = ?
                """, n.id());
    }

    @Transactional
    public int markAllRead() {
        return jdbc.update("""
                UPDATE advisory_notifications SET read_at = NOW()
                 WHERE advisory_company_id = ? AND read_at IS NULL
                """, tenant.getCurrentCompanyId());
    }

    private AdvisoryNotification getById(String id) {
        Optional<AdvisoryNotification> opt = jdbc.query("""
                SELECT id, advisory_company_id, client_company_id, notification_type,
                       severity, title, message, entity_ref, read_at, dismissed_at,
                       created_at
                  FROM advisory_notifications
                 WHERE id = ?
                """, MAPPER, id).stream().findFirst();
        AdvisoryNotification n = opt.orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Notificación no encontrada"));
        if (!n.advisoryCompanyId().equals(tenant.getCurrentCompanyId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notificación no encontrada en tu empresa");
        }
        return n;
    }

    public record EmitRequest(
            String advisoryCompanyId,
            String clientCompanyId,
            String notificationType,
            String severity,
            String title,
            String message,
            String entityRef
    ) {}

    private static final RowMapper<AdvisoryNotification> MAPPER = (rs, i) -> new AdvisoryNotification(
            rs.getString("id"),
            rs.getString("advisory_company_id"),
            rs.getString("client_company_id"),
            rs.getString("notification_type"),
            rs.getString("severity"),
            rs.getString("title"),
            rs.getString("message"),
            rs.getString("entity_ref"),
            toInstant(rs.getTimestamp("read_at")),
            toInstant(rs.getTimestamp("dismissed_at")),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    @RestController
    @RequestMapping("/api/advisory/notifications")
    @RequiresModule("advisory")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class Controller {
        private final AdvisoryNotificationService service;

        public Controller(AdvisoryNotificationService service) {
            this.service = service;
        }

        @GetMapping
        public List<AdvisoryNotification> list(
                @RequestParam(value = "onlyUnread", required = false, defaultValue = "false")
                boolean onlyUnread) {
            return service.listInbox(onlyUnread);
        }

        @GetMapping("/count-unread")
        public Map<String, Integer> countUnread() {
            return Map.of("unread", service.countUnread());
        }

        @PostMapping("/{id}/read")
        public void markRead(@PathVariable("id") String id) {
            service.markRead(id);
        }

        @PostMapping("/{id}/dismiss")
        public void dismiss(@PathVariable("id") String id) {
            service.dismiss(id);
        }

        @PostMapping("/mark-all-read")
        public Map<String, Integer> markAllRead() {
            return Map.of("markedRead", service.markAllRead());
        }
    }
}
