package com.benjagest.backend.aeat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Regresion de la aritmetica del 303/390 (IVA). No hay fixture "casilla a
 * casilla" real porque las capturas del 303 de Benjamin solo mostraban el
 * resultado final (713,14 el 1T, 1.325,80 el 2T) sin las bases; estos
 * tests fijan la derivacion de la cuota por tipo y el resultado.
 */
class AeatModel303CalcTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }
    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "esperado " + expected + " pero fue " + actual);
    }

    @Test
    void ivaRepercutidoSeDerivaDeLasBasesPorTipo() {
        var vb = AeatExtraModelsService.deriveRepercutido(
                bd("1000"), bd("2000"), bd("3000"));
        // 1000*4% + 2000*10% + 3000*21% = 40 + 200 + 630 = 870,00.
        assertAmount("870.00", vb.totalIva());
    }

    @Test
    void soloTipo21() {
        var vb = AeatExtraModelsService.deriveRepercutido(
                BigDecimal.ZERO, BigDecimal.ZERO, bd("6275"));
        // 6275 * 21% = 1.317,75.
        assertAmount("1317.75", vb.totalIva());
    }

    @Test
    void resultadoEsRepercutidoMenosSoportado() {
        // Repercutido 1.317,75 - soportado 604,61 = 713,14 (forma del 303).
        assertAmount("713.14",
                AeatExtraModelsService.computeResultadoIva(bd("1317.75"), bd("604.61")));
    }

    @Test
    void resultadoNegativoSeConserva_aCompensar() {
        // Mas soportado que repercutido -> negativo (a compensar/devolver).
        assertAmount("-100.00",
                AeatExtraModelsService.computeResultadoIva(bd("200"), bd("300")));
    }
}
