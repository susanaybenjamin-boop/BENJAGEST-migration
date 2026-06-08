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
 * CRUD de contratos de trabajo (employment_contracts).
 *
 * Un empleado puede tener varios contratos (uno tras otro tras cambios
 * de modalidad o tras una baja+alta). En cualquier momento un empleado
 * con un contrato ACTIVE es el "vigente"; las nominas se calculan
 * contra ese.
 */
@Service
public class EmploymentContractService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public EmploymentContractService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public List<ContractView> list(String employeeId) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, employee_id, contract_type, sepe_contract_code,
                       collective_agreement, professional_category, professional_group,
                       start_date, end_date, weekly_hours, gross_salary,
                       annual_bonuses, vacation_days, irpf_percent,
                       workplace_address, status, termination_reason,
                       probation_days, pdf_model
                  FROM employment_contracts
                 WHERE company_id = ?
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (StringUtils.hasText(employeeId)) {
            sql.append(" AND employee_id = ?");
            args.add(employeeId);
        }
        sql.append(" ORDER BY start_date DESC");
        return jdbcTemplate.query(sql.toString(), this::mapView, args.toArray());
    }

    @Transactional
    public ContractView create(UpsertRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO employment_contracts (
                    id, company_id, employee_id, contract_type, sepe_contract_code,
                    collective_agreement, professional_category, professional_group,
                    start_date, end_date, weekly_hours, gross_salary,
                    annual_bonuses, vacation_days, irpf_percent,
                    workplace_address, status, termination_reason,
                    probation_days, pdf_model
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantContext.getCurrentCompanyId(),
                req.employeeId(), req.contractType(), blank(req.sepeContractCode()),
                blank(req.collectiveAgreement()), blank(req.professionalCategory()),
                blank(req.professionalGroup()),
                req.startDate(), req.endDate(),
                req.weeklyHours(), req.grossSalary(),
                req.annualBonuses() == null ? 2 : req.annualBonuses(),
                req.vacationDays() == null ? 30 : req.vacationDays(),
                req.irpfPercent(),
                blank(req.workplaceAddress()),
                StringUtils.hasText(req.status()) ? req.status() : "ACTIVE",
                blank(req.terminationReason()),
                req.probationDays(),
                blank(req.pdfModel())
        );
        return findById(id);
    }

    @Transactional
    public ContractView update(String id, UpsertRequest req) {
        validate(req);
        int n = jdbcTemplate.update("""
                UPDATE employment_contracts
                   SET contract_type = ?, sepe_contract_code = ?,
                       collective_agreement = ?, professional_category = ?, professional_group = ?,
                       start_date = ?, end_date = ?, weekly_hours = ?, gross_salary = ?,
                       annual_bonuses = ?, vacation_days = ?, irpf_percent = ?,
                       workplace_address = ?, status = ?, termination_reason = ?,
                       probation_days = ?, pdf_model = ?
                 WHERE id = ? AND company_id = ?
                """,
                req.contractType(), blank(req.sepeContractCode()),
                blank(req.collectiveAgreement()), blank(req.professionalCategory()),
                blank(req.professionalGroup()),
                req.startDate(), req.endDate(),
                req.weeklyHours(), req.grossSalary(),
                req.annualBonuses(), req.vacationDays(), req.irpfPercent(),
                blank(req.workplaceAddress()),
                StringUtils.hasText(req.status()) ? req.status() : "ACTIVE",
                blank(req.terminationReason()),
                req.probationDays(), blank(req.pdfModel()),
                id, tenantContext.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contrato no encontrado");
        }
        return findById(id);
    }

    @Transactional
    public void delete(String id) {
        int n = jdbcTemplate.update("""
                DELETE FROM employment_contracts
                 WHERE id = ? AND company_id = ? AND status = 'DRAFT'
                """, id, tenantContext.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se pueden borrar contratos en borrador. Pasalo a TERMINATED para cerrarlo.");
        }
    }

    private ContractView findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, employee_id, contract_type, sepe_contract_code,
                       collective_agreement, professional_category, professional_group,
                       start_date, end_date, weekly_hours, gross_salary,
                       annual_bonuses, vacation_days, irpf_percent,
                       workplace_address, status, termination_reason,
                       probation_days, pdf_model
                  FROM employment_contracts
                 WHERE id = ? AND company_id = ?
                """, this::mapView, id, tenantContext.getCurrentCompanyId())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contrato no encontrado"));
    }

    private void validate(UpsertRequest req) {
        if (!StringUtils.hasText(req.employeeId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeId requerido");
        }
        if (!StringUtils.hasText(req.contractType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contractType requerido");
        }
        if (req.startDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate requerido");
        }
        if (req.irpfPercent() != null
                && (req.irpfPercent().signum() < 0
                        || req.irpfPercent().compareTo(BigDecimal.valueOf(50)) > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "irpfPercent fuera de rango");
        }
    }

    private String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    private ContractView mapView(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Date s = rs.getDate("start_date");
        java.sql.Date e = rs.getDate("end_date");
        return new ContractView(
                rs.getString("id"),
                rs.getString("employee_id"),
                rs.getString("contract_type"),
                rs.getString("sepe_contract_code"),
                rs.getString("collective_agreement"),
                rs.getString("professional_category"),
                rs.getString("professional_group"),
                s == null ? null : s.toLocalDate(),
                e == null ? null : e.toLocalDate(),
                rs.getBigDecimal("weekly_hours"),
                rs.getBigDecimal("gross_salary"),
                (Integer) rs.getObject("annual_bonuses"),
                (Integer) rs.getObject("vacation_days"),
                rs.getBigDecimal("irpf_percent"),
                rs.getString("workplace_address"),
                rs.getString("status"),
                rs.getString("termination_reason"),
                (Integer) rs.getObject("probation_days"),
                rs.getString("pdf_model")
        );
    }

    public record ContractView(
            String id, String employeeId, String contractType, String sepeContractCode,
            String collectiveAgreement, String professionalCategory, String professionalGroup,
            LocalDate startDate, LocalDate endDate,
            BigDecimal weeklyHours, BigDecimal grossSalary,
            Integer annualBonuses, Integer vacationDays, BigDecimal irpfPercent,
            String workplaceAddress, String status, String terminationReason,
            Integer probationDays, String pdfModel
    ) {}

    public record UpsertRequest(
            String employeeId, String contractType, String sepeContractCode,
            String collectiveAgreement, String professionalCategory, String professionalGroup,
            LocalDate startDate, LocalDate endDate,
            BigDecimal weeklyHours, BigDecimal grossSalary,
            Integer annualBonuses, Integer vacationDays, BigDecimal irpfPercent,
            String workplaceAddress, String status, String terminationReason,
            Integer probationDays, String pdfModel
    ) {}

    @RestController
    @RequestMapping("/api/labor/contracts")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class ContractController {
        private final EmploymentContractService service;

        public ContractController(EmploymentContractService service) { this.service = service; }

        @GetMapping
        public List<ContractView> list(@RequestParam(value = "employeeId", required = false) String employeeId) {
            return service.list(employeeId);
        }

        @PostMapping
        public ContractView create(@RequestBody UpsertRequest req) { return service.create(req); }

        @PutMapping("/{id}")
        public ContractView update(@PathVariable("id") String id, @RequestBody UpsertRequest req) {
            return service.update(id, req);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable("id") String id) { service.delete(id); }
    }
}
