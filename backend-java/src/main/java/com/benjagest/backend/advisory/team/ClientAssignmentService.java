package com.benjagest.backend.advisory.team;

import com.benjagest.backend.audit.AuditService;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 5A — Lógica de asignaciones de cliente a empleado dentro de
 * una asesoría. Solo el OWNER de la asesoría puede gestionar el
 * reparto; el resto de empleados solo pueden listar las suyas
 * propias.
 *
 * <p>Cada operación de asignación queda firmada en {@code audit_events}
 * con el user_id del OWNER que la ejecutó (combinado con el hash
 * encadenado de AUDIT-CHAIN, la trazabilidad legal es completa).
 */
@Service
public class ClientAssignmentService {

    private final ClientAssignmentRepository repository;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    public ClientAssignmentService(ClientAssignmentRepository repository,
                                    TenantContext tenantContext,
                                    CurrentUserService currentUserService,
                                    JdbcTemplate jdbcTemplate,
                                    AuditService auditService) {
        this.repository = repository;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    /**
     * Lista todas las asignaciones de la asesoría logueada. Solo el
     * OWNER puede ver el reparto completo. Para el "yo mismo, ¿qué
     * me han asignado?", usar {@link #listMine}.
     */
    public List<ClientAssignment> listForCurrentAdvisory() {
        String advisoryId = tenantContext.getCurrentCompanyId();
        requireOwner(advisoryId);
        return repository.listByAdvisory(advisoryId);
    }

    /**
     * Devuelve las asignaciones (titularidad o delegación) que el
     * usuario actual ve hoy en su listado "Mis clientes". Sin
     * restricción de rol — cada empleado puede consultar SUS propios
     * clientes asignados.
     */
    public List<String> listMine() {
        String advisoryId = tenantContext.getCurrentCompanyId();
        String userId = currentUserService.require().userId();
        return repository.clientIdsVisibleForEmployee(advisoryId, userId);
    }

    /**
     * Crea una nueva asignación. Solo OWNER. Si el cliente ya estaba
     * asignado a OTRO empleado, lanza 409 — el OWNER debe primero
     * reasignar (update) o eliminar la asignación anterior.
     */
    @Transactional
    public ClientAssignment assign(AssignRequest req) {
        String advisoryId = tenantContext.getCurrentCompanyId();
        requireOwner(advisoryId);

        if (req.employeeUserId() == null || req.employeeUserId().isBlank()) {
            throw bad("employeeUserId obligatorio");
        }
        if (req.clientCompanyId() == null || req.clientCompanyId().isBlank()) {
            throw bad("clientCompanyId obligatorio");
        }
        validateEmployeeBelongsToAdvisory(req.employeeUserId(), advisoryId);
        validateClientBelongsToAdvisory(req.clientCompanyId(), advisoryId);

        // Bloqueo conflicto: solo puede haber UNA asignación activa
        // para el mismo cliente dentro de la asesoría. Si ya hay otra,
        // forzamos al OWNER a reasignar conscientemente.
        Optional<String> existing = jdbcTemplate.query("""
                SELECT id FROM client_assignments
                 WHERE advisory_company_id = ? AND client_company_id = ?
                   AND active = TRUE
                """,
                rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty(),
                advisoryId, req.clientCompanyId());
        if (existing != null && existing.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este cliente ya está asignado a otro empleado. Reasígnalo en lugar de duplicar.");
        }

        ClientAssignment a = new ClientAssignment(
                UUID.randomUUID().toString(),
                advisoryId,
                req.employeeUserId(),
                req.clientCompanyId(),
                req.roleInClient() == null || req.roleInClient().isBlank()
                        ? ClientAssignment.ROLE_ADVISOR : req.roleInClient(),
                null, // assigned_at default CURRENT_TIMESTAMP
                safeUserId(),
                true,
                null, null, null,
                blank(req.notes()),
                null, null);
        repository.insert(a);
        auditService.recordGeneric(advisoryId, safeUserId(),
                "CLIENT_ASSIGNMENT_CREATED",
                "client_assignment", a.id(), "OK",
                "{\"employee\":\"" + req.employeeUserId()
                        + "\",\"client\":\"" + req.clientCompanyId() + "\"}");
        return a;
    }

    /**
     * Reasigna: cambia el employee_user_id de una asignación existente
     * O modifica role/notes/active. Para activar/desactivar usar
     * {@link #setActive}. Solo OWNER.
     */
    @Transactional
    public ClientAssignment update(String id, UpdateRequest req) {
        String advisoryId = tenantContext.getCurrentCompanyId();
        requireOwner(advisoryId);
        ClientAssignment current = repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Asignación no encontrada"));
        if (!advisoryId.equals(current.advisoryCompanyId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "La asignación no pertenece a tu asesoría.");
        }
        String newEmp = req.employeeUserId() == null
                ? current.employeeUserId() : req.employeeUserId();
        if (!newEmp.equals(current.employeeUserId())) {
            validateEmployeeBelongsToAdvisory(newEmp, advisoryId);
        }
        ClientAssignment updated = new ClientAssignment(
                current.id(),
                current.advisoryCompanyId(),
                newEmp,
                current.clientCompanyId(),
                req.roleInClient() == null ? current.roleInClient() : req.roleInClient(),
                current.assignedAt(),
                current.assignedByUserId(),
                req.active() == null ? current.active() : req.active(),
                current.delegatedToUserId(),
                current.delegatedFrom(),
                current.delegatedUntil(),
                req.notes() == null ? current.notes() : blank(req.notes()),
                current.createdAt(),
                current.updatedAt());
        repository.update(updated);
        auditService.recordGeneric(advisoryId, safeUserId(),
                "CLIENT_ASSIGNMENT_UPDATED",
                "client_assignment", id, "OK", null);
        return updated;
    }

    /**
     * Delegación temporal de las asignaciones de un empleado a otro
     * por baja/vacaciones. El delegado verá los clientes durante el
     * rango. Pasar {@code toUserId=null} cancela la delegación.
     * Solo OWNER.
     */
    @Transactional
    public ClientAssignment delegate(String id, DelegateRequest req) {
        String advisoryId = tenantContext.getCurrentCompanyId();
        requireOwner(advisoryId);
        ClientAssignment current = repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Asignación no encontrada"));
        if (!advisoryId.equals(current.advisoryCompanyId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "La asignación no pertenece a tu asesoría.");
        }
        if (req.toUserId() != null && !req.toUserId().isBlank()) {
            if (req.toUserId().equals(current.employeeUserId())) {
                throw bad("El delegado no puede ser el mismo empleado titular.");
            }
            validateEmployeeBelongsToAdvisory(req.toUserId(), advisoryId);
            if (req.from() == null || req.until() == null) {
                throw bad("from y until obligatorios al delegar.");
            }
            if (req.from().isAfter(req.until())) {
                throw bad("from debe ser <= until.");
            }
        }
        ClientAssignment updated = new ClientAssignment(
                current.id(), current.advisoryCompanyId(),
                current.employeeUserId(), current.clientCompanyId(),
                current.roleInClient(), current.assignedAt(),
                current.assignedByUserId(), current.active(),
                blank(req.toUserId()),
                req.toUserId() == null || req.toUserId().isBlank() ? null : req.from(),
                req.toUserId() == null || req.toUserId().isBlank() ? null : req.until(),
                current.notes(),
                current.createdAt(), current.updatedAt());
        repository.update(updated);
        auditService.recordGeneric(advisoryId, safeUserId(),
                blank(req.toUserId()) == null
                        ? "CLIENT_ASSIGNMENT_DELEGATION_CANCELLED"
                        : "CLIENT_ASSIGNMENT_DELEGATED",
                "client_assignment", id, "OK", null);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        String advisoryId = tenantContext.getCurrentCompanyId();
        requireOwner(advisoryId);
        ClientAssignment current = repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Asignación no encontrada"));
        if (!advisoryId.equals(current.advisoryCompanyId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "La asignación no pertenece a tu asesoría.");
        }
        repository.delete(id);
        auditService.recordGeneric(advisoryId, safeUserId(),
                "CLIENT_ASSIGNMENT_DELETED",
                "client_assignment", id, "OK", null);
    }

    // ================================================================
    //  Helpers
    // ================================================================

    /**
     * TRUE si el user actual es OWNER de la asesoría logueada. Lo usa
     * AdvisoryService.listMyManagedClients para no filtrar la lista
     * (el OWNER ve todos los clientes siempre).
     */
    public boolean currentUserIsOwnerOfAdvisory(String advisoryCompanyId) {
        String userId = safeUserId();
        if (userId == null) return false;
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM company_memberships
                 WHERE company_id = ? AND user_id = ?
                   AND role_name = 'OWNER' AND active = TRUE
                """, Integer.class, advisoryCompanyId, userId);
        return count != null && count > 0;
    }

    private void requireOwner(String advisoryCompanyId) {
        if (!currentUserIsOwnerOfAdvisory(advisoryCompanyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo el OWNER de la asesoría puede gestionar el reparto de clientes.");
        }
    }

    private void validateEmployeeBelongsToAdvisory(String userId, String advisoryId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM company_memberships
                 WHERE company_id = ? AND user_id = ? AND active = TRUE
                """, Integer.class, advisoryId, userId);
        if (count == null || count == 0) {
            throw bad("El usuario no es miembro de esta asesoría.");
        }
    }

    private void validateClientBelongsToAdvisory(String clientCompanyId, String advisoryId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM companies
                 WHERE id = ? AND parent_company_id = ?
                """, Integer.class, clientCompanyId, advisoryId);
        if (count == null || count == 0) {
            throw bad("El cliente no pertenece a tu cartera (parent_company_id no coincide).");
        }
    }

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // ================================================================
    //  DTOs
    // ================================================================

    public record AssignRequest(
            String employeeUserId,
            String clientCompanyId,
            String roleInClient,
            String notes) {}

    public record UpdateRequest(
            String employeeUserId,
            String roleInClient,
            Boolean active,
            String notes) {}

    public record DelegateRequest(
            String toUserId,
            LocalDate from,
            LocalDate until) {}
}
