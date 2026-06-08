package com.benjagest.ui.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * CTR-2 — Records UI para los catálogos del wizard de contrato.
 * Espejo de los DTOs del backend (ContractCatalogModels).
 */
public final class ContractCatalog {

    private ContractCatalog() {}

    public record SepeType(
            String code,
            String family,
            String workingDay,
            String description,
            String legalBasis,
            boolean unifiedModel2022,
            boolean active
    ) {
        /** Texto bonito para el combo: "100 — Indefinido jornada completa…". */
        public String label() {
            return code + " — " + description;
        }
    }

    public record Agreement(
            String id,
            String code,
            String name,
            String scope,
            String boeReference,
            List<Category> categories,
            boolean active
    ) {
        public String label() { return name; }
    }

    public record Category(
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
    ) {
        public String label() {
            return groupCode + " · " + categoryName;
        }
    }

    public record ClauseTemplate(
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
