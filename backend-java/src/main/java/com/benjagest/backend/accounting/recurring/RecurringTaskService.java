package com.benjagest.backend.accounting.recurring;

import com.benjagest.backend.accounting.AccountingTemplateService;
import com.benjagest.backend.accounting.LoanService;
import com.benjagest.backend.accounting.ManualJournalEntryService;
import com.benjagest.backend.accounting.TerceroAccountResolverService;
import com.benjagest.backend.accounting.TerceroAccountResolverService.TerceroType;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.billing.invoices.InvoiceLineInput;
import com.benjagest.backend.billing.invoices.InvoiceUpsertRequest;
import com.benjagest.backend.billing.invoices.SalesInvoice;
import com.benjagest.backend.billing.invoices.SalesInvoiceService;
import com.benjagest.backend.purchases.PurchaseInvoice;
import com.benjagest.backend.purchases.PurchaseInvoiceRepository;
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
    private final PurchaseInvoiceRepository purchaseRepository;
    private final ManualJournalEntryService manualEntries;
    private final AccountingTemplateService templates;
    private final LoanService loans;
    private final SalesInvoiceService salesService;
    /**
     * Slice 3S — Para los kinds ACCOUNTING_INCOME / ACCOUNTING_EXPENSE
     * (recurrentes contables puros sin pasar por SalesInvoiceService).
     * Resuelve la sub-cuenta del tercero (430.XXX o 410.XXX) o la crea.
     */
    private final TerceroAccountResolverService terceroResolver;
    /**
     * Slice 3K — TransactionTemplate REQUIRES_NEW para aislar cada
     * sub-runner (runJournalEntry, runPurchase, runSalesInvoice…) en
     * su propia transacción. Sin esto, una excepción dentro de un
     * sub-runner marcaba la transacción de runOne como rollback-only
     * y el {@link #finalize(...)} final lanzaba
     * UnexpectedRollbackException al intentar commit.
     */
    private final org.springframework.transaction.support.TransactionTemplate isolatedTx;

    public RecurringTaskService(JdbcTemplate jdbcTemplate, TenantContext tenantContext,
                                 CurrentUserService currentUserService,
                                 ObjectMapper objectMapper,
                                 PurchaseInvoiceService purchaseService,
                                 PurchaseInvoiceRepository purchaseRepository,
                                 ManualJournalEntryService manualEntries,
                                 AccountingTemplateService templates,
                                 LoanService loans,
                                 SalesInvoiceService salesService,
                                 TerceroAccountResolverService terceroResolver,
                                 org.springframework.transaction.PlatformTransactionManager txManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
        this.purchaseService = purchaseService;
        this.purchaseRepository = purchaseRepository;
        this.manualEntries = manualEntries;
        this.templates = templates;
        this.loans = loans;
        this.salesService = salesService;
        this.terceroResolver = terceroResolver;
        this.isolatedTx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.isolatedTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

    /**
     * Slice 3R — sub-select que detecta si el creador NO pertenece al
     * tenant de la tarea (creó la plantilla desde otra empresa
     * — típicamente la asesoría actuando como cliente). La relación
     * user↔company vive en {@code company_memberships}, no en una
     * columna {@code company_id} de {@code user_accounts}.
     */
    private static final String CREATED_BY_ADVISOR_EXPR = """
            CASE WHEN t.created_by_user_id IS NOT NULL
                      AND NOT EXISTS (
                              SELECT 1 FROM company_memberships m
                               WHERE m.user_id = t.created_by_user_id
                                 AND m.company_id = t.company_id)
                 THEN 1 ELSE 0 END AS created_by_advisor
            """;

    public RecurringTaskView get(String id) {
        List<RecurringTaskView> rows = jdbcTemplate.query("""
                SELECT t.*,
                """ + CREATED_BY_ADVISOR_EXPR + """
                  FROM recurring_tasks t
                 WHERE t.id = ? AND t.company_id = ?
                """, this::mapTask, id, tenantContext.getCurrentCompanyId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tarea recurrente no encontrada");
        return rows.get(0);
    }

    public List<RecurringTaskView> list(String kind, Boolean activeOnly) {
        StringBuilder sql = new StringBuilder(
                "SELECT t.*, " + CREATED_BY_ADVISOR_EXPR
              + " FROM recurring_tasks t WHERE t.company_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (kind != null && !kind.isBlank()) { sql.append(" AND t.kind = ?"); args.add(kind); }
        if (Boolean.TRUE.equals(activeOnly)) sql.append(" AND t.active = TRUE");
        sql.append(" ORDER BY t.active DESC, t.next_run_date ASC");
        return jdbcTemplate.query(sql.toString(), this::mapTask, args.toArray());
    }

    /**
     * Slice 3S-3 — TRUE si el {@code generatedId} (id de venta, compra
     * o asiento) aparece como resultado de algún run del cron. Lo usa
     * la UI para suprimir el prompt "¿hacer recurrente?" tras validar
     * una factura que el propio cron generó.
     */
    public boolean isGeneratedIdFromRecurring(String generatedId) {
        if (generatedId == null || generatedId.isBlank()) return false;
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM recurring_task_runs
                 WHERE generated_id = ? AND company_id = ?
                """, Integer.class, generatedId, tenantContext.getCurrentCompanyId());
        return count != null && count > 0;
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
    // Slice 3K — SIN @Transactional a propósito. Cada sub-runner se
    // ejecuta en su propia transacción (isolatedTx, REQUIRES_NEW); si
    // falla, su transacción se rollbackea sin contaminar a finalize.
    // Si dejamos esto @Transactional, la excepción del sub-runner
    // marca la transacción como rollback-only y al final del método
    // se lanza UnexpectedRollbackException.
    public RunView runOne(String taskId, LocalDate scheduledDate) {
        long t0 = System.currentTimeMillis();
        RecurringTaskView task = get(taskId);
        final String capturedCompanyId = tenantContext.getCurrentCompanyId();
        String generatedId = null;
        String generatedKind = null;
        String status;
        String message = null;
        try {
            switch (task.kind()) {
                case "PURCHASE" -> {
                    generatedId = isolatedTx.execute(s -> {
                        tenantContext.setCurrentCompanyId(capturedCompanyId);
                        return runPurchase(task, scheduledDate);
                    });
                    generatedKind = "PURCHASE_INVOICE";
                }
                case "JOURNAL_ENTRY" -> {
                    generatedId = isolatedTx.execute(s -> {
                        tenantContext.setCurrentCompanyId(capturedCompanyId);
                        return runJournalEntry(task, scheduledDate);
                    });
                    generatedKind = "JOURNAL_ENTRY";
                }
                case "TEMPLATE_APPLY" -> {
                    generatedId = isolatedTx.execute(s -> {
                        tenantContext.setCurrentCompanyId(capturedCompanyId);
                        return runTemplate(task, scheduledDate);
                    });
                    generatedKind = "JOURNAL_ENTRY";
                }
                case "LOAN_AUTO_PAY" -> {
                    generatedId = isolatedTx.execute(s -> {
                        tenantContext.setCurrentCompanyId(capturedCompanyId);
                        return runLoanAutoPay(task, scheduledDate);
                    });
                    generatedKind = "LOAN_INSTALLMENT";
                }
                case "SALES_INVOICE" -> {
                    generatedId = isolatedTx.execute(s -> {
                        tenantContext.setCurrentCompanyId(capturedCompanyId);
                        return runSalesInvoice(task, scheduledDate);
                    });
                    generatedKind = "SALES_INVOICE";
                }
                // Slice 3S — Recurrentes contables puros (sin factura
                // legal). Pensados para shadow companies donde la
                // asesoría lleva la contabilidad pero el cliente no
                // factura desde BENJAGEST. Generan asiento DRAFT con
                // toda la metadata fiscal (base/IVA/retención/tercero)
                // para que el 303/347/190 los cuadre correctamente.
                case "ACCOUNTING_INCOME" -> {
                    generatedId = isolatedTx.execute(s -> {
                        tenantContext.setCurrentCompanyId(capturedCompanyId);
                        return runAccountingIncome(task, scheduledDate);
                    });
                    generatedKind = "JOURNAL_ENTRY";
                }
                case "ACCOUNTING_EXPENSE" -> {
                    generatedId = isolatedTx.execute(s -> {
                        tenantContext.setCurrentCompanyId(capturedCompanyId);
                        return runAccountingExpense(task, scheduledDate);
                    });
                    generatedKind = "JOURNAL_ENTRY";
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
            // Desempaquetar TransactionSystemException o similares para
            // que el mensaje real llegue al frontend (3H-4).
            Throwable root = ex;
            while (root.getCause() != null && root != root.getCause()) {
                root = root.getCause();
            }
            message = root.getMessage() == null ? ex.getMessage() : root.getMessage();
        } finally {
            // Restaurar el tenant después del REQUIRES_NEW.
            tenantContext.setCurrentCompanyId(capturedCompanyId);
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
        // Concept: payload lleva "concept" opcional; si no, usa el nombre
        // de la recurrente como descripción genérica del gasto.
        String payloadConcept = (String) p.get("concept");
        String concept = payloadConcept != null && !payloadConcept.isBlank()
                ? payloadConcept
                : task.name();
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
                concept,
                "Generado por recurrencia '" + task.name() + "' — revisar y validar",
                null);
        // PurchaseInvoiceService.save crea POSTED por defecto. Para
        // recurrentes queremos DRAFT (el usuario revisa precio, añade
        // líneas y luego valida en multiselección).
        var result = purchaseService.save(req);
        String invoiceId = result.invoice().id();
        // Forzar status DRAFT y borrar asiento auto si lo creó.
        jdbcTemplate.update(
                "UPDATE purchase_invoices SET status = 'DRAFT' WHERE id = ?", invoiceId);
        // Si el save generó asiento automático, anularlo: el asiento se
        // creará cuando el usuario valide.
        String prevEntryId = jdbcTemplate.query(
                "SELECT journal_entry_id FROM purchase_invoices WHERE id = ?",
                (rs, n) -> rs.getString(1), invoiceId).stream().findFirst().orElse(null);
        if (prevEntryId != null) {
            jdbcTemplate.update(
                    "UPDATE journal_entries SET status = 'VOIDED' WHERE id = ?", prevEntryId);
            jdbcTemplate.update(
                    "UPDATE purchase_invoices SET journal_entry_id = NULL WHERE id = ?", invoiceId);
        }
        return invoiceId;
    }

    /**
     * Genera factura emitida (sales_invoices) como DRAFT a partir del
     * payload. El usuario revisa, puede añadir líneas o ajustar precios,
     * y la valida en multiselección desde el listado.
     *
     * <p>Payload esperado:
     * <pre>
     * {
     *   "customerId": "uuid-del-cliente",
     *   "concept": "Servicio mensual {YYYY}{MM}",
     *   "lines": [
     *     {"description":"Iguala mensual","quantity":1,"unitPrice":150.00,"vatPercent":21,"retentionPercent":0}
     *   ]
     * }
     * </pre>
     */
    private String runSalesInvoice(RecurringTaskView task, LocalDate scheduledDate) {
        Map<String, Object> p = readPayload(task);
        String customerId = (String) p.get("customerId");
        // Si no viene customerId pero viene NIF+nombre, buscar o crear
        // el cliente. Es el caso típico del recurrente "Iguala mensual a
        // Acme SL" donde el usuario solo proporciona el NIF y el nombre,
        // sin haber creado el customer antes.
        if (customerId == null || customerId.isBlank()) {
            String customerNif = (String) p.get("customerNif");
            String customerName = (String) p.get("customerLegalName");
            if (customerNif == null || customerNif.isBlank()) {
                throw new IllegalStateException(
                        "El recurrente SALES_INVOICE necesita customerId o customerNif+customerLegalName");
            }
            customerId = ensureCustomerExists(customerNif, customerName);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawLines = (List<Map<String, Object>>) p.get("lines");
        if (rawLines == null || rawLines.isEmpty()) {
            throw new IllegalStateException("El recurrente SALES_INVOICE necesita al menos una línea");
        }
        List<InvoiceLineInput> lines = new ArrayList<>();
        for (Map<String, Object> l : rawLines) {
            String desc = (String) l.get("description");
            if (desc != null) desc = expandPlaceholders(desc, scheduledDate);
            lines.add(new InvoiceLineInput(
                    desc,
                    (String) l.get("catalogItemId"),
                    bd(l.get("quantity")),
                    bd(l.get("unitPrice")),
                    bd(l.get("vatPercent")),
                    bd(l.get("retentionPercent"))));
        }
        String notes = "Generado por recurrencia '" + task.name() + "' — revisar y validar";
        // concept opcional en payload — si no, usar task.name como concepto
        // del ingreso (luego el asesor lo edita).
        String payloadConcept = (String) p.get("concept");
        String concept = payloadConcept != null && !payloadConcept.isBlank()
                ? payloadConcept : task.name();
        InvoiceUpsertRequest req = new InvoiceUpsertRequest(
                customerId,
                null, // seriesId se resuelve por invoiceType
                "NORMAL",
                scheduledDate,
                scheduledDate.plusDays(30),
                null, concept, notes,
                lines);
        SalesInvoice created = salesService.createDraft(req);
        return created.id();
    }

    private String runJournalEntry(RecurringTaskView task, LocalDate scheduledDate) {
        Map<String, Object> p = readPayload(task);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawLines = (List<Map<String, Object>>) p.get("lines");
        if (rawLines == null || rawLines.size() < 2) {
            throw new IllegalStateException("Payload sin líneas suficientes.");
        }
        List<ManualJournalEntryService.LineRequest> lines = new ArrayList<>();
        // Slice 3Y — Guard: detectar líneas con debe=0 Y haber=0 ANTES
        // de pasarlas al ManualJournalEntryService, que devolvería el
        // mensaje genérico "Línea X: la línea está vacía". El asesor
        // no sabe qué hacer con ese mensaje. Aquí explicamos que el
        // payload se guardó con importe 0 (típicamente porque el
        // JavaFX Spinner no committeó el texto antes del save — fix
        // 3M de la UI; las recurrentes creadas antes de ese fix pueden
        // tener este problema). La acción correcta: editar la
        // recurrente y rellenar el campo Importe.
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (Map<String, Object> l : rawLines) {
            BigDecimal d = bd(l.get("debit"));
            BigDecimal c = bd(l.get("credit"));
            if (d != null) totalDebit = totalDebit.add(d);
            if (c != null) totalCredit = totalCredit.add(c);
        }
        if (totalDebit.signum() == 0 && totalCredit.signum() == 0) {
            throw new IllegalStateException(
                    "La plantilla '" + task.name() + "' se guardó con importe 0. "
                  + "Edítala (doble click en el listado de recurrentes) y "
                  + "rellena el campo Importe antes de ejecutarla.");
        }
        for (Map<String, Object> l : rawLines) {
            // El payload puede traer accountId (UUID directo, formato
            // legacy) o accountCode (código PGC tipo "629", "572" —
            // Slice 3F). Si solo viene el código, lo resolvemos.
            String accountId = (String) l.get("accountId");
            if (accountId == null || accountId.isBlank()) {
                String accountCode = (String) l.get("accountCode");
                if (accountCode == null || accountCode.isBlank()) {
                    throw new IllegalStateException(
                            "Línea sin accountId ni accountCode — no se puede generar el asiento.");
                }
                accountId = resolveAccountIdByCode(accountCode);
            }
            lines.add(new ManualJournalEntryService.LineRequest(
                    accountId,
                    (String) l.get("description"),
                    bd(l.get("debit")), bd(l.get("credit"))));
        }
        boolean postNow = Boolean.TRUE.equals(p.get("postNow"));
        // Slice 3X — El editor "Pago periódico sin factura" siempre
        // genera gastos (cuota autónomo TGSS, modelos AEAT que se
        // ingresan, comisiones bancarias, cuotas de préstamo, etc.).
        // Source_type unificado a PURCHASE_INVOICE → en el Diario
        // aparece como "Gasto" y el concepto lleva "(recurrente)"
        // para que el asesor lo identifique.
        String concept = expandPlaceholders((String) p.get("concept"), scheduledDate);
        if (concept == null || concept.isBlank()) concept = task.name();
        String conceptWithTag = concept + " (recurrente)";
        ManualJournalEntryService.ManualEntryView v = manualEntries.createDraftAutoProposed(
                new ManualJournalEntryService.ManualEntryRequest(scheduledDate,
                        conceptWithTag, lines, postNow),
                "PURCHASE_INVOICE",
                task.id());
        return v.id();
    }

    /**
     * Slice 3S — Ingreso recurrente puro (sin factura).
     * Asiento de venta tipo:
     * <pre>
     *   DEBE  430.tercero   (base + iva - retencion)
     *   HABER counterAccount (base)                — típicamente 705
     *   HABER 477 IVA repercutido (cuotaIva)       — solo si vat > 0
     *   DEBE  4751 HP retenedora (cuotaRet)        — solo si ret > 0
     * </pre>
     * Resuelve la 430.XXX del tercero por NIF usando el resolver.
     * Si no hay NIF (cliente anónimo) usa la 430 padre.
     */
    private String runAccountingIncome(RecurringTaskView task, LocalDate scheduledDate) {
        Map<String, Object> p = readPayload(task);
        String partyNif = (String) p.get("partyNif");
        String partyName = (String) p.get("partyName");
        if (partyName == null || partyName.isBlank()) {
            throw new IllegalStateException("Falta partyName en el payload del ingreso recurrente.");
        }
        BigDecimal base = bd(p.get("baseAmount"));
        BigDecimal vatPct = bd(p.get("vatPercent"));
        BigDecimal retPct = bd(p.get("retentionPercent"));
        if (base == null || base.signum() <= 0) {
            throw new IllegalStateException("baseAmount obligatorio y > 0.");
        }
        if (vatPct == null) vatPct = BigDecimal.ZERO;
        if (retPct == null) retPct = BigDecimal.ZERO;
        BigDecimal cuotaIva = base.multiply(vatPct).movePointLeft(2)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal cuotaRet = base.multiply(retPct).movePointLeft(2)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal total = base.add(cuotaIva).subtract(cuotaRet);

        String counterCode = blank((String) p.get("counterAccountCode"));
        if (counterCode == null) counterCode = "705"; // prestación de servicios
        String counterId = resolveAccountIdByCode(counterCode);
        String tercero = resolveTerceroAccountId(TerceroType.CLIENTE, partyNif, partyName, "430");
        String concept = expandPlaceholders((String) p.get("concept"), scheduledDate);
        if (concept == null || concept.isBlank()) concept = task.name();
        List<ManualJournalEntryService.LineRequest> lines = new ArrayList<>();
        // 1) Cliente al debe por el total a cobrar
        lines.add(new ManualJournalEntryService.LineRequest(
                tercero, partyName + " — " + concept, total, BigDecimal.ZERO));
        // 2) Ingreso al haber
        lines.add(new ManualJournalEntryService.LineRequest(
                counterId, concept, BigDecimal.ZERO, base));
        // 3) IVA repercutido al haber (si hay)
        if (cuotaIva.signum() > 0) {
            String ivaId = resolveAccountIdByCode("477");
            lines.add(new ManualJournalEntryService.LineRequest(
                    ivaId, "IVA repercutido " + vatPct.toPlainString() + "%",
                    BigDecimal.ZERO, cuotaIva));
        }
        // 4) Retención al debe (si hay) — el cliente nos retiene
        if (cuotaRet.signum() > 0) {
            String retId = resolveAccountIdByCode("4751");
            lines.add(new ManualJournalEntryService.LineRequest(
                    retId, "Retención IRPF " + retPct.toPlainString() + "%",
                    cuotaRet, BigDecimal.ZERO));
        }
        boolean postNow = Boolean.TRUE.equals(p.get("postNow"));
        // Slice 3X — En vez de un source_type propio "RECURRING_ACCOUNTING"
        // (que duplicaba estados en el filtro Origen del Diario),
        // unificamos al source_type "SALES_INVOICE" y marcamos la
        // recurrencia con sufijo en el concepto: el asesor ve "Venta"
        // como origen y "(recurrente)" en el concepto sabe la fuente.
        // La trazabilidad exacta queda en recurring_task_runs.
        String conceptWithTag = concept + " (recurrente)";
        ManualJournalEntryService.ManualEntryView v = manualEntries.createDraftAutoProposed(
                new ManualJournalEntryService.ManualEntryRequest(scheduledDate, conceptWithTag, lines, postNow),
                "SALES_INVOICE",
                task.id());
        return v.id();
    }

    /**
     * Slice 3S — Gasto recurrente puro (sin factura).
     * Asiento de compra tipo:
     * <pre>
     *   DEBE  counterAccount (base)                — típicamente 629/621/623
     *   DEBE  472 IVA soportado (cuotaIva)         — solo si vat > 0
     *   HABER 4751 HP retenedora (cuotaRet)        — solo si ret > 0 (al pagar a un autónomo)
     *   HABER 410.tercero    (base + iva - retencion)
     * </pre>
     */
    private String runAccountingExpense(RecurringTaskView task, LocalDate scheduledDate) {
        Map<String, Object> p = readPayload(task);
        String partyNif = (String) p.get("partyNif");
        String partyName = (String) p.get("partyName");
        if (partyName == null || partyName.isBlank()) {
            throw new IllegalStateException("Falta partyName en el payload del gasto recurrente.");
        }
        BigDecimal base = bd(p.get("baseAmount"));
        BigDecimal vatPct = bd(p.get("vatPercent"));
        BigDecimal retPct = bd(p.get("retentionPercent"));
        if (base == null || base.signum() <= 0) {
            throw new IllegalStateException("baseAmount obligatorio y > 0.");
        }
        if (vatPct == null) vatPct = BigDecimal.ZERO;
        if (retPct == null) retPct = BigDecimal.ZERO;
        BigDecimal cuotaIva = base.multiply(vatPct).movePointLeft(2)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal cuotaRet = base.multiply(retPct).movePointLeft(2)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal total = base.add(cuotaIva).subtract(cuotaRet);

        String counterCode = blank((String) p.get("counterAccountCode"));
        if (counterCode == null) counterCode = "629"; // otros gastos
        String counterId = resolveAccountIdByCode(counterCode);
        String tercero = resolveTerceroAccountId(TerceroType.PROVEEDOR, partyNif, partyName, "410");
        String concept = expandPlaceholders((String) p.get("concept"), scheduledDate);
        if (concept == null || concept.isBlank()) concept = task.name();
        List<ManualJournalEntryService.LineRequest> lines = new ArrayList<>();
        // 1) Gasto al debe
        lines.add(new ManualJournalEntryService.LineRequest(
                counterId, concept, base, BigDecimal.ZERO));
        // 2) IVA soportado al debe (si hay)
        if (cuotaIva.signum() > 0) {
            String ivaId = resolveAccountIdByCode("472");
            lines.add(new ManualJournalEntryService.LineRequest(
                    ivaId, "IVA soportado " + vatPct.toPlainString() + "%",
                    cuotaIva, BigDecimal.ZERO));
        }
        // 3) Retención al haber (si hay) — nosotros retenemos al proveedor (autónomo)
        if (cuotaRet.signum() > 0) {
            String retId = resolveAccountIdByCode("4751");
            lines.add(new ManualJournalEntryService.LineRequest(
                    retId, "Retención IRPF practicada " + retPct.toPlainString() + "%",
                    BigDecimal.ZERO, cuotaRet));
        }
        // 4) Proveedor al haber por el neto a pagar
        lines.add(new ManualJournalEntryService.LineRequest(
                tercero, partyName + " — " + concept, BigDecimal.ZERO, total));
        boolean postNow = Boolean.TRUE.equals(p.get("postNow"));
        // Slice 3X — source_type "PURCHASE_INVOICE" en vez del
        // antiguo "RECURRING_ACCOUNTING". Concept con sufijo
        // "(recurrente)" para que el asesor sepa que viene del cron.
        String conceptWithTag = concept + " (recurrente)";
        ManualJournalEntryService.ManualEntryView v = manualEntries.createDraftAutoProposed(
                new ManualJournalEntryService.ManualEntryRequest(scheduledDate, conceptWithTag, lines, postNow),
                "PURCHASE_INVOICE",
                task.id());

        // Slice 3W — Crear también un purchase_invoice sintético
        // vinculado al asiento. Así el gasto recurrente aparece tanto
        // en el Diario como en el listado de Compras y Gastos, igual
        // que las facturas importadas. El asesor no necesita saber el
        // origen para verlas centralizadas. invoice_number generado
        // con sufijo del task.id para idempotencia y trazabilidad.
        try {
            String pinvId = UUID.randomUUID().toString();
            String invoiceNumber = "REC-" + task.id().substring(0, 8).toUpperCase()
                    + "-" + scheduledDate.toString();
            PurchaseInvoice pinv = new PurchaseInvoice(
                    pinvId, tenantContext.getCurrentCompanyId(),
                    blank(partyNif), partyName,
                    invoiceNumber,
                    scheduledDate,
                    base, vatPct, cuotaIva, total,
                    null, // sin SHA — no viene de PDF
                    0,
                    PurchaseInvoice.STATUS_POSTED,
                    v.id(), // vincular al asiento
                    null, // sin cuenta de gasto fija (cascada automática)
                    concept,
                    "Generado por recurrencia '" + task.name() + "'",
                    safeUserId(),
                    tenantContext.getCurrentCompanyId(),
                    null, null);
            purchaseRepository.insert(pinv);
        } catch (Exception ignored) {
            // Si la inserción del shadow purchase_invoice falla (p.ej.
            // duplicado), no rompemos el asiento contable. El asiento
            // ya está creado y es la fuente de verdad fiscal.
        }
        return v.id();
    }

    /**
     * Resuelve la sub-cuenta del tercero (430.XXX o 410.XXX). Si no hay
     * NIF identificado o el resolver no devuelve cuenta, cae al código
     * padre (430 o 410) como red de seguridad.
     */
    private String resolveTerceroAccountId(TerceroType type, String nif,
                                            String name, String fallbackCode) {
        try {
            TerceroAccountResolverService.ResolvedAccount r =
                    terceroResolver.getOrCreate(type, nif, name);
            if (r != null && r.accountId() != null) return r.accountId();
        } catch (Exception ignored) { /* cae al fallback */ }
        return resolveAccountIdByCode(fallbackCode);
    }

    /**
     * Busca la cuenta del PGC del tenant actual cuyo código coincida
     * exactamente con {@code code}. Devuelve el UUID. Falla si no se
     * encuentra (mensaje claro para que el asesor sepa que tiene que
     * sembrar la cuenta o ajustar el código en la plantilla).
     */
    private String resolveAccountIdByCode(String code) {
        String companyId = tenantContext.getCurrentCompanyId();
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND code = ?
                 LIMIT 1
                """, (rs, n) -> rs.getString(1), companyId, code.trim());
        if (ids.isEmpty()) {
            throw new IllegalStateException(
                    "No existe cuenta con código '" + code + "' en el PGC de la empresa.");
        }
        return ids.get(0);
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

    /**
     * Devuelve el id del customer asociado al NIF dado en la empresa
     * actual; si no existe, lo crea con el nombre legal proporcionado.
     * El UK (tax_identifier) ya estaba en la tabla por V11 (merge billing
     * profile), así que el INSERT IGNORE es seguro.
     */
    private String ensureCustomerExists(String customerNif, String customerLegalName) {
        String nif = customerNif.trim().toUpperCase();
        String name = (customerLegalName == null || customerLegalName.isBlank())
                ? nif : customerLegalName.trim();
        List<String> existing = jdbcTemplate.query("""
                SELECT id FROM customers
                 WHERE company_id = ? AND tax_identifier = ?
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"),
                tenantContext.getCurrentCompanyId(), nif);
        if (!existing.isEmpty()) return existing.get(0);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO customers (
                    id, company_id, legal_name, tax_identifier,
                    customer_type, active
                ) VALUES (?, ?, ?, ?, 'COMPANY', TRUE)
                """, id, tenantContext.getCurrentCompanyId(), name, nif);
        return id;
    }

    private String expandPlaceholders(String s, LocalDate date) {
        if (s == null) return null;
        // Slice 3I — Más placeholders predictivos para el campo
        // Concepto: nombre del mes en español (minúsculas y
        // capitalizado), año largo, día del mes sin cero.
        String[] meses = {"enero", "febrero", "marzo", "abril", "mayo",
                "junio", "julio", "agosto", "septiembre", "octubre",
                "noviembre", "diciembre"};
        String mes = meses[date.getMonthValue() - 1];
        String mesCap = Character.toUpperCase(mes.charAt(0)) + mes.substring(1);
        return s.replace("{YYYY}", String.format("%04d", date.getYear()))
                .replace("{YY}", String.format("%02d", date.getYear() % 100))
                .replace("{AÑO}", String.format("%04d", date.getYear()))
                .replace("{MM}", String.format("%02d", date.getMonthValue()))
                .replace("{M}", String.valueOf(date.getMonthValue()))
                .replace("{MES}", mes)
                .replace("{MES_MAY}", mesCap)
                .replace("{DD}", String.format("%02d", date.getDayOfMonth()))
                .replace("{D}", String.valueOf(date.getDayOfMonth()))
                .replace("{Q}", "Q" + ((date.getMonthValue() - 1) / 3 + 1))
                .replace("{T}", "T" + ((date.getMonthValue() - 1) / 3 + 1));
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
        // Slice 3R — flag pre-calculado por la query con un EXISTS
        // sobre company_memberships. TRUE cuando el creador NO tiene
        // membership en este tenant (caso típico: asesoría actuando
        // como cliente). findDueGlobally no incluye la columna; en
        // ese path el catch deja el flag a FALSE (no se usa allí).
        String taskCompany = rs.getString("company_id");
        boolean createdByAdvisor = false;
        try { createdByAdvisor = rs.getInt("created_by_advisor") == 1; }
        catch (SQLException ignored) {}
        return new RecurringTaskView(
                rs.getString("id"), taskCompany,
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
                createdByAdvisor,
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
            boolean createdByAdvisor,
            Instant createdAt, Instant updatedAt
    ) {}

    public record RunView(
            String id, String recurringTaskId, Instant runAt, LocalDate scheduledDate,
            String status, String generatedId, String generatedKind,
            String message, int durationMs
    ) {}

    public record DueTask(String id, String companyId, String kind, LocalDate nextRunDate) {}
}
