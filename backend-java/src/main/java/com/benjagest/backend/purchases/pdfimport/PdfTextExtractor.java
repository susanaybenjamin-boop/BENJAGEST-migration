package com.benjagest.backend.purchases.pdfimport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;

/**
 * Saca el texto de un PDF en dos modos:
 *
 *   1) {@link #extract(byte[])} — texto plano, retro-compatible.
 *   2) {@link #extractLayout(byte[])} — preserva posiciones X/Y de cada
 *      glyph y agrupa por líneas con tolerancia. Las facturas y los
 *      calendarios laborales son tablas: con texto plano se mezclan
 *      etiqueta y valor de columnas distintas, y los regex fallan.
 *
 * El motor de layout es equivalente al {@code reconstructPageLayout}
 * que CONTENDO tiene en {@code ocrEngine.js} sobre pdfjs-dist —
 * misma idea, hecha en Java con PDFBox extendiendo
 * {@link PDFTextStripper} y leyendo cada {@link TextPosition}.
 *
 * Si el PDF no contiene texto seleccionable (escaneado), ambos métodos
 * devuelven contenido vacío — el caller decide si rechaza con mensaje o
 * delega en OCR (Tess4J, slice futuro).
 */
@Service
public class PdfTextExtractor {

    /** Tolerancia Y (en puntos PDF) para agrupar spans en la misma línea. */
    private static final float LINE_Y_TOLERANCE = 3.0f;

    /** Máximo de páginas que procesamos para no bloquear con PDFs gigantes. */
    private static final int MAX_PAGES = 20;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PdfTextExtractor.class);

    public String extract(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            // OCR-TESSERACT: si el PDF no trae texto seleccionable (escaneado),
            // caemos a OCR sobre las páginas renderizadas.
            if (text == null || text.replaceAll("\\s", "").length() < 15) {
                String ocr = ocr(pdfBytes);
                if (ocr != null && !ocr.isBlank()) return ocr;
            }
            return text == null ? "" : text;
        }
    }

    /**
     * OCR-TESSERACT — texto de un PDF escaneado renderizando cada página a
     * imagen (300 DPI) y pasándola por Tesseract (idiomas spa+eng). El binario
     * nativo + tessdata deben estar en el sistema (TESSDATA_PREFIX). Si no, NO
     * rompe: devuelve "" y el caller sigue como antes.
     */
    private String ocr(byte[] pdfBytes) {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            net.sourceforge.tess4j.Tesseract tess = new net.sourceforge.tess4j.Tesseract();
            String dp = System.getenv("TESSDATA_PREFIX");
            if (dp != null && !dp.isBlank()) tess.setDatapath(dp);
            tess.setLanguage("spa+eng");
            org.apache.pdfbox.rendering.PDFRenderer renderer =
                    new org.apache.pdfbox.rendering.PDFRenderer(doc);
            int pages = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pages; i++) {
                java.awt.image.BufferedImage img = renderer.renderImageWithDPI(i, 300);
                sb.append(tess.doOCR(img)).append("\n");
            }
            return sb.toString();
        } catch (Throwable ex) {
            // UnsatisfiedLinkError / TesseractException / falta tessdata → degradar.
            log.warn("OCR-TESSERACT no disponible o falló ({}). Se ignora el escaneado.",
                    ex.getMessage());
            return "";
        }
    }

    /**
     * Extracción CON layout. Devuelve un {@link LayoutDocument} con las
     * páginas, líneas y spans posicionados. Las líneas vienen ordenadas
     * top→bottom y dentro de cada una los spans left→right.
     */
    public LayoutDocument extractLayout(byte[] pdfBytes) throws IOException {
        LayoutDocument out = new LayoutDocument();
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int pages = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            for (int i = 0; i < pages; i++) {
                PDPage page = doc.getPage(i);
                LayoutCollector collector = new LayoutCollector(i + 1,
                        page.getMediaBox().getWidth(),
                        page.getMediaBox().getHeight());
                collector.setStartPage(i + 1);
                collector.setEndPage(i + 1);
                collector.setSortByPosition(true);
                // Necesario para que PDFBox invoque writeString por bloques
                // y no agrupe sin pasar por nuestra captura.
                collector.getText(doc);
                out.addPage(collector.toPage());
            }
        }
        return out;
    }

    /**
     * Sub-clase de {@link PDFTextStripper} que captura cada
     * {@link TextPosition} y los agrupa por líneas Y con tolerancia.
     */
    private static final class LayoutCollector extends PDFTextStripper {

        private final int pageNumber;
        private final float pageWidth;
        private final float pageHeight;
        private final List<LayoutDocument.LayoutSpan> rawSpans = new ArrayList<>();

        LayoutCollector(int pageNumber, float pageWidth, float pageHeight) throws IOException {
            super();
            this.pageNumber = pageNumber;
            this.pageWidth = pageWidth;
            this.pageHeight = pageHeight;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (text == null || text.isBlank() || textPositions == null || textPositions.isEmpty()) {
                return;
            }
            // Cada writeString suele recibir una "palabra" o un fragmento
            // ya alineado. Tomamos la primera y última posición para
            // calcular x, y, width totales.
            TextPosition first = textPositions.get(0);
            TextPosition last = textPositions.get(textPositions.size() - 1);
            float x = first.getXDirAdj();
            float y = first.getYDirAdj();
            float width = (last.getXDirAdj() + last.getWidthDirAdj()) - x;
            float height = Math.max(first.getHeightDir(), 1f);
            String clean = text.replace(' ', ' ').strip();
            if (clean.isEmpty()) return;
            rawSpans.add(new LayoutDocument.LayoutSpan(clean, x, y, Math.max(width, 1f), height));
        }

        LayoutDocument.LayoutPage toPage() {
            LayoutDocument.LayoutPage page = new LayoutDocument.LayoutPage(
                    pageNumber, pageWidth, pageHeight);
            if (rawSpans.isEmpty()) return page;

            // Ordenar top→bottom (Y crece hacia abajo en getYDirAdj) y
            // left→right como segundo criterio.
            List<LayoutDocument.LayoutSpan> spans = new ArrayList<>(rawSpans);
            spans.sort(Comparator
                    .comparingDouble(LayoutDocument.LayoutSpan::y)
                    .thenComparingDouble(LayoutDocument.LayoutSpan::x));

            // Agrupar en líneas: si dos spans tienen |Δy| ≤ tolerancia,
            // misma línea. La tolerancia escala con la altura del texto.
            List<LayoutDocument.LayoutSpan> currentLine = new ArrayList<>();
            float currentY = spans.get(0).y();
            for (LayoutDocument.LayoutSpan s : spans) {
                float tol = Math.max(s.height() * 0.5f, LINE_Y_TOLERANCE);
                if (Math.abs(s.y() - currentY) <= tol) {
                    currentLine.add(s);
                } else {
                    page.addLine(new LayoutDocument.LayoutLine(currentY, new ArrayList<>(currentLine)));
                    currentLine.clear();
                    currentLine.add(s);
                    currentY = s.y();
                }
            }
            if (!currentLine.isEmpty()) {
                page.addLine(new LayoutDocument.LayoutLine(currentY, new ArrayList<>(currentLine)));
            }
            return page;
        }
    }
}
