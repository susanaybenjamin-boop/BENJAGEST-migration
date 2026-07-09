package com.benjagest.backend.purchases.pdfimport;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * PDF-EXTRACT-1 (2026-07-09) — regresión de las "skills" añadidas al
 * extractor tras el diagnóstico con los 27 PDFs reales de Benjamin (T1+T2
 * 2026). Fragmentos saneados, mismas formas que los PDFs reales.
 */
class PdfExtractSkillsTest {

    private final InvoiceFieldsExtractor extractor = new InvoiceFieldsExtractor();
    private static final InvoiceFieldsExtractor.OwnParty OWN =
            new InvoiceFieldsExtractor.OwnParty("74668351R", "BENJAMIN RECIO LOPEZ");

    // ---- isOwnNif: exacto y mutado por OCR/text-layer -----------------

    @Test
    void ownNif_matchesExactAndOcrMutations() {
        assertTrue(InvoiceFieldsExtractor.isOwnNif("74668351R", OWN));
        assertTrue(InvoiceFieldsExtractor.isOwnNif("74668351N", OWN)); // letra mutada
        assertTrue(InvoiceFieldsExtractor.isOwnNif("7466835N", OWN));  // dígito perdido
        assertFalse(InvoiceFieldsExtractor.isOwnNif("A79707345", OWN)); // Solred
        assertFalse(InvoiceFieldsExtractor.isOwnNif("B18429258", OWN)); // Los Llanos
        assertFalse(InvoiceFieldsExtractor.isOwnNif("B19682756", OWN)); // SH Asesores
    }

    // ---- nombres basura + rescate por sufijo societario ----------------

    @Test
    void junkSupplierNames_rejected() {
        assertTrue(InvoiceFieldsExtractor.isJunkSupplierName(
                "Artículo          Cantidad   Precio   Dto % Importe", OWN));
        assertTrue(InvoiceFieldsExtractor.isJunkSupplierName(
                "Núm. de Cuenta 460000250618 RECIO LOPEZ BENJAMIN", OWN));
        assertTrue(InvoiceFieldsExtractor.isJunkSupplierName("SH QR Tributario:", OWN));
        assertTrue(InvoiceFieldsExtractor.isJunkSupplierName(null, OWN));
        assertFalse(InvoiceFieldsExtractor.isJunkSupplierName(
                "Bloques Los Llanos, S.L.", OWN));
    }

    @Test
    void corporateNameFallback_findsNameInLegalFooter() {
        // Forjados La Azucena: la razón social solo aparece en el pie LOPD.
        String text = """
                Artículo          Cantidad   Precio   Dto % Importe
                D. CLIENTE EJEMPLO
                74668351R
                Forjados La Azucena, S.L. en cumplimiento de la Ley Orgánica de protección de datos
                """;
        assertEquals("Forjados La Azucena, S.L.",
                InvoiceFieldsExtractor.findCorporateNameFallback(text, OWN));
    }

    // ---- detector aritmético de totales ---------------------------------

    @Test
    void arithmeticTotals_basePctTotal_shAsesores() {
        // Forma de la factura de SH Asesores tras OCR: "base pct total".
        String text = """
                ASESORIA FISCAL Y CONTABLE (febrero) 1,00 42,31 42,31 21,00
                42,31 21.00 51,20
                Concep. y Supl. 51,20
                """;
        var r = extractor.extract(text);
        assertEquals(0, new BigDecimal("42.31").compareTo(r.baseAmount()));
        assertEquals(0, new BigDecimal("51.20").compareTo(r.totalAmount()));
        assertEquals(0, new BigDecimal("8.89").compareTo(r.vatAmount()));
    }

    @Test
    void arithmeticTotals_baseCuotaRetTotal_forjados() {
        // Forma de Forjados: fila de etiquetas separada de la de valores.
        String text = """
                Base    Cuota IVA  Retención IRPF  TOTAL FACTURA
                588,84      123,66         0,00        712,50
                """;
        var r = extractor.extract(text);
        assertEquals(0, new BigDecimal("588.84").compareTo(r.baseAmount()));
        assertEquals(0, new BigDecimal("123.66").compareTo(r.vatAmount()));
        assertEquals(0, new BigDecimal("712.50").compareTo(r.totalAmount()));
    }

    // ---- exclusión del NIF propio en la extracción completa -------------

    @Test
    void ownNifNeverBecomesEmitter() {
        // Como FRA-A26 de Forjados: el ÚNICO NIF del texto es el del cliente.
        String text = """
                FACTURA         Fecha
                A26- 628      24-mar.-2026
                D. BENJAMIN RECIO LOPEZ
                74668351R
                Base    Cuota IVA  Retención IRPF  TOTAL FACTURA
                588,84      123,66         0,00        712,50
                Forjados La Azucena, S.L. en cumplimiento de la Ley Orgánica
                """;
        var r = extractFor(text);
        assertNull(r.emitterNif(), "el NIF propio no puede salir como emisor");
        assertEquals("Forjados La Azucena, S.L.", r.supplierName());
    }

    private InvoiceFieldsExtractor.ExtractionResult extractFor(String text) {
        // vía pública con OwnParty: montamos un layout sintético de una línea
        // por renglón (mismo camino que el OCR).
        LayoutDocument doc = new LayoutDocument();
        LayoutDocument.LayoutPage page = new LayoutDocument.LayoutPage(1, 595f, 842f);
        float y = 20f;
        for (String line : text.split("\\n")) {
            if (line.isBlank()) continue;
            java.util.List<LayoutDocument.LayoutSpan> spans = new java.util.ArrayList<>();
            spans.add(new LayoutDocument.LayoutSpan(line.strip(), 20f, y, 400f, 10f));
            page.addLine(new LayoutDocument.LayoutLine(y, spans));
            y += 12f;
        }
        doc.addPage(page);
        return extractor.extractAll(doc, null, OWN).get(0);
    }

    // ---- detector de text-layer entrelazado ------------------------------

    @Test
    void scrambledTextLayer_detected_cleanOnesNot() {
        String scrambled = """
                SH          QR Tributario:
                CGA LAESETOSRORREE SD E VLEORSGELES, S.L.
                RL          1 8A0B0E8NCEGRRRAANJAEDSA 14, T
                NIF    B197686227056
                Pagadera FEoSr m3a5 d0e1 8P2a-g6o1 2:9 -R9E6C-I0B0 1B6A*N*C*A*R*IO
                """;
        assertTrue(PdfTextExtractor.needsOcrFallback(scrambled));
        // Amazon: referencias raras PERO NIF etiquetado limpio → texto nativo.
        String amazon = """
                Amazon EU S.à r.l., Sucursal en España NIF W0184081H
                Referencia del pedido 3RCWH8CTFNVTGBIMFXXW
                Pago 3RCWH8CTFNVTGBIMFXXW factura 1LM2MMFYEZRT9T7XT9MH
                Total 26,29 € ref 8H2K9L3M4N5P6Q7R
                """;
        assertFalse(PdfTextExtractor.needsOcrFallback(amazon));
        assertFalse(PdfTextExtractor.needsOcrFallback(
                "FACTURA normal\nBloques Los Llanos S.L.\nNIF B18429258\nTotal 100,00"));
    }
}
