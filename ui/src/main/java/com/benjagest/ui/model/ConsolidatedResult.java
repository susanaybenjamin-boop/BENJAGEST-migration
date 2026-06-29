package com.benjagest.ui.model;

import java.math.BigDecimal;
import java.util.List;

/** CONSOL-3 — Balance consolidado (agregado con eliminaciones intragrupo). */
public record ConsolidatedResult(
        String asOf,
        List<ConsolidatedBalance.TrialLine> lines,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        BigDecimal resultado,
        int companyCount,
        List<EliminationRow> eliminations,
        BigDecimal receivablesEliminated,
        BigDecimal payablesEliminated) {

    /** Saldo recíproco intragrupo eliminado (una subcuenta de tercero). */
    public record EliminationRow(String companyName, String code, String name,
                                 String terceroNif, String terceroType, BigDecimal balance) {}
}
