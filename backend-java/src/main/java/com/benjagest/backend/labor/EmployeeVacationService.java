package com.benjagest.backend.labor;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Registro de vacaciones del empleado (CV-VAC). Guarda los periodos de
 * vacaciones disfrutados/solicitados para que el finiquito calcule las
 * vacaciones pendientes (devengadas − disfrutadas) de forma automática.
 */
@Service
public class EmployeeVacationService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public EmployeeVacationService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public List<VacationView> list(String employeeId, Integer year) {
        StringBuilder sql = new StringBuilder("""
                SELECT v.id, v.employee_id, e.full_name AS employee_name,
                       v.start_date, v.end_date, v.days, v.status, v.notes
                  FROM employee_vacations v
                  JOIN employees e ON e.id = v.employee_id
                 WHERE v.company_id = ?
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenant.getCurrentCompanyId());
        if (StringUtils.hasText(employeeId)) { sql.append(" AND v.employee_id = ?"); args.add(employeeId); }
        if (year != null) { sql.append(" AND YEAR(v.start_date) = ?"); args.add(year); }
        sql.append(" ORDER BY v.start_date DESC");
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    /** Días de vacaciones disfrutados (APPROVED) por el empleado en el año, con
     *  inicio hasta {@code upTo} (incluido). Lo usa el finiquito. */
    public BigDecimal daysTakenInYear(String employeeId, int year, LocalDate upTo) {
        BigDecimal v = jdbc.query("""
                SELECT COALESCE(SUM(days), 0) FROM employee_vacations
                 WHERE company_id = ? AND employee_id = ? AND status = 'APPROVED'
                   AND YEAR(start_date) = ? AND start_date <= ?
                """,
                (rs, n) -> rs.getBigDecimal(1),
                tenant.getCurrentCompanyId(), employeeId, year, java.sql.Date.valueOf(upTo))
                .stream().findFirst().orElse(BigDecimal.ZERO);
        return v == null ? BigDecimal.ZERO : v;
    }

    @Transactional
    public VacationView create(UpsertRequest r) {
        validate(r);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO employee_vacations
                       (id, company_id, employee_id, start_date, end_date, days, status, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenant.getCurrentCompanyId(), r.employeeId(), r.startDate(), r.endDate(),
                r.days(), StringUtils.hasText(r.status()) ? r.status() : "APPROVED", blank(r.notes()));
        return findById(id);
    }

    @Transactional
    public VacationView update(String id, UpsertRequest r) {
        validate(r);
        int n = jdbc.update("""
                UPDATE employee_vacations
                   SET start_date = ?, end_date = ?, days = ?, status = ?, notes = ?
                 WHERE id = ? AND company_id = ?
                """,
                r.startDate(), r.endDate(), r.days(),
                StringUtils.hasText(r.status()) ? r.status() : "APPROVED", blank(r.notes()),
                id, tenant.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vacaciones no encontradas");
        return findById(id);
    }

    @Transactional
    public void delete(String id) {
        jdbc.update("DELETE FROM employee_vacations WHERE id = ? AND company_id = ?",
                id, tenant.getCurrentCompanyId());
    }

    private void validate(UpsertRequest r) {
        if (!StringUtils.hasText(r.employeeId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeId requerido");
        }
        if (r.startDate() == null || r.endDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fechas requeridas");
        }
        if (r.endDate().isBefore(r.startDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha fin es anterior a la de inicio");
        }
        if (r.days() == null || r.days().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Días inválidos");
        }
    }

    private VacationView findById(String id) {
        return jdbc.query("""
                SELECT v.id, v.employee_id, e.full_name AS employee_name,
                       v.start_date, v.end_date, v.days, v.status, v.notes
                  FROM employee_vacations v
                  JOIN employees e ON e.id = v.employee_id
                 WHERE v.id = ? AND v.company_id = ?
                """, this::map, id, tenant.getCurrentCompanyId())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vacaciones no encontradas"));
    }

    private VacationView map(ResultSet rs, int n) throws SQLException {
        java.sql.Date s = rs.getDate("start_date");
        java.sql.Date e = rs.getDate("end_date");
        return new VacationView(
                rs.getString("id"), rs.getString("employee_id"), rs.getString("employee_name"),
                s == null ? null : s.toLocalDate(), e == null ? null : e.toLocalDate(),
                rs.getBigDecimal("days"), rs.getString("status"), rs.getString("notes"));
    }

    private String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    public record VacationView(
            String id, String employeeId, String employeeName,
            LocalDate startDate, LocalDate endDate, BigDecimal days, String status, String notes) {}

    public record UpsertRequest(
            String employeeId, LocalDate startDate, LocalDate endDate,
            BigDecimal days, String status, String notes) {}

    @RestController
    @RequestMapping("/api/labor/vacations")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class Controller {
        private final EmployeeVacationService service;

        public Controller(EmployeeVacationService service) { this.service = service; }

        @GetMapping
        public List<VacationView> list(@RequestParam(value = "employeeId", required = false) String employeeId,
                                        @RequestParam(value = "year", required = false) Integer year) {
            return service.list(employeeId, year);
        }

        @PostMapping
        public VacationView create(@RequestBody UpsertRequest req) { return service.create(req); }

        @PutMapping("/{id}")
        public VacationView update(@PathVariable("id") String id, @RequestBody UpsertRequest req) {
            return service.update(id, req);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable("id") String id) { service.delete(id); }
    }
}
