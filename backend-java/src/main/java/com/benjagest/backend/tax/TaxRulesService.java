package com.benjagest.backend.tax;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Year;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lectura del catalogo fiscal: tramos IRPF, % IVA historicos,
 * retenciones historicas, epigrafes IAE.
 *
 * Todo es global (no por empresa) — son normas estatales. La empresa
 * solo puede personalizar sus propios `vat_rates` (V23), que se
 * superponen al historico.
 *
 * Incluye una utilidad para calcular cuota IRPF dada una base imponible
 * y el ano: se descompone por tramos y suma estatal+autonomico.
 */
@Service
public class TaxRulesService {

    private final JdbcTemplate jdbcTemplate;

    public TaxRulesService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<IrpfBracket> irpfBrackets(int year) {
        return jdbcTemplate.query("""
                SELECT year, bracket_order, min_income, max_income,
                       rate_state, rate_regional, notes
                  FROM tax_irpf_brackets
                 WHERE year = ?
                 ORDER BY bracket_order
                """, (rs, n) -> new IrpfBracket(
                        rs.getInt("year"),
                        rs.getInt("bracket_order"),
                        rs.getBigDecimal("min_income"),
                        rs.getBigDecimal("max_income"),
                        rs.getBigDecimal("rate_state"),
                        rs.getBigDecimal("rate_regional"),
                        rs.getString("notes")
                ), year);
    }

    public List<VatHistory> vatHistory(int year) {
        return jdbcTemplate.query("""
                SELECT year, vat_type, percent, description, legal_reference
                  FROM tax_vat_history
                 WHERE year = ?
                 ORDER BY vat_type
                """, (rs, n) -> new VatHistory(
                        rs.getInt("year"),
                        rs.getString("vat_type"),
                        rs.getBigDecimal("percent"),
                        rs.getString("description"),
                        rs.getString("legal_reference")
                ), year);
    }

    public List<WithholdingHistory> withholdingHistory(int year) {
        return jdbcTemplate.query("""
                SELECT year, withholding_type, percent, description, legal_reference
                  FROM tax_withholding_history
                 WHERE year = ?
                 ORDER BY withholding_type
                """, (rs, n) -> new WithholdingHistory(
                        rs.getInt("year"),
                        rs.getString("withholding_type"),
                        rs.getBigDecimal("percent"),
                        rs.getString("description"),
                        rs.getString("legal_reference")
                ), year);
    }

    public List<IaeEpigraph> iaeEpigraphs(String search) {
        StringBuilder sql = new StringBuilder("""
                SELECT code, section, description FROM tax_iae_epigraphs
                """);
        List<Object> args = new java.util.ArrayList<>();
        if (search != null && !search.isBlank()) {
            sql.append(" WHERE description LIKE ? OR code LIKE ?");
            args.add("%" + search + "%");
            args.add(search + "%");
        }
        sql.append(" ORDER BY code");
        return jdbcTemplate.query(sql.toString(),
                (rs, n) -> new IaeEpigraph(rs.getString("code"),
                        rs.getString("section"), rs.getString("description")),
                args.toArray());
    }

    /**
     * Calcula la cuota IRPF para una base imponible dada y un ano.
     * Suma estatal + autonomico tramo a tramo.
     */
    public IrpfCalculation calculateIrpf(BigDecimal base, int year) {
        if (base == null) base = BigDecimal.ZERO;
        List<IrpfBracket> brackets = irpfBrackets(year);
        BigDecimal stateQuota = BigDecimal.ZERO;
        BigDecimal regionalQuota = BigDecimal.ZERO;
        BigDecimal remaining = base;
        for (IrpfBracket b : brackets) {
            BigDecimal upper = b.maxIncome() == null
                    ? base.max(b.minIncome())
                    : b.maxIncome();
            BigDecimal span = upper.subtract(b.minIncome());
            BigDecimal cut = remaining.min(span.max(BigDecimal.ZERO));
            if (cut.signum() <= 0 && b.maxIncome() != null) continue;
            // Solo cuenta lo que cae en este tramo: min(remaining, span)
            BigDecimal taxable = base.subtract(b.minIncome()).min(span).max(BigDecimal.ZERO);
            if (b.maxIncome() == null) {
                taxable = base.subtract(b.minIncome()).max(BigDecimal.ZERO);
            }
            if (taxable.signum() <= 0) continue;
            stateQuota = stateQuota.add(taxable
                    .multiply(b.rateState())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            regionalQuota = regionalQuota.add(taxable
                    .multiply(b.rateRegional())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        }
        BigDecimal total = stateQuota.add(regionalQuota);
        BigDecimal effective = base.signum() == 0
                ? BigDecimal.ZERO
                : total.multiply(BigDecimal.valueOf(100))
                        .divide(base, 2, RoundingMode.HALF_UP);
        return new IrpfCalculation(year, base, stateQuota, regionalQuota, total, effective);
    }

    public record IrpfBracket(int year, int bracketOrder, BigDecimal minIncome, BigDecimal maxIncome,
                               BigDecimal rateState, BigDecimal rateRegional, String notes) {}

    public record VatHistory(int year, String vatType, BigDecimal percent,
                              String description, String legalReference) {}

    public record WithholdingHistory(int year, String withholdingType, BigDecimal percent,
                                      String description, String legalReference) {}

    public record IaeEpigraph(String code, String section, String description) {}

    public record IrpfCalculation(int year, BigDecimal base, BigDecimal stateQuota,
                                   BigDecimal regionalQuota, BigDecimal totalQuota,
                                   BigDecimal effectiveRate) {}

    @RestController
    @RequestMapping("/api/tax/rules")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class TaxRulesController {
        private final TaxRulesService service;

        public TaxRulesController(TaxRulesService service) { this.service = service; }

        @GetMapping("/irpf")
        public List<IrpfBracket> irpf(@RequestParam(value = "year", required = false) Integer year) {
            return service.irpfBrackets(year == null ? Year.now().getValue() : year);
        }

        @GetMapping("/vat")
        public List<VatHistory> vat(@RequestParam(value = "year", required = false) Integer year) {
            return service.vatHistory(year == null ? Year.now().getValue() : year);
        }

        @GetMapping("/withholdings")
        public List<WithholdingHistory> withholdings(@RequestParam(value = "year", required = false) Integer year) {
            return service.withholdingHistory(year == null ? Year.now().getValue() : year);
        }

        @GetMapping("/iae")
        public List<IaeEpigraph> iae(@RequestParam(value = "search", required = false) String search) {
            return service.iaeEpigraphs(search);
        }

        @GetMapping("/irpf/calculate")
        public IrpfCalculation calculate(@RequestParam("base") BigDecimal base,
                                          @RequestParam(value = "year", required = false) Integer year) {
            return service.calculateIrpf(base, year == null ? Year.now().getValue() : year);
        }
    }
}
