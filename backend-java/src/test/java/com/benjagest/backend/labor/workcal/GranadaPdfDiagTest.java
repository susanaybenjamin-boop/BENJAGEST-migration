package com.benjagest.backend.labor.workcal;

import java.io.File;
import java.nio.file.Files;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import com.benjagest.backend.purchases.pdfimport.PdfTextExtractor;

/**
 * Diagnóstico one-shot del PDF de Benjamin (calendario laboral Granada
 * 2026). Vuelca el texto crudo extraído por PDFBox y el resultado del
 * parser. NO se ejecuta en CI — habilitar @Disabled cuando se necesite.
 *
 * <p>Uso: borrar el @Disabled de los métodos, lanzar
 * {@code mvn -pl backend-java -Dtest=GranadaPdfDiagTest test} y mirar
 * el stdout. Por defecto los tests están @Disabled para no romper el
 * build si el PDF no está en el path local de Benjamin.
 */
public class GranadaPdfDiagTest {

    private static final String PDF_PATH =
            "C:/Users/benja/Desktop/firmado-1766707229925-final-6e5e19fb.pdf";

    @Test
    @Disabled("Diagnóstico manual — requiere PDF local de Benjamin")
    public void dumpRawText() throws Exception {
        File f = new File(PDF_PATH);
        if (!f.exists()) {
            System.out.println("[DIAG] PDF no encontrado: " + PDF_PATH);
            return;
        }
        byte[] bytes = Files.readAllBytes(f.toPath());
        // Usamos el MISMO PdfTextExtractor que usa el parser real para
        // ver exactamente las líneas que se procesan.
        PdfTextExtractor extractor = new PdfTextExtractor();
        String raw = extractor.extract(bytes);
        System.out.println("================= RAW PDFBOX TEXT START =================");
        System.out.println(raw);
        System.out.println("================= RAW PDFBOX TEXT END =================");
        System.out.println("[DIAG] longitud: " + raw.length()
                + ", lineas: " + raw.split("\\r?\\n").length);
    }

    @Test
    @Disabled("Diagnóstico manual — requiere PDF local de Benjamin")
    public void runExtractor() throws Exception {
        File f = new File(PDF_PATH);
        if (!f.exists()) {
            System.out.println("[DIAG] PDF no encontrado: " + PDF_PATH);
            return;
        }
        byte[] bytes = Files.readAllBytes(f.toPath());
        HolidayPdfExtractor extractor = new HolidayPdfExtractor(new PdfTextExtractor());
        HolidayPdfExtractor.DebugResult dbg = extractor.extractWithDebug(bytes);
        System.out.println("[DIAG] año detectado: " + dbg.result().year());
        System.out.println("[DIAG] festivos detectados: " + dbg.result().holidays().size());
        for (HolidayPdfExtractor.DetectedHoliday h : dbg.result().holidays()) {
            System.out.println("  - " + h.date() + " | " + h.name()
                    + " | scope=" + h.scope() + " | conf=" + h.confidence()
                    + " | raw=" + h.rawSourceLine());
        }
        System.out.println("[DIAG] lineas ignoradas (post-primer-mes): " + dbg.ignoredLines().size());
        for (String l : dbg.ignoredLines()) {
            System.out.println("  ~ " + l);
        }
    }
}
