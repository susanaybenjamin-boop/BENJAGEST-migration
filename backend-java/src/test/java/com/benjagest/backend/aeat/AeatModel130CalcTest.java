package com.benjagest.backend.aeat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * MOD-130-FIX (2026-07-08) — El calculo del modelo 130 se valida contra
 * DOS declaraciones REALES de Benjamin (ejercicio 2026, 1T y 2T,
 * estimacion directa simplificada), presentadas con el software fiscal
 * oficial. Antes BENJAGEST omitia el 5% de gastos de dificil
 * justificacion (art. 30 Reglamento IRPF) y cobraba de mas a todos los
 * autonomos.
 *
 * Fixture 1T: ingresos 6.275,00 / gastos 1.922,16 -> pago 827,04.
 * Fixture 2T: ingresos 16.589,16 / gastos 9.484,52 / prev 827,04 -> 522,84.
 */
class AeatModel130CalcTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    /** Compara importes por VALOR, no por escala (48000 == 48000.00). */
    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "esperado " + expected + " pero fue " + actual);
    }

    @Test
    void primerTrimestre2026_reproduceLaDeclaracionReal() {
        var c = AeatExtraModelsService.compute130(
                bd("6275.00"), bd("1922.16"), BigDecimal.ZERO, BigDecimal.ZERO);
        // Rendimiento previo = 6275 - 1922,16 = 4.352,84.
        assertAmount("4352.84", c.rendimientoPrevio());
        // 5% de 4.352,84 = 217,64 (casilla derivada del modelo real).
        assertAmount("217.64", c.gastosDificilJustificacion());
        // Rendimiento neto [03] = 4.352,84 - 217,64 = 4.135,20.
        assertAmount("4135.20", c.rendimientoNeto());
        // Cuota [04] = 20% = 827,04.
        assertAmount("827.04", c.cuota());
        // Pago [07]/[19] = 827,04 (sin retenciones ni pagos previos).
        assertAmount("827.04", c.pago());
    }

    @Test
    void segundoTrimestre2026_acumulado_reproduceLaDeclaracionReal() {
        var c = AeatExtraModelsService.compute130(
                bd("16589.16"), bd("9484.52"), BigDecimal.ZERO, bd("827.04"));
        // Rendimiento previo = 16.589,16 - 9.484,52 = 7.104,64.
        assertAmount("7104.64", c.rendimientoPrevio());
        // 5% = 355,23 (< 2.000, no topa).
        assertAmount("355.23", c.gastosDificilJustificacion());
        // Rendimiento neto [03] = 6.749,41.
        assertAmount("6749.41", c.rendimientoNeto());
        // Cuota [04] = 20% = 1.349,88.
        assertAmount("1349.88", c.cuota());
        // Pago [07] = cuota - pagos previos (827,04) = 522,84.
        assertAmount("522.84", c.pago());
    }

    @Test
    void topeDe2000EurosSeAplicaSobreElAcumulado() {
        // Rendimiento previo 50.000 -> 5% = 2.500, pero el tope anual es 2.000.
        var c = AeatExtraModelsService.compute130(
                bd("60000"), bd("10000"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertAmount("2000", c.gastosDificilJustificacion());
        // Rendimiento neto = 50.000 - 2.000 = 48.000; cuota 20% = 9.600.
        assertAmount("48000.00", c.rendimientoNeto());
        assertAmount("9600.00", c.cuota());
    }

    @Test
    void trimestreNegativoSeDeclaraCero_noReembolso() {
        // Gastos > ingresos: sin 5% (rendimiento previo negativo), pago 0.
        var c = AeatExtraModelsService.compute130(
                bd("1000"), bd("3000"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertAmount("0", c.gastosDificilJustificacion());
        assertAmount("0.00", c.cuota());
        assertAmount("0", c.pago());
    }

    @Test
    void retencionesReducenElPagoFraccionado() {
        // Mismo 1T pero con 500 de retenciones soportadas.
        var c = AeatExtraModelsService.compute130(
                bd("6275.00"), bd("1922.16"), bd("500"), BigDecimal.ZERO);
        assertAmount("827.04", c.cuota());
        // 827,04 - 500 = 327,04.
        assertAmount("327.04", c.pago());
    }
}
