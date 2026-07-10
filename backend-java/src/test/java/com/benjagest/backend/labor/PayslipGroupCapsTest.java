package com.benjagest.backend.labor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.benjagest.backend.labor.ss.SsGroupBasesService.GroupBase;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * F1-SSTOPES (2026-07-10) — Topes de cotización por GRUPO en el clamp de la
 * base (PayslipService.groupCapsMonthly, lógica pura). Cifras OFICIALES 2026
 * de la Orden PJC/297/2026 (BOE-A-2026-7296), las mismas que siembra V122:
 *
 *   grupos 1-7  → base mensual (mín por grupo, tope máximo común 5.101,20)
 *   grupos 8-11 → base DIARIA (47,48 mín / 170,04 máx) que se mensualiza ×30
 *                 (retribución mensual = cotización por 30 días).
 *
 * Antes del fix, los grupos diarios devolvían mínimo 0 (el mínimo diario no
 * se aplicaba nunca — "paso 4" pendiente del bloque NOM).
 */
class PayslipGroupCapsTest {

    /** Filas 2026 como las deja V122 (subset representativo + todos los diarios). */
    private static List<GroupBase> rows2026() {
        return List.of(
                gb(1, "1989.30", "5101.20", false),
                gb(2, "1649.70", "5101.20", false),
                gb(3, "1435.20", "5101.20", false),
                gb(4, "1424.40", "5101.20", false),
                gb(5, "1424.40", "5101.20", false),
                gb(6, "1424.40", "5101.20", false),
                gb(7, "1424.40", "5101.20", false),
                gb(8, "47.48", "170.04", true),
                gb(9, "47.48", "170.04", true),
                gb(10, "47.48", "170.04", true),
                gb(11, "47.48", "170.04", true));
    }

    private static GroupBase gb(int group, String min, String max, boolean daily) {
        return new GroupBase(2026, (short) group, "Grupo " + group,
                new BigDecimal(min), new BigDecimal(max), daily, false);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "esperado " + expected + " pero fue " + actual);
    }

    @Test
    void grupo1_mensual_devuelveSuMinimoYElTopeComun() {
        BigDecimal[] caps = PayslipService.groupCapsMonthly(rows2026(), 1);
        assertAmount("1989.30", caps[0]);
        assertAmount("5101.20", caps[1]);
    }

    @Test
    void grupo8_baseDiaria_seMensualizaPor30_incluidoElMinimo() {
        BigDecimal[] caps = PayslipService.groupCapsMonthly(rows2026(), 8);
        // 47,48 × 30 = 1.424,40 — ANTES devolvía 0 y el mínimo no se aplicaba.
        assertAmount("1424.40", caps[0]);
        // 170,04 × 30 = 5.101,20 — coincide con el tope máximo común.
        assertAmount("5101.20", caps[1]);
    }

    @Test
    void grupo11_baseDiaria_igualQueElGrupo8() {
        BigDecimal[] caps = PayslipService.groupCapsMonthly(rows2026(), 11);
        assertAmount("1424.40", caps[0]);
        assertAmount("5101.20", caps[1]);
    }

    @Test
    void grupoDiario_sinMaximoPropio_caeAlTopeComunMensual() {
        List<GroupBase> rows = List.of(
                gb(7, "1424.40", "5101.20", false),
                gb(8, "47.48", "0", true)); // sin máximo diario cargado
        BigDecimal[] caps = PayslipService.groupCapsMonthly(rows, 8);
        assertAmount("1424.40", caps[0]);
        assertAmount("5101.20", caps[1]);
    }

    @Test
    void grupoNoEncontrado_devuelveNull_yElMotorUsaElTopeGlobal() {
        assertNull(PayslipService.groupCapsMonthly(rows2026(), 12));
        assertNull(PayslipService.groupCapsMonthly(List.of(), 1));
        assertNull(PayslipService.groupCapsMonthly(null, 1));
    }
}
