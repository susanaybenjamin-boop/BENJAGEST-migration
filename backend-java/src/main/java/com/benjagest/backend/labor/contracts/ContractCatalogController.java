package com.benjagest.backend.labor.contracts;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.labor.contracts.ContractCatalogModels.CollectiveAgreement;
import com.benjagest.backend.labor.contracts.ContractCatalogModels.ContractClauseTemplate;
import com.benjagest.backend.labor.contracts.ContractCatalogModels.SepeContractType;
import com.benjagest.backend.modules.RequiresModule;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public ContractCatalogController(ContractCatalogService service) {
        this.service = service;
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
}
