package com.benjagest.backend.labor;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import com.benjagest.backend.timeclock.TimeClockController;
import com.benjagest.backend.timeclock.TimeClockService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * MEMP-4 — Solicitudes de ausencia del empleado (vacaciones, baja IT, permiso
 * retribuido, otros) pedidas desde el portal del empleado (PWA) con adjuntos.
 *
 * <p>Dos vistas sobre el mismo servicio (tenant-scoped):
 * <ul>
 *   <li>{@link SelfController} {@code /api/empleado/ausencias}: el empleado crea,
 *       lista las SUYAS y cancela mientras estén en REQUESTED.</li>
 *   <li>{@link AdminController} {@code /api/labor/leave-requests}: empresario y
 *       asesoría (OWNER/ADMIN/ACCOUNTANT) listan, aprueban y rechazan.</li>
 * </ul>
 *
 * <p>Al aprobar una VACATION se espeja una fila APPROVED en {@code employee_vacations}
 * (V114) para que el finiquito siga calculando vacaciones disfrutadas sin cambios.
 */
@Service
public class EmployeeLeaveService {

    /** Límite por adjunto (5 MB) — coherente con guardar el BLOB en BD. */
    private static final int MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final CurrentUserService currentUser;
    private final EmployeeVacationService vacationService;
    private final com.benjagest.backend.realtime.RealtimeService realtime;
    // RGPD (2026-07-08): el motivo de la ausencia puede contener
    // informacion medica (SICK_LEAVE) — se guarda cifrado (ENC:).
    private final com.benjagest.backend.config.FieldCipher fieldCipher;

