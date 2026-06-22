package com.benjagest.backend.labor.ss;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * INC-2 — Tipos de cotización ADICIONAL por horas extraordinarias (V137).
 * Tabla no-code editable por año; el motor lee la fila del año (fallback al
 * último año &le; pedido), igual que {@link SsContributionRatesService}.
 *
 * <p>Dos tipos: ESTRUCTURALES/fuerza mayor y NO ESTRUCTURALES, cada uno con su
 * reparto empresa/trabajador. Las horas extra NO entran en la base de
 * contingencias comunes: cotizan aparte sobre el importe de la hora extra.
 */
@Service
public class OvertimeRatesService {

    private final JdbcTemplate jdbc;

    public OvertimeRatesService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Rates ratesForYear(int year) {
        List<Rates> rows = jdbc.query("""
                SELECT year, structural_employer, structural_employee,
                       normal_employer, normal_employee, legal_reference
                  FROM overtime_contribution_rates
                 WHERE year <= ?
                 ORDER BY year DESC LIMIT 1
                """, MAPPER, year);
        if (!rows.isEmpty()) return rows.get(0);
        // Sin fallback a tipos a fuego: si la tabla está vacía es error de
        // configuración (V137 siembra 2026). El motor decide si exigirla.
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "No hay tipos de cotización de horas extra para " + year
                + " ni anteriores. Configúralos en la tabla overtime_contribution_rates.");
    }

    public List<Rates> listAll() {
        return jdbc.query("""
                SELECT year, structural_employer, structural_employee,
                       normal_employer, normal_employee, legal_reference
                  FROM overtime_contribution_rates
                 ORDER BY year DESC
                """, MAPPER);
    }

    private static final org.springframework.jdbc.core.RowMapper<Rates> MAPPER = (rs, n) -> new Rates(
            rs.getInt("year"),
            rs.getBigDecimal("structural_employer"), rs.getBigDecimal("structural_employee"),
            rs.getBigDecimal("normal_employer"), rs.getBigDecimal("normal_employee"),
            rs.getString("legal_reference"));

    public record Rates(
            int year,
            BigDecimal structuralEmployer, BigDecimal structuralEmployee,
            BigDecimal normalEmployer, BigDecimal normalEmployee,
            String legalReference) {

        /** Tipo trabajador según subtipo (STRUCTURAL | NORMAL). */
        public BigDecimal employeePct(String subtype) {
            return "STRUCTURAL".equals(subtype) ? structuralEmployee : normalEmployee;
        }

        /** Tipo empresa según subtipo (STRUCTURAL | NORMAL). */
        public BigDecimal employerPct(String subtype) {
            return "STRUCTURAL".equals(subtype) ? structuralEmployer : normalEmployer;
        }
    }
}
