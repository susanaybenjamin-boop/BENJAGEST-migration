package com.benjagest.backend.billing.pdf;

import com.benjagest.backend.billing.invoices.InvoiceLine;
import com.benjagest.backend.billing.invoices.SalesInvoice;
import com.benjagest.backend.billing.texts.InvoiceTexts;
import com.benjagest.backend.settings.CompanyDataResponse;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Genera el PDF de una factura. Slice F4b.
 *
 * Layout sencillo y legal (sin logo por ahora — `companies` no tiene
 * todavia columna de logo; al añadirla, se mete arriba a la izquierda):
 *
 *   ┌───────────────────────────────────────────────────────────────┐
 *   │ FACTURA F-2026-0001                       [Empresa SL]        │
 *   │ Fecha: 02/06/2026                         NIF: B12345678      │
 *   │ Vencimiento: 02/07/2026                   Calle, CP, Localidad│
 *   │                                           Tel/Email           │
 *   ├───────────────────────────────────────────────────────────────┤
 *   │ Cliente:                                                       │
 *   │   Construcciones Alba SL                                       │
 *   │   B12000001 · Calle Mayor 12 · Madrid · 28013                  │
 *   ├───────────────────────────────────────────────────────────────┤
 *   │ Tabla líneas (multi-página automático por flow del Document)   │
 *   ├───────────────────────────────────────────────────────────────┤
 *   │ Totales:                                                       │
 *   │   Base imponible       100,00 €                                │
 *   │   IVA 21%               21,00 €                                │
 *   │   Retención IRPF         0,00 €                                │
 *   │   TOTAL FACTURA       121,00 €                                 │
 *   ├───────────────────────────────────────────────────────────────┤
 *   │ Textos legales (los que vienen rellenos de InvoiceTexts)       │
 *   │ Pie + IBAN si aplica                                           │
 *   └───────────────────────────────────────────────────────────────┘
 *
 * Tipos de factura especiales:
 *   - RECTIFYING: cabecera muestra "FACTURA RECTIFICATIVA" + ref a la
 *     original (notes ya lleva "Rectificativa de X").
 *
 * Cumplimiento basico RD 1619/2012: nº factura, fecha emisión, datos
 * fiscales emisor/receptor, descripción operaciones, base imponible,
 * tipo IVA, cuota, total. Lo demás (firma, hash, QR VeriFactu) llega
 * en VF2/VF3.
 */
@Service
public class InvoicePdfGenerator {

    private static final NumberFormat MONEY = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-ES"));
    private static final DateTimeFormatter DATE_DM_Y = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Color INK = new Color(15, 23, 42);     // slate-900
    private static final Color INK_LIGHT = new Color(71, 85, 105);  // slate-600
    private static final Color BG_HEADER = new Color(15, 27, 45);  // navy palette CSS
    private static final Color BG_ALT = new Color(248, 251, 255);  // alt row
    private static final Color BG_TOTAL = new Color(29, 78, 216);  // blue 700

