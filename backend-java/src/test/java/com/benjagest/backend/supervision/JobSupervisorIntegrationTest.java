package com.benjagest.backend.supervision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.benjagest.backend.advisory.notifications.AdvisoryNotificationService;
import com.benjagest.backend.notifications.BusinessNotificationService;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * SUPERV — Verificación EN EJECUCIÓN (CLAUDE.md §10.ter) del vigilante de
 * tareas automáticas.
 *
 * <p>Nace del caso del BOE: el barrido diario estuvo <b>34 días</b> sin
 * funcionar (18-oct a 20-nov de 2026) y nadie se enteró, porque el error iba a
 * un {@code log.warn} en un fichero que no mira nadie.
 *
 * <p>Lo que estos tests fijan, y que es el diseño entero:
 * <ul>
 *   <li>Se avisa en la <b>transición</b> a fallo, no cada día — si no, se
 *       vuelven ruido y el usuario deja de leerlos.</li>
 *   <li>Se avisa por <b>ausencia</b> ("lleva N días sin funcionar"). <b>Esto es
 *       lo único que habría cazado el caso del BOE</b>, porque allí no hubo un
 *       error visible: hubo falta de resultados que nadie medía.</li>
 *   <li>{@code SKIPPED} <b>no</b> cuenta como éxito, o la vigilancia por
 *       ausencia se quedaría ciega.</li>
 * </ul>
 */
class JobSupervisorIntegrationTest {

    private static final String ASESORIA = "5a000000-0000-0000-0000-00000000a001";
    private static final String EMPRESA = "5a000000-0000-0000-0000-00000000a002";

    private static DB db;
    private static JdbcTemplate jdbc;
    private static JobSupervisor supervisor;

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        db = DB.newEmbeddedDB(cfg.build());
        db.start();
        db.createDB("benjagestsuperv");

        DriverManagerDataSource ds =
                new DriverManagerDataSource(cfg.getURL("benjagestsuperv"), "root", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);

        jdbc.update("INSERT INTO companies (id, legal_name, company_type) VALUES (?, ?, 'ADVISORY')",
                ASESORIA, "Asesoria Superv SL");
        jdbc.update("INSERT INTO companies (id, legal_name, company_type) VALUES (?, ?, 'CLIENT')",
                EMPRESA, "Empresa Superv SL");

