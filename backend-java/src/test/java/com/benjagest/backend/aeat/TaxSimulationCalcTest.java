package com.benjagest.backend.aeat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.benjagest.backend.aeat.TaxRegimeResolver.Regime;
import com.benjagest.backend.aeat.TaxSimulationService.SimLine;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * SIM — la aritmética del simulador comparativo, sin BD.
 *
 * <p>Petición de Benjamin (2026-11-21): poder enseñarle al cliente hasta
 * cuánto puede gastar para bajar los pagos del trimestre sin entrar en
 * pérdidas.
 *
 * <p>Lo que se fija aquí es la parte fiscal delicada: que el IVA no deducible
 * cuente como gasto de IRPF, que un vehículo al 50% no se trate como una
 * compra normal, y sobre todo que <b>el ahorro SIEMPRE sea menor que el
 * desembolso</b> — si alguna vez saliera al revés, la herramienta estaría
 * diciéndole al cliente que gastar le sale gratis.
 */
class TaxSimulationCalcTest {

    private static SimLine linea(String concepto, String base, String tipo,
                                 String ivaDeduciblePct, boolean irpf) {
        return new SimLine(concepto, new BigDecimal(base), new BigDecimal(tipo),
                new BigDecimal(ivaDeduciblePct), irpf);
    }

    // ====================================================================
    //  Una compra normal
    // ====================================================================

    @Test
    void unaCompraCorrienteAl21() {
        SimLine l = linea("Equipo informatico", "1000.00", "21", "100", true);

        assertEquals(0, new BigDecimal("210.00").compareTo(l.totalVat()));
        assertEquals(0, new BigDecimal("210.00").compareTo(l.deductibleVat()));
        assertEquals(0, new BigDecimal("1210.00").compareTo(l.cashOut()),
                "lo que sale de la cuenta del cliente");
        assertEquals(0, new BigDecimal("1000.00").compareTo(l.irpfExpense()),
                "en IRPF resta la base, porque el IVA se recupera");
    }

    @Test
    void unTipoReducidoSeCalculaIgual() {
        SimLine l = linea("Libros", "1000.00", "4", "100", true);
        assertEquals(0, new BigDecimal("40.00").compareTo(l.totalVat()));
        assertEquals(0, new BigDecimal("1040.00").compareTo(l.cashOut()));
    }

    // ====================================================================
    //  Los casos que se hacen mal a mano
    // ====================================================================

    /**
     * Vehículo de uso mixto: IVA deducible al 50%. La mitad que NO se
     * recupera no se evapora, es mayor coste del bien y resta en IRPF.
     * Es la regla que la app ya modela en sus subcuentas 62x1 (V173).
     */
    @Test
    void elVehiculoAlCincuentaPorCiento() {
        SimLine l = linea("Furgoneta", "10000.00", "21", "50", true);

        assertEquals(0, new BigDecimal("2100.00").compareTo(l.totalVat()));
        assertEquals(0, new BigDecimal("1050.00").compareTo(l.deductibleVat()),
                "solo se recupera la mitad del IVA");
        assertEquals(0, new BigDecimal("12100.00").compareTo(l.cashOut()),
                "al proveedor se le paga el IVA entero");
        assertEquals(0, new BigDecimal("11050.00").compareTo(l.irpfExpense()),
                "base + el IVA que NO se recupera");
    }

    /**
     * Un gasto no deducible en IRPF (una multa, una invitación) no resta
     * nada del rendimiento aunque se haya pagado.
     */
    @Test
    void loQueNoEsDeducibleEnIrpfNoResta() {
        SimLine l = linea("Gasto no deducible", "500.00", "21", "0", false);
        assertEquals(0, BigDecimal.ZERO.compareTo(l.irpfExpense()));
        assertEquals(0, BigDecimal.ZERO.compareTo(l.deductibleVat()));
        assertEquals(0, new BigDecimal("605.00").compareTo(l.cashOut()),
                "pero pagarlo, se paga entero");
    }

