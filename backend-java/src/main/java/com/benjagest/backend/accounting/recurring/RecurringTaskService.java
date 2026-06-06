package com.benjagest.backend.accounting.recurring;

import com.benjagest.backend.accounting.AccountingTemplateService;
import com.benjagest.backend.accounting.LoanService;
import com.benjagest.backend.accounting.ManualJournalEntryService;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.purchases.PurchaseInvoiceService;
import com.benjagest.backend.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Motor de tareas recurrentes contables.
 *
 * <p>Soporta:
 * <ul>
 *   <li>{@code PURCHASE} — genera factura recibida (gasto recurrente).
 *       Payload: supplierNif, supplierName, invoiceNumber (puede llevar
 *       {YYYY}{MM} para autogenerar), baseAmount, vatPercent, totalAmount.</li>
 *   <li>{@code SALES_INVOICE} — genera borrador de factura emitida.</li>
 *   <li>{@code JOURNAL_ENTRY} — crea asiento manual con líneas del payload.</li>
 *   <li>{@code TEMPLATE_APPLY} — aplica plantilla con variables del payload.</li>
 *   <li>{@code LOAN_AUTO_PAY} — marca como pagada la siguiente cuota
 *       vencida del préstamo.</li>
 * </ul>
 *
 * <p>El cálculo de {@code next_run_date} depende de {@code frequency}:
 * DAILY +1d, WEEKLY +7d, MONTHLY +1m (mismo day_of_month, con clamp
 * al último día), QUARTERLY +3m, YEARLY +12m, CUSTOM_MONTHS +N meses.
 */
