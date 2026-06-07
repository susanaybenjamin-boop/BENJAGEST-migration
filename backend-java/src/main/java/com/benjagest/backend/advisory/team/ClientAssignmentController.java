package com.benjagest.backend.advisory.team;

import com.benjagest.backend.auth.RequiresRole;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slice 5A — Endpoints REST del módulo EQUIPO / Reparto de clientes.
 *
 * <ul>
 *   <li>{@code GET /api/advisory/team/assignments} — solo OWNER, lista
 *       completa del reparto en su asesoría.</li>
 *   <li>{@code GET /api/advisory/team/assignments/mine} — cualquier
 *       miembro, IDs de clientes que ve hoy (incluye delegaciones).</li>
 *   <li>{@code POST /api/advisory/team/assignments} — solo OWNER.</li>
 *   <li>{@code PUT /api/advisory/team/assignments/{id}} — solo OWNER.</li>
 *   <li>{@code POST /api/advisory/team/assignments/{id}/delegate} —
 *       solo OWNER, delegación temporal por vacaciones/bajas.</li>
 *   <li>{@code DELETE /api/advisory/team/assignments/{id}} — solo OWNER.</li>
 * </ul>
 *
 * <p>El control fino "solo OWNER" lo hace el service consultando
 * company_memberships. Aquí el {@code @RequiresRole} se queda en
 * OWNER/ADMIN porque algunos endpoints pueden necesitarlo cuando se
 * añada la pantalla del administrador delegado.
 */
@RestController
@RequestMapping("/api/advisory/team/assignments")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "ADVISOR"})
public class ClientAssignmentController {

    private final ClientAssignmentService service;

    public ClientAssignmentController(ClientAssignmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClientAssignment> list() {
        return service.listForCurrentAdvisory();
    }

    /**
     * Slice 5B — Matriz para la UI del módulo Equipo: cada asignación
     * con su sub-lista de módulos. Solo OWNER.
     */
    @GetMapping("/with-modules")
    public List<ClientAssignmentService.AssignmentWithModules> listWithModules() {
        return service.listForCurrentAdvisoryWithModules();
    }

    @GetMapping("/mine")
    public Map<String, Object> mine() {
        return Map.of("clientIds", service.listMine());
    }

    /**
     * Slice 5B — Módulos que el user actual ve en {@code clientId}.
     * El sidebar del cliente filtra los items por este resultado.
     * Si la lista contiene "*" → mostrar todo el sidebar normal.
     */
    @GetMapping("/mine/modules/{clientId}")
    public Map<String, Object> myModulesInClient(@PathVariable("clientId") String clientId) {
        return Map.of("moduleSlugs", service.modulesVisibleInClient(clientId));
    }

    @PostMapping
    public ClientAssignment assign(@RequestBody ClientAssignmentService.AssignRequest req) {
        return service.assign(req);
    }

    /**
     * Slice 5B — Asignación en lote desde el listado de clientes con
     * checkboxes en la UI del módulo Equipo.
     */
    @PostMapping("/bulk")
    public ClientAssignmentService.BulkAssignResult bulkAssign(
            @RequestBody ClientAssignmentService.BulkAssignRequest req) {
        return service.bulkAssign(req);
    }

    @PutMapping("/{id}")
    public ClientAssignment update(@PathVariable("id") String id,
                                    @RequestBody ClientAssignmentService.UpdateRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/delegate")
    public ClientAssignment delegate(@PathVariable("id") String id,
                                      @RequestBody ClientAssignmentService.DelegateRequest req) {
        return service.delegate(id, req);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id) {
        service.delete(id);
        return Map.of("id", id, "deleted", true);
    }
}
