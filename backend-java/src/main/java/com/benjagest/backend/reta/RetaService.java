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
     * Devuelve el tramo RETA recomendado para el año en curso dado un
     * rendimiento neto anual. Lee la tabla {@code reta_tramos} de BD
     * (RETA-0: ya NO está hardcodeada; se edita por año desde la UI).
     */
    public TramoSuggestion suggestTramo(BigDecimal annualNetIncome) {
        return suggestTramo(Year.now().getValue(), annualNetIncome);
    }

    /** Como {@link #suggestTramo(BigDecimal)} pero para un año concreto. */
    public TramoSuggestion suggestTramo(int year, BigDecimal annualNetIncome) {
        if (annualNetIncome == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "annualNetIncome requerido");
        }
        BigDecimal monthly = annualNetIncome.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
        List<TramoRow> tramos = getTramos(year);
        if (tramos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No hay tramos RETA configurados para " + year
                            + ". Define o clona los tramos del año en la UI.");
        }
        // Cae en el primer tramo cuyo límite superior (rendimiento mensual) cubre
        // el monthly; si lo supera todos, el último tramo.
        TramoRow chosen = tramos.get(tramos.size() - 1);
        for (TramoRow tr : tramos) {
            if (monthly.compareTo(tr.incomeMaxMonthly()) <= 0) { chosen = tr; break; }
        }
        return new TramoSuggestion(
                chosen.label(), chosen.baseMin(), chosen.baseMax(), chosen.quotaMin(),
                annualNetIncome, monthly);
    }

    // ====================================================================
    //  RETA-0 — Tramos por año (tabla reta_tramos, editable desde la UI)
    // ====================================================================

    /** Años con tramos definidos (desc). */
    public List<Integer> listTramoYears() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT year_number FROM reta_tramos ORDER BY year_number DESC",
                Integer.class);
    }

    /** Tramos de un año, ordenados por {@code ord} (rendimiento creciente). */
    public List<TramoRow> getTramos(int year) {
        return jdbcTemplate.query("""
                SELECT id, year_number, ord, label, income_max_monthly,
                       base_min, base_max, quota_min
                  FROM reta_tramos
                 WHERE year_number = ?
                 ORDER BY ord
                """, (rs, n) -> new TramoRow(
                        rs.getString("id"), rs.getInt("year_number"), rs.getInt("ord"),
                        rs.getString("label"), rs.getBigDecimal("income_max_monthly"),
                        rs.getBigDecimal("base_min"), rs.getBigDecimal("base_max"),
                        rs.getBigDecimal("quota_min")),
                year);
    }

    /** Reemplaza por completo los tramos de un año (borra e inserta). */
    @org.springframework.transaction.annotation.Transactional
    public List<TramoRow> saveTramos(int year, List<TramoRow> rows) {
        jdbcTemplate.update("DELETE FROM reta_tramos WHERE year_number = ?", year);
        int ord = 0;
        for (TramoRow r : rows) {
            jdbcTemplate.update("""
                    INSERT INTO reta_tramos (id, year_number, ord, label,
                        income_max_monthly, base_min, base_max, quota_min)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), year, ord++,
                    blank(r.label()) == null ? ("Tramo " + ord) : r.label(),
                    r.incomeMaxMonthly() == null ? BigDecimal.ZERO : r.incomeMaxMonthly(),
                    r.baseMin() == null ? BigDecimal.ZERO : r.baseMin(),
                    r.baseMax() == null ? BigDecimal.ZERO : r.baseMax(),
                    r.quotaMin() == null ? BigDecimal.ZERO : r.quotaMin());
        }
        return getTramos(year);
    }

    /** Clona los tramos de {@code fromYear} en {@code toYear} (si éste no existe). */
    @org.springframework.transaction.annotation.Transactional
    public List<TramoRow> cloneTramos(int fromYear, int toYear) {
        if (!getTramos(toYear).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El año " + toYear + " ya tiene tramos. Bórralos antes de clonar.");
        }
        List<TramoRow> src = getTramos(fromYear);
        if (src.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El año origen " + fromYear + " no tiene tramos.");
        }
        return saveTramos(toYear, src);
    }

    public record TramoRow(
            String id, int year, int ord, String label,
            BigDecimal incomeMaxMonthly, BigDecimal baseMin,
            BigDecimal baseMax, BigDecimal quotaMin
    ) {}

    // ====================================================================
    //  RETA-2 — Auto-asegurar perfil del autónomo (owner con régimen RETA)
    // ====================================================================

    /**
     * Crea (si faltan) perfiles RETA para los titulares de la empresa que
     * cotizan en RETA (company_owners.ss_regime IN RETA/AUTONOMO_SOCIETARIO).
     * Idempotente. No toca sociedades sin autónomo. Devuelve cuántos creó.
     */
    /** Asegura perfiles de titulares RETA de la empresa actual (tenant). */
    public int ensureOwnerProfilesForCurrent() {
        return ensureOwnerProfiles(tenantContext.getCurrentCompanyId());
    }

    @org.springframework.transaction.annotation.Transactional
    public int ensureOwnerProfiles(String companyId) {
        List<java.util.Map<String, Object>> owners = jdbcTemplate.queryForList("""
                SELECT o.id, o.full_name, o.tax_identifier
                  FROM company_owners o
                 WHERE o.company_id = ? AND o.active = TRUE
                   AND o.ss_regime IN ('RETA', 'AUTONOMO_SOCIETARIO')
                   AND NOT EXISTS (
                       SELECT 1 FROM reta_profiles p
                        WHERE p.company_id = o.company_id AND p.owner_id = o.id)
                """, companyId);
        int created = 0;
        for (var o : owners) {
            jdbcTemplate.update("""
                    INSERT INTO reta_profiles (id, company_id, owner_id, full_name,
                        tax_identifier, active)
                    VALUES (?, ?, ?, ?, ?, TRUE)
                    """,
                    UUID.randomUUID().toString(), companyId, (String) o.get("id"),
                    (String) o.get("full_name"), (String) o.get("tax_identifier"));
            created++;
        }
        return created;
    }

    // ====================================================================
    //  RETA-3 — Alerta de regularización (base vs tramo por rendimiento REAL)
    // ====================================================================

    /**
     * Recorre la empresa actual + su cartera (companies.parent_company_id =
     * empresa actual) y marca los autónomos cuya base de cotización está fuera
     * del tramo que les corresponde por su RENDIMIENTO REAL (P&L del ejercicio:
     * grupos 7xx haber − 6xx debe en asientos POSTED). Decisión Benjamin
     * 2026-06-15. Auto-asegura perfiles de titulares RETA antes de revisar.
     */
    public List<RegularizationAlert> scanRegularization(int year) {
        String selfId = tenantContext.getCurrentCompanyId();
        // Empresa propia + cartera gestionada.
        List<java.util.Map<String, Object>> companies = new java.util.ArrayList<>();
        java.util.Map<String, Object> self = new java.util.HashMap<>();
        self.put("id", selfId);
        self.put("name", jdbcTemplate.query(
                "SELECT COALESCE(trade_name, legal_name) AS n FROM companies WHERE id = ?",
                (rs, n) -> rs.getString("n"), selfId).stream().findFirst().orElse("—"));
        companies.add(self);
        companies.addAll(jdbcTemplate.queryForList("""
                SELECT id, COALESCE(trade_name, legal_name) AS name
                  FROM companies
                 WHERE parent_company_id = ?
                 ORDER BY name
                """, selfId));

        java.sql.Date from = java.sql.Date.valueOf(java.time.LocalDate.of(year, 1, 1));
        java.sql.Date to = java.sql.Date.valueOf(java.time.LocalDate.of(year, 12, 31));

        List<RegularizationAlert> out = new java.util.ArrayList<>();
        for (var co : companies) {
            String companyId = (String) co.get("id");
            String companyName = (String) co.get("name");
            try { ensureOwnerProfiles(companyId); } catch (Exception ignored) { /* best-effort */ }

            List<java.util.Map<String, Object>> profiles = jdbcTemplate.queryForList("""
                    SELECT id, full_name, current_base
                      FROM reta_profiles
                     WHERE company_id = ? AND active = TRUE
                    """, companyId);
            if (profiles.isEmpty()) continue;

            BigDecimal income = sumGroup(companyId, from, to, "7", true);   // ventas (haber)
            BigDecimal expense = sumGroup(companyId, from, to, "6", false); // gastos (debe)
            // Sin contabilidad ese año (sin movimientos 6xx/7xx) NO se puede
            // estimar el rendimiento real → se omite para no dar falsos avisos.
            if (income.signum() == 0 && expense.signum() == 0) continue;
            BigDecimal net = income.subtract(expense);
            TramoSuggestion tr;
            try {
                tr = suggestTramo(year, net);
            } catch (Exception ex) {
                continue; // sin tramos para ese año → no se puede evaluar
            }

            for (var p : profiles) {
                BigDecimal base = (BigDecimal) p.get("current_base");
                String fullName = (String) p.get("full_name");
                String status;
                if (base == null || base.signum() == 0) {
                    status = "NO_BASE";
                } else if (base.compareTo(tr.baseMinima()) < 0) {
                    status = "UNDER"; // cotiza por debajo → regularización a pagar
                } else if (base.compareTo(tr.baseMaxima()) > 0) {
                    status = "OVER";  // cotiza por encima → paga de más
                } else {
                    continue; // base dentro del tramo → OK
                }
                out.add(new RegularizationAlert(
                        companyId, companyName, (String) p.get("id"), fullName,
                        base, net, tr.tramoLabel(), tr.baseMinima(), tr.baseMaxima(), status));
            }
        }
        return out;
    }

    private BigDecimal sumGroup(String companyId, java.sql.Date from, java.sql.Date to,
                                 String accountPrefix, boolean credit) {
        String col = credit ? "l.credit" : "l.debit";
        BigDecimal v = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(" + col + "), 0) "
                + "  FROM journal_entry_lines l "
                + "  JOIN journal_entries e ON e.id = l.journal_entry_id "
                + "  JOIN accounting_accounts a ON a.id = l.account_id "
                + " WHERE e.company_id = ? AND e.status = 'POSTED' "
                + "   AND e.entry_date BETWEEN ? AND ? AND a.code LIKE ?",
                BigDecimal.class, companyId, from, to, accountPrefix + "%");
        return v == null ? BigDecimal.ZERO : v;
    }

    public record RegularizationAlert(
            String companyId, String companyName,
            String profileId, String fullName,
            BigDecimal currentBase, BigDecimal netIncome,
            String tramoLabel, BigDecimal baseMin, BigDecimal baseMax,
            String status // NO_BASE | UNDER | OVER
    ) {}

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
        public TramoSuggestion suggest(@RequestParam("annualNetIncome") BigDecimal annual,
                                        @RequestParam(value = "year", required = false) Integer year) {
            return year == null ? service.suggestTramo(annual)
                                 : service.suggestTramo(year, annual);
        }

        // RETA-0 — tramos por año (editables desde la UI, sin tocar código).
        @GetMapping("/tramos/years")
        public List<Integer> tramoYears() { return service.listTramoYears(); }

        @GetMapping("/tramos/{year}")
        public List<TramoRow> tramos(@PathVariable("year") int year) {
            return service.getTramos(year);
        }

        @PutMapping("/tramos/{year}")
        public List<TramoRow> saveTramos(@PathVariable("year") int year,
                                          @RequestBody List<TramoRow> rows) {
            return service.saveTramos(year, rows);
        }

        @PostMapping("/tramos/{year}/clone-from/{src}")
        public List<TramoRow> cloneTramos(@PathVariable("year") int year,
                                           @PathVariable("src") int src) {
            return service.cloneTramos(src, year);
        }

        // RETA-2 — crear perfiles de titulares RETA que falten en la empresa
        // actual (idempotente). El scan también lo hace en toda la cartera.
        @PostMapping("/ensure-profiles")
        public java.util.Map<String, Integer> ensureProfiles() {
            return java.util.Map.of("created", service.ensureOwnerProfilesForCurrent());
        }

        // RETA-3 — escaneo de regularización (empresa propia + cartera).
        @PostMapping("/regularization/scan")
        public List<RegularizationAlert> scanRegularization(
                @RequestParam(value = "year", required = false) Integer year) {
            return service.scanRegularization(year == null ? Year.now().getValue() : year);
        }
    }
}