    @Test
    void sinTipoDeIvaNoHayIvaQueRecuperar() {
        SimLine l = linea("Seguro (exento)", "1000.00", "0", "100", true);
        assertEquals(0, BigDecimal.ZERO.compareTo(l.totalVat()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(l.cashOut()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(l.irpfExpense()));
    }

    @Test
    void unaLineaVaciaNoRompeNada() {
        SimLine l = new SimLine("", null, null, null, true);
        assertEquals(0, BigDecimal.ZERO.compareTo(l.cashOut()));
        assertEquals(0, BigDecimal.ZERO.compareTo(l.irpfExpense()));
        assertEquals(0, BigDecimal.ZERO.compareTo(l.deductibleVat()));
    }

    // ====================================================================
    //  La regla que no se puede romper nunca
    // ====================================================================

    /**
     * Gastar SIEMPRE cuesta más de lo que ahorra. Con 21% de IVA y el 20% del
     * 130, lo máximo que se recupera de 1.000 € de base son 210 de IVA + 200
     * de IRPF = 410: el 34% de los 1.210 desembolsados.
     *
     * <p>Si este test se cayera, el simulador estaría diciéndole al cliente
     * que gastar le sale gratis, que es la forma de que la asesoría le
     * aconseje mal.
     */
    @Test
    void elAhorroNuncaLlegaAlDesembolso() {
        SimLine l = linea("Compra", "1000.00", "21", "100", true);

        BigDecimal ahorroIva = l.deductibleVat();                 // 210
        BigDecimal ahorroIrpf = l.irpfExpense()
                .multiply(new BigDecimal("0.20"));                // 200 como mucho
        BigDecimal ahorroMaximo = ahorroIva.add(ahorroIrpf);      // 410

        assertTrue(ahorroMaximo.compareTo(l.cashOut()) < 0,
                "el ahorro (" + ahorroMaximo + ") no puede alcanzar al gasto (" + l.cashOut() + ")");
        assertEquals(0, new BigDecimal("410.00").compareTo(ahorroMaximo));
    }

    // ====================================================================
    //  El regimen decide si esto sirve de algo
    // ====================================================================

    @Test
    void elAutonomoEnDirectaSiSeBeneficia() {
        assertEquals(Regime.ESTIMACION_DIRECTA,
                TaxRegimeResolver.classify("AUTONOMO", "ESTIMACION_DIRECTA"));
        assertTrue(TaxRegimeResolver.expensesReduceIrpf(Regime.ESTIMACION_DIRECTA));
    }

    /**
     * En módulos el pago sale de personal, metros y potencia: gastar más NO
     * baja el 131. El simulador tiene que decirlo o estaría engañando.
     */
    @Test
    void enModulosLosGastosNoBajanElPago() {
        assertEquals(Regime.MODULOS, TaxRegimeResolver.classify("AUTONOMO", "MODULOS"));
        assertEquals(Regime.MODULOS, TaxRegimeResolver.classify(null, "ESTIMACION_OBJETIVA"));
        assertFalse(TaxRegimeResolver.expensesReduceIrpf(Regime.MODULOS),
                "lo contrario seria mentirle al cliente");
    }

    @Test
    void unaSociedadNoPresentaElCientoTreinta() {
        assertEquals(Regime.SOCIEDAD, TaxRegimeResolver.classify("SL", null));
        assertEquals(Regime.SOCIEDAD, TaxRegimeResolver.classify("SA", "ESTIMACION_DIRECTA"));
        assertFalse(TaxRegimeResolver.expensesReduceIrpf(Regime.SOCIEDAD));
    }

    @Test
    void elRegimenFiscalMandaSobreLaFormaJuridica() {
        // Un autonomo cuya ficha diga MODULOS esta en modulos: no son datos
        // contradictorios, son dos cosas distintas.
        assertEquals(Regime.MODULOS, TaxRegimeResolver.classify("AUTONOMO", "MODULOS"));
    }

    /** Ficha a medias: se supone estimación directa, pero marcado como suposición. */
    @Test
    void fichaSinRellenarSeSuponeDirectaPeroSeAvisa() {
        assertEquals(Regime.DESCONOCIDO, TaxRegimeResolver.classify(null, null));
        assertEquals(Regime.DESCONOCIDO, TaxRegimeResolver.classify("", "  "));
        assertTrue(TaxRegimeResolver.expensesReduceIrpf(Regime.DESCONOCIDO),
                "fail-open, igual que la tarjeta del 130 del cuadro de mando");
    }
}
