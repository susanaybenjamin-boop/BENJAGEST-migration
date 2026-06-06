package com.benjagest.backend.accounting.recurring;

import com.benjagest.backend.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Para cada tarea due, ponemos el {@link TenantContext} de su empresa
 * y llamamos a {@link RecurringTaskService#runOne}. Si una falla, lo
 * dejamos registrado en {@code recurring_task_runs} con status ERROR y
 * seguimos con la siguiente — no bloqueamos las demás empresas.
 *
 * <p>Hay también un endpoint {@code /api/accounting/recurring/run-now}
 * que permite al asesor disparar el ciclo manualmente para hoy (útil
 * cuando crea una tarea nueva y quiere ejecutarla sin esperar al
 * próximo cron).
 */
@Component
public class RecurringTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecurringTaskScheduler.class);

    private final RecurringTaskService service;
    private final TenantContext tenantContext;

    public RecurringTaskScheduler(RecurringTaskService service, TenantContext tenantContext) {
        this.service = service;
        this.tenantContext = tenantContext;
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
            try {
                tenantContext.setCurrentCompanyId(t.companyId());
                RecurringTaskService.RunView v = service.runOne(t.id(), t.nextRunDate());
                switch (v.status()) {
                    case "OK" -> ok++;
                    case "ERROR" -> {
                        err++;
                        log.warn("[recurring] {} ({}) → ERROR: {}", t.id(), t.kind(), v.message());
                    }
                    default -> skipped++;
                }
            } catch (Exception ex) {
                err++;
                log.warn("[recurring] {} ({}) → uncaught: {}", t.id(), t.kind(), ex.getMessage());
            } finally {
                // Limpia el ThreadLocal entre tareas para no arrastrar
                // company_id al siguiente iter.
                tenantContext.setCurrentCompanyId(null);
            }
        }
        if (!due.isEmpty()) {
            log.info("[recurring] {} tareas para {} → {} OK, {} ERROR, {} SKIPPED",
                    due.size(), date, ok, err, skipped);
        }
        return new RunSummary(date, due.size(), ok, err, skipped);
    }

    public record RunSummary(LocalDate date, int total, int ok, int errors, int skipped) {}
}
