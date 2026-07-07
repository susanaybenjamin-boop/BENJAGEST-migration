package com.benjagest.backend.billing.manufacturer;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import org.springframework.stereotype.Service;

/**
 * DR-1 — Render de la declaracion responsable del fabricante en texto
 * plano (para mostrarla visible en la pantalla Acerca de, como exige la
 * Orden HAC/1177/2024: "por escrito y de modo visible en el propio
 * sistema informatico en cada una de sus versiones") y en PDF (para que
 * el cliente disponga de constancia escrita al adquirir el producto).
 *
 * Pantalla y PDF salen del MISMO texto — una sola fuente de verdad, no
 * pueden divergir. Mismo patron OpenPDF que el resto de exportaciones
 * (ver AccountingReportsPdfService). Texto en español: es un documento
 * legal español, no pasa por i18n (igual que los PDF de factura).
 */
@Service
public class ManufacturerDeclarationPdfService {

    private static final Color NAVY = new Color(11, 49, 96);
    private static final Color GREY = new Color(90, 90, 90);

    /** Texto plano completo de la declaracion (pantalla Acerca de). */
    public String plainText(ManufacturerDeclaration d) {
        return "DECLARACIÓN RESPONSABLE DEL FABRICANTE DEL SISTEMA INFORMÁTICO DE FACTURACIÓN\n"
                + "(Real Decreto 1007/2023, de 5 de diciembre — Orden HAC/1177/2024, de 17 de octubre)\n"
                + "\n"
                + "1. PRODUCTOR / FABRICANTE\n"
                + "   Nombre: " + d.manufacturerName() + "\n"
                + "   NIF: " + d.manufacturerTaxIdentifier() + "\n"
                + "   Correo de contacto: " + d.manufacturerEmail() + "\n"
                + "   Localización: " + d.manufacturerAddress() + "\n"
                + "\n"
                + "2. SISTEMA INFORMÁTICO\n"
                + "   Nombre del producto: " + d.productName() + "\n"
                + "   Versión: " + d.productVersion() + "\n"
                + "   Tipo y composición: " + d.productType() + "\n"
                + "\n"
                + "3. FUNCIONALIDADES\n"
                + "   " + d.productFunctionalities() + "\n"
                + "\n"
                + "4. DECLARACIÓN\n"
                + "   " + d.complianceCommitment() + "\n"
                + "\n"
                + "5. FECHA Y LUGAR\n"
                + "   " + d.declarationDate() + " — " + d.declarationPlace() + "\n";
    }

    /** PDF A4 de una página con la declaracion completa. */
    public byte[] pdf(ManufacturerDeclaration d) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 56, 56, 56, 56);
        PdfWriter.getInstance(doc, baos);
        doc.open();

        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, NAVY);
        Font subtitle = FontFactory.getFont(FontFactory.HELVETICA, 9, GREY);
        Font section = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, NAVY);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, Color.BLACK);

        Paragraph t = new Paragraph(
                "DECLARACIÓN RESPONSABLE DEL FABRICANTE\nDEL SISTEMA INFORMÁTICO DE FACTURACIÓN",
                title);
        t.setAlignment(Element.ALIGN_CENTER);
        doc.add(t);
        Paragraph st = new Paragraph(
                "Real Decreto 1007/2023, de 5 de diciembre — Orden HAC/1177/2024, de 17 de octubre",
                subtitle);
        st.setAlignment(Element.ALIGN_CENTER);
        st.setSpacingAfter(16);
        doc.add(st);

        addSection(doc, section, "1. PRODUCTOR / FABRICANTE");
        addBody(doc, body, "Nombre: " + d.manufacturerName());
        addBody(doc, body, "NIF: " + d.manufacturerTaxIdentifier());
        addBody(doc, body, "Correo de contacto: " + d.manufacturerEmail());
        addBody(doc, body, "Localización: " + d.manufacturerAddress());

        addSection(doc, section, "2. SISTEMA INFORMÁTICO");
        addBody(doc, body, "Nombre del producto: " + d.productName());
        addBody(doc, body, "Versión: " + d.productVersion());
        addBody(doc, body, "Tipo y composición: " + d.productType());

        addSection(doc, section, "3. FUNCIONALIDADES");
        addBody(doc, body, d.productFunctionalities());

        addSection(doc, section, "4. DECLARACIÓN");
        addBody(doc, body, d.complianceCommitment());

        addSection(doc, section, "5. FECHA Y LUGAR");
        addBody(doc, body, d.declarationDate() + " — " + d.declarationPlace());

        doc.close();
        return baos.toByteArray();
    }

    private void addSection(Document doc, Font font, String text) {
        Paragraph p = new Paragraph(text, font);
        p.setSpacingBefore(10);
        p.setSpacingAfter(4);
        doc.add(p);
    }

    private void addBody(Document doc, Font font, String text) {
        Paragraph p = new Paragraph(text, font);
        p.setSpacingAfter(2);
        doc.add(p);
    }
}
