package com.benjagest.backend.accounting;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * FIN-1 — Cuadro de mando financiero del cliente (KPIs nivel 1).
 *
 * <p>Servicio tenant-scoped que reúne los indicadores financieros de la
 * empresa activa para un periodo. Reutiliza la lógica de
 * {@link SalesAndExpensesKpiService} (ventas/gastos/IVA/drafts, todo
 * derivado del Libro Diario POSTED) y añade:
 * <ul>
 *   <li><b>Coste de personal</b> = DEBE de cuentas 64x (sueldos + SS empresa).</li>
 *   <li><b>Beneficio</b> = ingresos − gastos.</li>
 *   <li><b>Ratios</b>: margen %, gasto/ingreso %, coste personal/ingreso %.</li>
 *   <li><b>Tesorería (cobros)</b>: pendiente de cobro y facturas vencidas
 *       de {@code sales_invoices} de la propia empresa.</li>
 * </ul>
 *
 * <p><b>Pendiente (FIN-1b)</b>: pendiente de PAGO a proveedores. La tabla
 * {@code purchase_invoices} cambió de esquema en V45 (se dropeó
 * {@code payment_status}); el seguimiento de pagos se hace por otra vía, así
 * que no lo incluimos aquí hasta verificar el modelo actual (regla 10.bis).
 *
 * <p>Sólo es fiable para clientes que llevan la contabilidad en BENJAGEST.
 * El número de asientos DRAFT se devuelve como aviso de fiabilidad (hay
 * movimientos sin validar que no entran en los importes POSTED).
 */
