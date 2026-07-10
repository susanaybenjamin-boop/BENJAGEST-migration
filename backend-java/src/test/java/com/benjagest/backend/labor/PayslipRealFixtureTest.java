package com.benjagest.backend.labor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.benjagest.backend.labor.ss.SsContributionRatesService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * F1-NOMTEST-REAL (2026-07-10) — La aritmética de cuotas SS se valida contra
 * DOS NÓMINAS REALES del mismo trabajador (peón de la construcción, Granada,
 * 2026) emitidas por DOS softwares de asesoría distintos. Importes reales;
 * los datos personales no se incluyen (repo público).
 *
 * <p><b>Fixture A — febrero 2026 (finiquito, contrato INDEFINIDO, grupo 10).</b>
 * Base de cotización única 1.205,61 (rem. 997,89 + prorrata extras 207,72).
 * El recibo cuadra internamente al céntimo y nuestro motor reproduce las
 * OCHO cuotas exactas, incluida la elección del desempleo de indefinido
 * (1,55/5,50): trabajador 58,47 + 18,69 + 1,21 = 78,37 · empresa
 * 293,56 + 66,31 + 9,64 (FP 7,23 + FOGASA 2,41) + AT/EP 80,78 = 450,29.
 * IRPF 14,17 % sobre 1.519,57 = 215,32.
 *
 * <p><b>Fixture B — junio 2026 (mes parcial 23 días, contrato TEMPORAL,
 * grupo 9).</b> Aportación del trabajador sobre lo percibido (1.503,48) con
 * desempleo temporal (1,60): 72,93* + 24,06 + 1,50 = 98,49. (*) Su software
 * redondea "CC+MEI 4,85 %" como un solo concepto (72,93); nosotros
 * redondeamos CC (70,66) y MEI (2,26) por separado = 72,92 → 1 céntimo,
 * criterio también válido. IRPF 2 % sobre 1.503,48 = 30,07.
 * <b>GAP CONOCIDO (documentado, pendiente):</b> ese recibo cotiza a la
 * EMPRESA sobre una base MENSUALIZADA mayor (668,10 de cuota total, con
 * permisos sin sueldo dentro del periodo) — bases asimétricas
 * trabajador/empresa en mes parcial que nuestro motor aún no modela
 * (refinamiento "mes parcial" ya anotado en PayslipService).
 */
class PayslipRealFixtureTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "esperado " + expected + " pero fue " + actual);
    }

    /** Tipos 2026 oficiales (Orden PJC/297/2026), los mismos que siembra la BD. */
    private static SsContributionRatesService.Rates rates2026() {
        return new SsContributionRatesService.Rates(2026,
                bd("4.70"), bd("1.55"), bd("0.10"), bd("0.15"),
                bd("23.60"), bd("5.50"), bd("0.20"), bd("0.60"), bd("0.75"),
                bd("1.50"), bd("5101.20"), bd("0.00"), "Orden PJC/297/2026");
    }

    @Test
    void fixtureA_febrero2026_indefinido_ochoCuotasAlCentimo() {
        var ss = PayslipService.computeSs(bd("1205.61"), bd("6.70"), rates2026(),
                bd("1.55"), bd("5.50")); // desempleo INDEFINIDO
        // Trabajador (el recibo real imprime CC+MEI juntos: 58,47).
        assertAmount("56.66", ss.eeCommon());
        assertAmount("1.81", ss.eeMei());
        assertAmount("18.69", ss.eeUnemployment());
        assertAmount("1.21", ss.eeTraining());
        assertAmount("78.37", ss.employeeTotal());
        // Empresa (el recibo real: 293,56 + 66,31 + 9,64 + 80,78 = 450,29).
        assertAmount("284.52", ss.erCommon());
        assertAmount("9.04", ss.erMei());
        assertAmount("66.31", ss.erUnemployment());
        assertAmount("7.23", ss.erTraining());
        assertAmount("2.41", ss.erFogasa());
        assertAmount("80.78", ss.erAtEp());
        assertAmount("450.29", ss.employerTotal());
    }

    @Test
    void fixtureA_febrero2026_irpf_14_17_sobre_1519_57() {
        // El recibo real: retención 215,32.
        BigDecimal irpf = bd("1519.57").multiply(bd("14.17"))
                .divide(bd("100"), 2, java.math.RoundingMode.HALF_UP);
        assertAmount("215.32", irpf);
    }

    @Test
    void fixtureB_junio2026_temporal_aportacionTrabajadorSobreLoPercibido() {
        var ss = PayslipService.computeSs(bd("1503.48"), bd("6.70"), rates2026(),
                bd("1.60"), bd("6.70")); // desempleo TEMPORAL
        // Real: 72,93 (CC+MEI juntos) + 24,06 + 1,50 = 98,49. Nosotros
        // redondeamos CC y MEI por separado → 72,92 → total 98,48 (±0,01).
        assertAmount("70.66", ss.eeCommon());
        assertAmount("2.26", ss.eeMei());
        assertAmount("24.06", ss.eeUnemployment());
        assertAmount("1.50", ss.eeTraining());
        assertAmount("98.48", ss.employeeTotal());
        // IRPF fijo del contrato: 2 % sobre 1.503,48 = 30,07 (real ✓).
        BigDecimal irpf = bd("1503.48").multiply(bd("2"))
                .divide(bd("100"), 2, java.math.RoundingMode.HALF_UP);
        assertAmount("30.07", irpf);
    }
}
