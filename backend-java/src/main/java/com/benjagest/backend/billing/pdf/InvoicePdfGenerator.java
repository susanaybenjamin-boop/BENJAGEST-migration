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
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
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
 * Genera el PDF de una factura (F4b).
 *
 * Layout final (después de feedback 2026-06-03):
 *
 *   ┌────────────────────────────────────────────────────────────┐
 *   │                       FACTURA                              │  ← título centrado
 *   ├─────────────────────────┬──────────────────────────────────┤
 *   │ [Logo opcional]         │   Nº F-2026-0001                 │  ← nº+fecha encima
 *   │                         │   Fecha: 02/06/2026              │     del cliente
 *   │ Empresa SL              │                                  │
 *   │ NIF B12...              │   Cliente                        │
 *   │ Dirección               │   Construcciones Alba SL         │
 *   │ Contacto                │   NIF B12000001                  │
 *   │       [QR]              │   Calle Mayor 12, 28013 Madrid   │
 *   ├─────────────────────────┴──────────────────────────────────┤
 *   │ Tabla de líneas (multi-página automático)                  │
 *   │ ...                                                        │
 *   │                                                            │
 *   │                                                            │
 *   ├──── zona inferior fija (en CADA página) ─────────────────────┤
 *   │ [Textos legales abajo izq]            ┌───────────────────┐ │
 *   │ IBAN: ESxx xxxx xxxx xxxx xxxx        │ Base    100,00 €│ │
 *   │ Pie / agradecimientos                 │ IVA 21%  21,00 €│ │
 *   │                                       │ Ret.      0,00 €│ │
 *   │                                       │ TOTAL   121,00 €│ │
 *   │                                       └───────────────────┘ │
 *   └────────────────────────────────────────────────────────────┘
 *
 * El bloque inferior (totales abajo derecha + textos legales / IBAN /
 * pie a la izquierda) se dibuja en cada página vía PdfPageEvent.onEndPage
 * para que esté SIEMPRE pegado abajo, independientemente de dónde
 * termina la última línea de la tabla.
 *
 * El logo y el QR son por ahora placeholders: el QR llega con VF3
 * (cliente AEAT), el logo cuando se añada columna companies.logo_path.
 *
 * Pendiente: pintar los totales SOLO en la última página y dejar un
 * footer-light en las intermedias. Por ahora se pintan en todas, que es
 * el comportamiento habitual de muchas facturas (defensivo si el cliente
 * solo imprime una página).
 */
@Service
public class InvoicePdfGenerator {

    private static final NumberFormat MONEY = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-ES"));
    private static final DateTimeFormatter DATE_DM_Y = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Color INK = new Color(15, 23, 42);
    private static final Color INK_LIGHT = new Color(71, 85, 105);
    private static final Color BG_HEADER = new Color(15, 27, 45);
    private static final Color BG_ALT = new Color(248, 251, 255);
    private static final Color BG_TOTAL = new Color(29, 78, 216);
    private static final Color DIVIDER = new Color(219, 227, 239);

    // Altura reservada para el footer fijo (totales + legal + pie).
    private static final float FOOTER_HEIGHT = 230f;

    public byte[] generate(SalesInvoice invoice, CompanyDataResponse company, InvoiceTexts texts) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            // bottomMargin grande para reservar la zona del footer fijo.
            Document document = new Document(PageSize.A4, 36, 36, 50, FOOTER_HEIGHT + 30);
            PdfWriter writer = PdfWriter.getInstance(document, bos);
            writer.setPageEvent(new FooterEvent(invoice, company, texts));
            document.open();

            addTitle(document, invoice);
            addTopBlock(document, invoice, company);
            addLinesTable(document, invoice);

