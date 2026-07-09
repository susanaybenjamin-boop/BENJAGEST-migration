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
    void tiposDistintosDe4_10_21_noSePierden() {
        // AUDIT resto (2026-07-09): una venta al 5% (alimentos 2022-2024)
        // caía fuera de los buckets y su cuota desaparecía del total
        // devengado. Ahora entra como "otros tipos" con su cuota real.
        var vb = AeatExtraModelsService.deriveRepercutido(
                BigDecimal.ZERO, BigDecimal.ZERO, bd("1000"),
                bd("200"), bd("10.00")); // 200 al 5% -> cuota 10
        assertAmount("200.00", vb.baseOtros());
        assertAmount("10.00", vb.cuotaOtros());
        // 1000*21% + 10 = 220,00.
        assertAmount("220.00", vb.totalIva());
    }

    @Test
    void sinOtrosTipos_laDerivacionNoCambia() {
        var vb3 = AeatExtraModelsService.deriveRepercutido(
                bd("1000"), bd("2000"), bd("3000"));
        var vb5 = AeatExtraModelsService.deriveRepercutido(
                bd("1000"), bd("2000"), bd("3000"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertAmount("870.00", vb3.totalIva());
        assertAmount("870.00", vb5.totalIva());
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

    // ---- IVA-COMP: compensación de cuotas de periodos anteriores ----

    @Test
    void compensacion_1T2026_real() {
        // Régimen 968,05 + previa 254,91 -> aplica 254,91, resultado 713,14.
        var c = AeatExtraModelsService.aplicarCompensacion(bd("968.05"), bd("254.91"));
        assertAmount("254.91", c.aplicada());
        assertAmount("713.14", c.resultado());
        assertAmount("0", c.remanente());
    }

    @Test
    void compensacion_1T2025_real_resultadoCero_conRemanente() {
        // Régimen 114,62 + previa 1.207,25 -> aplica 114,62, resultado 0,00,
        // remanente 1.092,63 para el trimestre siguiente.
        var c = AeatExtraModelsService.aplicarCompensacion(bd("114.62"), bd("1207.25"));
        assertAmount("114.62", c.aplicada());
        assertAmount("0", c.resultado());
        assertAmount("1092.63", c.remanente());
    }

    @Test
    void compensacion_trimestreNegativo_sumaAlRemanente() {
        // Régimen negativo (-300): no se aplica nada, el saldo a compensar
        // CRECE en 300 sobre la previa.
        var c = AeatExtraModelsService.aplicarCompensacion(bd("-300"), bd("500"));
        assertAmount("0", c.aplicada());
        assertAmount("-300.00", c.resultado());
        assertAmount("800.00", c.remanente());
    }

    @Test
    void compensacion_sinSaldoPrevio_resultadoIntacto() {
        var c = AeatExtraModelsService.aplicarCompensacion(bd("500"), BigDecimal.ZERO);
        assertAmount("0", c.aplicada());
        assertAmount("500.00", c.resultado());
        assertAmount("0", c.remanente());
    }

    // ---- Arrastre desde la declaración anterior (casilla 87 -> 110) ----

    @Test
    void remanente_delT1_tecleadoEnLaUI_seConsumeEntero() {
        // Caso real de Benjamin: T1 tecleado a mano (repercutido 968,05,
        // sin IVA soportado) con 254,91 a compensar previos. Consume los
        // 254,91 -> remanente 0. El T2 debe partir de 0, no de 254,91.
        String t1Ui = "{\"cuota_21\":\"968.05\",\"cuota_10\":\"0\",\"cuota_4\":\"0\","
                + "\"cuota_soportada\":\"0\",\"compensar_anteriores\":\"254.91\"}";
        assertAmount("0", AeatExtraModelsService.extractRemanente303(t1Ui));
    }

    @Test
    void remanente_trimestreNegativo_creceElSaldo() {
        // 303 tecleado con más soportado que repercutido: régimen -100,
        // previa 200 -> remanente 300.
        String ui = "{\"cuota_21\":\"100\",\"cuota_soportada\":\"200\",\"compensar_anteriores\":\"200\"}";
        assertAmount("300.00", AeatExtraModelsService.extractRemanente303(ui));
    }

    @Test
    void remanente_leeElValorAlmacenadoSiEstaPresente() {
        // Vista del backend: si viene el remanente ya calculado, se usa tal cual.
        String backend = "{\"resultado\":0.00,\"remanenteCompensar\":1092.63,"
                + "\"casillas\":{\"87_remanente_compensar\":1092.63}}";
        assertAmount("1092.63", AeatExtraModelsService.extractRemanente303(backend));
    }

    @Test
    void remanente_usaElResultadoDelRegimenGuardado_conModificacion() {
        // 303-FULL: la UI guarda 46_resultado_regimen (ya incluye la
        // modificacion 14/15). El arrastre lo usa directo: regimen 500,
        // previa 700 -> remanente 200.
        String ui = "{\"46_resultado_regimen\":\"500.00\",\"compensar_anteriores\":\"700\"}";
        assertAmount("200.00", AeatExtraModelsService.extractRemanente303(ui));
    }

    @Test
    void modificacion_restaEnElResultado_casoBenjamin() {
        // T2 de Benjamin: regimen general 1452,41 con una rectificativa que
        // modifica la cuota en -121,80 -> total devengado neto y resultado
        // bajan en 121,80. (arit. del total: 27 = repercutido + mod15).
        var devengado = bd("2287.77").add(bd("-121.80")); // 06 + 15
        var resultado = AeatExtraModelsService.computeResultadoIva(devengado, bd("835.36"));
        assertAmount("1330.61", resultado);
    }

    // ---- OPTYPE-2: routing del soportado a casillas por tipo de operacion ----

    private static java.util.List<AeatExtraModelsService.PurchaseRow> rows(
            AeatExtraModelsService.PurchaseRow... r) { return java.util.List.of(r); }
    private static AeatExtraModelsService.PurchaseRow row(String op, boolean inv,
            String base, String cuota, String ded) {
        return new AeatExtraModelsService.PurchaseRow(op, inv, bd(base), bd(cuota), bd(ded));
    }

    @Test
    void routing_interior100_soportadoIntacto_casoBenjamin() {
        // Todo interior 100% deducible -> el soportado del T2 no cambia.
        var m = AeatExtraModelsService.routePurchases303(
                rows(row("INTERIOR", false, "6337.64", "835.36", "835.36")));
        assertAmount("835.36", m.get("cuota_soportada"));
        assertAmount("835.36", m.get("_total_deducible"));
        assertAmount("0", m.get("_devengado_autorrep"));
    }

    @Test
    void routing_intracom100_autorrepercusionNeutra() {
        // Adq. intracom 100% deducible: devengado (11)=210 y deducible (37)=210
        // -> impacto NETO cero en el resultado (27 y 45 suben lo mismo).
        var m = AeatExtraModelsService.routePurchases303(
                rows(row("INTRACOM", false, "1000", "210", "210")));
        assertAmount("210.00", m.get("cuota_intra"));      // 11 (devengado)
        assertAmount("210.00", m.get("cuota_intra_ded"));  // 37 (deducible)
        assertAmount("210.00", m.get("_devengado_autorrep"));
        assertAmount("210.00", m.get("_total_deducible"));
    }

    @Test
    void routing_isp_devengadoIgualDeducible() {
        // Inversion del sujeto pasivo: 13 (devengado) == 29 (deducible) -> neutro.
        var m = AeatExtraModelsService.routePurchases303(
                rows(row("ISP", false, "500", "105", "105")));
        assertAmount("105.00", m.get("cuota_isp"));        // 13
        assertAmount("105.00", m.get("cuota_soportada"));  // 29
        assertAmount("105.00", m.get("_devengado_autorrep"));
    }

    @Test
    void routing_deducibilidadParcial_prorrata() {
        // IVA 100 al 50% deducible -> solo deduce 50.
        var m = AeatExtraModelsService.routePurchases303(
                rows(row("INTERIOR", false, "476.19", "100", "50")));
        assertAmount("50.00", m.get("cuota_soportada"));
        assertAmount("50.00", m.get("_total_deducible"));
    }

    @Test
    void routing_ivaNoDeducible_noResta() {
        // IVA no deducible (0%) -> cuota deducible 0, no baja el resultado.
        var m = AeatExtraModelsService.routePurchases303(
                rows(row("INTERIOR", false, "476.19", "100", "0")));
        assertAmount("0", m.get("cuota_soportada"));
        assertAmount("0", m.get("_total_deducible"));
    }

    @Test
    void routing_importacionYbienInversion_aSusCasillas() {
        var imp = AeatExtraModelsService.routePurchases303(
                rows(row("IMPORT", false, "2000", "420", "420")));
        assertAmount("420.00", imp.get("cuota_import"));   // 33
        assertAmount("0", imp.get("_devengado_autorrep")); // importaciones no autorrepercuten
        var inv = AeatExtraModelsService.routePurchases303(
                rows(row("INTERIOR", true, "30000", "6300", "6300")));
        assertAmount("6300.00", inv.get("cuota_inv"));     // 31
        assertAmount("0", inv.get("cuota_soportada"));     // no en interiores corrientes
    }
}
