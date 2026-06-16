package com.benjagest.backend.labor.ss;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bases de cotización a la SS por GRUPO DE COTIZACIÓN y AÑO (V121).
 *
 * <p>Tabla global {@code ss_contribution_group_bases}, editable desde la UI sin
 * tocar código (patrón "no-code" igual que {@code reta_tramos}): el asesor clona
 * el año y ajusta las cifras. La base mínima depende del grupo (1..11); la
 * máxima es común. Grupos 8-11 usan base DIARIA.
 *
 * <p><b>Importante:</b> el motor de nóminas todavía NO consume esta tabla (usa
 * el tope global de {@code ss_contribution_rates}). El cableado se hará cuando
 * Benjamin valide las cifras 2026 ({@code pendingValidation}).
 */
@Service
public class SsGroupBasesService {

    private final JdbcTemplate jdbc;

    public SsGroupBasesService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Grupos de un año. Si no existe la fila exacta, usa el último año &le; al
     *  pedido (mismo fallback que el resto de tablas PARAM-YEAR). */
    public List<GroupBase> listByYear(int year) {
        Integer resolved = jdbc.query(
                "SELECT year_number FROM ss_contribution_group_bases WHERE year_number <= ? "
                        + "ORDER BY year_number DESC LIMIT 1",
                rs -> rs.next() ? rs.getInt(1) : null, year);
        if (resolved == null) return List.of();
        return jdbc.query("""
                SELECT id, year_number, cotiz_group, label, base_min, base_max,
                       is_daily, pending_validation
                  FROM ss_contribution_group_bases
                 WHERE year_number = ?
                 ORDER BY cotiz_group
                """, MAPPER, resolved);
    }

    /** Años con datos cargados (desc). */
    public List<Integer> listYears() {
        return jdbc.queryForList(
                "SELECT DISTINCT year_number FROM ss_contribution_group_bases ORDER BY year_number DESC",
                Integer.class);
    }

    /** Reemplaza TODAS las filas de un año por las recibidas (editor no-code). */
    @Transactional
    public List<GroupBase> saveYear(int year, List<GroupBase> rows) {
        if (year < 2000 || year > 2100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Año fuera de rango");
        }
        if (rows == null || rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sin filas que guardar");
        }
        jdbc.update("DELETE FROM ss_contribution_group_bases WHERE year_number = ?", year);
        for (GroupBase r : rows) {
            if (r.cotizGroup() < 1 || r.cotizGroup() > 11) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Grupo de cotización fuera de rango (1-11): " + r.cotizGroup());
            }
            jdbc.update("""
                    INSERT INTO ss_contribution_group_bases
                        (id, year_number, cotiz_group, label, base_min, base_max,
                         is_daily, pending_validation)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), year, r.cotizGroup(),
                    r.label() == null ? ("Grupo " + r.cotizGroup()) : r.label(),
                    nz(r.baseMin()), nz(r.baseMax()),
                    r.daily(), r.pendingValidation());
        }
        return listByYear(year);
    }

    /** Clona el último año disponible hacia un año nuevo (mantiene pendingValidation). */
    @Transactional
    public List<GroupBase> cloneFromLatest(int targetYear) {
        List<Integer> years = listYears();
        if (years.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No hay ningún año base para clonar");
        }
        if (years.contains(targetYear)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El año " + targetYear + " ya existe");
        }
        List<GroupBase> source = listByYear(years.get(0));
        return saveYear(targetYear, source);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static final RowMapper<GroupBase> MAPPER = (rs, n) -> new GroupBase(
            rs.getInt("year_number"), rs.getShort("cotiz_group"), rs.getString("label"),
            rs.getBigDecimal("base_min"), rs.getBigDecimal("base_max"),
            rs.getBoolean("is_daily"), rs.getBoolean("pending_validation"));

    public record GroupBase(
            int yearNumber, short cotizGroup, String label,
            BigDecimal baseMin, BigDecimal baseMax,
            boolean daily, boolean pendingValidation) {}

    @RestController
    @RequestMapping("/api/labor/ss-group-bases")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class Controller {
        private final SsGroupBasesService service;

        public Controller(SsGroupBasesService service) { this.service = service; }

        @GetMapping("/years")
        public List<Integer> years() { return service.listYears(); }

        @GetMapping("/{year}")
        public List<GroupBase> byYear(@PathVariable("year") int year) { return service.listByYear(year); }

        @PostMapping("/{year}")
        @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
        public List<GroupBase> save(@PathVariable("year") int year, @RequestBody List<GroupBase> rows) {
            return service.saveYear(year, rows);
        }

        @PostMapping("/clone")
        @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
        public List<GroupBase> clone(@RequestParam("targetYear") int targetYear) {
            return service.cloneFromLatest(targetYear);
        }
    }
}
