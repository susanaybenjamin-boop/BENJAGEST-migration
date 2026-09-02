package com.benjagest.backend.accounting.recurring;

import com.benjagest.backend.advisory.notifications.AdvisoryNotificationService;
import com.benjagest.backend.notifications.BusinessNotificationService;
import com.benjagest.backend.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler que ejecuta las tareas recurrentes pendientes.
 *
 * <p>Cron: cada día a las 06:10 (a esa hora no hay tráfico humano, las
 * facturas auto-generadas quedan listas antes de que el asesor empiece
 * la jornada). Configurable via {@code benjagest.recurring.cron} si
 * llegara a ser necesario.
 *
 * <p>Para cada tarea due ponemos DOS contextos antes de llamar a
 * {@link RecurringTaskService#runOne}:
 * <ul>
 *   <li>el {@link TenantContext} de su empresa, y</li>
 *   <li>el {@code SecurityContext} del usuario que creó la tarea
 *       ({@link RecurringRunIdentity}). Sin él, todo lo que llame a
 *       {@code CurrentUserService.require()} — {@code PurchaseInvoiceService.save()}
 *       entre otros — moría con {@code 401 "No hay sesion activa"} y la
 *       ejecución automática de los gastos recurrentes nunca llegó a
 *       funcionar (REC-CRON-AUTH, 2026-09-02).</li>
 * </ul>
 * Si una falla, lo dejamos registrado en {@code recurring_task_runs} con
 * status ERROR y seguimos con la siguiente — no bloqueamos las demás
 * empresas.
 *
 * <p>Un fallo ya no es silencioso: se emite una notificación a la bandeja
 * de la empresa (la campana del header) para que se vea el mismo día, y la
 * tarea se reintenta al día siguiente en vez de saltarse el periodo.
 *
 * <p>Hay también un endpoint {@code /api/accounting/recurring/run-now}
 * que permite al asesor disparar el ciclo manualmente para hoy (útil
 * cuando crea una tarea nueva y quiere ejecutarla sin esperar al
 * próximo cron).
 */
@Component
public class RecurringTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecurringTaskScheduler.class);

    /** Tipo de notificación de las bandejas — la UI agrupa por este código. */
    private static final String NOTIF_TYPE = "RECURRING_TASK_FAILED";

    private final RecurringTaskService service;
    private final TenantContext tenantContext;
    private final RecurringRunIdentity identity;
    private final AdvisoryNotificationService advisoryNotifications;
    private final BusinessNotificationService businessNotifications;
    private final JdbcTemplate jdbcTemplate;

    public RecurringTaskScheduler(RecurringTaskService service, TenantContext tenantContext,
                                  RecurringRunIdentity identity,
                                  AdvisoryNotificationService advisoryNotifications,
                                  BusinessNotificationService businessNotifications,
                                  JdbcTemplate jdbcTemplate) {
        this.service = service;
        this.tenantContext = tenantContext;
        this.identity = identity;
        this.advisoryNotifications = advisoryNotifications;
        this.businessNotifications = businessNotifications;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "${benjagest.recurring.cron:0 10 6 * * *}")
    public void runDailyTasks() {
        runForDate(LocalDate.now());
    }

    /** Ejecuta las tareas due para una fecha. Devuelve resumen ok/error/skipped. */
    public RunSummary runForDate(LocalDate date) {
        List<RecurringTaskService.DueTask> due = service.findDueGlobally(date);
        int ok = 0, err = 0, skipped = 0;
        for (RecurringTaskService.DueTask t : due) {
            // El handle restaura la autenticación anterior al cerrarse: este
            // método también se invoca desde POST /run-all, sobre el hilo de
            // una petición HTTP que ya trae su propio usuario en el contexto.
            try (RecurringRunIdentity.Handle restoreAuth =
                         identity.install(t.createdByUserId(), t.companyId())) {
                tenantContext.setCurrentCompanyId(t.companyId());
                RecurringTaskService.RunView v = service.runOne(t.id(), t.nextRunDate());
                switch (v.status()) {
                    case "OK" -> ok++;
                    case "ERROR" -> {
                        err++;
                        log.warn("[recurring] {} ({}) -> ERROR: {}", t.id(), t.kind(), v.message());
                        notifyFailure(t, v.message());
                    }
                    default -> skipped++;
                }
            } catch (Exception ex) {
                err++;
                log.warn("[recurring] {} ({}) -> uncaught: {}", t.id(), t.kind(), ex.getMessage());
                notifyFailure(t, ex.getMessage());
            } finally {
                // Limpia el ThreadLocal entre tareas para no arrastrar
                // company_id al siguiente iter.
                tenantContext.setCurrentCompanyId(null);
            }
        }
        if (!due.isEmpty()) {
            log.info("[recurring] {} tareas para {} -> {} OK, {} ERROR, {} SKIPPED",
                    due.size(), date, ok, err, skipped);
        }
        return new RunSummary(date, due.size(), ok, err, skipped);
    }

    /**
     * Avisa en la bandeja de la empresa de que una recurrente ha fallado.
     *
     * <p>Solo en la TRANSICIÓN a fallo: si la tarea ya venía en ERROR, el
     * aviso anterior sigue en la bandeja sin leer y ahora los ERROR se
     * reintentan a diario — sin este filtro sería un aviso nuevo cada día.
     *
     * <p>La bandeja depende del tipo de empresa, igual que el criterio que usa
     * la campana del header: asesoría a {@code advisory_notifications},
     * empresa a {@code business_notifications}. Nunca propaga: un fallo
     * notificando no puede tumbar el ciclo de las demás tareas.
     */
    private void notifyFailure(RecurringTaskService.DueTask task, String message) {
        if ("ERROR".equals(task.lastRunStatus())) return;
        String title = "Recurrente fallida: " + task.name();
        String body = "La tarea recurrente \"" + task.name() + "\" no se pudo ejecutar el "
                + task.nextRunDate() + ".\n"
                + "Motivo: " + (message == null ? "(sin mensaje)" : message) + "\n"
                + "Se reintentará automáticamente cada día hasta que se resuelva.";
        try {
            if (isAdvisoryCompany(task.companyId())) {
                advisoryNotifications.emit(new AdvisoryNotificationService.EmitRequest(
                        task.companyId(), null, NOTIF_TYPE,
                        AdvisoryNotificationService.SEVERITY_WARNING,
                        title, body, "recurring:" + task.id()));
            } else {
                businessNotifications.emit(new BusinessNotificationService.EmitRequest(
                        task.companyId(), null, NOTIF_TYPE,
                        BusinessNotificationService.SEVERITY_WARNING,
                        title, body, "recurring:" + task.id()));
            }
        } catch (RuntimeException ex) {
            log.warn("[recurring] no se pudo notificar el fallo de {}: {}",
                    task.id(), ex.getMessage());
        }
    }

    private boolean isAdvisoryCompany(String companyId) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM companies
                 WHERE id = ? AND company_type IN ('INTERNAL', 'ADVISORY')
                """, Integer.class, companyId);
        return n != null && n > 0;
    }

    public record RunSummary(LocalDate date, int total, int ok, int errors, int skipped) {}
}
