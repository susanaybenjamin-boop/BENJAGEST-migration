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
    private final JournalQueryService journal;
    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public AccountingReportsPdfService(FinancialReportsService reports,
                                         JournalQueryService journal,
                                         JdbcTemplate jdbcTemplate,
                                         TenantContext tenantContext) {
        this.reports = reports;
        this.journal = journal;
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

    public byte[] ledgerPdf(String accountId, LocalDate from, LocalDate to) {
        JournalQueryService.LedgerView lv = journal.ledger(accountId, from, to);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = open(baos);
        header(doc, "LIBRO MAYOR",
                "Cuenta " + lv.accountCode() + " · " + lv.accountName()
                + "  ·  Período: " + periodLine(from, to));

        Font small = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, GREY);
        doc.add(new Paragraph("Saldo de apertura: " + money(lv.openingBalance()), small));
        doc.add(new Paragraph(" "));

        PdfPTable t = new PdfPTable(new float[] { 1.1f, 0.9f, 3.2f, 1.4f, 1.4f, 1.6f });
        t.setWidthPercentage(100);
        Font hf = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
        headerCell(t, "Fecha", hf);
        headerCell(t, "Asiento", hf);
        headerCell(t, "Concepto", hf);
        headerCell(t, "Debe", hf);
        headerCell(t, "Haber", hf);
        headerCell(t, "Saldo", hf);
        Font cf = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
        for (JournalQueryService.LedgerLine l : lv.movements()) {
            String concept = (l.lineDescription() != null && !l.lineDescription().isBlank())
                    ? l.lineDescription() : l.concept();
            cell(t, l.entryDate() == null ? "" : DATE.format(l.entryDate()), cf, Element.ALIGN_LEFT);
            cell(t, String.valueOf(l.entryNumber()), cf, Element.ALIGN_LEFT);
            cell(t, concept, cf, Element.ALIGN_LEFT);
            cell(t, money(l.debit()), cf, Element.ALIGN_RIGHT);
            cell(t, money(l.credit()), cf, Element.ALIGN_RIGHT);
            cell(t, money(l.runningBalance()), cf, Element.ALIGN_RIGHT);
        }
        doc.add(t);
        totalLine(doc, "SALDO FINAL", lv.closingBalance());

        doc.close();
        return baos.toByteArray();
    }

    public byte[] trialBalancePdf(LocalDate from, LocalDate to, String prefix) {
        List<JournalQueryService.BalanceRow> rows = journal.balance(from, to, prefix);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = open(baos);
        header(doc, "BALANCE DE SUMAS Y SALDOS",
                "Período: " + periodLine(from, to)
                + (prefix != null && !prefix.isBlank() ? "  ·  Grupo " + prefix : ""));

        PdfPTable t = new PdfPTable(new float[] { 1.0f, 3.2f, 1.4f, 1.4f, 1.4f, 1.4f });
        t.setWidthPercentage(100);
        Font hf = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
        headerCell(t, "Cuenta", hf);
        headerCell(t, "Descripción", hf);
        headerCell(t, "Debe", hf);
        headerCell(t, "Haber", hf);
        headerCell(t, "S. Deudor", hf);
        headerCell(t, "S. Acreedor", hf);
        Font cf = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
        BigDecimal td = BigDecimal.ZERO, tc = BigDecimal.ZERO;
        BigDecimal tsd = BigDecimal.ZERO, tsa = BigDecimal.ZERO;
        for (JournalQueryService.BalanceRow r : rows) {
            cell(t, r.code(), cf, Element.ALIGN_LEFT);
            cell(t, r.name(), cf, Element.ALIGN_LEFT);
            cell(t, money(r.totalDebit()), cf, Element.ALIGN_RIGHT);
            cell(t, money(r.totalCredit()), cf, Element.ALIGN_RIGHT);
            cell(t, money(r.saldoDeudor()), cf, Element.ALIGN_RIGHT);
            cell(t, money(r.saldoAcreedor()), cf, Element.ALIGN_RIGHT);
            td = td.add(nz(r.totalDebit()));
            tc = tc.add(nz(r.totalCredit()));
            tsd = tsd.add(nz(r.saldoDeudor()));
            tsa = tsa.add(nz(r.saldoAcreedor()));
        }
        // Fila de totales.
        Font bf = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, NAVY);
        PdfPCell totLabel = new PdfPCell(new Phrase("TOTALES", bf));
        totLabel.setColspan(2);
        totLabel.setBorder(com.lowagie.text.Rectangle.TOP);
        totLabel.setPadding(3);
        t.addCell(totLabel);
        totalCell(t, money(td), bf);
        totalCell(t, money(tc), bf);
        totalCell(t, money(tsd), bf);
        totalCell(t, money(tsa), bf);
        doc.add(t);

        doc.close();
        return baos.toByteArray();
    }

    // ----- helpers -----

    private String periodLine(LocalDate from, LocalDate to) {
        return (from == null ? "—" : DATE.format(from)) + " a " + (to == null ? "—" : DATE.format(to));
    }

    private void headerCell(PdfPTable t, String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBackgroundColor(NAVY);
        c.setPadding(4);
        c.setBorder(0);
        t.addCell(c);
    }

    private void totalCell(PdfPTable t, String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBorder(com.lowagie.text.Rectangle.TOP);
        c.setPadding(3);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.addCell(c);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

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
