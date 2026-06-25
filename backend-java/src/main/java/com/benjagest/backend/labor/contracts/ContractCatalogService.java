package com.benjagest.backend.labor.contracts;

import com.benjagest.backend.labor.contracts.ContractCatalogModels.CollectiveAgreement;
import com.benjagest.backend.labor.contracts.ContractCatalogModels.ContractClauseTemplate;
import com.benjagest.backend.labor.contracts.ContractCatalogModels.ProfessionalCategory;
import com.benjagest.backend.labor.contracts.ContractCatalogModels.SepeContractType;
import com.benjagest.backend.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * CTR-2 — Servicio de solo lectura sobre los catálogos sembrados en V74.
 *
 * <p>La UI del wizard de contrato consume estos endpoints para poblar
 * los combos. Como son catálogos básicamente estáticos (cambian solo
 * cuando hay reforma BOE), no aplicamos caché por ahora — JDBC sobre
 * <2000 filas total es instantáneo. Si crece, basta con un cache
 * Spring sobre los métodos.
 */
@Service
public class ContractCatalogService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public ContractCatalogService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    // ============================================================
    //  SEPE
    // ============================================================

    public List<SepeContractType> listSepeTypes() {
        return jdbcTemplate.query("""
                SELECT code, family, working_day, description, legal_basis,
                       unified_model_2022, active
                  FROM sepe_contract_types
                 WHERE active = TRUE
                 ORDER BY family, code
                """, (rs, i) -> new SepeContractType(
                rs.getString("code"),
                rs.getString("family"),
                rs.getString("working_day"),
                rs.getString("description"),
                rs.getString("legal_basis"),
                rs.getBoolean("unified_model_2022"),
                rs.getBoolean("active")
        ));
    }

    /**
     * CONTRATO-MODALIDADES — ¿el código SEPE cotiza DESEMPLEO por el esquema
     * TEMPORAL (8,30 %: 1,60 trabajador / 6,70 empresa)? Si no, cotiza por el
     * INDEFINIDO (7,05 %: 1,55 / 5,50). El esquema lo dicta la columna
     * {@code unemployment_scheme} de {@code sepe_contract_types} (V144), que
     * respeta los matices de la Orden de cotización (la sustitución y los
     * formativos cotizan al esquema INDEFINIDO aunque sean temporales).
     *
     * <p>Default DEFENSIVO: código nulo/desconocido → {@code false} (esquema
     * indefinido) → no cambia el cálculo previo de los contratos antiguos.
     */
    public boolean isTemporalUnemployment(String sepeCode) {
        if (sepeCode == null || sepeCode.isBlank()) return false;
        List<String> schemes = jdbcTemplate.query("""
                SELECT unemployment_scheme FROM sepe_contract_types
                 WHERE code = ? LIMIT 1
                """, (rs, i) -> rs.getString(1), sepeCode.trim());
        return !schemes.isEmpty() && "TEMPORAL".equalsIgnoreCase(schemes.get(0));
    }

    // ============================================================
    //  Convenios + categorías profesionales
    // ============================================================

    /**
     * Devuelve los convenios con sus categorías anidadas. Una sola
     * pasada por BD + agrupación en memoria — cómodo para v1 donde
     * hay 25 convenios y ~80 categorías.
     */
    public List<CollectiveAgreement> listAgreementsWithCategories() {
        // 1) Convenios
        Map<String, CollectiveAgreement> byId = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT id, code, name, scope, boe_reference, active
                  FROM collective_agreements
                 WHERE active = TRUE
                 ORDER BY code
                """, rs -> {
            String id = rs.getString("id");
            byId.put(id, new CollectiveAgreement(
                    id,
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getString("scope"),
                    rs.getString("boe_reference"),
                    new ArrayList<>(),
                    rs.getBoolean("active")
            ));
        });
        // 2) Categorías agregadas a su convenio
        jdbcTemplate.query("""
                SELECT id, collective_agreement_id, group_code, ss_contribution_group,
                       category_name, min_annual_salary, min_monthly_salary, max_weekly_hours,
                       probation_days, year_published, active
                  FROM professional_categories
                 WHERE active = TRUE
                 ORDER BY collective_agreement_id, group_code, category_name
                """, rs -> {
            String agreementId = rs.getString("collective_agreement_id");
            CollectiveAgreement agreement = byId.get(agreementId);
            if (agreement == null) return; // convenio inactivo o huérfano
            agreement.categories().add(new ProfessionalCategory(
                    rs.getString("id"),
                    agreementId,
                    rs.getString("group_code"),
                    rs.getObject("ss_contribution_group") instanceof Number n ? n.intValue() : null,
                    rs.getString("category_name"),
                    rs.getBigDecimal("min_annual_salary"),
                    rs.getBigDecimal("min_monthly_salary"),
                    rs.getBigDecimal("max_weekly_hours"),
                    (Integer) rs.getObject("probation_days"),
                    (Integer) rs.getObject("year_published"),
                    rs.getBoolean("active")
            ));
        });
        return new ArrayList<>(byId.values());
    }

    // ============================================================
    //  Cláusulas adicionales / anexos
    // ============================================================

    /**
     * Cláusulas disponibles para la asesoría actual: las built-in
     * (visibles para todas) + las custom propias (company_id =
     * tenant actual). Las custom de OTRAS asesorías no se ven.
     */
    public List<ContractClauseTemplate> listClauseTemplates() {
        String companyId = tenantContext.getCurrentCompanyId();
        return jdbcTemplate.query("""
                SELECT id, code, title, body, category, legal_basis,
                       is_built_in, company_id, active
                  FROM contract_clause_templates
                 WHERE active = TRUE
                   AND (is_built_in = TRUE
                        OR company_id = ?)
                 ORDER BY is_built_in DESC, category, title
                """, (rs, i) -> new ContractClauseTemplate(
                rs.getString("id"),
                rs.getString("code"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("category"),
                rs.getString("legal_basis"),
                rs.getBoolean("is_built_in"),
                rs.getString("company_id"),
                rs.getBoolean("active")
        ), companyId);
    }
}
