package com.benjagest.ui.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * SIM — resultado del simulador comparativo (POST /api/aeat/simulation).
 *
 * <p>Las dos columnas que se pintan una al lado de otra: {@code real} es lo
 * que el cliente pagaria hoy y {@code simulated} lo que pagaria si hiciera las
 * compras previstas. Los tres numeros de abajo ({@code extraSpend},
 * {@code saving}, {@code netCost}) son lo que de verdad decide: el ahorro
 * SIEMPRE es menor que el desembolso, y ensenar solo el ahorro empujaria a
 * gastar por gastar.
 */
public record TaxSimulationResult(
        int year,
        int quarter,
        /** ESTIMACION_DIRECTA | MODULOS | SOCIEDAD | DESCONOCIDO */
        String regime,
        /** Si es false, los gastos NO bajan el pago de IRPF: hay que decirlo. */
        boolean expensesReduceIrpf,
        Scenario real,
        Scenario simulated,
        BigDecimal extraSpend,
        BigDecimal saving,
        BigDecimal netCost,
        /** Gasto maximo que deja el rendimiento del anio en cero. */
        BigDecimal ceilingBase,
        BigDecimal ceilingSaving,
        /** Claves i18n de los avisos a pintar. */
        List<String> warnings
) {

    public record Scenario(
            BigDecimal income,
            BigDecimal expenses,
            BigDecimal profit,
            BigDecimal model303,
            BigDecimal model130,
            BigDecimal totalToPay
    ) {}
}
