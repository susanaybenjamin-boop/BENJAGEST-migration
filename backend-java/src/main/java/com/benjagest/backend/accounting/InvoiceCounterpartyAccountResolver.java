package com.benjagest.backend.accounting;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * CONTA-1 (2026-09-05) — Resuelve la cuenta de TERCERO contra la que hay que
 * saldar el cobro o el pago de una factura: la MISMA subcuenta que usó la
 * factura (p. ej. {@code 4300005 "Clientes - 3R Consultores"}), no la genérica.
 *
 * <p><b>El bug que arregla</b>, encontrado por Benjamin mirando producción:
 * <i>"teniendo un cliente con todas las facturas cobradas, me sale en sumas y
 * saldos un saldo deudor de la última factura, y así todos los clientes"</i>.
 *
 * <p>La factura carga la subcuenta del tercero (vía
 * {@link TerceroAccountResolverService}), pero los dos caminos que la saldan
 * buscaban la cuenta <b>solo por prefijo</b>, y eso devuelve el código más
 * corto que empieza por 430/400: la <b>genérica</b>. Debe en una cuenta y haber
 * en otra, así que la subcuenta del tercero se quedaba con saldo deudor y la
 * genérica acumulaba el acreedor por el mismo importe. En los datos reales:
 * {@code 4300005} con +1.724,25 y la {@code 430} con −1.724,25.
 *
 * <p>Los dos caminos afectados (el mismo fallo copiado en dos sitios, por eso
 * esto vive aquí y no dentro de uno de ellos):
 * <ul>
 *   <li>{@link PaymentScheduleService} — cobro/pago de un vencimiento.</li>
 *   <li>{@link BankMovementService} — conciliación bancaria.</li>
 * </ul>
 *
 * <p>La cuenta se saca del ASIENTO DE LA PROPIA FACTURA, no volviendo a
 * preguntar al resolver de terceros: así, si el asesor reclasificó la factura a
 * otra cuenta, el cobro la sigue en vez de volver a descuadrarla.
 */
@Service
public class InvoiceCounterpartyAccountResolver {

    private final JdbcTemplate jdbc;

    public InvoiceCounterpartyAccountResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param invoiceKind {@code "SALES"} o {@code "PURCHASE"}.
     * @return id de la cuenta de tercero que usó la factura; si la factura no
     *         tiene asiento (caso raro), la genérica por prefijo, para no
     *         bloquear el cobro. {@code null} solo si tampoco existe la
     *         genérica.
     */
    public String resolve(String companyId, String invoiceKind, String invoiceId) {
        String fromInvoice = accountUsedByInvoice(companyId, invoiceKind, invoiceId);
        if (fromInvoice != null) {
            return fromInvoice;
        }
        return findAccountByPrefix(companyId, genericPrefix(invoiceKind));
    }

    /** Prefijo de la cuenta genérica: 430 clientes / 400 proveedores. */
    public static String genericPrefix(String invoiceKind) {
        return "SALES".equals(invoiceKind) ? "430" : "400";
    }

    /**
     * Cuenta de tercero que movió el asiento de la factura. En la factura de
     * VENTA el tercero va al DEBE (43x); en la de COMPRA, al HABER (40x/41x).
     * Se ignoran los asientos anulados.
     */
    private String accountUsedByInvoice(String companyId, String invoiceKind, String invoiceId) {
        if (invoiceId == null || invoiceId.isBlank()) {
            return null;
        }
        boolean sales = "SALES".equals(invoiceKind);
        String sourceType = sales ? "SALES_INVOICE" : "PURCHASE_INVOICE";
        String amountColumn = sales ? "l.debit" : "l.credit";
        String codeFilter = sales
                ? "ac.code LIKE '43%'"
                : "(ac.code LIKE '40%' OR ac.code LIKE '41%')";

        String sql = "SELECT l.account_id"
                + "  FROM journal_entry_lines l"
                + "  JOIN journal_entries e ON e.id = l.journal_entry_id"
                + "  JOIN accounting_accounts ac ON ac.id = l.account_id"
                + " WHERE e.company_id = ? AND e.source_type = ? AND e.source_id = ?"
                + "   AND e.status <> 'VOIDED'"
                + "   AND " + codeFilter
                + "   AND " + amountColumn + " > 0"
                + " ORDER BY " + amountColumn + " DESC"
                + " LIMIT 1";
        List<String> ids = jdbc.query(sql, (rs, n) -> rs.getString("account_id"),
                companyId, sourceType, invoiceId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String findAccountByPrefix(String companyId, String prefix) {
        List<String> ids = jdbc.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE AND code LIKE ?
                 ORDER BY LENGTH(code), code LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, prefix + "%");
        return ids.isEmpty() ? null : ids.get(0);
    }
}
