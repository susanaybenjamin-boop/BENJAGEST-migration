package com.benjagest.backend.supervision;

import com.benjagest.backend.advisory.notifications.AdvisoryNotificationService;
import com.benjagest.backend.notifications.BusinessNotificationService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * SUPERV — vigila las tareas automáticas y avisa cuando dejan de funcionar.
 *
 * <p><b>Por qué existe.</b> El 18-oct-2026 el barrido del BOE dejó de
 * funcionar y no se supo hasta el 20-nov: 34 días. El error se escribía con
 * {@code log.warn} en un fichero que nadie abre y el servicio seguía. De las 7
 * tareas automáticas del backend, solo los recurrentes avisaban al fallar.
 *
 * <p><b>La idea clave</b>, y lo que diferencia esto de "loguear mejor": el caso
 * del BOE <b>no se habría cazado vigilando errores</b>, porque desde fuera no
 * había ningún error visible — había <b>ausencia de resultados</b>. Por eso se
 * guarda el histórico y se vigilan dos cosas distintas:
 * <ul>
 *   <li><b>Fallo</b>: la tarea corrió y reventó. Aviso en la <b>transición</b>
 *       a fallo, no cada día (si no, se vuelven ruido y se ignoran).</li>
 *   <li><b>Ausencia</b>: la tarea lleva N días sin terminar bien, falle o no.
 *       Esto es lo que habría cazado el BOE el día 2.</li>
 * </ul>
 *
 * <p>Y avisa también de la <b>recuperación</b>: si no, el usuario se queda con
 * un aviso de fallo en la bandeja sin saber si sigue roto.
 *
 * <p>Todo es defensivo: <b>un fallo supervisando NUNCA puede tumbar la tarea
 * supervisada</b>. Sería el colmo que el vigilante rompiera lo que vigila.
 */
@Service
public class JobSupervisor {

    private static final Logger log = LoggerFactory.getLogger(JobSupervisor.class);

    /** Código que la campana usa para agrupar. */
    public static final String NOTIF_TYPE = "SYSTEM_JOB";

    public static final String OK = "OK";
    public static final String ERROR = "ERROR";
    public static final String SKIPPED = "SKIPPED";
    /** Estado de aviso: no es un resultado de ejecución, es "avisé por ausencia". */
    private static final String STALE = "STALE";

    private final JdbcTemplate jdbc;
    private final AdvisoryNotificationService advisoryNotifications;
    private final BusinessNotificationService businessNotifications;

    public JobSupervisor(JdbcTemplate jdbc,
                         AdvisoryNotificationService advisoryNotifications,
                         BusinessNotificationService businessNotifications) {
        this.jdbc = jdbc;
        this.advisoryNotifications = advisoryNotifications;
        this.businessNotifications = businessNotifications;
    }

    // ====================================================================
    //  Envolver una tarea
    // ====================================================================

    /**
     * Ejecuta la tarea registrando el resultado. Si revienta, lo registra y
     * <b>vuelve a lanzar</b> la excepción: la supervisión observa, no cambia el
     * comportamiento de quien la usa.
     */
    public <T> T call(SupervisedJob job, Callable<T> body) throws Exception {
        long t0 = System.currentTimeMillis();
        try {
            T result = body.call();
            record(job, null, OK, null, System.currentTimeMillis() - t0);
            return result;
        } catch (Exception ex) {
            record(job, null, ERROR, describe(ex), System.currentTimeMillis() - t0);
            throw ex;
        }
    }

