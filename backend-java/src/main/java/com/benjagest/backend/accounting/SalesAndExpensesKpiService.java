package com.benjagest.backend.accounting;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
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
            int draftCount                // asientos sin validar
    ) {}

    public Kpis compute(LocalDate from, LocalDate to) {
        String companyId = tenantContext.getCurrentCompanyId();
        Date dFrom = Date.valueOf(from);
        Date dTo = Date.valueOf(to);

        // Ventas = suma del HABER de cuentas 7xx en asientos POSTED del
        // rango. Cuenta = nº de asientos distintos (no de líneas).
        BigDecimal salesTotal = sumLineAmount(companyId, dFrom, dTo,
                "7", /*credit=*/true);
        int salesCount = countEntriesWithAccountPrefix(companyId, dFrom, dTo, "7");

        // Gastos = suma del DEBE de cuentas 6xx en asientos POSTED.
        BigDecimal expensesTotal = sumLineAmount(companyId, dFrom, dTo,
                "6", /*credit=*/false);
        int expensesCount = countEntriesWithAccountPrefix(companyId, dFrom, dTo, "6");

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

        return new Kpis(salesTotal, salesCount, expensesTotal, expensesCount,
                vatCharged, vatBorne, model303,
                drafts == null ? 0 : drafts);
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

    /** Cuenta asientos DISTINTOS que tengan alguna línea con ese prefijo. */
    private int countEntriesWithAccountPrefix(String companyId, Date from, Date to,
                                               String accountPrefix) {
        Integer c = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT e.id)
                  FROM journal_entries e
                  JOIN journal_entry_lines l ON l.journal_entry_id = e.id
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE e.company_id = ?
                   AND e.status = 'POSTED'
                   AND e.entry_date BETWEEN ? AND ?
                   AND a.code LIKE ?
                """, Integer.class, companyId, from, to, accountPrefix + "%");
        return c == null ? 0 : c;
    }
}
