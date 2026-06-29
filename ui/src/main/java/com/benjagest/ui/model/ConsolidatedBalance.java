package com.benjagest.ui.model;

import java.math.BigDecimal;
import java.util.List;

/** CONSOL-2 — Balance de comprobación agregado de un grupo. */
public record ConsolidatedBalance(
        String asOf,
        List<TrialLine> lines,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        BigDecimal resultado,
        int companyCount) {

    /** Línea del balance de comprobación (por código de cuenta). */
    public record TrialLine(String code, String name, BigDecimal debit, BigDecimal credit) {
        public BigDecimal saldo() {
            BigDecimal d = debit == null ? BigDecimal.ZERO : debit;
            BigDecimal c = credit == null ? BigDecimal.ZERO : credit;
            return d.subtract(c);
        }
    }
}
