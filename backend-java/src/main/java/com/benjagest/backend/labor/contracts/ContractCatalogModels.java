package com.benjagest.backend.labor.contracts;

import java.math.BigDecimal;
import java.util.List;

/**
 * CTR-2 — Records de los catálogos sembrados en V74 que la UI consume
 * para poblar los combos del wizard de contrato:
 * tipo SEPE → convenio → categoría → cláusulas adicionales.
 *
 * <p>Records agrupados en un solo archivo porque son DTOs pequeños y
 * van siempre juntos. Cuando alguno crezca con lógica propia, se
 * mueve a archivo aparte.
 */
public final class ContractCatalogModels {

    private ContractCatalogModels() {}

    /**
     * Código SEPE oficial (tabla {@code sepe_contract_types}).
     * <p>{@code unifiedModel2022}: TRUE si usa el modelo unificado SEPE
     * post 30-mar-2022 (modelo único con campos según tipo); FALSE si
     * sigue con cláusulado clásico por código.
     */
    public record SepeContractType(
            String code,
            String family,         // INDEFINIDO / TEMPORAL / FORMATIVO / …
            String workingDay,     // FULL_TIME / PART_TIME / FIXED_DISCONTINUOUS
            String description,
            String legalBasis,
            boolean unifiedModel2022,
            boolean active
    ) {}

    /**
     * Convenio colectivo (tabla {@code collective_agreements}) con su lista
     * de categorías profesionales anidadas. La UI hace combo en cascada:
     * convenio → categoría con salario mínimo auto-rellenado.
     */
    public record CollectiveAgreement(
            String id,
            String code,
            String name,
            String scope,           // STATE / AUTONOMOUS / PROVINCIAL / LOCAL / COMPANY
            String boeReference,
            List<ProfessionalCategory> categories,
            boolean active
    ) {}

    /**
     * Categoría profesional dentro de un convenio
     * (tabla {@code professional_categories}). Salarios mínimos 2026 que
     * el wizard usa para warnings amarillos cuando el OWNER pone un
     * salario inferior.
     */
    public record ProfessionalCategory(
            String id,
            String collectiveAgreementId,
            String groupCode,
            String categoryName,
            BigDecimal minAnnualSalary,
            BigDecimal minMonthlySalary,
            BigDecimal maxWeeklyHours,
            Integer probationDays,
            Integer yearPublished,
            boolean active
    ) {}

    /**
     * Cláusula adicional / anexo (tabla {@code contract_clause_templates}).
     * <p>{@code isBuiltIn}: TRUE para las 12 sembradas en V74 (visibles
     * para todas las asesorías); FALSE para las que el OWNER cree
     * custom para su propia asesoría ({@code companyId} != null).
     */
    public record ContractClauseTemplate(
            String id,
            String code,
            String title,
            String body,
            String category,
            String legalBasis,
            boolean isBuiltIn,
            String companyId,
            boolean active
    ) {}
}
