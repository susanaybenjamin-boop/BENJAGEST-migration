package com.benjagest.backend.advisory.collab;

import com.benjagest.backend.audit.AuditService;
import com.benjagest.backend.auth.AuthRepository;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * L4-6 — Lógica de colaboración inter-asesoría.
 *
 * <p>Flujo:
 * <ol>
 *   <li>A (OWNER asesoría anfitriona) invita por email al OWNER de B.
 *       Si B aún no tiene cuenta en BENJAGEST, la invitación queda
 *       PENDING esperando registro.</li>
 *   <li>B (OWNER asesoría colaboradora) abre BENJAGEST, ve banner /
 *       sección "Invitaciones recibidas" y acepta o rechaza.</li>
 *   <li>Al aceptar, el sistema rellena {@code partner_advisory_id}
 *       con la asesoría OWNER del que acepta. Desde ese momento,
 *       A puede asignar clientes a empleados de B en
 *       {@code client_assignments} (L4-7 cierra la UI).</li>
 * </ol>
 *
 * <p>Solo OWNER puede invitar / revocar; el receptor que acepta
 * también debe ser OWNER de su propia asesoría (lo enforce el
 * service comprobando memberships).
 */
@Service
public class AdvisoryCollaborationService {

    private final AdvisoryCollaborationRepository repository;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;
    private final AuthRepository authRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    public AdvisoryCollaborationService(AdvisoryCollaborationRepository repository,
                                          TenantContext tenantContext,
                                          CurrentUserService currentUserService,
                                          AuthRepository authRepository,
                                          JdbcTemplate jdbcTemplate,
                                          AuditService auditService) {
        this.repository = repository;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.authRepository = authRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    // ============================================================
    //  Invitar (A → B)
    // ============================================================

    @Transactional
    public AdvisoryCollaboration invite(InviteRequest req) {
        String advisoryId = tenantContext.getCurrentCompanyId();
        requireOwner(advisoryId);

        String email = blank(req.email());
        if (email == null) {
            throw bad("Email obligatorio para invitar a una asesoría colaboradora.");
        }
        if (repository.existsActiveByEmail(advisoryId, email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya hay una invitación PENDING o ACCEPTED para ese email.");
        }

        String currentUserId = safeUserId();
        String id = UUID.randomUUID().toString();
        AdvisoryCollaboration entry = new AdvisoryCollaboration(
                id, advisoryId, null, email,
                AdvisoryCollaboration.STATUS_PENDING,
                null, currentUserId,
                null, null, null, null,
                blank(req.notes()),
                null, null);
        repository.insert(entry);

        auditService.recordGeneric(advisoryId, currentUserId,
                "ADVISORY_COLLAB_INVITED", "advisory_collaboration",
                id, "OK", "{\"email\":\"" + escape(email) + "\"}");
        return entry;
    }

    // ============================================================
    //  Aceptar / rechazar (B)
    // ============================================================

    @Transactional
    public AdvisoryCollaboration accept(String collaborationId) {
        var me = currentUserService.require();
        var collab = repository.findById(collaborationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Invitación no encontrada"));
        if (!AdvisoryCollaboration.STATUS_PENDING.equals(collab.status())) {
            throw bad("La invitación no está en estado PENDING.");
        }
        if (!sameEmail(collab.invitedEmail(), me.email())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Esta invitación no es para ti.");
        }
        // El receptor debe ser OWNER de una asesoría INTERNAL/ADVISORY.
        String partnerAdvisoryId = authRepository.findMembershipsForUser(me.userId()).stream()
                .filter(m -> "OWNER".equals(m.roleName()))
                .filter(m -> "INTERNAL".equalsIgnoreCase(m.companyType())
                        || "ADVISORY".equalsIgnoreCase(m.companyType()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Solo el OWNER de una asesoría puede aceptar colaboraciones."))
                .companyId();

        repository.accept(collaborationId, partnerAdvisoryId, me.userId());
        auditService.recordGeneric(collab.advisoryCompanyId(), me.userId(),
                "ADVISORY_COLLAB_ACCEPTED", "advisory_collaboration",
                collaborationId, "OK", null);
        return repository.findById(collaborationId).orElseThrow();
    }

    @Transactional
    public void reject(String collaborationId) {
        var me = currentUserService.require();
        var collab = repository.findById(collaborationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Invitación no encontrada"));
        if (!AdvisoryCollaboration.STATUS_PENDING.equals(collab.status())) {
            throw bad("La invitación no está en estado PENDING.");
        }
        if (!sameEmail(collab.invitedEmail(), me.email())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Esta invitación no es para ti.");
        }
        repository.reject(collaborationId, me.userId());
        auditService.recordGeneric(collab.advisoryCompanyId(), me.userId(),
                "ADVISORY_COLLAB_REJECTED", "advisory_collaboration",
                collaborationId, "OK", null);
    }

    // ============================================================
    //  Revocar (A — anfitrión)
    // ============================================================

    @Transactional
    public void revoke(String collaborationId) {
        String advisoryId = tenantContext.getCurrentCompanyId();
        requireOwner(advisoryId);
        var collab = repository.findById(collaborationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Colaboración no encontrada"));
        if (!advisoryId.equals(collab.advisoryCompanyId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "La colaboración no pertenece a tu asesoría.");
        }
        repository.revoke(collaborationId, safeUserId());
        auditService.recordGeneric(advisoryId, safeUserId(),
                "ADVISORY_COLLAB_REVOKED", "advisory_collaboration",
                collaborationId, "OK", null);
    }

    // ============================================================
    //  Listados
    // ============================================================

    public List<AdvisoryCollaboration> listOutgoing() {
        String advisoryId = tenantContext.getCurrentCompanyId();
        requireOwner(advisoryId);
        return repository.listOutgoing(advisoryId);
    }

    public List<AdvisoryCollaboration> listIncoming() {
        var me = currentUserService.require();
        if (me.email() == null || me.email().isBlank()) return List.of();
        return repository.listIncoming(me.email());
    }

    public List<AdvisoryCollaboration> listActivePartners() {
        String advisoryId = tenantContext.getCurrentCompanyId();
        requireOwner(advisoryId);
        return repository.listActivePartners(advisoryId);
    }

    /**
     * L4-7 helper — IDs de las asesorías colaboradoras activas para A.
     * El módulo Equipo lo usará para extender el combo "Asignar a:"
     * con los empleados de esas asesorías.
     */
    public List<String> listActivePartnerIds() {
        return listActivePartners().stream()
                .map(AdvisoryCollaboration::partnerAdvisoryId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private void requireOwner(String advisoryCompanyId) {
        String userId = safeUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sesión inválida.");
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM company_memberships
                 WHERE company_id = ? AND user_id = ?
                   AND role_name = 'OWNER' AND active = TRUE
                """, Integer.class, advisoryCompanyId, userId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo el OWNER de la asesoría puede gestionar colaboraciones.");
        }
    }

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static boolean sameEmail(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ============================================================
    //  DTOs
    // ============================================================

    public record InviteRequest(String email, String notes) {}
}
