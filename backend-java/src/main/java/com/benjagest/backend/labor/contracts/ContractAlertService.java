package com.benjagest.backend.labor.contracts;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CTR-6 — Motor de alertas de vencimiento.
 *
 * <p>Un cron diario barre TODAS las empresas/contratos y genera (idempotente
 * vía UK) las cuatro clases de aviso:
 * <ul>
 *   <li>PROBATION_ENDING — start_date + probation_days ∈ [hoy, hoy+7d]</li>
 *   <li>CONTRACT_ENDING_60D — end_date ∈ [hoy, hoy+60d]</li>
 *   <li>CONTRACT_ENDING_30D — end_date ∈ [hoy, hoy+30d]</li>
 *   <li>BIRTHDAY — employees.birth_date con mes+día == hoy</li>
 * </ul>
 *
 * <p>El cron NO usa TenantContext (es transversal — un user system corre
 * en todos los tenants). Para inserts hacemos JDBC explícito con
 * company_id de cada fila. Por eso las queries del cron son SIN filtro
 * de tenant.
 *
 * <p>Las queries de LECTURA del controller SÍ filtran por tenant.
 */
@Service
public class ContractAlertService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public ContractAlertService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    /**
     * Diario a las 06:00 hora local. Barre todos los contratos ACTIVE y
     * crea alertas faltantes para los próximos 60 días.
     */
    @Scheduled(cron = "0 0 6 * * *")
    public void runDaily() {
        scan();
    }

    /**
     * Ejecutable manual desde endpoint para pruebas. Cero ambigüedad sobre
     * cuándo refresca: el OWNER hace POST y al instante se ven nuevas
     * alertas.
     */
    @Transactional
    public ScanResult scan() {
        int probation = scanProbationEndings();
        int end30 = scanEnding(30, "CONTRACT_ENDING_30D", "WARNING",
                "Contrato finaliza en menos de 30 días",
                "El contrato del empleado finaliza pronto. Decida renovación, conversión o baja.");
        int end60 = scanEnding(60, "CONTRACT_ENDING_60D", "INFO",
                "Contrato finaliza en menos de 60 días",
                "Ventana legal para preavisar al SEPE conforme al art. 49 ET y el contrato.");
        int bday = scanBirthdays();
        return new ScanResult(probation, end30, end60, bday);
    }

    private int scanProbationEndings() {
        // contratos con probation_days > 0 cuyo fin de prueba caiga en los próximos 7 días
        return jdbc.update("""
                INSERT IGNORE INTO contract_alerts
                    (id, company_id, employee_id, contract_id, kind, title, message,
                     severity, fires_at)
                SELECT UUID(), c.company_id, c.employee_id, c.id,
                       'PROBATION_ENDING',
                       'Fin de periodo de prueba próximo',
                       CONCAT('El periodo de prueba de ', COALESCE(e.full_name, 'el empleado'),
                              ' finaliza el ', DATE_ADD(c.start_date, INTERVAL c.probation_days DAY),
                              '. Si quiere desistir, hágalo antes.'),
                       'WARNING',
                       DATE_ADD(c.start_date, INTERVAL c.probation_days DAY)
                  FROM employment_contracts c
                  LEFT JOIN employees e ON e.id = c.employee_id
                 WHERE c.status = 'ACTIVE'
                   AND c.probation_days IS NOT NULL AND c.probation_days > 0
                   AND DATE_ADD(c.start_date, INTERVAL c.probation_days DAY)
                       BETWEEN CURRENT_DATE AND DATE_ADD(CURRENT_DATE, INTERVAL 7 DAY)
                """);
    }

    private int scanEnding(int days, String kind, String severity, String title, String reason) {
        return jdbc.update("""
                INSERT IGNORE INTO contract_alerts
                    (id, company_id, employee_id, contract_id, kind, title, message,
                     severity, fires_at)
                SELECT UUID(), c.company_id, c.employee_id, c.id,
                       ?, ?,
                       CONCAT(?, ' Empleado: ', COALESCE(e.full_name, '—'),
                              '. Fecha fin: ', c.end_date, '.'),
                       ?, c.end_date
                  FROM employment_contracts c
                  LEFT JOIN employees e ON e.id = c.employee_id
                 WHERE c.status = 'ACTIVE'
                   AND c.end_date IS NOT NULL
                   AND c.end_date BETWEEN CURRENT_DATE AND DATE_ADD(CURRENT_DATE, INTERVAL ? DAY)
                """, kind, title, reason, severity, days);
    }

    private int scanBirthdays() {
        // Cumpleaños DE HOY — un único disparo por año por empleado gracias al UK
        // (company, contract=NULL, employee, kind, fires_at=today).
        return jdbc.update("""
                INSERT IGNORE INTO contract_alerts
                    (id, company_id, employee_id, contract_id, kind, title, message,
                     severity, fires_at)
                SELECT UUID(), e.company_id, e.id, NULL,
                       'BIRTHDAY',
                       CONCAT('Cumpleaños de ', e.full_name),
                       'Hoy cumple años. Buen detalle felicitarle.',
                       'INFO',
                       CURRENT_DATE
                  FROM employees e
                 WHERE e.birth_date IS NOT NULL
                   AND e.active = TRUE
                   AND DATE_FORMAT(e.birth_date, '%m-%d') = DATE_FORMAT(CURRENT_DATE, '%m-%d')
                """);
    }

    // ===== Lectura por tenant ===============================================

    public List<View> listPending() {
        return jdbc.query("""
                SELECT a.id, a.kind, a.title, a.message, a.severity, a.fires_at,
                       a.seen_at, a.contract_id, a.employee_id,
                       e.full_name AS employee_name
                  FROM contract_alerts a
                  LEFT JOIN employees e ON e.id = a.employee_id
                 WHERE a.company_id = ? AND a.dismissed_at IS NULL
                 ORDER BY a.fires_at, a.severity DESC
                """, this::map, tenant.getCurrentCompanyId());
    }

    @Transactional
    public void markSeen(String id) {
        jdbc.update("""
                UPDATE contract_alerts SET seen_at = COALESCE(seen_at, NOW())
                 WHERE id = ? AND company_id = ?
                """, id, tenant.getCurrentCompanyId());
    }

    @Transactional
    public void dismiss(String id) {
        int n = jdbc.update("""
                UPDATE contract_alerts SET dismissed_at = NOW()
                 WHERE id = ? AND company_id = ?
                """, id, tenant.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Alerta no encontrada");
        }
    }

    private View map(ResultSet rs, int n) throws SQLException {
        java.sql.Date d = rs.getDate("fires_at");
        java.sql.Timestamp seen = rs.getTimestamp("seen_at");
        return new View(
                rs.getString("id"),
                rs.getString("kind"),
                rs.getString("title"),
                rs.getString("message"),
                rs.getString("severity"),
                d == null ? null : d.toLocalDate(),
                seen != null,
                rs.getString("contract_id"),
                rs.getString("employee_id"),
                rs.getString("employee_name")
        );
    }

    public record View(
            String id, String kind, String title, String message,
            String severity, LocalDate firesAt, boolean seen,
            String contractId, String employeeId, String employeeName
    ) {}

    public record ScanResult(int probation, int end30, int end60, int birthdays) {}

    @RestController
    @RequestMapping("/api/contracts/alerts")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "ADVISOR", "EMPLOYEE"})
    public static class Controller {
        private final ContractAlertService service;

        public Controller(ContractAlertService service) { this.service = service; }

        @GetMapping
        public List<View> list() { return service.listPending(); }

        @PostMapping("/{id}/seen")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void markSeen(@PathVariable("id") String id) { service.markSeen(id); }

        @PostMapping("/{id}/dismiss")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void dismiss(@PathVariable("id") String id) { service.dismiss(id); }

        @PostMapping("/rescan")
        public ScanResult rescan() { return service.scan(); }
    }
}
