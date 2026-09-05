package com.benjagest.backend.accounting;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Estados financieros oficiales (modelo abreviado PYME — RD 1514/2007).
 *
 * <ul>
 *   <li><b>Balance de situación</b>: Activo (1-2 fijos + 3-4-5-6 circulante)
 *       y Pasivo (1 PN + 1 deuda L/P + 4-5 deuda C/P). Modelo abreviado.</li>
 *   <li><b>Cuenta de Pérdidas y Ganancias</b>: agrupa grupo 6 (gastos)
 *       y grupo 7 (ingresos) en epígrafes oficiales.</li>
 *   <li><b>Estado de Cambios en el Patrimonio Neto (ECPN)</b>: variaciones
 *       de las cuentas del subgrupo 10-13 entre dos fechas. MVP simple.</li>
 * </ul>
 *
 * <p>Los reports leen únicamente asientos {@code POSTED} — los DRAFTS no
 * computan. Esto asegura coherencia con el Diario y el Mayor.
 */
@Service
public class FinancialReportsService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public FinancialReportsService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    // ====================================================================
    //  Balance de situación
    // ====================================================================

    public BalanceSheet balanceSheet(LocalDate asOf) {
        String companyId = tenantContext.getCurrentCompanyId();
        LocalDate cutoff = asOf == null ? LocalDate.now() : asOf;
        List<AccountBalance> all = balancesAt(companyId, cutoff);

        // ACTIVO no corriente: cuentas grupo 2 saldo deudor.
        BalanceSection activoNoCorriente = section("Activo no corriente", all, code ->
                code.startsWith("2"), true);
        // ACTIVO corriente: existencias (3) + realizable (4 deudor) + tesorería (5 deudor).
        BalanceSection existencias = section("Existencias", all, code -> code.startsWith("3"), true);
        BalanceSection realizable = section("Deudores comerciales", all, code ->
                code.startsWith("43") || code.startsWith("44") || code.startsWith("46")
                        || code.startsWith("47"), true);
        BalanceSection tesoreria = section("Efectivo y otros activos líquidos", all, code ->
                code.startsWith("57") || code.startsWith("572") || code.startsWith("570"), true);

        // PATRIMONIO NETO: grupo 1 (10-13) saldo acreedor.
        BalanceSection fondosPropios = section("Fondos propios", all, code ->
                code.startsWith("10") || code.startsWith("11") || code.startsWith("12")
                        || code.startsWith("13"), false);
        // PASIVO no corriente: 16, 17, 18 acreedor.
        BalanceSection pasivoNoCorriente = section("Pasivo no corriente", all, code ->
                code.startsWith("16") || code.startsWith("17") || code.startsWith("18"), false);
        // PASIVO corriente: 5xx acreedor (excepto 57x), 40, 41, 47x acreedor.
        BalanceSection pasivoCorriente = section("Pasivo corriente", all, code ->
                code.startsWith("40") || code.startsWith("41")
                        || (code.startsWith("47") && !code.startsWith("470")) // 472 puede ser acreedor o deudor
                        || (code.startsWith("5") && !code.startsWith("57")), false);

        BigDecimal totalActivo = sum(activoNoCorriente, existencias, realizable, tesoreria);
        BigDecimal totalPasivo = sum(fondosPropios, pasivoNoCorriente, pasivoCorriente);

        return new BalanceSheet(cutoff,
                List.of(activoNoCorriente, existencias, realizable, tesoreria), totalActivo,
                List.of(fondosPropios, pasivoNoCorriente, pasivoCorriente), totalPasivo);
    }

    private BalanceSection section(String name, List<AccountBalance> all,
                                     java.util.function.Predicate<String> codeFilter,
                                     boolean takeDebitSide) {
        List<BalanceItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (AccountBalance b : all) {
            if (!codeFilter.test(b.code)) continue;
            BigDecimal saldo = b.balance(); // debit - credit
            BigDecimal value;
            if (takeDebitSide) {
                if (saldo.signum() <= 0) continue;
                value = saldo;
            } else {
                if (saldo.signum() >= 0) continue;
                value = saldo.negate();
            }
            items.add(new BalanceItem(b.code, b.name, value));
            total = total.add(value);
        }
        return new BalanceSection(name, items, total);
    }

    private BigDecimal sum(BalanceSection... s) {
        BigDecimal total = BigDecimal.ZERO;
        for (BalanceSection x : s) total = total.add(x.total());
        return total;
    }

    // ====================================================================
    //  PyG
    // ====================================================================

    public ProfitAndLoss profitAndLoss(LocalDate from, LocalDate to) {
        String companyId = tenantContext.getCurrentCompanyId();
        List<AccountBalance> all = balancesBetween(companyId, from, to);

        // Ingresos (grupo 7) → saldo acreedor.
        BalanceSection ingresosExplotacion = section("Importe neto de la cifra de negocios", all, code ->
                code.startsWith("70") || code.startsWith("75"), false);
        BalanceSection otrosIngresos = section("Otros ingresos de explotación", all, code ->
                code.startsWith("74") || code.startsWith("75") || code.startsWith("76")
                        || code.startsWith("77"), false);
        // Gastos (grupo 6) → saldo deudor.
        BalanceSection aprovisionamientos = section("Aprovisionamientos", all, code -> code.startsWith("60"), true);
        BalanceSection personal = section("Gastos de personal", all, code ->
                code.startsWith("64"), true);
        BalanceSection otrosGastos = section("Otros gastos de explotación", all, code ->
                code.startsWith("62") || code.startsWith("63") || code.startsWith("65")
                        || code.startsWith("66") || code.startsWith("67")
                        || code.startsWith("69"), true);
        BalanceSection amortizaciones = section("Amortizaciones", all, code -> code.startsWith("68"), true);

        BigDecimal totalIngresos = ingresosExplotacion.total().add(otrosIngresos.total());
        BigDecimal totalGastos = aprovisionamientos.total().add(personal.total())
                .add(otrosGastos.total()).add(amortizaciones.total());
        BigDecimal resultadoExplotacion = totalIngresos.subtract(totalGastos);

        return new ProfitAndLoss(from, to,
                List.of(ingresosExplotacion, otrosIngresos), totalIngresos,
                List.of(aprovisionamientos, personal, otrosGastos, amortizaciones), totalGastos,
                resultadoExplotacion);
    }

    // ====================================================================
    //  ECPN simplificado: variación cuentas 100-129 entre dos fechas.
    // ====================================================================

    public List<EquityMovementRow> equityChanges(LocalDate from, LocalDate to) {
        String companyId = tenantContext.getCurrentCompanyId();
        // Saldo a `from` y saldo a `to` por cuenta 1xx.
        Map<String, BigDecimal> openMap = saldosPorCuenta(companyId, null,
                from == null ? null : from.minusDays(1), "1");
        Map<String, BigDecimal> closeMap = saldosPorCuenta(companyId, null, to, "1");
        Map<String, String> names = accountNames(companyId, "1");

        Map<String, EquityMovementRow> merged = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : openMap.entrySet()) {
            String code = e.getKey();
            BigDecimal open = e.getValue();
            BigDecimal close = closeMap.getOrDefault(code, BigDecimal.ZERO);
            merged.put(code, new EquityMovementRow(code, names.getOrDefault(code, code),
                    open.negate(), close.negate(), close.subtract(open).negate()));
        }
        for (Map.Entry<String, BigDecimal> e : closeMap.entrySet()) {
            merged.computeIfAbsent(e.getKey(), code -> new EquityMovementRow(
                    code, names.getOrDefault(code, code),
                    BigDecimal.ZERO, e.getValue().negate(), e.getValue().negate()));
        }
        return new ArrayList<>(merged.values());
    }

    // ====================================================================
    //  Helpers de query
    // ====================================================================

    private List<AccountBalance> balancesAt(String companyId, LocalDate asOf) {
        // CONTA-4 - mismo fallo que en Sumas y saldos: con el estado en el ON de
        // un LEFT JOIN, las lineas de asientos ANULADOS seguian sumando. Se
        // agrega con CASE sobre je.id IS NULL ("no cumplio el join").
        return jdbcTemplate.query("""
                SELECT a.code, a.name,
                       COALESCE(SUM(CASE WHEN je.id IS NULL THEN 0 ELSE l.debit END), 0) AS d,
                       COALESCE(SUM(CASE WHEN je.id IS NULL THEN 0 ELSE l.credit END), 0) AS c
                  FROM accounting_accounts a
                  LEFT JOIN journal_entry_lines l ON l.account_id = a.id
                  LEFT JOIN journal_entries je ON je.id = l.journal_entry_id
                                              AND je.status = 'POSTED'
                                              AND je.entry_date <= ?
                 WHERE a.company_id = ?
                 GROUP BY a.id, a.code, a.name
                 ORDER BY a.code
                """,
                (rs, n) -> new AccountBalance(rs.getString("code"), rs.getString("name"),
                        rs.getBigDecimal("d"), rs.getBigDecimal("c")),
                Date.valueOf(asOf), companyId);
    }

    private List<AccountBalance> balancesBetween(String companyId, LocalDate from, LocalDate to) {
        // CONTA-4 - idem: los asientos anulados no cuentan, y una linea fuera del
        // rango tampoco (antes se colaba porque je.entry_date quedaba a NULL).
        StringBuilder sql = new StringBuilder("""
                SELECT a.code, a.name,
                       COALESCE(SUM(CASE WHEN je.id IS NULL THEN 0 ELSE l.debit END), 0) AS d,
                       COALESCE(SUM(CASE WHEN je.id IS NULL THEN 0 ELSE l.credit END), 0) AS c
                  FROM accounting_accounts a
                  LEFT JOIN journal_entry_lines l ON l.account_id = a.id
                  LEFT JOIN journal_entries je ON je.id = l.journal_entry_id
                                              AND je.status = 'POSTED'
                """);
        List<Object> args = new ArrayList<>();
        if (from != null) { sql.append(" AND je.entry_date >= ?"); args.add(Date.valueOf(from)); }
        if (to != null)   { sql.append(" AND je.entry_date <= ?"); args.add(Date.valueOf(to)); }
        sql.append(" WHERE a.company_id = ?");
        args.add(companyId);
        sql.append(" GROUP BY a.id, a.code, a.name ORDER BY a.code");
        return jdbcTemplate.query(sql.toString(),
                (rs, n) -> new AccountBalance(rs.getString("code"), rs.getString("name"),
                        rs.getBigDecimal("d"), rs.getBigDecimal("c")),
                args.toArray());
    }

    private Map<String, BigDecimal> saldosPorCuenta(String companyId,
                                                       LocalDate from, LocalDate to,
                                                       String prefix) {
        List<AccountBalance> raw = balancesBetween(companyId, from, to);
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (AccountBalance b : raw) {
            if (prefix != null && !b.code.startsWith(prefix)) continue;
            out.put(b.code, b.balance());
        }
        return out;
    }

    private Map<String, String> accountNames(String companyId, String prefix) {
        Map<String, String> map = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT code, name FROM accounting_accounts
                 WHERE company_id = ? AND code LIKE ? AND active = TRUE
                 ORDER BY code
                """, (rs) -> {
                    map.put(rs.getString("code"), rs.getString("name"));
                }, companyId, prefix + "%");
        return map;
    }

    // ====================================================================
    //  DTOs
    // ====================================================================

    public record BalanceSheet(
            LocalDate asOf,
            List<BalanceSection> activo, BigDecimal totalActivo,
            List<BalanceSection> pasivo, BigDecimal totalPasivo
    ) {}

    public record BalanceSection(String name, List<BalanceItem> items, BigDecimal total) {}

    public record BalanceItem(String code, String name, BigDecimal amount) {}

    public record ProfitAndLoss(
            LocalDate from, LocalDate to,
            List<BalanceSection> ingresos, BigDecimal totalIngresos,
            List<BalanceSection> gastos, BigDecimal totalGastos,
            BigDecimal resultadoExplotacion
    ) {}

    public record EquityMovementRow(
            String code, String name,
            BigDecimal openingBalance, BigDecimal closingBalance, BigDecimal variation
    ) {}

    private record AccountBalance(String code, String name, BigDecimal debit, BigDecimal credit) {
        BigDecimal balance() { return debit.subtract(credit); }
    }
}
