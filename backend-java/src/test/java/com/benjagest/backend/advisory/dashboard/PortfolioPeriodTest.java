package com.benjagest.backend.advisory.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * CARTERA-FILTRO — los rangos del filtro de periodo de "Mi cartera".
 *
 * <p>Se prueba con fechas <b>incómodas</b> a propósito: el último día del mes,
 * un 31 de diciembre y un 1 de enero. Ahí es donde salen los errores de un día,
 * y un error de un día en una asesoría significa una factura que entra o sale
 * del trimestre que se está mirando.
 */
class PortfolioPeriodTest {

    // ====================================================================
    //  Mes
    // ====================================================================

    @Test
    void elMesEnCursoVaDelDiaUnoAlPrimeroDelSiguiente() {
        LocalDate hoy = LocalDate.of(2026, 11, 22);

        assertEquals(LocalDate.of(2026, 11, 1), PortfolioPeriod.CURRENT_MONTH.from(hoy));
        assertEquals(LocalDate.of(2026, 12, 1), PortfolioPeriod.CURRENT_MONTH.toExclusive(hoy));
    }

    /** El último día del mes sigue perteneciendo a su mes, no al siguiente. */
    @Test
    void elUltimoDiaDelMesSigueSiendoDelMesEnCurso() {
        LocalDate hoy = LocalDate.of(2026, 11, 30);

        assertEquals(LocalDate.of(2026, 11, 1), PortfolioPeriod.CURRENT_MONTH.from(hoy));
        assertEquals(LocalDate.of(2026, 12, 1), PortfolioPeriod.CURRENT_MONTH.toExclusive(hoy));
    }

    @Test
    void elMesAnteriorTerminaJustoDondeEmpiezaElActual() {
        LocalDate hoy = LocalDate.of(2026, 11, 22);

        assertEquals(LocalDate.of(2026, 10, 1), PortfolioPeriod.PREVIOUS_MONTH.from(hoy));
        assertEquals(LocalDate.of(2026, 11, 1), PortfolioPeriod.PREVIOUS_MONTH.toExclusive(hoy));
    }

    /** En enero, el mes anterior es diciembre del año pasado. */
    @Test
    void enEneroElMesAnteriorEsDiciembreDelAnoPasado() {
        LocalDate hoy = LocalDate.of(2026, 1, 15);

        assertEquals(LocalDate.of(2025, 12, 1), PortfolioPeriod.PREVIOUS_MONTH.from(hoy));
        assertEquals(LocalDate.of(2026, 1, 1), PortfolioPeriod.PREVIOUS_MONTH.toExclusive(hoy));
    }

    // ====================================================================
    //  Trimestre — los de Hacienda: 1-ene, 1-abr, 1-jul, 1-oct
    // ====================================================================

    @Test
    void losTrimestresSonLosDeHacienda() {
        // Un día de cada trimestre, y el comienzo esperado de cada uno.
        assertEquals(LocalDate.of(2026, 1, 1),
                PortfolioPeriod.CURRENT_QUARTER.from(LocalDate.of(2026, 2, 9)));
        assertEquals(LocalDate.of(2026, 4, 1),
                PortfolioPeriod.CURRENT_QUARTER.from(LocalDate.of(2026, 6, 30)));
        assertEquals(LocalDate.of(2026, 7, 1),
                PortfolioPeriod.CURRENT_QUARTER.from(LocalDate.of(2026, 7, 1)));
        assertEquals(LocalDate.of(2026, 10, 1),
                PortfolioPeriod.CURRENT_QUARTER.from(LocalDate.of(2026, 11, 22)));
    }

    @Test
    void elTrimestreEnCursoDuraTresMeses() {
        LocalDate hoy = LocalDate.of(2026, 11, 22);

        assertEquals(LocalDate.of(2026, 10, 1), PortfolioPeriod.CURRENT_QUARTER.from(hoy));
        assertEquals(LocalDate.of(2027, 1, 1), PortfolioPeriod.CURRENT_QUARTER.toExclusive(hoy));
    }

    @Test
    void elTrimestreAnteriorTerminaDondeEmpiezaElActual() {
        LocalDate hoy = LocalDate.of(2026, 11, 22);

        assertEquals(LocalDate.of(2026, 7, 1), PortfolioPeriod.PREVIOUS_QUARTER.from(hoy));
        assertEquals(LocalDate.of(2026, 10, 1), PortfolioPeriod.PREVIOUS_QUARTER.toExclusive(hoy));
    }

