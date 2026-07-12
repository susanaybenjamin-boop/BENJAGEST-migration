package com.benjagest.backend.accounting.compensation;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BLOQUE COMP- — Compensación (netting) de facturas: saldar una VENTA (me
 * deben, cuenta 430) contra una COMPRA (yo debo, cuenta 400) del <b>mismo
 * tercero</b> (por NIF), extinguiendo ambas deudas hasta la cantidad
 * concurrente (Código Civil arts. 1195-1202). Detalle legal y contable en
 * {@code docs/compensacion-legal.md}.
 *
 * <p><b>COMP-1 — detección (solo lectura).</b> Este servicio NO escribe nada:
 * detecta terceros que a la vez tienen ventas pendientes/parciales (430) y
 * compras pendientes (400) casando por NIF normalizado, y propone la
 * compensación. El asesor confirma; la ejecución (asiento 400/430) vive en
 * COMP-2.
 *
 * <p>OJO nomenclatura: "compensación" en este backend ya se usa para el IVA
 * ({@code vat_compensation_baseline}, casilla 110 del 303). Esto es otra cosa:
 * netting de facturas. El {@code source_type} del asiento será
 * {@code 'COMPENSATION'} (namespace del diario, sin colisión).
 *
 * <p>Pendiente de VENTA = {@code total - paid_amount} (la proyección oficial;
 * fuente de verdad = vencimientos, ya reflejada en {@code paid_amount}).
 * Pendiente de COMPRA = {@code total_amount - Σ vencimientos PAID}; las compras
 * marcadas {@code paid=TRUE} (flag V167) se consideran saldadas y se excluyen.
 */
