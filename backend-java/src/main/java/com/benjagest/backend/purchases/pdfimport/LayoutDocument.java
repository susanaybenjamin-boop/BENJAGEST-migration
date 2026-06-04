package com.benjagest.backend.purchases.pdfimport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Representa un PDF extraído con preservación de layout (posiciones
 * X/Y de cada glyph). Sirve para que los parsers (facturas, calendarios
 * laborales, recibos…) puedan razonar sobre tablas, columnas y bloques,
 * cosa que el texto plano de {@link PdfTextExtractor#extract} pierde.
 *
 * Inspirado en {@code reconstructPageLayout} de CONTENDO
 * (ocrEngine.js) — la clave es que las facturas son tablas, no prosa, y
 * los regex sueltos sobre texto plano fallan cuando las etiquetas y los
 * valores no comparten línea exacta o están alineados por columna.
 *
 * Construcción:
 *   1) PdfTextExtractor.extractLayout(bytes) → LayoutDocument.
 *   2) Las páginas se rellenan en orden con LayoutPage.
 *   3) Cada página agrupa spans en líneas por tolerancia Y.
 */
public final class LayoutDocument {

    private final List<LayoutPage> pages = new ArrayList<>();

    public List<LayoutPage> pages() { return pages; }

    public void addPage(LayoutPage page) { pages.add(page); }

    /**
     * Devuelve TODAS las líneas de TODAS las páginas en orden.
     */
    public List<LayoutLine> allLines() {
        List<LayoutLine> out = new ArrayList<>();
        for (LayoutPage p : pages) out.addAll(p.lines());
        return out;
    }

    /**
     * Reconstruye texto plano preservando saltos de línea (con espacios
     * extra cuando hay "huecos grandes" — separación de columna).
     * Algoritmo idéntico al de CONTENDO reconstructPageLayout.
     */
    public String toPlainText() {
        StringBuilder sb = new StringBuilder();
        for (LayoutPage p : pages) {
            for (LayoutLine line : p.lines()) {
                sb.append(line.text()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Una página con sus líneas ya agrupadas y ordenadas top→bottom.
     */
    public static final class LayoutPage {
        private final int number;
        private final float width;
        private final float height;
        private final List<LayoutLine> lines = new ArrayList<>();

        public LayoutPage(int number, float width, float height) {
            this.number = number;
            this.width = width;
            this.height = height;
        }

        public int number() { return number; }
        public float width() { return width; }
        public float height() { return height; }
        public List<LayoutLine> lines() { return lines; }

        public void addLine(LayoutLine line) { lines.add(line); }
    }

    /**
     * Una línea con sus spans ordenados left→right + texto reconstruido
     * con espaciado proporcional a los huecos (los huecos grandes se
     * convierten en varios espacios, simulando un TAB).
     */
    public static final class LayoutLine {
        private final float y;
        private final List<LayoutSpan> spans;
        private final String text;

        public LayoutLine(float y, List<LayoutSpan> spans) {
            this.y = y;
            spans.sort(Comparator.comparingDouble(LayoutSpan::x));
            this.spans = spans;
            this.text = buildText(spans);
        }

        public float y() { return y; }
        public List<LayoutSpan> spans() { return spans; }
        public String text() { return text; }

        private static String buildText(List<LayoutSpan> spans) {
            StringBuilder sb = new StringBuilder();
            float lastEnd = -1;
            for (LayoutSpan s : spans) {
                if (lastEnd < 0) {
                    sb.append(s.text());
                } else {
                    float gap = s.x() - lastEnd;
                    if (gap > 8f) {
                        // hueco grande → tab visual (varios espacios)
                        int n = Math.min(Math.max(Math.round(gap / 6f), 2), 10);
                        sb.append(" ".repeat(n));
                    } else if (gap > 2f) {
                        sb.append(' ');
                    }
                    sb.append(s.text());
                }
                lastEnd = s.x() + s.width();
            }
            return sb.toString().trim();
        }
    }

    /**
     * Un fragmento de texto en una posición exacta.
     */
    public record LayoutSpan(String text, float x, float y, float width, float height) {}
}
