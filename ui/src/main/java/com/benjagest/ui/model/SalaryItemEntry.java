package com.benjagest.ui.model;

import java.math.BigDecimal;

/** Concepto salarial de un contrato (salario base o complemento). */
public record SalaryItemEntry(
        String id,
        String conceptName,
        String kind,            // SALARY_BASE | COMPLEMENT | NON_SALARIAL
        BigDecimal annualAmount,
        boolean cotizes,        // entra en la base de cotización a la SS
        boolean taxable         // sujeto a retención de IRPF
) {}
