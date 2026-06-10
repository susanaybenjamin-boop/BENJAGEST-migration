package com.benjagest.backend.worklogs;

import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * PORT-2 (sesión 2026-06-10) — Servicio base de partes de día.
 *
 * <p>Esqueleto: solo lo necesario para que el endpoint
 * {@code /api/portal/jobs} del Portal del empleado deje de devolver
 * lista vacía y para que el OWNER pueda dar de alta partes a mano.
 * Plantillas, turnos, planificación, validación admin y facturación
 * cierran en slices posteriores cuando Benjamin diseñe la UX.
 *
 * <p>Modelo embebido (decisión Benjamin 2026-06-10): cada
 * {@code work_logs} con {@code is_billable=TRUE} + {@code customer_id}
 * setado puede generar una línea de factura al cobrar (ese flujo NO
 * está implementado todavía).
 */
@Service
public class WorkLogService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final TenantContext tenantContext;

    public WorkLogService(JdbcTemplate jdbcTemplate,
                           CurrentUserService currentUserService,
                           TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.tenantContext = tenantContext;
    }

    /** Lista los partes del empleado actual (read-only). */
    public List<WorkLog> listMine(LocalDate from, LocalDate to) {
        AuthenticatedUser user = currentUserService.require();
        String companyId = tenantContext.getCurrentCompanyId();
        List<String> empIds = jdbcTemplate.queryForList(
                "SELECT id FROM employees WHERE user_id = ? AND company_id = ? AND active = TRUE",
                String.class, user.userId(), companyId);
        if (empIds.isEmpty()) return List.of();
        return jdbcTemplate.query("""
                SELECT id, company_id, employee_id,
                       CAST(log_date AS CHAR) AS log_date,
                       minutes_worked, customer_id,
                       COALESCE(description, '') AS description,
                       is_billable, billable_amount, status,
                       billed_invoice_line_id,
                       CAST(COALESCE(approved_at, created_at) AS CHAR) AS sortable_at
                  FROM work_logs
                 WHERE employee_id = ?
                   AND log_date BETWEEN ? AND ?
                 ORDER BY log_date DESC
                 LIMIT 200
                """,
                (rs, i) -> new WorkLog(
                        rs.getString("id"),
                        rs.getString("company_id"),
                        rs.getString("employee_id"),
                        LocalDate.parse(rs.getString("log_date")),
                        rs.getInt("minutes_worked"),
                        rs.getString("customer_id"),
                        rs.getString("description"),
                        rs.getBoolean("is_billable"),
                        rs.getBigDecimal("billable_amount"),
                        rs.getString("status"),
                        rs.getString("billed_invoice_line_id")),
                empIds.get(0), from, to);
    }

    /** Lista todos los partes de la empresa (OWNER/ADMIN). */
    public List<WorkLog> listForCompany(LocalDate from, LocalDate to) {
        String companyId = tenantContext.getCurrentCompanyId();
        return jdbcTemplate.query("""
                SELECT id, company_id, employee_id,
                       CAST(log_date AS CHAR) AS log_date,
                       minutes_worked, customer_id,
                       COALESCE(description, '') AS description,
                       is_billable, billable_amount, status,
                       billed_invoice_line_id
                  FROM work_logs
                 WHERE company_id = ?
                   AND log_date BETWEEN ? AND ?
                 ORDER BY log_date DESC, employee_id
                 LIMIT 500
                """,
                (rs, i) -> new WorkLog(
                        rs.getString("id"),
                        rs.getString("company_id"),
                        rs.getString("employee_id"),
                        LocalDate.parse(rs.getString("log_date")),
                        rs.getInt("minutes_worked"),
                        rs.getString("customer_id"),
                        rs.getString("description"),
                        rs.getBoolean("is_billable"),
                        rs.getBigDecimal("billable_amount"),
                        rs.getString("status"),
                        rs.getString("billed_invoice_line_id")),
                companyId, from, to);
    }

    /** Crea un parte manual. */
    @Transactional
    public WorkLog create(CreateRequest req) {
        String companyId = tenantContext.getCurrentCompanyId();
        if (req.employeeId() == null || req.employeeId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeId obligatorio");
        }
        if (req.logDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "logDate obligatorio");
        }
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO work_logs (id, company_id, employee_id, log_date,
                                       minutes_worked, customer_id, description,
                                       is_billable, billable_amount, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT')
                """,
                id, companyId, req.employeeId(), req.logDate(),
                req.minutesWorked() == null ? 0 : req.minutesWorked(),
                req.customerId(),
                req.description(),
                req.isBillable() != null && req.isBillable(),
                req.billableAmount());
        return findById(id);
    }

    private WorkLog findById(String id) {
        String companyId = tenantContext.getCurrentCompanyId();
        List<WorkLog> rows = jdbcTemplate.query("""
                SELECT id, company_id, employee_id,
                       CAST(log_date AS CHAR) AS log_date,
                       minutes_worked, customer_id,
                       COALESCE(description, '') AS description,
                       is_billable, billable_amount, status,
                       billed_invoice_line_id
                  FROM work_logs
                 WHERE id = ? AND company_id = ?
                """,
                (rs, i) -> new WorkLog(
                        rs.getString("id"),
                        rs.getString("company_id"),
                        rs.getString("employee_id"),
                        LocalDate.parse(rs.getString("log_date")),
                        rs.getInt("minutes_worked"),
                        rs.getString("customer_id"),
                        rs.getString("description"),
                        rs.getBoolean("is_billable"),
                        rs.getBigDecimal("billable_amount"),
                        rs.getString("status"),
                        rs.getString("billed_invoice_line_id")),
                id, companyId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Parte no encontrado");
        }
        return rows.get(0);
    }

    public record WorkLog(
            String id,
            String companyId,
            String employeeId,
            LocalDate logDate,
            int minutesWorked,
            String customerId,
            String description,
            boolean billable,
            BigDecimal billableAmount,
            String status,
            String billedInvoiceLineId
    ) {}

    public record CreateRequest(
            String employeeId,
            LocalDate logDate,
            Integer minutesWorked,
            String customerId,
            String description,
            Boolean isBillable,
            BigDecimal billableAmount
    ) {}
}
