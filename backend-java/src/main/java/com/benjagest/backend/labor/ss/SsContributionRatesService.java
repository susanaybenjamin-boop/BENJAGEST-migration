package com.benjagest.backend.labor.ss;

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
 * Tipos de cotización a la Seguridad Social POR AÑO (bloque PARAM-YEAR).
 *
 * <p>Tabla global {@code ss_contribution_rates}. El cálculo de nóminas lee
 * los tipos del año correspondiente (con fallback al último año disponible
 * &le; al pedido), de modo que cuando cambien los tipos solo hay que añadir
 * la fila del nuevo año desde la pantalla — sin tocar código.
 */
@Service
public class SsContributionRatesService {

    private final JdbcTemplate jdbc;

    public SsContributionRatesService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tipos vigentes para un año. Si no existe la fila exacta, usa la del
     *  último año disponible &le; al pedido. Si NO hay ninguno (tabla vacía),
     *  lanza 422 — no se calcula con tipos a fuego (N3). V108 siembra 2026. */
    public Rates ratesForYear(int year) {
        List<Rates> rows = jdbc.query("""
                SELECT year, ee_common, ee_unemployment, ee_training, ee_mei,
                       er_common, er_unemployment, er_fogasa, er_training, er_mei,
                       default_at_ep, base_max_monthly, base_min_monthly, legal_reference
                  FROM ss_contribution_rates
                 WHERE year <= ?
                 ORDER BY year DESC LIMIT 1
                """, MAPPER, year);
        if (!rows.isEmpty()) return rows.get(0);
        // N3 — Sin fallback silencioso a 2026. V108 siembra 2026; si la tabla está
        // vacía es un error de configuración y NO se calcula con tipos a fuego que
        // podrían no ser los legales del año. Se falla de forma ruidosa.
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "No hay tipos de cotización a la Seguridad Social configurados para "
                + year + " ni años anteriores. Configúralos en Laboral → Tipos de cotización.");
    }

    /**
     * Tipos de DESEMPLEO del esquema TEMPORAL para el año (columnas V143).
     * Devuelve {@code [ee, er]} (trabajador, empresa). Fallback DEFENSIVO a los
     * valores legales 2026 (1,60 / 6,70) si no hay fila — nunca rompe el cálculo.
     */
    public BigDecimal[] unemploymentTemporalRates(int year) {
        List<BigDecimal[]> rows = jdbc.query("""
                SELECT ee_unemployment_temporal, er_unemployment_temporal
                  FROM ss_contribution_rates
                 WHERE year <= ?
                 ORDER BY year DESC LIMIT 1
                """, (rs, n) -> new BigDecimal[]{
                        rs.getBigDecimal("ee_unemployment_temporal"),
                        rs.getBigDecimal("er_unemployment_temporal")},
                year);
        if (rows.isEmpty() || rows.get(0)[0] == null || rows.get(0)[1] == null) {
            return new BigDecimal[]{new BigDecimal("1.60"), new BigDecimal("6.70")};
        }
        return rows.get(0);
    }

    public List<Rates> listAll() {
        return jdbc.query("""
                SELECT year, ee_common, ee_unemployment, ee_training, ee_mei,
                       er_common, er_unemployment, er_fogasa, er_training, er_mei,
                       default_at_ep, base_max_monthly, base_min_monthly, legal_reference
                  FROM ss_contribution_rates
                 ORDER BY year DESC
                """, MAPPER);
    }

    @Transactional
    public Rates upsert(Rates r) {
        if (r.year() < 2000 || r.year() > 2100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Año fuera de rango");
        }
        jdbc.update("""
                INSERT INTO ss_contribution_rates
                    (year, ee_common, ee_unemployment, ee_training, ee_mei,
                     er_common, er_unemployment, er_fogasa, er_training, er_mei,
                     default_at_ep, base_max_monthly, base_min_monthly, legal_reference)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                     ee_common = VALUES(ee_common), ee_unemployment = VALUES(ee_unemployment),
                     ee_training = VALUES(ee_training), ee_mei = VALUES(ee_mei),
                     er_common = VALUES(er_common), er_unemployment = VALUES(er_unemployment),
                     er_fogasa = VALUES(er_fogasa), er_training = VALUES(er_training),
                     er_mei = VALUES(er_mei), default_at_ep = VALUES(default_at_ep),
                     base_max_monthly = VALUES(base_max_monthly),
                     base_min_monthly = VALUES(base_min_monthly),
                     legal_reference = VALUES(legal_reference)
                """,
                r.year(), nz(r.eeCommon()), nz(r.eeUnemployment()), nz(r.eeTraining()), nz(r.eeMei()),
                nz(r.erCommon()), nz(r.erUnemployment()), nz(r.erFogasa()), nz(r.erTraining()), nz(r.erMei()),
                nz(r.defaultAtEp()), nz(r.baseMaxMonthly()), nz(r.baseMinMonthly()), r.legalReference());
        return ratesForYear(r.year());
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static final org.springframework.jdbc.core.RowMapper<Rates> MAPPER = (rs, n) -> new Rates(
            rs.getInt("year"),
            rs.getBigDecimal("ee_common"), rs.getBigDecimal("ee_unemployment"),
            rs.getBigDecimal("ee_training"), rs.getBigDecimal("ee_mei"),
            rs.getBigDecimal("er_common"), rs.getBigDecimal("er_unemployment"),
            rs.getBigDecimal("er_fogasa"), rs.getBigDecimal("er_training"), rs.getBigDecimal("er_mei"),
            rs.getBigDecimal("default_at_ep"), rs.getBigDecimal("base_max_monthly"),
            rs.getBigDecimal("base_min_monthly"), rs.getString("legal_reference"));

    public record Rates(
            int year,
            BigDecimal eeCommon, BigDecimal eeUnemployment, BigDecimal eeTraining, BigDecimal eeMei,
            BigDecimal erCommon, BigDecimal erUnemployment, BigDecimal erFogasa,
            BigDecimal erTraining, BigDecimal erMei, BigDecimal defaultAtEp,
            BigDecimal baseMaxMonthly, BigDecimal baseMinMonthly,
            String legalReference
    ) {}

    @RestController
    @RequestMapping("/api/labor/ss-rates")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class Controller {
        private final SsContributionRatesService service;

        public Controller(SsContributionRatesService service) { this.service = service; }

        @GetMapping
        public List<Rates> list() { return service.listAll(); }

        @GetMapping("/{year}")
        public Rates forYear(@PathVariable("year") int year) { return service.ratesForYear(year); }

        @PostMapping
        @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
        public Rates upsert(@RequestBody Rates r) { return service.upsert(r); }
    }
}
