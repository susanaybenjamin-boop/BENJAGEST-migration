package com.benjagest.backend.billing.tpb;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * PDF del acuerdo previo de facturación por tercero (RD 1619/2012 art. 5).
 *
 * <p>Layout mínimo profesional con: título, encabezado normativo,
 * datos de las dos partes, alcance marcado, fecha+método de firma
 * (si aplica) y sello SHA-256 de los datos clave como prueba de
 * integridad del documento.
 */
@Service
public class ThirdPartyBillingAgreementPdfGenerator {

    private static final DateTimeFormatter HUMAN_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("Europe/Madrid"));

    private final JdbcTemplate jdbc;

    public ThirdPartyBillingAgreementPdfGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public byte[] generate(ThirdPartyBillingAgreement a, String signedMethod, Instant signedAt) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 56, 56, 64, 56);
        PdfWriter.getInstance(doc, baos);
        doc.open();

        Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, new Color(15, 23, 42));
        Font h2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, new Color(30, 41, 59));
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 10, new Color(15, 23, 42));
        Font muted = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(100, 116, 139));
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 8, new Color(100, 116, 139));

        Paragraph title = new Paragraph(
                "Acuerdo de facturación por tercero", h1);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(4);
        doc.add(title);

        Paragraph sub = new Paragraph(
                "RD 1619/2012, de 30 de noviembre, artículo 5.", muted);
        sub.setAlignment(Element.ALIGN_CENTER);
        sub.setSpacingAfter(20);
        doc.add(sub);

        // Datos de las partes
        CompanyHead advisory = loadHead(a.advisoryCompanyId());
        CompanyHead client = loadHead(a.clientCompanyId());

        PdfPTable parties = new PdfPTable(2);
        parties.setWidthPercentage(100);
        parties.setSpacingBefore(4);
        parties.addCell(partyCell("Asesoría (expedidor material)", advisory, h2, body));
        parties.addCell(partyCell("Cliente (titular fiscal)", client, h2, body));
        doc.add(parties);

        Paragraph clauses = new Paragraph();
        clauses.setSpacingBefore(20);
        clauses.add(new Phrase("Las partes acuerdan que la asesoría queda autorizada a expedir "
                + "materialmente, en nombre del titular fiscal, las facturas correspondientes a "
                + "las operaciones cubiertas por el presente acuerdo. ", body));
        clauses.add(new Phrase("La responsabilidad última del cumplimiento de las obligaciones "
                + "tributarias derivadas de dichas facturas corresponde al titular fiscal, "
                + "según establece el artículo 5.2 del RD 1619/2012.", body));
        doc.add(clauses);

        // Alcance
        Paragraph scopeTitle = new Paragraph("Alcance del acuerdo", h2);
        scopeTitle.setSpacingBefore(16);
        scopeTitle.setSpacingAfter(4);
        doc.add(scopeTitle);

        doc.add(scopeLine("Facturas emitidas (ventas)", a.scopeSales(), body));
        doc.add(scopeLine("Facturas recibidas (compras)", a.scopePurchases(), body));
        doc.add(scopeLine("Modelos AEAT (declaraciones tributarias)", a.scopeTaxModels(), body));

        // Firma
        Paragraph signTitle = new Paragraph("Firma", h2);
        signTitle.setSpacingBefore(20);
        signTitle.setSpacingAfter(4);
        doc.add(signTitle);

        if (signedAt != null && signedMethod != null) {
            Paragraph signed = new Paragraph();
            signed.add(new Phrase("Acuerdo firmado el ", body));
            signed.add(new Phrase(HUMAN_DATE.format(signedAt) + " (Europa/Madrid)", h2));
            signed.add(new Phrase(" mediante ", body));
            signed.add(new Phrase(humanMethod(signedMethod), h2));
            signed.add(new Phrase(".", body));
            doc.add(signed);
        } else {
            Paragraph pending = new Paragraph("Pendiente de firma. Imprime este documento, fírmalo "
                    + "y entrégalo a la asesoría para que lo escanee y lo suba.", muted);
            doc.add(pending);

            // Espacios para firmas físicas si es proposal
            Paragraph spacer = new Paragraph(" "); spacer.setSpacingBefore(40);
            doc.add(spacer);
            PdfPTable sigBlock = new PdfPTable(2);
            sigBlock.setWidthPercentage(100);
            sigBlock.addCell(signCell("Asesoría", body));
            sigBlock.addCell(signCell("Cliente", body));
            doc.add(sigBlock);
        }

        // Sello
        Paragraph stamp = new Paragraph();
        stamp.setSpacingBefore(28);
        stamp.add(new Phrase("ID: " + a.id() + "  •  SHA-256: " + computeStamp(a, signedMethod, signedAt),
                small));
        doc.add(stamp);

        doc.close();
        return baos.toByteArray();
    }

    private PdfPCell partyCell(String headerText, CompanyHead c, Font h2, Font body) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(0);
        cell.setPaddingRight(12);
        cell.addElement(new Paragraph(headerText, h2));
        cell.addElement(new Paragraph(c.legalName == null ? "—" : c.legalName, body));
        cell.addElement(new Paragraph("NIF/CIF: " + (c.taxIdentifier == null ? "—" : c.taxIdentifier), body));
        if (c.address != null && !c.address.isBlank()) {
            cell.addElement(new Paragraph(c.address, body));
        }
        if (c.postalCode != null || c.city != null || c.province != null) {
            String line = (c.postalCode == null ? "" : c.postalCode + " ")
                    + (c.city == null ? "" : c.city)
                    + (c.province == null ? "" : ", " + c.province);
            if (!line.isBlank()) cell.addElement(new Paragraph(line, body));
        }
        return cell;
    }

    private Paragraph scopeLine(String text, boolean checked, Font body) {
        return new Paragraph((checked ? "✓  " : "✗  ") + text, body);
    }

    private PdfPCell signCell(String role, Font body) {
        PdfPCell c = new PdfPCell();
        c.setBorder(0);
        c.setPaddingTop(20);
        c.addElement(new Paragraph("__________________________", body));
        c.addElement(new Paragraph(role, body));
        return c;
    }

    private String humanMethod(String method) {
        return switch (method == null ? "" : method) {
            case "PIN_SESSION" -> "PIN de sesión del cliente (firma electrónica simple, eIDAS art. 25)";
            case "OFFLINE_PDF" -> "firma manuscrita en PDF físico verificada por la asesoría";
            default -> method;
        };
    }

    private String computeStamp(ThirdPartyBillingAgreement a, String signedMethod, Instant signedAt) {
        String canonical = a.id() + "|" + a.advisoryCompanyId() + "|" + a.clientCompanyId()
                + "|s=" + a.scopeSales() + ",p=" + a.scopePurchases() + ",t=" + a.scopeTaxModels()
                + "|" + (signedMethod == null ? "" : signedMethod)
                + "|" + (signedAt == null ? "" : signedAt.toString());
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] bytes = sha.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString().substring(0, 16) + "…";
        } catch (Exception ex) {
            return "—";
        }
    }

    private CompanyHead loadHead(String companyId) {
        return jdbc.query("""
                SELECT legal_name, tax_identifier, address_line, postal_code, city, province
                  FROM companies WHERE id = ?
                """, rs -> {
            CompanyHead c = new CompanyHead();
            if (rs.next()) {
                c.legalName = rs.getString("legal_name");
                c.taxIdentifier = rs.getString("tax_identifier");
                c.address = rs.getString("address_line");
                c.postalCode = rs.getString("postal_code");
                c.city = rs.getString("city");
                c.province = rs.getString("province");
            }
            return c;
        }, companyId);
    }

    private static class CompanyHead {
        String legalName, taxIdentifier, address, postalCode, city, province;
    }
}
