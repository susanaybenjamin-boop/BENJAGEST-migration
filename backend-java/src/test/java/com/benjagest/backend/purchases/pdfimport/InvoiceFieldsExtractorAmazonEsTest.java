package com.benjagest.backend.purchases.pdfimport;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Test contra la transcripción REAL (vía dump del LayoutDocument) del
 * PDF "LAPICES Y TENAZAS.pdf" de Amazon España. Es una factura del
 * caso Amazon ES Sucursal — el NIF fiscal es el W español, no el LU.
 *
 * Lo que falló en producción y que este test bloquea:
 *
 *   - El extractor cogía LU20260743 como emisor. Debe coger W0184081H.
 *   - El nº de factura no se detectaba: "Número del documento" usa
 *     "del" (contracción), la regex solo aceptaba "de la / de el".
 *   - Base y cuota IVA llegaban vacías o cogían el unit price del
 *     primer item. La firma "21% 35,09 € 7,37 €" no se reconocía y la
 *     línea "Total 35,09 € 7,37 €" (subtotal IVA de la tabla) se
 *     confundía con el total general.
 */
class InvoiceFieldsExtractorAmazonEsTest {

    private final InvoiceFieldsExtractor extractor = new InvoiceFieldsExtractor();

    @Test
    void amazonEs_extractsFiscalNifAndTotals() {
        // Transcripción exacta del LayoutDocument.toPlainText() volcado
        // del PDF real. Conservar el orden y los espacios.
        String text = """
                Pagado
                Nº de referencia de pago 3UTxbIFfI2W1wDLCfXBS
                Vendido por Amazon EU S.à r.l., Sucursal en España
                IVA ESW0184081H
                Fecha de envío          10 abril 2026
                BENJAMIN RECIO LOPEZ
                Número del documento       ES6N2S4JAEUS
                LA ZUBIA, GR, C/ HERRERIA Nº59
                Total pendiente          42,46 €
                LA ZUBIA, GRANADA, 18140
                ES
                Si tienes preguntas sobre tus pedidos, visita https://www.amazon.es/contacto
                Dirección de facturación Dirección de envío Vendido por
                BENJAMIN RECIO LOPEZ BENJAMIN RECIO LOPEZ Amazon EU S.à r.l., Sucursal en España
                LA ZUBIA, GR, C/ HERRERIA Nº59 LA ZUBIA, GR, C/ HERRERIA Nº59 Calle de Ramírez de Prado 5
                LA ZUBIA, GRANADA, 18140 LA ZUBIA, GRANADA, 18140 28045 Madrid
                ES ES España
                IVA ESW0184081H
                Información del pedido
                Fecha del pedido          08 abril 2026
                Número del pedido         407-7386100-0393934
                Detalles del documento
                Descripción          Cant.     P. Unitario  IVA %     P. Unitario    Precio total
                (IVA excluido)         (IVA incluido)   (IVA incluido)
                Staedtler Carpenter Pencils 148 50. Conjunto de 12 lápices para carpinteros     1       12,99 €   21% 15,72 €       15,72 €
                de dureza alta 6H.
                ASIN: B007M8QZQQ
                KNIPEX Tenaza rusa de fuerza gran efecto palanca recubiertos de plástico      1       22,10 €   21% 26,74 €       26,74 €
                300 mm, 99 11 300
                ASIN: B00202HMWU
                Envío          0,00 €          0,00 €       0,00 €
                Total          42,46 €
                IVA %          Precio total IVA
                (IVA excluido)
                21%          35,09 €       7,37 €
                Total          35,09 €       7,37 €
                Nº Registro Integrado Industrial 6297 (AEE) / 1762 (Pilas y Acumuladores)
                LU-BIO-04
                Amazon EU S.à r.l. - 38 avenue John F. Kennedy, L-1855 Luxemburgo
                R.C.S. Luxemburgo: B 101818 • Capital Social: 37.500 EUR • Número de Registro de IVA: LU20260743
                Amazon EU S.à r.l., Sucursal en España – Calle de Ramírez de Prado 5, 28045 Madrid, España
                Registro Mercantil de Madrid • Tomo 33.166, Libro 0, Folio 105, Seccion 8, Hoja M-596.819 • NIF W0184081H
                Página 1 de 1
                """;

        var r = extractor.extract(text);

        assertEquals("W0184081H", r.emitterNif(),
                "El NIF fiscal en España es el W (sucursal), no el LU (matriz)");
        assertNotNull(r.supplierName(), "Debe leer el proveedor");
        assertTrue(r.supplierName().toLowerCase().contains("amazon"));
        assertEquals("ES6N2S4JAEUS", r.invoiceNumber(),
                "Número de documento — 'del' debe matchearse");
        assertNotNull(r.invoiceDate(), "Debe leer fecha");
        assertEquals(2026, r.invoiceDate().getYear());
        assertEquals(4, r.invoiceDate().getMonthValue());
        assertEquals(10, r.invoiceDate().getDayOfMonth());
        assertEquals(new BigDecimal("35.09"), r.baseAmount(),
                "Base imponible — firma '21% 35,09 € 7,37 €'");
        assertEquals(new BigDecimal("21.00"), r.vatPercent(),
                "% IVA = 21");
        assertEquals(new BigDecimal("7.37"), r.vatAmount(),
                "Cuota IVA = 7,37 €");
        assertEquals(new BigDecimal("42.46"), r.totalAmount(),
                "Total = 42,46 € (cross-validation con base + iva)");
        assertEquals(InvoiceFieldsExtractor.Confidence.HIGH, r.confidence(),
                "35,09 + 7,37 = 42,46 → validación cruzada OK");
    }
}
