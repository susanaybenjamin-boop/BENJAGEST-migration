package com.benjagest.backend.supervision;

import com.benjagest.backend.auth.RequiresRole;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.stereotype.Component;

/**
 * SUPERV — API de "Estado del sistema".
 *
 * <pre>
 *   GET  /api/supervision/jobs      estado de cada tarea vigilada
 *   POST /api/supervision/report    la UI reporta una tarea que ejecuta ELLA
 * </pre>
 *
 * <p>El {@code report} existe por la comprobación de actualizaciones: no la
 * hace el backend, la hace la UI al arrancar contra GitHub. Sin este endpoint
 * esa tarea no podría salir en la misma pantalla que las demás, y el usuario
 * tendría que mirar en dos sitios para saber si su sistema está sano.
 */
@RestController
@RequestMapping("/api/supervision")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class SupervisionController {

    private final JobSupervisor supervisor;

    public SupervisionController(JobSupervisor supervisor) {
        this.supervisor = supervisor;
    }

    @GetMapping("/jobs")
    public List<JobSupervisor.JobStatus> jobs() {
        return supervisor.statuses();
    }

    /**
     * Resultado de una tarea que corre fuera del backend.
     *
     * <p>Solo se aceptan nombres del enum {@link SupervisedJob}: un cliente no
     * puede inventarse tareas ni ensuciar el histórico con nombres libres.
     */
    public record ReportRequest(String jobName, String status, String message) {}

    @PostMapping("/report")
    public void report(@RequestBody ReportRequest body) {
        SupervisedJob job = SupervisedJob.byName(body.jobName()).orElse(null);
        if (job == null) return;
        String status = body.status();
        if (!JobSupervisor.OK.equals(status)
                && !JobSupervisor.ERROR.equals(status)
                && !JobSupervisor.SKIPPED.equals(status)) {
            return;
        }
        supervisor.record(job, null, status, body.message(), 0);
    }

    /**
     * SUPERV — el vigilante de AUSENCIAS. Diario a las 07:00, una hora después
     * de las tareas de las 06:00, para que les dé tiempo a terminar y no las
     * declare ausentes mientras están corriendo.
     *
     * <p>Va en una clase aparte del controller REST porque {@code @Scheduled}
     * en un {@code @RestController} funciona pero despista: esto no es un
     * endpoint.
     */
    @Component
    public static class AbsenceWatcher {

        private final JobSupervisor supervisor;

        public AbsenceWatcher(JobSupervisor supervisor) {
            this.supervisor = supervisor;
        }

        @Scheduled(cron = "0 0 7 * * *")
        public void daily() {
            supervisor.checkAbsences();
        }
    }
}
