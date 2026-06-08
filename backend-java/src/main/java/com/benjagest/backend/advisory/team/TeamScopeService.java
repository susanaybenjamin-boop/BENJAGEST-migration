package com.benjagest.backend.advisory.team;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.tenant.TenantContext;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * EMP-SCOPED-UI (Slice 1) — Devuelve al UI el "scope" del usuario actual
 * dentro de la asesoría logueada.
 *
 * <p>El UI usa este scope para:
 * <ol>
 *   <li>Decidir si filtrar el sidebar (cuando {@code isFullAccess=false}).</li>
 *   <li>Filtrar la lista "Mis clientes" a {@code customerIds}.</li>
 *   <li>Pre-calcular qué módulos puede tocar en CUALQUIER cliente
 *       (unión {@code moduleSlugs}). Si la unión contiene
 *       {@link ClientAssignment#MODULE_ALL} significa que al menos una
 *       asignación es "abierta" (sin filas en assignment_modules) y
 *       todo el sidebar del cliente queda visible.</li>
 * </ol>
 *
 * <p>OWNER / ADMIN de la asesoría reciben {@code isFullAccess=true} y la
 * UI cortocircuita TODO el filtrado — siguen viendo el sidebar entero
 * y todos los clientes.
 *
 * <p>El endpoint admite los 4 roles (OWNER/ADMIN/ACCOUNTANT/ADVISOR)
 * porque cualquier empleado autenticado necesita consultar su propio
 * scope al cargar la UI. No se acepta usuario anónimo.
 */
@Service
public class TeamScopeService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final CurrentUserService currentUser;
    private final ClientAssignmentService assignmentService;

    public TeamScopeService(JdbcTemplate jdbc,
                            TenantContext tenant,
                            CurrentUserService currentUser,
                            ClientAssignmentService assignmentService) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.currentUser = currentUser;
        this.assignmentService = assignmentService;
    }

    public MyScope getMyScope() {
        String advisoryId = tenant.getCurrentCompanyId();
        String userId = currentUser.require().userId();

        // OWNER / ADMIN de la asesoría → acceso completo, no se filtra nada
        if (isAdvisoryOwnerOrAdmin(advisoryId, userId)) {
            return new MyScope(true, List.of(), List.of());
        }

        // Lista de clientes visibles (titularidad + delegación activa)
        List<String> customerIds = jdbc.queryForList("""
                SELECT DISTINCT client_company_id
                  FROM client_assignments
                 WHERE advisory_company_id = ?
                   AND active = TRUE
                   AND (
                     employee_user_id = ?
                     OR (delegated_to_user_id = ?
                         AND delegated_from <= CURRENT_DATE
                         AND delegated_until >= CURRENT_DATE)
                   )
                """, String.class, advisoryId, userId, userId);

        // Unión de módulos de TODAS las asignaciones activas del empleado.
        // Una asignación SIN filas en assignment_modules es "abierta" → todo
        // el sidebar visible. Detectamos eso con LEFT JOIN y COUNT=0 en una
        // subquery aparte.
        Set<String> moduleSlugs = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT DISTINCT am.module_slug
                  FROM client_assignments ca
                  JOIN assignment_modules am ON am.assignment_id = ca.id
                 WHERE ca.advisory_company_id = ?
                   AND ca.active = TRUE
                   AND (ca.employee_user_id = ?
                        OR (ca.delegated_to_user_id = ?
                            AND ca.delegated_from <= CURRENT_DATE
                            AND ca.delegated_until >= CURRENT_DATE))
                """, String.class, advisoryId, userId, userId));

        boolean hasOpenAssignment = jdbc.queryForObject("""
                SELECT COUNT(*) FROM client_assignments ca
                 WHERE ca.advisory_company_id = ?
                   AND ca.active = TRUE
                   AND (ca.employee_user_id = ?
                        OR (ca.delegated_to_user_id = ?
                            AND ca.delegated_from <= CURRENT_DATE
                            AND ca.delegated_until >= CURRENT_DATE))
                   AND NOT EXISTS (
                     SELECT 1 FROM assignment_modules am
                      WHERE am.assignment_id = ca.id
                   )
                """, Integer.class, advisoryId, userId, userId) > 0;
        if (hasOpenAssignment) {
            moduleSlugs.add(ClientAssignment.MODULE_ALL);
        }

        return new MyScope(false, customerIds, List.copyOf(moduleSlugs));
    }

    /**
     * TRUE si el usuario es OWNER o ADMIN de la asesoría logueada.
     * Ambos roles bypassean el filtrado scope.
     */
    private boolean isAdvisoryOwnerOrAdmin(String advisoryId, String userId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM company_memberships
                 WHERE company_id = ? AND user_id = ?
                   AND role_name IN ('OWNER', 'ADMIN')
                   AND active = TRUE
                """, Integer.class, advisoryId, userId);
        return n != null && n > 0;
    }

    public record MyScope(
            boolean isFullAccess,
            List<String> customerIds,
            List<String> moduleSlugs
    ) {
        public MyScope {
            customerIds = customerIds == null ? List.of() : List.copyOf(customerIds);
            moduleSlugs = moduleSlugs == null ? List.of() : List.copyOf(moduleSlugs);
        }
    }

    @RestController
    @RequestMapping("/api/team/me")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "ADVISOR"})
    public static class Controller {
        private final TeamScopeService service;

        public Controller(TeamScopeService service) {
            this.service = service;
        }

        @GetMapping("/scope")
        public MyScope myScope() {
            return service.getMyScope();
        }
    }
}