    public EmployeeLeaveService(JdbcTemplate jdbc, TenantContext tenant,
                                CurrentUserService currentUser,
                                EmployeeVacationService vacationService,
                                com.benjagest.backend.realtime.RealtimeService realtime,
                                com.benjagest.backend.config.FieldCipher fieldCipher) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.currentUser = currentUser;
        this.vacationService = vacationService;
        this.realtime = realtime;
        this.fieldCipher = fieldCipher;
    }

    private void emitSafe(Runnable r) { try { r.run(); } catch (Exception ignored) {} }

    // ---- Creación (empleado) ---------------------------------------------

    @Transactional
    public LeaveView create(String employeeId, CreateRequest r) {
        if (!StringUtils.hasText(employeeId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeId requerido");
        }
        String kind = r.kind() == null ? "" : r.kind().toUpperCase();
        if (!List.of("VACATION", "SICK_LEAVE", "PAID_LEAVE", "OTHER").contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de ausencia no válido");
        }
        if (r.startDate() == null || r.endDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fechas requeridas");
        }
        if (r.endDate().isBefore(r.startDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha fin es anterior a la de inicio");
        }
        List<AttachmentInput> atts = r.attachments() == null ? List.of() : r.attachments();
        if ("SICK_LEAVE".equals(kind) && atts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La baja médica requiere adjuntar el parte de baja.");
        }

        String companyId = tenant.getCurrentCompanyId();
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO employee_leave_requests
                       (id, company_id, employee_id, kind, start_date, end_date, days, reason, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED')
                """,
                id, companyId, employeeId, kind, r.startDate(), r.endDate(),
                r.days(), fieldCipher.encrypt(blank(r.reason())));

        for (AttachmentInput a : atts) {
            byte[] bytes = decode(a.base64());
            if (bytes.length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Adjunto vacío o no válido");
            }
            if (bytes.length > MAX_ATTACHMENT_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "El adjunto supera el máximo de 5 MB.");
            }
            jdbc.update("""
                    INSERT INTO employee_leave_attachments
                           (id, company_id, request_id, filename, content_type, size_bytes, content)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), companyId, id,
                    StringUtils.hasText(a.filename()) ? a.filename() : "adjunto",
                    blank(a.contentType()), bytes.length, bytes);
        }
        // NOTIF-RT — avisar a la empresa (empresario/asesoría) de la nueva solicitud.
        emitSafe(() -> realtime.publishToCompany(companyId, "leave",
                "{\"action\":\"created\",\"kind\":\"" + kind + "\",\"employeeId\":\"" + employeeId + "\"}"));
        return findById(id);
    }

    // ---- Listados ---------------------------------------------------------

    public List<LeaveView> listForEmployee(String employeeId) {
        return query(" AND r.employee_id = ? ", List.of(employeeId));
    }

    public List<LeaveView> listForCompany(String status, String employeeId) {
        StringBuilder extra = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(status)) { extra.append(" AND r.status = ? "); args.add(status.toUpperCase()); }
        if (StringUtils.hasText(employeeId)) { extra.append(" AND r.employee_id = ? "); args.add(employeeId); }
        return query(extra.toString(), args);
    }

    private List<LeaveView> query(String extraWhere, List<Object> extraArgs) {
        List<Object> args = new ArrayList<>();
        args.add(tenant.getCurrentCompanyId());
        args.addAll(extraArgs);
        List<LeaveView> rows = jdbc.query("""
                SELECT r.id, r.employee_id, e.full_name AS employee_name,
                       r.kind, r.start_date, r.end_date, r.days, r.reason, r.status,
                       r.reviewed_at, r.review_notes
                  FROM employee_leave_requests r
                  JOIN employees e ON e.id = r.employee_id
                 WHERE r.company_id = ?
                """ + extraWhere + """
                 ORDER BY r.created_at DESC
                """, this::mapRow, args.toArray());
        for (LeaveView v : rows) {
            v.attachments().addAll(listAttachmentMeta(v.id()));
        }
        return rows;
    }

    private List<AttachmentMeta> listAttachmentMeta(String requestId) {
        return jdbc.query("""
                SELECT id, filename, content_type, size_bytes
                  FROM employee_leave_attachments
                 WHERE request_id = ? AND company_id = ?
                 ORDER BY created_at
                """,
                (rs, n) -> new AttachmentMeta(rs.getString("id"), rs.getString("filename"),
                        rs.getString("content_type"), rs.getInt("size_bytes")),
                requestId, tenant.getCurrentCompanyId());
    }

    /** Metadatos de los adjuntos de una solicitud (sin el BLOB). */
    public List<AttachmentMeta> attachmentsOf(String requestId) {
        return listAttachmentMeta(requestId);
    }

    public Attachment getAttachment(String attachmentId) {
        return jdbc.query("""
                SELECT filename, content_type, content
                  FROM employee_leave_attachments
                 WHERE id = ? AND company_id = ?
                """,
                rs -> rs.next()
                        ? new Attachment(rs.getString("filename"), rs.getString("content_type"), rs.getBytes("content"))
                        : null,
                attachmentId, tenant.getCurrentCompanyId());
    }

    // ---- Transiciones de estado ------------------------------------------

    @Transactional
    public void cancel(String id, String employeeId) {
        int n = jdbc.update("""
                UPDATE employee_leave_requests
                   SET status = 'CANCELLED'
                 WHERE id = ? AND company_id = ? AND employee_id = ? AND status = 'REQUESTED'
                """, id, tenant.getCurrentCompanyId(), employeeId);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede cancelar (no existe, no es tuya o ya fue revisada).");
        }
    }

    @Transactional
    public LeaveView approve(String id, String reviewNotes) {
        LeaveView before = findById(id);
        if (!"REQUESTED".equals(before.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La solicitud ya fue revisada.");
        }
        setReviewed(id, "APPROVED", reviewNotes);
        // Espejo de vacaciones aprobadas para el finiquito (V114).
        if ("VACATION".equals(before.kind())) {
            BigDecimal days = before.days() != null && before.days().signum() > 0
                    ? before.days()
                    : BigDecimal.valueOf(before.startDate().until(before.endDate()).getDays() + 1L);
            vacationService.create(new EmployeeVacationService.UpsertRequest(
                    before.employeeId(), before.startDate(), before.endDate(),
                    days, "APPROVED",
                    "MEMP-4: solicitud del empleado" + (StringUtils.hasText(before.reason()) ? " — " + before.reason() : "")));
        }
        emitReviewed(before, "APPROVED");
        return findById(id);
    }

    @Transactional
    public LeaveView reject(String id, String reviewNotes) {
        LeaveView before = findById(id);
        if (!"REQUESTED".equals(before.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La solicitud ya fue revisada.");
        }
        setReviewed(id, "REJECTED", reviewNotes);
        emitReviewed(before, "REJECTED");
        return findById(id);
    }

    /** NOTIF-RT — avisa al empleado (su PWA) de la resolución y refresca a la empresa. */
    private void emitReviewed(LeaveView before, String status) {
        String companyId = tenant.getCurrentCompanyId();
        String json = "{\"action\":\"reviewed\",\"status\":\"" + status
                + "\",\"kind\":\"" + before.kind() + "\"}";
        emitSafe(() -> realtime.publishToEmployee(companyId, before.employeeId(), "leave_reviewed", json));
        emitSafe(() -> realtime.publishToCompany(companyId, "leave", json));
    }

    private void setReviewed(String id, String status, String reviewNotes) {
        jdbc.update("""
                UPDATE employee_leave_requests
                   SET status = ?, reviewed_by = ?, reviewed_at = ?, review_notes = ?
                 WHERE id = ? AND company_id = ?
                """,
                status, currentUser.require().userId(), java.sql.Timestamp.from(Instant.now()),
                blank(reviewNotes), id, tenant.getCurrentCompanyId());
    }

    private LeaveView findById(String id) {
        List<LeaveView> rows = query(" AND r.id = ? ", List.of(id));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Solicitud no encontrada");
        }
        return rows.get(0);
    }

    // ---- Helpers ----------------------------------------------------------

    private LeaveView mapRow(ResultSet rs, int n) throws SQLException {
        java.sql.Date s = rs.getDate("start_date");
        java.sql.Date e = rs.getDate("end_date");
        java.sql.Timestamp rev = rs.getTimestamp("reviewed_at");
        return new LeaveView(
                rs.getString("id"), rs.getString("employee_id"), rs.getString("employee_name"),
                rs.getString("kind"),
                s == null ? null : s.toLocalDate(), e == null ? null : e.toLocalDate(),
                rs.getBigDecimal("days"), fieldCipher.decrypt(rs.getString("reason")), rs.getString("status"),
                rev == null ? null : rev.toInstant(), rs.getString("review_notes"),
                new ArrayList<>());
    }

    private static String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    /** Decodifica base64 admitiendo el prefijo data-URL ("data:...;base64,"). */
    private static byte[] decode(String base64) {
        if (base64 == null || base64.isBlank()) return new byte[0];
        String b = base64;
        int comma = b.indexOf(',');
        if (b.startsWith("data:") && comma >= 0) b = b.substring(comma + 1);
        try {
            return java.util.Base64.getDecoder().decode(b.replaceAll("\\s", ""));
        } catch (IllegalArgumentException ex) {
            return new byte[0];
        }
    }

    // ---- Records ----------------------------------------------------------

    public record LeaveView(
            String id, String employeeId, String employeeName, String kind,
            LocalDate startDate, LocalDate endDate, BigDecimal days, String reason,
            String status, Instant reviewedAt, String reviewNotes,
            List<AttachmentMeta> attachments) {}

    public record AttachmentMeta(String id, String filename, String contentType, int sizeBytes) {}

    public record AttachmentInput(String filename, String contentType, String base64) {}

    public record CreateRequest(String kind, LocalDate startDate, LocalDate endDate,
                                BigDecimal days, String reason, List<AttachmentInput> attachments) {}

    public record ReviewRequest(String reviewNotes) {}

    public record Attachment(String filename, String contentType, byte[] content) {}

    // ---- Controller empleado (PWA) ---------------------------------------

    @RestController
    @RequestMapping("/api/empleado/ausencias")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE", "USER"})
    public static class SelfController {
        private final EmployeeLeaveService service;
        private final TimeClockService timeClock;

        public SelfController(EmployeeLeaveService service, TimeClockService timeClock) {
            this.service = service;
            this.timeClock = timeClock;
        }

        @GetMapping
        public List<LeaveView> mine() {
            TimeClockController.MyEmployeeInfo me = timeClock.resolveCurrentEmployee();
            return service.listForEmployee(me.employeeId());
        }

        @PostMapping
        public LeaveView create(@RequestBody CreateRequest req) {
            TimeClockController.MyEmployeeInfo me = timeClock.resolveCurrentEmployee();
            return service.create(me.employeeId(), req);
        }

        @PostMapping("/{id}/cancel")
        public void cancel(@PathVariable("id") String id) {
            TimeClockController.MyEmployeeInfo me = timeClock.resolveCurrentEmployee();
            service.cancel(id, me.employeeId());
        }
    }

    // ---- Controller asesoría / empresario --------------------------------

    @RestController
    @RequestMapping("/api/labor/leave-requests")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class AdminController {
        private final EmployeeLeaveService service;

        public AdminController(EmployeeLeaveService service) { this.service = service; }

        @GetMapping
        public List<LeaveView> list(@RequestParam(value = "status", required = false) String status,
                                    @RequestParam(value = "employeeId", required = false) String employeeId) {
            return service.listForCompany(status, employeeId);
        }

        @PostMapping("/{id}/approve")
        public LeaveView approve(@PathVariable("id") String id, @RequestBody(required = false) ReviewRequest req) {
            return service.approve(id, req == null ? null : req.reviewNotes());
        }

        @PostMapping("/{id}/reject")
        public LeaveView reject(@PathVariable("id") String id, @RequestBody(required = false) ReviewRequest req) {
            return service.reject(id, req == null ? null : req.reviewNotes());
        }

        @GetMapping("/{id}/attachments")
        public List<AttachmentMeta> attachments(@PathVariable("id") String id) {
            return service.attachmentsOf(id);
        }

        @GetMapping("/attachments/{attachmentId}")
        public org.springframework.http.ResponseEntity<byte[]> attachment(@PathVariable("attachmentId") String attachmentId) {
            Attachment a = service.getAttachment(attachmentId);
            if (a == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Adjunto no encontrado");
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + a.filename() + "\"")
                    .header("Content-Type", a.contentType() != null ? a.contentType() : "application/octet-stream")
                    .body(a.content());
        }
    }
}
