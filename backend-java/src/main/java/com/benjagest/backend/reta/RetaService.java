package com.benjagest.backend.reta;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
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
 * RETA — Regimen Especial de Trabajadores Autonomos.
 *
 * Modelo nuevo desde 2023 (RD-Ley 13/2022): cotizacion por rendimientos
 * netos reales. Hay 15 tramos. La cuota se determina por el tramo que
 * cae el rendimiento neto previsto. La base se elige dentro del
 * intervalo del tramo.
 *
 * Aqui implementamos:
 *
 *   - CRUD de perfiles RETA.
 *   - Registro de cambios de base (hasta 6 al ano).
 *   - Helper `suggestTramo` que dado un rendimiento neto devuelve el
 *     tramo y la cuota minima 2026. Tabla embebida — cambia cada ano,
 *     se actualiza con la PGE.
 */
@Service
public class RetaService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public RetaService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public List<ProfileView> list(boolean includeInactive) {
        return jdbcTemplate.query("""
                SELECT id, owner_id, employee_id, full_name, tax_identifier,
                       social_security_number, reta_start_date, reta_end_date,
                       pluriactividad, tarifa_plana, tarifa_plana_until,
                       activity_code, activity_description, iae_epigraph,
                       expected_net_income, current_base, current_quota,
                       notes, active, created_at, updated_at
                  FROM reta_profiles
                 WHERE company_id = ?
                   AND (? = TRUE OR active = TRUE)
                 ORDER BY active DESC, full_name
                """, this::mapProfile,
                tenantContext.getCurrentCompanyId(), includeInactive);
    }

    @Transactional
    public ProfileView create(UpsertProfile req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO reta_profiles (
                    id, company_id, owner_id, employee_id, full_name,
                    tax_identifier, social_security_number,
                    reta_start_date, reta_end_date,
                    pluriactividad, tarifa_plana, tarifa_plana_until,
                    activity_code, activity_description, iae_epigraph,
                    expected_net_income, current_base, current_quota,
                    notes, active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantContext.getCurrentCompanyId(),
                blank(req.ownerId()), blank(req.employeeId()), req.fullName(),
                blank(req.taxIdentifier()), blank(req.socialSecurityNumber()),
                req.retaStartDate(), req.retaEndDate(),
                req.pluriactividad() != null && req.pluriactividad(),
                req.tarifaPlana() != null && req.tarifaPlana(),
                req.tarifaPlanaUntil(),
                blank(req.activityCode()), blank(req.activityDescription()),
                blank(req.iaeEpigraph()),
                req.expectedNetIncome(), req.currentBase(), req.currentQuota(),
                blank(req.notes()),
                req.active() == null || req.active()
        );
        return findById(id);
    }

    @Transactional
    public ProfileView update(String id, UpsertProfile req) {
        validate(req);
        int n = jdbcTemplate.update("""
                UPDATE reta_profiles
                   SET full_name = ?, tax_identifier = ?, social_security_number = ?,
                       reta_start_date = ?, reta_end_date = ?,
                       pluriactividad = ?, tarifa_plana = ?, tarifa_plana_until = ?,
                       activity_code = ?, activity_description = ?, iae_epigraph = ?,
                       expected_net_income = ?, current_base = ?, current_quota = ?,
                       notes = ?, active = ?
                 WHERE id = ? AND company_id = ?
                """,
                req.fullName(), blank(req.taxIdentifier()), blank(req.socialSecurityNumber()),
                req.retaStartDate(), req.retaEndDate(),
                req.pluriactividad() != null && req.pluriactividad(),
                req.tarifaPlana() != null && req.tarifaPlana(),
                req.tarifaPlanaUntil(),
                blank(req.activityCode()), blank(req.activityDescription()),
                blank(req.iaeEpigraph()),
                req.expectedNetIncome(), req.currentBase(), req.currentQuota(),
                blank(req.notes()),
                req.active() == null || req.active(),
                id, tenantContext.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Perfil no encontrado");
        return findById(id);
    }

    @Transactional
    public void delete(String id) {
        int n = jdbcTemplate.update("""
                UPDATE reta_profiles SET active = FALSE,
                       reta_end_date = COALESCE(reta_end_date, CURRENT_DATE())
                 WHERE id = ? AND company_id = ?
                """, id, tenantContext.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Perfil no encontrado");
    }

    private ProfileView findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, owner_id, employee_id, full_name, tax_identifier,
                       social_security_number, reta_start_date, reta_end_date,
                       pluriactividad, tarifa_plana, tarifa_plana_until,
                       activity_code, activity_description, iae_epigraph,
                       expected_net_income, current_base, current_quota,
                       notes, active, created_at, updated_at
                  FROM reta_profiles
                 WHERE id = ? AND company_id = ?
                """, this::mapProfile, id, tenantContext.getCurrentCompanyId())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Perfil no encontrado"));
    }

    // -------- cambios de base --------

    public List<BaseChangeView> listChanges(String profileId, int year) {
        return jdbcTemplate.query("""
                SELECT id, profile_id, effective_date, change_reason,
                       new_base, new_quota, expected_net_income,
                       submitted_to_ss, submitted_at, notes, created_at
                  FROM reta_base_changes
                 WHERE company_id = ? AND profile_id = ?
                   AND YEAR(effective_date) = ?
                 ORDER BY effective_date DESC
                """,
                (rs, n) -> mapChange(rs),
                tenantContext.getCurrentCompanyId(), profileId, year);
    }

    @Transactional
    public BaseChangeView createChange(String profileId, UpsertChange req) {
        if (req.newBase() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "newBase requerido");
        }
        // Hasta 6 cambios al ano (limite legal)
        int year = req.effectiveDate() == null ? Year.now().getValue() : req.effectiveDate().getYear();
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM reta_base_changes
                 WHERE company_id = ? AND profile_id = ? AND YEAR(effective_date) = ?
                """, Integer.class,
                tenantContext.getCurrentCompanyId(), profileId, year);
        if (count != null && count >= 6) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Limite de 6 cambios de base anuales alcanzado para " + year);
        }

        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO reta_base_changes (
                    id, company_id, profile_id, effective_date,
                    change_reason, new_base, new_quota, expected_net_income,
                    submitted_to_ss, notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantContext.getCurrentCompanyId(), profileId,
                req.effectiveDate(), blank(req.changeReason()),
                req.newBase(), req.newQuota(), req.expectedNetIncome(),
                req.submittedToSs() != null && req.submittedToSs(),
                blank(req.notes()));

        // Tambien actualizamos el perfil con la nueva base/cuota actual
        jdbcTemplate.update("""
                UPDATE reta_profiles
                   SET current_base = ?, current_quota = COALESCE(?, current_quota),
                       expected_net_income = COALESCE(?, expected_net_income)
                 WHERE id = ? AND company_id = ?
                """,
                req.newBase(), req.newQuota(), req.expectedNetIncome(),
                profileId, tenantContext.getCurrentCompanyId());

        return jdbcTemplate.query("""
                SELECT id, profile_id, effective_date, change_reason,
                       new_base, new_quota, expected_net_income,
                       submitted_to_ss, submitted_at, notes, created_at
                  FROM reta_base_changes WHERE id = ? AND company_id = ?
                """, (rs, n) -> mapChange(rs), id, tenantContext.getCurrentCompanyId())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cambio no encontrado"));
    }

    /**
     * Devuelve el tramo RETA recomendado para 2026 dado un rendimiento
     * neto anual previsto. Tabla embebida segun PGE 2026 (placeholder
     * con valores 2025 — confirmar al publicarse BOE 2026).
     */
    public TramoSuggestion suggestTramo(BigDecimal annualNetIncome) {
        if (annualNetIncome == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "annualNetIncome requerido");
        }
        // Tabla 2025 (valores transitorios hasta PGE 2026):
        // 15 tramos. Cada uno tiene baseMin, baseMax, cuotaMinima.
        // Fuente: Resolucion 17-01-2025 BOE, art. 308 LGSS.
        BigDecimal monthly = annualNetIncome.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
        Object[][] tramos = {
                {"Tramo 1 reducida", new BigDecimal("670.00"), new BigDecimal("718.85"), new BigDecimal("216.78")},
                {"Tramo 2 reducida", new BigDecimal("827.50"), new BigDecimal("899.74"), new BigDecimal("267.59")},
                {"Tramo 3 reducida", new BigDecimal("952.59"), new BigDecimal("1167.00"), new BigDecimal("303.88")},
                {"Tramo 1 general", new BigDecimal("960.78"), new BigDecimal("1143.79"), new BigDecimal("298.13")},
                {"Tramo 2 general", new BigDecimal("960.78"), new BigDecimal("1209.39"), new BigDecimal("314.39")},
                {"Tramo 3 general", new BigDecimal("960.78"), new BigDecimal("1272.87"), new BigDecimal("325.18")},
                {"Tramo 4 general", new BigDecimal("1013.07"), new BigDecimal("1336.35"), new BigDecimal("335.97")},
                {"Tramo 5 general", new BigDecimal("1029.41"), new BigDecimal("1454.25"), new BigDecimal("366.21")},
                {"Tramo 6 general", new BigDecimal("1045.75"), new BigDecimal("1700.32"), new BigDecimal("428.99")},
                {"Tramo 7 general", new BigDecimal("1078.43"), new BigDecimal("1900.10"), new BigDecimal("478.85")},
                {"Tramo 8 general", new BigDecimal("1143.79"), new BigDecimal("2030.91"), new BigDecimal("511.41")},
                {"Tramo 9 general", new BigDecimal("1209.15"), new BigDecimal("2346.18"), new BigDecimal("594.40")},
                {"Tramo 10 general", new BigDecimal("1274.51"), new BigDecimal("2660.43"), new BigDecimal("674.40")},
                {"Tramo 11 general", new BigDecimal("1356.21"), new BigDecimal("2994.95"), new BigDecimal("754.84")},
                {"Tramo 12 general", new BigDecimal("1437.91"), new BigDecimal("4720.50"), new BigDecimal("1191.40")}
        };
        // Cae en el tramo cuyo limite superior (rendimiento mensual) cubre el monthly
        String[] thresholds = {"670", "900", "1167", "1300", "1500", "1700", "1850", "2030", "2330", "2760", "3190", "3620", "4050", "6000", "9999"};
        int idx = 0;
        for (int i = 0; i < thresholds.length; i++) {
            BigDecimal th = new BigDecimal(thresholds[i]);
            if (monthly.compareTo(th) <= 0) { idx = i; break; }
            idx = i;
        }
        Object[] t = tramos[Math.min(idx, tramos.length - 1)];
        return new TramoSuggestion(
                (String) t[0], (BigDecimal) t[1], (BigDecimal) t[2], (BigDecimal) t[3],
                annualNetIncome, monthly);
    }

    private String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    private void validate(UpsertProfile req) {
        if (!StringUtils.hasText(req.fullName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fullName requerido");
        }
        if (req.ownerId() != null && req.employeeId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Un perfil RETA solo puede enlazar con owner O empleado, no ambos");
        }
    }

    private ProfileView mapProfile(ResultSet rs, int rowNum) throws SQLException {
        return new ProfileView(
                rs.getString("id"),
                rs.getString("owner_id"),
                rs.getString("employee_id"),
                rs.getString("full_name"),
                rs.getString("tax_identifier"),
                rs.getString("social_security_number"),
                toLocalDate(rs.getDate("reta_start_date")),
                toLocalDate(rs.getDate("reta_end_date")),
                rs.getBoolean("pluriactividad"),
                rs.getBoolean("tarifa_plana"),
                toLocalDate(rs.getDate("tarifa_plana_until")),
                rs.getString("activity_code"),
                rs.getString("activity_description"),
                rs.getString("iae_epigraph"),
                rs.getBigDecimal("expected_net_income"),
                rs.getBigDecimal("current_base"),
                rs.getBigDecimal("current_quota"),
                rs.getString("notes"),
                rs.getBoolean("active"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private BaseChangeView mapChange(ResultSet rs) throws SQLException {
        return new BaseChangeView(
                rs.getString("id"),
                rs.getString("profile_id"),
                toLocalDate(rs.getDate("effective_date")),
                rs.getString("change_reason"),
                rs.getBigDecimal("new_base"),
                rs.getBigDecimal("new_quota"),
                rs.getBigDecimal("expected_net_income"),
                rs.getBoolean("submitted_to_ss"),
                toInstant(rs.getTimestamp("submitted_at")),
                rs.getString("notes"),
                toInstant(rs.getTimestamp("created_at"))
        );
    }

    private LocalDate toLocalDate(java.sql.Date d) { return d == null ? null : d.toLocalDate(); }
    private Instant toInstant(java.sql.Timestamp t) { return t == null ? null : t.toInstant(); }

    public record ProfileView(
            String id, String ownerId, String employeeId, String fullName,
            String taxIdentifier, String socialSecurityNumber,
            LocalDate retaStartDate, LocalDate retaEndDate,
            boolean pluriactividad, boolean tarifaPlana, LocalDate tarifaPlanaUntil,
            String activityCode, String activityDescription, String iaeEpigraph,
            BigDecimal expectedNetIncome, BigDecimal currentBase, BigDecimal currentQuota,
            String notes, boolean active, Instant createdAt, Instant updatedAt
    ) {}

    public record UpsertProfile(
            String ownerId, String employeeId, String fullName,
            String taxIdentifier, String socialSecurityNumber,
            LocalDate retaStartDate, LocalDate retaEndDate,
            Boolean pluriactividad, Boolean tarifaPlana, LocalDate tarifaPlanaUntil,
            String activityCode, String activityDescription, String iaeEpigraph,
            BigDecimal expectedNetIncome, BigDecimal currentBase, BigDecimal currentQuota,
            String notes, Boolean active
    ) {}

    public record BaseChangeView(
            String id, String profileId, LocalDate effectiveDate,
            String changeReason, BigDecimal newBase, BigDecimal newQuota,
            BigDecimal expectedNetIncome, boolean submittedToSs,
            Instant submittedAt, String notes, Instant createdAt
    ) {}

    public record UpsertChange(
            LocalDate effectiveDate, String changeReason,
            BigDecimal newBase, BigDecimal newQuota, BigDecimal expectedNetIncome,
            Boolean submittedToSs, String notes
    ) {}

    public record TramoSuggestion(
            String tramoLabel, BigDecimal baseMinima, BigDecimal baseMaxima,
            BigDecimal cuotaMinima, BigDecimal annualNetIncome, BigDecimal monthlyIncome
    ) {}

    @RestController
    @RequestMapping("/api/reta")
    @RequiresModule("self-employed")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class RetaController {
        private final RetaService service;

        public RetaController(RetaService service) { this.service = service; }

        @GetMapping("/profiles")
        public List<ProfileView> list(@RequestParam(value = "includeInactive", defaultValue = "false") boolean inc) {
            return service.list(inc);
        }

        @PostMapping("/profiles")
        public ProfileView create(@RequestBody UpsertProfile req) { return service.create(req); }

        @PutMapping("/profiles/{id}")
        public ProfileView update(@PathVariable("id") String id, @RequestBody UpsertProfile req) {
            return service.update(id, req);
        }

        @DeleteMapping("/profiles/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable("id") String id) { service.delete(id); }

        @GetMapping("/profiles/{id}/changes")
        public List<BaseChangeView> listChanges(@PathVariable("id") String id,
                                                 @RequestParam(value = "year", required = false) Integer year) {
            return service.listChanges(id, year == null ? Year.now().getValue() : year);
        }

        @PostMapping("/profiles/{id}/changes")
        public BaseChangeView createChange(@PathVariable("id") String id,
                                            @RequestBody UpsertChange req) {
            return service.createChange(id, req);
        }

        @GetMapping("/tramos/suggest")
        public TramoSuggestion suggest(@RequestParam("annualNetIncome") BigDecimal annual) {
            return service.suggestTramo(annual);
        }
    }
}
