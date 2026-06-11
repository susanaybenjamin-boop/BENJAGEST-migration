package com.benjagest.backend.advisory.messaging;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Servicio de mensajería asesoría ↔ cliente.
 *
 * <p>Operaciones:
 * <ul>
 *   <li>{@code listThread(otherCompanyId)} — timeline cronológico de
 *       todos los mensajes con la otra parte (el "otro" se infiere por
 *       quién es el tenant actual: si soy asesoría, otherCompanyId es
 *       el cliente; si soy cliente, es la asesoría).</li>
 *   <li>{@code listThreadsForCurrent()} — listado de threads abiertos
 *       con el último mensaje + contador de no leídos, para el sidebar
 *       de "Bandeja de mensajes".</li>
 *   <li>{@code send(otherCompanyId, body)} — escribe un mensaje en el
 *       thread con la otra parte. La dirección la calcula el servicio.</li>
 *   <li>{@code markRead(otherCompanyId)} — marca todos los mensajes
 *       entrantes del thread como leídos (cuando el usuario abre el
 *       thread en el UI).</li>
 * </ul>
 *
 * <p>Detección de rol asesoría/cliente: se hace una SELECT contra
 * {@code companies.parent_company_id} para decidir. Si el tenant
 * actual es la asesoría y otherCompanyId es uno de sus clientes con
 * parent_company_id apuntando a ella → soy asesoría. Si el tenant
 * actual es el cliente y otherCompanyId es su parent → soy cliente.
 * Cualquier otro caso = 403.
 */
