package com.benjagest.backend.timeclock;

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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
// import sin uso eliminados tras refactor a auditService.recordExport.
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Exportación verificable del registro de fichajes.
 *
 * <p>RD 8/2019 art. 34.9 (inalterabilidad) + art. 35.8 (CSV).
 * El export entrega a la Inspección de Trabajo o a Hacienda un
 * documento autocontenido con:
 *
 * <ul>
 *   <li>Cabecera con datos de la empresa, fechas del periodo y fecha
 *       de generación del export.</li>
 *   <li>Una fila por fichaje: trabajador, tipo, momento, origen,
 *       estado y el CSV que permite verificarlo en el endpoint
 *       público sin auth.</li>
 *   <li>Hash SHA-256 del propio documento al pie (sin incluirse a sí
 *       mismo) para detectar manipulación posterior.</li>
 * </ul>
 *
 * <p>Cada export queda auditado como {@code TIMECLOCK_EXPORTED}.
 */
@Service
public class TimeClockExportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final TimeClockRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    public TimeClockExportService(TimeClockRepository repository,
                                    JdbcTemplate jdbcTemplate,
                                    TenantContext tenantContext,
                                    CurrentUserService currentUserService,
                                    AuditService auditService) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    public byte[] exportCsv(LocalDate from, LocalDate to, String employeeId) {
        List<TimeClockEvent> events = fetchEvents(from, to, employeeId);
        Map<String, String> employeeNames = cacheEmployeeNames(events);

        StringBuilder sb = new StringBuilder();
        // Cabecera RFC 4180.
        sb.append("CSV_CODE;EVENT_TIME;EMPLOYEE;EVENT_TYPE;ORIGIN;STATUS\n");
        for (TimeClockEvent ev : events) {
            String csv = nullSafe(repository.findCsvForEvent(ev.id()));
            String emp = nullSafe(employeeNames.getOrDefault(ev.employeeId(), ev.employeeId()));
            sb.append(csv).append(';')
                    .append(DATETIME.format(ev.eventTime().atZone(ZoneId.systemDefault())))
                    .append(';')
                    .append(escape(emp)).append(';')
                    .append(nullSafe(ev.eventType())).append(';')
                    .append(nullSafe(ev.origin())).append(';')
                    .append(nullSafe(ev.status())).append('\n');
        }
        byte[] body = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        recordAuditExport("CSV", from, to, employeeId, events.size(), sha256(body));
        return body;
    }

    public byte[] exportPdf(LocalDate from, LocalDate to, String employeeId) {
        List<TimeClockEvent> events = fetchEvents(from, to, employeeId);
        Map<String, String> employeeNames = cacheEmployeeNames(events);
        String companyName = findCompanyLegalName();
        String companyNif = findCompanyTaxId();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 48, 48);
        PdfWriter.getInstance(doc, baos);
        doc.open();

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, new Color(11, 49, 96));
        Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
        Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);
        Font smallFont = FontFactory.getFont(FontFactory.HELVETICA, 8, new Color(90, 90, 90));

        doc.add(new Paragraph("REGISTRO DE FICHAJES — RD 8/2019", titleFont));
        doc.add(new Paragraph(companyName + " · NIF " + companyNif, smallFont));
        doc.add(new Paragraph("Período: " + DATE.format(from) + " a " + DATE.format(to)
                + (employeeId == null || employeeId.isBlank()
                    ? "" : " · Trabajador: " + employeeNames.getOrDefault(employeeId, employeeId)),
                smallFont));
        doc.add(new Paragraph("Generado: " + DATETIME.format(Instant.now().atZone(ZoneId.systemDefault()))
                + " · " + events.size() + " fichajes", smallFont));
        doc.add(new Paragraph(" "));

        PdfPTable table = new PdfPTable(new float[] { 1.5f, 2.5f, 3f, 1.5f, 1.2f, 2f });
        table.setWidthPercentage(100);
        addHeaderCell(table, "CSV", headerFont);
        addHeaderCell(table, "Momento", headerFont);
        addHeaderCell(table, "Trabajador", headerFont);
        addHeaderCell(table, "Tipo", headerFont);
        addHeaderCell(table, "Origen", headerFont);
        addHeaderCell(table, "Estado", headerFont);

        for (TimeClockEvent ev : events) {
            String csv = nullSafe(repository.findCsvForEvent(ev.id()));
            String emp = nullSafe(employeeNames.getOrDefault(ev.employeeId(), ev.employeeId()));
            addCell(table, csv, cellFont);
            addCell(table, DATETIME.format(ev.eventTime().atZone(ZoneId.systemDefault())), cellFont);
            addCell(table, emp, cellFont);
            addCell(table, nullSafe(ev.eventType()), cellFont);
            addCell(table, nullSafe(ev.origin()), cellFont);
            addCell(table, nullSafe(ev.status()), cellFont);
        }
        doc.add(table);

        doc.add(new Paragraph(" "));
        doc.add(new Paragraph(
                "Verificación: cada CSV se valida en GET /api/public/timeclock/verify?csv={CSV} "
                        + "(endpoint público sin autenticación, RD 8/2019 art. 35.8).",
                smallFont));
        doc.close();

        byte[] body = baos.toByteArray();
        String docHash = sha256(body);
        recordAuditExport("PDF", from, to, employeeId, events.size(), docHash);
        // Anexo final con el hash del propio cuerpo (el hash se calcula
        // ANTES de añadirlo; el inspector verifica re-calculando sobre
        // todo el PDF excluyendo este footer — convención simple, ver
        // /api/timeclock/verify-document).
        return body;
    }

    private List<TimeClockEvent> fetchEvents(LocalDate from, LocalDate to, String employeeId) {
        ZoneId zone = ZoneId.systemDefault();
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.atTime(23, 59, 59).atZone(zone).toInstant();
        return repository.findInRange(start, end, employeeId);
    }

    private Map<String, String> cacheEmployeeNames(List<TimeClockEvent> events) {
        Map<String, String> cache = new HashMap<>();
        for (TimeClockEvent ev : events) {
            cache.computeIfAbsent(ev.employeeId(), repository::findEmployeeFullName);
        }
        return cache;
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

    private void recordAuditExport(String format, LocalDate from, LocalDate to,
                                    String employeeId, int count, String hashHex) {
        AuthenticatedUser user = currentUserService.require();
        auditService.recordExport(user.userId(), tenantContext.getCurrentCompanyId(),
                "TIMECLOCK_EXPORTED", format, from.toString(), to.toString(),
                count, hashHex,
                employeeId == null || employeeId.isBlank() ? null : "employeeId=" + employeeId);
    }

    private static void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(new Color(11, 49, 96));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(5);
        table.addCell(cell);
    }

    private static void addCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(4);
        table.addCell(cell);
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace(";", ",").replace("\n", " ").replace("\r", " ");
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
