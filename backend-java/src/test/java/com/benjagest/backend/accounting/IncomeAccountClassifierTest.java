package com.benjagest.backend.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ASI-3 (2026-07-15) — Fija el bug que reportó Benjamin: una factura de
 * servicios se contabilizaba en la 700 (Ventas de mercaderías) en vez de la
 * 705 (Prestaciones de servicios).
 *
 * <p>El classifier NUNCA estuvo roto — la regla 705 siempre funcionó. El bug
 * estaba en el caller ({@code SalesJournalEntryService:123}), que armaba el
 * texto a clasificar con {@code notes + invoiceNumber} y se dejaba fuera el
 * {@code concept}: justo el campo donde el usuario escribe qué ha vendido.
 * Sin concepto no matcheaba ninguna regla y caía al fallback 700, mientras la
 * descripción de la línea sí usaba el concepto — de ahí el asiento absurdo:
 * cuenta 700 rotulada "Prestación de servicios".
 *
 * <p>Estos tests cubren el classifier con las entradas que el caller le pasa
 * AHORA (con concepto) y las que le pasaba ANTES (sin él), demostrando la
 * diferencia de resultado.
 */
class IncomeAccountClassifierTest {

    private final IncomeAccountClassifierService classifier = new IncomeAccountClassifierService();

    /** El caso de Benjamin: con el concepto delante, sale 705. */
    @Test
    void concepto_de_servicios_clasifica_705() {
        String descConConcepto = "Prestación de servicios de albañilería"
                + " " + ""                 // notes vacío (lo normal)
                + " " + "FRA-2026-0009";   // invoiceNumber

        assertEquals(Optional.of("705"), classifier.classify(descConConcepto, "MIGUEL ANTONIO MARTÍN PALOMO"));
    }

    /**
     * Y sin el concepto — exactamente lo que recibía antes del fix — el
     * classifier no tiene con qué decidir y no propone nada, por lo que el
     * caller caía al fallback 700. Esta es la regresión que vigilamos.
     */
    @Test
    void sin_concepto_no_hay_propuesta_y_por_eso_caia_a_700() {
        String descSinConcepto = "" + " " + "FRA-2026-0009";

        assertTrue(classifier.classify(descSinConcepto, "MIGUEL ANTONIO MARTÍN PALOMO").isEmpty(),
                "sin concepto no debe haber propuesta: el caller cae al fallback 700");
    }

    /** Los acentos y las mayúsculas no deben importar (normalize + NFD). */
    @Test
    void tildes_y_minusculas_no_afectan() {
        assertEquals(Optional.of("705"), classifier.classify("prestacion de servicios", null));
        assertEquals(Optional.of("705"), classifier.classify("PRESTACIÓN DE SERVICIOS", null));
    }

    /** Otros conceptos de servicio que el usuario escribe a diario. */
    @Test
    void variantes_de_servicio_tambien_705() {
        assertEquals(Optional.of("705"), classifier.classify("Servicio de mantenimiento anual", null));
        assertEquals(Optional.of("705"), classifier.classify("Servicios de reparación de cubierta", null));
        assertEquals(Optional.of("705"), classifier.classify("Honorarios por dirección de obra", null));
    }

    /** Una venta de género real sigue yendo a la 700: el fix no la rompe. */
    @Test
    void venta_de_mercaderia_sigue_en_700() {
        assertEquals(Optional.of("700"), classifier.classify("Venta de material de construcción", null));
    }

    /**
     * El orden de las reglas importa: una rectificativa de venta debe ir a 708
     * (devoluciones) y no colarse por el catch-all 700, que también matchea
     * "VENTA". Lo comprobamos porque el concepto ahora entra en la mezcla y
     * las rectificativas llevan concepto propio.
     */
    @Test
    void rectificativa_va_a_708_y_no_al_catchall_700() {
        assertEquals(Optional.of("708"), classifier.classify("Rectificativa venta FRA-2026-0006", null));
        assertEquals(Optional.of("708"), classifier.classify("Devolución de mercadería", null));
    }
}