@Service
public class RecurringTaskService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;
    private final PurchaseInvoiceService purchaseService;
    private final ManualJournalEntryService manualEntries;
    private final AccountingTemplateService templates;
    private final LoanService loans;

    public RecurringTaskService(JdbcTemplate jdbcTemplate, TenantContext tenantContext,
                                 CurrentUserService currentUserService,
                                 ObjectMapper objectMapper,
                                 PurchaseInvoiceService purchaseService,
                                 ManualJournalEntryService manualEntries,
                                 AccountingTemplateService templates,
                                 LoanService loans) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
        this.purchaseService = purchaseService;
        this.manualEntries = manualEntries;
        this.templates = templates;
        this.loans = loans;
    }

    // ====================================================================
    //  CRUD
    // ====================================================================

    @Transactional
    public RecurringTaskView create(RecurringTaskRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        String userId = safeUserId();
        LocalDate firstRun = req.firstRunDate() == null
                ? defaultNextDate(req.frequency(), LocalDate.now(),
                        req.dayOfMonth(), req.dayOfWeek())
                : req.firstRunDate();
        String payload = serializePayload(req.payload());
        jdbcTemplate.update("""
                INSERT INTO recurring_tasks (
                    id, company_id, kind, name, description,
                    frequency, day_of_month, day_of_week, months_between,
                    next_run_date, end_date, payload_json,
                    template_id, loan_id, active, created_by_user_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                """,
                id, tenantContext.getCurrentCompanyId(),
                req.kind(), req.name().trim(), blank(req.description()),
                req.frequency(), req.dayOfMonth(), req.dayOfWeek(),
                req.monthsBetween() == null ? 1 : req.monthsBetween(),
                java.sql.Date.valueOf(firstRun),
                req.endDate() == null ? null : java.sql.Date.valueOf(req.endDate()),
                payload, blank(req.templateId()), blank(req.loanId()),
                userId);
        return get(id);
    }

    @Transactional
    public RecurringTaskView update(String id, RecurringTaskRequest req) {
        validate(req);
        String payload = serializePayload(req.payload());
        jdbcTemplate.update("""
                UPDATE recurring_tasks
                   SET kind = ?, name = ?, description = ?,
                       frequency = ?, day_of_month = ?, day_of_week = ?,
                       months_between = ?,
                       next_run_date = COALESCE(?, next_run_date),
                       end_date = ?, payload_json = ?,
                       template_id = ?, loan_id = ?
                 WHERE id = ? AND company_id = ?
                """,
                req.kind(), req.name().trim(), blank(req.description()),
                req.frequency(), req.dayOfMonth(), req.dayOfWeek(),
                req.monthsBetween() == null ? 1 : req.monthsBetween(),
                req.firstRunDate() == null ? null : java.sql.Date.valueOf(req.firstRunDate()),
                req.endDate() == null ? null : java.sql.Date.valueOf(req.endDate()),
                payload, blank(req.templateId()), blank(req.loanId()),
                id, tenantContext.getCurrentCompanyId());
        return get(id);
    }

    @Transactional
    public void setActive(String id, boolean active) {
        jdbcTemplate.update("""
                UPDATE recurring_tasks SET active = ?
                 WHERE id = ? AND company_id = ?
                """, active, id, tenantContext.getCurrentCompanyId());
    }

    @Transactional
    public void delete(String id) {
        jdbcTemplate.update("""
                DELETE FROM recurring_tasks WHERE id = ? AND company_id = ?
                """, id, tenantContext.getCurrentCompanyId());
    }

    public RecurringTaskView get(String id) {
        List<RecurringTaskView> rows = jdbcTemplate.query("""
                SELECT * FROM recurring_tasks WHERE id = ? AND company_id = ?
                """, this::mapTask, id, tenantContext.getCurrentCompanyId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tarea recurrente no encontrada");
        return rows.get(0);
    }

    public List<RecurringTaskView> list(String kind, Boolean activeOnly) {
        StringBuilder sql = new StringBuilder("SELECT * FROM recurring_tasks WHERE company_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (kind != null && !kind.isBlank()) { sql.append(" AND kind = ?"); args.add(kind); }
        if (Boolean.TRUE.equals(activeOnly)) sql.append(" AND active = TRUE");
        sql.append(" ORDER BY active DESC, next_run_date ASC");
        return jdbcTemplate.query(sql.toString(), this::mapTask, args.toArray());
    }

    public List<RunView> listRuns(String taskId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, recurring_task_id, run_at, scheduled_date, status,
                       generated_id, generated_kind, message, duration_ms
                  FROM recurring_task_runs
                 WHERE recurring_task_id = ? AND company_id = ?
                 ORDER BY run_at DESC
                 LIMIT ?
                """,
                (rs, n) -> new RunView(
                        rs.getString("id"), rs.getString("recurring_task_id"),
                        rs.getTimestamp("run_at").toInstant(),
                        rs.getDate("scheduled_date").toLocalDate(),
                        rs.getString("status"),
                        rs.getString("generated_id"), rs.getString("generated_kind"),
                        rs.getString("message"), rs.getInt("duration_ms")),
                taskId, tenantContext.getCurrentCompanyId(),
                limit <= 0 ? 50 : limit);
    }

    // ====================================================================
    //  Ejecución masiva — llamada por el scheduler
    // ====================================================================

    /**
     * Devuelve los ids de tareas pendientes hoy, sin contexto tenant.
     * El scheduler las ejecuta una por una en transacciones aisladas para
     * que el fallo de una no rompa al resto.
     */
    public List<DueTask> findDueGlobally(LocalDate today) {
        return jdbcTemplate.query("""
                SELECT id, company_id, kind, next_run_date
                  FROM recurring_tasks
                 WHERE active = TRUE
                   AND next_run_date <= ?
                   AND (end_date IS NULL OR end_date >= next_run_date)
                 ORDER BY next_run_date ASC, company_id
                 LIMIT 500
                """,
                (rs, n) -> new DueTask(
                        rs.getString("id"), rs.getString("company_id"),
                        rs.getString("kind"),
                        rs.getDate("next_run_date").toLocalDate()),
                java.sql.Date.valueOf(today));
    }

    /**
     * Ejecuta UNA tarea recurrente en su tenant. El scheduler ya se ha
     * encargado de poner el TenantContext antes de llamar a este método.
     */
    @Transactional
    public RunView runOne(String taskId, LocalDate scheduledDate) {
        long t0 = System.currentTimeMillis();
        RecurringTaskView task = get(taskId);
        String generatedId = null;
        String generatedKind = null;
        String status;
        String message = null;
        try {
            switch (task.kind()) {
                case "PURCHASE" -> {
                    generatedId = runPurchase(task, scheduledDate);
                    generatedKind = "PURCHASE_INVOICE";
                }
                case "JOURNAL_ENTRY" -> {
                    generatedId = runJournalEntry(task, scheduledDate);
                    generatedKind = "JOURNAL_ENTRY";
                }
                case "TEMPLATE_APPLY" -> {
                    generatedId = runTemplate(task, scheduledDate);
                    generatedKind = "JOURNAL_ENTRY";
                }
                case "LOAN_AUTO_PAY" -> {
                    generatedId = runLoanAutoPay(task, scheduledDate);
                    generatedKind = "LOAN_INSTALLMENT";
                }
                case "SALES_INVOICE" -> {
                    // Sub-slice: la generación de factura emitida requiere
                    // el editor de facturas. Por ahora marca SKIPPED con
                    // explicación y deja el payload listo para el slice
                    // SALES-RECURRING dedicado.
                    status = "SKIPPED";
                    message = "SALES_INVOICE recurrente no automatizable todavía — pendiente SALES-RECURRING.";
                    return finalize(task, scheduledDate, status, null, null, message, t0);
                }
                default -> {
                    status = "SKIPPED";
                    message = "kind no implementado: " + task.kind();
                    return finalize(task, scheduledDate, status, null, null, message, t0);
                }
            }
            status = generatedId == null ? "SKIPPED" : "OK";
        } catch (Exception ex) {
            status = "ERROR";
            message = ex.getMessage();
        }
        return finalize(task, scheduledDate, status, generatedId, generatedKind, message, t0);
    }

    private RunView finalize(RecurringTaskView task, LocalDate scheduledDate,
                               String status, String generatedId, String generatedKind,
                               String message, long t0) {
        long duration = System.currentTimeMillis() - t0;
        String runId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO recurring_task_runs (
                    id, recurring_task_id, company_id, run_at, scheduled_date,
                    status, generated_id, generated_kind, message, duration_ms
                ) VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?)
                """,
                runId, task.id(), task.companyId(),
                java.sql.Date.valueOf(scheduledDate),
                status, generatedId, generatedKind, message, (int) duration);

        // Avanzar next_run_date y contadores.
        LocalDate nextRun = computeNextRun(task, scheduledDate);
        jdbcTemplate.update("""
                UPDATE recurring_tasks
                   SET last_run_date = ?, last_run_status = ?, last_run_message = ?,
                       last_generated_id = ?,
                       times_run = times_run + ?,
                       times_failed = times_failed + ?,
                       next_run_date = ?,
                       active = CASE WHEN end_date IS NOT NULL AND ? > end_date
                                     THEN FALSE ELSE active END
                 WHERE id = ?
                """,
                java.sql.Date.valueOf(scheduledDate), status,
                message != null && message.length() > 500
                        ? message.substring(0, 500) : message,
                generatedId,
                "OK".equals(status) ? 1 : 0,
                "ERROR".equals(status) ? 1 : 0,
                java.sql.Date.valueOf(nextRun),
                java.sql.Date.valueOf(nextRun),
                task.id());
        return new RunView(runId, task.id(), Instant.now(), scheduledDate,
                status, generatedId, generatedKind, message, (int) duration);
    }

    // ====================================================================
    //  Ejecutores por kind
    // ====================================================================

    private String runPurchase(RecurringTaskView task, LocalDate scheduledDate) {
        Map<String, Object> p = readPayload(task);
        String invoiceNumber = expandPlaceholders((String) p.get("invoiceNumber"), scheduledDate);
        PurchaseInvoiceService.SaveRequest req = new PurchaseInvoiceService.SaveRequest(
                (String) p.get("supplierNif"),
                (String) p.get("supplierName"),
                invoiceNumber,
                scheduledDate,
                bd(p.get("baseAmount")),
                bd(p.get("vatPercent")),
                bd(p.get("vatAmount")),
                bd(p.get("totalAmount")),
                null, 0,
                "Generado por recurrencia '" + task.name() + "'");
        var result = purchaseService.save(req);
        return result.invoice().id();
    }

    private String runJournalEntry(RecurringTaskView task, LocalDate scheduledDate) {
        Map<String, Object> p = readPayload(task);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawLines = (List<Map<String, Object>>) p.get("lines");
        if (rawLines == null || rawLines.size() < 2) {
            throw new IllegalStateException("Payload sin líneas suficientes.");
        }
        List<ManualJournalEntryService.LineRequest> lines = new ArrayList<>();
        for (Map<String, Object> l : rawLines) {
            lines.add(new ManualJournalEntryService.LineRequest(
                    (String) l.get("accountId"),
                    (String) l.get("description"),
                    bd(l.get("debit")), bd(l.get("credit"))));
        }
        boolean postNow = Boolean.TRUE.equals(p.get("postNow"));
        ManualJournalEntryService.ManualEntryView v = manualEntries.createDraft(
                new ManualJournalEntryService.ManualEntryRequest(scheduledDate,
                        expandPlaceholders((String) p.get("concept"), scheduledDate),
                        lines, postNow));
        return v.id();
    }

    private String runTemplate(RecurringTaskView task, LocalDate scheduledDate) {
        if (task.templateId() == null) throw new IllegalStateException("template_id no configurado");
        Map<String, Object> p = readPayload(task);
        Map<String, BigDecimal> vars = new java.util.HashMap<>();
        Object varsRaw = p.get("variables");
        if (varsRaw instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) varsRaw;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                vars.put(e.getKey(), bd(e.getValue()));
            }
        }
        boolean postNow = Boolean.TRUE.equals(p.get("postNow"));
        String concept = expandPlaceholders((String) p.get("concept"), scheduledDate);
        ManualJournalEntryService.ManualEntryView v = templates.apply(task.templateId(),
                new AccountingTemplateService.ApplyRequest(scheduledDate, concept, vars, postNow));
        return v.id();
    }

    private String runLoanAutoPay(RecurringTaskView task, LocalDate scheduledDate) {
        if (task.loanId() == null) throw new IllegalStateException("loan_id no configurado");
        // Buscar la cuota PENDING más antigua con due_date <= scheduledDate.
        List<String> pending = jdbcTemplate.query("""
                SELECT id FROM loan_installments
                 WHERE loan_id = ? AND company_id = ? AND status = 'PENDING'
                   AND due_date <= ?
                 ORDER BY installment_number ASC LIMIT 1
                """, (rs, n) -> rs.getString("id"),
                task.loanId(), task.companyId(),
                java.sql.Date.valueOf(scheduledDate));
        if (pending.isEmpty()) return null;
        LoanService.InstallmentView v = loans.payInstallment(pending.get(0),
                new LoanService.PaymentRequest(scheduledDate));
        return v.id();
    }

    // ====================================================================
    //  Cálculo de next_run_date
    // ====================================================================

    private LocalDate computeNextRun(RecurringTaskView task, LocalDate executed) {
        LocalDate base = executed;
        return switch (task.frequency()) {
            case "DAILY" -> base.plusDays(1);
            case "WEEKLY" -> base.plusWeeks(1);
            case "MONTHLY" -> adjustToDom(base.plusMonths(1), task.dayOfMonth());
            case "QUARTERLY" -> adjustToDom(base.plusMonths(3), task.dayOfMonth());
            case "YEARLY" -> adjustToDom(base.plusYears(1), task.dayOfMonth());
            case "CUSTOM_MONTHS" -> adjustToDom(base.plusMonths(task.monthsBetween()), task.dayOfMonth());
            default -> base.plusMonths(1);
        };
    }

    private LocalDate defaultNextDate(String frequency, LocalDate today,
                                        Integer dayOfMonth, Integer dayOfWeek) {
        if ("WEEKLY".equals(frequency) && dayOfWeek != null) {
            int diff = (dayOfWeek - today.getDayOfWeek().getValue() + 7) % 7;
            return diff == 0 ? today.plusDays(7) : today.plusDays(diff);
        }
        if (dayOfMonth != null) {
            LocalDate candidate = adjustToDom(today, dayOfMonth);
            return candidate.isAfter(today) ? candidate : adjustToDom(today.plusMonths(1), dayOfMonth);
        }
        return today.plusDays(1);
    }

    private LocalDate adjustToDom(LocalDate d, Integer dayOfMonth) {
        if (dayOfMonth == null) return d;
        int last = d.with(TemporalAdjusters.lastDayOfMonth()).getDayOfMonth();
        int dom = Math.min(dayOfMonth, last);
        return d.withDayOfMonth(dom);
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private String expandPlaceholders(String s, LocalDate date) {
        if (s == null) return null;
        return s.replace("{YYYY}", String.format("%04d", date.getYear()))
                .replace("{MM}", String.format("%02d", date.getMonthValue()))
                .replace("{DD}", String.format("%02d", date.getDayOfMonth()))
                .replace("{Q}", "Q" + ((date.getMonthValue() - 1) / 3 + 1));
    }

    private Map<String, Object> readPayload(RecurringTaskView task) {
        try {
            if (task.payloadJson() == null || task.payloadJson().isBlank()) return Map.of();
            return objectMapper.readValue(task.payloadJson(), new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("Payload JSON inválido: " + ex.getMessage());
        }
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo serializar payload");
        }
    }

    private BigDecimal bd(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(v.toString());
    }

    private void validate(RecurringTaskRequest req) {
        if (req.kind() == null) throw bad("kind obligatorio");
        if (req.name() == null || req.name().isBlank()) throw bad("name obligatorio");
        if (req.frequency() == null) throw bad("frequency obligatoria");
        if (req.dayOfMonth() != null && (req.dayOfMonth() < 1 || req.dayOfMonth() > 31))
            throw bad("dayOfMonth fuera de rango");
        if (req.dayOfWeek() != null && (req.dayOfWeek() < 1 || req.dayOfWeek() > 7))
            throw bad("dayOfWeek fuera de rango (1-7)");
    }

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    private RecurringTaskView mapTask(ResultSet rs, int n) throws SQLException {
        java.sql.Date lrd = rs.getDate("last_run_date");
        java.sql.Date ed = rs.getDate("end_date");
        java.sql.Timestamp ca = rs.getTimestamp("created_at");
        java.sql.Timestamp ua = rs.getTimestamp("updated_at");
        return new RecurringTaskView(
                rs.getString("id"), rs.getString("company_id"),
                rs.getString("kind"), rs.getString("name"), rs.getString("description"),
                rs.getString("frequency"),
                (Integer) rs.getObject("day_of_month"),
                (Integer) rs.getObject("day_of_week"),
                rs.getInt("months_between"),
                rs.getDate("next_run_date").toLocalDate(),
                lrd == null ? null : lrd.toLocalDate(),
                rs.getString("last_run_status"),
                rs.getString("last_run_message"),
                rs.getString("last_generated_id"),
                rs.getInt("times_run"), rs.getInt("times_failed"),
                ed == null ? null : ed.toLocalDate(),
                rs.getString("payload_json"),
                rs.getString("template_id"), rs.getString("loan_id"),
                rs.getBoolean("active"),
                rs.getString("created_by_user_id"),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant());
    }

    private static String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // ====================================================================
    //  DTOs
    // ====================================================================

    public record RecurringTaskRequest(
            String kind, String name, String description,
            String frequency, Integer dayOfMonth, Integer dayOfWeek, Integer monthsBetween,
            LocalDate firstRunDate, LocalDate endDate,
            Map<String, Object> payload,
            String templateId, String loanId
    ) {}

    public record RecurringTaskView(
            String id, String companyId, String kind, String name, String description,
            String frequency, Integer dayOfMonth, Integer dayOfWeek, int monthsBetween,
            LocalDate nextRunDate, LocalDate lastRunDate,
            String lastRunStatus, String lastRunMessage,
            String lastGeneratedId,
            int timesRun, int timesFailed,
            LocalDate endDate, String payloadJson,
            String templateId, String loanId,
            boolean active, String createdByUserId,
            Instant createdAt, Instant updatedAt
    ) {}

    public record RunView(
            String id, String recurringTaskId, Instant runAt, LocalDate scheduledDate,
            String status, String generatedId, String generatedKind,
            String message, int durationMs
    ) {}

    public record DueTask(String id, String companyId, String kind, LocalDate nextRunDate) {}
}
