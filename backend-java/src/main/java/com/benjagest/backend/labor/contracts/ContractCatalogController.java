package com.benjagest.backend.labor.contracts;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.labor.contracts.ContractCatalogModels.CollectiveAgreement;
import com.benjagest.backend.labor.contracts.ContractCatalogModels.ContractClauseTemplate;
import com.benjagest.backend.labor.contracts.ContractCatalogModels.SepeContractType;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * CTR-2 — Endpoints de solo lectura para que el wizard del contrato
 * pueble los combos del frontend.
 *
 * <ul>
 *   <li>{@code GET /api/contracts/catalog/sepe}      — códigos SEPE.</li>
 *   <li>{@code GET /api/contracts/catalog/agreements} — convenios con
 *       categorías anidadas.</li>
 *   <li>{@code GET /api/contracts/catalog/clauses}   — cláusulas built-in
 *       + custom de la asesoría actual.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/contracts/catalog")
@RequiresModule("labor")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "ADVISOR"})
public class ContractCatalogController {

    private final ContractCatalogService service;
    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public ContractCatalogController(ContractCatalogService service,
                                     JdbcTemplate jdbc,
                                     TenantContext tenant) {
        this.service = service;
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @GetMapping("/sepe")
    public List<SepeContractType> listSepe() {
        return service.listSepeTypes();
    }

    @GetMapping("/agreements")
    public List<CollectiveAgreement> listAgreements() {
        return service.listAgreementsWithCategories();
    }

    @GetMapping("/clauses")
    public List<ContractClauseTemplate> listClauses() {
        return service.listClauseTemplates();
    }

    // CTR-7 — CRUD de cláusulas custom del OWNER ===============================
    // Solo se pueden tocar las cláusulas marcadas is_built_in=FALSE y que
    // pertenezcan al tenant actual. Las built-in son inmutables.

    public record ClauseUpsert(String code, String title, String body,
                               String category, String legalBasis) {}

    @PostMapping("/clauses")
    @ResponseStatus(HttpStatus.CREATED)
    public ContractClauseTemplate createClause(@RequestBody ClauseUpsert req) {
        validateClause(req);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO contract_clause_templates
                    (id, code, title, body, category, legal_basis,
                     is_built_in, company_id, active)
                VALUES (?, ?, ?, ?, ?, ?, FALSE, ?, TRUE)
                """, id, req.code(), req.title(), req.body(),
                req.category() == null ? "CUSTOM" : req.category(),
                req.legalBasis(), tenant.getCurrentCompanyId());
        return findClause(id);
    }

    @PutMapping("/clauses/{id}")
    public ContractClauseTemplate updateClause(@PathVariable("id") String id,
                                               @RequestBody ClauseUpsert req) {
        validateClause(req);
        int n = jdbc.update("""
                UPDATE contract_clause_templates
                   SET code = ?, title = ?, body = ?, category = ?, legal_basis = ?
                 WHERE id = ? AND company_id = ? AND is_built_in = FALSE
                """, req.code(), req.title(), req.body(),
                req.category() == null ? "CUSTOM" : req.category(),
                req.legalBasis(), id, tenant.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Cláusula no encontrada (o es built-in del sistema, no editable)");
        }
        return findClause(id);
    }

    @DeleteMapping("/clauses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteClause(@PathVariable("id") String id) {
        int n = jdbc.update("""
                UPDATE contract_clause_templates SET active = FALSE
                 WHERE id = ? AND company_id = ? AND is_built_in = FALSE
                """, id, tenant.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Cláusula no encontrada (o es built-in, no eliminable)");
        }
    }

    private ContractClauseTemplate findClause(String id) {
        return jdbc.query("""
                SELECT id, code, title, body, category, legal_basis,
                       is_built_in, company_id, active
                  FROM contract_clause_templates WHERE id = ?
                """, (rs, i) -> new ContractClauseTemplate(
                rs.getString("id"), rs.getString("code"), rs.getString("title"),
                rs.getString("body"), rs.getString("category"), rs.getString("legal_basis"),
                rs.getBoolean("is_built_in"), rs.getString("company_id"),
                rs.getBoolean("active")
        ), id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void validateClause(ClauseUpsert req) {
        if (req.code() == null || req.code().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code requerido");
        if (req.title() == null || req.title().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title requerido");
        if (req.body() == null || req.body().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body requerido");
    }
}
