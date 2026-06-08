package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * CTR-3 — Plantilla reutilizable de contrato.
 * Espejo del DTO backend en ContractTemplateService.View.
 */
public record ContractTemplate(
        String id,
        String name,
        String description,
        String sepeContractCode,
        String contractType,
        String collectiveAgreementId,
        String professionalCategoryId,
        String professionalGroup,
        BigDecimal weeklyHours,
        BigDecimal grossSalary,
        Integer annualBonuses,
        Integer vacationDays,
        BigDecimal irpfPercent,
        Integer probationDays,
        String workplaceAddress,
        String clauseCodes,
        String pdfModel,
        boolean isBuiltIn,
        boolean active
) {
    public String label() {
        return name == null ? "—" : name;
    }

    /** Para que ChoiceDialog y ComboBox pinten el nombre por defecto. */
    @Override public String toString() { return label(); }
}
