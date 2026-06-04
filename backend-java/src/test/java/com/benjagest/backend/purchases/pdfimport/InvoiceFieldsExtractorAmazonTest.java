package com.benjagest.backend.purchases.pdfimport;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Test del extractor v2 contra una transcripción real del PDF de
 * Amazon (cinta métrica Milwaukee) — factura intracomunitaria con
 * proveedor extranjero (Amazon EU S.à r.l. en Luxemburgo).
 */
class InvoiceFieldsExtractorAmazonTest {

    private final InvoiceFieldsExtractor extractor = new InvoiceFieldsExtractor();

    @Test
    void amazon_intracom_extractsAllFields() {
        String text = """
                Factura
                Página 1 de 1
                Nº Registro Integrado Industrial 6297 (AEE) / 1762 (Pilas y Acumuladores)
                LU-BIO-04
                Amazon EU S.à r.l. - 38 avenue John F. Kennedy, L-1855 Luxemburgo
                R.C.S. Luxemburgo: B 101818 • Capital Social: 37.500 EUR • Número de Registro de IVA: LU20260743
                IVA % Precio total
                (IVA excluido)
                IVA
                21% 21,73 € 4,56 €
                Total 21,73 € 4,56 €
                Total 26,29 €
                Detalles de la factura
                Fecha del pedido 10 abril 2026
                Número del pedido 407-8690647-2049155
                Pagado
                Nº de referencia de pago 11oPIZSbseaFNU2hu9dK
                Vendido por Amazon EU S.à r.l., Sucursal en España
                IVA LU20260743
                Fecha de la factura/Fecha de la entrega 11 abril 2026
                Número de la factura LU61XJG27AEUI
                Total pendiente 26,29 €
                Dirección de facturación
                BENJAMIN RECIO LOPEZ
                LA ZUBIA, GR, C/ HERRERIA Nº59
                LA ZUBIA, GRANADA, 18140
                """;

        var r = extractor.extract(text);

        assertEquals("LU20260743", r.emitterNif(),
                "Amazon EU usa IVA LU intracomunitario");
        // Acepta variantes posibles del proveedor; lo importante es que
        // contenga "Amazon".
        assertNotNull(r.supplierName(), "Debe leer el proveedor");
        assertTrue(r.supplierName().toLowerCase().contains("amazon"),
                "El proveedor debe contener 'Amazon'");
        assertEquals("LU61XJG27AEUI", r.invoiceNumber(),
                "Número de la factura LU61XJG27AEUI");
        assertNotNull(r.invoiceDate(), "Debe leer fecha 'DD mes YYYY'");
        assertEquals(2026, r.invoiceDate().getYear());
        assertEquals(4, r.invoiceDate().getMonthValue());
        assertEquals(11, r.invoiceDate().getDayOfMonth());

        assertEquals(new BigDecimal("21.73"), r.baseAmount(),
                "Base imponible = 21,73 €");
        assertEquals(new BigDecimal("21.00"), r.vatPercent(),
                "% IVA = 21");
        assertEquals(new BigDecimal("4.56"), r.vatAmount(),
                "Cuota IVA = 4,56 €");
        assertEquals(new BigDecimal("26.29"), r.totalAmount(),
                "Total = 26,29 €");
    }
}
