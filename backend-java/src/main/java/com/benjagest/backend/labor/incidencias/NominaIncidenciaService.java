package com.benjagest.backend.labor.incidencias;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * INC-1 — Incidencias de nómina por (empleado, periodo). CRUD sobre
 * {@code nomina_incidencias} (V136). Las usa el motor de nómina al calcular el
 * recibo de ese periodo (integración en INC-2/INC-3/INC-4). Aquí solo se
 * registran y persisten; el tratamiento legal (cotización adicional de horas
 * extra, reducción de base por ausencias, asiento de pagas extra) vive en los
 * slices siguientes.
 *
 * <p>Tenant-scoped: todas las consultas filtran por la empresa activa. Gestión
 * por administración de nómina (OWNER/ADMIN/ACCOUNTANT), no por el empleado.
 */
@Service
public class NominaIncidenciaService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public NominaIncidenciaService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public List<IncidenciaView> list(String employeeId, int year, int month) {
        return jdbc.query("""
                SELECT id, employee_id, period_year, period_month, kind, subtype, concept,
                       hours, unit_price, days, amount, cotizes, taxable, notes, source, source_ref_id
                  FROM nomina_incidencias
                 WHERE company_id = ? AND employee_id = ?
                   AND period_year = ? AND period_month = ?
                 ORDER BY kind, concept
                """, MAPPER, tenant.getCurrentCompanyId(), employeeId, year, month);
    }

    public IncidenciaView create(UpsertRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        BigDecimal amount = effectiveAmount(req);
        jdbc.update("""
                INSERT INTO nomina_incidencias
                       (id, company_id, employee_id, period_year, period_month, kind, subtype,
                        concept, hours, unit_price, days, amount, cotizes, taxable, notes, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL')
                """,
                id, tenant.getCurrentCompanyId(), req.employeeId(), req.periodYear(), req.periodMonth(),
                req.kind(), blank(req.subtype()), req.concept().trim(),
                req.hours(), req.unitPrice(), req.days(), amount,
                bool(req.cotizes(), defaultCotizes(req.kind())),
                bool(req.taxable(), defaultTaxable(req.kind())),
                blank(req.notes()));
        return getOrThrow(id);
    }

    public IncidenciaView update(String id, UpsertRequest req) {
        validate(req);
        BigDecimal amount = effectiveAmount(req);
        int n = jdbc.update("""
                UPDATE nomina_incidencias
                   SET kind = ?, subtype = ?, concept = ?, hours = ?, unit_price = ?, days = ?,
                       amount = ?, cotizes = ?, taxable = ?, notes = ?
                 WHERE id = ? AND company_id = ?
                """,
                req.kind(), blank(req.subtype()), req.concept().trim(),
                req.hours(), req.unitPrice(), req.days(), amount,
                bool(req.cotizes(), defaultCotizes(req.kind())),
                bool(req.taxable(), defaultTaxable(req.kind())),
                blank(req.notes()),
                id, tenant.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Incidencia no encontrada");
        return getOrThrow(id);
    }

    public void delete(String id) {
        int n = jdbc.update("DELETE FROM nomina_incidencias WHERE id = ? AND company_id = ?",
                id, tenant.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Incidencia no encontrada");
    }

    // ---- helpers ----

    private IncidenciaView getOrThrow(String id) {
        List<IncidenciaView> rows = jdbc.query("""
                SELECT id, employee_id, period_year, period_month, kind, subtype, concept,
                       hours, unit_price, days, amount, cotizes, taxable, notes, source, source_ref_id
                  FROM nomina_incidencias
                 WHERE id = ? AND company_id = ?
                """, MAPPER, id, tenant.getCurrentCompanyId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Incidencia no encontrada");
        return rows.get(0);
    }

    private void validate(UpsertRequest req) {
        if (req.employeeId() == null || req.employeeId().isBlank())
            throw bad("employeeId requerido");
        if (req.kind() == null || !KINDS.contains(req.kind()))
            throw bad("kind invalido: " + req.kind());
        if (req.concept() == null || req.concept().isBlank())
            throw bad("concepto requerido");
        if (req.periodMonth() < 1 || req.periodMonth() > 12)
            throw bad("mes invalido");
        if (req.periodYear() < 2000 || req.periodYear() > 2100)
            throw bad("año invalido");
        if (effectiveAmount(req).signum() == 0 && req.days() == null)
            throw bad("indica un importe (o horas x precio, o días)");
    }

    /** Importe efectivo: el directo, o hours*unitPrice si no se dio importe. */
    private BigDecimal effectiveAmount(UpsertRequest req) {
        if (req.amount() != null && req.amount().signum() != 0) return req.amount().abs();
        if (req.hours() != null && req.unitPrice() != null)
            return req.hours().multiply(req.unitPrice()).setScale(2, RoundingMode.HALF_UP).abs();
        return BigDecimal.ZERO;
    }

    /** Por defecto: ABSENCE/DEDUCTION no tributan; el resto sí. */
    private boolean defaultTaxable(String kind) {
        return !("DEDUCTION".equals(kind));
    }

    /** Por defecto: DEDUCTION no cotiza; el resto sí (overtime cotiza aparte en INC-2). */
    private boolean defaultCotizes(String kind) {
        return !("DEDUCTION".equals(kind));
    }

    private static final java.util.Set<String> KINDS =
            java.util.Set.of("OVERTIME", "COMPLEMENT", "ABSENCE", "DEDUCTION", "OTHER");

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean bool(Boolean b, boolean dflt) {
        return b == null ? dflt : b;
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static final org.springframework.jdbc.core.RowMapper<IncidenciaView> MAPPER = (rs, n) ->
            new IncidenciaView(
                    rs.getString("id"), rs.getString("employee_id"),
                    rs.getInt("period_year"), rs.getInt("period_month"),
                    rs.getString("kind"), rs.getString("subtype"), rs.getString("concept"),
                    rs.getBigDecimal("hours"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("days"),
                    rs.getBigDecimal("amount"), rs.getBoolean("cotizes"), rs.getBoolean("taxable"),
                    rs.getString("notes"), rs.getString("source"), rs.getString("source_ref_id"));

    // ---- DTOs ----

    public record IncidenciaView(
            String id, String employeeId, int periodYear, int periodMonth,
            String kind, String subtype, String concept,
            BigDecimal hours, BigDecimal unitPrice, BigDecimal days,
            BigDecimal amount, boolean cotizes, boolean taxable,
            String notes, String source, String sourceRefId
    ) {}

    public record UpsertRequest(
            String employeeId, int periodYear, int periodMonth,
            String kind, String subtype, String concept,
            BigDecimal hours, BigDecimal unitPrice, BigDecimal days,
            BigDecimal amount, Boolean cotizes, Boolean taxable, String notes
    ) {}

    @RestController
    @RequestMapping("/api/labor/incidencias")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class Controller {
        private final NominaIncidenciaService service;

        public Controller(NominaIncidenciaService service) { this.service = service; }

        @GetMapping
        public List<IncidenciaView> list(
                @RequestParam("employeeId") String employeeId,
                @RequestParam("year") int year,
                @RequestParam("month") int month) {
            return service.list(employeeId, year, month);
        }

        @PostMapping
        public IncidenciaView create(@RequestBody UpsertRequest req) {
            return service.create(req);
        }

        @PutMapping("/{id}")
        public IncidenciaView update(@PathVariable("id") String id, @RequestBody UpsertRequest req) {
            return service.update(id, req);
        }

        @DeleteMapping("/{id}")
        public void delete(@PathVariable("id") String id) {
            service.delete(id);
        }
    }
}
