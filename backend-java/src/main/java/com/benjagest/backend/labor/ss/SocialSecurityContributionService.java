package com.benjagest.backend.labor.ss;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
 * Servicio de cuotas de Seguridad Social. CRUD básico para registrar
 * las cuotas mensuales TC1/RED por empresa o por empleado. Los
 * cálculos reales (tipos % + bases máximas/mínimas) los hace la
 * mutua / sistema RED; aquí solo registramos el resultado.
 *
 * <p>Pensado para que el módulo de Nóminas pueda enlazar a estas
 * filas como derivadas de la nómina del periodo.
 */
@Service
public class SocialSecurityContributionService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public SocialSecurityContributionService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public List<SocialSecurityContribution> list(Integer year, Integer month, String employeeId) {
        String companyId = tenant.getCurrentCompanyId();
        StringBuilder sql = new StringBuilder("""
                SELECT id, company_id, employee_id, period_year, period_month,
                       contribution_type, base_amount, contribution_amount,
                       status, created_at
                  FROM social_security_contributions
                 WHERE company_id = ?
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(companyId);
        if (year != null) {
            sql.append(" AND period_year = ?");
            args.add(year);
        }
        if (month != null) {
            sql.append(" AND period_month = ?");
            args.add(month);
        }
        if (employeeId != null && !employeeId.isBlank()) {
            sql.append(" AND employee_id = ?");
            args.add(employeeId);
        }
        sql.append(" ORDER BY period_year DESC, period_month DESC, contribution_type");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public SocialSecurityContribution getById(String id) {
        Optional<SocialSecurityContribution> opt = jdbc.query("""
                SELECT id, company_id, employee_id, period_year, period_month,
                       contribution_type, base_amount, contribution_amount,
                       status, created_at
                  FROM social_security_contributions
                 WHERE id = ?
                """, MAPPER, id).stream().findFirst();
        SocialSecurityContribution c = opt.orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Cuota no encontrada"));
        if (!c.companyId().equals(tenant.getCurrentCompanyId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cuota no encontrada en tu empresa");
        }
        return c;
    }

    @Transactional
    public SocialSecurityContribution create(CreateRequest req) {
        validate(req);
        SocialSecurityContribution c = new SocialSecurityContribution(
                UUID.randomUUID().toString(),
                tenant.getCurrentCompanyId(),
                blank(req.employeeId()),
                req.periodYear(),
                req.periodMonth(),
                req.contributionType(),
                req.baseAmount() == null ? BigDecimal.ZERO : req.baseAmount(),
                req.contributionAmount() == null ? BigDecimal.ZERO : req.contributionAmount(),
                req.status() == null || req.status().isBlank()
                        ? SocialSecurityContribution.STATUS_DRAFT : req.status(),
                Instant.now()
        );
        jdbc.update("""
                INSERT INTO social_security_contributions
                       (id, company_id, employee_id, period_year, period_month,
                        contribution_type, base_amount, contribution_amount,
                        status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                """,
                c.id(), c.companyId(), c.employeeId(), c.periodYear(), c.periodMonth(),
                c.contributionType(), c.baseAmount(), c.contributionAmount(),
                c.status());
        return c;
    }

    @Transactional
    public SocialSecurityContribution update(String id, UpdateRequest req) {
        SocialSecurityContribution current = getById(id);
        SocialSecurityContribution updated = new SocialSecurityContribution(
                current.id(),
                current.companyId(),
                current.employeeId(),
                current.periodYear(),
                current.periodMonth(),
                req.contributionType() != null ? req.contributionType() : current.contributionType(),
                req.baseAmount() != null ? req.baseAmount() : current.baseAmount(),
                req.contributionAmount() != null ? req.contributionAmount() : current.contributionAmount(),
                req.status() != null ? req.status() : current.status(),
                current.createdAt()
        );
        jdbc.update("""
                UPDATE social_security_contributions
                   SET contribution_type = ?, base_amount = ?,
                       contribution_amount = ?, status = ?
                 WHERE id = ?
                """,
                updated.contributionType(), updated.baseAmount(),
                updated.contributionAmount(), updated.status(), updated.id());
        return updated;
    }

    @Transactional
    public void delete(String id) {
        SocialSecurityContribution c = getById(id);
        // Si ya está FILED o PAID, bloqueamos el borrado — equivalente
        // a la inalterabilidad de un asiento contable cerrado. Solo
        // los DRAFT se pueden eliminar.
        if (!SocialSecurityContribution.STATUS_DRAFT.equals(c.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo las cuotas en DRAFT se pueden eliminar.");
        }
        jdbc.update("DELETE FROM social_security_contributions WHERE id = ?", id);
    }

    private static void validate(CreateRequest req) {
        if (req.periodYear() < 2020 || req.periodYear() > 2050) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "periodYear fuera de rango (2020-2050)");
        }
        if (req.periodMonth() < 1 || req.periodMonth() > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "periodMonth debe estar entre 1 y 12");
        }
        if (req.contributionType() == null || req.contributionType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "contributionType obligatorio");
        }
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static final RowMapper<SocialSecurityContribution> MAPPER = (rs, i) ->
            new SocialSecurityContribution(
                    rs.getString("id"),
                    rs.getString("company_id"),
                    rs.getString("employee_id"),
                    rs.getInt("period_year"),
                    rs.getInt("period_month"),
                    rs.getString("contribution_type"),
                    rs.getBigDecimal("base_amount"),
                    rs.getBigDecimal("contribution_amount"),
                    rs.getString("status"),
                    toInstant(rs.getTimestamp("created_at"))
            );

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public record CreateRequest(
            String employeeId,
            int periodYear,
            int periodMonth,
            String contributionType,
            BigDecimal baseAmount,
            BigDecimal contributionAmount,
            String status
    ) {}

    public record UpdateRequest(
            String contributionType,
            BigDecimal baseAmount,
            BigDecimal contributionAmount,
            String status
    ) {}

    @RestController
    @RequestMapping("/api/labor/social-security")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class Controller {
        private final SocialSecurityContributionService service;

        public Controller(SocialSecurityContributionService service) {
            this.service = service;
        }

        @GetMapping
        public List<SocialSecurityContribution> list(
                @RequestParam(value = "year", required = false) Integer year,
                @RequestParam(value = "month", required = false) Integer month,
                @RequestParam(value = "employeeId", required = false) String employeeId) {
            return service.list(year, month, employeeId);
        }

        @GetMapping("/{id}")
        public SocialSecurityContribution getById(@PathVariable("id") String id) {
            return service.getById(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
        public SocialSecurityContribution create(@RequestBody CreateRequest req) {
            return service.create(req);
        }

        @PutMapping("/{id}")
        @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
        public SocialSecurityContribution update(@PathVariable("id") String id,
                                                    @RequestBody UpdateRequest req) {
            return service.update(id, req);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        @RequiresRole({"OWNER", "ADMIN"})
        public void delete(@PathVariable("id") String id) {
            service.delete(id);
        }
    }
}
