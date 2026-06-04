package com.benjagest.backend.purchases.pdfimport;

import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

/**
 * Saca el texto plano de un PDF — primer paso del C3.
 *
 * <p>Tres escenarios posibles al recibir un PDF:</p>
 *
 * <ul>
 *   <li>PDF con texto nativo (lo normal cuando lo genera otro software
 *       de facturacion): PDFBox extrae el texto en milisegundos. Es el
 *       camino feliz; cubre &gt;80% de las facturas recibidas.</li>
 *   <li>PDF escaneado / imagen: PDFBox devolvera string vacio o casi
 *       vacio. Caso TODO — el caller decide si rechaza con un
 *       mensaje claro o invoca OCR (Tesseract via Tess4J, fuera de
 *       este slice).</li>
 *   <li>PDF cifrado: el constructor de PDFBox lo detecta y lanza
 *       IOException. Lo dejamos burbujear con mensaje claro al
 *       caller.</li>
 * </ul>
 */
@Service
public class PdfTextExtractor {

    /**
     * Devuelve el texto plano completo del PDF. Si el PDF no contiene
     * texto (esta escaneado), devuelve "" — el caller decide que
     * hacer.
     */
    public String extract(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            // Por defecto PDFBox sigue el orden de objetos del PDF, que
            // a veces salta entre cabecera y pie. Activar sortByPosition
            // mejora el orden percibido por el extractor de campos.
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            return text == null ? "" : text;
        }
    }
}
