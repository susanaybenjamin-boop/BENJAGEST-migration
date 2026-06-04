package com.benjagest.backend.labor;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
 * Servicio de Empleados. La tabla `employees` ya existia desde V2; en
 * V29 se enriquece con NUSS, direccion, IBAN, regimen SS, fecha de
 * alta, situacion familiar...
 *
 * Diseno de campos pensado para que el modelo 111/190 y las
 * comunicaciones con SS puedan tirar directamente.
 */
@Service
public class EmployeeService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public EmployeeService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public List<EmployeeView> list(boolean includeInactive) {
        return jdbcTemplate.query("""
                SELECT id, full_name, tax_identifier, social_security_number,
                       email, phone, birth_date, gender, marital_status,
                       dependent_children, dependent_disabled,
                       address_line, city, province, postal_code, country,
                       iban, work_type, ss_regime, hire_date, termination_date,
                       termination_reason, active, created_at, updated_at
                  FROM employees
                 WHERE company_id = ?
                   AND (? = TRUE OR active = TRUE)
                 ORDER BY active DESC, full_name
                """, this::mapView,
                tenantContext.getCurrentCompanyId(), includeInactive);
    }

    public EmployeeView get(String id) {
        return findById(id);
    }

    @Transactional
    public EmployeeView create(UpsertRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO employees (
                    id, company_id, full_name, tax_identifier, social_security_number,
                    email, phone, birth_date, gender, marital_status,
                    dependent_children, dependent_disabled,
                    address_line, city, province, postal_code, country,
                    iban, work_type, ss_regime, hire_date, termination_date,
                    termination_reason, active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantContext.getCurrentCompanyId(),
                req.fullName(), blank(req.taxIdentifier()), blank(req.socialSecurityNumber()),
                blank(req.email()), blank(req.phone()),
                req.birthDate(), blank(req.gender()), blank(req.maritalStatus()),
                req.dependentChildren(), req.dependentDisabled(),
                blank(req.addressLine()), blank(req.city()), blank(req.province()),
                blank(req.postalCode()), blank(req.country()),
                blank(req.iban()), blank(req.workType()), blank(req.ssRegime()),
                req.hireDate(), req.terminationDate(), blank(req.terminationReason()),
                req.active() == null || req.active()
        );
        return findById(id);
    }

    @Transactional
    public EmployeeView update(String id, UpsertRequest req) {
        validate(req);
        int n = jdbcTemplate.update("""
                UPDATE employees
                   SET full_name = ?, tax_identifier = ?, social_security_number = ?,
                       email = ?, phone = ?, birth_date = ?, gender = ?, marital_status = ?,
                       dependent_children = ?, dependent_disabled = ?,
                       address_line = ?, city = ?, province = ?, postal_code = ?, country = ?,
                       iban = ?, work_type = ?, ss_regime = ?, hire_date = ?,
                       termination_date = ?, termination_reason = ?, active = ?
                 WHERE id = ? AND company_id = ?
                """,
                req.fullName(), blank(req.taxIdentifier()), blank(req.socialSecurityNumber()),
                blank(req.email()), blank(req.phone()),
                req.birthDate(), blank(req.gender()), blank(req.maritalStatus()),
                req.dependentChildren(), req.dependentDisabled(),
                blank(req.addressLine()), blank(req.city()), blank(req.province()),
                blank(req.postalCode()), blank(req.country()),
                blank(req.iban()), blank(req.workType()), blank(req.ssRegime()),
                req.hireDate(), req.terminationDate(), blank(req.terminationReason()),
                req.active() == null || req.active(),
                id, tenantContext.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empleado no encontrado");
        }
        return findById(id);
    }

    /**
     * Soft delete: marca inactivo + fija termination_date si no hay.
     * No borramos porque hay foreign keys de contratos, fichajes, nominas.
     */
    @Transactional
    public void delete(String id) {
        int n = jdbcTemplate.update("""
                UPDATE employees
                   SET active = FALSE,
                       termination_date = COALESCE(termination_date, CURRENT_DATE())
                 WHERE id = ? AND company_id = ?
                """, id, tenantContext.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empleado no encontrado");
        }
    }

    private EmployeeView findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, full_name, tax_identifier, social_security_number,
                       email, phone, birth_date, gender, marital_status,
                       dependent_children, dependent_disabled,
                       address_line, city, province, postal_code, country,
                       iban, work_type, ss_regime, hire_date, termination_date,
                       termination_reason, active, created_at, updated_at
                  FROM employees
                 WHERE id = ? AND company_id = ?
                """, this::mapView, id, tenantContext.getCurrentCompanyId())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empleado no encontrado"));
    }

    private void validate(UpsertRequest req) {
        if (!StringUtils.hasText(req.fullName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nombre requerido");
        }
        if (req.ssRegime() != null && !req.ssRegime().isBlank()
                && !List.of("RETA", "GENERAL", "AUTONOMO_SOCIETARIO", "ARTISTAS", "MAR", "AGRARIO", "OTHER")
                        .contains(req.ssRegime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Regimen SS invalido");
        }
    }

    private String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private EmployeeView mapView(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Date bd = rs.getDate("birth_date");
        java.sql.Date hd = rs.getDate("hire_date");
        java.sql.Date td = rs.getDate("termination_date");
        Timestamp ca = rs.getTimestamp("created_at");
        Timestamp ua = rs.getTimestamp("updated_at");
        return new EmployeeView(
                rs.getString("id"),
                rs.getString("full_name"),
                rs.getString("tax_identifier"),
                rs.getString("social_security_number"),
                rs.getString("email"),
                rs.getString("phone"),
                bd == null ? null : bd.toLocalDate(),
                rs.getString("gender"),
                rs.getString("marital_status"),
                (Integer) rs.getObject("dependent_children"),
                (Integer) rs.getObject("dependent_disabled"),
                rs.getString("address_line"),
                rs.getString("city"),
                rs.getString("province"),
                rs.getString("postal_code"),
                rs.getString("country"),
                rs.getString("iban"),
                rs.getString("work_type"),
                rs.getString("ss_regime"),
                hd == null ? null : hd.toLocalDate(),
                td == null ? null : td.toLocalDate(),
                rs.getString("termination_reason"),
                rs.getBoolean("active"),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant()
        );
    }

    public record EmployeeView(
            String id, String fullName, String taxIdentifier, String socialSecurityNumber,
            String email, String phone, LocalDate birthDate, String gender, String maritalStatus,
            Integer dependentChildren, Integer dependentDisabled,
            String addressLine, String city, String province, String postalCode, String country,
            String iban, String workType, String ssRegime,
            LocalDate hireDate, LocalDate terminationDate, String terminationReason,
            boolean active, Instant createdAt, Instant updatedAt
    ) {}

    public record UpsertRequest(
            String fullName, String taxIdentifier, String socialSecurityNumber,
            String email, String phone, LocalDate birthDate, String gender, String maritalStatus,
            Integer dependentChildren, Integer dependentDisabled,
            String addressLine, String city, String province, String postalCode, String country,
            String iban, String workType, String ssRegime,
            LocalDate hireDate, LocalDate terminationDate, String terminationReason,
            Boolean active
    ) {}

    @RestController
    @RequestMapping("/api/labor/employees")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class EmployeeController {
        private final EmployeeService service;

        public EmployeeController(EmployeeService service) { this.service = service; }

        @GetMapping
        public List<EmployeeView> list(@RequestParam(value = "includeInactive", defaultValue = "false") boolean inc) {
            return service.list(inc);
        }

        @GetMapping("/{id}")
        public EmployeeView get(@PathVariable("id") String id) { return service.get(id); }

        @PostMapping
        public EmployeeView create(@RequestBody UpsertRequest req) { return service.create(req); }

        @PutMapping("/{id}")
        public EmployeeView update(@PathVariable("id") String id, @RequestBody UpsertRequest req) {
            return service.update(id, req);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable("id") String id) { service.delete(id); }
    }
}
