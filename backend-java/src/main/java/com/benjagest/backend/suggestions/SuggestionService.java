package com.benjagest.backend.suggestions;

import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * PORT-3 SUG — Servicio de sugerencias.
 *
 * <p>Buzón de mejoras, bugs y peticiones que el usuario envía al equipo
 * de soporte. Per-tenant: solo se ven las propias de la empresa activa.
 * No es ruido — el OWNER puede usarlo como changelog interno también
 * (estado=closed para descartar).
 *
 * <p>Análogo a CONTENDO {@code sugerencias_180} + sugerenciasController.
 */
@Service
public class SuggestionService {

    /** Categorías permitidas — espejo del UI de CONTENDO. */
    private static final java.util.Set<String> ALLOWED_CATEGORIES = java.util.Set.of(
            "general", "improvement", "module", "bug", "other");

    /** Estados permitidos. */
    private static final java.util.Set<String> ALLOWED_STATUSES = java.util.Set.of(
            "new", "read", "answered", "closed");

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final TenantContext tenantContext;

    public SuggestionService(JdbcTemplate jdbcTemplate,
                              CurrentUserService currentUserService,
                              TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.tenantContext = tenantContext;
    }

    private static final RowMapper<Suggestion> MAPPER = (rs, i) -> new Suggestion(
            rs.getString("id"),
            rs.getString("company_id"),
            rs.getString("created_by_user_id"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("category"),
            rs.getString("status"),
            rs.getString("answer"),
            rs.getString("answered_at"),
            rs.getString("answered_by_user_id"),
            rs.getString("created_at"));

    public List<Suggestion> listForCurrentCompany() {
        String companyId = tenantContext.getCurrentCompanyId();
        return jdbcTemplate.query("""
                SELECT id, company_id, created_by_user_id, title, description,
                       category, status, answer,
                       CAST(answered_at AS CHAR) AS answered_at,
                       answered_by_user_id,
                       CAST(created_at AS CHAR) AS created_at
                  FROM suggestions
                 WHERE company_id = ?
                 ORDER BY created_at DESC
                 LIMIT 500
                """, MAPPER, companyId);
    }

    @Transactional
    public Suggestion create(CreateRequest req) {
        AuthenticatedUser user = currentUserService.require();
        String companyId = tenantContext.getCurrentCompanyId();
        validateCreate(req);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO suggestions (id, company_id, created_by_user_id,
                                          title, description, category,
                                          status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'new', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id, companyId, user.userId(),
                req.title().trim(), req.description().trim(),
                normalizeCategory(req.category()));
        return findById(id);
    }

    @Transactional
    public Suggestion updateStatus(String id, String newStatus) {
        Suggestion s = findById(id);
        if (!ALLOWED_STATUSES.contains(newStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Estado inválido: " + newStatus);
        }
        jdbcTemplate.update(
                "UPDATE suggestions SET status = ? WHERE id = ?",
                newStatus, s.id());
        return findById(id);
    }

    @Transactional
    public void delete(String id) {
        Suggestion s = findById(id);
        jdbcTemplate.update("DELETE FROM suggestions WHERE id = ?", s.id());
    }

    private Suggestion findById(String id) {
        String companyId = tenantContext.getCurrentCompanyId();
        List<Suggestion> rows = jdbcTemplate.query("""
                SELECT id, company_id, created_by_user_id, title, description,
                       category, status, answer,
                       CAST(answered_at AS CHAR) AS answered_at,
                       answered_by_user_id,
                       CAST(created_at AS CHAR) AS created_at
                  FROM suggestions
                 WHERE id = ? AND company_id = ?
                """, MAPPER, id, companyId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sugerencia no encontrada");
        }
        return rows.get(0);
    }

    private static void validateCreate(CreateRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title obligatorio");
        }
        if (req.description() == null || req.description().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "description obligatorio");
        }
    }

    private static String normalizeCategory(String c) {
        if (c == null || c.isBlank()) return "general";
        String low = c.trim().toLowerCase();
        return ALLOWED_CATEGORIES.contains(low) ? low : "general";
    }

    // ============================================================
    //  DTOs
    // ============================================================

    public record Suggestion(
            String id,
            String companyId,
            String createdByUserId,
            String title,
            String description,
            String category,
            String status,
            String answer,
            String answeredAt,
            String answeredByUserId,
            String createdAt
    ) {}

    public record CreateRequest(String title, String description, String category) {}

    public record UpdateStatusRequest(String status) {}
}
