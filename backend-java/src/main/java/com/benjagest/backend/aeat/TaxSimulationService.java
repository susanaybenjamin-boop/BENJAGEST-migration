package com.benjagest.backend.aeat;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * SIM (2026-11-21) — <b>simulador comparativo contra la realidad</b>.
 *
 * <p>Petición de Benjamin: <i>"este simulador es para poder reducir los pagos
 * trimestrales. Si el empresario ha tenido unas ventas grandes y apenas ha
 * tenido gastos, va a tener que pagar bastante; pero si tuviera un simulador
 * la asesoría, en el que pueda ver hasta qué cantidad de gastos puede tener
 * para reducir esos pagos sin que le dé pérdidas, estaría bien"</i>.
 *
 * <p>Coge las cifras REALES del cliente, les suma unas compras previstas y
 * recalcula el 303 y el 130 para enseñar las dos columnas una al lado de otra.
 *
 * <h2>Lo que este servicio NO hace, a propósito</h2>
 * No inventa gastos ni los apunta en ningún sitio: <b>no escribe en la base de
 * datos</b>. Es una calculadora para planificar compras REALES que el cliente
 * se está planteando (adelantar la furgoneta de enero a diciembre, renovar los
 * ordenadores). Por eso el resultado incluye siempre el {@code netCost}: lo
 * que la compra cuesta de verdad después del ahorro. Un simulador que solo
 * enseñara "pagarás menos impuestos" empujaría a gastar por gastar, y gastar
 * 1.000 € para ahorrar 410 € solo tiene sentido si necesitabas la cosa.
 *
 * <h2>Dos periodos distintos, y es importante</h2>
 * <ul>
 *   <li>El <b>303</b> es del TRIMESTRE: IVA repercutido menos soportado de
 *       esos tres meses.</li>
 *   <li>El <b>130</b> es ACUMULADO del año hasta el fin de ese trimestre, y se
 *       le restan los pagos de los trimestres anteriores.</li>
 * </ul>
 * Calcularlo todo sobre el mismo periodo daría cifras que no cuadrarían con el
 * cuadro de mando ni con el modelo oficial. Un gasto metido en el trimestre
 * entra en los dos, pero sobre bases distintas.
 *
 * <p>La fórmula del 130 <b>no se duplica</b>: se reusa
 * {@link AeatExtraModelsService#compute130}, que ya es pública y estática. Así
 * el simulador y el modelo oficial no pueden discrepar.
 */
@Service
public class TaxSimulationService {

    private final AeatExtraModelsService aeatService;
    private final TaxRegimeResolver regimeResolver;

    public TaxSimulationService(AeatExtraModelsService aeatService,
                                TaxRegimeResolver regimeResolver,
                                TenantContext tenant) {
        this.aeatService = aeatService;
        this.regimeResolver = regimeResolver;
        this.tenant = tenant;
    }

    private final TenantContext tenant;

    // ====================================================================
    //  Entrada
    // ====================================================================

    /**
     * Una compra prevista.
     *
     * @param concept        para qu\u00e9 es ("Furgoneta", "Equipo inform\u00e1tico")
     * @param baseAmount     importe SIN IVA
     * @param vatRate        tipo de IVA en % (21, 10, 4, 0)
     * @param vatDeductiblePct  qu\u00e9 parte de ese IVA es deducible. 100 lo normal;
     *                       <b>50 en veh\u00edculos de uso mixto</b>, que es la regla
     *                       que la app ya modela en sus subcuentas 62x1 (V173).
     * @param irpfDeductible si el gasto resta en el rendimiento del 130. Un
     *                       veh\u00edculo de uso mixto suele tener IVA al 50% y NO
     *                       ser deducible en IRPF: son dos cosas distintas y
     *                       meterlas en una sola casilla dar\u00eda cifras falsas.
     */
    public record SimLine(String concept, BigDecimal baseAmount, BigDecimal vatRate,
                          BigDecimal vatDeductiblePct, boolean irpfDeductible) {

        /** IVA soportado deducible de esta l\u00ednea. */
        public BigDecimal deductibleVat() {
            BigDecimal pct = vatDeductiblePct == null ? ONE_HUNDRED : vatDeductiblePct;
            return totalVat().multiply(pct).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
        }

        /** IVA total de la factura (deducible o no): lo que se paga al proveedor. */
        public BigDecimal totalVat() {
            return nz(baseAmount).multiply(nz(vatRate))
                    .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
        }

        /** Desembolso real de esta compra. */
        public BigDecimal cashOut() {
            return nz(baseAmount).add(totalVat());
        }

        /**
         * Lo que resta en el rendimiento del 130. El IVA NO deducible es mayor
         * coste del bien y por tanto s\u00ed resta en IRPF: por eso no basta con la
         * base.
         */
        public BigDecimal irpfExpense() {
            if (!irpfDeductible) return BigDecimal.ZERO;
            return nz(baseAmount).add(totalVat().subtract(deductibleVat()));
        }
    }

    // ====================================================================
    //  Salida
    // ====================================================================

    /** Una de las dos columnas: la realidad o la simulaci\u00f3n. */
    public record Scenario(
            BigDecimal income,        // ingresos acumulados del a\u00f1o (casilla del 130)
            BigDecimal expenses,      // gastos acumulados del a\u00f1o
            BigDecimal profit,        // ingresos - gastos
            BigDecimal model303,      // resultado del 303 DEL TRIMESTRE (casilla 71)
            BigDecimal model130,      // pago fraccionado de IRPF del trimestre
            BigDecimal totalToPay     // 303 + 130
    ) {}

    public record SimulationResult(
            int year, int quarter,
            TaxRegimeResolver.Regime regime,
            /** Si es false, los gastos NO bajan el pago de IRPF y hay que decirlo. */
            boolean expensesReduceIrpf,
            Scenario real,
            Scenario simulated,
            /** Desembolso de las compras previstas (base + IVA). */
            BigDecimal extraSpend,
            /** Lo que se deja de pagar a Hacienda. SIEMPRE menor que el desembolso. */
            BigDecimal saving,
            /** Lo que la compra cuesta de verdad: desembolso menos ahorro. */
            BigDecimal netCost,
            /**
             * Gasto deducible m\u00e1ximo (base) que deja el rendimiento del a\u00f1o en
             * cero. Gastar por encima ya es entrar en p\u00e9rdidas.
             */
            BigDecimal ceilingBase,
            /** Con ese techo, cu\u00e1nto se ahorrar\u00eda como mucho. */
            BigDecimal ceilingSaving,
            /** Avisos para pintar en la pantalla (claves i18n). */
            List<String> warnings
    ) {}

    // ====================================================================
    //  C\u00e1lculo
    // ====================================================================

    /**
     * Compara la realidad con la realidad m\u00e1s unas compras previstas.
     *
     * <p>Los dos modelos salen de {@link AeatExtraModelsService} en modo vista
     * previa ({@code persist=false}): son <b>los mismos n\u00fameros que la asesor\u00eda
     * va a presentar</b>. Si el simulador calculase por su cuenta podr\u00eda decir
     * "pagar\u00e1s 400" y luego el modelo oficial decir otra cosa, que es
     * exactamente lo que no puede pasar en una herramienta de decisi\u00f3n.
     */
    public SimulationResult simulate(int year, int quarter, List<SimLine> lines) {
        String companyId = tenant.getCurrentCompanyId();
        TaxRegimeResolver.Regime regime = regimeResolver.resolve(companyId);
        boolean reduceIrpf = TaxRegimeResolver.expensesReduceIrpf(regime);
        List<SimLine> safe = lines == null ? List.of() : lines;

        AeatExtraModelsService.Model303View v303 = aeatService.generate303(year, quarter, false);
        AeatExtraModelsService.Model130View v130 = aeatService.generate130(year, quarter, false);

        // --- totales de las compras previstas ---
        BigDecimal extraIrpfExpense = BigDecimal.ZERO;
        BigDecimal extraVatDeductible = BigDecimal.ZERO;
        BigDecimal extraSpend = BigDecimal.ZERO;
        for (SimLine l : safe) {
            extraIrpfExpense = extraIrpfExpense.add(l.irpfExpense());
            extraVatDeductible = extraVatDeductible.add(l.deductibleVat());
            extraSpend = extraSpend.add(l.cashOut());
        }
        // En modulos el gasto no toca el 131; el IVA si puede bajar.
        if (!reduceIrpf) extraIrpfExpense = BigDecimal.ZERO;

        Scenario real = scenario(v130.ingresos(), v130.gastos(), v303.resultado(),
                v130.pago(), reduceIrpf);

        Scenario sim = scenario(
                v130.ingresos(), nz(v130.gastos()).add(extraIrpfExpense),
                simulated303(v303, extraVatDeductible),
                simulated130(v130, extraIrpfExpense), reduceIrpf);

        BigDecimal saving = real.totalToPay().subtract(sim.totalToPay());
        BigDecimal netCost = extraSpend.subtract(saving);

        // --- techo: cuanto cabe gastar antes de entrar en perdidas ---
        BigDecimal ceiling = nz(v130.ingresos()).subtract(nz(v130.gastos()));
        if (ceiling.signum() < 0 || !reduceIrpf) ceiling = BigDecimal.ZERO;
        Scenario atCeiling = scenario(v130.ingresos(), nz(v130.gastos()).add(ceiling),
                v303.resultado(), simulated130(v130, ceiling), reduceIrpf);
        BigDecimal ceilingSaving = real.totalToPay().subtract(atCeiling.totalToPay());

        List<String> warnings = new ArrayList<>();
        if (regime == TaxRegimeResolver.Regime.MODULOS) warnings.add("sim.warn.modulos");
        if (regime == TaxRegimeResolver.Regime.SOCIEDAD) warnings.add("sim.warn.sociedad");
        if (regime == TaxRegimeResolver.Regime.DESCONOCIDO) warnings.add("sim.warn.regimen");
        if (reduceIrpf && ceiling.signum() == 0) warnings.add("sim.warn.sin_margen");
        if (reduceIrpf && extraIrpfExpense.compareTo(ceiling) > 0) warnings.add("sim.warn.perdidas");

        return new SimulationResult(year, quarter, regime, reduceIrpf,
                real, sim, extraSpend, saving, netCost, ceiling, ceilingSaving, warnings);
    }

    /**
     * 303 simulado. El IVA deducible de m\u00e1s baja el resultado del r\u00e9gimen y
     * luego se vuelve a aplicar la compensaci\u00f3n de trimestres anteriores con
     * la MISMA funci\u00f3n del modelo oficial: si el resultado se vuelve negativo
     * no se "cobra", se arrastra, y el simulador tiene que reflejarlo.
     */
    private BigDecimal simulated303(AeatExtraModelsService.Model303View v303,
                                    BigDecimal extraVatDeductible) {
        BigDecimal regimen = nz(v303.resultadoRegimen()).subtract(nz(extraVatDeductible));
        return AeatExtraModelsService
                .aplicarCompensacion(regimen, v303.compensacionPrevia())
                .resultado();
    }

    /** 130 simulado con la MISMA formula del modelo oficial. */
    private BigDecimal simulated130(AeatExtraModelsService.Model130View v130,
                                    BigDecimal extraExpense) {
        return AeatExtraModelsService.compute130(
                v130.ingresos(), nz(v130.gastos()).add(nz(extraExpense)),
                v130.retenciones(), v130.pagosPrevios()).pago();
    }

    /**
     * Monta una columna. Los importes negativos se dejan a cero para comparar
     * LO QUE SE PAGA: un 303 o un 130 negativo no se cobra en el trimestre, se
     * arrastra al siguiente.
     */
    private Scenario scenario(BigDecimal income, BigDecimal expenses,
                              BigDecimal model303, BigDecimal model130,
                              boolean irpfApplies) {
        BigDecimal pay303 = nz(model303).max(BigDecimal.ZERO);
        BigDecimal pay130 = irpfApplies ? nz(model130).max(BigDecimal.ZERO) : BigDecimal.ZERO;
        return new Scenario(nz(income), nz(expenses), nz(income).subtract(nz(expenses)),
                pay303, pay130, pay303.add(pay130));
    }

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