            document.close();
            return bos.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException("No se pudo generar el PDF de la factura", e);
        }
    }

    // ----- Cabecera -----

    private void addTitle(Document document, SalesInvoice invoice) throws DocumentException {
        boolean isRectifying = "RECTIFYING".equals(invoice.invoiceType());
        String title = isRectifying ? "FACTURA RECTIFICATIVA" : "FACTURA";

        // Tamaño moderado (15pt) y más spacingAfter para que el bloque
        // del logo encima del emisor no quede visualmente invadido por el
        // título — sobre todo en rectificativas, donde el texto es más
        // largo. Antes 22pt centrado pisaba la zona del logo.
        Font fTitle = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, INK);
        Paragraph p = new Paragraph(title, fTitle);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setSpacingAfter(20f);
        document.add(p);
    }

    private void addTopBlock(Document document, SalesInvoice invoice, CompanyDataResponse company) throws DocumentException {
        Font fLabel = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, INK_LIGHT);
        Font fEmpName = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, INK);
        Font fMeta = FontFactory.getFont(FontFactory.HELVETICA, 9, INK_LIGHT);
        Font fMetaRight = FontFactory.getFont(FontFactory.HELVETICA, 9, INK_LIGHT);
        Font fNumber = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, INK);
        Font fCustomerName = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, INK);

        // Tabla cabecera con 3 columnas: emisor (izq, con logo encima) |
        // QR centrado | cliente (der, con nº/fecha encima). Anchos 40/20/40
        // para dar más sitio a los dos bloques de texto y compactar el QR
        // en el centro como pidió el usuario.
        PdfPTable top = new PdfPTable(3);
        top.setWidthPercentage(100);
        top.setWidths(new float[]{40f, 20f, 40f});
        top.setSpacingAfter(14f);

        // --- Izquierda: emisor ---
        PdfPCell leftCell = new PdfPCell();
        leftCell.setBorder(Rectangle.NO_BORDER);
        leftCell.setPadding(2f);
        leftCell.setVerticalAlignment(Element.ALIGN_TOP);

        // Placeholder logo (caja vacía). Cuando companies.logo_path
        // exista, aquí va Image.getInstance(...).
        Paragraph logoSlot = new Paragraph(" ", fMeta);
        logoSlot.setSpacingAfter(6f);
        leftCell.addElement(logoSlot);

        Paragraph emp = new Paragraph();
        emp.setAlignment(Element.ALIGN_LEFT);
        emp.add(new Phrase(nz(company.legalName()) + "\n", fEmpName));
        if (nonBlank(company.taxIdentifier())) {
            emp.add(new Phrase("NIF: " + company.taxIdentifier() + "\n", fMeta));
        }
        String fullAddress = joinNonBlank(", ",
                company.addressLine(), company.postalCode(), company.city(), company.province());
        if (!fullAddress.isBlank()) {
            emp.add(new Phrase(fullAddress + "\n", fMeta));
        }
        String contact = joinNonBlank(" · ", company.phone(), company.email(), company.website());
        if (!contact.isBlank()) {
            emp.add(new Phrase(contact + "\n", fMeta));
        }
        if (nonBlank(company.registryInformation())) {
            emp.add(new Phrase(company.registryInformation() + "\n", fMeta));
        }
        leftCell.addElement(emp);
        top.addCell(leftCell);

        // --- Centro: QR placeholder ---
        PdfPCell centerCell = new PdfPCell();
        centerCell.setBorder(Rectangle.NO_BORDER);
        centerCell.setPadding(2f);
        centerCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        centerCell.setVerticalAlignment(Element.ALIGN_TOP);
        // Pequeño spacer arriba para que el QR no quede pegado al título.
        Paragraph spacerCenter = new Paragraph(" ", fMeta);
        spacerCenter.setSpacingAfter(4f);
        centerCell.addElement(spacerCenter);
        centerCell.addElement(qrPlaceholder());
        top.addCell(centerCell);

        // --- Derecha: nº+fecha y cliente, TODO alineado a la derecha ---
        PdfPCell rightCell = new PdfPCell();
        rightCell.setBorder(Rectangle.NO_BORDER);
        rightCell.setPadding(2f);
        rightCell.setVerticalAlignment(Element.ALIGN_TOP);

        Paragraph numAndDate = new Paragraph();
        numAndDate.setAlignment(Element.ALIGN_RIGHT);
        numAndDate.add(new Phrase("Nº " + nz(invoice.invoiceNumber() == null ? "(borrador)" : invoice.invoiceNumber()) + "\n", fNumber));
        numAndDate.add(new Phrase("Fecha: " + safe(invoice.invoiceDate(), DATE_DM_Y) + "\n", fMetaRight));
        if ("RECTIFYING".equals(invoice.invoiceType()) && nonBlank(invoice.notes())) {
            numAndDate.add(new Phrase(invoice.notes() + "\n", fMetaRight));
        }
        numAndDate.setSpacingAfter(14f);
        rightCell.addElement(numAndDate);

        Paragraph custLabel = new Paragraph("CLIENTE", fLabel);
        custLabel.setAlignment(Element.ALIGN_RIGHT);
        rightCell.addElement(custLabel);

        Paragraph custName = new Paragraph(nz(invoice.customerLegalName()), fCustomerName);
        custName.setAlignment(Element.ALIGN_RIGHT);
        rightCell.addElement(custName);

        top.addCell(rightCell);

        document.add(top);
        document.add(divider());
    }

    private PdfPTable qrPlaceholder() {
        PdfPTable t = new PdfPTable(1);
        // Ancho moderado (90% del contenedor) para dejar respiración en
        // los laterales de la columna central.
        t.setWidthPercentage(90f);
        t.setHorizontalAlignment(Element.ALIGN_CENTER);
        PdfPCell c = new PdfPCell();
        c.setFixedHeight(75f);
        c.setBorder(Rectangle.BOX);
        c.setBorderColor(DIVIDER);
        c.setBackgroundColor(new Color(245, 248, 252));
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Font fSmall = FontFactory.getFont(FontFactory.HELVETICA, 7, INK_LIGHT);
        Paragraph p = new Paragraph("QR\nVeriFactu\n(VF3)", fSmall);
        p.setAlignment(Element.ALIGN_CENTER);
        c.addElement(p);
        t.addCell(c);
        return t;
    }

    // ----- Tabla líneas -----

    private void addLinesTable(Document document, SalesInvoice invoice) throws DocumentException {
        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{50f, 10f, 14f, 10f, 16f});
        table.setSpacingAfter(8f);
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

    // ----- Footer fijo (totales + legales + pie) por página -----

    private class FooterEvent extends PdfPageEventHelper {
        private final SalesInvoice invoice;
        private final CompanyDataResponse company;
        private final InvoiceTexts texts;

        FooterEvent(SalesInvoice invoice, CompanyDataResponse company, InvoiceTexts texts) {
            this.invoice = invoice;
            this.company = company;
            this.texts = texts;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();

            float pageWidth = document.getPageSize().getWidth();
            float pageMarginLeft = document.leftMargin();
            float pageMarginRight = document.rightMargin();
            float footerTopY = FOOTER_HEIGHT + 10f;

            // Divider gris encima del footer.
            cb.saveState();
            cb.setLineWidth(0.5f);
            cb.setColorStroke(DIVIDER);
            cb.moveTo(pageMarginLeft, footerTopY);
            cb.lineTo(pageWidth - pageMarginRight, footerTopY);
            cb.stroke();
            cb.restoreState();

            try {
                // ---- Bloque DERECHA: totales ----
                float totalsWidth = 220f;
                float totalsX = pageWidth - pageMarginRight - totalsWidth;
                float totalsTopY = footerTopY - 14f;
                drawTotals(cb, invoice, totalsX, totalsTopY, totalsWidth);

                // ---- Bloque IZQUIERDA: textos legales + IBAN + pie ----
                float leftWidth = pageWidth - pageMarginLeft - pageMarginRight - totalsWidth - 18f;
                float leftTopY = footerTopY - 14f;
                drawLegalAndFooter(cb, invoice, company, texts, pageMarginLeft, leftTopY, leftWidth);
            } catch (DocumentException ignored) {
                // No reventamos por una página con footer incompleto.
            }
        }

        private void drawTotals(PdfContentByte cb, SalesInvoice invoice, float x, float topY, float width) throws DocumentException {
            Map<BigDecimal, BigDecimal> cuotaByVat = new LinkedHashMap<>();
            for (InvoiceLine line : invoice.lines()) {
                BigDecimal pct = line.vatPercent() == null ? BigDecimal.ZERO : line.vatPercent();
                cuotaByVat.merge(pct, line.lineVat(), BigDecimal::add);
            }

            PdfPTable totals = new PdfPTable(2);
            totals.setTotalWidth(width);
            totals.setWidths(new float[]{60f, 40f});
            totals.setLockedWidth(true);

            Font fRow = FontFactory.getFont(FontFactory.HELVETICA, 10, INK);
            Font fTotalLabel = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.WHITE);
            Font fTotalValue = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, Color.WHITE);

            addTotalRow(totals, "Base imponible", money(invoice.subtotal()), fRow, fRow, null);
            for (Map.Entry<BigDecimal, BigDecimal> e : cuotaByVat.entrySet()) {
                addTotalRow(totals, "IVA " + plainDecimal(e.getKey()) + " %", money(e.getValue()), fRow, fRow, null);
            }
            if (invoice.retentionTotal() != null && invoice.retentionTotal().compareTo(BigDecimal.ZERO) != 0) {
                addTotalRow(totals, "Retención IRPF", "-" + money(invoice.retentionTotal()), fRow, fRow, null);
            }
            addTotalRow(totals, "TOTAL FACTURA", money(invoice.total()), fTotalLabel, fTotalValue, BG_TOTAL);

            totals.writeSelectedRows(0, -1, x, topY, cb);
        }

        private void drawLegalAndFooter(PdfContentByte cb, SalesInvoice invoice, CompanyDataResponse company,
                                         InvoiceTexts texts, float x, float topY, float width) throws DocumentException {
            Font fText = FontFactory.getFont(FontFactory.HELVETICA, 8, INK_LIGHT);
            Font fIban = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, INK);
            Font fFooter = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, INK_LIGHT);

            ColumnText ct = new ColumnText(cb);
            ct.setSimpleColumn(x, 40f, x + width, topY);

            boolean addedSomething = false;
            if (texts != null) {
                if ("RECTIFYING".equals(invoice.invoiceType()) && nonBlank(texts.rectifying())) {
                    ct.addElement(legalParagraph(texts.rectifying(), fText));
                    addedSomething = true;
                }
                boolean hasExempt = invoice.lines().stream()
                        .anyMatch(l -> l.vatPercent() != null && l.vatPercent().compareTo(BigDecimal.ZERO) == 0);
                if (hasExempt && nonBlank(texts.exempt())) {
                    ct.addElement(legalParagraph(texts.exempt(), fText));
                    addedSomething = true;
                }
                boolean hasReduced = invoice.lines().stream()
                        .anyMatch(l -> l.vatPercent() != null
                                && (l.vatPercent().compareTo(new BigDecimal("4")) == 0
                                        || l.vatPercent().compareTo(new BigDecimal("10")) == 0));
                if (hasReduced && nonBlank(texts.reducedVat())) {
                    ct.addElement(legalParagraph(texts.reducedVat(), fText));
                    addedSomething = true;
                }
                if (nonBlank(texts.legalTerms())) {
                    ct.addElement(legalParagraph(texts.legalTerms(), fText));
                    addedSomething = true;
                }
                if (texts.showIban() && nonBlank(company.iban())) {
                    Paragraph ibanP = new Paragraph("IBAN: " + company.iban(), fIban);
                    ibanP.setSpacingBefore(4f);
                    ct.addElement(ibanP);
                    addedSomething = true;
                }
                if (nonBlank(texts.pie())) {
                    Paragraph p = new Paragraph(texts.pie(), fFooter);
                    p.setSpacingBefore(6f);
                    ct.addElement(p);
                    addedSomething = true;
                }
            }
            if (!addedSomething && nonBlank(company.invoiceFooter())) {
                Paragraph p = new Paragraph(company.invoiceFooter(), fFooter);
                ct.addElement(p);
            }

            ct.go();
        }
    }

    // ----- Helpers de cells -----

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
        cLabel.setPadding(6f);
        if (bg != null) cLabel.setBackgroundColor(bg);
        cLabel.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.addCell(cLabel);

        PdfPCell cValue = new PdfPCell(new Phrase(value, fValue));
        cValue.setBorder(Rectangle.NO_BORDER);
        cValue.setPadding(6f);
        if (bg != null) cValue.setBackgroundColor(bg);
        cValue.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(cValue);
    }

    private Paragraph legalParagraph(String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setSpacingBefore(2f);
        p.setSpacingAfter(1f);
        return p;
    }

    private PdfPTable divider() throws DocumentException {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell();
        c.setFixedHeight(1f);
        c.setBorder(Rectangle.NO_BORDER);
        c.setBackgroundColor(DIVIDER);
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
