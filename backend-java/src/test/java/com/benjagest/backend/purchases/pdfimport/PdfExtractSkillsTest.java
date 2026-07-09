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

    // ---- PDF-EXTRACT-2: fila de totales de 5 números (taller) ------------

    @Test
    void arithmeticTotals_fiveNumbersWorkshopRow() {
        // REPARACION FURGONETA (OCR): "base %iva dto cuota total". Antes, el
        // código postal "18140, LAZUBIA... 18140 LA ZUBIA" se troceaba en
        // 181+40−181=40 y colaba como totales (base=181, total=40).
        String text = """
                Cmno. de Gojar, Bajo 6
                18140, LAZUBIA, GRANADA Codigo Postal: 18140 LA ZUBIA
                Tfno. 958 59 27 49 Provincia: GRANADA
                Mano Obra
                HACER DIAGNSIS DE AVERIA, CAMBIO DE INYECTOR
                , PROGRAMAR Y COMPROBAR FUNCIONAMIENTO. 2.00 39.00 78.00
                INYECTOR DELPHI 1.00 234.85 234.85
                402.85 21.00 0.00 84.60 487.45
                CONFORME EL CLIENTE Reparacion garantizada, turismos, 3 meses o 2000 Km. Vehiculos industriales, 15 dias o 2000 Km.
                """;
        var r = extractor.extract(text);
        assertEquals(0, new BigDecimal("402.85").compareTo(r.baseAmount()));
        assertEquals(0, new BigDecimal("84.60").compareTo(r.vatAmount()));
        assertEquals(0, new BigDecimal("487.45").compareTo(r.totalAmount()));
        assertEquals(0, new BigDecimal("21.00").compareTo(r.vatPercent()));
        assertEquals("HACER DIAGNSIS DE AVERIA, CAMBIO DE INYECTOR", r.concept());
    }

    // ---- PDF-EXTRACT-2: "Desglose de Impuestos" (Hermanos Arenas) --------

    @Test
    void desgloseTotals_hermanosArenas() {
        String text = """
                DROGUERIA HERMANOS ARENAS S.L.
                Ref Producto Cant Impuesto Base Dto
                00470596 KI PROTECTOR NOOLORO VI-480 Satinado 0,75L 1 21% 10,9917
                Desglose de Impuestos: Impuesto 6,21 € al 21% sobre 29,59 €
                """;
        var r = extractor.extract(text);
        assertEquals(0, new BigDecimal("29.59").compareTo(r.baseAmount()));
        assertEquals(0, new BigDecimal("6.21").compareTo(r.vatAmount()));
        assertEquals(0, new BigDecimal("35.80").compareTo(r.totalAmount()));
        assertEquals("KI PROTECTOR NOOLORO VI-480 Satinado 0,75L", r.concept());
    }

    // ---- PDF-EXTRACT-2: totales en vertical (presupuesto A-024) ----------

    @Test
    void verticalTotals_handmadeInvoiceA024() {
        String text = """
                FACTURA: A-024
                Miguel Antonio Martin Palomo C/ Ermita 52
                D.N.I. 24 259 998 N
                CODIGO RESUMEN CANTIDAD PRECIO IMPORTE
                Reforma de cocina en C/ herreria,59 La Zubia Granada
                total capitulo 2.511,15 €
                TOTAL PRESUPUESTO CONTRATA 2.511,15 €
                10 %  I.V.A 251,11 €
                2.762,26 €
                Granada, 21 de Abril de 2026
                """;
        var r = extractFor(text);
        assertEquals("24259998N", r.emitterNif());
        assertEquals("Miguel Antonio Martin Palomo", r.supplierName());
        assertEquals("A-024", r.invoiceNumber());
        assertEquals(0, new BigDecimal("2511.15").compareTo(r.baseAmount()));
        assertEquals(0, new BigDecimal("251.11").compareTo(r.vatAmount()));
        assertEquals(0, new BigDecimal("2762.26").compareTo(r.totalAmount()));
        assertEquals(java.time.LocalDate.of(2026, 4, 21), r.invoiceDate());
        assertEquals("Reforma de cocina en C/ herreria,59 La Zubia Granada", r.concept());
    }

    // ---- PDF-EXTRACT-2: número de factura --------------------------------

    @Test
    void invoiceNumber_periodDateRejected_solred() {
        // Solred marzo: la 1ª línea "Núm. Factura" trae el PERIODO facturado
        // (una fecha); el nº real va en la 2ª. Antes devolvía "01/03/2026".
        String text = """
                Núm. Factura     01/03/2026 AL 31/03/2026
                Núm. Factura          SMP260057590
                Lugar y Fecha        MADRID - 31/03/2026
                """;
        var r = extractor.extract(text);
        assertEquals("SMP260057590", r.invoiceNumber());
        assertEquals(java.time.LocalDate.of(2026, 3, 31), r.invoiceDate());
    }

    @Test
    void invoiceNumber_ocrGarbledTableHeader_shAsesores() {
        // SH Asesores: el QR pisa el nº de factura y NO es recuperable (el
        // "229" visible es el CÓDIGO DE CLIENTE, idéntico todos los meses —
        // un nº falso repetido dispararía la detección de duplicados). La
        // fecha tampoco existe como dd/mm/yyyy — se deriva de "(enero 2026)"
        // → día 1 del mes (mismo mes/trimestre; el usuario la ajusta).
        String text = """
                SH ASESORES VERGELES, S.L.
                NIF B19682756
                NUDE FACTURA IDENTIFICACIÓN CLIENTE
                229 RECIO LOPEZ BENJAMIN
                12 ASESORIA FISCAL Y CONTABLE (enero 2026) 1,00 42,31 42,31 21,00
                42,31 21.00 51,20
                """;
        var r = extractor.extract(text);
        assertNull(r.invoiceNumber(), "el código de cliente 229 no debe salir como nº de factura");
        assertEquals(java.time.LocalDate.of(2026, 1, 1), r.invoiceDate());
        assertEquals("ASESORIA FISCAL Y CONTABLE (enero 2026)", r.concept());
    }

    @Test
    void invoiceNumber_clientNumberIgnored_abbrevDate_forjados() {
        // Forjados: "Nº Cliente: 1391" NO es el nº de factura (es "A26- 628",
        // partido por espacio) y la fecha real va abreviada "24-mar.-2026";
        // la del albarán (20/03/2026) se ignora.
        String text = """
                Artículo          Cantidad   Precio   Dto % Importe
                Página: 1 de 1
                FACTURA         Fecha
                A26- 628      24-mar.-2026
                D. BENJAMIN RECIO LOPEZ
                74668351R
                Nº Cliente: 1391
                Artículo          Cantidad    Precio    Dto.    Importe
                Albarán nº: 2740 Fecha: 20/03/2026
                VIGA ARMADA DE 4.70 M V-5          12,00     19,50    0,00      234,05
                Base    Cuota IVA  Retención IRPF  TOTAL FACTURA
                588,84      123,66         0,00        712,50€
                Forjados La Azucena, S.L. en cumplimiento de la Ley Orgánica
                """;
        var r = extractFor(text);
        assertEquals("A26-628", r.invoiceNumber());
        assertEquals(java.time.LocalDate.of(2026, 3, 24), r.invoiceDate());
        assertEquals("VIGA ARMADA DE 4.70 M V-5", r.concept());
    }

    // ---- PDF-EXTRACT-2: concepto ------------------------------------------

    @Test
    void concept_tableWithConceptoHeader_solred() {
        String text = """
                Base     Tipo     Cuota
                Concepto          Cantidad          Importe
                Imponible  I.V.A.    I.V.A.
                DIESEL E+ NEOTECH (L)          58,45          82,64    21%       17,36      100,00
                Total Factura en Euros          183,93          38,63      222,56
                """;
        var r = extractor.extract(text);
        assertEquals("DIESEL E+ NEOTECH (L)", r.concept());
    }

    @Test
    void concept_longOnesTruncatedWithEllipsis() {
        String longConcept = "SUMINISTRO Y COLOCACION DE TARIMA FLOTANTE DE ROBLE "
                + "NATURAL EN SALON COMEDOR Y DORMITORIOS INCLUYENDO RODAPIE";
        var r = extractor.extract("""
                Concepto Cantidad Precio Importe
                %s 1,00 500,00 500,00
                """.formatted(longConcept));
        assertNotNull(r.concept());
        assertTrue(r.concept().length() <= 81, "recortado: " + r.concept());
        assertTrue(r.concept().endsWith("…"));
    }

    @Test
    void concept_nullWhenNoLineItems() {
        var r = extractor.extract("""
                FACTURA 2026-001
                Total 100,00
                """);
        assertNull(r.concept());
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