@Service
public class ClientFinancialsService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final SalesAndExpensesKpiService kpiService;

    public ClientFinancialsService(JdbcTemplate jdbc, TenantContext tenant,
                                     SalesAndExpensesKpiService kpiService) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.kpiService = kpiService;
    }

    public record ClientFinancials(
            LocalDate from, LocalDate to,
            BigDecimal income,            // ingresos (7xx haber)
            BigDecimal expenses,          // gastos (6xx debe)
            BigDecimal result,            // beneficio = ingresos − gastos
            BigDecimal personnelCost,     // coste personal (64x debe)
            BigDecimal vatCharged,        // IVA repercutido (477)
            BigDecimal vatBorne,          // IVA soportado (472)
            BigDecimal model303Estimated, // 477 − 472
            BigDecimal pendingCollections,// pendiente de cobro (ventas)
            int overdueInvoices,          // facturas de venta vencidas sin cobrar
            BigDecimal pendingPayments,   // pendiente de pago (saldo acreedor 400/410)
            BigDecimal marginPct,         // result / income * 100
            BigDecimal expenseRatioPct,   // expenses / income * 100
            BigDecimal personnelRatioPct, // personnelCost / income * 100
            int draftCount,               // asientos sin validar (aviso fiabilidad)
            /**
             * PAGO-2 — Cuántas facturas de proveedor están SIN PAGAR. El importe
             * de arriba ({@code pendingPayments}) es el saldo contable 400/410 y
             * puede incluir cosas que no son facturas (apuntes manuales), así que
             * NO son dos formas de decir lo mismo: este contador existe para
             * poder ir a VERLAS ("¿qué facturas me faltan por pagar?", que es lo
             * que el saldo por sí solo no responde).
             */
            int unpaidPurchaseInvoices
    ) {}

    public ClientFinancials compute(LocalDate from, LocalDate to) {
        String companyId = tenant.getCurrentCompanyId();
        SalesAndExpensesKpiService.Kpis k = kpiService.compute(from, to);

        BigDecimal income = nz(k.salesTotal());
        BigDecimal expenses = nz(k.expensesTotal());
        BigDecimal result = income.subtract(expenses);
        BigDecimal personnel = sumDebitPrefix(companyId, from, to, "64");

        BigDecimal pending = pendingCollections(companyId);
        int overdue = overdueInvoices(companyId);
        BigDecimal pendingPay = pendingPayments(companyId, to);

        return new ClientFinancials(
                from, to, income, expenses, result, personnel,
                nz(k.vatCharged()), nz(k.vatBorne()), nz(k.model303Estimated()),
                pending, overdue, pendingPay,
                pct(result, income), pct(expenses, income), pct(personnel, income),
                pendingValidationCount(companyId),
                unpaidPurchaseInvoices(companyId));
    }

    /**
     * PAGO-2 — Nº de facturas de proveedor SIN PAGAR (vivas: se excluyen las
     * anuladas). Con PAGO-1 el flag {@code paid} ya es fiable: lo escriben los
     * dos caminos de pago (Registrar pago y Vencimientos).
     */
    private int unpaidPurchaseInvoices(String companyId) {
        Integer c = jdbc.queryForObject("""
                SELECT COUNT(*) FROM purchase_invoices
                 WHERE company_id = ? AND paid = FALSE AND status <> 'VOID'
                """, Integer.class, companyId);
        return c == null ? 0 : c;
    }

    /**
     * Asientos realmente "por validar": DRAFT + {@code auto_proposed=TRUE}.
     * Debe COINCIDIR con lo que muestra la pestaña "Por validar" (que filtra
     * por auto-propuestos). Los DRAFT manuales (p.ej. aplicar una plantilla
     * sin contabilizar, o un asiento manual en borrador) NO son "por validar"
     * — se gestionan desde el Diario — así que NO se cuentan aquí, para no
     * mandar al usuario a una pestaña vacía. {@code k.draftCount()} (todos los
     * DRAFT) era el origen del desajuste.
     */
    private int pendingValidationCount(String companyId) {
        Integer c = jdbc.queryForObject("""
                SELECT COUNT(*) FROM journal_entries
                 WHERE company_id = ? AND status = 'DRAFT' AND auto_proposed = TRUE
                """, Integer.class, companyId);
        return c == null ? 0 : c;
    }

    /**
     * Pendiente de pago a proveedores/acreedores = saldo ACREEDOR (haber − debe)
     * de las cuentas 400 (Proveedores) y 410 (Acreedores por prestaciones de
     * servicios) acumulado hasta la fecha, en asientos POSTED. Es la medida
     * contable robusta tras la reestructuración de purchase_invoices (V45): no
     * depende del estado de cada factura, que hoy se rastrea por conciliación
     * bancaria. Si el saldo fuese deudor (raro), se devuelve 0.
     */
    private BigDecimal pendingPayments(String companyId, LocalDate to) {
        BigDecimal v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.credit - l.debit), 0)
                  FROM journal_entry_lines l
                  JOIN journal_entries e ON e.id = l.journal_entry_id
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE e.company_id = ?
                   AND e.status = 'POSTED'
                   AND e.entry_date <= ?
                   AND (a.code LIKE '400%' OR a.code LIKE '410%')
                """, BigDecimal.class, companyId, Date.valueOf(to));
        if (v == null || v.signum() < 0) return BigDecimal.ZERO;
        return v;
    }

    // ====================================================================
    //  FIN-2 — Evolución mensual (serie del año)
    // ====================================================================

    public record MonthPoint(int month, BigDecimal income, BigDecimal expenses, BigDecimal result) {}

    /**
     * Serie mensual (12 puntos) de ingresos/gastos/beneficio del año, desde
     * el diario POSTED. Los meses sin movimiento salen a cero.
     */
    public java.util.List<MonthPoint> monthlySeries(int year) {
        String companyId = tenant.getCurrentCompanyId();
        BigDecimal[] income = new BigDecimal[13];
        BigDecimal[] expenses = new BigDecimal[13];
        for (int i = 1; i <= 12; i++) { income[i] = BigDecimal.ZERO; expenses[i] = BigDecimal.ZERO; }
        jdbc.query("""
                SELECT MONTH(e.entry_date) AS m,
                       COALESCE(SUM(CASE WHEN a.code LIKE '7%' THEN l.credit ELSE 0 END), 0) AS income,
                       COALESCE(SUM(CASE WHEN a.code LIKE '6%' THEN l.debit  ELSE 0 END), 0) AS expenses
                  FROM journal_entries e
                  JOIN journal_entry_lines l ON l.journal_entry_id = e.id
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE e.company_id = ?
                   AND e.status = 'POSTED'
                   AND YEAR(e.entry_date) = ?
                 GROUP BY MONTH(e.entry_date)
                """, rs -> {
            int m = rs.getInt("m");
            if (m >= 1 && m <= 12) {
                income[m] = rs.getBigDecimal("income");
                expenses[m] = rs.getBigDecimal("expenses");
            }
        }, companyId, year);
        java.util.List<MonthPoint> out = new java.util.ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            BigDecimal inc = nz(income[m]);
            BigDecimal exp = nz(expenses[m]);
            out.add(new MonthPoint(m, inc, exp, inc.subtract(exp)));
        }
        return out;
    }

    // ====================================================================
    //  FIN-3 — Proyección de cierre + IS estimado
    // ====================================================================

    public record ClosingProjection(
            int year, int monthsElapsed,
            BigDecimal resultToDate,        // beneficio acumulado YTD (POSTED)
            BigDecimal projectedResult,     // extrapolado a fin de año (lineal)
            BigDecimal estimatedCorporateTax, // IS estimado (25% si beneficio > 0)
            BigDecimal projectedAfterTax
    ) {}

    /**
     * Proyección ORIENTATIVA de cierre: extrapola linealmente el beneficio
     * acumulado del año a 12 meses y estima el Impuesto de Sociedades al tipo
     * general del 25% (igual que la precalculadora de cierre). NO es una
     * declaración: es una estimación para tesorería/planificación. Tan fiable
     * como el histórico (negocios estacionales se desvían).
     */
    public ClosingProjection projectYearEnd(int year) {
        LocalDate today = LocalDate.now();
        boolean currentYear = (year == today.getYear());
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = currentYear ? today : LocalDate.of(year, 12, 31);

        ClientFinancials ytd = compute(from, to);
        BigDecimal resultToDate = ytd.result();
        int monthsElapsed = currentYear ? today.getMonthValue() : 12;

        BigDecimal projected = (currentYear && monthsElapsed > 0)
                ? resultToDate.multiply(BigDecimal.valueOf(12))
                        .divide(BigDecimal.valueOf(monthsElapsed), 2, RoundingMode.HALF_UP)
                : resultToDate;

        BigDecimal tax = projected.signum() > 0
                ? projected.multiply(new BigDecimal("0.25")).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new ClosingProjection(year, monthsElapsed, resultToDate,
                projected, tax, projected.subtract(tax));
    }

    /** Suma del DEBE de líneas con cuenta de prefijo dado en asientos POSTED del rango. */
    private BigDecimal sumDebitPrefix(String companyId, LocalDate from, LocalDate to, String prefix) {
        BigDecimal v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.debit), 0)
                  FROM journal_entry_lines l
                  JOIN journal_entries e ON e.id = l.journal_entry_id
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE e.company_id = ?
                   AND e.status = 'POSTED'
                   AND e.entry_date BETWEEN ? AND ?
                   AND a.code LIKE ?
                """, BigDecimal.class, companyId,
                Date.valueOf(from), Date.valueOf(to), prefix + "%");
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Pendiente de cobro = suma de (total − cobrado) de facturas VALIDATED aún
     * PENDING/PARTIAL. Se excluyen las rectificativas que anulan una original
     * ya VOIDED: la original excluida y su rectificativa (importe negativo)
     * netean a cero, así que dejar viva solo la negativa restaba de más
     * (bug: una anulada + su rectificativa hacían 968 − 701,80 = 266,20 y
     * contaban como 2 vencidas, cuando lo pendiente real era solo la de 968).
     * Las rectificativas parciales de una factura AÚN abierta (original no
     * VOIDED) sí siguen restando, que es lo correcto.
     */
    private BigDecimal pendingCollections(String companyId) {
        BigDecimal v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(si.total - COALESCE(si.paid_amount, 0)), 0)
                  FROM sales_invoices si
                 WHERE si.company_id = ?
                   AND si.status = 'VALIDATED'
                   AND si.payment_status IN ('PENDING', 'PARTIAL')
                   AND NOT (si.original_invoice_id IS NOT NULL AND EXISTS (
                            SELECT 1 FROM sales_invoices o
                             WHERE o.id = si.original_invoice_id AND o.status = 'VOIDED'))
                """, BigDecimal.class, companyId);
        return v == null ? BigDecimal.ZERO : v;
    }

    private int overdueInvoices(String companyId) {
        Integer c = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sales_invoices si
                 WHERE si.company_id = ?
                   AND si.status = 'VALIDATED'
                   AND si.payment_status IN ('PENDING', 'PARTIAL')
                   AND si.due_date IS NOT NULL
                   AND si.due_date < CURRENT_DATE
                   AND NOT (si.original_invoice_id IS NOT NULL AND EXISTS (
                            SELECT 1 FROM sales_invoices o
                             WHERE o.id = si.original_invoice_id AND o.status = 'VOIDED'))
                """, Integer.class, companyId);
        return c == null ? 0 : c;
    }

    /** porcentaje = part / base * 100, con 1 decimal; 0 si la base es 0. */
    private BigDecimal pct(BigDecimal part, BigDecimal base) {
        if (base == null || base.signum() == 0) return BigDecimal.ZERO;
        return part.multiply(BigDecimal.valueOf(100))
                .divide(base, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
