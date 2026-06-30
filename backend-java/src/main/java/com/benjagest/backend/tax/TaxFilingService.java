package com.benjagest.backend.tax;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
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
 * Declaraciones tributarias (tax filings) — una fila = una declaracion
 * de UNA empresa para UN modelo en UN periodo (ano/trimestre/mes).
 *
 * No materializamos las casillas como columnas: van en data JSON. Cuando
 * implementemos el generador del modelo 303 (sub-slice futuro) leera el
 * data, generara el XML/PDF y mutara el estado a PRESENTED.
 *
 * Endpoint de calendario fiscal: dado un ano, devuelve los vencimientos
 * teoricos por periodicidad (calculados, no materializados). Eso permite
 * que la UI muestre "pendientes" sin tener que crear filas DRAFT.
 */
@Service
public class TaxFilingService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public TaxFilingService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public List<TaxModelCatalog> listCatalog() {
        return jdbcTemplate.query("""
                SELECT code, name, description, periodicity, info_url, active
                  FROM tax_model_catalog
                 WHERE active = TRUE
                 ORDER BY code
                """, (rs, n) -> new TaxModelCatalog(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("periodicity"),
                        rs.getString("info_url"),
                        rs.getBoolean("active")));
    }

    public List<FilingView> list(Integer year, String status, String modelCode) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, tax_model_code, period_year, period_quarter, period_month,
                       status, total_amount, deadline_at, presented_at, presented_by,
                       csv_aeat, notes, created_at, updated_at
                  FROM tax_filings
                 WHERE company_id = ?
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (year != null) { sql.append(" AND period_year = ?"); args.add(year); }
        if (StringUtils.hasText(status)) { sql.append(" AND status = ?"); args.add(status); }
        if (StringUtils.hasText(modelCode)) { sql.append(" AND tax_model_code = ?"); args.add(modelCode); }
        sql.append(" ORDER BY period_year DESC, period_quarter DESC, period_month DESC, tax_model_code");
        return jdbcTemplate.query(sql.toString(), this::mapView, args.toArray());
    }

    @Transactional
    public FilingView create(UpsertRequest req) {
        validate(req);
        // Dedup: una declaración por empresa+modelo+periodo. Si ya existe (de
        // cualquier estado), la devolvemos en vez de crear un duplicado — evita
        // que "Nueva declaración" del mismo 303/130/… apile borradores repetidos.
        List<String> existing = jdbcTemplate.query("""
                SELECT id FROM tax_filings
                 WHERE company_id = ? AND tax_model_code = ? AND period_year = ?
                   AND ((period_quarter IS NULL AND ? IS NULL) OR period_quarter = ?)
                   AND ((period_month IS NULL AND ? IS NULL) OR period_month = ?)
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"),
                tenantContext.getCurrentCompanyId(), req.taxModelCode(), req.periodYear(),
                req.periodQuarter(), req.periodQuarter(),
                req.periodMonth(), req.periodMonth());
        if (!existing.isEmpty()) {
            return findById(existing.get(0));
        }
        String id = UUID.randomUUID().toString();
        LocalDate deadline = computeDeadline(req.taxModelCode(), req.periodYear(),
                req.periodQuarter(), req.periodMonth());
        jdbcTemplate.update("""
                INSERT INTO tax_filings (
                    id, company_id, tax_model_code, period_year, period_quarter,
                    period_month, status, data, total_amount, deadline_at, notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantContext.getCurrentCompanyId(),
                req.taxModelCode(), req.periodYear(),
                req.periodQuarter(), req.periodMonth(),
                StringUtils.hasText(req.status()) ? req.status() : "DRAFT",
                req.data(), req.totalAmount(),
                deadline, blankToNull(req.notes())
        );
        return findById(id);
    }

    @Transactional
    public FilingView update(String id, UpsertRequest req) {
        validate(req);
        int n = jdbcTemplate.update("""
                UPDATE tax_filings
                   SET status = ?, data = ?, total_amount = ?,
                       csv_aeat = ?, notes = ?,
                       presented_at = CASE WHEN ? = 'PRESENTED' AND presented_at IS NULL
                                            THEN CURRENT_TIMESTAMP ELSE presented_at END
                 WHERE id = ? AND company_id = ?
                """,
                req.status(), req.data(), req.totalAmount(),
                blankToNull(req.csvAeat()), blankToNull(req.notes()),
                req.status(),
                id, tenantContext.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Declaracion no encontrada");
        }
        return findById(id);
    }

    @Transactional
    public void delete(String id) {
        int n = jdbcTemplate.update("""
                DELETE FROM tax_filings
                 WHERE id = ? AND company_id = ? AND status IN ('DRAFT', 'CANCELLED')
                """, id, tenantContext.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se pueden borrar declaraciones en borrador o canceladas");
        }
    }

    /**
     * Devuelve los vencimientos teoricos del ano dado. No crea filas en
     * tax_filings — solo enumera fechas calculadas a partir de la
     * periodicidad. La UI los muestra como "pendiente" si no hay tax_filing
     * presentado para ese (modelo, periodo).
     */
    public List<DueDate> calendar(int year) {
        List<TaxModelCatalog> models = listCatalog();
        List<DueDate> out = new java.util.ArrayList<>();
        for (TaxModelCatalog m : models) {
            switch (m.periodicity()) {
                case "QUARTERLY" -> {
                    for (int q = 1; q <= 4; q++) {
                        out.add(new DueDate(m.code(), m.name(), year, q, null,
                                computeDeadline(m.code(), year, q, null)));
                    }
                }
                case "MONTHLY" -> {
                    for (int mm = 1; mm <= 12; mm++) {
                        out.add(new DueDate(m.code(), m.name(), year, null, mm,
                                computeDeadline(m.code(), year, null, mm)));
                    }
                }
                case "ANNUAL" -> out.add(new DueDate(m.code(), m.name(), year, null, null,
                        computeDeadline(m.code(), year, null, null)));
                case "BIANNUAL" -> {
                    out.add(new DueDate(m.code(), m.name(), year, 2, null,
                            computeDeadline(m.code(), year, 2, null)));
                    out.add(new DueDate(m.code(), m.name(), year, 4, null,
                            computeDeadline(m.code(), year, 4, null)));
                }
                case "AD_HOC" -> { /* 202 etc: no se genera automaticamente */ }
            }
        }
        return out;
    }

    /**
     * Plazos estandar AEAT (sin festivos: el usuario ve la fecha base y
     * decide). Para los del IVA trimestral: 20 del mes siguiente al cierre
     * de trimestre (T4 hasta 30 de enero). 303 mensual: 30 dia mes
     * siguiente. 347/390: 28-feb del ano siguiente.
     */
    private LocalDate computeDeadline(String modelCode, int year,
                                       Integer quarter, Integer month) {
        if (modelCode == null) return null;
        // Modelos anuales con plazo enero/febrero ano siguiente
        if ("390".equals(modelCode) || "347".equals(modelCode) || "180".equals(modelCode)
                || "190".equals(modelCode)) {
            return LocalDate.of(year + 1, Month.FEBRUARY, 28);
        }
        if ("100".equals(modelCode)) {
            // Renta: campana suele cerrar a 30-jun
            return LocalDate.of(year + 1, Month.JUNE, 30);
        }
        if ("200".equals(modelCode)) {
            // IS: 25 dias naturales tras los 6 meses del cierre ejercicio
            return LocalDate.of(year + 1, Month.JULY, 25);
        }
        if ("720".equals(modelCode)) {
            return LocalDate.of(year + 1, Month.MARCH, 31);
        }
        // Trimestrales: T1 20-abr, T2 20-jul, T3 20-oct, T4 30-ene siguiente
        if (quarter != null) {
            return switch (quarter) {
                case 1 -> LocalDate.of(year, Month.APRIL, 20);
                case 2 -> LocalDate.of(year, Month.JULY, 20);
                case 3 -> LocalDate.of(year, Month.OCTOBER, 20);
                case 4 -> LocalDate.of(year + 1, Month.JANUARY, 30);
                default -> null;
            };
        }
        // Mensuales: dia 30 del mes siguiente
        if (month != null) {
            return month == 12
                    ? LocalDate.of(year + 1, Month.JANUARY, 30)
                    : LocalDate.of(year, month, 1).plusMonths(1).withDayOfMonth(
                            Math.min(30, LocalDate.of(year, month, 1).plusMonths(1).lengthOfMonth()));
        }
        return null;
    }

    private FilingView findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, tax_model_code, period_year, period_quarter, period_month,
                       status, total_amount, deadline_at, presented_at, presented_by,
                       csv_aeat, notes, created_at, updated_at
                  FROM tax_filings
                 WHERE id = ? AND company_id = ?
                """, this::mapView, id, tenantContext.getCurrentCompanyId())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Declaracion no encontrada"));
    }

    private void validate(UpsertRequest req) {
        if (!StringUtils.hasText(req.taxModelCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taxModelCode requerido");
        }
        if (req.periodYear() == null || req.periodYear() < 2000 || req.periodYear() > 2100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "periodYear invalido");
        }
        if (req.periodQuarter() != null && (req.periodQuarter() < 1 || req.periodQuarter() > 4)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "periodQuarter invalido");
        }
        if (req.periodMonth() != null && (req.periodMonth() < 1 || req.periodMonth() > 12)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "periodMonth invalido");
        }
    }

    private String blankToNull(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    private FilingView mapView(ResultSet rs, int rowNum) throws SQLException {
        Timestamp pres = rs.getTimestamp("presented_at");
        Timestamp ca = rs.getTimestamp("created_at");
        Timestamp ua = rs.getTimestamp("updated_at");
        java.sql.Date d = rs.getDate("deadline_at");
        return new FilingView(
                rs.getString("id"),
                rs.getString("tax_model_code"),
                rs.getInt("period_year"),
                (Integer) rs.getObject("period_quarter"),
                (Integer) rs.getObject("period_month"),
                rs.getString("status"),
                rs.getBigDecimal("total_amount"),
                d == null ? null : d.toLocalDate(),
                pres == null ? null : pres.toInstant(),
                rs.getString("presented_by"),
                rs.getString("csv_aeat"),
                rs.getString("notes"),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant()
        );
    }

    public record FilingView(
            String id, String taxModelCode, int periodYear,
            Integer periodQuarter, Integer periodMonth, String status,
            BigDecimal totalAmount, LocalDate deadlineAt,
            Instant presentedAt, String presentedBy, String csvAeat, String notes,
            Instant createdAt, Instant updatedAt
    ) {}

    public record UpsertRequest(
            String taxModelCode, Integer periodYear, Integer periodQuarter,
            Integer periodMonth, String status, String data,
            BigDecimal totalAmount, String csvAeat, String notes
    ) {}

    public record DueDate(
            String taxModelCode, String taxModelName, int periodYear,
            Integer periodQuarter, Integer periodMonth, LocalDate deadlineAt
    ) {}

    @RestController
    @RequestMapping("/api/tax")
    @RequiresModule("accounting")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class TaxController {
        private final TaxFilingService service;

        public TaxController(TaxFilingService service) {
            this.service = service;
        }

        @GetMapping("/models")
        public List<TaxModelCatalog> models() {
            return service.listCatalog();
        }

        @GetMapping("/filings")
        public List<FilingView> filings(
                @RequestParam(value = "year", required = false) Integer year,
                @RequestParam(value = "status", required = false) String status,
                @RequestParam(value = "modelCode", required = false) String modelCode) {
            return service.list(year, status, modelCode);
        }

        @PostMapping("/filings")
        public FilingView create(@RequestBody UpsertRequest req) {
            return service.create(req);
        }

        @PutMapping("/filings/{id}")
        public FilingView update(@PathVariable("id") String id,
                                  @RequestBody UpsertRequest req) {
            return service.update(id, req);
        }

        @DeleteMapping("/filings/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable("id") String id) {
            service.delete(id);
        }

        @GetMapping("/calendar")
        public List<DueDate> calendar(@RequestParam("year") int year) {
            return service.calendar(year);
        }
    }
}
