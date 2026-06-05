package com.benjagest.backend.billing.sif;

import com.benjagest.backend.audit.AuditService;
import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
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
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Exportación PDF/CSV verificable del Registro de Eventos del SIF
 * (Orden HAC/1177/2024 — evento 9: "Exportación de registros de
 * eventos de un período").
 *
 * <p>El controller ya emitía un evento {@code EXPORT_EVENTS} antes de
 * este slice, pero NO entregaba el fichero — ese era el TODO
 * documentado. Este servicio cierra el hueco generando el documento
 * autocontenido + auditando con SHA-256 en {@code audit_events}.
 */
@Service
public class SifEventExportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final SifEventRepository repository;
    private final SifEventService eventService;
    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    public SifEventExportService(SifEventRepository repository,
                                   SifEventService eventService,
                                   JdbcTemplate jdbcTemplate,
                                   TenantContext tenantContext,
                                   CurrentUserService currentUserService,
                                   AuditService auditService) {
        this.repository = repository;
        this.eventService = eventService;
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    public byte[] exportCsv(LocalDate from, LocalDate to, String eventTypeFilter) {
        List<SifEvent> events = fetch(from, to, eventTypeFilter);
        StringBuilder sb = new StringBuilder();
        sb.append("GENERATED_AT;EVENT_TYPE;STATUS;HASH_CURRENT;HASH_PREVIOUS;PAYLOAD\n");
        for (SifEvent ev : events) {
            sb.append(DATETIME.format(ev.generatedAt().atZone(ZoneId.systemDefault())))
                    .append(';').append(nullSafe(ev.eventType()))
                    .append(';').append(nullSafe(ev.status()))
                    .append(';').append(nullSafe(ev.hashCurrent()))
                    .append(';').append(nullSafe(ev.hashPrevious()))
                    .append(';').append(escape(nullSafe(ev.payload())))
                    .append('\n');
        }
        byte[] body = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        recordExport("CSV", from, to, eventTypeFilter, events.size(), sha256(body));
        return body;
    }

    public byte[] exportPdf(LocalDate from, LocalDate to, String eventTypeFilter) {
        List<SifEvent> events = fetch(from, to, eventTypeFilter);
        String companyName = findCompanyLegalName();
        String companyNif = findCompanyTaxId();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 36, 36, 48, 48);
        PdfWriter.getInstance(doc, baos);
        doc.open();

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, new Color(11, 49, 96));
        Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
        Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
        Font smallFont = FontFactory.getFont(FontFactory.HELVETICA, 8, new Color(90, 90, 90));

        doc.add(new Paragraph("REGISTRO DE EVENTOS DEL SIF", titleFont));
        doc.add(new Paragraph(companyName + " · NIF " + companyNif, smallFont));
        doc.add(new Paragraph("Período: " + DATE.format(from) + " a " + DATE.format(to)
                + (eventTypeFilter == null || eventTypeFilter.isBlank()
                    ? "" : " · Tipo: " + eventTypeFilter),
                smallFont));
        doc.add(new Paragraph("Generado: " + DATETIME.format(Instant.now().atZone(ZoneId.systemDefault()))
                + " · " + events.size() + " eventos. RD 1007/2023 + Orden HAC/1177/2024.", smallFont));
        doc.add(new Paragraph(" "));

        PdfPTable table = new PdfPTable(new float[] { 2.2f, 2.5f, 1f, 2.5f, 2.5f, 4f });
        table.setWidthPercentage(100);
        addHeader(table, "Generado", headerFont);
        addHeader(table, "Tipo evento", headerFont);
        addHeader(table, "Estado", headerFont);
        addHeader(table, "Hash actual (16)", headerFont);
        addHeader(table, "Hash previo (16)", headerFont);
        addHeader(table, "Payload", headerFont);

        for (SifEvent ev : events) {
            addCell(table, DATETIME.format(ev.generatedAt().atZone(ZoneId.systemDefault())), cellFont);
            addCell(table, nullSafe(ev.eventType()), cellFont);
            addCell(table, nullSafe(ev.status()), cellFont);
            addCell(table, shortHash(ev.hashCurrent()), cellFont);
            addCell(table, shortHash(ev.hashPrevious()), cellFont);
            addCell(table, truncate(nullSafe(ev.payload()), 200), cellFont);
        }
        doc.add(table);

        doc.add(new Paragraph(" "));
        doc.add(new Paragraph(
                "Cada evento lleva hash encadenado SHA-256 (Orden HAC/1177/2024 art. 8). "
                        + "Verificación disponible en GET /api/billing/sif-events/verify, "
                        + "que recorre y recalcula la cadena. Este export queda registrado "
                        + "tanto como evento EXPORT_EVENTS en la propia cadena SIF como en "
                        + "audit_events con el SHA-256 del documento generado.",
                smallFont));
        doc.close();

        byte[] body = baos.toByteArray();
        recordExport("PDF", from, to, eventTypeFilter, events.size(), sha256(body));
        return body;
    }

    private List<SifEvent> fetch(LocalDate from, LocalDate to, String eventTypeFilter) {
        ZoneId zone = ZoneId.systemDefault();
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.atTime(23, 59, 59).atZone(zone).toInstant();
        return repository.findInRangeForCompany(
                tenantContext.getCurrentCompanyId(), start, end, eventTypeFilter);
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

    /**
     * Auditoría dual del export: como evento EXPORT_EVENTS en la
     * propia cadena SIF (lo exige la Orden HAC) Y como SIF_EVENTS_EXPORTED
     * en audit_events con el SHA-256 del documento generado.
     */
    private void recordExport(String format, LocalDate from, LocalDate to,
                                String filter, int count, String hashHex) {
        AuthenticatedUser user = currentUserService.require();
        // 1) Evento legal en la cadena SIF
        String payload = "{\"format\":\"" + format
                + "\",\"from\":\"" + from
                + "\",\"to\":\"" + to
                + "\",\"count\":" + count
                + ",\"sha256\":\"" + hashHex + "\"}";
        eventService.record("EXPORT_EVENTS", payload);
        // 2) Auditoría general para detectar manipulación posterior
        auditService.recordExport(user.userId(), tenantContext.getCurrentCompanyId(),
                "SIF_EVENTS_EXPORTED", format, from.toString(), to.toString(),
                count, hashHex,
                filter == null || filter.isBlank() ? null : "eventType=" + filter);
    }

    private static void addHeader(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(new Color(11, 49, 96));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(4);
        table.addCell(cell);
    }

    private static void addCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(3);
        table.addCell(cell);
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace(";", ",").replace("\n", " ").replace("\r", " ");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String shortHash(String h) {
        if (h == null || h.isEmpty()) return "—";
        return h.substring(0, Math.min(16, h.length()));
    }

    private static String sha256(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body));
        } catch (Exception e) {
            return "";
        }
    }
}