    public byte[] generate(SalesInvoice invoice, CompanyDataResponse company, InvoiceTexts texts) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 42, 42, 50, 50);
            PdfWriter.getInstance(document, bos);
            document.open();

            addHeader(document, invoice, company);
            addCustomerBlock(document, invoice);
            addLinesTable(document, invoice);
            addTotalsBlock(document, invoice);
            addLegalTexts(document, invoice, texts, company);
            addFooter(document, company, texts);

            document.close();
            return bos.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException("No se pudo generar el PDF de la factura", e);
        }
    }

    // ----- Bloques -----

    private void addHeader(Document document, SalesInvoice invoice, CompanyDataResponse company) throws DocumentException {
        boolean isRectifying = "RECTIFYING".equals(invoice.invoiceType());
        String title = isRectifying ? "FACTURA RECTIFICATIVA" : "FACTURA";

        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        header.setWidths(new float[]{55f, 45f});
        header.setSpacingAfter(14f);

        // Columna izquierda: titulo + numero + fechas + notas si RECT
        Font fTitle = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, INK);
        Font fNumber = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, INK);
        Font fMeta = FontFactory.getFont(FontFactory.HELVETICA, 10, INK_LIGHT);

        Paragraph leftBlock = new Paragraph();
        leftBlock.add(new Phrase(title + "\n", fTitle));
        if (invoice.invoiceNumber() != null) {
            leftBlock.add(new Phrase(invoice.invoiceNumber() + "\n", fNumber));
        } else {
            leftBlock.add(new Phrase("(borrador)\n", fNumber));
        }
        leftBlock.add(new Phrase("Fecha: " + safe(invoice.invoiceDate(), DATE_DM_Y) + "\n", fMeta));
        leftBlock.add(new Phrase("Vencimiento: " + safe(invoice.dueDate(), DATE_DM_Y) + "\n", fMeta));
        if (isRectifying && invoice.notes() != null && !invoice.notes().isBlank()) {
            leftBlock.add(new Phrase(invoice.notes() + "\n", fMeta));
        }
        PdfPCell leftCell = noBorderCell(leftBlock);
        header.addCell(leftCell);

        // Columna derecha: datos empresa
        Font fCompanyName = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, INK);
        Paragraph rightBlock = new Paragraph();
        rightBlock.setAlignment(Element.ALIGN_RIGHT);
        rightBlock.add(new Phrase(nz(company.legalName()) + "\n", fCompanyName));
        if (nonBlank(company.taxIdentifier())) {
            rightBlock.add(new Phrase("NIF: " + company.taxIdentifier() + "\n", fMeta));
        }
        String fullAddress = joinNonBlank(" · ",
                company.addressLine(), company.postalCode(), company.city(), company.province());
        if (!fullAddress.isBlank()) {
            rightBlock.add(new Phrase(fullAddress + "\n", fMeta));
        }
        String contact = joinNonBlank(" · ", company.phone(), company.email(), company.website());
        if (!contact.isBlank()) {
            rightBlock.add(new Phrase(contact + "\n", fMeta));
        }
        if (nonBlank(company.registryInformation())) {
            rightBlock.add(new Phrase(company.registryInformation() + "\n", fMeta));
        }
        PdfPCell rightCell = noBorderCell(rightBlock);
        rightCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        header.addCell(rightCell);

        document.add(header);
        document.add(divider());
    }

    private void addCustomerBlock(Document document, SalesInvoice invoice) throws DocumentException {
        Font fLabel = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, INK);
        Font fName = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, INK);

        Paragraph p = new Paragraph();
        p.setSpacingBefore(6f);
        p.setSpacingAfter(10f);
        p.add(new Phrase("Cliente:\n", fLabel));
        p.add(new Phrase(nz(invoice.customerLegalName()) + "\n", fName));
        document.add(p);
    }

    private void addLinesTable(Document document, SalesInvoice invoice) throws DocumentException {
        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{50f, 10f, 14f, 10f, 16f});
        table.setSpacingAfter(10f);
        table.setHeaderRows(1);

        Font fHead = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE);
        addHeaderCell(table, "Descripción", fHead, Element.ALIGN_LEFT);
        addHeaderCell(table, "Cant.", fHead, Element.ALIGN_RIGHT);
        addHeaderCell(table, "Precio", fHead, Element.ALIGN_RIGHT);
        addHeaderCell(table, "IVA", fHead, Element.ALIGN_RIGHT);
        addHeaderCell(table, "Subtotal", fHead, Element.ALIGN_RIGHT);

        Font fBody = FontFactory.getFont(FontFactory.HELVETICA, 10, INK);
        int i = 0;
        for (InvoiceLine line : invoice.lines()) {
            Color bg = (i % 2 == 0) ? Color.WHITE : BG_ALT;
            addBodyCell(table, nz(line.description()), fBody, Element.ALIGN_LEFT, bg);
            addBodyCell(table, plainDecimal(line.quantity()), fBody, Element.ALIGN_RIGHT, bg);
            addBodyCell(table, money(line.unitPrice()), fBody, Element.ALIGN_RIGHT, bg);
            addBodyCell(table, plainDecimal(line.vatPercent()) + " %", fBody, Element.ALIGN_RIGHT, bg);
            addBodyCell(table, money(line.lineSubtotal()), fBody, Element.ALIGN_RIGHT, bg);
            i++;
        }
        document.add(table);
    }

    private void addTotalsBlock(Document document, SalesInvoice invoice) throws DocumentException {
        // Agrupacion por % IVA para mostrar las cuotas separadas — útil
        // en facturas con varios tipos (21/10/4).
        Map<BigDecimal, BigDecimal> baseByVat = new LinkedHashMap<>();
        Map<BigDecimal, BigDecimal> cuotaByVat = new LinkedHashMap<>();
        for (InvoiceLine line : invoice.lines()) {
            BigDecimal pct = line.vatPercent() == null ? BigDecimal.ZERO : line.vatPercent();
            baseByVat.merge(pct, line.lineSubtotal(), BigDecimal::add);
            cuotaByVat.merge(pct, line.lineVat(), BigDecimal::add);
        }

        PdfPTable totals = new PdfPTable(2);
        totals.setWidthPercentage(50f);
        totals.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totals.setWidths(new float[]{60f, 40f});
        totals.setSpacingBefore(4f);
        totals.setSpacingAfter(14f);

        Font fRow = FontFactory.getFont(FontFactory.HELVETICA, 10, INK);
        Font fRowBold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, INK);
        Font fTotal = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, Color.WHITE);

        addTotalRow(totals, "Base imponible", money(invoice.subtotal()), fRow, fRowBold, null);
        for (Map.Entry<BigDecimal, BigDecimal> e : cuotaByVat.entrySet()) {
            addTotalRow(totals, "IVA " + plainDecimal(e.getKey()) + " %", money(e.getValue()), fRow, fRowBold, null);
        }
        if (invoice.retentionTotal() != null && invoice.retentionTotal().compareTo(BigDecimal.ZERO) != 0) {
            addTotalRow(totals, "Retención IRPF", "-" + money(invoice.retentionTotal()), fRow, fRowBold, null);
        }
        addTotalRow(totals, "TOTAL FACTURA", money(invoice.total()), fTotal, fTotal, BG_TOTAL);

        document.add(totals);
    }

    private void addLegalTexts(Document document, SalesInvoice invoice, InvoiceTexts texts, CompanyDataResponse company) throws DocumentException {
        if (texts == null) return;
        Font fText = FontFactory.getFont(FontFactory.HELVETICA, 9, INK_LIGHT);

        // Rectificativa SIEMPRE imprime su texto si está definido.
        if ("RECTIFYING".equals(invoice.invoiceType()) && nonBlank(texts.rectifying())) {
            document.add(legalParagraph(texts.rectifying(), fText));
        }

        // Exención IVA: si alguna linea tiene IVA 0% y hay texto definido.
        boolean hasExempt = invoice.lines().stream()
                .anyMatch(l -> l.vatPercent() != null && l.vatPercent().compareTo(BigDecimal.ZERO) == 0);
        if (hasExempt && nonBlank(texts.exempt())) {
            document.add(legalParagraph(texts.exempt(), fText));
        }

        // IVA reducido (4% o 10%): si hay líneas con esos tipos.
        boolean hasReduced = invoice.lines().stream()
                .anyMatch(l -> l.vatPercent() != null
                        && (l.vatPercent().compareTo(new BigDecimal("4")) == 0
                                || l.vatPercent().compareTo(new BigDecimal("10")) == 0));
        if (hasReduced && nonBlank(texts.reducedVat())) {
            document.add(legalParagraph(texts.reducedVat(), fText));
        }

        // Términos legales generales (vencimiento, mora, jurisdicción).
        if (nonBlank(texts.legalTerms())) {
            document.add(legalParagraph(texts.legalTerms(), fText));
        }

        // IBAN si la empresa lo activa.
        if (texts.showIban() && nonBlank(company.iban())) {
            Font fIban = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, INK);
            Paragraph ibanP = new Paragraph("IBAN: " + company.iban(), fIban);
            ibanP.setSpacingBefore(4f);
            ibanP.setSpacingAfter(4f);
            document.add(ibanP);
        }
    }

    private void addFooter(Document document, CompanyDataResponse company, InvoiceTexts texts) throws DocumentException {
        Font fFooter = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, INK_LIGHT);
        // Pie general: prioridad al texto de InvoiceTexts.pie; si está
        // vacio, pruebas con companies.invoice_footer; si ese tampoco,
        // un genérico inocuo.
        String footerText = null;
        if (texts != null && nonBlank(texts.pie())) {
            footerText = texts.pie();
        } else if (nonBlank(company.invoiceFooter())) {
            footerText = company.invoiceFooter();
        }
        if (footerText != null) {
            Paragraph p = new Paragraph(footerText, fFooter);
            p.setAlignment(Element.ALIGN_CENTER);
            p.setSpacingBefore(20f);
            document.add(p);
        }
    }

    // ----- Helpers de cells -----

    private PdfPCell noBorderCell(Paragraph p) {
        PdfPCell c = new PdfPCell();
        c.addElement(p);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(2f);
        return c;
    }

    private void addHeaderCell(PdfPTable table, String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBackgroundColor(BG_HEADER);
        c.setHorizontalAlignment(align);
        c.setPadding(7f);
        c.setBorder(Rectangle.NO_BORDER);
        table.addCell(c);
    }

    private void addBodyCell(PdfPTable table, String text, Font font, int align, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBackgroundColor(bg);
        c.setHorizontalAlignment(align);
        c.setPadding(6f);
        c.setBorder(Rectangle.NO_BORDER);
        table.addCell(c);
    }

    private void addTotalRow(PdfPTable table, String label, String value, Font fLabel, Font fValue, Color bg) {
        PdfPCell cLabel = new PdfPCell(new Phrase(label, fLabel));
        cLabel.setBorder(Rectangle.NO_BORDER);
        cLabel.setPadding(7f);
        if (bg != null) cLabel.setBackgroundColor(bg);
        cLabel.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.addCell(cLabel);

        PdfPCell cValue = new PdfPCell(new Phrase(value, fValue));
        cValue.setBorder(Rectangle.NO_BORDER);
        cValue.setPadding(7f);
        if (bg != null) cValue.setBackgroundColor(bg);
        cValue.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(cValue);
    }

    private Paragraph legalParagraph(String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setSpacingBefore(4f);
        p.setSpacingAfter(2f);
        return p;
    }

    private PdfPTable divider() throws DocumentException {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell();
        c.setFixedHeight(1f);
        c.setBorder(Rectangle.NO_BORDER);
        c.setBackgroundColor(new Color(219, 227, 239));
        t.addCell(c);
        t.setSpacingAfter(8f);
        return t;
    }

    // ----- Format helpers -----

    private String money(BigDecimal value) {
        if (value == null) return MONEY.format(0);
        return MONEY.format(value);
    }

    private String plainDecimal(BigDecimal value) {
        if (value == null) return "0";
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0
                ? stripped.setScale(0, RoundingMode.UNNECESSARY).toPlainString()
                : stripped.toPlainString();
    }

    private String safe(java.time.LocalDate d, DateTimeFormatter fmt) {
        return d == null ? "—" : d.format(fmt);
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (nonBlank(p)) {
                if (sb.length() > 0) sb.append(sep);
                sb.append(p);
            }
        }
        return sb.toString();
    }
}
