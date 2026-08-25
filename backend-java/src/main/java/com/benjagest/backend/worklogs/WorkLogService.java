package com.benjagest.backend.worklogs;

import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * MÓDULO TRABAJOS (TRB-1, 2026-06-22) — partes/trabajos facturables.
 *
 * <p>Un trabajo (work_logs) pertenece a un empleado y opcionalmente a un cliente.
 * Se valora como en CONTENDO: por unidad (HOURS/DAYS/MONTHS = cantidad × precio)
 * o por precio CERRADO (FIXED, importe fijo). Si {@code is_billable} y tiene
 * cliente, puede convertirse en línea(s) de factura (flujo TRB-3) y entonces
 * queda BILLED con su {@code billed_invoice_line_id}.
 *
 * <p>Estados: DRAFT → APPROVED → BILLED. El cobro lo lleva la FACTURA (no el
 * trabajo): aquí no se duplica el estado de pago.
 */
@Service
public class WorkLogService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final TenantContext tenantContext;
    private final com.benjagest.backend.billing.invoices.SalesInvoiceService salesInvoiceService;

    public WorkLogService(JdbcTemplate jdbcTemplate,
                           CurrentUserService currentUserService,
                           TenantContext tenantContext,
                           com.benjagest.backend.billing.invoices.SalesInvoiceService salesInvoiceService) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.tenantContext = tenantContext;
        this.salesInvoiceService = salesInvoiceService;
    }

    private static final String SELECT = """
            SELECT w.id, w.company_id, w.employee_id, e.full_name AS employee_name,
                   CAST(w.log_date AS CHAR) AS log_date, w.minutes_worked,
                   w.customer_id, c.legal_name AS customer_name,
                   COALESCE(w.description, '') AS description,
                   w.is_billable, w.billing_unit, w.quantity, w.unit_price,
                   w.billable_amount, w.status, w.billed_invoice_line_id
              FROM work_logs w
              LEFT JOIN employees e ON e.id = w.employee_id
              LEFT JOIN customers c ON c.id = w.customer_id
            """;

    private final org.springframework.jdbc.core.RowMapper<WorkLog> mapper = (rs, i) -> new WorkLog(
            rs.getString("id"), rs.getString("company_id"),
            rs.getString("employee_id"), rs.getString("employee_name"),
            LocalDate.parse(rs.getString("log_date")), rs.getInt("minutes_worked"),
            rs.getString("customer_id"), rs.getString("customer_name"),
            rs.getString("description"), rs.getBoolean("is_billable"),
            rs.getString("billing_unit"), rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price"),
            rs.getBigDecimal("billable_amount"), rs.getString("status"),
            rs.getString("billed_invoice_line_id"));

    /** Lista los trabajos del empleado actual (read-only, para la PWA). */
    public List<WorkLog> listMine(LocalDate from, LocalDate to) {
        AuthenticatedUser user = currentUserService.require();
        String companyId = tenantContext.getCurrentCompanyId();
        List<String> empIds = jdbcTemplate.queryForList(
                "SELECT id FROM employees WHERE user_id = ? AND company_id = ? AND active = TRUE",
                String.class, user.userId(), companyId);
        if (empIds.isEmpty()) return List.of();
        return jdbcTemplate.query(SELECT + """
                 WHERE w.employee_id = ? AND w.log_date BETWEEN ? AND ?
                 ORDER BY w.log_date DESC
                """, mapper, empIds.get(0), from, to);
    }

    /**
     * Lista los trabajos de la empresa con filtros (OWNER/ADMIN/ACCOUNTANT).
     * {@code customerId}/{@code status} opcionales; {@code billableUnbilledOnly}
     * = solo facturables y aún sin facturar (bandeja "pendientes de facturar").
     */
    public List<WorkLog> listForCompany(LocalDate from, LocalDate to,
                                        String customerId, String status,
                                        boolean billableUnbilledOnly) {
        StringBuilder sql = new StringBuilder(SELECT)
                .append(" WHERE w.company_id = ? AND w.log_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        args.add(from);
        args.add(to);
        if (customerId != null && !customerId.isBlank()) {
            sql.append(" AND w.customer_id = ?");
            args.add(customerId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND w.status = ?");
            args.add(status);
        }
        if (billableUnbilledOnly) {
            sql.append(" AND w.is_billable = TRUE AND w.billed_invoice_line_id IS NULL"
                    + " AND w.status <> 'BILLED'");
        }
        // Sin LIMIT (decision de Benjamin, 2026-08-24): con el tope de 500 los
        // botones "Todo lo pendiente" / "Todos los trabajos" de la UI mentirian
        // en cuanto hubiera mas de 500 trabajos en el rango consultado.
        sql.append(" ORDER BY w.log_date DESC, employee_name");
        return jdbcTemplate.query(sql.toString(), mapper, args.toArray());
    }

    @Transactional
    public WorkLog create(UpsertRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        BigDecimal amount = effectiveAmount(req);
        jdbcTemplate.update("""
                INSERT INTO work_logs (id, company_id, employee_id, log_date, work_date, minutes_worked,
                                       customer_id, description, is_billable, billing_unit,
                                       quantity, unit_price, billable_amount, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT')
                """,
                id, tenantContext.getCurrentCompanyId(), blank(req.employeeId()),
                req.logDate(), req.logDate(),
                req.minutesWorked() == null ? 0 : req.minutesWorked(),
                blank(req.customerId()), req.description(),
                req.isBillable() != null && req.isBillable(),
                blank(req.billingUnit()), req.quantity(), req.unitPrice(), amount);
        return findById(id);
    }

    @Transactional
    public WorkLog update(String id, UpsertRequest req) {
        validate(req);
        WorkLog cur = findById(id);
        if ("BILLED".equals(cur.status()) || cur.billedInvoiceLineId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede editar un trabajo ya facturado. Anula primero la factura.");
        }
        BigDecimal amount = effectiveAmount(req);
        jdbcTemplate.update("""
                UPDATE work_logs
                   SET employee_id = ?, log_date = ?, work_date = ?, minutes_worked = ?, customer_id = ?,
                       description = ?, is_billable = ?, billing_unit = ?, quantity = ?,
                       unit_price = ?, billable_amount = ?
                 WHERE id = ? AND company_id = ?
                """,
                blank(req.employeeId()), req.logDate(), req.logDate(),
                req.minutesWorked() == null ? 0 : req.minutesWorked(),
                blank(req.customerId()), req.description(),
                req.isBillable() != null && req.isBillable(),
                blank(req.billingUnit()), req.quantity(), req.unitPrice(), amount,
                id, tenantContext.getCurrentCompanyId());
        return findById(id);
    }

    @Transactional
    public void delete(String id) {
        WorkLog cur = findById(id);
        if ("BILLED".equals(cur.status()) || cur.billedInvoiceLineId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede borrar un trabajo ya facturado.");
        }
        jdbcTemplate.update("DELETE FROM work_logs WHERE id = ? AND company_id = ?",
                id, tenantContext.getCurrentCompanyId());
    }

    /** DRAFT ↔ SUBMITTED → APPROVED (admin). APPROVED graba quién y cuándo.
     *  No toca los facturados (BILLED). */
    @Transactional
    public WorkLog setStatus(String id, String status) {
        if (!"DRAFT".equals(status) && !"SUBMITTED".equals(status) && !"APPROVED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado inválido: " + status);
        }
        WorkLog cur = findById(id);
        if ("BILLED".equals(cur.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El trabajo ya está facturado.");
        }
        if ("APPROVED".equals(status)) {
            jdbcTemplate.update("""
                    UPDATE work_logs
                       SET status = 'APPROVED', approved_by_user_id = ?, approved_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND company_id = ?
                    """, currentUserService.require().userId(), id, tenantContext.getCurrentCompanyId());
        } else {
            jdbcTemplate.update("""
                    UPDATE work_logs
                       SET status = ?, approved_by_user_id = NULL, approved_at = NULL
                     WHERE id = ? AND company_id = ?
                    """, status, id, tenantContext.getCurrentCompanyId());
        }
        return findById(id);
    }

    // ---- PORTAL DEL EMPLEADO — el empleado crea/envía sus propios partes ----

    private String myEmployeeId() {
        AuthenticatedUser user = currentUserService.require();
        List<String> empIds = jdbcTemplate.queryForList(
                "SELECT id FROM employees WHERE user_id = ? AND company_id = ? AND active = TRUE",
                String.class, user.userId(), tenantContext.getCurrentCompanyId());
        if (empIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tu usuario no está vinculado a un empleado de esta empresa.");
        }
        return empIds.get(0);
    }

    /** El empleado crea un parte de trabajo (queda DRAFT hasta que lo envía). */
    @Transactional
    public WorkLog createMine(LocalDate logDate, Integer minutes, String description, String customerId) {
        String empId = myEmployeeId();
        LocalDate d = logDate == null ? LocalDate.now() : logDate;
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO work_logs (id, company_id, employee_id, log_date, work_date,
                                       minutes_worked, customer_id, description, is_billable, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE, 'DRAFT')
                """,
                id, tenantContext.getCurrentCompanyId(), empId, d, d,
                minutes == null ? 0 : minutes, blank(customerId), description);
        return findById(id);
    }

    /** El empleado ENVÍA su parte (DRAFT → SUBMITTED) para que el admin lo apruebe. */
    @Transactional
    public WorkLog submitMine(String id) {
        String empId = myEmployeeId();
        WorkLog cur = findById(id);
        if (!empId.equals(cur.employeeId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ese parte no es tuyo.");
        }
        if (!"DRAFT".equals(cur.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Solo se pueden enviar borradores.");
        }
        jdbcTemplate.update(
                "UPDATE work_logs SET status = 'SUBMITTED' WHERE id = ? AND company_id = ? AND employee_id = ?",
                id, tenantContext.getCurrentCompanyId(), empId);
        return findById(id);
    }

    /** El empleado borra un parte SUYO mientras no esté aprobado/facturado. */
    @Transactional
    public void deleteMine(String id) {
        String empId = myEmployeeId();
        WorkLog cur = findById(id);
        if (!empId.equals(cur.employeeId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ese parte no es tuyo.");
        }
        if ("APPROVED".equals(cur.status()) || "BILLED".equals(cur.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No puedes borrar un parte ya aprobado o facturado.");
        }
        jdbcTemplate.update(
                "DELETE FROM work_logs WHERE id = ? AND company_id = ? AND employee_id = ?",
                id, tenantContext.getCurrentCompanyId(), empId);
    }

    /**
     * TRB-3 — Factura los trabajos seleccionados (mismo cliente, facturables, sin
     * facturar). {@code merge}=true agrupa todo en UNA línea con {@code mergedConcept}
     * y la suma; si no, una línea por trabajo. Crea una factura BORRADOR del cliente
     * (IVA 21% por defecto, editable en el borrador) y marca los trabajos BILLED.
     * Devuelve el id de la factura creada.
     */
    @Transactional
    public String billSelected(List<String> ids, boolean merge, String mergedConcept) {
        if (ids == null || ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecciona al menos un trabajo");
        }
        List<WorkLog> works = new ArrayList<>();
        String customerId = null;
        for (String id : ids) {
            WorkLog w = findById(id);
            if (!w.billable() || "BILLED".equals(w.status()) || w.billedInvoiceLineId() != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Hay trabajos no facturables o ya facturados en la selección.");
            }
            if (w.customerId() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Un trabajo no tiene cliente.");
            }
            if (customerId == null) customerId = w.customerId();
            else if (!customerId.equals(w.customerId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Los trabajos deben ser del mismo cliente para facturarse juntos.");
            }
            works.add(w);
        }
        BigDecimal vat = new BigDecimal("21");
        List<com.benjagest.backend.billing.invoices.InvoiceLineInput> lines = new ArrayList<>();
        if (merge) {
            BigDecimal sum = works.stream()
                    .map(w -> w.billableAmount() == null ? BigDecimal.ZERO : w.billableAmount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            String concept = (mergedConcept == null || mergedConcept.isBlank()) ? "Trabajos" : mergedConcept.trim();
            lines.add(new com.benjagest.backend.billing.invoices.InvoiceLineInput(
                    concept, null, BigDecimal.ONE, sum, vat, BigDecimal.ZERO));
        } else {
            for (WorkLog w : works) {
                String d = (w.description() == null || w.description().isBlank()) ? "Trabajo" : w.description();
                BigDecimal q, p;
                // Preservar cantidad × precio (8 h × 25 €), no 1 × total.
                if (w.quantity() != null && w.unitPrice() != null && !"FIXED".equals(w.billingUnit())) {
                    q = w.quantity();
                    p = w.unitPrice();
                } else {
                    q = BigDecimal.ONE;
                    p = w.billableAmount() == null ? BigDecimal.ZERO : w.billableAmount();
                }
                lines.add(new com.benjagest.backend.billing.invoices.InvoiceLineInput(
                        d, null, q, p, vat, BigDecimal.ZERO));
            }
        }
        var req = new com.benjagest.backend.billing.invoices.InvoiceUpsertRequest(
                customerId, null, "NORMAL", LocalDate.now(), null, null, null, null, lines);
        var invoice = salesInvoiceService.createDraft(req);
        for (WorkLog w : works) {
            jdbcTemplate.update(
                    "UPDATE work_logs SET status = 'BILLED', invoice_id = ? WHERE id = ? AND company_id = ?",
                    invoice.id(), w.id(), tenantContext.getCurrentCompanyId());
        }
        return invoice.id();
    }

    /**
     * Marca trabajos como FACTURADOS enlazados a una factura ya creada (flujo del
     * editor de facturas: el usuario importa trabajos pendientes como líneas y, al
     * guardar/validar, se marcan). Best-effort: solo toca los que no estén ya BILLED.
     */
    @Transactional
    public void markBilled(List<String> ids, String invoiceId) {
        if (ids == null || ids.isEmpty() || invoiceId == null || invoiceId.isBlank()) return;
        for (String id : ids) {
            jdbcTemplate.update("""
                    UPDATE work_logs SET status = 'BILLED', invoice_id = ?
                     WHERE id = ? AND company_id = ? AND status <> 'BILLED'
                    """, invoiceId, id, tenantContext.getCurrentCompanyId());
        }
    }

    WorkLog findById(String id) {
        List<WorkLog> rows = jdbcTemplate.query(SELECT + " WHERE w.id = ? AND w.company_id = ?",
                mapper, id, tenantContext.getCurrentCompanyId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trabajo no encontrado");
        }
        return rows.get(0);
    }

    // ---- helpers ----

    private void validate(UpsertRequest req) {
        // employeeId es OPCIONAL: un trabajo sin empleado = el titular (autónomo
        // que trabaja solo). Solo se exige fecha (y cliente si es facturable).
        if (req.logDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "logDate obligatorio");
        }
        boolean billable = req.isBillable() != null && req.isBillable();
        if (billable && (req.customerId() == null || req.customerId().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Un trabajo facturable necesita un cliente.");
        }
    }

    /** Importe = precio cerrado (FIXED) o cantidad × precio (HOURS/DAYS/MONTHS). */
    private BigDecimal effectiveAmount(UpsertRequest req) {
        if (req.isBillable() == null || !req.isBillable()) return BigDecimal.ZERO;
        if ("FIXED".equals(req.billingUnit())) {
            return req.billableAmount() == null ? BigDecimal.ZERO : req.billableAmount();
        }
        if (req.quantity() != null && req.unitPrice() != null) {
            return req.quantity().multiply(req.unitPrice()).setScale(2, RoundingMode.HALF_UP);
        }
        return req.billableAmount() == null ? BigDecimal.ZERO : req.billableAmount();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ---- DTOs ----

    public record WorkLog(
            String id, String companyId, String employeeId, String employeeName,
            LocalDate logDate, int minutesWorked,
            String customerId, String customerName, String description,
            boolean billable, String billingUnit,
            BigDecimal quantity, BigDecimal unitPrice, BigDecimal billableAmount,
            String status, String billedInvoiceLineId
    ) {}

    public record UpsertRequest(
            String employeeId, LocalDate logDate, Integer minutesWorked,
            String customerId, String description, Boolean isBillable,
            String billingUnit, BigDecimal quantity, BigDecimal unitPrice,
            BigDecimal billableAmount
    ) {}
}