        supervisor = new JobSupervisor(jdbc,
                new AdvisoryNotificationService(jdbc, tenant(ASESORIA)),
                new BusinessNotificationService(jdbc, tenant(EMPRESA)));
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) db.stop();
    }

    @BeforeEach
    void limpiar() {
        jdbc.update("DELETE FROM job_runs");
        jdbc.update("DELETE FROM job_supervision");
        jdbc.update("DELETE FROM advisory_notifications");
        jdbc.update("DELETE FROM business_notifications");
    }

    // ====================================================================
    //  Registro
    // ====================================================================

    @Test
    void unaEjecucionCorrectaSeApuntaYNoMolestaANadie() {
        supervisor.recordOk(SupervisedJob.BOE_ALERTS, "243 articulos, 2 avisos");

        assertEquals(1, runs(SupervisedJob.BOE_ALERTS));
        assertEquals(0, avisos(), "una tarea que va bien no tiene que avisar de nada");
    }

    @Test
    void elPrimerFalloAvisaAlAsesorYAlaEmpresa() {
        supervisor.recordOk(SupervisedJob.BOE_ALERTS, null);
        supervisor.recordError(SupervisedJob.BOE_ALERTS, "PKIX path building failed");

        assertEquals(1, avisosAsesoria(), "la asesoria tiene que enterarse");
        assertEquals(1, avisosEmpresa(), "y la empresa tambien");
        String titulo = jdbc.queryForObject(
                "SELECT title FROM advisory_notifications ORDER BY created_at DESC LIMIT 1",
                String.class);
        assertTrue(titulo.contains("Alertas BOE"), "fue: " + titulo);
    }

    /** La regla que hace esto usable: un aviso, no uno cada día. */
    @Test
    void seguirFallandoNoVuelveAAvisar() {
        supervisor.recordOk(SupervisedJob.BOE_ALERTS, null);
        supervisor.recordError(SupervisedJob.BOE_ALERTS, "dia 1");
        int tras1 = avisos();

        supervisor.recordError(SupervisedJob.BOE_ALERTS, "dia 2");
        supervisor.recordError(SupervisedJob.BOE_ALERTS, "dia 3");
        supervisor.recordError(SupervisedJob.BOE_ALERTS, "dia 4");

        assertEquals(tras1, avisos(),
                "34 dias fallando no pueden ser 34 avisos: se dejarian de leer");
        // 1 OK + 4 ERROR: el historico las guarda TODAS aunque solo avise de la primera.
        assertEquals(5, runs(SupervisedJob.BOE_ALERTS), "pero SI se apuntan todas");
    }

    @Test
    void cuandoSeArreglaTambienSeDice() {
        supervisor.recordOk(SupervisedJob.BOE_ALERTS, null);
        supervisor.recordError(SupervisedJob.BOE_ALERTS, "roto");
        int trasFallo = avisos();

        supervisor.recordOk(SupervisedJob.BOE_ALERTS, "281 articulos");

        assertTrue(avisos() > trasFallo,
                "sin esto el usuario se queda con el aviso de fallo sin saber si sigue roto");
        // Se comprueba que EXISTE, no que sea el ultimo: `created_at` tiene
        // precision de segundo y en un test los dos avisos caen en el mismo,
        // asi que "el mas reciente" es ambiguo. En la app van dias aparte.
        Integer resueltos = jdbc.queryForObject(
                "SELECT COUNT(*) FROM advisory_notifications WHERE title LIKE 'Resuelto%'",
                Integer.class);
        assertEquals(1, resueltos, "falta el aviso de que ya funciona");
    }

    @Test
    void siNuncaFalloNoSeAnunciaUnaRecuperacionQueNoHubo() {
        supervisor.recordOk(SupervisedJob.BOE_ALERTS, null);
        supervisor.recordOk(SupervisedJob.BOE_ALERTS, null);
        assertEquals(0, avisos());
    }

    // ====================================================================
    //  Ausencia — lo que habria cazado el caso del BOE
    // ====================================================================

    @Test
    void avisaCuandoLlevaDemasiadosDiasSinFuncionar() {
        // El escenario REAL: la tarea no "falla", simplemente dejo de dar
        // resultados. Su ultima ejecucion buena fue hace 5 dias.
        insertRunDaysAgo(SupervisedJob.BOE_ALERTS, "OK", 5);

        supervisor.checkAbsences();

        assertEquals(1, avisosAsesoria());
        String titulo = jdbc.queryForObject(
                "SELECT title FROM advisory_notifications ORDER BY created_at DESC LIMIT 1",
                String.class);
        assertTrue(titulo.startsWith("Sin funcionar"), "fue: " + titulo);
    }

    @Test
    void elAvisoPorAusenciaTampocoSeRepiteCadaDia() {
        insertRunDaysAgo(SupervisedJob.BOE_ALERTS, "OK", 10);
        supervisor.checkAbsences();
        int tras1 = avisos();
        supervisor.checkAbsences();
        supervisor.checkAbsences();
        assertEquals(tras1, avisos());
    }

    @Test
    void dentroDePlazoNoSeAvisa() {
        insertRunDaysAgo(SupervisedJob.BOE_ALERTS, "OK", 1);
        supervisor.checkAbsences();
        assertEquals(0, avisos(), "un dia de retraso no es un problema");
    }

    @Test
    void unaTareaQueNuncaHaCorridoNoDisparaNada() {
        // En una instalacion recien hecha TODAS estarian ausentes y el usuario
        // se encontraria la bandeja llena el primer dia.
        supervisor.checkAbsences();
        assertEquals(0, avisos());
    }

    /** Si SKIPPED contara como exito, la vigilancia se quedaria ciega. */
    @Test
    void saltarseElTrabajoNoCuentaComoHaberloHecho() {
        insertRunDaysAgo(SupervisedJob.BACKUP, "OK", 30);
        // El backup de arranque decide "ya hay uno reciente" y no copia nada.
        // Si esto renovase el ultimo acierto, un backup roto durante meses
        // pareceria sano en cada arranque.
        supervisor.recordSkipped(SupervisedJob.BACKUP, "Backup reciente");

        supervisor.checkAbsences();

        assertEquals(1, avisosAsesoria(),
                "los 30 dias sin copia REAL tienen que seguir contando");
    }

    @Test
    void elBackupTieneMasMargenPorqueEsSemanal() {
        assertEquals(10, SupervisedJob.BACKUP.maxDaysWithoutSuccess());
        insertRunDaysAgo(SupervisedJob.BACKUP, "OK", 8);
        supervisor.checkAbsences();
        assertEquals(0, avisos(), "8 dias con un ciclo semanal es normal");
    }

    @Test
    void laComprobacionDeActualizacionesNoVigilaAusencias() {
        // Solo corre al abrir la app: decir "lleva 5 dias sin comprobarse"
        // seria mentira si simplemente ha sido fin de semana.
        assertFalse(SupervisedJob.UPDATE_CHECK.watchesAbsence());
        insertRunDaysAgo(SupervisedJob.UPDATE_CHECK, "OK", 60);
        supervisor.checkAbsences();
        assertEquals(0, avisos());
    }

    @Test
    void peroSiFallaSiAvisa() {
        supervisor.recordOk(SupervisedJob.UPDATE_CHECK, "Al dia");
        supervisor.recordError(SupervisedJob.UPDATE_CHECK, "ConnectException");
        assertTrue(avisos() > 0, "no poder comprobar actualizaciones SI es un fallo");
    }

    // ====================================================================
    //  Estado para la pantalla
    // ====================================================================

    @Test
    void laPantallaRecibeUnaFilaPorTareaAunqueNoHayanCorrido() {
        List<JobSupervisor.JobStatus> st = supervisor.statuses();
        assertEquals(SupervisedJob.values().length, st.size());
        for (JobSupervisor.JobStatus s : st) {
            assertNotNull(s.jobName());
            assertNotNull(s.label());
        }
    }

    @Test
    void elEstadoDistingueUltimaEjecucionDeUltimoAcierto() {
        insertRunDaysAgo(SupervisedJob.BOE_ALERTS, "OK", 9);
        supervisor.recordError(SupervisedJob.BOE_ALERTS, "roto hoy");

        JobSupervisor.JobStatus s = supervisor.statuses().stream()
                .filter(x -> x.jobName().equals("boe-alerts")).findFirst().orElseThrow();

        assertEquals("ERROR", s.lastStatus(), "lo ultimo que paso fue un fallo");
        assertNotNull(s.lastSuccessAt(), "pero el ultimo acierto sigue ahi");
        assertTrue(s.lastMessage().contains("roto hoy"));
    }

    /** Un fallo supervisando no puede tumbar la tarea supervisada. */
    @Test
    void siLaSupervisionRevientaLaTareaSigueSuCamino() {
        JobSupervisor roto = new JobSupervisor(
                new JdbcTemplate(new DriverManagerDataSource("jdbc:mariadb://localhost:1/nada")),
                new AdvisoryNotificationService(jdbc, tenant(ASESORIA)),
                new BusinessNotificationService(jdbc, tenant(EMPRESA)));
        // No debe lanzar: si lanzase, habria roto el backup por no poder
        // apuntar que el backup fue bien.
        roto.recordOk(SupervisedJob.BACKUP, "da igual");
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    /**
     * {@code TenantContext} no es una interfaz funcional (tiene getter y
     * setter), asi que no vale una lambda. Aqui da igual el valor: el
     * supervisor manda la empresa por parametro en cada emit.
     */
    private static com.benjagest.backend.tenant.TenantContext tenant(String companyId) {
        return new com.benjagest.backend.tenant.TenantContext() {
            private String id = companyId;
            @Override public String getCurrentCompanyId() { return id; }
            @Override public void setCurrentCompanyId(String v) { this.id = v; }
        };
    }

    private void insertRunDaysAgo(SupervisedJob job, String status, int days) {
        jdbc.update("""
                INSERT INTO job_runs (id, job_name, started_at, finished_at, status, message)
                VALUES (?, ?, DATE_SUB(NOW(), INTERVAL ? DAY), DATE_SUB(NOW(), INTERVAL ? DAY), ?, ?)
                """, UUID.randomUUID().toString(), job.jobName(), days, days, status, "semilla");
    }

    private int runs(SupervisedJob job) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM job_runs WHERE job_name = ?", Integer.class, job.jobName());
        return n == null ? 0 : n;
    }

    private int avisos() {
        return avisosAsesoria() + avisosEmpresa();
    }

    private int avisosAsesoria() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM advisory_notifications", Integer.class);
        return n == null ? 0 : n;
    }

    private int avisosEmpresa() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_notifications", Integer.class);
        return n == null ? 0 : n;
    }
}
