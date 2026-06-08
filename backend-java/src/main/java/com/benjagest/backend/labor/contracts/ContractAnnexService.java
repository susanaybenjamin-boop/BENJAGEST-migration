package com.benjagest.backend.labor.contracts;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CTR-7 — Anexos al contrato.
 *
 * <p>Combina dos tipos de "anexos":
 * <ul>
 *   <li>Cláusulas del catálogo (built-in + custom del OWNER) vinculadas
 *       al contrato — tabla {@code contract_clause_links}.</li>
 *   <li>Texto libre redactado a mano por el OWNER al firmar un contrato
 *       concreto — tabla {@code contract_free_clauses}.</li>
 * </ul>
 *
 * <p>Multi-tenant via {@code employment_contracts.company_id}: cada query
 * verifica que el contrato pertenece al tenant actual antes de modificar.
 */
@Service
public class ContractAnnexService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public ContractAnnexService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    // ===== Cláusulas del catálogo linkeadas =================================

    public List<LinkedClause> listLinked(String contractId) {
        ensureContractOwned(contractId);
        return jdbc.query("""
                SELECT cl.id AS link_id, ct.id AS clause_id, ct.code, ct.title, ct.category, ct.body, cl.sort_order
                  FROM contract_clause_links cl
                  JOIN contract_clause_templates ct ON ct.id = cl.clause_template_id
                 WHERE cl.contract_id = ?
                 ORDER BY cl.sort_order, ct.title
                """, this::mapLinked, contractId);
    }

    @Transactional
    public LinkedClause link(String contractId, LinkRequest req) {
        ensureContractOwned(contractId);
        if (req.clauseTemplateId() == null || req.clauseTemplateId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clauseTemplateId requerido");
        }
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO contract_clause_links (id, contract_id, clause_template_id, sort_order)
                    VALUES (?, ?, ?, ?)
                    """, id, contractId, req.clauseTemplateId(),
                    req.sortOrder() == null ? 100 : req.sortOrder());
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta cláusula ya está vinculada al contrato");
        }
        return jdbc.query("""
                SELECT cl.id AS link_id, ct.id AS clause_id, ct.code, ct.title, ct.category, ct.body, cl.sort_order
                  FROM contract_clause_links cl
                  JOIN contract_clause_templates ct ON ct.id = cl.clause_template_id
                 WHERE cl.id = ?
                """, this::mapLinked, id).stream().findFirst().orElseThrow();
    }

    @Transactional
    public void unlink(String contractId, String linkId) {
        ensureContractOwned(contractId);
        int n = jdbc.update("DELETE FROM contract_clause_links WHERE id = ? AND contract_id = ?",
                linkId, contractId);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vínculo no encontrado");
        }
    }

    // ===== Cláusulas libres redactadas a mano ===============================

    public List<FreeClause> listFree(String contractId) {
        ensureContractOwned(contractId);
        return jdbc.query("""
                SELECT id, title, body, sort_order
                  FROM contract_free_clauses
                 WHERE contract_id = ?
                 ORDER BY sort_order, title
                """, this::mapFree, contractId);
    }

    @Transactional
    public FreeClause addFree(String contractId, FreeUpsert req) {
        ensureContractOwned(contractId);
        validateFree(req);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO contract_free_clauses (id, contract_id, title, body, sort_order)
                VALUES (?, ?, ?, ?, ?)
                """, id, contractId, req.title(), req.body(),
                req.sortOrder() == null ? 200 : req.sortOrder());
        return findFree(id);
    }

    @Transactional
    public FreeClause updateFree(String contractId, String id, FreeUpsert req) {
        ensureContractOwned(contractId);
        validateFree(req);
        int n = jdbc.update("""
                UPDATE contract_free_clauses
                   SET title = ?, body = ?, sort_order = ?
                 WHERE id = ? AND contract_id = ?
                """, req.title(), req.body(),
                req.sortOrder() == null ? 200 : req.sortOrder(),
                id, contractId);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cláusula libre no encontrada");
        }
        return findFree(id);
    }

    @Transactional
    public void deleteFree(String contractId, String id) {
        ensureContractOwned(contractId);
        int n = jdbc.update("DELETE FROM contract_free_clauses WHERE id = ? AND contract_id = ?",
                id, contractId);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cláusula libre no encontrada");
        }
    }

    // ===== Helpers ==========================================================

    private FreeClause findFree(String id) {
        return jdbc.query("""
                SELECT id, title, body, sort_order FROM contract_free_clauses WHERE id = ?
                """, this::mapFree, id).stream().findFirst().orElseThrow();
    }

    private void ensureContractOwned(String contractId) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM employment_contracts WHERE id = ? AND company_id = ?",
                Integer.class, contractId, tenant.getCurrentCompanyId());
        if (cnt == null || cnt == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contrato no encontrado");
        }
    }

    private void validateFree(FreeUpsert req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El título es obligatorio");
        }
        if (req.body() == null || req.body().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El cuerpo de la cláusula es obligatorio");
        }
    }

    private LinkedClause mapLinked(ResultSet rs, int n) throws SQLException {
        return new LinkedClause(
                rs.getString("link_id"),
                rs.getString("clause_id"),
                rs.getString("code"),
                rs.getString("title"),
                rs.getString("category"),
                rs.getString("body"),
                rs.getInt("sort_order")
        );
    }

    private FreeClause mapFree(ResultSet rs, int n) throws SQLException {
        return new FreeClause(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getInt("sort_order")
        );
    }

    public record LinkedClause(
            String linkId, String clauseId, String code, String title,
            String category, String body, int sortOrder
    ) {}

    public record FreeClause(String id, String title, String body, int sortOrder) {}

    public record LinkRequest(String clauseTemplateId, Integer sortOrder) {}

    public record FreeUpsert(String title, String body, Integer sortOrder) {}

    @RestController
    @RequestMapping("/api/contracts/{contractId}/annexes")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class Controller {
        private final ContractAnnexService service;

        public Controller(ContractAnnexService service) { this.service = service; }

        @GetMapping("/linked")
        public List<LinkedClause> listLinked(@PathVariable("contractId") String contractId) {
            return service.listLinked(contractId);
        }

        @PostMapping("/linked")
        public LinkedClause link(@PathVariable("contractId") String contractId,
                                 @RequestBody LinkRequest req) {
            return service.link(contractId, req);
        }

        @DeleteMapping("/linked/{linkId}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void unlink(@PathVariable("contractId") String contractId,
                           @PathVariable("linkId") String linkId) {
            service.unlink(contractId, linkId);
        }

        @GetMapping("/free")
        public List<FreeClause> listFree(@PathVariable("contractId") String contractId) {
            return service.listFree(contractId);
        }

        @PostMapping("/free")
        public FreeClause addFree(@PathVariable("contractId") String contractId,
                                  @RequestBody FreeUpsert req) {
            return service.addFree(contractId, req);
        }

        @PutMapping("/free/{id}")
        public FreeClause updateFree(@PathVariable("contractId") String contractId,
                                     @PathVariable("id") String id,
                                     @RequestBody FreeUpsert req) {
            return service.updateFree(contractId, id, req);
        }

        @DeleteMapping("/free/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void deleteFree(@PathVariable("contractId") String contractId,
                               @PathVariable("id") String id) {
            service.deleteFree(contractId, id);
        }
    }
}
