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
     * Slice 5B — Devuelve las asignaciones con sus módulos resueltos
     * (cada asignación + su sub-lista de slugs). Para la matriz del
     * módulo "Equipo" en la UI. Solo OWNER.
     */
    public List<AssignmentWithModules> listForCurrentAdvisoryWithModules() {
        List<ClientAssignment> rows = listForCurrentAdvisory();
        return rows.stream()
                .map(a -> new AssignmentWithModules(a,
                        repository.listModulesByAssignment(a.id())))
                .toList();
    }

    /**
     * Slice 5B — Módulos que el usuario actual ve en {@code clientId}.
     * Combina asignaciones titulares + delegaciones temporales activas.
     * Si una de ellas es "abierta" (sin módulos en assignment_modules)
     * devuelve {@link ClientAssignment#MODULE_ALL} → el caller abre el
     * sidebar entero.
     */
    public List<String> modulesVisibleInClient(String clientCompanyId) {
        String advisoryId = tenantContext.getCurrentCompanyId();
        String userId = currentUserService.require().userId();
        // El OWNER ve todos los módulos siempre
        if (currentUserIsOwnerOfAdvisory(advisoryId)) {
            return List.of(ClientAssignment.MODULE_ALL);
        }
        return repository.modulesVisibleForUserInClient(advisoryId, userId, clientCompanyId);
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
        // Slice 5B — Persistir los módulos de la asignación. Si la
        // lista viene null o vacía, queda "abierta" (todos los módulos
        // activos del cliente serán visibles para este empleado).
        if (req.moduleSlugs() != null && !req.moduleSlugs().isEmpty()) {
            repository.replaceModules(a.id(), req.moduleSlugs());
        }
        auditService.recordGeneric(advisoryId, safeUserId(),
                "CLIENT_ASSIGNMENT_CREATED",
                "client_assignment", a.id(), "OK",
                "{\"employee\":\"" + req.employeeUserId()
                        + "\",\"client\":\"" + req.clientCompanyId()
                        + "\",\"modules\":" + jsonArray(req.moduleSlugs()) + "}");
        return a;
    }

    /**
     * Slice 5B — Asignación "en lote" desde la UI del módulo "Equipo":
     * el OWNER marca con checkbox varios clientes en el listado y elige
     * qué módulos asigna a un empleado de un golpe. Se procesa cada
     * cliente como una llamada a {@link #assign}; si alguno falla con
     * 409 (ya asignado), se incluye en el resumen y el resto sigue.
     */
    @Transactional
    public BulkAssignResult bulkAssign(BulkAssignRequest req) {
        String advisoryId = tenantContext.getCurrentCompanyId();
        requireOwner(advisoryId);
        if (req.employeeUserId() == null || req.employeeUserId().isBlank()) {
            throw bad("employeeUserId obligatorio");
        }
        if (req.clientCompanyIds() == null || req.clientCompanyIds().isEmpty()) {
            throw bad("clientCompanyIds no puede estar vacío");
        }
        java.util.List<ClientAssignment> created = new java.util.ArrayList<>();
        java.util.List<String> skipped = new java.util.ArrayList<>();
        for (String clientId : req.clientCompanyIds()) {
            try {
                ClientAssignment a = assign(new AssignRequest(
                        req.employeeUserId(),
                        clientId,
                        req.roleInClient(),
                        req.moduleSlugs(),
                        req.notes()));
                created.add(a);
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode().value() == 409) {
                    skipped.add(clientId);
                } else {
                    throw ex;
                }
            }
        }
        return new BulkAssignResult(created.size(), created, skipped);
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
        // Slice 5B — Si la request trae moduleSlugs (puede ser lista
        // vacía para forzar "asignación abierta"), reemplazamos los
        // módulos. Si moduleSlugs es null, no tocamos los existentes.
        if (req.moduleSlugs() != null) {
            repository.replaceModules(id, req.moduleSlugs());
        }
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

    /**
     * Slice 5C / L4-5 — Lista los miembros del Equipo de la asesoría
     * logueada. Solo OWNER puede ver el listado completo.
     *
     * <p>Refactor L4-5 (2026-06-08): la fuente de la lista cambia de
     * {@code company_memberships} a {@code employees.app_access=TRUE
     * JOIN user_accounts}. Antes salían TODOS los users con membership
     * activa (incluido Marcos, que tiene membership pero no acceso a
     * la app); ahora solo los empleados con acceso real al login PIN
     * — los que el módulo Equipo necesita asignar. Marcos desaparece
     * del Equipo cuando su {@code app_access=FALSE} (queda en Personal).
     *
     * <p>El JOIN es LEFT outer porque hay un caso transitorio para el
     * OWNER: si el OWNER no tiene aún su fila employees (caso de
     * asesorías creadas antes de L4-4), su user_account con membership
     * OWNER sigue debiendo aparecer. Lo metemos con UNION del registro
     * OWNER vía company_memberships para no perder a quien actualmente
     * está logueado.
     */
    public List<TeamMember> listAdvisoryMembers() {
        String advisoryId = tenantContext.getCurrentCompanyId();
        requireOwner(advisoryId);
        // El UNION cubre dos casos:
        //   1) Empleados con app_access=TRUE (caso principal del módulo).
        //   2) OWNER de la asesoría (siempre presente aunque aún no
        //      tenga fila en employees — caso transitorio en asesorías
        //      anteriores a L4-4).
        // Envolvemos en una subquery para poder hacer ORDER BY con
        // expresión sobre el resultado del UNION (MariaDB exige
        // wrapping cuando el ORDER BY toca expresiones derivadas).
        return jdbcTemplate.query("""
                SELECT id, email, display_name, role_name, global_role, active
                  FROM (
                    SELECT u.id, u.email, u.display_name, m.role_name,
                           u.global_role, u.active
                      FROM employees e
                      JOIN user_accounts u ON u.id = e.user_id
                      JOIN company_memberships m ON m.user_id = u.id
                                                AND m.company_id = e.company_id
                     WHERE e.company_id = ?
                       AND e.app_access = TRUE
                       AND e.active = TRUE
                       AND u.active = TRUE
                       AND m.active = TRUE
                    UNION
                    SELECT u.id, u.email, u.display_name, m.role_name,
                           u.global_role, u.active
                      FROM company_memberships m
                      JOIN user_accounts u ON u.id = m.user_id
                     WHERE m.company_id = ?
                       AND m.role_name = 'OWNER'
                       AND m.active = TRUE
                       AND u.active = TRUE
                  ) AS team
                 ORDER BY (role_name = 'OWNER') DESC,
                          display_name ASC
                """, (rs, i) -> new TeamMember(
                rs.getString("id"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("role_name"),
                rs.getString("global_role"),
                rs.getBoolean("active")
        ), advisoryId, advisoryId);
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
                    "Solo el Propietario de la asesoría puede gestionar el reparto de clientes.");
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
            /** Lista de slugs de módulos. {@code null} o vacía = asignación
             *  abierta (todos los módulos del cliente). */
            List<String> moduleSlugs,
            String notes) {}

    public record UpdateRequest(
            String employeeUserId,
            String roleInClient,
            Boolean active,
            /** {@code null} = no tocar módulos. Lista vacía = pasar a
             *  "asignación abierta". Lista no vacía = reemplazar. */
            List<String> moduleSlugs,
            String notes) {}

    public record DelegateRequest(
            String toUserId,
            LocalDate from,
            LocalDate until) {}

    /** Slice 5B — Asignar en lote desde la UI con checkbox de clientes. */
    public record BulkAssignRequest(
            String employeeUserId,
            List<String> clientCompanyIds,
            String roleInClient,
            List<String> moduleSlugs,
            String notes) {}

    public record BulkAssignResult(
            int createdCount,
            List<ClientAssignment> created,
            /** clientCompanyIds que se saltaron porque ya tenían otra
             *  asignación activa para ese cliente (409). El OWNER decide
             *  si reasignar manualmente. */
            List<String> skippedAlreadyAssigned) {}

    /** Asignación + su lista de módulos para la matriz del OWNER. */
    public record AssignmentWithModules(
            ClientAssignment assignment,
            List<String> moduleSlugs) {}

    /**
     * Slice 5C — Miembro de la asesoría visible en la pestaña
     * "Empleados" del módulo Equipo. {@code roleName} es el rol en
     * company_memberships (OWNER, ADMIN, ACCOUNTANT…), no el rol que
     * después se le asigne en cada cliente concreto.
     */
    public record TeamMember(
            String userId,
            String email,
            String displayName,
            String roleName,
            String globalRole,
            boolean active) {}

    /** Serializa una List<String> a JSON inline para el details del audit. */
    private static String jsonArray(List<String> items) {
        if (items == null || items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(items.get(i).replace("\"", "\\\"")).append('"');
        }
        sb.append(']');
        return sb.toString();
    }
}
