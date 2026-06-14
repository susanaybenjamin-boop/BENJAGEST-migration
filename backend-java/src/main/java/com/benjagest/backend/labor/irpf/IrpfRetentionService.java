package com.benjagest.backend.labor.irpf;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Motor de retención de IRPF (procedimiento general, Reglamento IRPF art.
 * 80-89) — como A3. Calcula el tipo de retención a partir de la retribución
 * anual, la SS del trabajador y los datos del modelo 145 del empleado.
 *
 * <p>Escala y parámetros (mínimos / reducciones) son POR AÑO y editables
 * ({@code irpf_retention_brackets}, {@code irpf_retention_params}). Si no hay
 * datos del modelo 145, el cálculo de nómina usa el % fijo del contrato.
 */
@Service
public class IrpfRetentionService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public IrpfRetentionService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public record Modelo145(
            int familySituation, String spouseNif,
            int descendants, int descendantsUnder3,
            int descendantsDisability33, int descendantsDisability65, boolean exclusiveCustody,
            int ascendantsOver65, int ascendantsOver75,
            String ownDisability, boolean ownMobility,
            boolean taxpayerOver65, boolean taxpayerOver75,
            boolean contractUnderYear, boolean geographicMobility, boolean mortgageBefore2013) {}

    public Modelo145 findForEmployee(String employeeId) {
        return jdbc.query("""
                SELECT family_situation, spouse_nif, descendants, descendants_under3,
                       descendants_disability33, descendants_disability65, exclusive_custody,
                       ascendants_over65, ascendants_over75, own_disability, own_mobility,
                       taxpayer_over65, taxpayer_over75, contract_under_year,
                       geographic_mobility, mortgage_before2013
                  FROM employee_irpf_data
                 WHERE employee_id = ? AND company_id = ?
                """,
                (rs, n) -> new Modelo145(
                        rs.getInt("family_situation"), rs.getString("spouse_nif"),
                        rs.getInt("descendants"), rs.getInt("descendants_under3"),
                        rs.getInt("descendants_disability33"), rs.getInt("descendants_disability65"),
                        rs.getBoolean("exclusive_custody"),
                        rs.getInt("ascendants_over65"), rs.getInt("ascendants_over75"),
                        rs.getString("own_disability"), rs.getBoolean("own_mobility"),
                        rs.getBoolean("taxpayer_over65"), rs.getBoolean("taxpayer_over75"),
                        rs.getBoolean("contract_under_year"), rs.getBoolean("geographic_mobility"),
                        rs.getBoolean("mortgage_before2013")),
                employeeId, tenant.getCurrentCompanyId())
                .stream().findFirst().orElse(null);
    }

    @Transactional
    public void upsert(String employeeId, Modelo145 m) {
        jdbc.update("""
                INSERT INTO employee_irpf_data
                    (employee_id, company_id, family_situation, spouse_nif, descendants,
                     descendants_under3, descendants_disability33, descendants_disability65,
                     exclusive_custody, ascendants_over65, ascendants_over75, own_disability,
                     own_mobility, taxpayer_over65, taxpayer_over75, contract_under_year,
                     geographic_mobility, mortgage_before2013)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                     family_situation = VALUES(family_situation), spouse_nif = VALUES(spouse_nif),
                     descendants = VALUES(descendants), descendants_under3 = VALUES(descendants_under3),
                     descendants_disability33 = VALUES(descendants_disability33),
                     descendants_disability65 = VALUES(descendants_disability65),
                     exclusive_custody = VALUES(exclusive_custody),
                     ascendants_over65 = VALUES(ascendants_over65),
                     ascendants_over75 = VALUES(ascendants_over75),
                     own_disability = VALUES(own_disability), own_mobility = VALUES(own_mobility),
                     taxpayer_over65 = VALUES(taxpayer_over65), taxpayer_over75 = VALUES(taxpayer_over75),
                     contract_under_year = VALUES(contract_under_year),
                     geographic_mobility = VALUES(geographic_mobility),
                     mortgage_before2013 = VALUES(mortgage_before2013)
                """,
                employeeId, tenant.getCurrentCompanyId(),
                m.familySituation(), blank(m.spouseNif()), m.descendants(), m.descendantsUnder3(),
                m.descendantsDisability33(), m.descendantsDisability65(), m.exclusiveCustody(),
                m.ascendantsOver65(), m.ascendantsOver75(),
                m.ownDisability() == null ? "NONE" : m.ownDisability(), m.ownMobility(),
                m.taxpayerOver65(), m.taxpayerOver75(), m.contractUnderYear(),
                m.geographicMobility(), m.mortgageBefore2013());
    }

    /**
     * Tipo de retención (%) según el procedimiento general. annualGross =
     * retribución íntegra anual (recurrente); annualSs = SS trabajador anual.
     */
    public BigDecimal computeRate(int year, BigDecimal annualGross, BigDecimal annualSs, Modelo145 m) {
        Params p = loadParams(year);
        List<double[]> brackets = loadBrackets(year); // [lowerLimit, rate]
        double gross = annualGross.doubleValue();
        double ss = annualSs == null ? 0 : annualSs.doubleValue();
        if (gross <= 0) return BigDecimal.ZERO;

        // art. 81: límite excluyente — no se retiene por debajo del mínimo.
        int situation = m != null ? m.familySituation() : 3;
        int children = m != null ? m.descendants() : 0;
        double exempt = exemptThreshold(year, situation, children);
        if (gross <= exempt) return BigDecimal.ZERO;

        // 1) Reducción por obtención de rendimientos del trabajo (art. 20).
        //    El RNT del algoritmo es retribución − cotizaciones (sin restar los
        //    "otros gastos" de 2.000). Tres tramos hasta 19.747,50.
        double rnt = gross - ss;
        if (rnt < 0) rnt = 0;
        double workReduction;
        if (rnt <= p.workThreshold1) workReduction = p.workMax;
        else if (rnt <= p.workThreshold2) workReduction = p.workMax - p.workFactor * (rnt - p.workThreshold1);
        else if (rnt < p.workThreshold3) workReduction = p.workMax2 - p.workFactor2 * (rnt - p.workThreshold2);
        else workReduction = 0;
        if (workReduction < 0) workReduction = 0;

        // 2) Base para calcular el tipo = (retribución − cotizaciones − otros
        //    gastos − reducción art. 20) menos las reducciones del art. 83.3:
        //    en particular 600 € si hay MÁS DE DOS descendientes.
        double moreDesc = (m != null && m.descendants() > 2) ? p.moreThan2Desc : 0;
        double base = gross - ss - p.expenseDeduction - workReduction - moreDesc;
        if (base < 0) base = 0;

        // 3) Mínimo personal y familiar.
        double mpf = p.personalMin;
        if (m != null) {
            if (m.taxpayerOver75()) mpf += p.personalOver65 + p.personalOver75;
            else if (m.taxpayerOver65()) mpf += p.personalOver65;
            // Descendientes (por orden) + < 3 años + discapacidad.
            double desc = 0;
            int n = m.descendants();
            if (n >= 1) desc += p.desc1;
            if (n >= 2) desc += p.desc2;
            if (n >= 3) desc += p.desc3;
            if (n >= 4) desc += (n - 3) * p.desc4plus;
            desc += m.descendantsUnder3() * p.descUnder3;
            desc += m.descendantsDisability33() * p.disability33;
            desc += m.descendantsDisability65() * p.disability65;
            if (!m.exclusiveCustody()) desc /= 2.0; // 50% si se comparte
            mpf += desc;
            // Ascendientes.
            mpf += m.ascendantsOver65() * p.ascOver65;
            mpf += m.ascendantsOver75() * (p.ascOver65 + p.ascOver75);
            // Discapacidad del propio trabajador.
            if ("D65".equals(m.ownDisability())) mpf += p.disability65;
            else if ("D33".equals(m.ownDisability())) mpf += p.disability33;
            if (m.ownMobility()) mpf += p.disabilityMobility;
        }

        // 4) Cuota de retención = escala(base) − escala(min(mpf, base)).
        double cuota1 = applyScale(base, brackets);
        double cuota2 = applyScale(Math.min(mpf, base), brackets);
        double cuota = Math.max(0, cuota1 - cuota2);

        // art. 85.3: para rentas <= cap, la cuota no supera el limitRate% de
        // (retribución − mínimo excluido). Suaviza el salto al salir del exento.
        if (gross <= p.limitIncomeCap && exempt > 0) {
            double limite = p.limitRate / 100.0 * (gross - exempt);
            if (limite < 0) limite = 0;
            if (cuota > limite) cuota = limite;
        }

        // Minoración por préstamo vivienda anterior a 2013 (rentas < 33.007,2).
        // El algoritmo AEAT trunca la minoración (2% de la retribución).
        if (m != null && m.mortgageBefore2013() && gross < 33007.20) {
            double minopago = Math.floor(gross * 0.02 * 100.0) / 100.0; // TRUNCAR 2 dec.
            cuota -= minopago;
            if (cuota < 0) cuota = 0;
        }

        // 5) Tipo de retención. El algoritmo AEAT TRUNCA el tipo a 2 decimales.
        double tipo = cuota / gross * 100.0;
        BigDecimal tipoBd = BigDecimal.valueOf(tipo).setScale(2, RoundingMode.DOWN);
        if (m != null && m.contractUnderYear() && tipoBd.compareTo(BigDecimal.valueOf(2)) < 0) {
            tipoBd = BigDecimal.valueOf(2).setScale(2);
        }
        if (tipoBd.signum() < 0) tipoBd = BigDecimal.ZERO.setScale(2);
        return tipoBd;
    }

    private double applyScale(double amount, List<double[]> brackets) {
        double cuota = 0;
        for (int i = 0; i < brackets.size(); i++) {
            double lower = brackets.get(i)[0];
            double rate = brackets.get(i)[1];
            double upper = i + 1 < brackets.size() ? brackets.get(i + 1)[0] : Double.MAX_VALUE;
            if (amount > lower) {
                double taxable = Math.min(amount, upper) - lower;
                cuota += taxable * rate / 100.0;
            }
        }
        return cuota;
    }

    private List<double[]> loadBrackets(int year) {
        List<double[]> rows = jdbc.query("""
                SELECT lower_limit, rate FROM irpf_retention_brackets
                 WHERE year = (SELECT MAX(year) FROM irpf_retention_brackets WHERE year <= ?)
                 ORDER BY lower_limit
                """,
                (rs, n) -> new double[]{rs.getBigDecimal("lower_limit").doubleValue(),
                        rs.getBigDecimal("rate").doubleValue()}, year);
        if (rows.isEmpty()) {
            // Fallback escala 2026.
            rows = List.of(new double[]{0, 19}, new double[]{12450, 24}, new double[]{20200, 30},
                    new double[]{35200, 37}, new double[]{60000, 45}, new double[]{300000, 47});
        }
        return rows;
    }

    /** Mínimo excluido de retención (art. 81) para situación y nº de hijos. */
    private double exemptThreshold(int year, int situation, int children) {
        int ch = Math.min(Math.max(children, 0), 2);
        Integer ey = jdbc.query("SELECT MAX(year) y FROM irpf_exempt_thresholds WHERE year <= ?",
                (rs, n) -> (Integer) rs.getObject("y"), year).stream().findFirst().orElse(null);
        if (ey == null) return 0;
        Double v = jdbc.query("""
                SELECT threshold FROM irpf_exempt_thresholds
                 WHERE year = ? AND situation = ? AND children = ?
                """, (rs, n) -> rs.getDouble("threshold"), ey, situation, ch)
                .stream().findFirst().orElse(null);
        if (v != null) return v;
        // Fallback: situación 3 (la más común) con ese nº de hijos.
        Double v3 = jdbc.query("""
                SELECT threshold FROM irpf_exempt_thresholds
                 WHERE year = ? AND situation = 3 AND children = ?
                """, (rs, n) -> rs.getDouble("threshold"), ey, ch)
                .stream().findFirst().orElse(null);
        return v3 != null ? v3 : 0;
    }

    private Params loadParams(int year) {
        return jdbc.query("""
                SELECT * FROM irpf_retention_params
                 WHERE year = (SELECT MAX(year) FROM irpf_retention_params WHERE year <= ?)
                """,
                (rs, n) -> {
                    Params p = new Params();
                    p.personalMin = rs.getDouble("personal_min");
                    p.personalOver65 = rs.getDouble("personal_over65");
                    p.personalOver75 = rs.getDouble("personal_over75");
                    p.desc1 = rs.getDouble("descendant_1");
                    p.desc2 = rs.getDouble("descendant_2");
                    p.desc3 = rs.getDouble("descendant_3");
                    p.desc4plus = rs.getDouble("descendant_4plus");
                    p.descUnder3 = rs.getDouble("descendant_under3");
                    p.ascOver65 = rs.getDouble("ascendant_over65");
                    p.ascOver75 = rs.getDouble("ascendant_over75");
                    p.disability33 = rs.getDouble("disability_33");
                    p.disability65 = rs.getDouble("disability_65");
                    p.disabilityMobility = rs.getDouble("disability_mobility");
                    p.expenseDeduction = rs.getDouble("expense_deduction");
                    p.workMax = rs.getDouble("work_reduction_max");
                    p.workThreshold1 = rs.getDouble("work_reduction_threshold1");
                    p.workThreshold2 = rs.getDouble("work_reduction_threshold2");
                    p.workFactor = rs.getDouble("work_reduction_factor");
                    p.workMax2 = rs.getDouble("work_reduction_max2");
                    p.workThreshold3 = rs.getDouble("work_reduction_threshold3");
                    p.workFactor2 = rs.getDouble("work_reduction_factor2");
                    p.moreThan2Desc = rs.getDouble("reduction_more_than_2_desc");
                    p.limitRate = rs.getDouble("limit_rate");
                    p.limitIncomeCap = rs.getDouble("limit_income_cap");
                    return p;
                }, year)
                .stream().findFirst().orElseGet(Params::defaults2026);
    }

    private static String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    private static class Params {
        double personalMin, personalOver65, personalOver75;
        double desc1, desc2, desc3, desc4plus, descUnder3;
        double ascOver65, ascOver75;
        double disability33, disability65, disabilityMobility;
        double expenseDeduction, workMax, workThreshold1, workThreshold2, workFactor;
        // 3er tramo de la reducción art. 20 (17.673,52 - 19.747,50).
        double workMax2 = 2364.34, workThreshold3 = 19747.50, workFactor2 = 1.14;
        // Reducción por más de dos descendientes (art. 83.3 RIRPF).
        double moreThan2Desc = 600;
        double limitRate = 43, limitIncomeCap = 35200;
        static Params defaults2026() {
            Params p = new Params();
            p.personalMin = 5550; p.personalOver65 = 1150; p.personalOver75 = 1400;
            p.desc1 = 2400; p.desc2 = 2700; p.desc3 = 4000; p.desc4plus = 4500; p.descUnder3 = 2800;
            p.ascOver65 = 1150; p.ascOver75 = 1400;
            p.disability33 = 3000; p.disability65 = 9000; p.disabilityMobility = 3000;
            p.expenseDeduction = 2000; p.workMax = 7302; p.workThreshold1 = 14852;
            p.workThreshold2 = 17673.52; p.workFactor = 1.75;
            p.workMax2 = 2364.34; p.workThreshold3 = 19747.50; p.workFactor2 = 1.14;
            p.moreThan2Desc = 600;
            p.limitRate = 43; p.limitIncomeCap = 35200;
            return p;
        }
    }

    // ===== Administración de parámetros IRPF por año (punto 2) =====

    public record Bracket(BigDecimal lowerLimit, BigDecimal rate) {}

    public List<Integer> listParamYears() {
        return jdbc.query("SELECT year FROM irpf_retention_params ORDER BY year DESC",
                (rs, n) -> rs.getInt("year"));
    }

    public List<Bracket> listBrackets(int year) {
        return jdbc.query("""
                SELECT lower_limit, rate FROM irpf_retention_brackets
                 WHERE year = ? ORDER BY lower_limit
                """, (rs, n) -> new Bracket(rs.getBigDecimal("lower_limit"), rs.getBigDecimal("rate")), year);
    }

    @Transactional
    public void replaceBrackets(int year, List<Bracket> brackets) {
        jdbc.update("DELETE FROM irpf_retention_brackets WHERE year = ?", year);
        if (brackets == null) return;
        for (Bracket b : brackets) {
            if (b.lowerLimit() == null || b.rate() == null) continue;
            jdbc.update("INSERT INTO irpf_retention_brackets (year, lower_limit, rate) VALUES (?, ?, ?)",
                    year, b.lowerLimit(), b.rate());
        }
    }

    /** Clona toda la configuración IRPF (escala + parámetros + mínimos exentos)
     *  del último año disponible a {@code toYear}, para arrancar un año nuevo. */
    @Transactional
    public void cloneYear(int toYear) {
        Integer from = jdbc.query(
                "SELECT MAX(year) y FROM irpf_retention_params WHERE year < ?",
                (rs, n) -> (Integer) rs.getObject("y"), toYear).stream().findFirst().orElse(null);
        if (from == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "No hay un año previo que clonar");
        }
        jdbc.update("DELETE FROM irpf_retention_brackets WHERE year = ?", toYear);
        jdbc.update("""
                INSERT INTO irpf_retention_brackets (year, lower_limit, rate)
                SELECT ?, lower_limit, rate FROM irpf_retention_brackets WHERE year = ?
                """, toYear, from);
        jdbc.update("DELETE FROM irpf_exempt_thresholds WHERE year = ?", toYear);
        jdbc.update("""
                INSERT INTO irpf_exempt_thresholds (year, situation, children, threshold)
                SELECT ?, situation, children, threshold FROM irpf_exempt_thresholds WHERE year = ?
                """, toYear, from);
        jdbc.update("DELETE FROM irpf_retention_params WHERE year = ?", toYear);
        jdbc.update("""
                INSERT INTO irpf_retention_params
                    (year, personal_min, personal_over65, personal_over75, descendant_1,
                     descendant_2, descendant_3, descendant_4plus, descendant_under3,
                     ascendant_over65, ascendant_over75, disability_33, disability_65,
                     disability_mobility, expense_deduction, work_reduction_max,
                     work_reduction_threshold1, work_reduction_threshold2, work_reduction_factor,
                     work_reduction_max2, work_reduction_threshold3, work_reduction_factor2,
                     reduction_more_than_2_desc,
                     limit_rate, limit_income_cap, legal_reference)
                SELECT ?, personal_min, personal_over65, personal_over75, descendant_1,
                     descendant_2, descendant_3, descendant_4plus, descendant_under3,
                     ascendant_over65, ascendant_over75, disability_33, disability_65,
                     disability_mobility, expense_deduction, work_reduction_max,
                     work_reduction_threshold1, work_reduction_threshold2, work_reduction_factor,
                     work_reduction_max2, work_reduction_threshold3, work_reduction_factor2,
                     reduction_more_than_2_desc,
                     limit_rate, limit_income_cap, legal_reference
                  FROM irpf_retention_params WHERE year = ?
                """, toYear, from);
    }

    @RestController
    @RequestMapping("/api/labor/irpf-params")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class ParamsController {
        private final IrpfRetentionService service;

        public ParamsController(IrpfRetentionService service) { this.service = service; }

        @GetMapping("/years")
        public List<Integer> years() { return service.listParamYears(); }

        @GetMapping("/brackets/{year}")
        public List<Bracket> brackets(@PathVariable("year") int year) { return service.listBrackets(year); }

        @PostMapping("/brackets/{year}")
        @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
        public List<Bracket> saveBrackets(@PathVariable("year") int year, @RequestBody List<Bracket> b) {
            service.replaceBrackets(year, b);
            return service.listBrackets(year);
        }

        @PostMapping("/clone/{year}")
        @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
        public List<Integer> clone(@PathVariable("year") int year) {
            service.cloneYear(year);
            return service.listParamYears();
        }
    }

    @RestController
    @RequestMapping("/api/labor/irpf-data")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class Controller {
        private final IrpfRetentionService service;

        public Controller(IrpfRetentionService service) { this.service = service; }

        @GetMapping("/{employeeId}")
        public Modelo145 get(@PathVariable("employeeId") String employeeId) {
            Modelo145 m = service.findForEmployee(employeeId);
            return m != null ? m : new Modelo145(3, null, 0, 0, 0, 0, true,
                    0, 0, "NONE", false, false, false, false, false, false);
        }

        @PostMapping("/{employeeId}")
        public Modelo145 save(@PathVariable("employeeId") String employeeId, @RequestBody Modelo145 m) {
            service.upsert(employeeId, m);
            return service.findForEmployee(employeeId);
        }
    }
}