@Service
public class AdvisoryMessageService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final CurrentUserService currentUser;
    private final com.benjagest.backend.notifications.BusinessNotificationService businessNotif;
    private final com.benjagest.backend.advisory.notifications.AdvisoryNotificationService advisoryNotif;

    public AdvisoryMessageService(JdbcTemplate jdbc, TenantContext tenant,
                                    CurrentUserService currentUser,
                                    com.benjagest.backend.notifications.BusinessNotificationService businessNotif,
                                    com.benjagest.backend.advisory.notifications.AdvisoryNotificationService advisoryNotif) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.currentUser = currentUser;
        this.businessNotif = businessNotif;
        this.advisoryNotif = advisoryNotif;
    }

    /** Lista cronológica del thread con {@code otherCompanyId}. */
    public List<AdvisoryMessage> listThread(String otherCompanyId) {
        ThreadParts p = resolveParts(otherCompanyId);
        return jdbc.query("""
                SELECT id, advisory_company_id, client_company_id, direction,
                       from_user_id, body, attachment_path, read_at, created_at
                  FROM advisory_messages
                 WHERE advisory_company_id = ? AND client_company_id = ?
                 ORDER BY created_at ASC
                """, MAPPER, p.advisoryId(), p.clientId());
    }

    /**
     * Lista de threads abiertos del tenant actual con contador de no
     * leídos. Útil para el sidebar/inbox.
     */
    public List<ThreadSummary> listThreadsForCurrent() {
        String me = tenant.getCurrentCompanyId();
        // El tenant actual puede ser asesoría (advisory_company_id) o
        // cliente (client_company_id). La query agrega por la "otra
        // parte" para que cada thread aparezca una sola vez.
        return jdbc.query("""
                SELECT
                    CASE WHEN m.advisory_company_id = ?
                         THEN m.client_company_id ELSE m.advisory_company_id END
                        AS other_company_id,
                    MAX(m.created_at) AS last_at,
                    SUM(CASE
                        WHEN m.read_at IS NULL
                          AND ((m.advisory_company_id = ? AND m.direction = 'C2A')
                            OR (m.client_company_id   = ? AND m.direction = 'A2C'))
                        THEN 1 ELSE 0 END) AS unread_count,
                    SUM(1) AS total_count
                  FROM advisory_messages m
                 WHERE m.advisory_company_id = ? OR m.client_company_id = ?
                 GROUP BY other_company_id
                 ORDER BY last_at DESC
                """,
                (rs, i) -> new ThreadSummary(
                        rs.getString("other_company_id"),
                        toInstant(rs.getTimestamp("last_at")),
                        rs.getInt("unread_count"),
                        rs.getInt("total_count")
                ),
                me, me, me, me, me);
    }

    @Transactional
    public AdvisoryMessage send(String otherCompanyId, SendRequest req) {
        if (req.body() == null || req.body().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El mensaje no puede estar vacío");
        }
        ThreadParts p = resolveParts(otherCompanyId);
        AdvisoryMessage m = new AdvisoryMessage(
                UUID.randomUUID().toString(),
                p.advisoryId(),
                p.clientId(),
                p.direction(),
                safeUserId(),
                req.body().trim(),
                req.attachmentPath() == null || req.attachmentPath().isBlank()
                        ? null : req.attachmentPath().trim(),
                null,
                Instant.now()
        );
        jdbc.update("""
                INSERT INTO advisory_messages
                       (id, advisory_company_id, client_company_id, direction,
                        from_user_id, body, attachment_path, read_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NOW())
                """,
                m.id(), m.advisoryCompanyId(), m.clientCompanyId(), m.direction(),
                m.fromUserId(), m.body(), m.attachmentPath());
        // Hook 2026-06-11: notifica al destinatario en su bandeja.
        // A2C → notif al business (cliente); C2A → notif al advisory.
        // try/catch para que un fallo en notificación no aborte el
        // mensaje (el mensaje en BD es el dato canónico).
        try {
            String preview = m.body().length() > 80
                    ? m.body().substring(0, 80) + "…" : m.body();
            if (AdvisoryMessage.DIRECTION_A2C.equals(m.direction())) {
                businessNotif.emit(new com.benjagest.backend.notifications.BusinessNotificationService.EmitRequest(
                        m.clientCompanyId(),
                        m.advisoryCompanyId(),
                        "ADVISORY_MESSAGE",
                        com.benjagest.backend.notifications.BusinessNotificationService.SEVERITY_INFO,
                        "Mensaje de tu asesoría",
                        preview,
                        "advisory_message:" + m.id()));
            } else {
                advisoryNotif.emit(new com.benjagest.backend.advisory.notifications.AdvisoryNotificationService.EmitRequest(
                        m.advisoryCompanyId(),
                        m.clientCompanyId(),
                        "CLIENT_MESSAGE",
                        com.benjagest.backend.advisory.notifications.AdvisoryNotificationService.SEVERITY_INFO,
                        "Mensaje del cliente",
                        preview,
                        "advisory_message:" + m.id()));
            }
        } catch (Exception ignore) {
            // No bloquear el envío de mensajes por fallo de notif.
        }
        return m;
    }

    /**
     * Marca como leídos todos los mensajes ENTRANTES del thread del
     * tenant actual. Idempotente.
     */
    @Transactional
    public int markThreadRead(String otherCompanyId) {
        ThreadParts p = resolveParts(otherCompanyId);
        // Los "entrantes" son la dirección opuesta al rol del tenant.
        String incomingDir = AdvisoryMessage.DIRECTION_A2C.equals(p.direction())
                ? AdvisoryMessage.DIRECTION_C2A : AdvisoryMessage.DIRECTION_A2C;
        return jdbc.update("""
                UPDATE advisory_messages
                   SET read_at = NOW()
                 WHERE advisory_company_id = ? AND client_company_id = ?
                   AND direction = ? AND read_at IS NULL
                """, p.advisoryId(), p.clientId(), incomingDir);
    }

    /**
     * Resuelve el rol del tenant actual frente a {@code otherCompanyId}
     * y devuelve los ids correctos para advisory/client + la
     * dirección que llevaría un mensaje emitido por el tenant actual.
     *
     * <p>Lanza 403 si el tenant actual no tiene relación
     * asesoría-cliente con {@code otherCompanyId}.
     */
    private ThreadParts resolveParts(String otherCompanyId) {
        String me = tenant.getCurrentCompanyId();
        if (me == null || me.isBlank() || otherCompanyId == null || otherCompanyId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tenant y otherCompanyId obligatorios");
        }
        // Caso 1: yo soy asesoría, other es mi cliente.
        Integer asAdvisory = jdbc.queryForObject("""
                SELECT COUNT(*) FROM companies
                 WHERE id = ? AND parent_company_id = ?
                """, Integer.class, otherCompanyId, me);
        if (asAdvisory != null && asAdvisory > 0) {
            return new ThreadParts(me, otherCompanyId,
                    AdvisoryMessage.DIRECTION_A2C);
        }
        // Caso 2: yo soy cliente, other es mi asesoría.
        Integer asClient = jdbc.queryForObject("""
                SELECT COUNT(*) FROM companies
                 WHERE id = ? AND parent_company_id = ?
                """, Integer.class, me, otherCompanyId);
        if (asClient != null && asClient > 0) {
            return new ThreadParts(otherCompanyId, me,
                    AdvisoryMessage.DIRECTION_C2A);
        }
        // Caso 3 (especial Mi gestión): tenant es su propia asesoría
        // que se auto-link a sí misma. El thread con uno mismo no
        // tiene sentido — lo rechazamos limpio.
        if (me.equals(otherCompanyId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se puede enviar mensajes a tu propia empresa.");
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "No hay relación asesoría-cliente con esa empresa.");
    }

    private String safeUserId() {
        try { return currentUser.require().userId(); }
        catch (Exception ex) { return null; }
    }

    /** Estructura interna para el resolver — no se expone al cliente. */
    private record ThreadParts(String advisoryId, String clientId, String direction) {}

    public record ThreadSummary(
            String otherCompanyId,
            Instant lastAt,
            int unreadCount,
            int totalCount
    ) {}

    public record SendRequest(String body, String attachmentPath) {}

    private static final RowMapper<AdvisoryMessage> MAPPER = (rs, i) -> new AdvisoryMessage(
            rs.getString("id"),
            rs.getString("advisory_company_id"),
            rs.getString("client_company_id"),
            rs.getString("direction"),
            rs.getString("from_user_id"),
            rs.getString("body"),
            rs.getString("attachment_path"),
            toInstant(rs.getTimestamp("read_at")),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /**
     * Controller embebido. NO se pone @RequiresModule porque tanto
     * la asesoría como el cliente pueden mandar mensajes — si lo
     * limitásemos a "advisory", los clientes no podrían responder.
     * La autorización efectiva la hace resolveParts() comprobando la
     * relación asesoría-cliente en BD.
     */
    @RestController
    @RequestMapping("/api/advisory/messages")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class Controller {
        private final AdvisoryMessageService service;

        public Controller(AdvisoryMessageService service) {
            this.service = service;
        }

        @GetMapping("/threads")
        public List<ThreadSummary> listThreads() {
            return service.listThreadsForCurrent();
        }

        @GetMapping("/threads/{otherCompanyId}")
        public List<AdvisoryMessage> listThread(
                @PathVariable("otherCompanyId") String otherCompanyId) {
            return service.listThread(otherCompanyId);
        }

        @PostMapping("/threads/{otherCompanyId}/send")
        public AdvisoryMessage send(
                @PathVariable("otherCompanyId") String otherCompanyId,
                @RequestBody SendRequest req) {
            return service.send(otherCompanyId, req);
        }

        @PostMapping("/threads/{otherCompanyId}/mark-read")
        public Map<String, Integer> markRead(
                @PathVariable("otherCompanyId") String otherCompanyId) {
            return Map.of("markedRead", service.markThreadRead(otherCompanyId));
        }
    }
}
