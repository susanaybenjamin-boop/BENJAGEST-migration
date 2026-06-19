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
import java.time.Month;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * FIN-5 — Informe PDF del cuadro de mando financiero del cliente.
 *
 * <p>Documento orientativo (no es una declaración) con: resumen de cifras
 * del periodo, proyección de cierre + IS estimado, recomendaciones y la
 * evolución mensual. Texto en español, como el resto de PDFs generados
 * (factura, contrato, fichajes, auditoría). Reutiliza
 * {@link ClientFinancialsService} para todos los cálculos.
 */
@Service
public class FinancialDashboardPdfService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Locale ES = Locale.forLanguageTag("es-ES");
    private static final Color NAVY = new Color(11, 49, 96);
    private static final Color GREY = new Color(90, 90, 90);

    private final ClientFinancialsService financials;
    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public FinancialDashboardPdfService(ClientFinancialsService financials,
                                          JdbcTemplate jdbcTemplate,
                                          TenantContext tenantContext) {
        this.financials = financials;
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public byte[] exportPdf(LocalDate from, LocalDate to, int year) {
        ClientFinancialsService.ClientFinancials f = financials.compute(from, to);
        ClientFinancialsService.ClosingProjection p = financials.projectYearEnd(year);
        List<ClientFinancialsService.MonthPoint> months = financials.monthlySeries(year);
        String companyName = findCompanyLegalName();
        String companyNif = findCompanyTaxId();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 48, 48);
        PdfWriter.getInstance(doc, baos);
        doc.open();

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, NAVY);
        Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, NAVY);
        Font smallFont = FontFactory.getFont(FontFactory.HELVETICA, 8, GREY);

        doc.add(new Paragraph("CUADRO DE MANDO FINANCIERO", titleFont));
        doc.add(new Paragraph(companyName + " · NIF " + companyNif, smallFont));
        doc.add(new Paragraph("Período: " + DATE.format(from) + " a " + DATE.format(to), smallFont));
        doc.add(new Paragraph("Generado: " + DATETIME.format(Instant.now().atZone(ZoneId.systemDefault())), smallFont));
        doc.add(new Paragraph(" "));

        // Resumen de cifras.
        doc.add(new Paragraph("Resumen del periodo", sectionFont));
        PdfPTable t = kvTable();
        kvRow(t, "Ingresos", money(f.income()));
        kvRow(t, "Gastos", money(f.expenses()));
        kvRow(t, "Beneficio / pérdida", money(f.result()));
        kvRow(t, "Margen", pct(f.marginPct()));
        kvRow(t, "Coste de personal", money(f.personnelCost()));
        kvRow(t, "Coste personal / ingresos", pct(f.personnelRatioPct()));
        kvRow(t, "Gasto / ingreso", pct(f.expenseRatioPct()));
        kvRow(t, "IVA repercutido", money(f.vatCharged()));
        kvRow(t, "IVA soportado", money(f.vatBorne()));
        kvRow(t, "Modelo 303 estimado", money(f.model303Estimated()));
        kvRow(t, "Pendiente de cobro", money(f.pendingCollections()));
        kvRow(t, "Facturas vencidas", String.valueOf(f.overdueInvoices()));
        kvRow(t, "Pendiente de pago (proveedores)", money(f.pendingPayments()));
        doc.add(t);
        doc.add(new Paragraph(" "));

        // Proyección de cierre.
        doc.add(new Paragraph("Proyección de cierre (orientativa)", sectionFont));
        PdfPTable pt = kvTable();
        kvRow(pt, "Beneficio acumulado", money(p.resultToDate()));
        kvRow(pt, "Meses transcurridos", String.valueOf(p.monthsElapsed()));
        kvRow(pt, "Beneficio proyectado (12 meses)", money(p.projectedResult()));
        kvRow(pt, "IS estimado (25%)", money(p.estimatedCorporateTax()));
        kvRow(pt, "Beneficio tras IS", money(p.projectedAfterTax()));
        doc.add(pt);
        doc.add(new Paragraph(" "));

        // Recomendaciones.
        doc.add(new Paragraph("Recomendaciones", sectionFont));
        Font recFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);
        for (String r : recommendations(f)) {
            doc.add(new Paragraph("•  " + r, recFont));
        }
        doc.add(new Paragraph(" "));

        // Evolución mensual.
        doc.add(new Paragraph("Evolución mensual " + year, sectionFont));
        PdfPTable mt = new PdfPTable(new float[] { 2f, 1.5f, 1.5f, 1.5f });
        mt.setWidthPercentage(100);
        Font hf = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
        headerCell(mt, "Mes", hf);
        headerCell(mt, "Ingresos", hf);
        headerCell(mt, "Gastos", hf);
        headerCell(mt, "Beneficio", hf);
        Font cf = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
        for (ClientFinancialsService.MonthPoint m : months) {
            cell(mt, monthName(m.month()), cf, Element.ALIGN_LEFT);
            cell(mt, money(m.income()), cf, Element.ALIGN_RIGHT);
            cell(mt, money(m.expenses()), cf, Element.ALIGN_RIGHT);
            cell(mt, money(m.result()), cf, Element.ALIGN_RIGHT);
        }
        doc.add(mt);
        doc.add(new Paragraph(" "));

        Paragraph note = new Paragraph(
                "Documento orientativo de gestión, no es una declaración fiscal. Las cifras "
                + "provienen del diario contabilizado; los asientos en borrador no se incluyen. "
                + "La proyección extrapola linealmente el resultado acumulado.", smallFont);
        doc.add(note);

        doc.close();
        return baos.toByteArray();
    }

    /** Mismas reglas que la UI (FIN-4), en español para el PDF. */
    private List<String> recommendations(ClientFinancialsService.ClientFinancials f) {
        List<String> out = new ArrayList<>();
        if (f.overdueInvoices() > 0) {
            out.add("Tienes " + f.overdueInvoices() + " factura(s) vencida(s) sin cobrar ("
                    + money(f.pendingCollections()) + "). Reclama el cobro.");
        }
        if (f.result().signum() < 0) {
            out.add("El resultado del periodo es negativo (" + money(f.result())
                    + "). Revisa gastos y márgenes.");
        }
        if (f.income().signum() > 0 && f.personnelRatioPct().compareTo(new BigDecimal("40")) > 0) {
            out.add("El coste de personal es el " + pct(f.personnelRatioPct())
                    + " de los ingresos (alto). Revisa la estructura de costes.");
        }
        if (f.income().signum() > 0 && f.expenseRatioPct().compareTo(new BigDecimal("90")) > 0
                && f.result().signum() >= 0) {
            out.add("Los gastos son el " + pct(f.expenseRatioPct())
                    + " de los ingresos; el margen es muy ajustado.");
        }
        if (f.model303Estimated().signum() > 0) {
            out.add("IVA estimado a pagar (Modelo 303): " + money(f.model303Estimated())
                    + ". Provisiona la liquidación.");
        } else if (f.model303Estimated().signum() < 0) {
            out.add("IVA estimado a tu favor: " + money(f.model303Estimated().abs())
                    + " (a compensar o devolver).");
        }
        if (f.draftCount() > 0) {
            out.add("Hay " + f.draftCount() + " asientos en borrador por validar; "
                    + "las cifras no son definitivas.");
        }
        if (out.isEmpty()) {
            out.add("Sin alertas para este periodo. Cifras saludables.");
        }
        return out;
    }

    // ----- helpers PDF -----

    private PdfPTable kvTable() {
        PdfPTable t = new PdfPTable(new float[] { 2.5f, 2f });
        t.setWidthPercentage(70);
        t.setHorizontalAlignment(Element.ALIGN_LEFT);
        return t;
    }

    private void kvRow(PdfPTable t, String k, String v) {
        Font kf = FontFactory.getFont(FontFactory.HELVETICA, 9, GREY);
        Font vf = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.BLACK);
        PdfPCell c1 = new PdfPCell(new Phrase(k, kf));
        c1.setBorder(0);
        c1.setPadding(3);
        PdfPCell c2 = new PdfPCell(new Phrase(v, vf));
        c2.setBorder(0);
        c2.setPadding(3);
        c2.setHorizontalAlignment(Element.ALIGN_RIGHT);
        t.addCell(c1);
        t.addCell(c2);
    }

    private void headerCell(PdfPTable t, String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBackgroundColor(NAVY);
        c.setPadding(4);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        t.addCell(c);
    }

    private void cell(PdfPTable t, String text, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setPadding(3);
        c.setHorizontalAlignment(align);
        t.addCell(c);
    }

    private String money(BigDecimal v) {
        return NumberFormat.getCurrencyInstance(ES).format(v == null ? BigDecimal.ZERO : v);
    }

    private String pct(BigDecimal v) {
        return (v == null ? "0" : v.toPlainString()) + " %";
    }

    private String monthName(int m) {
        if (m < 1 || m > 12) return String.valueOf(m);
        return Month.of(m).getDisplayName(TextStyle.FULL, ES);
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
