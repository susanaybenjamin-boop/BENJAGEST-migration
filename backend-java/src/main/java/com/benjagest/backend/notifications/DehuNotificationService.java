package com.benjagest.backend.notifications;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bandeja DEHu interna. Por ahora la creacion es manual — el operador
 * apunta cada notificacion que ha colgado el organismo. En el slice
 * siguiente engancharemos el SOAP de DEHu para pull automatico.
 *
 * El servicio tambien expone un endpoint summary que devuelve cuantas
 * notificaciones pendientes hay y cuantas van a caducar en 3 dias —
 * el dashboard lo pinta en un widget rojo si > 0.
 */
@Service
public class DehuNotificationService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;

    public DehuNotificationService(JdbcTemplate jdbcTemplate,
                                    TenantContext tenantContext,
                                    CurrentUserService currentUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
    }

    private String currentUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    public List<NotificationView> list(String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, dehu_id, nif_receiver, organism_name, organism_code,
                       procedure_name, procedure_code, subject,
                       issued_at, expires_at, accessed_at, read_at, read_by,
                       status, csv, content_url, local_pdf_path, notes,
                       created_at, updated_at
                  FROM dehu_notifications
                 WHERE company_id = ?
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (StringUtils.hasText(status)) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY issued_at DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        return jdbcTemplate.query(sql.toString(), this::mapView, args.toArray());
    }

    public Summary summary() {
        Integer pending = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dehu_notifications
                 WHERE company_id = ? AND status = 'PENDING'
                """, Integer.class, tenantContext.getCurrentCompanyId());
        Integer expiringSoon = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dehu_notifications
                 WHERE company_id = ?
                   AND status = 'PENDING'
                   AND expires_at IS NOT NULL
                   AND expires_at <= DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 3 DAY)
                """, Integer.class, tenantContext.getCurrentCompanyId());
        Integer expired = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dehu_notifications
                 WHERE company_id = ?
                   AND status IN ('PENDING','EXPIRED')
                   AND expires_at < CURRENT_TIMESTAMP
                """, Integer.class, tenantContext.getCurrentCompanyId());
        return new Summary(
                pending == null ? 0 : pending,
                expiringSoon == null ? 0 : expiringSoon,
                expired == null ? 0 : expired);
    }

    @Transactional
    public NotificationView create(UpsertRequest req) {
        if (!StringUtils.hasText(req.subject())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subject requerido");
        }
        if (!StringUtils.hasText(req.organismName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "organismName requerido");
        }
        String id = UUID.randomUUID().toString();
        String dehuId = StringUtils.hasText(req.dehuId()) ? req.dehuId() : "MANUAL-" + id.substring(0, 8);
        Instant issued = req.issuedAt() == null ? Instant.now() : req.issuedAt();
        // Plazo legal: 10 dias naturales desde la puesta a disposicion
        Instant expires = req.expiresAt() == null
                ? issued.plus(java.time.Duration.ofDays(10))
                : req.expiresAt();
        jdbcTemplate.update("""
                INSERT INTO dehu_notifications (
                    id, company_id, dehu_id, nif_receiver, organism_name, organism_code,
                    procedure_name, procedure_code, subject,
                    issued_at, expires_at, status, csv, content_url, notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantContext.getCurrentCompanyId(),
                dehuId, blank(req.nifReceiver()), req.organismName(),
                blank(req.organismCode()),
                blank(req.procedureName()), blank(req.procedureCode()),
                req.subject(), Timestamp.from(issued), Timestamp.from(expires),
                "PENDING", blank(req.csv()), blank(req.contentUrl()), blank(req.notes()));
        return findById(id);
    }

    @Transactional
    public NotificationView markRead(String id) {
        int n = jdbcTemplate.update("""
                UPDATE dehu_notifications
                   SET status = 'READ', read_at = CURRENT_TIMESTAMP,
                       accessed_at = COALESCE(accessed_at, CURRENT_TIMESTAMP),
                       read_by = ?
                 WHERE id = ? AND company_id = ? AND status IN ('PENDING','EXPIRED')
                """, currentUserId(), id, tenantContext.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notificacion no encontrada o ya leida");
        }
        return findById(id);
    }

    @Transactional
    public NotificationView dismiss(String id) {
        int n = jdbcTemplate.update("""
                UPDATE dehu_notifications
                   SET status = 'DISMISSED', read_by = ?, read_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND company_id = ?
                """, currentUserId(), id, tenantContext.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notificacion no encontrada");
        }
        return findById(id);
    }

    @Transactional
    public void updateNotes(String id, String notes) {
        int n = jdbcTemplate.update("""
                UPDATE dehu_notifications SET notes = ?
                 WHERE id = ? AND company_id = ?
                """, blank(notes), id, tenantContext.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notificacion no encontrada");
        }
    }

    @Transactional
    public void delete(String id) {
        int n = jdbcTemplate.update("""
                DELETE FROM dehu_notifications
                 WHERE id = ? AND company_id = ? AND status IN ('DISMISSED','EXPIRED')
                """, id, tenantContext.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se pueden borrar notificaciones descartadas o caducadas");
        }
    }

    /**
     * Tarea programable (futuro Scheduler): marca como AUTO_READ las
     * notificaciones cuyo plazo ha pasado sin haber sido abiertas.
     * Por ahora se invoca a demanda desde el endpoint de summary cuando
     * la UI lo solicita.
     */
    @Transactional
    public int markExpiredAsAutoRead() {
        return jdbcTemplate.update("""
                UPDATE dehu_notifications
                   SET status = 'AUTO_READ'
                 WHERE company_id = ? AND status = 'PENDING'
                   AND expires_at IS NOT NULL AND expires_at < CURRENT_TIMESTAMP
                """, tenantContext.getCurrentCompanyId());
    }

    private NotificationView findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, dehu_id, nif_receiver, organism_name, organism_code,
                       procedure_name, procedure_code, subject,
                       issued_at, expires_at, accessed_at, read_at, read_by,
                       status, csv, content_url, local_pdf_path, notes,
                       created_at, updated_at
                  FROM dehu_notifications
                 WHERE id = ? AND company_id = ?
                """, this::mapView, id, tenantContext.getCurrentCompanyId())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notificacion no encontrada"));
    }

    private String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    private NotificationView mapView(ResultSet rs, int rowNum) throws SQLException {
        return new NotificationView(
                rs.getString("id"),
                rs.getString("dehu_id"),
                rs.getString("nif_receiver"),
                rs.getString("organism_name"),
                rs.getString("organism_code"),
                rs.getString("procedure_name"),
                rs.getString("procedure_code"),
                rs.getString("subject"),
                toInstant(rs.getTimestamp("issued_at")),
                toInstant(rs.getTimestamp("expires_at")),
                toInstant(rs.getTimestamp("accessed_at")),
                toInstant(rs.getTimestamp("read_at")),
                rs.getString("read_by"),
                rs.getString("status"),
                rs.getString("csv"),
                rs.getString("content_url"),
                rs.getString("local_pdf_path"),
                rs.getString("notes"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private Instant toInstant(Timestamp t) { return t == null ? null : t.toInstant(); }

    public record NotificationView(
            String id, String dehuId, String nifReceiver,
            String organismName, String organismCode,
            String procedureName, String procedureCode, String subject,
            Instant issuedAt, Instant expiresAt, Instant accessedAt,
            Instant readAt, String readBy, String status,
            String csv, String contentUrl, String localPdfPath, String notes,
            Instant createdAt, Instant updatedAt
    ) {}

    public record UpsertRequest(
            String dehuId, String nifReceiver, String organismName, String organismCode,
            String procedureName, String procedureCode, String subject,
            Instant issuedAt, Instant expiresAt, String csv, String contentUrl, String notes
    ) {}

    public record Summary(int pending, int expiringSoon, int expired) {}

    @RestController
    @RequestMapping("/api/notifications/dehu")
    @RequiresModule("notifications")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class DehuController {
        private final DehuNotificationService service;

        public DehuController(DehuNotificationService service) { this.service = service; }

        @GetMapping
        public List<NotificationView> list(@RequestParam(value = "status", required = false) String status,
                                            @RequestParam(value = "limit", defaultValue = "200") int limit) {
            // Antes de listar, marcamos las pendientes caducadas como AUTO_READ
            // — la legislacion las considera dadas por leidas.
            service.markExpiredAsAutoRead();
            return service.list(status, limit);
        }

        @GetMapping("/summary")
        public Summary summary() {
            service.markExpiredAsAutoRead();
            return service.summary();
        }

        @PostMapping
        public NotificationView create(@RequestBody UpsertRequest req) { return service.create(req); }

        @PutMapping("/{id}/read")
        public NotificationView markRead(@PathVariable("id") String id) { return service.markRead(id); }

        @PutMapping("/{id}/dismiss")
        public NotificationView dismiss(@PathVariable("id") String id) { return service.dismiss(id); }

        @PutMapping("/{id}/notes")
        public void notes(@PathVariable("id") String id, @RequestBody NotesRequest req) {
            service.updateNotes(id, req.notes());
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable("id") String id) { service.delete(id); }

        public record NotesRequest(String notes) {}
    }
}
