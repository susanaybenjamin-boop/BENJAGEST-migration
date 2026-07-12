package com.benjagest.backend.accounting.compensation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.benjagest.backend.accounting.compensation.InvoiceCompensationService.CompensationProposal;
import com.benjagest.backend.accounting.compensation.InvoiceCompensationService.Line;
import com.benjagest.backend.accounting.compensation.InvoiceCompensationService.RawLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * COMP-1 — cruce puro venta↔compra por NIF. Verifica la lógica de agrupación,
 * normalización de NIF, el importe compensable (menor de los dos) y el
 * filtrado de pendientes. Sin BD (patrón compute130).
 */
class InvoiceCompensationCrossMatchTest {

    private static Line line(String id, String amount) {
        BigDecimal a = new BigDecimal(amount);
        return new Line(id, id, LocalDate.of(2026, 1, 1), null, a, a, true);
    }

    private static RawLine sale(String nif, String name, String amount) {
        return new RawLine(nif, name, line("V-" + amount, amount));
    }

    private static RawLine purchase(String nif, String name, String amount) {
        return new RawLine(nif, name, line("C-" + amount, amount));
    }

    @Test
    void compensableEsElMenorDeLosDosLados() {
        // Venta 1.000 (me deben) vs compra 300 (yo debo) del mismo NIF.
        List<CompensationProposal> out = InvoiceCompensationService.crossMatch(
                List.of(sale("B12345678", "ACME SL", "1000.00")),
                List.of(purchase("B12345678", "ACME SL", "300.00")));

        assertEquals(1, out.size());
        CompensationProposal p = out.get(0);
        assertEquals(new BigDecimal("1000.00"), p.salesPending());
        assertEquals(new BigDecimal("300.00"), p.purchasePending());
        assertEquals(new BigDecimal("300.00"), p.compensable()); // el menor
    }

    @Test
    void casaAunqueElNifVengaConPuntosYGuiones() {
        // Mismo tercero escrito distinto en venta y compra → debe casar.
        List<CompensationProposal> out = InvoiceCompensationService.crossMatch(
                List.of(sale("B-1234.5678", "ACME", "500.00")),
                List.of(purchase("b12345678", "ACME", "800.00")));

        assertEquals(1, out.size());
        assertEquals(new BigDecimal("500.00"), out.get(0).compensable());
    }

    @Test
    void sumaVariasFacturasDeCadaLado() {
        // Alcance: varias facturas de cada lado del mismo NIF.
        List<CompensationProposal> out = InvoiceCompensationService.crossMatch(
                List.of(sale("X1", "T", "100.00"), sale("X1", "T", "250.00")),
                List.of(purchase("X1", "T", "400.00"), purchase("X1", "T", "50.00")));

        assertEquals(1, out.size());
        CompensationProposal p = out.get(0);
        assertEquals(new BigDecimal("350.00"), p.salesPending());   // 100+250
        assertEquals(new BigDecimal("450.00"), p.purchasePending()); // 400+50
        assertEquals(new BigDecimal("350.00"), p.compensable());     // menor
        assertEquals(2, p.sales().size());
        assertEquals(2, p.purchases().size());
    }

    @Test
    void sinCompraDelMismoNifNoHayPropuesta() {
        List<CompensationProposal> out = InvoiceCompensationService.crossMatch(
                List.of(sale("A", "T", "100.00")),
                List.of(purchase("B", "T", "100.00"))); // NIF distinto
        assertTrue(out.isEmpty());
    }

    @Test
    void ignoraPendientesCeroONegativos() {
        List<CompensationProposal> out = InvoiceCompensationService.crossMatch(
                List.of(sale("N", "T", "0.00")),        // venta sin pendiente
                List.of(purchase("N", "T", "300.00")));
        assertTrue(out.isEmpty());
    }

    @Test
    void ordenaPorCompensableDescendente() {
        List<CompensationProposal> out = InvoiceCompensationService.crossMatch(
                List.of(sale("PEQ", "T", "50.00"), sale("GRA", "T", "9000.00")),
                List.of(purchase("PEQ", "T", "50.00"), purchase("GRA", "T", "9000.00")));

        assertEquals(2, out.size());
        assertEquals("GRA", out.get(0).nif()); // mayor compensable primero
        assertEquals("PEQ", out.get(1).nif());
    }
}
