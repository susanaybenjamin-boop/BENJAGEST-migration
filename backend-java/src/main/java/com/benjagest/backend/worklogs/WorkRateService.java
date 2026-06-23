package com.benjagest.backend.worklogs;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * TRB-4 — Tarifas de trabajo por cliente (customer_work_rates). Autorrellenan el
 * precio al crear un trabajo. {@code customerId} NULL = tarifa GENERAL (por
 * defecto). Gestión por OWNER/ADMIN/ACCOUNTANT, módulo "shifts".
 */
@Service
public class WorkRateService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public WorkRateService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    private static final String SELECT = """
            SELECT id, company_id, customer_id, unit, concept, price, active
              FROM customer_work_rates
            """;

    private final org.springframework.jdbc.core.RowMapper<Rate> mapper = (rs, n) -> new Rate(
            rs.getString("id"), rs.getString("company_id"), rs.getString("customer_id"),
            rs.getString("unit"), rs.getString("concept"), rs.getBigDecimal("price"),
            rs.getBoolean("active"));

    /** Tarifas de un ÁMBITO concreto (cliente dado, o generales si customerId null). */
    public List<Rate> listScope(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return jdbc.query(SELECT + " WHERE company_id = ? AND customer_id IS NULL ORDER BY unit, concept",
                    mapper, tenant.getCurrentCompanyId());
        }
        return jdbc.query(SELECT + " WHERE company_id = ? AND customer_id = ? ORDER BY unit, concept",
                mapper, tenant.getCurrentCompanyId(), customerId);
    }

    /** Tarifas EFECTIVAS para el formulario: las del cliente + las generales. */
    public List<Rate> listEffective(String customerId) {
        StringBuilder sql = new StringBuilder(SELECT)
                .append(" WHERE company_id = ? AND active = TRUE AND (customer_id IS NULL");
        List<Object> args = new ArrayList<>();
        args.add(tenant.getCurrentCompanyId());
        if (customerId != null && !customerId.isBlank()) {
            sql.append(" OR customer_id = ?");
            args.add(customerId);
        }
        sql.append(") ORDER BY (customer_id IS NULL), unit, concept");
        return jdbc.query(sql.toString(), mapper, args.toArray());
    }

    public Rate create(UpsertRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO customer_work_rates (id, company_id, customer_id, unit, concept, price, active)
                VALUES (?, ?, ?, ?, ?, ?, TRUE)
                """,
                id, tenant.getCurrentCompanyId(), blank(req.customerId()),
                req.unit(), req.concept().trim(), req.price());
        return getOrThrow(id);
    }

    public Rate update(String id, UpsertRequest req) {
        validate(req);
        int n = jdbc.update("""
                UPDATE customer_work_rates SET customer_id = ?, unit = ?, concept = ?, price = ?
                 WHERE id = ? AND company_id = ?
                """,
                blank(req.customerId()), req.unit(), req.concept().trim(), req.price(),
                id, tenant.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tarifa no encontrada");
        return getOrThrow(id);
    }

    public void delete(String id) {
        int n = jdbc.update("DELETE FROM customer_work_rates WHERE id = ? AND company_id = ?",
                id, tenant.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tarifa no encontrada");
    }

    private Rate getOrThrow(String id) {
        List<Rate> rows = jdbc.query(SELECT + " WHERE id = ? AND company_id = ?",
                mapper, id, tenant.getCurrentCompanyId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tarifa no encontrada");
        return rows.get(0);
    }

    private void validate(UpsertRequest req) {
        if (req.unit() == null || !java.util.Set.of("HOURS", "DAYS", "MONTHS", "FIXED").contains(req.unit())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unidad inválida");
        }
        if (req.concept() == null || req.concept().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "concepto requerido");
        }
        if (req.price() == null || req.price().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "precio inválido");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public record Rate(String id, String companyId, String customerId,
                       String unit, String concept, BigDecimal price, boolean active) {}

    public record UpsertRequest(String customerId, String unit, String concept, BigDecimal price) {}

    @RestController
    @RequestMapping("/api/work-rates")
    @RequiresModule("shifts")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class Controller {
        private final WorkRateService service;

        public Controller(WorkRateService service) { this.service = service; }

        @GetMapping
        public List<Rate> list(
                @RequestParam(value = "customerId", required = false) String customerId,
                @RequestParam(value = "effective", required = false, defaultValue = "false") boolean effective) {
            return effective ? service.listEffective(customerId) : service.listScope(customerId);
        }

        @PostMapping
        public Rate create(@RequestBody UpsertRequest req) { return service.create(req); }

        @PutMapping("/{id}")
        public Rate update(@PathVariable("id") String id, @RequestBody UpsertRequest req) {
            return service.update(id, req);
        }

        @DeleteMapping("/{id}")
        public void delete(@PathVariable("id") String id) { service.delete(id); }
    }
}
