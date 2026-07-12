package com.benjagest.backend.accounting.compensation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.benjagest.backend.accounting.compensation.InvoiceCompensationService.Alloc;
import com.benjagest.backend.accounting.compensation.InvoiceCompensationService.InvoiceRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * COMP-2 — reparto FIFO del importe compensable entre facturas. Verifica que
 * el reparto suma exactamente el compensable (asiento cuadrado), respeta el
 * pendiente de cada factura, va de más antigua a más nueva y parte la última.
 */
class InvoiceCompensationAllocTest {

    private static InvoiceRow inv(String id, String date, String pending) {
        BigDecimal p = new BigDecimal(pending);
        return new InvoiceRow(id, id, LocalDate.parse(date), p, p, "B1", "T");
    }

    private static BigDecimal sum(List<Alloc> allocs) {
        BigDecimal s = BigDecimal.ZERO;
        for (Alloc a : allocs) s = s.add(a.amount());
        return s;
    }

    @Test
    void repartoSumaExactamenteElCompensable() {
        // 300 a repartir entre compras de 400+50 (Σ=450 ≥ 300).
        List<Alloc> out = InvoiceCompensationService.allocateFifo(
                List.of(inv("C1", "2026-01-10", "400.00"), inv("C2", "2026-02-01", "50.00")),
                new BigDecimal("300.00"));
        assertEquals(new BigDecimal("300.00"), sum(out)); // cuadre exacto
    }

    @Test
    void fifoLaMasAntiguaPrimeroYParteLaUltima() {
        // Compensable 350: C1(100, ene) entera, C2(400, feb) parcial 250.
        List<Alloc> out = InvoiceCompensationService.allocateFifo(
                List.of(inv("C2", "2026-02-01", "400.00"), inv("C1", "2026-01-01", "100.00")),
                new BigDecimal("350.00"));
        assertEquals(2, out.size());
        assertEquals("C1", out.get(0).row().id());          // más antigua primero
        assertEquals(new BigDecimal("100.00"), out.get(0).amount()); // entera
        assertEquals("C2", out.get(1).row().id());
        assertEquals(new BigDecimal("250.00"), out.get(1).amount()); // parcial
        assertEquals(new BigDecimal("350.00"), sum(out));
    }

    @Test
    void noExcedeElPendienteDeCadaFactura() {
        List<Alloc> out = InvoiceCompensationService.allocateFifo(
                List.of(inv("C1", "2026-01-01", "100.00")),
                new BigDecimal("500.00")); // pido más de lo que hay
        assertEquals(1, out.size());
        assertEquals(new BigDecimal("100.00"), out.get(0).amount()); // capado al pendiente
        assertEquals(new BigDecimal("100.00"), sum(out));
    }

    @Test
    void ladoMenorSeSaldaEntero() {
        // Compensable = 450 (todas las compras): ambas enteras, nada parcial.
        List<Alloc> out = InvoiceCompensationService.allocateFifo(
                List.of(inv("C1", "2026-01-01", "400.00"), inv("C2", "2026-02-01", "50.00")),
                new BigDecimal("450.00"));
        assertEquals(2, out.size());
        assertEquals(new BigDecimal("450.00"), sum(out));
        assertTrue(out.stream().allMatch(a -> a.amount().compareTo(a.row().pending()) == 0));
    }
}
