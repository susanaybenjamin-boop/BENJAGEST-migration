package com.benjagest.backend.accounting.compensation;

import com.benjagest.backend.accounting.compensation.InvoiceCompensationService.CompRow;
import com.benjagest.backend.accounting.compensation.InvoiceCompensationService.CompensationDetail;
import com.benjagest.backend.accounting.compensation.InvoiceCompensationService.DetailLine;
import com.benjagest.backend.tenant.TenantContext;
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
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * COMP-4 — Justificante (acuerdo) de compensación de deudas. Documento como
 * prueba de la compensación (CC arts. 1195-1202): partes, facturas saldadas de
 * cada lado, importe compensado y saldo resultante, con hueco de firma.
 */
@Service
public class InvoiceCompensationPdfGenerator {

    private static final DateTimeFormatter HUMAN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public InvoiceCompensationPdfGenerator(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public byte[] generate(CompensationDetail detail) {
        CompRow h = detail.header();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 56, 56, 64, 56);
        PdfWriter.getInstance(doc, baos);
        doc.open();

        Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, new Color(15, 23, 42));
        Font h2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, new Color(30, 41, 59));
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 10, new Color(15, 23, 42));
        Font muted = FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(100, 116, 139));

        Paragraph title = new Paragraph("Acuerdo de compensación de deudas", h1);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(4);
        doc.add(title);

        Paragraph sub = new Paragraph(
                "Código Civil, artículos 1195 a 1202. Fecha: " + HUMAN_DATE.format(h.date()), muted);
        sub.setAlignment(Element.ALIGN_CENTER);
        sub.setSpacingAfter(20);
        doc.add(sub);

        // Partes
        CompanyHead own = loadHead(tenant.getCurrentCompanyId());
        PdfPTable parties = new PdfPTable(2);
        parties.setWidthPercentage(100);
        parties.addCell(partyCell("Parte", own.legalName, own.taxIdentifier, h2, body));
        parties.addCell(partyCell("Contraparte (mismo tercero)",
                h.counterpartyName(), h.nif(), h2, body));
        doc.add(parties);

        Paragraph intro = new Paragraph();
        intro.setSpacingBefore(18);
        intro.add(new Phrase("Siendo ambas partes recíprocamente acreedora y deudora la una de la "
                + "otra, acuerdan compensar las siguientes deudas, extinguiéndose hasta la cantidad "
                + "concurrente:", body));
        doc.add(intro);

        // Ventas (me deben) y compras (yo debo)
        doc.add(sectionTable("Facturas emitidas compensadas (me deben)", "SALES", detail.lines(), h2, body, muted));
        doc.add(sectionTable("Facturas recibidas compensadas (yo debo)", "PURCHASE", detail.lines(), h2, body, muted));

        Paragraph total = new Paragraph();
        total.setSpacingBefore(16);
        total.add(new Phrase("Importe total compensado: ", h2));
        total.add(new Phrase(money(h.amount()) + " €", h2));
        doc.add(total);

        Paragraph note = new Paragraph(
                "El saldo restante, si lo hubiera, queda pendiente de cobro/pago por su medio "
                + "habitual (transferencia, etc.).", muted);
        note.setSpacingBefore(4);
        doc.add(note);

        if ("REVERSED".equals(h.status())) {
            Paragraph rev = new Paragraph("*** COMPENSACIÓN ANULADA ***", h2);
            rev.setSpacingBefore(16);
            doc.add(rev);
        }

        // Firmas
        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingBefore(44);
        doc.add(spacer);
        PdfPTable sig = new PdfPTable(2);
        sig.setWidthPercentage(100);
        sig.addCell(signCell("Parte", body));
        sig.addCell(signCell("Contraparte", body));
        doc.add(sig);

        Paragraph stamp = new Paragraph("ID: " + h.id(),
                FontFactory.getFont(FontFactory.HELVETICA, 8, new Color(100, 116, 139)));
        stamp.setSpacingBefore(24);
        doc.add(stamp);

        doc.close();
        return baos.toByteArray();
    }

    private PdfPTable sectionTable(String heading, String kind, java.util.List<DetailLine> all,
                                   Font h2, Font body, Font muted) {
        PdfPTable wrap = new PdfPTable(1);
        wrap.setWidthPercentage(100);
        wrap.setSpacingBefore(14);
        PdfPCell headCell = new PdfPCell(new Paragraph(heading, h2));
        headCell.setBorder(0);
        headCell.setPaddingBottom(4);
        wrap.addCell(headCell);

        PdfPTable t = new PdfPTable(new float[]{3, 1});
        t.setWidthPercentage(100);
        t.addCell(muteCell("Factura", muted));
        t.addCell(muteCellRight("Importe compensado", muted));
        boolean any = false;
        for (DetailLine l : all) {
            if (!kind.equals(l.invoiceKind())) continue;
            any = true;
            t.addCell(bodyCell(l.invoiceNumber() == null ? "—" : l.invoiceNumber(), body));
            t.addCell(bodyCellRight(money(l.amount()) + " €", body));
        }
        if (!any) {
            PdfPCell none = new PdfPCell(new Paragraph("(ninguna)", muted));
            none.setColspan(2);
            none.setBorder(0);
            t.addCell(none);
        }
        PdfPCell tableCell = new PdfPCell(t);
        tableCell.setBorder(0);
        wrap.addCell(tableCell);
        return wrap;
    }

    private PdfPCell partyCell(String header, String name, String nif, Font h2, Font body) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(0);
        cell.setPaddingRight(12);
        cell.addElement(new Paragraph(header, h2));
        cell.addElement(new Paragraph(name == null || name.isBlank() ? "—" : name, body));
        cell.addElement(new Paragraph("NIF/CIF: " + (nif == null ? "—" : nif), body));
        return cell;
    }

    private PdfPCell signCell(String role, Font body) {
        PdfPCell c = new PdfPCell();
        c.setBorder(0);
        c.setPaddingTop(20);
        c.addElement(new Paragraph("__________________________", body));
        c.addElement(new Paragraph(role, body));
        return c;
    }

    private PdfPCell muteCell(String s, Font f) {
        PdfPCell c = new PdfPCell(new Paragraph(s, f));
        c.setBackgroundColor(new Color(241, 245, 249));
        return c;
    }

    private PdfPCell muteCellRight(String s, Font f) {
        PdfPCell c = muteCell(s, f);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    private PdfPCell bodyCell(String s, Font f) { return new PdfPCell(new Paragraph(s, f)); }

    private PdfPCell bodyCellRight(String s, Font f) {
        PdfPCell c = bodyCell(s, f);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    private static String money(BigDecimal v) {
        return v == null ? "0,00" : String.format(java.util.Locale.GERMANY, "%,.2f", v);
    }

    private CompanyHead loadHead(String companyId) {
        return jdbc.query("""
                SELECT legal_name, tax_identifier FROM companies WHERE id = ?
                """, rs -> {
            CompanyHead c = new CompanyHead();
            if (rs.next()) {
                c.legalName = rs.getString("legal_name");
                c.taxIdentifier = rs.getString("tax_identifier");
            }
            return c;
        }, companyId);
    }

    private static class CompanyHead {
        String legalName, taxIdentifier;
    }
}
