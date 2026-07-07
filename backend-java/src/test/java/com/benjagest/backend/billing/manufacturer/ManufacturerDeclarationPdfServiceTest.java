package com.benjagest.backend.billing.manufacturer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * DR-1 — La declaracion responsable es un documento LEGAL (RD 1007/2023
 * + Orden HAC/1177/2024 art. 15): si un refactor le quita el NIF del
 * productor, la version o el compromiso, deja de ser valida sin que
 * nadie lo note. Estos tests fijan el contenido minimo obligatorio.
 */
class ManufacturerDeclarationPdfServiceTest {

    private final ManufacturerDeclarationPdfService service = new ManufacturerDeclarationPdfService();

    @Test
    void currentUsesTheInstalledVersionPassedByTheUi() {
        assertEquals("0.1.10", ManufacturerDeclaration.current("0.1.10").productVersion());
        assertEquals("0.1.10", ManufacturerDeclaration.current("  0.1.10  ").productVersion());
    }

    @Test
    void currentWithoutVersionPointsToWhereToFindItInsteadOfInventingOne() {
        String v = ManufacturerDeclaration.current(null).productVersion();
        assertTrue(v.contains("Acerca de"), "sin version debe remitir a Acerca de, no inventar: " + v);
        assertEquals(v, ManufacturerDeclaration.current("").productVersion());
    }

    @Test
    void plainTextContainsTheLegallyRequiredContent() {
        String text = service.plainText(ManufacturerDeclaration.current("0.1.10"));
        // Identificacion del productor (Orden HAC/1177/2024 art. 15).
        assertTrue(text.contains("Benjamín Recio López"), "falta el nombre del productor");
        assertTrue(text.contains("74668351R"), "falta el NIF del productor");
        // Identificacion del sistema y su version.
        assertTrue(text.contains("BENJAGEST"), "falta el nombre del producto");
        assertTrue(text.contains("0.1.10"), "falta la version del producto");
        // Normas de referencia y compromiso.
        assertTrue(text.contains("1007/2023"), "falta la cita del RD 1007/2023");
        assertTrue(text.contains("HAC/1177/2024"), "falta la cita de la Orden HAC/1177/2024");
        assertTrue(text.contains("declara bajo su responsabilidad"), "falta el compromiso");
        // Fecha y lugar.
        assertTrue(text.contains("FECHA Y LUGAR"), "falta la seccion de fecha y lugar");
    }

    @Test
    void pdfIsGeneratedAndIsARealPdf() {
        byte[] pdf = service.pdf(ManufacturerDeclaration.current("0.1.10"));
        assertTrue(pdf.length > 1000, "PDF sospechosamente pequeno: " + pdf.length + " bytes");
        String magic = new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals("%PDF-", magic, "la salida no empieza por %PDF-");
    }
}
