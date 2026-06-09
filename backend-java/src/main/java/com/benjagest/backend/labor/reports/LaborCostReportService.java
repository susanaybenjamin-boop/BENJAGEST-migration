package com.benjagest.backend.labor.reports;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reporte de coste laboral por empleado.
 *
 * <p>Agrega para un año concreto:
 * <ul>
 *   <li>Suma de salarios brutos pagados (payslips.gross_amount).</li>
 *   <li>Suma de cuotas SS empresariales agregadas a esa empresa
 *       (employer_*) en el mismo período.</li>
 *   <li>Coste total = brutos + cuotas patronales.</li>
 *   <li>Coste medio mensual = total / 12.</li>
 * </ul>
 *
 * <p>El reporte combina dos tablas pero el resultado se ofrece como
 * fila plana por empleado para el listado UI.
 */
@Service
public class LaborCostReportService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public LaborCostReportService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    /**
     * Devuelve una fila por empleado activo de la empresa con el
     * coste agregado del año dado.
     */
    public List<EmployeeCostRow> report(int year) {
        String companyId = tenant.getCurrentCompanyId();
        return jdbc.query("""
                SELECT e.id AS employee_id,
                       e.full_name,
                       e.tax_identifier,
                       e.active,
                       COALESCE(SUM(CASE WHEN ps.period_year = ? THEN ps.gross_amount ELSE 0 END), 0)
                           AS gross_total,
                       COALESCE(SUM(CASE WHEN ps.period_year = ? THEN ps.net_amount ELSE 0 END), 0)
                           AS net_total,
                       COALESCE(SUM(CASE WHEN ssc.period_year = ?
                                          AND ssc.contribution_type LIKE 'EMPLOYER_%'
                                         THEN ssc.contribution_amount
                                         ELSE 0 END), 0)
                           AS employer_ss_total
                  FROM employees e
                  LEFT JOIN payslips ps ON ps.employee_id = e.id
                  LEFT JOIN social_security_contributions ssc
                       ON ssc.employee_id = e.id
                 WHERE e.company_id = ?
                 GROUP BY e.id, e.full_name, e.tax_identifier, e.active
                 ORDER BY gross_total DESC, e.full_name ASC
                """,
                (rs, i) -> {
                    BigDecimal gross = rs.getBigDecimal("gross_total");
                    BigDecimal employerSs = rs.getBigDecimal("employer_ss_total");
                    BigDecimal total = (gross == null ? BigDecimal.ZERO : gross)
                            .add(employerSs == null ? BigDecimal.ZERO : employerSs);
                    return new EmployeeCostRow(
                            rs.getString("employee_id"),
                            rs.getString("full_name"),
                            rs.getString("tax_identifier"),
                            rs.getBoolean("active"),
                            gross,
                            rs.getBigDecimal("net_total"),
                            employerSs,
                            total,
                            total.divide(BigDecimal.valueOf(12), 2, java.math.RoundingMode.HALF_UP)
                    );
                },
                year, year, year, companyId);
    }

    /**
     * Suma global del año para la empresa (un solo número).
     */
    public CompanyTotalRow companyTotal(int year) {
        List<EmployeeCostRow> rows = report(year);
        BigDecimal gross = rows.stream().map(EmployeeCostRow::grossTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = rows.stream().map(EmployeeCostRow::netTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal employerSs = rows.stream().map(EmployeeCostRow::employerSsTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = gross.add(employerSs);
        return new CompanyTotalRow(year, rows.size(), gross, net, employerSs, total,
                total.divide(BigDecimal.valueOf(12), 2, java.math.RoundingMode.HALF_UP));
    }

    public record EmployeeCostRow(
            String employeeId,
            String fullName,
            String taxIdentifier,
            boolean active,
            BigDecimal grossTotal,
            BigDecimal netTotal,
            BigDecimal employerSsTotal,
            BigDecimal totalCostYear,
            BigDecimal averageMonthlyCost
    ) {}

    public record CompanyTotalRow(
            int year,
            int employeeCount,
            BigDecimal grossTotal,
            BigDecimal netTotal,
            BigDecimal employerSsTotal,
            BigDecimal totalCostYear,
            BigDecimal averageMonthlyCost
    ) {}

    @RestController
    @RequestMapping("/api/labor/reports/cost")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class Controller {
        private final LaborCostReportService service;

        public Controller(LaborCostReportService service) {
            this.service = service;
        }

        @GetMapping
        public List<EmployeeCostRow> report(@RequestParam("year") int year) {
            return service.report(year);
        }

        @GetMapping("/total")
        public CompanyTotalRow total(@RequestParam("year") int year) {
            return service.companyTotal(year);
        }
    }
}