    /** Variante para tareas sin resultado. */
    public void run(SupervisedJob job, ThrowingRunnable body) {
        long t0 = System.currentTimeMillis();
        try {
            body.run();
            record(job, null, OK, null, System.currentTimeMillis() - t0);
        } catch (Exception ex) {
            record(job, null, ERROR, describe(ex), System.currentTimeMillis() - t0);
            // No se relanza: las tareas @Scheduled ya capturaban su excepción
            // antes de existir esto, y propagar cambiaria su comportamiento.
            log.warn("[superv] {} fallo", job.jobName(), ex);
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ====================================================================
    //  Registrar un resultado
    // ====================================================================

    /**
     * Apunta una ejecución y, si el estado ha cambiado respecto a la anterior,
     * avisa. Nunca propaga: lo llama código que ya ha hecho su trabajo.
     */
    public void record(SupervisedJob job, String companyId, String status,
                       String message, long durationMs) {
        try {
            String previous = lastStatus(job);
            jdbc.update("""
                    INSERT INTO job_runs
                           (id, job_name, company_id, started_at, finished_at,
                            status, message, duration_ms)
                    VALUES (?, ?, ?, NOW(), NOW(), ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), job.jobName(), companyId,
                    status, truncate(message, 2000), (int) durationMs);

            if (ERROR.equals(status)) {
                // Solo en la TRANSICION a fallo. Si ya estaba fallando, el
                // usuario ya tiene su aviso: repetirlo cada dia lo convierte en
                // ruido y deja de leerlos.
                if (!ERROR.equals(previous)) {
                    alert(job, ERROR,
                            "Tarea con problemas: " + job.label(),
                            "La tarea automatica \"" + job.label() + "\" no se pudo completar.\n"
                            + "Motivo: " + (message == null ? "(sin mensaje)" : message) + "\n"
                            + "Se reintentara en su proxima ejecucion. "
                            + "Puedes ver el detalle en Configuracion -> Estado del sistema.");
                }
            } else if (OK.equals(status)) {
                // Recuperacion: solo si se habia avisado de algo.
                String alerted = lastAlertStatus(job);
                if (alerted != null) {
                    alert(job, null,
                            "Resuelto: " + job.label(),
                            "La tarea automatica \"" + job.label() + "\" ha vuelto a funcionar con normalidad.");
                }
            }
        } catch (RuntimeException ex) {
            // Un fallo supervisando no puede tumbar la tarea supervisada.
            log.warn("[superv] no se pudo registrar la ejecucion de {}", job.jobName(), ex);
        }
    }

    public void recordOk(SupervisedJob job, String message) {
        record(job, null, OK, message, 0);
    }

    public void recordError(SupervisedJob job, String message) {
        record(job, null, ERROR, message, 0);
    }

    public void recordSkipped(SupervisedJob job, String message) {
        record(job, null, SKIPPED, message, 0);
    }

    // ====================================================================
    //  Vigilancia por AUSENCIA — lo que habria cazado el caso del BOE
    // ====================================================================

    /**
     * Para cada tarea que vigile la ausencia, mira cuándo fue su último OK. Si
     * hace más días de los que admite, avisa (una sola vez).
     *
     * <p>Una tarea que <b>nunca</b> ha corrido no dispara el aviso: en una
     * instalación recién hecha todas estarían "ausentes" y el usuario se
     * encontraría la bandeja llena el primer día. Solo se vigila lo que alguna
     * vez funcionó y ha dejado de hacerlo.
     */
    public void checkAbsences() {
        for (SupervisedJob job : SupervisedJob.values()) {
            if (!job.watchesAbsence()) continue;
            try {
                LocalDateTime lastOk = lastSuccessAt(job);
                if (lastOk == null) continue;
                long days = java.time.Duration.between(lastOk, LocalDateTime.now()).toDays();
                if (days < job.maxDaysWithoutSuccess()) continue;
                if (STALE.equals(lastAlertStatus(job))) continue;
                alert(job, STALE,
                        "Sin funcionar: " + job.label(),
                        "La tarea automatica \"" + job.label() + "\" lleva " + days
                        + " dias sin completarse correctamente.\n"
                        + "La ultima vez que funciono fue el " + lastOk.toLocalDate() + ".\n"
                        + "Revisalo en Configuracion -> Estado del sistema.");
            } catch (RuntimeException ex) {
                log.warn("[superv] fallo comprobando la ausencia de {}", job.jobName(), ex);
            }
        }
    }

    // ====================================================================
    //  Consultas
    // ====================================================================

    /** Última foto de cada tarea, para la pantalla "Estado del sistema". */
    public List<JobStatus> statuses() {
        return java.util.Arrays.stream(SupervisedJob.values())
                .map(job -> {
                    LocalDateTime lastRun = lastRunAt(job);
                    LocalDateTime lastOk = lastSuccessAt(job);
                    return new JobStatus(
                            job.jobName(), job.label(),
                            lastRun == null ? null : lastRun.toString(),
                            lastStatus(job),
                            lastOk == null ? null : lastOk.toString(),
                            lastMessage(job),
                            job.maxDaysWithoutSuccess());
                })
                .toList();
    }

    public record JobStatus(String jobName, String label, String lastRunAt,
                            String lastStatus, String lastSuccessAt,
                            String lastMessage, int maxDaysWithoutSuccess) {}

    private String lastStatus(SupervisedJob job) {
        return one("SELECT status FROM job_runs WHERE job_name = ? ORDER BY seq DESC LIMIT 1",
                String.class, job.jobName());
    }

    private String lastMessage(SupervisedJob job) {
        return one("SELECT message FROM job_runs WHERE job_name = ? ORDER BY seq DESC LIMIT 1",
                String.class, job.jobName());
    }

    private LocalDateTime lastRunAt(SupervisedJob job) {
        return one("SELECT started_at FROM job_runs WHERE job_name = ? ORDER BY seq DESC LIMIT 1",
                LocalDateTime.class, job.jobName());
    }

    private LocalDateTime lastSuccessAt(SupervisedJob job) {
        return one("SELECT started_at FROM job_runs WHERE job_name = ? AND status = 'OK'"
                + " ORDER BY seq DESC LIMIT 1",
                LocalDateTime.class, job.jobName());
    }

    private String lastAlertStatus(SupervisedJob job) {
        return one("SELECT last_alert_status FROM job_supervision WHERE job_name = ?",
                String.class, job.jobName());
    }

    private <T> T one(String sql, Class<T> type, Object... args) {
        List<T> rows = jdbc.queryForList(sql, type, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ====================================================================
    //  Aviso
    // ====================================================================

    /**
     * Manda el aviso a la bandeja de cada empresa raíz y recuerda de qué se
     * avisó. {@code alertStatus} null = recuperación (se limpia la marca).
     *
     * <p>La bandeja depende del tipo de empresa, igual que hace el aviso de
     * recurrentes: asesoría a {@code advisory_notifications}, empresa a
     * {@code business_notifications}.
     */
    private void alert(SupervisedJob job, String alertStatus, String title, String body) {
        for (String companyId : rootCompanies()) {
            try {
                if (isAdvisory(companyId)) {
                    advisoryNotifications.emit(new AdvisoryNotificationService.EmitRequest(
                            companyId, null, NOTIF_TYPE,
                            AdvisoryNotificationService.SEVERITY_WARNING,
                            title, body, "job:" + job.jobName()));
                } else {
                    businessNotifications.emit(new BusinessNotificationService.EmitRequest(
                            companyId, null, NOTIF_TYPE,
                            BusinessNotificationService.SEVERITY_WARNING,
                            title, body, "job:" + job.jobName()));
                }
            } catch (RuntimeException ex) {
                log.warn("[superv] no se pudo avisar a {} de {}", companyId, job.jobName(), ex);
            }
        }
        jdbc.update("""
                INSERT INTO job_supervision (job_name, last_alert_status, last_alert_at)
                VALUES (?, ?, NOW())
                ON DUPLICATE KEY UPDATE
                    last_alert_status = VALUES(last_alert_status),
                    last_alert_at = VALUES(last_alert_at)
                """, job.jobName(), alertStatus);
    }

    /** Empresas "de primer nivel": las que tienen a alguien delante mirando la campana. */
    private List<String> rootCompanies() {
        return jdbc.queryForList(
                "SELECT id FROM companies WHERE parent_company_id IS NULL", String.class);
    }

    private boolean isAdvisory(String companyId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM companies
                 WHERE id = ? AND company_type IN ('INTERNAL', 'ADVISORY')
                """, Integer.class, companyId);
        return n != null && n > 0;
    }

    // ====================================================================
    //  Utilidades
    // ====================================================================

    /**
     * Mensaje legible de una excepción. Se incluye el tipo porque muchas
     * excepciones de red llegan con {@code getMessage()} vacío y un aviso que
     * dijese "Motivo: null" no ayudaria a nadie.
     */
    private static String describe(Throwable ex) {
        if (ex == null) return null;
        String msg = ex.getMessage();
        String type = ex.getClass().getSimpleName();
        if (msg == null || msg.isBlank()) return type;
        return type + ": " + msg;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1);
    }

    /** Por si hace falta sellar la hora desde fuera. */
    public static Instant now() {
        return Instant.now();
    }
}
