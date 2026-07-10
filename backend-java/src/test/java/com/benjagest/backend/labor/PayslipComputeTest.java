package com.benjagest.backend.labor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.benjagest.backend.labor.ss.SsContributionRatesService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * F1-NOMTEST (2026-07-10) — El cálculo de nómina se fija con un GOLDEN
 * SNAPSHOT tomado del motor en ejecución ANTES de extraer
 * {@code PayslipService.computePayslip} (puro): mismo contrato, mismos
 * conceptos, mismos tipos 2026 de la BD dev → mismos céntimos. Si un
 * refactor futuro mueve un solo redondeo, estos tests lo cazan.
 *
 * <p>Contrato del golden: 21.000 € (base 18.000 + plus convenio 3.000 +
 * dietas exentas 1.200 que ni cotizan ni tributan), 2 pagas extra NO
 * prorrateadas, IRPF fijo 12 %, AT/EP 1,50 %, grupo de cotización 5.
 * Tipos 2026 (Orden PJC/297/2026): EE 4,70+1,55+0,10+0,15 · ER
 * 23,60+5,50+0,20+0,60+0,75. Topes grupo 5: [1.424,40, 5.101,20].
 *
 * <p>PENDIENTE (con Benjamin): añadir el fixture de una nómina REAL
 * calculada por un software oficial (como MOD-130-FIX con el 130).
 */
class PayslipComputeTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "esperado " + expected + " pero fue " + actual);
    }

    private static PayslipService.ContractData contract() {
        var c = new PayslipService.ContractData();
        c.id = "ctr-golden";
        c.grossSalary = bd("22200.00");
        c.annualBonuses = 2;
        c.extrasProrated = false;
        c.irpfPercent = bd("12.00");
        c.atEpPercent = bd("1.50");
        c.ssContributionGroup = 5;
        return c;
    }

    private static List<PayslipService.SalaryConcept> concepts() {
        return List.of(
                new PayslipService.SalaryConcept("Salario base", "SALARY_BASE", bd("18000"), true, true),
                new PayslipService.SalaryConcept("Plus convenio", "COMPLEMENT", bd("3000"), true, true),
                new PayslipService.SalaryConcept("Dietas exentas", "COMPLEMENT", bd("1200"), false, false));
    }

    private static SsContributionRatesService.Rates rates2026() {
        return new SsContributionRatesService.Rates(2026,
                bd("4.70"), bd("1.55"), bd("0.10"), bd("0.15"),
                bd("23.60"), bd("5.50"), bd("0.20"), bd("0.60"), bd("0.75"),
                bd("1.50"), bd("5101.20"), bd("0.00"), "Orden PJC/297/2026");
    }

    /** Entradas del golden; los tres funcionales NO deben invocarse en estos
     *  escenarios (IRPF fijo del contrato, sin horas extra). */
    private static PayslipService.EngineInputs inputs(
            String type, Boolean prorated, BigDecimal otherDeductions,
            List<PayslipService.ExtraConcept> extraConcepts) {
        return new PayslipService.EngineInputs(
                type, contract(), concepts(), extraConcepts, null,
                prorated != null ? prorated : false, otherDeductions,
                List.of(), rates2026(),
                new BigDecimal[]{ bd("1424.40"), bd("5101.20") },
                false, null, false, null,
                () -> { throw new AssertionError("overtimeRates no debe usarse"); },
                null,
                () -> { throw new AssertionError("irpfFallback no debe usarse"); });
    }

    @Test
    void mensualSinProrrateo_reproduceElGolden() {
        var c = PayslipService.computePayslip(inputs("MONTHLY", false, null, null));
        // Devengo: base 18000/14 = 1.285,71 + plus 3000/12 = 250 + dietas 1200/12 = 100.
        assertAmount("1635.71", c.gross());
        // Base SS: cotizable anual 21000/12 = 1.750 (dietas fuera; topes no actúan).
        assertAmount("1750.00", c.cotizationBase());
        // EE por concepto (HALF_UP): 82,25+27,13+1,75+2,63 = 113,76.
        assertAmount("113.76", c.ssEmployee());
        // IRPF 12% del tributable del periodo (1.535,71): 184,29.
        assertAmount("184.29", c.irpf());
        assertAmount("12.00", c.irpfPct());
        assertAmount("1337.66", c.net());
        // ER: 413,00+96,25+3,50+10,50+13,13+26,25 = 562,63.
        assertAmount("562.63", c.ss().employerTotal());
    }

    @Test
    void mensualConExtrasDelMesYDeducciones_reproduceElGolden() {
        var extras = List.of(
                new PayslipService.ExtraConcept("Dieta viaje", bd("100"), false, false),
                new PayslipService.ExtraConcept("Plus asistencia", bd("80"), true, true));
        var c = PayslipService.computePayslip(inputs("MONTHLY", false, bd("25"), extras));
        assertAmount("1815.71", c.gross());
        // Base: 1.750 + 80 del plus que cotiza (la dieta no).
        assertAmount("1830.00", c.cotizationBase());
        assertAmount("118.96", c.ssEmployee());
        // IRPF sobre 1.535,71 + 80 = 1.615,71 → 193,89.
        assertAmount("193.89", c.irpf());
        assertAmount("25", c.otherDeductions());
        assertAmount("1477.86", c.net());
    }

    @Test
    void mensualProrrateada_reproduceElGolden() {
        var c = PayslipService.computePayslip(inputs("MONTHLY", true, null, null));
        // Base salarial 18000/12 = 1.500 (prorrata de extras dentro).
        assertAmount("1850.00", c.gross());
        // La base de cotización NO cambia con el prorrateo (anual/12 siempre).
        assertAmount("1750.00", c.cotizationBase());
        assertAmount("113.76", c.ssEmployee());
        assertAmount("210.00", c.irpf());
        assertAmount("1526.24", c.net());
    }

    @Test
    void pagaExtraVerano_reproduceElGolden() {
        var c = PayslipService.computePayslip(inputs("EXTRA_SUMMER", false, null, null));
        // Una mensualidad de salario base: 18000/14 = 1.285,71. Solo base
        // (los complementos no van en la extra).
        assertAmount("1285.71", c.gross());
        // No cotiza aparte (ya cotizó prorrateada mes a mes); el mínimo del
        // grupo NO fuerza una base > 0 en la extra.
        assertAmount("0", c.cotizationBase());
        assertAmount("0.00", c.ssEmployee());
        // Tributa al 12%: 154,29.
        assertAmount("154.29", c.irpf());
        assertAmount("1131.42", c.net());
        assertAmount("0.00", c.ss().employerTotal());
    }

    @Test
    void mejoraRecurrente_delSolver_anualizaEnBaseYTributa() {
        // Escenario del solve-target del golden: mejora mensual 199,16.
        var rec = List.of(new PayslipService.ExtraConcept(
                PayslipService.MEJORA_CONCEPT, bd("199.16"), true, true));
        var c = PayslipService.computePayslip(new PayslipService.EngineInputs(
                "MONTHLY", contract(), concepts(), null, rec, false, null,
                List.of(), rates2026(),
                new BigDecimal[]{ bd("1424.40"), bd("5101.20") },
                false, null, false, null,
                () -> { throw new AssertionError("overtimeRates no debe usarse"); },
                null,
                () -> { throw new AssertionError("irpfFallback no debe usarse"); }));
        assertAmount("1834.87", c.gross());
        // Base: (21000 + 199,16×12)/12 = 1.949,16.
        assertAmount("1949.16", c.cotizationBase());
        assertAmount("126.69", c.ssEmployee());
        assertAmount("208.18", c.irpf());
        assertAmount("1500.00", c.net());
    }
}
