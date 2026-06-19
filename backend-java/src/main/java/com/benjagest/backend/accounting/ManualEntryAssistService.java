package com.benjagest.backend.accounting;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Asiento manual intuitivo (ME-2 + ME-3) — ayudas para el editor de asiento:
 *
 * <ul>
 *   <li><b>ME-2</b> {@link #openInvoicesForAccount}: al elegir una cuenta de
 *       cliente (43x) o proveedor (40x/41x), devuelve sus facturas pendientes
 *       (para un cobro/pago manual).</li>
 *   <li><b>ME-3</b> {@link #suggest}: dada una cuenta "ancla" (la recién puesta,
 *       típicamente el tercero), sugiere las cuentas que SUELEN acompañarla,
 *       aprendidas del HISTÓRICO de asientos POSTED (co-ocurrencia) + reglas de
 *       partida doble de respaldo (IVA). El usuario elige; no se impone nada.</li>
 * </ul>
 */
@Service
public class ManualEntryAssistService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public ManualEntryAssistService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public record OpenInvoice(String kind, String number, LocalDate date,
                              BigDecimal total, BigDecimal pending) {}

    public record SuggestedAccount(String code, String name, int score, String reason) {}

    // ====================================================================
    //  ME-2 — Facturas pendientes del tercero de la cuenta
    // ====================================================================

    public List<OpenInvoice> openInvoicesForAccount(String accountCode) {
        String companyId = tenant.getCurrentCompanyId();
        if (accountCode == null || accountCode.isBlank()) return List.of();
        String code = accountCode.trim();

        List<String> nifRow = jdbc.query("""
                SELECT tercero_nif FROM accounting_accounts
                 WHERE company_id = ? AND code = ? LIMIT 1
                """, (rs, n) -> rs.getString("tercero_nif"), companyId, code);
        String nif = nifRow.isEmpty() ? null : nifRow.get(0);

        boolean customer = code.startsWith("43") || code.startsWith("44");
        boolean supplier = code.startsWith("40") || code.startsWith("41");
        List<OpenInvoice> out = new ArrayList<>();

        if (customer && nif != null && !nif.isBlank()) {
            out.addAll(jdbc.query("""
                    SELECT si.invoice_number AS num, si.invoice_date AS d,
                           si.total AS t, COALESCE(si.paid_amount, 0) AS p
                      FROM sales_invoices si
                      JOIN customers c ON c.id = si.customer_id
                     WHERE si.company_id = ? AND c.tax_identifier = ?
                       AND si.status = 'VALIDATED'
                       AND si.payment_status IN ('PENDING', 'PARTIAL')
                     ORDER BY si.invoice_date
                    """, (rs, n) -> {
                java.sql.Date d = rs.getDate("d");
                BigDecimal t = nz(rs.getBigDecimal("t"));
                return new OpenInvoice("SALES", rs.getString("num"),
                        d == null ? null : d.toLocalDate(), t, t.subtract(nz(rs.getBigDecimal("p"))));
            }, companyId, nif));
        }

        if (supplier && nif != null && !nif.isBlank()) {
            List<OpenInvoice> purch = jdbc.query("""
                    SELECT pi.id AS id, pi.invoice_number AS num, pi.invoice_date AS d,
                           pi.total_amount AS t,
                           (SELECT COALESCE(SUM(dd.amount), 0) FROM invoice_due_dates dd
                             WHERE dd.company_id = pi.company_id AND dd.invoice_kind = 'PURCHASE'
                               AND dd.invoice_id = pi.id AND dd.status = 'PAID') AS paid
                      FROM purchase_invoices pi
                     WHERE pi.company_id = ? AND pi.supplier_nif = ?
                     ORDER BY pi.invoice_date
                    """, (rs, n) -> {
                java.sql.Date d = rs.getDate("d");
                BigDecimal t = nz(rs.getBigDecimal("t"));
                BigDecimal pending = t.subtract(nz(rs.getBigDecimal("paid")));
                return new OpenInvoice("PURCHASE", rs.getString("num"),
                        d == null ? null : d.toLocalDate(), t, pending);
            }, companyId, nif);
            for (OpenInvoice oi : purch) {
                if (oi.pending() != null && oi.pending().compareTo(new BigDecimal("0.01")) > 0) out.add(oi);
            }
        }
        return out;
    }

    // ====================================================================
    //  ME-3 — Cuentas sugeridas (histórico de co-ocurrencia + reglas)
    // ====================================================================

    public List<SuggestedAccount> suggest(String anchorCode, List<String> exclude) {
        String companyId = tenant.getCurrentCompanyId();
        if (anchorCode == null || anchorCode.isBlank()) return List.of();
        String anchor = anchorCode.trim();
        Set<String> ex = new LinkedHashSet<>();
        ex.add(anchor);
        if (exclude != null) for (String c : exclude) if (c != null && !c.isBlank()) ex.add(c.trim());

        List<SuggestedAccount> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // 1) Histórico: cuentas que co-ocurren con la ancla en asientos POSTED.
        List<SuggestedAccount> hist = jdbc.query("""
                SELECT a2.code AS code, MAX(a2.name) AS name, COUNT(*) AS n
                  FROM journal_entry_lines l1
                  JOIN journal_entries e ON e.id = l1.journal_entry_id
                       AND e.company_id = ? AND e.status = 'POSTED'
                  JOIN journal_entry_lines l2 ON l2.journal_entry_id = e.id AND l2.id <> l1.id
                  JOIN accounting_accounts a1 ON a1.id = l1.account_id
                  JOIN accounting_accounts a2 ON a2.id = l2.account_id
                 WHERE a1.company_id = ? AND a1.code = ?
                 GROUP BY a2.code
                 ORDER BY n DESC, a2.code
                 LIMIT 12
                """, (rs, n) -> new SuggestedAccount(
                        rs.getString("code"), rs.getString("name"), rs.getInt("n"), "history"),
                companyId, companyId, anchor);
        for (SuggestedAccount s : hist) {
            if (ex.contains(s.code()) || seen.contains(s.code())) continue;
            out.add(s);
            seen.add(s.code());
        }

        // 2) Reglas de partida doble de respaldo (IVA), si no salieron del histórico.
        String ivaCode = anchor.startsWith("7") ? "477" : anchor.startsWith("6") ? "472" : null;
        if (ivaCode != null && !ex.contains(ivaCode) && !seen.contains(ivaCode)) {
            String name = accountName(companyId, ivaCode);
            if (name != null) { out.add(new SuggestedAccount(ivaCode, name, 0, "rule")); seen.add(ivaCode); }
        }

        if (out.size() > 8) return out.subList(0, 8);
        return out;
    }

    private String accountName(String companyId, String codePrefix) {
        List<String> r = jdbc.query("""
                SELECT name FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE AND code LIKE ?
                 ORDER BY LENGTH(code), code LIMIT 1
                """, (rs, n) -> rs.getString("name"), companyId, codePrefix + "%");
        return r.isEmpty() ? null : r.get(0);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
