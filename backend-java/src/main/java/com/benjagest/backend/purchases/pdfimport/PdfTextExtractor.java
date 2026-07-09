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
            java.nio.file.Path dataDir = resolveTessdataDir();
            if (dataDir != null) tess.setDatapath(dataDir.toString());
            tess.setLanguage(resolveLanguages(dataDir));
            org.apache.pdfbox.rendering.PDFRenderer renderer =
                    new org.apache.pdfbox.rendering.PDFRenderer(doc);
            int pages = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pages; i++) {
                java.awt.image.BufferedImage img = renderer.renderImageWithDPI(i, 300);
                // PDF-EXTRACT-1: los escaneos llegan a veces GIRADOS (la
                // furgoneta de Benjamin venía boca abajo y el OCR devolvía
                // basura). Probamos 0/180/90/270 y nos quedamos con la
                // orientación cuyo texto puntúe más palabras españolas.
                sb.append(ocrBestOrientation(tess, img)).append("\n");
            }
            return sb.toString();
        } catch (Throwable ex) {
            // UnsatisfiedLinkError / TesseractException / falta tessdata → degradar.
            log.warn("OCR-TESSERACT no disponible o falló ({}). Se ignora el escaneado.",
                    ex.getMessage());
            return "";
        }
    }

    /** OCR probando orientaciones; devuelve el texto con mejor puntuación. */
    private static String ocrBestOrientation(net.sourceforge.tess4j.Tesseract tess,
                                              java.awt.image.BufferedImage img) throws Exception {
        String best = "";
        int bestScore = -1;
        for (int deg : new int[]{0, 180, 90, 270}) {
            String txt = tess.doOCR(deg == 0 ? img : rotate(img, deg));
            int score = spanishScore(txt);
            if (score > bestScore) { bestScore = score; best = txt; }
            // 0º con buena puntuación: no quemamos 3 OCR extra por página.
            if (deg == 0 && score >= 12) break;
        }
        return best;
    }

    /** Nº de apariciones de palabras frecuentes de una factura española. */
    static int spanishScore(String text) {
        if (text == null || text.isBlank()) return 0;
        String t = " " + text.toLowerCase() + " ";
        int score = 0;
        for (String w : new String[]{" de ", " la ", " el ", " total", "factura", "fecha",
                "importe", " iva", " nif", " cif", "base", "cliente", "euros", "pago",
                "s.l", "s.a", "n.º", "nº", "cuota"}) {
            int idx = 0;
            while ((idx = t.indexOf(w, idx)) >= 0) { score++; idx += w.length(); }
        }
        return score;
    }

    private static java.awt.image.BufferedImage rotate(java.awt.image.BufferedImage img, int deg) {
        int w = img.getWidth(), h = img.getHeight();
        boolean quarter = deg == 90 || deg == 270;
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                quarter ? h : w, quarter ? w : h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.rotate(Math.toRadians(deg), out.getWidth() / 2.0, out.getHeight() / 2.0);
        if (quarter) {
            g.translate((out.getWidth() - w) / 2.0, (out.getHeight() - h) / 2.0);
        }
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    /**
     * Localiza el directorio con los {@code *.traineddata}. Prioridad:
     *   1) propiedad {@code -Dbenjagest.tessdata=...} (la pone el instalable),
     *   2) variable de entorno {@code TESSDATA_PREFIX},
     *   3) Tesseract instalado en el sistema (Program Files).
     * Devuelve {@code null} si no encuentra ninguno (Tess4J usará su tessdata
     * embebido, que solo trae los configs, no idiomas → OCR degradará a "").
     */
    private static java.nio.file.Path resolveTessdataDir() {
        String prop = System.getProperty("benjagest.tessdata");
        String env = System.getenv("TESSDATA_PREFIX");
        java.util.List<String> candidates = new ArrayList<>();
        if (prop != null && !prop.isBlank()) candidates.add(prop);
        if (env != null && !env.isBlank()) candidates.add(env);
        candidates.add("C:\\Program Files\\Tesseract-OCR\\tessdata");
        candidates.add("C:\\Program Files (x86)\\Tesseract-OCR\\tessdata");
        candidates.add("/usr/share/tesseract-ocr/4.00/tessdata");
        candidates.add("/usr/share/tessdata");
        for (String c : candidates) {
            java.nio.file.Path p = java.nio.file.Paths.get(c);
            if (java.nio.file.Files.isDirectory(p)) return p;
        }
        return null;
    }

    /**
     * Elige los idiomas presentes en el datapath para no romper si falta alguno
     * (Tesseract aborta si pides un idioma sin su {@code .traineddata}).
     * Preferencia spa+eng; si solo hay uno, ese; si no hay datapath, "eng".
     */
    private static String resolveLanguages(java.nio.file.Path dataDir) {
        if (dataDir == null) return "eng";
        java.util.List<String> langs = new ArrayList<>();
        for (String l : new String[] {"spa", "eng"}) {
            if (java.nio.file.Files.exists(dataDir.resolve(l + ".traineddata"))) langs.add(l);
        }
        return langs.isEmpty() ? "eng" : String.join("+", langs);
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
        // PDF-EXTRACT-1 (2026-07-09): fallback OCR también en el camino con
        // layout (el import real usa extractLayout, no extract, y hasta hoy
        // un escaneado devolvía vacío → 422). Dos disparadores:
        //   a) sin texto (PDF escaneado — la furgoneta de Benjamin);
        //   b) texto ENTRELAZADO: PDFs cuyo text-layer mezcla dos columnas
        //      carácter a carácter ("CGA LAESETOSRORREE SD E VLEORSGELES" en
        //      las facturas de SH Asesores) — inservible para regex. El
        //      render visual es correcto, así que el OCR lo lee bien.
        String plain = out.toPlainText();
        if (needsOcrFallback(plain)) {
            String viaOcr = ocr(pdfBytes);
            if (viaOcr != null && !viaOcr.isBlank()) {
                log.info("PDF-EXTRACT: texto {} — usando OCR como fuente del layout",
                        plain.replaceAll("\\s", "").length() < 15 ? "vacío (escaneado)" : "entrelazado/corrupto");
                return syntheticLayoutFromText(viaOcr);
            }
        }
        return out;
    }

    /** NIF/CIF etiquetado y BIEN FORMADO — señal de text-layer sano. */
    private static final java.util.regex.Pattern CLEAN_LABELED_NIF =
            java.util.regex.Pattern.compile(
                    "(?i)\\b(?:nif|c\\.?i\\.?f\\.?)\\s*:?\\s*(?:ES)?"
                    + "([A-Z]\\d{8}|\\d{8}[A-Z]|[A-Z]\\d{7}[A-Z0-9])(?![0-9A-Za-z])");

    /** Sin texto utilizable: vacío (escaneado) o entrelazado (columnas mezcladas). */
    static boolean needsOcrFallback(String plain) {
        if (plain == null) return true;
        if (plain.replaceAll("\\s", "").length() < 15) return true;
        // Entrelazado + ningún NIF etiquetado sano = text-layer inservible.
        // (Un PDF con referencias raras pero NIF limpio — Amazon — se queda
        // con su texto nativo, que siempre es más fiel que el OCR.)
        return looksScrambled(plain) && !CLEAN_LABELED_NIF.matcher(plain).find();
    }

    /**
     * Detecta el text-layer ENTRELAZADO: tokens largos con muchas
     * alternancias dígito↔letra ("8A0B0E8NCEGRRRAANJAEDSA", "8P2a-g6o1")
     * que solo aparecen cuando dos textos se imprimen intercalados. Los
     * códigos legítimos (referencias Amazon, IBAN) rara vez pasan de 2-3
     * tokens así; el umbral pide ≥3 para no dar falsos positivos.
     */
    static boolean looksScrambled(String plain) {
        // DISTINTOS, no ocurrencias: una referencia legítima (nº de pedido
        // Amazon) se repite varias veces pero es UN solo token; el texto
        // entrelazado genera muchos tokens raros DIFERENTES.
        java.util.Set<String> weird = new java.util.HashSet<>();
        for (String tok : plain.split("\\s+")) {
            if (tok.length() < 8) continue;
            int transitions = 0;
            Boolean prevDigit = null;
            for (int i = 0; i < tok.length(); i++) {
                char c = tok.charAt(i);
                if (!Character.isLetterOrDigit(c)) continue;
                boolean d = Character.isDigit(c);
                if (prevDigit != null && d != prevDigit) transitions++;
                prevDigit = d;
            }
            if (transitions >= 4) weird.add(tok);
            if (weird.size() >= 3) return true;
        }
        return false;
    }

    /**
     * LayoutDocument sintético desde texto OCR plano (una página A4, una
     * línea por renglón). Sin coordenadas reales: las heurísticas por
     * columna degradan con gracia y las textuales funcionan igual.
     */
    private static LayoutDocument syntheticLayoutFromText(String text) {
        LayoutDocument doc = new LayoutDocument();
        LayoutDocument.LayoutPage page = new LayoutDocument.LayoutPage(1, 595f, 842f);
        float y = 20f;
        for (String line : text.split("\\r?\\n")) {
            String t = line.strip();
            if (t.isEmpty()) { y += 12f; continue; }
            java.util.List<LayoutDocument.LayoutSpan> spans = new java.util.ArrayList<>();
            spans.add(new LayoutDocument.LayoutSpan(t, 20f, y, Math.min(t.length() * 5.5f, 555f), 10f));
            page.addLine(new LayoutDocument.LayoutLine(y, spans));
            y += 12f;
        }
        doc.addPage(page);
        return doc;
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
