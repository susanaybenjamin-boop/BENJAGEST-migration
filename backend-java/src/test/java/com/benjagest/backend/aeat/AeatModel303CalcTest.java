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
    void ejercicio2025_primerTrimestre_declaracionReal() {
        // 303 1T 2025 presentado (SH Asesores). Bases devengadas:
        // 1.095,00 al 10% + 792,00 al 21%. IVA deducible 161,20.
        var rep = AeatExtraModelsService.deriveRepercutido(
                BigDecimal.ZERO, bd("1095.00"), bd("792.00"));
        // [27] Total cuota devengada = 109,50 + 166,32 = 275,82.
        assertAmount("275.82", rep.totalIva());
        // [46] Resultado régimen general = 275,82 - 161,20 = 114,62.
        assertAmount("114.62",
                AeatExtraModelsService.computeResultadoIva(rep.totalIva(), bd("161.20")));
        // NOTA: el resultado REAL de la declaración [71] fue 0,00, no
        // 114,62, porque habia 1.207,25 de cuotas a compensar de
        // periodos anteriores (casilla 110/78). BENJAGEST calcula bien el
        // resultado del trimestre pero AUN NO arrastra el IVA negativo
        // acumulado — gap anotado en el backlog (bloque IVA-COMP).
    }

    @Test
    void ejercicio2026_primerTrimestre_declaracionReal_conCompensacion() {
        // 303 1T 2026 presentado. Devengado [27] 1.183,00 - deducible
        // [45] 214,95 = resultado regimen [46] 968,05.
        assertAmount("968.05",
                AeatExtraModelsService.computeResultadoIva(bd("1183.00"), bd("214.95")));
        // Resultado REAL [71] fue 713,14 = 968,05 - 254,91 de cuotas a
        // compensar de periodos anteriores (casilla 110/78). BENJAGEST
        // daria 968,05 (gap IVA-COMP: afecta a las declaraciones REALES
        // de 2026 de Benjamin, no solo a 2025).
        assertAmount("713.14", bd("968.05").subtract(bd("254.91")));
    }

    @Test
    void ejercicio2026_segundoTrimestre_declaracionReal() {
        // 303 2T 2026: devengado 2.165,97 - deducible 840,17 = 1.325,80.
        // Sin compensacion pendiente (la del 1T se agoto) -> resultado
        // final = resultado del trimestre.
        assertAmount("1325.80",
                AeatExtraModelsService.computeResultadoIva(bd("2165.97"), bd("840.17")));
    }

    @Test
    void resultadoNegativoSeConserva_aCompensar() {
        // Mas soportado que repercutido -> negativo (a compensar/devolver).
        assertAmount("-100.00",
                AeatExtraModelsService.computeResultadoIva(bd("200"), bd("300")));
    }
}
