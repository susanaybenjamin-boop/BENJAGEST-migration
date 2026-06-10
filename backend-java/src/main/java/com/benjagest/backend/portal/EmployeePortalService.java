package com.benjagest.backend.portal;

import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * PORT-1 — Portal del empleado.
 *
 * <p>Vistas personales que un EMPLOYEE ve cuando entra a BENJAGEST: su
 * propio calendario laboral, sus nóminas, sus notificaciones y sus
 * trabajos asignados. Read-only desde el punto de vista del empleado;
 * los OWNER/ADMIN también pueden entrar pero verán datos vacíos si no
 * tienen ficha de empleado en la empresa activa.
 *
 * <p>Análogo a CONTENDO {@code app/empleado/*}, pero en lugar de 4 rutas
 * separadas se renderiza como un único módulo con 4 pestañas (misma
 * JavaFX, no app aparte).
 */
@Service
public class EmployeePortalService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final TenantContext tenantContext;

    public EmployeePortalService(JdbcTemplate jdbcTemplate,
                                  CurrentUserService currentUserService,
                                  TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.tenantContext = tenantContext;
    }

    /**
     * Devuelve el employees.id del usuario logueado en la empresa activa,
     * o {@code null} si no tiene ficha. NO lanza 404 — el portal del
     * empleado tiene tabs que NO dependen del employeeId (calendario
     * laboral general, por ejemplo), así que devolvemos null y cada
     * endpoint decide qué hacer.
     */
    public String currentEmployeeIdOrNull() {
        AuthenticatedUser user = currentUserService.require();
        String companyId = tenantContext.getCurrentCompanyId();
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id FROM employees WHERE user_id = ? AND company_id = ? AND active = TRUE",
                String.class, user.userId(), companyId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * Lista los eventos del calendario laboral del empleado en el rango
     * dado. Combina:
     * <ul>
     *   <li>Eventos de {@code calendar_events} de la empresa (Agenda
     *       general — incluye festivos volcados desde work_calendars).
     *   </li>
     *   <li>Bajas médicas propias del empleado solapando el rango.</li>
     * </ul>
     *
     * <p>Se pinta con un campo {@code kind} discriminador (CALENDAR,
     * LEAVE) para que la UI pueda colorear y mostrar el origen.
     */
    public List<PortalEvent> listCalendar(LocalDate from, LocalDate to) {
        String companyId = tenantContext.getCurrentCompanyId();
        String employeeId = currentEmployeeIdOrNull();

        // 1) Eventos de la Agenda general filtrados por rango.
        List<PortalEvent> events = jdbcTemplate.query("""
                SELECT id,
                       CAST(event_date AS CHAR) AS event_date,
                       title,
                       COALESCE(description, '') AS detail,
                       event_type,
                       COALESCE(source_type, '') AS source_type
                  FROM calendar_events
                 WHERE company_id = ?
                   AND event_date BETWEEN ? AND ?
                   AND active = TRUE
                """,
                (rs, i) -> new PortalEvent(
                        rs.getString("id"),
                        LocalDate.parse(rs.getString("event_date")),
                        rs.getString("title"),
                        rs.getString("detail"),
                        rs.getString("event_type"),
                        "CALENDAR",
                        rs.getString("source_type")),
                companyId, from, to);

        // 2) Bajas médicas del empleado solapando el rango.
        if (employeeId != null) {
            List<PortalEvent> leaves = jdbcTemplate.query("""
                    SELECT id,
                           CAST(start_date AS CHAR) AS start_date,
                           CAST(COALESCE(end_date, start_date) AS CHAR) AS end_date,
                           leave_type,
                           COALESCE(notes, '') AS notes,
                           status
                      FROM medical_leaves
                     WHERE employee_id = ?
                       AND start_date <= ?
                       AND (end_date IS NULL OR end_date >= ?)
                    """,
                    (rs, i) -> new PortalEvent(
                            rs.getString("id"),
                            LocalDate.parse(rs.getString("start_date")),
                            "Baja: " + rs.getString("leave_type"),
                            rs.getString("notes"),
                            rs.getString("leave_type"),
                            "LEAVE",
                            "MEDICAL_LEAVE"),
                    employeeId, to, from);
            events = new java.util.ArrayList<>(events);
            events.addAll(leaves);
            events.sort(java.util.Comparator.comparing(PortalEvent::date));
        }
        return events;
    }

    /**
     * Lista las nóminas (payslips) del empleado actual. Solo lectura.
     * Devuelve lista vacía si el usuario no tiene ficha de empleado.
     */
    public List<PortalPayslip> listPayslips() {
        String employeeId = currentEmployeeIdOrNull();
        if (employeeId == null) return List.of();
        // payslips puede o no existir; usamos try/catch defensivo para
        // no romper el portal si el módulo de nóminas no está activo.
        try {
            return jdbcTemplate.query("""
                    SELECT id,
                           CAST(period_year AS CHAR) AS period_year,
                           CAST(period_month AS CHAR) AS period_month,
                           COALESCE(net_amount, 0) AS net_amount,
                           COALESCE(gross_amount, 0) AS gross_amount,
                           COALESCE(status, '') AS status,
                           COALESCE(pdf_path, '') AS pdf_path
                      FROM payslips
                     WHERE employee_id = ?
                     ORDER BY period_year DESC, period_month DESC
                     LIMIT 50
                    """,
                    (rs, i) -> new PortalPayslip(
                            rs.getString("id"),
                            rs.getInt("period_year"),
                            rs.getInt("period_month"),
                            rs.getBigDecimal("gross_amount"),
                            rs.getBigDecimal("net_amount"),
                            rs.getString("status"),
                            rs.getString("pdf_path")),
                    employeeId);
        } catch (org.springframework.dao.DataAccessException ex) {
            return List.of();
        }
    }

    /**
     * Lista las notificaciones que afectan al empleado:
     * {@code advisory_notifications} de su empresa con target_user_id
     * NULL o igual al user_id actual. Read-only.
     */
    public List<PortalNotification> listNotifications() {
        AuthenticatedUser user = currentUserService.require();
        String companyId = tenantContext.getCurrentCompanyId();
        try {
            return jdbcTemplate.query("""
                    SELECT id,
                           severity,
                           title,
                           COALESCE(body, '') AS body,
                           COALESCE(CAST(created_at AS CHAR), '') AS created_at,
                           read_at IS NOT NULL AS is_read
                      FROM advisory_notifications
                     WHERE company_id = ?
                       AND (target_user_id IS NULL OR target_user_id = ?)
                       AND dismissed_at IS NULL
                     ORDER BY created_at DESC
                     LIMIT 100
                    """,
                    (rs, i) -> new PortalNotification(
                            rs.getString("id"),
                            rs.getString("severity"),
                            rs.getString("title"),
                            rs.getString("body"),
                            rs.getString("created_at"),
                            rs.getBoolean("is_read")),
                    companyId, user.userId());
        } catch (org.springframework.dao.DataAccessException ex) {
            return List.of();
        }
    }

    /**
     * Lista los trabajos asignados al empleado. Como BENJAGEST aún no
     * tiene tabla {@code jobs} dedicada (queda en PORT-2 si se decide
     * portar work logs de CONTENDO), devolvemos siempre lista vacía.
     * Endpoint preparado para cuando exista.
     */
    public List<PortalJob> listJobs() {
        // TODO PORT-2 — cuando se decida si portamos work_logs con
        // billing embebido o separados, leer la tabla correspondiente.
        return List.of();
    }

    // ============================================================
    //  DTOs
    // ============================================================

    public record PortalEvent(
            String id,
            LocalDate date,
            String title,
            String detail,
            String eventType,
            /** CALENDAR | LEAVE */
            String kind,
            String sourceType
    ) {}

    public record PortalPayslip(
            String id,
            int year,
            int month,
            java.math.BigDecimal grossAmount,
            java.math.BigDecimal netAmount,
            String status,
            String pdfPath
    ) {}

    public record PortalNotification(
            String id,
            String severity,
            String title,
            String body,
            String createdAt,
            boolean read
    ) {}

    public record PortalJob(
            String id,
            String title,
            LocalDate date,
            String status
    ) {}
}
