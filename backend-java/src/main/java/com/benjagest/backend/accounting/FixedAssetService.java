package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Inmovilizado (RD 1514/2007 grupo 2). CRUD + cálculo de amortización
 * lineal.
 *
 * Amortización lineal:
 *   cuota_anual = (coste - valor_residual) / vida_útil_años
 *   cuota_mensual = cuota_anual / 12
 *
 * El servicio expone:
 *   - CRUD básico sobre fixed_assets.
 *   - calculateMonthDepreciations(year, month) para todos los assets
 *     activos del periodo.
 *   - depreciationHistory(assetId) para inspección.
 *
 * Lo que NO hace (sub-slice futuro):
 *   - Método degresivo / suma de dígitos.
 *   - Generación automática de asientos contables (necesita módulo
 *     contable real).
 *   - Bajas con cálculo de pérdida/ganancia.
 */
@Service
public class FixedAssetService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public FixedAssetService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public List<AssetView> list(boolean includeInactive) {
        return jdbcTemplate.query("""
                SELECT id, code, name, description, category, accounting_account_id,
                       acquisition_date, acquisition_cost, residual_value,
                       useful_life_years, depreciation_method,
                       in_service_date, disposed_at, disposal_reason, disposal_value,
                       supplier_name, invoice_reference, notes, active,
                       created_at, updated_at
                  FROM fixed_assets
                 WHERE company_id = ? AND (? = TRUE OR active = TRUE)
                 ORDER BY active DESC, acquisition_date DESC, name
                """, this::mapAsset,
                tenantContext.getCurrentCompanyId(), includeInactive);
    }

    @Transactional
    public AssetView create(UpsertRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO fixed_assets (
                    id, company_id, code, name, description, category, accounting_account_id,
                    acquisition_date, acquisition_cost, residual_value,
                    useful_life_years, depreciation_method,
                    in_service_date, supplier_name, invoice_reference, notes, active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantContext.getCurrentCompanyId(),
                req.code(), req.name(), blank(req.description()),
                req.category(), blank(req.accountingAccountId()),
                req.acquisitionDate(), req.acquisitionCost(),
                req.residualValue() == null ? BigDecimal.ZERO : req.residualValue(),
                req.usefulLifeYears(), defaultMethod(req.depreciationMethod()),
                req.inServiceDate(),
                blank(req.supplierName()), blank(req.invoiceReference()),
                blank(req.notes()), req.active() == null || req.active());
        return findById(id);
    }

    @Transactional
    public AssetView update(String id, UpsertRequest req) {
        validate(req);
        int n = jdbcTemplate.update("""
                UPDATE fixed_assets
                   SET code = ?, name = ?, description = ?, category = ?,
                       accounting_account_id = ?,
                       acquisition_date = ?, acquisition_cost = ?, residual_value = ?,
                       useful_life_years = ?, depreciation_method = ?,
                       in_service_date = ?, supplier_name = ?, invoice_reference = ?,
                       notes = ?, active = ?
                 WHERE id = ? AND company_id = ?
                """,
                req.code(), req.name(), blank(req.description()),
                req.category(), blank(req.accountingAccountId()),
                req.acquisitionDate(), req.acquisitionCost(),
                req.residualValue() == null ? BigDecimal.ZERO : req.residualValue(),
                req.usefulLifeYears(), defaultMethod(req.depreciationMethod()),
                req.inServiceDate(),
                blank(req.supplierName()), blank(req.invoiceReference()),
                blank(req.notes()), req.active() == null || req.active(),
                id, tenantContext.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Inmovilizado no encontrado");
        return findById(id);
    }

    @Transactional
    public AssetView dispose(String id, DisposeRequest req) {
        int n = jdbcTemplate.update("""
                UPDATE fixed_assets
                   SET disposed_at = ?, disposal_reason = ?, disposal_value = ?,
                       active = FALSE
                 WHERE id = ? AND company_id = ?
                """,
                req.disposedAt(), blank(req.disposalReason()), req.disposalValue(),
                id, tenantContext.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Inmovilizado no encontrado");
        return findById(id);
    }

    @Transactional
    public void delete(String id) {
        Integer hasDep = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fixed_asset_depreciations WHERE asset_id = ? AND company_id = ?",
                Integer.class, id, tenantContext.getCurrentCompanyId());
        if (hasDep != null && hasDep > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tiene cuotas de amortizacion calculadas. Da de baja en su lugar.");
        }
        int n = jdbcTemplate.update("""
                DELETE FROM fixed_assets WHERE id = ? AND company_id = ?
                """, id, tenantContext.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Inmovilizado no encontrado");
    }

    /**
     * Calcula y persiste la amortización mensual para todos los activos
     * activos cuya fecha de puesta en servicio sea ≤ fin de mes. Si ya
     * existe una linea para ese (asset, year, month), la salta.
     *
     * @return numero de lineas creadas.
     */
    @Transactional
    public int calculateMonthDepreciations(int year, int month) {
        if (month < 1 || month > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month invalido");
        }
        LocalDate periodEnd = LocalDate.of(year, month, 1)
                .plusMonths(1).minusDays(1);
        List<AssetForCalc> assets = jdbcTemplate.query("""
                SELECT id, acquisition_date, in_service_date,
                       acquisition_cost, residual_value, useful_life_years,
                       depreciation_method, disposed_at
                  FROM fixed_assets
                 WHERE company_id = ? AND active = TRUE
                   AND depreciation_method != 'NONE'
                   AND COALESCE(in_service_date, acquisition_date) <= ?
                   AND (disposed_at IS NULL OR disposed_at > ?)
                """,
                (rs, n) -> {
                    AssetForCalc a = new AssetForCalc();
                    a.id = rs.getString("id");
                    java.sql.Date acq = rs.getDate("acquisition_date");
                    java.sql.Date svc = rs.getDate("in_service_date");
                    a.startDate = (svc != null ? svc : acq).toLocalDate();
                    a.cost = rs.getBigDecimal("acquisition_cost");
                    a.residual = rs.getBigDecimal("residual_value");
                    a.lifeYears = rs.getBigDecimal("useful_life_years");
                    a.method = rs.getString("depreciation_method");
                    return a;
                },
                tenantContext.getCurrentCompanyId(), periodEnd, periodEnd);

        int created = 0;
        for (AssetForCalc a : assets) {
            Integer exists = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM fixed_asset_depreciations
                     WHERE asset_id = ? AND period_year = ? AND period_month = ?
                    """, Integer.class, a.id, year, month);
            if (exists != null && exists > 0) continue;

            // Cuota mensual = (cost - residual) / (life * 12)
            BigDecimal depreciable = a.cost.subtract(
                    a.residual == null ? BigDecimal.ZERO : a.residual);
            BigDecimal months = a.lifeYears.multiply(BigDecimal.valueOf(12));
            if (months.signum() <= 0) continue;
            BigDecimal monthly = depreciable.divide(months, 2, RoundingMode.HALF_UP);

            // Acumulada anterior
            BigDecimal accumulatedPrev = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(MAX(accumulated_amount), 0)
                      FROM fixed_asset_depreciations
                     WHERE asset_id = ?
                       AND (period_year < ? OR (period_year = ? AND period_month < ?))
                    """, BigDecimal.class, a.id, year, year, month);
            if (accumulatedPrev == null) accumulatedPrev = BigDecimal.ZERO;

            // Tope: no superar el coste amortizable
            BigDecimal remaining = depreciable.subtract(accumulatedPrev);
            if (remaining.signum() <= 0) continue;
            if (monthly.compareTo(remaining) > 0) monthly = remaining;

            BigDecimal accumulated = accumulatedPrev.add(monthly);
            BigDecimal netBook = a.cost.subtract(accumulated);

            jdbcTemplate.update("""
                    INSERT INTO fixed_asset_depreciations (
                        id, company_id, asset_id, period_year, period_month,
                        depreciation_amount, accumulated_amount, net_book_value,
                        status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CALCULATED')
                    """,
                    UUID.randomUUID().toString(),
                    tenantContext.getCurrentCompanyId(),
                    a.id, year, month, monthly, accumulated, netBook);
            created++;
        }
        return created;
    }

    public List<DepreciationView> history(String assetId) {
        return jdbcTemplate.query("""
                SELECT id, asset_id, period_year, period_month,
                       depreciation_amount, accumulated_amount, net_book_value,
                       status, journal_entry_id, created_at
                  FROM fixed_asset_depreciations
                 WHERE company_id = ? AND asset_id = ?
                 ORDER BY period_year DESC, period_month DESC
                """, (rs, n) -> new DepreciationView(
                        rs.getString("id"), rs.getString("asset_id"),
                        rs.getInt("period_year"),
                        (Integer) rs.getObject("period_month"),
                        rs.getBigDecimal("depreciation_amount"),
                        rs.getBigDecimal("accumulated_amount"),
                        rs.getBigDecimal("net_book_value"),
                        rs.getString("status"),
                        rs.getString("journal_entry_id"),
                        toInstant(rs.getTimestamp("created_at"))
                ), tenantContext.getCurrentCompanyId(), assetId);
    }

    private AssetView findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, code, name, description, category, accounting_account_id,
                       acquisition_date, acquisition_cost, residual_value,
                       useful_life_years, depreciation_method,
                       in_service_date, disposed_at, disposal_reason, disposal_value,
                       supplier_name, invoice_reference, notes, active,
                       created_at, updated_at
                  FROM fixed_assets
                 WHERE id = ? AND company_id = ?
                """, this::mapAsset, id, tenantContext.getCurrentCompanyId())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inmovilizado no encontrado"));
    }

    private void validate(UpsertRequest req) {
        if (!StringUtils.hasText(req.code())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code requerido");
        if (!StringUtils.hasText(req.name())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name requerido");
        if (req.acquisitionCost() == null || req.acquisitionCost().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "acquisitionCost > 0");
        }
        if (req.usefulLifeYears() == null || req.usefulLifeYears().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "usefulLifeYears > 0");
        }
        if (req.acquisitionDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "acquisitionDate requerido");
        }
    }

    private String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    private String defaultMethod(String m) {
        return StringUtils.hasText(m) ? m : "LINEAR";
    }

    private AssetView mapAsset(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Date acq = rs.getDate("acquisition_date");
        java.sql.Date svc = rs.getDate("in_service_date");
        java.sql.Date dsp = rs.getDate("disposed_at");
        Timestamp ca = rs.getTimestamp("created_at");
        Timestamp ua = rs.getTimestamp("updated_at");
        return new AssetView(
                rs.getString("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getString("category"),
                rs.getString("accounting_account_id"),
                acq == null ? null : acq.toLocalDate(),
                rs.getBigDecimal("acquisition_cost"),
                rs.getBigDecimal("residual_value"),
                rs.getBigDecimal("useful_life_years"),
                rs.getString("depreciation_method"),
                svc == null ? null : svc.toLocalDate(),
                dsp == null ? null : dsp.toLocalDate(),
                rs.getString("disposal_reason"),
                rs.getBigDecimal("disposal_value"),
                rs.getString("supplier_name"),
                rs.getString("invoice_reference"),
                rs.getString("notes"),
                rs.getBoolean("active"),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant()
        );
    }

    private Instant toInstant(Timestamp t) { return t == null ? null : t.toInstant(); }

    private static class AssetForCalc {
        String id;
        LocalDate startDate;
        BigDecimal cost;
        BigDecimal residual;
        BigDecimal lifeYears;
        String method;
    }

    public record AssetView(
            String id, String code, String name, String description, String category,
            String accountingAccountId,
            LocalDate acquisitionDate, BigDecimal acquisitionCost, BigDecimal residualValue,
            BigDecimal usefulLifeYears, String depreciationMethod,
            LocalDate inServiceDate, LocalDate disposedAt, String disposalReason,
            BigDecimal disposalValue,
            String supplierName, String invoiceReference, String notes,
            boolean active, Instant createdAt, Instant updatedAt
    ) {}

    public record DepreciationView(
            String id, String assetId, int periodYear, Integer periodMonth,
            BigDecimal depreciationAmount, BigDecimal accumulatedAmount, BigDecimal netBookValue,
            String status, String journalEntryId, Instant createdAt
    ) {}

    public record UpsertRequest(
            String code, String name, String description, String category,
            String accountingAccountId,
            LocalDate acquisitionDate, BigDecimal acquisitionCost, BigDecimal residualValue,
            BigDecimal usefulLifeYears, String depreciationMethod,
            LocalDate inServiceDate,
            String supplierName, String invoiceReference, String notes,
            Boolean active
    ) {}

    public record DisposeRequest(LocalDate disposedAt, String disposalReason, BigDecimal disposalValue) {}

    @RestController
    @RequestMapping("/api/accounting/fixed-assets")
    @RequiresModule("accounting")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class FixedAssetController {
        private final FixedAssetService service;
        public FixedAssetController(FixedAssetService service) { this.service = service; }

        @GetMapping
        public List<AssetView> list(@RequestParam(value = "includeInactive", defaultValue = "false") boolean inc) {
            return service.list(inc);
        }

        @PostMapping
        public AssetView create(@RequestBody UpsertRequest req) { return service.create(req); }

        @PutMapping("/{id}")
        public AssetView update(@PathVariable("id") String id, @RequestBody UpsertRequest req) {
            return service.update(id, req);
        }

        @PutMapping("/{id}/dispose")
        public AssetView dispose(@PathVariable("id") String id, @RequestBody DisposeRequest req) {
            return service.dispose(id, req);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable("id") String id) { service.delete(id); }

        @PostMapping("/calculate-month")
        public int calculate(@RequestParam("year") int year, @RequestParam("month") int month) {
            return service.calculateMonthDepreciations(year, month);
        }

        @GetMapping("/{id}/depreciations")
        public List<DepreciationView> history(@PathVariable("id") String id) {
            return service.history(id);
        }
    }
}
