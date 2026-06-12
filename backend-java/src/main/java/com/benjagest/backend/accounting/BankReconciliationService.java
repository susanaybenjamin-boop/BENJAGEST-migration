package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REC-BANCARIA — sugerencias de match entre bank_movements no
 * reconciliados y facturas pendientes/parciales.
 *
 * <p>Criterios (decisión Benjamin 2026-06-12):
 * <ul>
 *   <li>{@code amount} dentro de ±1€ del importe pendiente.</li>
 *   <li>{@code operation_date} dentro de ±7 días respecto a
 *       {@code invoice_date} (o {@code due_date} si está más cerca).</li>
 *   <li>Distancia Levenshtein entre {@code description} (o
 *       {@code counterparty_name}) y {@code customer.legal_name} < 3.</li>
 * </ul>
 *
 * <p>El asesor confirma cada sugerencia desde la UI — el matching es
 * asistido, no automático.
 */
@Service
public class BankReconciliationService {

    private static final BigDecimal AMOUNT_TOL = new BigDecimal("1.00");
    private static final int DAYS_TOL = 7;
    private static final int LEV_TOL = 3;

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public BankReconciliationService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public List<MovementSuggestions> findSuggestions() {
        String companyId = tenant.getCurrentCompanyId();
        List<MovementSnap> movements = jdbc.query("""
                SELECT id, operation_date, description, counterparty_name,
                       counterparty_nif, amount
                  FROM bank_movements
                 WHERE company_id = ?
                   AND status = 'UNRECONCILED'
                   AND linked_invoice_id IS NULL
                   AND amount > 0
                 ORDER BY operation_date DESC
                 LIMIT 200
                """, (rs, n) -> new MovementSnap(
                        rs.getString("id"),
                        rs.getDate("operation_date").toLocalDate(),
                        rs.getString("description"),
                        rs.getString("counterparty_name"),
                        rs.getString("counterparty_nif"),
                        rs.getBigDecimal("amount")), companyId);

        if (movements.isEmpty()) return List.of();

        List<InvoiceSnap> invoices = jdbc.query("""
                SELECT si.id, si.invoice_number, si.invoice_date, si.due_date,
                       si.total, si.paid_amount,
                       c.legal_name, c.tax_identifier
                  FROM sales_invoices si
                  JOIN customers c ON c.id = si.customer_id
                 WHERE si.company_id = ?
                   AND si.status = 'VALIDATED'
                   AND si.payment_status IN ('PENDING', 'PARTIAL')
                """, (rs, n) -> new InvoiceSnap(
                        rs.getString("id"),
                        rs.getString("invoice_number"),
                        rs.getDate("invoice_date") == null ? null
                                : rs.getDate("invoice_date").toLocalDate(),
                        rs.getDate("due_date") == null ? null
                                : rs.getDate("due_date").toLocalDate(),
                        rs.getBigDecimal("total"),
                        rs.getBigDecimal("paid_amount"),
                        rs.getString("legal_name"),
                        rs.getString("tax_identifier")), companyId);

        List<MovementSuggestions> out = new ArrayList<>();
        for (MovementSnap m : movements) {
            List<Suggestion> matches = new ArrayList<>();
            for (InvoiceSnap inv : invoices) {
                BigDecimal pending = inv.total().subtract(
                        inv.paidAmount() == null ? BigDecimal.ZERO : inv.paidAmount());
                if (pending.signum() <= 0) continue;
                BigDecimal diff = m.amount().subtract(pending).abs();
                if (diff.compareTo(AMOUNT_TOL) > 0) continue;
                int daysDiff = Math.abs(diffDays(m.operationDate(), inv.invoiceDate()));
                int daysDiffDue = inv.dueDate() == null
                        ? Integer.MAX_VALUE
                        : Math.abs(diffDays(m.operationDate(), inv.dueDate()));
                int bestDays = Math.min(daysDiff, daysDiffDue);
                if (bestDays > DAYS_TOL) continue;
                String txt = (m.counterpartyName() != null && !m.counterpartyName().isBlank()
                        ? m.counterpartyName() : m.description());
                int lev = txt == null || inv.customerLegalName() == null
                        ? Integer.MAX_VALUE
                        : levenshtein(normalize(txt), normalize(inv.customerLegalName()));
                if (lev >= LEV_TOL) continue;
                double score = scoreOf(diff, bestDays, lev);
                matches.add(new Suggestion(inv.id(), inv.invoiceNumber(),
                        inv.customerLegalName(), pending, bestDays, lev, score));
            }
            if (!matches.isEmpty()) {
                matches.sort((a, b) -> Double.compare(b.score(), a.score()));
                out.add(new MovementSuggestions(m.id(), m.operationDate(),
                        m.description(), m.amount(), matches));
            }
        }
        return out;
    }

    private static double scoreOf(BigDecimal amountDiff, int dayDiff, int lev) {
        // Score normalizado 0..1: 1 = match exacto. Penaliza linealmente.
        double aScore = 1.0 - amountDiff.doubleValue() / AMOUNT_TOL.doubleValue();
        double dScore = 1.0 - (double) dayDiff / DAYS_TOL;
        double lScore = 1.0 - (double) lev / LEV_TOL;
        return (aScore + dScore + lScore) / 3.0;
    }

    private static int diffDays(LocalDate a, LocalDate b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        return (int) java.time.temporal.ChronoUnit.DAYS.between(a, b);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", "").trim().replaceAll("\\s+", " ");
    }

    /** Distancia Levenshtein clásica. */
    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    private record MovementSnap(String id, LocalDate operationDate,
                                  String description, String counterpartyName,
                                  String counterpartyNif, BigDecimal amount) {}

    private record InvoiceSnap(String id, String invoiceNumber, LocalDate invoiceDate,
                                 LocalDate dueDate, BigDecimal total,
                                 BigDecimal paidAmount, String customerLegalName,
                                 String customerNif) {}

    public record Suggestion(String invoiceId, String invoiceNumber,
                              String customerLegalName, BigDecimal pendingAmount,
                              int dayDiff, int levDistance, double score) {}

    public record MovementSuggestions(String movementId, LocalDate operationDate,
                                        String description, BigDecimal amount,
                                        List<Suggestion> suggestions) {}

    @RestController
    @RequestMapping("/api/accounting/bank-reconciliation")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class Controller {
        private final BankReconciliationService service;

        public Controller(BankReconciliationService service) {
            this.service = service;
        }

        @GetMapping("/suggestions")
        public List<MovementSuggestions> suggestions() {
            return service.findSuggestions();
        }
    }
}