    /**
     * En el primer trimestre, el anterior es el <b>cuarto del año pasado</b>. Es
     * el caso que más se usa de verdad: en enero la asesoría mira el 4T para
     * presentar.
     */
    @Test
    void enEneroElTrimestreAnteriorEsElCuartoDelAnoPasado() {
        LocalDate hoy = LocalDate.of(2026, 1, 20);

        assertEquals(LocalDate.of(2025, 10, 1), PortfolioPeriod.PREVIOUS_QUARTER.from(hoy));
        assertEquals(LocalDate.of(2026, 1, 1), PortfolioPeriod.PREVIOUS_QUARTER.toExclusive(hoy));
    }

    // ====================================================================
    //  Año
    // ====================================================================

    @Test
    void elAnoEnCursoVaDeEneroAEnero() {
        LocalDate hoy = LocalDate.of(2026, 11, 22);

        assertEquals(LocalDate.of(2026, 1, 1), PortfolioPeriod.CURRENT_YEAR.from(hoy));
        assertEquals(LocalDate.of(2027, 1, 1), PortfolioPeriod.CURRENT_YEAR.toExclusive(hoy));
    }

    /** 31 de diciembre: sigue siendo de este año. */
    @Test
    void elTreintaYUnoDeDiciembreSigueSiendoDeEsteAno() {
        LocalDate hoy = LocalDate.of(2026, 12, 31);

        assertEquals(LocalDate.of(2026, 1, 1), PortfolioPeriod.CURRENT_YEAR.from(hoy));
        assertEquals(LocalDate.of(2027, 1, 1), PortfolioPeriod.CURRENT_YEAR.toExclusive(hoy));
    }

    @Test
    void elAnoAnteriorTerminaDondeEmpiezaElActual() {
        LocalDate hoy = LocalDate.of(2026, 3, 4);

        assertEquals(LocalDate.of(2025, 1, 1), PortfolioPeriod.PREVIOUS_YEAR.from(hoy));
        assertEquals(LocalDate.of(2026, 1, 1), PortfolioPeriod.PREVIOUS_YEAR.toExclusive(hoy));
    }

    /**
     * Un año bisiesto no puede descolocar los rangos: se calculan con el
     * calendario, no sumando días.
     */
    @Test
    void elVeintinueveDeFebreroNoDescolocaNada() {
        LocalDate hoy = LocalDate.of(2028, 2, 29);

        assertEquals(LocalDate.of(2028, 2, 1), PortfolioPeriod.CURRENT_MONTH.from(hoy));
        assertEquals(LocalDate.of(2028, 3, 1), PortfolioPeriod.CURRENT_MONTH.toExclusive(hoy));
        assertEquals(LocalDate.of(2028, 1, 1), PortfolioPeriod.CURRENT_QUARTER.from(hoy));
    }

    // ====================================================================
    //  Lo que llega por la URL
    // ====================================================================

    /**
     * Un periodo que no se reconoce NO puede tumbar la pantalla: cae al mes en
     * curso, que es lo que se veía antes de que esto existiera.
     */
    @Test
    void unPeriodoDesconocidoCaeAlMesEnCurso() {
        assertEquals(PortfolioPeriod.CURRENT_MONTH, PortfolioPeriod.parse(null));
        assertEquals(PortfolioPeriod.CURRENT_MONTH, PortfolioPeriod.parse(""));
        assertEquals(PortfolioPeriod.CURRENT_MONTH, PortfolioPeriod.parse("   "));
        assertEquals(PortfolioPeriod.CURRENT_MONTH, PortfolioPeriod.parse("DESDE_SIEMPRE"));
    }

    @Test
    void elPeriodoSeEntiendeEnMinusculasYConEspacios() {
        assertEquals(PortfolioPeriod.PREVIOUS_QUARTER, PortfolioPeriod.parse("previous_quarter"));
        assertEquals(PortfolioPeriod.CURRENT_YEAR, PortfolioPeriod.parse("  Current_Year  "));
    }

    /**
     * Ningún periodo puede empezar después de donde termina. Barato de
     * comprobar y cubre los que se añadan en el futuro sin tocar este test.
     */
    @Test
    void ningunPeriodoEmpiezaDespuesDeTerminar() {
        for (LocalDate hoy : new LocalDate[] {
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 15),
                LocalDate.of(2026, 12, 31), LocalDate.of(2028, 2, 29)}) {
            for (PortfolioPeriod p : PortfolioPeriod.values()) {
                assertEquals(true, p.from(hoy).isBefore(p.toExclusive(hoy)),
                        p + " el " + hoy + " da un rango al reves o vacio");
            }
        }
    }
}
