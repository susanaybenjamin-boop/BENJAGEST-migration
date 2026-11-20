package com.benjagest.backend.billing.kpi;

import com.benjagest.backend.billing.invoices.SalesInvoiceSql;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * DASH-E2 (2026-11-20) — cuadro de mando de FACTURACION.
 *
 * <p>Nace de la peticion de Benjamin: <i>"el dashboard de facturacion en el
 * modo empresario lo veo vacio, me gustaria que se viera como en contabilidad
 * el cuadro de mando, incluso con los filtros"</i>. Lo que habia era un
 * placeholder del "slice F6" con cuatro CONTADORES de facturas (sin importes,
 * sin fechas y sin filtros) calculados en la UI sobre un listado cortado a 200
 * facturas.
 *
 * <p><b>Diferencia con {@code ClientFinancialsService}</b> (el cuadro de mando
 * de Contabilidad), que NO se duplica aqui: ese mide el LIBRO DIARIO (cuentas
 * 7xx/6xx de asientos POSTED) y por tanto incluye las ventas metidas como
 * asiento manual sin factura. Este mide las FACTURAS. Las dos cifras son
 * correctas y pueden no coincidir; por eso cada pantalla dice de donde sale la
 * suya. Lo que SI comparten es el criterio de "factura viva"
 * ({@link SalesInvoiceSql}), que era el origen real del descuadre.
 *
 * <p>Todo es tenant-scoped via {@link TenantContext}: el mismo servicio sirve
 * al modo empresario y a la asesoria actuando como cliente.
 */
