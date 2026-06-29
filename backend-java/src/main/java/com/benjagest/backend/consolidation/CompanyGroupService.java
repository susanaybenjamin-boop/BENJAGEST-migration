package com.benjagest.backend.consolidation;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.auth.RequiresRole;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CONSOL-1 — Grupos empresariales definidos por el usuario (cimiento de la
 * consolidación intragrupo). El dueño del grupo es la empresa activa del usuario
 * ({@code activeCompanyId} del JWT, estable aunque esté "actuando como" un
 * cliente). Los miembros son empresas cuyos libros consolidar (CONSOL-2..).
 */
@Service
public class CompanyGroupService {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUser;

    public CompanyGroupService(JdbcTemplate jdbc, CurrentUserService currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    private String ownerId() {
        return currentUser.require().activeCompanyId();
    }

    public List<GroupView> list() {
        return jdbc.query("""
                SELECT g.id, g.name,
                       (SELECT COUNT(*) FROM company_group_members m WHERE m.group_id = g.id) AS member_count
                  FROM company_groups g
                 WHERE g.owner_company_id = ? AND g.active = TRUE
                 ORDER BY g.name
                """, (rs, n) -> new GroupView(
                        rs.getString("id"), rs.getString("name"), rs.getInt("member_count")),
                ownerId());
    }

    public List<MemberView> membersOf(String groupId) {
        assertOwned(groupId);
        return membersOfInternal(groupId);
    }

    private List<MemberView> membersOfInternal(String groupId) {
        return jdbc.query("""
                SELECT m.company_id, c.legal_name, c.tax_identifier
                  FROM company_group_members m
                  JOIN companies c ON c.id = m.company_id
                 WHERE m.group_id = ?
                 ORDER BY c.legal_name
                """, (rs, n) -> new MemberView(
                        rs.getString("company_id"), rs.getString("legal_name"),
                        rs.getString("tax_identifier")), groupId);
    }

    @Transactional
    public GroupView create(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre del grupo es obligatorio");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO company_groups (id, owner_company_id, name) VALUES (?, ?, ?)",
                id, ownerId(), name.trim());
        return new GroupView(id, name.trim(), 0);
    }

    @Transactional
    public void delete(String groupId) {
        assertOwned(groupId);
        // ON DELETE CASCADE limpia los miembros.
        jdbc.update("DELETE FROM company_groups WHERE id = ? AND owner_company_id = ?", groupId, ownerId());
    }

    @Transactional
    public void addMember(String groupId, String companyId) {
        assertOwned(groupId);
        if (companyId == null || companyId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta la empresa");
        }
        // Seguridad: solo empresas que el usuario controla (la consolidación lee
        // sus libros directamente por company_id). Evita añadir un company_id ajeno.
        if (!isAccessible(companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No tienes acceso a esa empresa.");
        }
        // Idempotente: si ya está, no duplica (UK group_id+company_id).
        jdbc.update("""
                INSERT IGNORE INTO company_group_members (id, group_id, company_id)
                VALUES (?, ?, ?)
                """, UUID.randomUUID().toString(), groupId, companyId);
    }

    @Transactional
    public void removeMember(String groupId, String companyId) {
        assertOwned(groupId);
        jdbc.update("DELETE FROM company_group_members WHERE group_id = ? AND company_id = ?",
                groupId, companyId);
    }

    /**
     * Empresas que el usuario controla y puede meter en un grupo: la empresa
     * activa + las gestionadas por la asesoría ({@code parent_company_id}) + las
     * sociedades donde el usuario es miembro OWNER/ADMIN/ACCOUNTANT/ADVISOR (caso
     * empresario con varias sociedades). Así "Grupos" sirve en los dos modos.
     */
    public List<MemberView> candidates() {
        String owner = ownerId();
        String userId = currentUser.require().userId();
        return jdbc.query("""
                SELECT c.id, c.legal_name, c.tax_identifier
                  FROM companies c
                 WHERE c.active = TRUE
                   AND (c.id = ?
                     OR c.parent_company_id = ?
                     OR c.id IN (SELECT m.company_id FROM company_memberships m
                                  WHERE m.user_id = ? AND m.active = TRUE
                                    AND m.role_name IN ('OWNER','ADMIN','ACCOUNTANT','ADVISOR')))
                 ORDER BY c.legal_name
                """, (rs, n) -> new MemberView(
                        rs.getString("id"), rs.getString("legal_name"),
                        rs.getString("tax_identifier")), owner, owner, userId);
    }

    /** ¿El usuario controla esa empresa? (mismo criterio que {@link #candidates()}). */
    private boolean isAccessible(String companyId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM companies c
                 WHERE c.id = ? AND c.active = TRUE
                   AND (c.id = ?
                     OR c.parent_company_id = ?
                     OR c.id IN (SELECT m.company_id FROM company_memberships m
                                  WHERE m.user_id = ? AND m.active = TRUE
                                    AND m.role_name IN ('OWNER','ADMIN','ACCOUNTANT','ADVISOR')))
                """, Integer.class, companyId, ownerId(), ownerId(),
                currentUser.require().userId());
        return n != null && n > 0;
    }

    private void assertOwned(String groupId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM company_groups WHERE id = ? AND owner_company_id = ?",
                Integer.class, groupId, ownerId());
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Grupo no encontrado");
        }
    }

    public record GroupView(String id, String name, int memberCount) {}
    public record MemberView(String companyId, String legalName, String taxIdentifier) {}
    public record CreateRequest(String name) {}
    public record MemberRequest(String companyId) {}

    @RestController
    @RequestMapping("/api/company-groups")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class Controller {
        private final CompanyGroupService service;

        public Controller(CompanyGroupService service) { this.service = service; }

        @GetMapping
        public List<GroupView> list() { return service.list(); }

        @GetMapping("/candidates")
        public List<MemberView> candidates() { return service.candidates(); }

        @GetMapping("/{id}/members")
        public List<MemberView> members(@PathVariable("id") String id) { return service.membersOf(id); }

        @PostMapping
        public GroupView create(@RequestBody CreateRequest req) { return service.create(req.name()); }

        @DeleteMapping("/{id}")
        public void delete(@PathVariable("id") String id) { service.delete(id); }

        @PostMapping("/{id}/members")
        public void addMember(@PathVariable("id") String id, @RequestBody MemberRequest req) {
            service.addMember(id, req.companyId());
        }

        @DeleteMapping("/{id}/members/{companyId}")
        public void removeMember(@PathVariable("id") String id, @PathVariable("companyId") String companyId) {
            service.removeMember(id, companyId);
        }
    }
}
