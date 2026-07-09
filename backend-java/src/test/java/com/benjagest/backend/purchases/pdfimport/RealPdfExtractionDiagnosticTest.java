package com.benjagest.backend.purchases.pdfimport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * PDF-EXTRACT — arnés de DIAGNÓSTICO sobre PDFs reales (no es un test de
 * regresión: no asevera nada). Ejecuta el pipeline completo
 * (PdfTextExtractor → LayoutDocument → InvoiceFieldsExtractor.extractAll)
 * sobre todos los .pdf de un directorio y vuelca lo extraído, para comparar
 * a mano contra las facturas de verdad.
 *
 * <p>Uso:
 * <pre>
 *   mvn -pl backend-java test -Dtest=RealPdfExtractionDiagnosticTest \
 *       -Dbenjagest.pdf.dir="C:\ruta\con\pdfs" \
 *       [-Dbenjagest.tessdata="C:\Program Files\BENJAGEST\tessdata"]
 * </pre>
 * Sin {@code benjagest.pdf.dir} el test se salta (no molesta en CI).
 */
class RealPdfExtractionDiagnosticTest {

    /** Vuelca el TEXTO CRUDO (layout) de un único PDF, para diseñar heurísticas. */
    @Test
    @EnabledIfSystemProperty(named = "benjagest.pdf.file", matches = ".+")
    void dumpRawTextForOnePdf() throws Exception {
        Path pdf = Path.of(System.getProperty("benjagest.pdf.file"));
        PdfTextExtractor textExtractor = new PdfTextExtractor();
        byte[] bytes = Files.readAllBytes(pdf);
        LayoutDocument layout = textExtractor.extractLayout(bytes);
        System.out.println("### RAW TEXT de " + pdf.getFileName() + " ###");
        System.out.println(layout.toPlainText());
        System.out.println("### FIN RAW ###");
    }

    @Test
    @EnabledIfSystemProperty(named = "benjagest.pdf.dir", matches = ".+")
    void dumpExtractionForRealPdfs() throws Exception {
        Path dir = Path.of(System.getProperty("benjagest.pdf.dir"));
        PdfTextExtractor textExtractor = new PdfTextExtractor();
        InvoiceFieldsExtractor fields = new InvoiceFieldsExtractor();

        try (var stream = Files.list(dir)) {
            List<Path> pdfs = stream
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .sorted()
                    .toList();
            for (Path pdf : pdfs) {
                System.out.println("=================================================");
                System.out.println("PDF: " + pdf.getFileName());
                try {
                    byte[] bytes = Files.readAllBytes(pdf);
                    LayoutDocument layout = textExtractor.extractLayout(bytes);
                    var own = new InvoiceFieldsExtractor.OwnParty(
                            System.getProperty("benjagest.own.nif", ""),
                            System.getProperty("benjagest.own.name", ""));
                    var results = fields.extractAll(layout, bytes,
                            own.nif().isBlank() ? null : own);
                    if (results.isEmpty()) {
                        System.out.println("  (sin resultados)");
                    }
                    int i = 0;
                    for (var r : results) {
                        System.out.printf("  [%d] proveedor=%s | nifEmisor=%s | nifReceptor=%s | num=%s | fecha=%s | base=%s | iva%%=%s | cuota=%s | total=%s | concepto=%s | nifsVistos=%s%n",
                                i++, r.supplierName(), r.emitterNif(), r.receiverNif(),
                                r.invoiceNumber(), r.invoiceDate(), r.baseAmount(),
                                r.vatPercent(), r.vatAmount(), r.totalAmount(),
                                r.concept(), r.allDetectedNifs());
                    }
                } catch (Exception ex) {
                    System.out.println("  ERROR: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
            }
        }
    }
}
