package com.benjagest.backend.labor;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * N3(b) — Topes de INDEMNIZACIÓN por despido POR AÑO (no-code). Tabla global
 * {@code severance_params} (V127). {@link TerminationService} lee los días/año,
 * topes y exención de IRPF del año del cese (con fallback al último año
 * disponible &le; al pedido), de modo que cuando cambie la ley solo hay que
 * añadir/editar la fila del año desde la pantalla — sin tocar código.
 */
@Service
public class SeveranceParamsService {

    private final JdbcTemplate jdbc;

    public SeveranceParamsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Parámetros vigentes para un año. Si no existe la fila exacta, usa la del
     *  último año disponible &le; al pedido. Si NO hay ninguno, lanza 422 — no se
     *  calcula con valores a fuego (mismo criterio que N3(a)). V127 siembra 2012. */
    public Params paramsForYear(int year) {
        List<Params> rows = jdbc.query("""
                SELECT year_number, unfair_days_per_year, unfair_cap_days,
                       unfair_pre2012_days_per_year, unfair_pre2012_cap_days,
                       objective_days_per_year, objective_cap_days,
                       end_contract_days_per_year, irpf_exempt_cap, legal_reference
                  FROM severance_params
                 WHERE year_number <= ?
                 ORDER BY year_number DESC LIMIT 1
                """, MAPPER, year);
        if (!rows.isEmpty()) return rows.get(0);
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "No hay topes de indemnización configurados para " + year
                + " ni años anteriores. Configúralos en Laboral → Indemnización.");
    }

    public List<Params> listAll() {
        return jdbc.query("""
                SELECT year_number, unfair_days_per_year, unfair_cap_days,
                       unfair_pre2012_days_per_year, unfair_pre2012_cap_days,
                       objective_days_per_year, objective_cap_days,
                       end_contract_days_per_year, irpf_exempt_cap, legal_reference
                  FROM severance_params
                 ORDER BY year_number DESC
                """, MAPPER);
    }

    @Transactional
    public Params upsert(Params p) {
        if (p.yearNumber() < 2000 || p.yearNumber() > 2100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Año fuera de rango");
        }
        jdbc.update("""
                INSERT INTO severance_params
                    (id, year_number, unfair_days_per_year, unfair_cap_days,
                     unfair_pre2012_days_per_year, unfair_pre2012_cap_days,
                     objective_days_per_year, objective_cap_days,
                     end_contract_days_per_year, irpf_exempt_cap, legal_reference)
                VALUES (UUID(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                     unfair_days_per_year = VALUES(unfair_days_per_year),
                     unfair_cap_days = VALUES(unfair_cap_days),
                     unfair_pre2012_days_per_year = VALUES(unfair_pre2012_days_per_year),
                     unfair_pre2012_cap_days = VALUES(unfair_pre2012_cap_days),
                     objective_days_per_year = VALUES(objective_days_per_year),
                     objective_cap_days = VALUES(objective_cap_days),
                     end_contract_days_per_year = VALUES(end_contract_days_per_year),
                     irpf_exempt_cap = VALUES(irpf_exempt_cap),
                     legal_reference = VALUES(legal_reference)
                """,
                p.yearNumber(), nz(p.unfairDaysPerYear()), p.unfairCapDays(),
                nz(p.unfairPre2012DaysPerYear()), p.unfairPre2012CapDays(),
                nz(p.objectiveDaysPerYear()), p.objectiveCapDays(),
                nz(p.endContractDaysPerYear()), nz(p.irpfExemptCap()), p.legalReference());
        return paramsForYear(p.yearNumber());
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static final org.springframework.jdbc.core.RowMapper<Params> MAPPER = (rs, n) -> new Params(
            rs.getInt("year_number"),
            rs.getBigDecimal("unfair_days_per_year"), rs.getInt("unfair_cap_days"),
            rs.getBigDecimal("unfair_pre2012_days_per_year"), rs.getInt("unfair_pre2012_cap_days"),
            rs.getBigDecimal("objective_days_per_year"), rs.getInt("objective_cap_days"),
            rs.getBigDecimal("end_contract_days_per_year"), rs.getBigDecimal("irpf_exempt_cap"),
            rs.getString("legal_reference"));

    public record Params(
            int yearNumber,
            BigDecimal unfairDaysPerYear, int unfairCapDays,
            BigDecimal unfairPre2012DaysPerYear, int unfairPre2012CapDays,
            BigDecimal objectiveDaysPerYear, int objectiveCapDays,
            BigDecimal endContractDaysPerYear, BigDecimal irpfExemptCap,
            String legalReference
    ) {}

    @RestController
    @RequestMapping("/api/labor/severance-params")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class Controller {
        private final SeveranceParamsService service;

        public Controller(SeveranceParamsService service) { this.service = service; }

        @GetMapping
        public List<Params> list() { return service.listAll(); }

        @GetMapping("/{year}")
        public Params forYear(@PathVariable("year") int year) { return service.paramsForYear(year); }

        @PostMapping
        @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
        public Params upsert(@RequestBody Params p) { return service.upsert(p); }
    }
}