@Service
public class InvoiceCompensationService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public InvoiceCompensationService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    /**
     * Propone compensaciones para la empresa activa: un grupo por cada NIF que
     * tenga a la vez ventas pendientes y compras pendientes. Solo lectura.
     */
    public List<CompensationProposal> findProposals() {
        String companyId = tenant.getCurrentCompanyId();
        LocalDate today = LocalDate.now();

        // 1) Ventas pendientes/parciales (430 = me deben).
        List<RawLine> sales = jdbc.query("""
                SELECT si.id, si.invoice_number, si.invoice_date, si.due_date,
                       si.total, COALESCE(si.paid_amount, 0) AS paid_amount,
                       c.legal_name, c.tax_identifier
                  FROM sales_invoices si
                  JOIN customers c ON c.id = si.customer_id
                 WHERE si.company_id = ?
                   AND si.status = 'VALIDATED'
                   AND si.payment_status IN ('PENDING', 'PARTIAL')
                   AND c.tax_identifier IS NOT NULL AND c.tax_identifier <> ''
                """, (rs, n) -> {
                    BigDecimal total = rs.getBigDecimal("total");
                    BigDecimal paid = rs.getBigDecimal("paid_amount");
                    BigDecimal pending = total.subtract(paid);
                    LocalDate invDate = rs.getDate("invoice_date") == null ? null
                            : rs.getDate("invoice_date").toLocalDate();
                    LocalDate dueDate = rs.getDate("due_date") == null ? null
                            : rs.getDate("due_date").toLocalDate();
                    return new RawLine(rs.getString("tax_identifier"),
                            rs.getString("legal_name"),
                            new Line(rs.getString("id"), rs.getString("invoice_number"),
                                    invDate, dueDate, total, pending,
                                    isDue(dueDate, invDate, today)));
                }, companyId);

        // 2) Compras pendientes (400 = yo debo). Excluye las pagadas por flag
        //    (paid=TRUE); resta los vencimientos PAID de las que usan ese modelo.
        List<RawLine> purchases = jdbc.query("""
                SELECT pi.id, pi.invoice_number, pi.invoice_date,
                       pi.total_amount, pi.supplier_nif, pi.supplier_name,
                       COALESCE((
                           SELECT SUM(dd.amount) FROM invoice_due_dates dd
                            WHERE dd.company_id = pi.company_id
                              AND dd.invoice_kind = 'PURCHASE'
                              AND dd.invoice_id = pi.id
                              AND dd.status = 'PAID'
                       ), 0) AS paid_dd
                  FROM purchase_invoices pi
                 WHERE pi.company_id = ?
                   AND pi.status = 'POSTED'
                   AND pi.paid = FALSE
                   AND pi.total_amount IS NOT NULL
                   AND pi.supplier_nif IS NOT NULL AND pi.supplier_nif <> ''
                """, (rs, n) -> {
                    BigDecimal total = rs.getBigDecimal("total_amount");
                    BigDecimal paidDd = rs.getBigDecimal("paid_dd");
                    BigDecimal pending = total.subtract(paidDd);
                    LocalDate invDate = rs.getDate("invoice_date") == null ? null
                            : rs.getDate("invoice_date").toLocalDate();
                    return new RawLine(rs.getString("supplier_nif"),
                            rs.getString("supplier_name"),
                            new Line(rs.getString("id"), rs.getString("invoice_number"),
                                    invDate, null, total, pending,
                                    // sin due_date en compras: exigible desde su fecha
                                    isDue(null, invDate, today)));
                }, companyId);

        // 3+4) Cruce puro (agrupar por NIF, quedarnos con los terceros que
        //      tienen ambos lados, calcular el compensable). Testeable aparte.
        return crossMatch(sales, purchases);
    }

    /**
     * Cruce PURO venta↔compra por NIF normalizado (patrón compute130: sin BD,
     * testeable). Devuelve una propuesta por cada tercero con ventas Y compras
     * pendientes; el compensable es el menor de los dos totales pendientes.
     * Ordena por compensable descendente.
     */
    static List<CompensationProposal> crossMatch(List<RawLine> sales, List<RawLine> purchases) {
        Map<String, Bucket> byNif = new LinkedHashMap<>();
        for (RawLine r : sales) {
            if (r.line().pending().signum() <= 0) continue;
            String key = normNif(r.nif());
            if (key.isEmpty()) continue;
            byNif.computeIfAbsent(key, k -> new Bucket(r.nif(), r.name()))
                    .sales.add(r.line());
        }
        for (RawLine r : purchases) {
            if (r.line().pending().signum() <= 0) continue;
            String key = normNif(r.nif());
            if (key.isEmpty()) continue;
            Bucket b = byNif.get(key);
            if (b == null) continue; // sin venta pendiente de ese NIF → no compensa
            if (b.name == null || b.name.isBlank()) b.name = r.name();
            b.purchases.add(r.line());
        }

        List<CompensationProposal> out = new ArrayList<>();
        for (Bucket b : byNif.values()) {
            if (b.sales.isEmpty() || b.purchases.isEmpty()) continue;
            BigDecimal salesPending = sum(b.sales);
            BigDecimal purchasePending = sum(b.purchases);
            BigDecimal compensable = salesPending.min(purchasePending);
            if (compensable.signum() <= 0) continue;
            out.add(new CompensationProposal(b.nif, b.name, salesPending,
                    purchasePending, compensable, b.sales, b.purchases));
        }
        out.sort((a, c) -> c.compensable().compareTo(a.compensable()));
        return out;
    }

    private static boolean isDue(LocalDate dueDate, LocalDate invoiceDate, LocalDate today) {
        LocalDate ref = dueDate != null ? dueDate : invoiceDate;
        return ref == null || !ref.isAfter(today);
    }

    private static BigDecimal sum(List<Line> lines) {
        BigDecimal s = BigDecimal.ZERO;
        for (Line l : lines) s = s.add(l.pending());
        return s;
    }

    /** Normaliza el NIF para casar ventas y compras: mayúsculas, sin puntos/guiones/espacios. */
    private static String normNif(String s) {
        if (s == null) return "";
        return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    // ---- DTOs ----

    /** Una factura pendiente (venta o compra) con su importe pendiente. */
    public record Line(String invoiceId, String invoiceNumber, LocalDate invoiceDate,
                       LocalDate dueDate, BigDecimal total, BigDecimal pending,
                       boolean due) {}

    /** Propuesta de compensación para un tercero (mismo NIF). */
    public record CompensationProposal(String nif, String counterpartyName,
                                       BigDecimal salesPending, BigDecimal purchasePending,
                                       BigDecimal compensable,
                                       List<Line> sales, List<Line> purchases) {}

    /** Línea cruda con su NIF/nombre de tercero antes de agrupar (paquete-visible para test). */
    record RawLine(String nif, String name, Line line) {}

    private static final class Bucket {
        final String nif;
        String name;
        final List<Line> sales = new ArrayList<>();
        final List<Line> purchases = new ArrayList<>();
        Bucket(String nif, String name) { this.nif = nif; this.name = name; }
    }

    @RestController
    @RequestMapping("/api/accounting/compensation")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class Controller {
        private final InvoiceCompensationService service;

        public Controller(InvoiceCompensationService service) {
            this.service = service;
        }

        /** COMP-1 — propuestas de compensación de la empresa activa. */
        @GetMapping("/suggestions")
        public List<CompensationProposal> suggestions() {
            return service.findProposals();
        }
    }
}
