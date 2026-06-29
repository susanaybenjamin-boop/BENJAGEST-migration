package com.benjagest.backend.consolidation;

import com.benjagest.backend.auth.RequiresRole;
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
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;

/** CONSOL-4 — Informe PDF del balance consolidado de un grupo. */
@Service
public class ConsolidationPdfService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Locale ES = Locale.forLanguageTag("es-ES");
    private static final Color NAVY = new Color(11, 49, 96);
    private static final Color GREY = new Color(90, 90, 90);

    private final ConsolidationReportService reports;
    private final JdbcTemplate jdbc;

    public ConsolidationPdfService(ConsolidationReportService reports, JdbcTemplate jdbc) {
        this.reports = reports;
        this.jdbc = jdbc;
    }

    public byte[] consolidatedPdf(String groupId, LocalDate asOf) {
        if (asOf == null) asOf = LocalDate.now();
        ConsolidationReportService.ConsolidatedResult r = reports.consolidated(groupId, asOf);
        String groupName = jdbc.query("SELECT name FROM company_groups WHERE id = ?",
                rs -> rs.next() ? rs.getString("name") : "", groupId);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 48, 48);
        PdfWriter.getInstance(doc, baos);
        doc.open();

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, NAVY);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 8, GREY);
        doc.add(new Paragraph("BALANCE CONSOLIDADO", titleFont));
        doc.add(new Paragraph("Grupo: " + groupName + "  ·  " + r.companyCount() + " empresas", small));
        doc.add(new Paragraph("A fecha " + DATE.format(asOf), small));
        doc.add(new Paragraph("Generado: " + DATETIME.format(Instant.now().atZone(ZoneId.systemDefault())), small));
        doc.add(new Paragraph(" "));

        // Balance consolidado.
        PdfPTable t = new PdfPTable(new float[] {1.0f, 3.4f, 1.5f, 1.5f, 1.5f});
        t.setWidthPercentage(100);
        Font hf = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
        headerCell(t, "Cuenta", hf);
        headerCell(t, "Descripción", hf);
        headerCell(t, "Debe", hf);
        headerCell(t, "Haber", hf);
        headerCell(t, "Saldo", hf);
        Font cf = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
        for (ConsolidationReportService.Line l : r.lines()) {
            cell(t, l.code(), cf, Element.ALIGN_LEFT);
            cell(t, l.name(), cf, Element.ALIGN_LEFT);
            cell(t, money(l.debit()), cf, Element.ALIGN_RIGHT);
            cell(t, money(l.credit()), cf, Element.ALIGN_RIGHT);
            cell(t, money(l.balance()), cf, Element.ALIGN_RIGHT);
        }
        doc.add(t);
        totalLine(doc, "RESULTADO CONSOLIDADO", r.resultado());

        // Eliminaciones intragrupo.
        doc.add(new Paragraph(" "));
        doc.add(new Paragraph("ELIMINACIONES INTRAGRUPO (saldos recíprocos)",
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, NAVY)));
        if (r.eliminations().isEmpty()) {
            doc.add(new Paragraph("No se detectan saldos recíprocos entre las empresas del grupo.", small));
        } else {
            PdfPTable e = new PdfPTable(new float[] {2.2f, 1.0f, 2.6f, 1.4f, 1.5f});
            e.setWidthPercentage(100);
            headerCell(e, "Empresa", hf);
            headerCell(e, "Cuenta", hf);
            headerCell(e, "Descripción", hf);
            headerCell(e, "NIF tercero", hf);
            headerCell(e, "Saldo", hf);
            for (ConsolidationReportService.EliminationRow er : r.eliminations()) {
                cell(e, er.companyName(), cf, Element.ALIGN_LEFT);
                cell(e, er.code(), cf, Element.ALIGN_LEFT);
                cell(e, er.name(), cf, Element.ALIGN_LEFT);
                cell(e, er.terceroNif(), cf, Element.ALIGN_LEFT);
                cell(e, money(er.balance()), cf, Element.ALIGN_RIGHT);
            }
            doc.add(e);
            doc.add(new Paragraph("Clientes eliminados: " + money(r.receivablesEliminated())
                    + "   ·   Proveedores eliminados: " + money(r.payablesEliminated()), small));
        }

        doc.add(new Paragraph(" "));
        doc.add(new Paragraph("Nota: las eliminaciones de pérdidas y ganancias intragrupo "
                + "(ventas/compras entre empresas del grupo) son un refinamiento posterior.",
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 7, GREY)));

        doc.close();
        return baos.toByteArray();
    }

    private void headerCell(PdfPTable t, String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBackgroundColor(NAVY);
        c.setPadding(4);
        c.setBorder(0);
        t.addCell(c);
    }

    private void cell(PdfPTable t, String text, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, f));
        c.setBorder(0);
        c.setPadding(2);
        c.setHorizontalAlignment(align);
        t.addCell(c);
    }

    private void totalLine(Document doc, String label, BigDecimal total) {
        PdfPTable t = new PdfPTable(new float[] {4f, 2f});
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

    private String money(BigDecimal v) {
        return NumberFormat.getCurrencyInstance(ES).format(v == null ? BigDecimal.ZERO : v);
    }

    @RestController
    @RequestMapping("/api/company-groups")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class Controller {
        private final ConsolidationPdfService service;

        public Controller(ConsolidationPdfService service) { this.service = service; }

        @GetMapping("/{id}/consolidated.pdf")
        public ResponseEntity<byte[]> pdf(
                @PathVariable("id") String groupId,
                @RequestParam(value = "asOf", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
            byte[] bytes = service.consolidatedPdf(groupId, asOf);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"consolidado.pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(bytes);
        }
    }
}
