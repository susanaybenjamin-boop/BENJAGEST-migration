package com.benjagest.backend.advisory.team;

import com.benjagest.backend.tenant.TenantContext;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Slice 5A — CRUD de {@link ClientAssignment} + queries para los dos
 * flujos clave:
 *
 * <ul>
 *   <li>"¿qué clientes ve este empleado?" — combina asignaciones
 *       activas + delegaciones temporales vigentes hoy.</li>
 *   <li>"¿qué reparto tiene esta asesoría?" — listado completo para
 *       la pantalla del OWNER (módulo Equipo).</li>
 * </ul>
 */
@Repository
public class ClientAssignmentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public ClientAssignmentRepository(JdbcTemplate jdbcTemplate,
                                       TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public void insert(ClientAssignment a) {
        jdbcTemplate.update("""
                INSERT INTO client_assignments (
                    id, advisory_company_id, employee_user_id,
                    client_company_id, role_in_client,
                    assigned_by_user_id, active,
                    delegated_to_user_id, delegated_from, delegated_until,
                    notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                a.id(), a.advisoryCompanyId(), a.employeeUserId(),
                a.clientCompanyId(), a.roleInClient(),
                a.assignedByUserId(), a.active(),
                a.delegatedToUserId(),
                a.delegatedFrom() == null ? null : Date.valueOf(a.delegatedFrom()),
                a.delegatedUntil() == null ? null : Date.valueOf(a.delegatedUntil()),
                a.notes());
    }

    public Optional<ClientAssignment> findById(String id) {
        try {
            ClientAssignment row = jdbcTemplate.queryForObject("""
                    SELECT * FROM client_assignments WHERE id = ?
                    """, this::map, id);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * Listado para la pantalla del OWNER. Filtra por la asesoría
     * actual (el OWNER está logueado en su propio tenant).
     */
    public List<ClientAssignment> listByAdvisory(String advisoryCompanyId) {
        return jdbcTemplate.query("""
                SELECT * FROM client_assignments
                 WHERE advisory_company_id = ?
                 ORDER BY active DESC, assigned_at DESC
                """, this::map, advisoryCompanyId);
    }

    /**
     * Devuelve los {@code client_company_id} que este empleado ve hoy.
     * Combina:
     *   - asignaciones activas como titular.
     *   - asignaciones de OTROS empleados donde este user es el
     *     delegado y la fecha actual está dentro del rango
     *     [delegated_from, delegated_until].
     *
     * Útil para AdvisoryService.listMyManagedClients() filtrado.
     */
    public List<String> clientIdsVisibleForEmployee(String advisoryCompanyId,
                                                     String employeeUserId) {
        LocalDate today = LocalDate.now();
        return jdbcTemplate.query("""
                SELECT DISTINCT client_company_id
                  FROM client_assignments
                 WHERE advisory_company_id = ?
                   AND active = TRUE
                   AND (
                     employee_user_id = ?
                     OR (delegated_to_user_id = ?
                         AND delegated_from <= ?
                         AND delegated_until >= ?)
                   )
                """,
                (rs, n) -> rs.getString("client_company_id"),
                advisoryCompanyId, employeeUserId,
                employeeUserId,
                Date.valueOf(today), Date.valueOf(today));
    }

    public void update(ClientAssignment a) {
        jdbcTemplate.update("""
                UPDATE client_assignments
                   SET employee_user_id = ?,
                       role_in_client = ?,
                       active = ?,
                       delegated_to_user_id = ?,
                       delegated_from = ?,
                       delegated_until = ?,
                       notes = ?
                 WHERE id = ?
                """,
                a.employeeUserId(), a.roleInClient(), a.active(),
                a.delegatedToUserId(),
                a.delegatedFrom() == null ? null : Date.valueOf(a.delegatedFrom()),
                a.delegatedUntil() == null ? null : Date.valueOf(a.delegatedUntil()),
                a.notes(), a.id());
    }

    public void delete(String id) {
        jdbcTemplate.update(
                "DELETE FROM client_assignments WHERE id = ?", id);
    }

    private ClientAssignment map(ResultSet rs, int n) throws SQLException {
        java.sql.Date df = rs.getDate("delegated_from");
        java.sql.Date du = rs.getDate("delegated_until");
        return new ClientAssignment(
                rs.getString("id"),
                rs.getString("advisory_company_id"),
                rs.getString("employee_user_id"),
                rs.getString("client_company_id"),
                rs.getString("role_in_client"),
                rs.getTimestamp("assigned_at").toInstant(),
                rs.getString("assigned_by_user_id"),
                rs.getBoolean("active"),
                rs.getString("delegated_to_user_id"),
                df == null ? null : df.toLocalDate(),
                du == null ? null : du.toLocalDate(),
                rs.getString("notes"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
