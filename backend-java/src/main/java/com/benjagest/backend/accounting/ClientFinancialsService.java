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
            BigDecimal marginPct,         // result / income * 100
            BigDecimal expenseRatioPct,   // expenses / income * 100
            BigDecimal personnelRatioPct, // personnelCost / income * 100
            int draftCount                // asientos sin validar (aviso fiabilidad)
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

        return new ClientFinancials(
                from, to, income, expenses, result, personnel,
                nz(k.vatCharged()), nz(k.vatBorne()), nz(k.model303Estimated()),
                pending, overdue,
                pct(result, income), pct(expenses, income), pct(personnel, income),
                k.draftCount());
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

    private BigDecimal pendingCollections(String companyId) {
        BigDecimal v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(si.total - COALESCE(si.paid_amount, 0)), 0)
                  FROM sales_invoices si
                 WHERE si.company_id = ?
                   AND si.status = 'VALIDATED'
                   AND si.payment_status IN ('PENDING', 'PARTIAL')
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
