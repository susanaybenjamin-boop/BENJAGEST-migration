package com.benjagest.backend.advisory.documents;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
 * Servicio de documentos compartidos asesoría↔cliente.
 *
 * <p>Reusa el mismo modelo de resolución de partes que
 * {@link com.benjagest.backend.advisory.messaging.AdvisoryMessageService}:
 * el tenant actual puede ser asesoría o cliente; la dirección del
 * documento se calcula desde el rol del tenant.
 *
 * <p>El archivo binario se guarda en disco vía el flujo de upload de
 * la UI (multipart) o el endpoint /upload con el path interno. Aquí
 * solo almacenamos metadata. La descarga real va por un endpoint
 * separado (reusable con el PdfViewer del cliente UI).
 */
@Service
public class AdvisoryDocumentService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final CurrentUserService currentUser;

    public AdvisoryDocumentService(JdbcTemplate jdbc, TenantContext tenant,
                                    CurrentUserService currentUser) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.currentUser = currentUser;
    }

    public List<AdvisoryDocument> listThread(String otherCompanyId) {
        ThreadParts p = resolveParts(otherCompanyId);
        return jdbc.query("""
                SELECT id, advisory_company_id, client_company_id, direction,
                       title, file_path, file_size_bytes, mime_type, status,
                       note, uploaded_by_user_id, reviewed_by_user_id,
                       reviewed_at, created_at
                  FROM advisory_documents
                 WHERE advisory_company_id = ? AND client_company_id = ?
                 ORDER BY created_at DESC
                """, MAPPER, p.advisoryId(), p.clientId());
    }

    /**
     * Lista documentos pendientes de revisión para el rol del tenant
     * actual. Si soy asesoría, devuelve los C2A en UPLOADED. Si soy
     * cliente, devuelve los A2C en UPLOADED.
     */
    public List<AdvisoryDocument> listPendingReview() {
        String me = tenant.getCurrentCompanyId();
        // Buscamos por ambos lados sin saber si soy advisory o client.
        // El filtro por status=UPLOADED y dirección correcta lo aplica
        // la subconsulta.
        return jdbc.query("""
                SELECT id, advisory_company_id, client_company_id, direction,
                       title, file_path, file_size_bytes, mime_type, status,
                       note, uploaded_by_user_id, reviewed_by_user_id,
                       reviewed_at, created_at
                  FROM advisory_documents
                 WHERE status = 'UPLOADED'
                   AND ((advisory_company_id = ? AND direction = 'C2A')
                     OR (client_company_id   = ? AND direction = 'A2C'))
                 ORDER BY created_at ASC
                """, MAPPER, me, me);
    }

    @Transactional
    public AdvisoryDocument register(String otherCompanyId, RegisterRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "title obligatorio");
        }
        if (req.filePath() == null || req.filePath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "filePath obligatorio");
        }
        ThreadParts p = resolveParts(otherCompanyId);
        AdvisoryDocument d = new AdvisoryDocument(
                UUID.randomUUID().toString(),
                p.advisoryId(),
                p.clientId(),
                p.direction(),
                req.title().trim(),
                req.filePath().trim(),
                req.fileSizeBytes() == null ? 0 : req.fileSizeBytes(),
                req.mimeType() == null || req.mimeType().isBlank()
                        ? "application/octet-stream" : req.mimeType().trim(),
                AdvisoryDocument.STATUS_UPLOADED,
                req.note() == null || req.note().isBlank() ? null : req.note().trim(),
                safeUserId(),
                null,
                null,
                Instant.now()
        );
        jdbc.update("""
                INSERT INTO advisory_documents
                       (id, advisory_company_id, client_company_id, direction,
                        title, file_path, file_size_bytes, mime_type, status,
                        note, uploaded_by_user_id, reviewed_by_user_id,
                        reviewed_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NOW())
                """,
                d.id(), d.advisoryCompanyId(), d.clientCompanyId(), d.direction(),
                d.title(), d.filePath(), d.fileSizeBytes(), d.mimeType(),
                d.status(), d.note(), d.uploadedByUserId());
        return d;
    }

    @Transactional
    public AdvisoryDocument review(String id, ReviewRequest req) {
        AdvisoryDocument current = getById(id);
        String newStatus = req.status();
        if (newStatus == null
                || !(newStatus.equals(AdvisoryDocument.STATUS_REVIEWED)
                  || newStatus.equals(AdvisoryDocument.STATUS_ACCEPTED)
                  || newStatus.equals(AdvisoryDocument.STATUS_REJECTED))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status debe ser REVIEWED | ACCEPTED | REJECTED");
        }
        // Si va a REJECTED, exigir nota.
        if (AdvisoryDocument.STATUS_REJECTED.equals(newStatus)
                && (req.note() == null || req.note().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Para rechazar, indica el motivo en note.");
        }
        String userId = safeUserId();
        jdbc.update("""
                UPDATE advisory_documents
                   SET status = ?, note = ?, reviewed_by_user_id = ?, reviewed_at = NOW()
                 WHERE id = ?
                """,
                newStatus,
                req.note() == null || req.note().isBlank()
                        ? current.note() : req.note().trim(),
                userId, current.id());
        return jdbc.query("""
                SELECT id, advisory_company_id, client_company_id, direction,
                       title, file_path, file_size_bytes, mime_type, status,
                       note, uploaded_by_user_id, reviewed_by_user_id,
                       reviewed_at, created_at
                  FROM advisory_documents
                 WHERE id = ?
                """, MAPPER, current.id()).get(0);
    }

    @Transactional
    public void delete(String id) {
        AdvisoryDocument d = getById(id);
        // Solo el uploader o el OWNER del lado emisor puede borrar.
        // Aquí simplificamos: si está en estado terminal (ACCEPTED),
        // bloqueamos el borrado por trazabilidad.
        if (AdvisoryDocument.STATUS_ACCEPTED.equals(d.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede eliminar un documento aceptado por trazabilidad.");
        }
        jdbc.update("DELETE FROM advisory_documents WHERE id = ?", id);
    }

    /** Público para uso desde {@code AdvisoryDocumentUploadController.download}. */
    public AdvisoryDocument findById(String id) {
        return getById(id);
    }

    private AdvisoryDocument getById(String id) {
        Optional<AdvisoryDocument> opt = jdbc.query("""
                SELECT id, advisory_company_id, client_company_id, direction,
                       title, file_path, file_size_bytes, mime_type, status,
                       note, uploaded_by_user_id, reviewed_by_user_id,
                       reviewed_at, created_at
                  FROM advisory_documents
                 WHERE id = ?
                """, MAPPER, id).stream().findFirst();
        AdvisoryDocument d = opt.orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento no encontrado"));
        String me = tenant.getCurrentCompanyId();
        // Verificar pertenencia: el tenant debe ser una de las dos
        // partes del thread.
        if (!me.equals(d.advisoryCompanyId()) && !me.equals(d.clientCompanyId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Documento no encontrado en tu canal.");
        }
        return d;
    }

    private ThreadParts resolveParts(String otherCompanyId) {
        String me = tenant.getCurrentCompanyId();
        if (me == null || me.isBlank() || otherCompanyId == null || otherCompanyId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tenant y otherCompanyId obligatorios");
        }
        Integer asAdvisory = jdbc.queryForObject("""
                SELECT COUNT(*) FROM companies
                 WHERE id = ? AND parent_company_id = ?
                """, Integer.class, otherCompanyId, me);
        if (asAdvisory != null && asAdvisory > 0) {
            return new ThreadParts(me, otherCompanyId, AdvisoryDocument.DIRECTION_A2C);
        }
        Integer asClient = jdbc.queryForObject("""
                SELECT COUNT(*) FROM companies
                 WHERE id = ? AND parent_company_id = ?
                """, Integer.class, me, otherCompanyId);
        if (asClient != null && asClient > 0) {
            return new ThreadParts(otherCompanyId, me, AdvisoryDocument.DIRECTION_C2A);
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "No hay relación asesoría-cliente con esa empresa.");
    }

    private String safeUserId() {
        try { return currentUser.require().userId(); }
        catch (Exception ex) { return null; }
    }

    private record ThreadParts(String advisoryId, String clientId, String direction) {}

    public record RegisterRequest(
            String title,
            String filePath,
            Long fileSizeBytes,
            String mimeType,
            String note
    ) {}

    public record ReviewRequest(String status, String note) {}

    private static final RowMapper<AdvisoryDocument> MAPPER = (rs, i) -> new AdvisoryDocument(
            rs.getString("id"),
            rs.getString("advisory_company_id"),
            rs.getString("client_company_id"),
            rs.getString("direction"),
            rs.getString("title"),
            rs.getString("file_path"),
            rs.getLong("file_size_bytes"),
            rs.getString("mime_type"),
            rs.getString("status"),
            rs.getString("note"),
            rs.getString("uploaded_by_user_id"),
            rs.getString("reviewed_by_user_id"),
            toInstant(rs.getTimestamp("reviewed_at")),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    @RestController
    @RequestMapping("/api/advisory/documents")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class Controller {
        private final AdvisoryDocumentService service;

        public Controller(AdvisoryDocumentService service) {
            this.service = service;
        }

        @GetMapping("/threads/{otherCompanyId}")
        public List<AdvisoryDocument> listThread(
                @PathVariable("otherCompanyId") String otherCompanyId) {
            return service.listThread(otherCompanyId);
        }

        @GetMapping("/pending-review")
        public List<AdvisoryDocument> listPendingReview() {
            return service.listPendingReview();
        }

        @PostMapping("/threads/{otherCompanyId}/register")
        public AdvisoryDocument register(
                @PathVariable("otherCompanyId") String otherCompanyId,
                @RequestBody RegisterRequest req) {
            return service.register(otherCompanyId, req);
        }

        @PostMapping("/{id}/review")
        public AdvisoryDocument review(@PathVariable("id") String id,
                                        @RequestBody ReviewRequest req) {
            return service.review(id, req);
        }

        @PostMapping("/{id}/delete")
        public void delete(@PathVariable("id") String id) {
            service.delete(id);
        }
    }
}
