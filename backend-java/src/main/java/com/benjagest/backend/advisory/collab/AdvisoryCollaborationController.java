package com.benjagest.backend.advisory.collab;

import com.benjagest.backend.auth.RequiresRole;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * L4-6 — Endpoints REST de colaboración entre asesorías.
 *
 * <ul>
 *   <li>{@code POST   /api/advisory/collaborations}            — A invita por email</li>
 *   <li>{@code GET    /api/advisory/collaborations/outgoing}   — A ve las que envió</li>
 *   <li>{@code GET    /api/advisory/collaborations/incoming}   — B ve las pendientes que le llegaron</li>
 *   <li>{@code GET    /api/advisory/collaborations/active}     — A ve sus colaboradores aceptados</li>
 *   <li>{@code POST   /api/advisory/collaborations/{id}/accept} — B acepta</li>
 *   <li>{@code POST   /api/advisory/collaborations/{id}/reject} — B rechaza</li>
 *   <li>{@code DELETE /api/advisory/collaborations/{id}}        — A revoca</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/advisory/collaborations")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "ADVISOR"})
public class AdvisoryCollaborationController {

    private final AdvisoryCollaborationService service;

    public AdvisoryCollaborationController(AdvisoryCollaborationService service) {
        this.service = service;
    }

    @PostMapping
    public AdvisoryCollaboration invite(
            @RequestBody AdvisoryCollaborationService.InviteRequest req) {
        return service.invite(req);
    }

    @GetMapping("/outgoing")
    public List<AdvisoryCollaboration> listOutgoing() {
        return service.listOutgoing();
    }

    @GetMapping("/incoming")
    public List<AdvisoryCollaboration> listIncoming() {
        return service.listIncoming();
    }

    @GetMapping("/active")
    public List<AdvisoryCollaboration> listActive() {
        return service.listActivePartners();
    }

    @PostMapping("/{id}/accept")
    public AdvisoryCollaboration accept(@PathVariable("id") String id) {
        return service.accept(id);
    }

    @PostMapping("/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable("id") String id) {
        service.reject(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> revoke(@PathVariable("id") String id) {
        service.revoke(id);
        return Map.of("id", id, "revoked", true);
    }
}