@Service
public class BillingKpiService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public BillingKpiService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    /**
     * Indicadores de facturacion del rango. Las cifras de IMPORTE son de las
     * facturas EMITIDAS Y VIVAS con {@code invoice_date} dentro del rango; las
     * de borradores y anuladas van aparte porque son avisos, no facturacion.
     */
    public record BillingKpis(
            LocalDate from,
            LocalDate to,
            int issuedCount,               // nº facturas emitidas vivas del rango
            BigDecimal billedBase,         // base imponible (cuadra con "Ingresos" si todo va por factura)
            BigDecimal vatCharged,         // IVA repercutido de esas facturas
            BigDecimal retention,          // retenciones practicadas (IRPF)
            BigDecimal billedTotal,        // total de factura (base + IVA - retencion)
            BigDecimal avgTicket,          // billedTotal / issuedCount
            BigDecimal collected,          // ya cobrado de las facturas del rango
            BigDecimal pendingCollections, // (total - cobrado) de las del rango sin cobrar
            int pendingCount,              // cuantas estan sin cobrar
            BigDecimal overdueAmount,      // de lo pendiente, lo que ya paso su vencimiento
            int overdueCount,
            int draftCount,                // borradores SIN emitir del rango (aviso)
            BigDecimal draftBase,
            int voidedCount,               // anuladas del rango (aviso)
            int customersBilled,           // clientes distintos facturados
            String topCustomerName,        // cliente que mas base factura en el rango
            BigDecimal topCustomerBase
    ) {}

    public BillingKpis compute(LocalDate from, LocalDate to) {
        String companyId = tenant.getCurrentCompanyId();
        Date f = Date.valueOf(from);
        Date t = Date.valueOf(to);

        // Una sola pasada para todo lo agregado de las emitidas vivas: evita
        // cinco viajes a la BD que ademas podrian leer estados distintos si
        // alguien valida una factura entre consulta y consulta.
        Totals tot = jdbc.queryForObject(
                "SELECT COUNT(*) AS n,"
                + " COALESCE(SUM(si.subtotal), 0) AS base,"
                + " COALESCE(SUM(si.vat_total), 0) AS vat,"
                + " COALESCE(SUM(si.retention_total), 0) AS ret,"
                + " COALESCE(SUM(si.total), 0) AS tot,"
                + " COALESCE(SUM(COALESCE(si.paid_amount, 0)), 0) AS paid,"
                + " COUNT(DISTINCT si.customer_id) AS customers"
                + " FROM sales_invoices si"
                + " WHERE si.company_id = ? AND si.invoice_date BETWEEN ? AND ?"
                + SalesInvoiceSql.LIVE_ISSUED,
                (rs, n) -> new Totals(
                        rs.getInt("n"),
                        nz(rs.getBigDecimal("base")),
                        nz(rs.getBigDecimal("vat")),
                        nz(rs.getBigDecimal("ret")),
                        nz(rs.getBigDecimal("tot")),
                        nz(rs.getBigDecimal("paid")),
                        rs.getInt("customers")),
                companyId, f, t);
        if (tot == null) {
            tot = new Totals(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }

        Pending pend = jdbc.queryForObject(
                "SELECT COUNT(*) AS n,"
                + " COALESCE(SUM(si.total - COALESCE(si.paid_amount, 0)), 0) AS amt,"
                + " COALESCE(SUM(CASE WHEN si.due_date IS NOT NULL AND si.due_date < CURRENT_DATE"
                + "                   THEN si.total - COALESCE(si.paid_amount, 0) ELSE 0 END), 0) AS overdue_amt,"
                + " COALESCE(SUM(CASE WHEN si.due_date IS NOT NULL AND si.due_date < CURRENT_DATE"
                + "                   THEN 1 ELSE 0 END), 0) AS overdue_n"
                + " FROM sales_invoices si"
                + " WHERE si.company_id = ? AND si.invoice_date BETWEEN ? AND ?"
                + SalesInvoiceSql.LIVE_ISSUED
                + SalesInvoiceSql.STILL_UNPAID,
                (rs, n) -> new Pending(
                        rs.getInt("n"),
                        nz(rs.getBigDecimal("amt")),
                        nz(rs.getBigDecimal("overdue_amt")),
                        rs.getInt("overdue_n")),
                companyId, f, t);
        if (pend == null) {
            pend = new Pending(0, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }

        // Borradores: NO son facturacion (no tienen numero ni huella SIF) pero
        // si son un aviso — "tienes 3 facturas a medias sin emitir".
        Drafts dr = jdbc.queryForObject(
                "SELECT COUNT(*) AS n, COALESCE(SUM(si.subtotal), 0) AS base"
                + " FROM sales_invoices si"
                + " WHERE si.company_id = ? AND si.invoice_date BETWEEN ? AND ?"
                + " AND si.status = 'DRAFT'",
                (rs, n) -> new Drafts(rs.getInt("n"), nz(rs.getBigDecimal("base"))),
                companyId, f, t);
        if (dr == null) {
            dr = new Drafts(0, BigDecimal.ZERO);
        }

        Integer voided = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_invoices si"
                + " WHERE si.company_id = ? AND si.invoice_date BETWEEN ? AND ?"
                + " AND si.status = 'VOIDED'",
                Integer.class, companyId, f, t);

        // Cliente que mas factura en el rango. LIMIT 1 sobre el agrupado; si no
        // hay facturas, la lista viene vacia y se devuelve null (la UI oculta
        // la tarjeta en ese caso, en vez de pintar un "-" sin sentido).
        List<Object[]> top = jdbc.query(
                "SELECT c.legal_name AS name, COALESCE(SUM(si.subtotal), 0) AS base"
                + " FROM sales_invoices si"
                + " JOIN customers c ON c.id = si.customer_id"
                + " WHERE si.company_id = ? AND si.invoice_date BETWEEN ? AND ?"
                + SalesInvoiceSql.LIVE_ISSUED
                + " GROUP BY c.id, c.legal_name"
                + " ORDER BY base DESC"
                + " LIMIT 1",
                (rs, n) -> new Object[] { rs.getString("name"), nz(rs.getBigDecimal("base")) },
                companyId, f, t);

        BigDecimal avg = tot.n() > 0
                ? tot.tot().divide(BigDecimal.valueOf(tot.n()), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new BillingKpis(
                from, to,
                tot.n(), tot.base(), tot.vat(), tot.ret(), tot.tot(), avg,
                tot.paid(),
                pend.amt(), pend.n(), pend.overdueAmt(), pend.overdueN(),
                dr.n(), dr.base(),
                voided == null ? 0 : voided,
                tot.customers(),
                top.isEmpty() ? null : (String) top.get(0)[0],
                top.isEmpty() ? BigDecimal.ZERO : (BigDecimal) top.get(0)[1]);
    }

    /**
     * Serie mensual del ejercicio (12 puntos, los meses sin facturar a cero).
     * Mismo patron que {@code ClientFinancialsService.monthlySeries} para que
     * las dos tablas de evolucion se lean igual.
     */
    public record BillingMonthPoint(
            int month, int count, BigDecimal base, BigDecimal vat, BigDecimal total) {}

    public List<BillingMonthPoint> monthlySeries(int year) {
        String companyId = tenant.getCurrentCompanyId();
        int[] count = new int[13];
        BigDecimal[] base = new BigDecimal[13];
        BigDecimal[] vat = new BigDecimal[13];
        BigDecimal[] total = new BigDecimal[13];
        for (int m = 1; m <= 12; m++) {
            base[m] = BigDecimal.ZERO;
            vat[m] = BigDecimal.ZERO;
            total[m] = BigDecimal.ZERO;
        }
        jdbc.query(
                "SELECT MONTH(si.invoice_date) AS m, COUNT(*) AS n,"
                + " COALESCE(SUM(si.subtotal), 0) AS base,"
                + " COALESCE(SUM(si.vat_total), 0) AS vat,"
                + " COALESCE(SUM(si.total), 0) AS tot"
                + " FROM sales_invoices si"
                + " WHERE si.company_id = ? AND YEAR(si.invoice_date) = ?"
                + SalesInvoiceSql.LIVE_ISSUED
                + " GROUP BY MONTH(si.invoice_date)",
                rs -> {
                    int m = rs.getInt("m");
                    if (m >= 1 && m <= 12) {
                        count[m] = rs.getInt("n");
                        base[m] = nz(rs.getBigDecimal("base"));
                        vat[m] = nz(rs.getBigDecimal("vat"));
                        total[m] = nz(rs.getBigDecimal("tot"));
                    }
                },
                companyId, year);
        List<BillingMonthPoint> out = new ArrayList<>(12);
        for (int m = 1; m <= 12; m++) {
            out.add(new BillingMonthPoint(m, count[m], base[m], vat[m], total[m]));
        }
        return out;
    }

    // --- tuplas internas de las consultas agregadas ---
    private record Totals(int n, BigDecimal base, BigDecimal vat, BigDecimal ret,
                          BigDecimal tot, BigDecimal paid, int customers) {}

    private record Pending(int n, BigDecimal amt, BigDecimal overdueAmt, int overdueN) {}

    private record Drafts(int n, BigDecimal base) {}

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
