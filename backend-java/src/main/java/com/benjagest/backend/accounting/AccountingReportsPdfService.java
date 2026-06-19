package com.benjagest.backend.accounting;

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
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Export PDF de los informes contables (Balance de Situación y Pérdidas y
 * Ganancias). Reutiliza {@link FinancialReportsService} para los datos y el
 * mismo patrón OpenPDF que el resto de exportaciones. Texto en español.
 */
@Service
public class AccountingReportsPdfService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Locale ES = Locale.forLanguageTag("es-ES");
    private static final Color NAVY = new Color(11, 49, 96);
    private static final Color GREY = new Color(90, 90, 90);

    private final FinancialReportsService reports;
    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public AccountingReportsPdfService(FinancialReportsService reports,
                                         JdbcTemplate jdbcTemplate,
                                         TenantContext tenantContext) {
        this.reports = reports;
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public byte[] balanceSheetPdf(LocalDate asOf) {
        FinancialReportsService.BalanceSheet bs = reports.balanceSheet(asOf);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = open(baos);
        header(doc, "BALANCE DE SITUACIÓN", "A fecha " + DATE.format(asOf));

        Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, NAVY);
        doc.add(new Paragraph("ACTIVO", sectionFont));
        doc.add(sectionsTable(bs.activo()));
        totalLine(doc, "TOTAL ACTIVO", bs.totalActivo());
        doc.add(new Paragraph(" "));
        doc.add(new Paragraph("PATRIMONIO NETO Y PASIVO", sectionFont));
        doc.add(sectionsTable(bs.pasivo()));
        totalLine(doc, "TOTAL PATRIMONIO NETO Y PASIVO", bs.totalPasivo());

        doc.close();
        return baos.toByteArray();
    }

    public byte[] profitAndLossPdf(LocalDate from, LocalDate to) {
        FinancialReportsService.ProfitAndLoss pl = reports.profitAndLoss(from, to);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = open(baos);
        header(doc, "PÉRDIDAS Y GANANCIAS", "Período: " + DATE.format(from) + " a " + DATE.format(to));

        Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, NAVY);
        doc.add(new Paragraph("INGRESOS", sectionFont));
        doc.add(sectionsTable(pl.ingresos()));
        totalLine(doc, "TOTAL INGRESOS", pl.totalIngresos());
        doc.add(new Paragraph(" "));
        doc.add(new Paragraph("GASTOS", sectionFont));
        doc.add(sectionsTable(pl.gastos()));
        totalLine(doc, "TOTAL GASTOS", pl.totalGastos());
        doc.add(new Paragraph(" "));
        totalLine(doc, "RESULTADO DEL EJERCICIO", pl.resultadoExplotacion());

        doc.close();
        return baos.toByteArray();
    }

    // ----- helpers -----

    private Document open(ByteArrayOutputStream baos) {
        Document doc = new Document(PageSize.A4, 40, 40, 48, 48);
        PdfWriter.getInstance(doc, baos);
        doc.open();
        return doc;
    }

    private void header(Document doc, String title, String periodLine) {
        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, NAVY);
        Font smallFont = FontFactory.getFont(FontFactory.HELVETICA, 8, GREY);
        doc.add(new Paragraph(title, titleFont));
        doc.add(new Paragraph(findCompanyLegalName() + " · NIF " + findCompanyTaxId(), smallFont));
        doc.add(new Paragraph(periodLine, smallFont));
        doc.add(new Paragraph("Generado: "
                + DATETIME.format(Instant.now().atZone(ZoneId.systemDefault())), smallFont));
        doc.add(new Paragraph(" "));
    }

    private PdfPTable sectionsTable(List<FinancialReportsService.BalanceSection> sections) {
        PdfPTable t = new PdfPTable(new float[] { 1.2f, 4f, 2f });
        t.setWidthPercentage(100);
        Font secFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.BLACK);
        Font codeFont = FontFactory.getFont(FontFactory.HELVETICA, 8, GREY);
        Font nameFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
        Font amtFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
        if (sections != null) {
            for (FinancialReportsService.BalanceSection s : sections) {
                PdfPCell sc = new PdfPCell(new Phrase(s.name(), secFont));
                sc.setColspan(2);
                sc.setBorder(0);
                sc.setPaddingTop(5);
                t.addCell(sc);
                PdfPCell st = new PdfPCell(new Phrase(money(s.total()), secFont));
                st.setBorder(0);
                st.setPaddingTop(5);
                st.setHorizontalAlignment(Element.ALIGN_RIGHT);
                t.addCell(st);
                if (s.items() != null) {
                    for (FinancialReportsService.BalanceItem it : s.items()) {
                        cell(t, it.code(), codeFont, Element.ALIGN_LEFT);
                        cell(t, it.name(), nameFont, Element.ALIGN_LEFT);
                        cell(t, money(it.amount()), amtFont, Element.ALIGN_RIGHT);
                    }
                }
            }
        }
        return t;
    }

    private void totalLine(Document doc, String label, BigDecimal total) {
        PdfPTable t = new PdfPTable(new float[] { 4f, 2f });
        t.setWidthPercentage(100);
        Font f = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, NAVY);
        PdfPCell l = new PdfPCell(new Phrase(label, f));
        l.setBorder(com.lowagie.text.Rectangle.TOP);
        l.setPadding(4);
        PdfPCell v = new PdfPCell(new Phrase(money(total), f));
        v.setBorder(com.lowagie.text.Rectangle.TOP);
        v.setPadding(4);
        v.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.addCell(l);
        t.addCell(v);
        doc.add(t);
    }

    private void cell(PdfPTable t, String text, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, f));
        c.setBorder(0);
        c.setPadding(2);
        c.setHorizontalAlignment(align);
        t.addCell(c);
    }

    private String money(BigDecimal v) {
        return NumberFormat.getCurrencyInstance(ES).format(v == null ? BigDecimal.ZERO : v);
    }

    private String findCompanyLegalName() {
        return jdbcTemplate.query("SELECT legal_name FROM companies WHERE id = ?",
                rs -> rs.next() ? rs.getString("legal_name") : "—",
                tenantContext.getCurrentCompanyId());
    }

    private String findCompanyTaxId() {
        return jdbcTemplate.query("SELECT tax_identifier FROM companies WHERE id = ?",
                rs -> rs.next() ? rs.getString("tax_identifier") : "—",
                tenantContext.getCurrentCompanyId());
    }
}
