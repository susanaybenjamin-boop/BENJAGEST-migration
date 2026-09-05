package com.benjagest.backend.accounting;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * KPIs rápidos para la vista "Ventas y Gastos" del cliente NO vinculado.
 *
 * <p>Calcula totales agregados directamente desde {@code journal_entries}
 * + {@code journal_entry_lines} + {@code accounting_accounts} para un
 * rango de fechas. Solo cuenta asientos {@code POSTED} (validados) —
 * los DRAFT entran aparte como "pendientes".
 *
 * <p>Modelo 303 estimado:
 * <pre>
 *   Modelo 303 = IVA repercutido (477) - IVA soportado (472)
 *   > 0 → a pagar a Hacienda
 *   < 0 → a devolver
 * </pre>
 *
 * <p>Está pensado para refresco rápido (debe ejecutar en menos de
 * 200ms para un trimestre típico con 50-200 asientos). No tiene
 * caché — cada llamada relanza los queries; si en el futuro hay
 * cargas problemáticas, se puede precomputar.
 */
@Service
public class SalesAndExpensesKpiService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public SalesAndExpensesKpiService(JdbcTemplate jdbcTemplate,
                                       TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public record Kpis(
            BigDecimal salesTotal,
            int salesCount,
            BigDecimal expensesTotal,
            int expensesCount,
            BigDecimal vatCharged,        // IVA repercutido (cuentas 477)
            BigDecimal vatBorne,          // IVA soportado    (cuentas 472)
            BigDecimal model303Estimated, // vatCharged - vatBorne
            int draftCount,               // asientos sin validar
            // M130-1 (2026-09-05, peticion Benjamin): pago fraccionado de IRPF
            // estimado. ACUMULADO del anio hasta el fin del rango, no del
            // trimestre suelto: el modelo 130 se declara asi.
            // M130-3: es el pago DEL TRIMESTRE (acumulado del anio menos los
            // trimestres anteriores), no el acumulado a secas.
            BigDecimal model130Estimated,
            // Trimestre (1-4) al que corresponde esa cifra, derivado de la fecha
            // "hasta" del filtro. La UI lo pinta para que se vea a que periodo
            // se refiere y no parezca una cifra congelada.
            int model130Quarter,
            // El 130 solo lo presenta el autonomo en estimacion directa. Si el
            // cliente es SL/SA o esta en modulos, la tarjeta no se pinta (un
            // "IRPF a pagar" en la ficha de una sociedad seria informacion falsa).
            boolean model130Applicable
    ) {}

    public Kpis compute(LocalDate from, LocalDate to) {
        String companyId = tenantContext.getCurrentCompanyId();
        Date dFrom = Date.valueOf(from);
        Date dTo = Date.valueOf(to);

        // Ventas = suma del HABER de cuentas 7xx en asientos POSTED del
        // rango. Cuenta = nº de asientos distintos (no de líneas).
        BigDecimal salesTotal = sumLineAmount(companyId, dFrom, dTo,
                "7", /*credit=*/true);
        int salesCount = countEntriesWithAccountPrefix(companyId, dFrom, dTo, "7", false);

        // Gastos = suma del DEBE de cuentas 6xx en asientos POSTED. El TOTAL sí
        // incluye nómina (640/642 son gasto real), pero el CONTADOR "N facturas"
        // excluye los asientos de nómina (no son facturas) — petición Benjamin.
        BigDecimal expensesTotal = sumLineAmount(companyId, dFrom, dTo,
                "6", /*credit=*/false);
        int expensesCount = countEntriesWithAccountPrefix(companyId, dFrom, dTo, "6", true);

        // IVA repercutido (477) en HABER. IVA soportado (472) en DEBE.
        BigDecimal vatCharged = sumLineAmount(companyId, dFrom, dTo,
                "477", true);
        BigDecimal vatBorne = sumLineAmount(companyId, dFrom, dTo,
                "472", false);

        BigDecimal model303 = vatCharged.subtract(vatBorne);

        // Drafts pendientes de validar: cualquier asiento DRAFT, sin
        // filtrar por fecha (los DRAFT viejos también molestan).
        Integer drafts = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM journal_entries
                 WHERE company_id = ? AND status = 'DRAFT'
                """, Integer.class, companyId);

        // M130-1 — Modelo 130 estimado. Misma fuente que el resto de tarjetas
        // (los ASIENTOS), para que las cifras del cuadro cuadren entre si. Ojo:
        // el 130 oficial de Fiscal (AeatExtraModelsService.generate130) suma
        // desde sales_invoices/purchase_invoices, asi que puede diferir si hay
        // ventas metidas como asiento manual. La FORMULA es la misma: se reusa
        // compute130 para no duplicar las constantes legales (5% de dificil
        // justificacion con tope 2.000 EUR, tipo 20%).
        BigDecimal model130 = computeModel130Estimate(companyId, to);

        return new Kpis(salesTotal, salesCount, expensesTotal, expensesCount,
                vatCharged, vatBorne, model303,
                drafts == null ? 0 : drafts,
                model130, (to.getMonthValue() - 1) / 3 + 1,
                isModel130Applicable(companyId));
    }

    /**
     * Estimacion del modelo 130 a partir de los asientos POSTED, acumulada
     * desde el 1 de enero hasta {@code to} (el 130 es acumulativo dentro del
     * anio; declarar solo el trimestre suelto daria una cifra que no es la
     * del modelo).
     *
     * <ul>
     *   <li>Ingresos = HABER de 7xx.</li>
     *   <li>Gastos   = DEBE de 6xx.</li>
     *   <li>Retenciones = DEBE de 473 (las que le practicaron al autonomo).</li>
     *   <li>Pagos fraccionados previos = 130 de trimestres anteriores del mismo
     *       anio ya PRESENTADOS o PAGADOS (mismo criterio que generate130: un
     *       borrador no presentado no es un pago).</li>
     * </ul>
     */
    private BigDecimal computeModel130Estimate(String companyId, LocalDate to) {
        int year = to.getYear();
        int quarter = (to.getMonthValue() - 1) / 3 + 1;

        // M130-3 — pagos fraccionados de los trimestres ANTERIORES del mismo
        // anio, en cascada. Para cada uno:
        //   - si su 130 esta PRESENTADO/PAGADO en Fiscal, manda ESE importe
        //     (decision Benjamin: la estimacion no debe contradecir a lo que ya
        //     se mando a Hacienda);
        //   - si no existe, se calcula con la propia contabilidad, en vez de
        //     contarlo como 0 e inflar el resultado del trimestre en curso.
        BigDecimal pagosPrevios = BigDecimal.ZERO;
        for (int q = 1; q < quarter; q++) {
            BigDecimal presentado = presentedFiling130(companyId, year, q);
            BigDecimal pagoDelQ = presentado != null
                    ? presentado
                    : model130PaymentUpTo(companyId, year, endOfQuarter(year, q), pagosPrevios);
            pagosPrevios = pagosPrevios.add(pagoDelQ);
        }

        // El resultado del trimestre en curso: acumulado de enero hasta la fecha
        // "hasta" del filtro, menos lo de los trimestres anteriores.
        return model130PaymentUpTo(companyId, year, to, pagosPrevios);
    }

    /**
     * Resultado del modelo 130 acumulando de 1 de enero hasta {@code to} y
     * descontando {@code pagosPrevios}. La formula es la legal y NO se duplica:
     * se reusa {@code AeatExtraModelsService.compute130}.
     */
    private BigDecimal model130PaymentUpTo(String companyId, int year, LocalDate to,
                                            BigDecimal pagosPrevios) {
        Date ytdFrom = Date.valueOf(LocalDate.of(year, 1, 1));
        Date ytdTo = Date.valueOf(to);

        BigDecimal ingresos = sumLineAmount(companyId, ytdFrom, ytdTo, "7", true);
        BigDecimal gastos = sumLineAmount(companyId, ytdFrom, ytdTo, "6", false);
        // CONTA-2 (2026-09-05) — Retenciones que le practicaron al autonomo, SIN
        // contar los pagos fraccionados del propio 130.
        //
        // Bug encontrado por Benjamin en produccion ("el 130 en produccion me
        // sale a cero"): la 473 "H.P. retenciones y pagos a cuenta" recoge DOS
        // cosas distintas —las retenciones de los clientes Y los pagos del 130—
        // y aqui se sumaba entera. Como los pagos previos ya se restan aparte
        // (desde tax_filings), los pagos del 130 se restaban DOS VECES y el
        // resultado se iba a 0. En su BD la 473 tenia exactamente los dos pagos
        // del 130 (827,04 del 1T + 693,36 del 2T = 1.520,40) y ninguna retencion
        // real: se restaban 3.040,80 en vez de 1.520,40.
        BigDecimal retenciones = sumRetentionsExcludingTaxPayments(companyId, ytdFrom, ytdTo);

        return com.benjagest.backend.aeat.AeatExtraModelsService
                .compute130(ingresos, gastos, retenciones, pagosPrevios)
                .pago();
    }

    /** Importe del 130 de ese trimestre si ya esta presentado o pagado; null si no. */
    private BigDecimal presentedFiling130(String companyId, int year, int quarter) {
        List<BigDecimal> rows = jdbcTemplate.query("""
                SELECT total_amount FROM tax_filings
                 WHERE company_id = ? AND tax_model_code = '130'
                   AND period_year = ? AND period_quarter = ?
                   AND status IN ('PRESENTED', 'PAID')
                 LIMIT 1
                """, (rs, n) -> rs.getBigDecimal("total_amount"), companyId, year, quarter);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static LocalDate endOfQuarter(int year, int quarter) {
        return LocalDate.of(year, quarter * 3, 1)
                .withDayOfMonth(LocalDate.of(year, quarter * 3, 1).lengthOfMonth());
    }

    /**
     * El 130 aplica al autonomo en estimacion directa. Se mira la forma
     * juridica ({@code companies.legal_form}, V120) y el regimen fiscal
     * ({@code client_advisory_config.tax_regime}, V119), ambos editables desde la ficha
     * del cliente.
     *
     * <p>Si un campo dice explicitamente que NO (SL/SA/... o MODULOS/
     * SOCIEDADES), no aplica. Si ninguno de los dos esta relleno, se muestra
     * igualmente (fail-open): el caso tipico del cliente no vinculado es el
     * autonomo pequenio, y esconder la tarjeta por una ficha a medio rellenar
     * seria mas confuso que ensenarla.
     */
    private boolean isModel130Applicable(String companyId) {
        String legalForm = jdbcTemplate.query(
                "SELECT legal_form FROM companies WHERE id = ?",
                rs -> rs.next() ? rs.getString("legal_form") : null, companyId);
        String taxRegime = jdbcTemplate.query(
                "SELECT tax_regime FROM client_advisory_config WHERE company_id = ?",
                rs -> rs.next() ? rs.getString("tax_regime") : null, companyId);

        boolean legalFormSet = legalForm != null && !legalForm.isBlank();
        boolean regimeSet = taxRegime != null && !taxRegime.isBlank();
        if (!legalFormSet && !regimeSet) return true; // ficha sin rellenar

        if (legalFormSet && !"AUTONOMO".equalsIgnoreCase(legalForm)) return false;
        if (regimeSet && !"ESTIMACION_DIRECTA".equalsIgnoreCase(taxRegime)) return false;
        return true;
    }

    /**
     * CONTA-2 — Suma del DEBE de la 473 excluyendo los asientos de pago o
     * liquidacion de impuestos ({@code TAX_PAYMENT} / {@code TAX_LIQUIDATION},
     * las constantes de {@code TaxLedgerService}). Lo que queda son las
     * retenciones que los clientes le practicaron, que es lo que pide la
     * casilla de retenciones del 130.
     */
    private BigDecimal sumRetentionsExcludingTaxPayments(String companyId, Date from, Date to) {
        BigDecimal v = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(l.debit), 0)
                  FROM journal_entry_lines l
                  JOIN journal_entries e ON e.id = l.journal_entry_id
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE e.company_id = ?
                   AND e.status = 'POSTED'
                   AND e.entry_date BETWEEN ? AND ?
                   AND a.code LIKE '473%'
                   AND (e.source_type IS NULL
                        OR e.source_type NOT IN ('TAX_PAYMENT', 'TAX_LIQUIDATION'))
                """, BigDecimal.class, companyId, from, to);
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Suma debe/haber de líneas cuyo código de cuenta empieza por el
     * prefijo dado, dentro de asientos POSTED del rango.
     */
    private BigDecimal sumLineAmount(String companyId, Date from, Date to,
                                       String accountPrefix, boolean credit) {
        String col = credit ? "l.credit" : "l.debit";
        BigDecimal v = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(""" + col + """
                ), 0)
                  FROM journal_entry_lines l
                  JOIN journal_entries e ON e.id = l.journal_entry_id
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE e.company_id = ?
                   AND e.status = 'POSTED'
                   AND e.entry_date BETWEEN ? AND ?
                   AND a.code LIKE ?
                """, BigDecimal.class, companyId, from, to, accountPrefix + "%");
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Cuenta asientos DISTINTOS que tengan alguna línea con ese prefijo. Si
     * {@code excludePayroll} es true, ignora los asientos de nómina
     * (source_type PAYSLIP_*) — no son "facturas" y desvirtuaban el contador
     * de gastos.
     */
    private int countEntriesWithAccountPrefix(String companyId, Date from, Date to,
                                               String accountPrefix, boolean excludePayroll) {
        String payrollFilter = excludePayroll
                ? " AND (e.source_type IS NULL OR e.source_type NOT LIKE 'PAYSLIP%')"
                : "";
        Integer c = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT e.id)
                  FROM journal_entries e
                  JOIN journal_entry_lines l ON l.journal_entry_id = e.id
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE e.company_id = ?
                   AND e.status = 'POSTED'
                   AND e.entry_date BETWEEN ? AND ?
                   AND a.code LIKE ?
                """ + payrollFilter,
                Integer.class, companyId, from, to, accountPrefix + "%");
        return c == null ? 0 : c;
    }
}
