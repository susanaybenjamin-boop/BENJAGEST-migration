package com.benjagest.backend.purchases.pdfimport;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Test del extractor v2 contra una transcripción real del PDF de
 * Bloques Los Llanos S.L. (factura 263274) que Benjamin reportó como
 * fallida en el primer intento.
 */
class InvoiceFieldsExtractorTotalsTest {

    private final InvoiceFieldsExtractor extractor = new InvoiceFieldsExtractor();

    @Test
    void losLlanos_extractsAllFieldsCorrectly() {
        // Texto reducido pero con todas las piezas relevantes que el
        // PdfTextExtractor sacaría del PDF real.
        String text = """
                Bloques Los Llanos, S.L.
                Pol. Ind. Las Canteras, S/N
                18193 Monachil (Granada)
                958 50 12 50
                BENJAMIN RECIO LOPEZ
                C/ HERRERIA 59
                18140 LA ZUBIA GRANADA
                susanaybenjamin@gmail.com
                Tfno. 670265576 NIF: 74668351R
                Ref.## c/ ibiza - ogijares
                FACTURA Página: 1 / 3
                Número Serie Fecha Cliente
                263274 1 31-05-2026 11755
                Ref. Artículo caja/palet uds/m2 Precio Importe
                Albarán: 262932 12-05-2026
                4889 SACA DE ARENA Fina Blanca 8.00 25,000 200,00
                SUMA IMPORTES % DTO DTO BASE IMPONIBLE % IVA CUOTA TOTAL A PAGAR
                1.205,68               1.205,68 21 253,19 1.458,87
                FORMA DE PAGO: Transferencia C. Rural ES3830230145981450003403
                Inscrita en el Registro Mercantil de Granada, tomo 737, folio 82, hoja nº GR/10606, Inscripción 1ª - CIF B18429258 Tlf: 958 50 12 50
                """;

        var r = extractor.extract(text);

        assertEquals("B18429258", r.emitterNif(),
                "El CIF del proveedor (pie de página) debe ganar al DNI del cliente");
        assertEquals("263274", r.invoiceNumber(),
                "El número de factura está en la tabla bajo 'Número Serie Fecha'");
        assertNotNull(r.invoiceDate(), "Debe leer la fecha");
        assertEquals(2026, r.invoiceDate().getYear());
        assertEquals(5, r.invoiceDate().getMonthValue());
        assertEquals(31, r.invoiceDate().getDayOfMonth());

        assertEquals(new BigDecimal("1205.68"), r.baseAmount(),
                "Base imponible de la fila de totales");
        assertEquals(new BigDecimal("21.00"), r.vatPercent(),
                "% IVA = 21");
        assertEquals(new BigDecimal("253.19"), r.vatAmount(),
                "Cuota IVA = 253,19");
        assertEquals(new BigDecimal("1458.87"), r.totalAmount(),
                "Total a pagar = 1.458,87");
        assertEquals(InvoiceFieldsExtractor.Confidence.HIGH, r.confidence(),
                "1.205,68 + 253,19 = 1.458,87 → validación cruzada OK");
    }
}
