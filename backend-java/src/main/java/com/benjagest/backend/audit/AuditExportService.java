package com.benjagest.backend.audit;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Exportación verificable del registro de auditoría general.
 *
 * <p>Pensado para Hacienda u otros inspectores que pidan ver qué se
 * hizo en el sistema (logins, cambios de módulos, validaciones de
 * facturas, etc.) en un periodo. Mismo patrón que TimeClockExportService:
 * PDF + CSV + SHA-256 del documento + auditoría del propio export.
 */
@Service
public class AuditExportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final AuditEventRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    public AuditExportService(AuditEventRepository repository,
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

    public byte[] exportCsv(LocalDate from, LocalDate to, String eventTypePrefix) {
        List<AuditEvent> events = fetch(from, to, eventTypePrefix);
        Map<String, String> userNames = resolveUserNames(events);
        StringBuilder sb = new StringBuilder();
        // AUDIT-CHAIN: incluimos SEQ, EVENT_HASH y PREV_HASH para que un
        // inspector pueda verificar la cadena externamente recalculando
        // el SHA-256 sobre los campos canónicos.
        sb.append("SEQ;CREATED_AT;EVENT_TYPE;RESULT;USER;ENTITY_TYPE;ENTITY_ID;IP;DETAILS;EVENT_HASH;PREV_HASH\n");
        for (AuditEvent ev : events) {
            sb.append(ev.sequenceNumber() == null ? "" : ev.sequenceNumber())
                    .append(';').append(ev.createdAt() == null ? "" : DATETIME.format(ev.createdAt().atZone(ZoneId.systemDefault())))
                    .append(';').append(nullSafe(ev.eventType()))
                    .append(';').append(nullSafe(ev.result()))
                    .append(';').append(escape(userNames.getOrDefault(ev.userId(), nullSafe(ev.userId()))))
                    .append(';').append(nullSafe(ev.entityType()))
                    .append(';').append(nullSafe(ev.entityId()))
                    .append(';').append(nullSafe(ev.ipAddress()))
                    .append(';').append(escape(nullSafe(ev.details())))
                    .append(';').append(nullSafe(ev.eventHash()))
                    .append(';').append(nullSafe(ev.prevEventHash()))
                    .append('\n');
        }
        byte[] body = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        recordAuditExport("CSV", from, to, eventTypePrefix, events.size(), sha256(body));
        return body;
    }

    public byte[] exportPdf(LocalDate from, LocalDate to, String eventTypePrefix) {
        List<AuditEvent> events = fetch(from, to, eventTypePrefix);
        Map<String, String> userNames = resolveUserNames(events);
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

        doc.add(new Paragraph("REGISTRO DE AUDITORÍA", titleFont));
        doc.add(new Paragraph(companyName + " · NIF " + companyNif, smallFont));
        doc.add(new Paragraph("Período: " + DATE.format(from) + " a " + DATE.format(to)
                + (eventTypePrefix == null || eventTypePrefix.isBlank()
                    ? "" : " · Tipo: " + eventTypePrefix + "*"),
                smallFont));
        doc.add(new Paragraph("Generado: " + DATETIME.format(Instant.now().atZone(ZoneId.systemDefault()))
                + " · " + events.size() + " eventos", smallFont));
        doc.add(new Paragraph(" "));

        // Columnas pensadas para que un inspector lea el documento sin
        // necesidad de descodificar UUIDs: secuencia, fecha, evento,
        // resultado, USUARIO con nombre humano (no UUID), entidad, IP,
        // detalles y los 12 primeros chars del hash (suficiente para
        // verificar manualmente y para detectar manipulación grosera).
        PdfPTable table = new PdfPTable(new float[] { 0.7f, 2.0f, 2.3f, 0.9f, 2.2f, 2.0f, 1.3f, 2.5f, 1.3f });
        table.setWidthPercentage(100);
        addHeader(table, "Seq", headerFont);
        addHeader(table, "Fecha y hora", headerFont);
        addHeader(table, "Evento", headerFont);
        addHeader(table, "Result.", headerFont);
        addHeader(table, "Usuario", headerFont);
        addHeader(table, "Entidad", headerFont);
        addHeader(table, "IP", headerFont);
        addHeader(table, "Detalle", headerFont);
        addHeader(table, "Hash (12)", headerFont);

        for (AuditEvent ev : events) {
            addCell(table, ev.sequenceNumber() == null ? "" : ev.sequenceNumber().toString(), cellFont);
            addCell(table, ev.createdAt() == null ? "" : DATETIME.format(ev.createdAt().atZone(ZoneId.systemDefault())), cellFont);
            addCell(table, nullSafe(ev.eventType()), cellFont);
            addCell(table, nullSafe(ev.result()), cellFont);
            addCell(table, userNames.getOrDefault(ev.userId(), nullSafe(ev.userId())), cellFont);
            addCell(table, nullSafe(ev.entityType()) + (ev.entityId() != null ? " " + ev.entityId() : ""), cellFont);
            addCell(table, nullSafe(ev.ipAddress()), cellFont);
            addCell(table, truncate(nullSafe(ev.details()), 100), cellFont);
            addCell(table, ev.eventHash() == null ? "—" : ev.eventHash().substring(0, Math.min(12, ev.eventHash().length())), cellFont);
        }
        doc.add(table);

        doc.add(new Paragraph(" "));
        doc.add(new Paragraph(
                "Cada evento lleva un hash encadenado (SHA-256 del payload + hash del evento "
                        + "anterior). Si alguien modifica una fila, todos los hash posteriores "
                        + "dejan de coincidir. Verificación disponible en "
                        + "GET /api/settings/audit-events/verify, que recorre y recalcula la cadena. "
                        + "Adicionalmente, este export queda registrado como AUDIT_EXPORTED con el "
                        + "SHA-256 del propio documento.",
                smallFont));
        doc.close();

        byte[] body = baos.toByteArray();
        recordAuditExport("PDF", from, to, eventTypePrefix, events.size(), sha256(body));
        return body;
    }

    private List<AuditEvent> fetch(LocalDate from, LocalDate to, String eventTypePrefix) {
        ZoneId zone = ZoneId.systemDefault();
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.atTime(23, 59, 59).atZone(zone).toInstant();
        return repository.findInRangeForCompany(
                tenantContext.getCurrentCompanyId(), start, end, eventTypePrefix);
    }

    /**
     * Carga los nombres humanos de los usuarios que aparecen en los
     * eventos. Una sola query con IN(...) para no hacer N+1. Si el
     * UUID no resuelve a una fila (usuario borrado), devuelve el UUID
     * como fallback para que el documento no quede vacío.
     */
    private Map<String, String> resolveUserNames(List<AuditEvent> events) {
        Map<String, String> map = new HashMap<>();
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (AuditEvent ev : events) {
            if (ev.userId() != null && !ev.userId().isBlank()) ids.add(ev.userId());
        }
        if (ids.isEmpty()) return map;
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] args = ids.toArray();
        jdbcTemplate.query(
                "SELECT id, COALESCE(NULLIF(full_name, ''), email) AS label "
                        + "FROM user_accounts WHERE id IN (" + placeholders + ")",
                rs -> { map.put(rs.getString("id"), rs.getString("label")); },
                args);
        return map;
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
                                    String filter, int count, String hashHex) {
        AuthenticatedUser user = currentUserService.require();
        auditService.recordExport(user.userId(), tenantContext.getCurrentCompanyId(),
                "AUDIT_EXPORTED", format, from.toString(), to.toString(),
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

    private static String sha256(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body));
        } catch (Exception e) {
            return "";
        }
    }
}
