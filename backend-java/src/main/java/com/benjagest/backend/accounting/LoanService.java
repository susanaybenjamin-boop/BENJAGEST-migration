package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Préstamos: alta, cuadro de amortización, pago de cuota con asiento auto.
 *
 * <p>Métodos de amortización:
 * <ul>
 *   <li><b>FRENCH</b>: cuota constante. La parte de intereses baja y la
 *       de principal sube. Es el más usado en hipotecas y préstamos PYME.
 *       {@code C = P × (i / (1 - (1+i)^-n))}</li>
 *   <li><b>CONSTANT_PRINCIPAL</b>: amortización constante. La cuota baja
 *       cada mes.</li>
 *   <li><b>BULLET</b>: solo intereses cada periodo; principal al final.</li>
 * </ul>
 *
 * <p>Asiento de pago de cuota (sin diferencia largo/corto plazo todavía):
 * <pre>
 *   Debe   170 (Deuda L/P) o 520 (Deuda C/P)   por principal
 *   Debe   662 (Intereses)                       por interés
 *                Haber  572 (Banco)               por cuota total
 * </pre>
 */
@Service
public class LoanService {

    private static final int SCALE = 2;
    private static final RoundingMode RM = RoundingMode.HALF_UP;

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final BankAccountService bankAccounts;
    private final FiscalYearGuardService fiscalGuard;
    private final CurrentUserService currentUserService;

    public LoanService(JdbcTemplate jdbcTemplate, TenantContext tenantContext,
                       BankAccountService bankAccounts,
                       FiscalYearGuardService fiscalGuard,
                       CurrentUserService currentUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.bankAccounts = bankAccounts;
        this.fiscalGuard = fiscalGuard;
        this.currentUserService = currentUserService;
    }

    // ====================================================================
    //  CRUD préstamo
    // ====================================================================

    @Transactional
    public LoanView create(LoanRequest req) {
        validate(req);
        String companyId = tenantContext.getCurrentCompanyId();
        String id = UUID.randomUUID().toString();
        BigDecimal installmentAmount = computeInstallment(req);
        jdbcTemplate.update("""
                INSERT INTO loans (
                    id, company_id, code, description,
                    lender_name, lender_nif,
                    principal_amount, interest_rate, term_months,
                    start_date, first_installment_date, installment_amount,
                    method, long_term_account_id, short_term_account_id,
                    interest_account_id, bank_account_id,
                    status, notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                """,
                id, companyId, req.code().trim(), req.description().trim(),
                blank(req.lenderName()), normalizeNif(req.lenderNif()),
                req.principalAmount(), req.interestRate(), req.termMonths(),
                Date.valueOf(req.startDate()), Date.valueOf(req.firstInstallmentDate()),
                installmentAmount, req.method() == null ? "FRENCH" : req.method(),
                blank(req.longTermAccountId()), blank(req.shortTermAccountId()),
                blank(req.interestAccountId()), blank(req.bankAccountId()),
                blank(req.notes()));
        generateAmortizationTable(id, req, installmentAmount);
        return get(id);
    }

    public LoanView get(String id) {
        List<LoanView> rows = jdbcTemplate.query("""
                SELECT * FROM loans WHERE id = ? AND company_id = ?
                """, this::mapLoan, id, tenantContext.getCurrentCompanyId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Préstamo no encontrado");
        return rows.get(0);
    }

    public List<LoanView> list(String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM loans WHERE company_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (status != null && !status.isBlank()) { sql.append(" AND status = ?"); args.add(status); }
        sql.append(" ORDER BY status, start_date DESC");
        return jdbcTemplate.query(sql.toString(), this::mapLoan, args.toArray());
    }

    public List<InstallmentView> listInstallments(String loanId) {
        return jdbcTemplate.query("""
                SELECT id, loan_id, installment_number, due_date,
                       principal_amount, interest_amount, total_amount,
                       remaining_principal, status, paid_at,
                       journal_entry_id, bank_movement_id
                  FROM loan_installments
                 WHERE loan_id = ? AND company_id = ?
                 ORDER BY installment_number
                """, this::mapInstallment, loanId, tenantContext.getCurrentCompanyId());
    }

    // ====================================================================
    //  Pago de cuota — genera asiento contable
    // ====================================================================

    @Transactional
    public InstallmentView payInstallment(String installmentId, PaymentRequest req) {
        String companyId = tenantContext.getCurrentCompanyId();
        InstallmentView inst = getInstallment(installmentId);
        if ("PAID".equals(inst.status())) return inst;
        if ("CANCELLED".equals(inst.status())) {
            throw bad("La cuota está cancelada.");
        }
        LocalDate paymentDate = req.paymentDate() == null ? inst.dueDate() : req.paymentDate();
        fiscalGuard.requireOpenForDate(paymentDate, "pagar cuota préstamo");

        LoanView loan = get(inst.loanId());

        String acc170 = pickAccount(loan.longTermAccountId(), "170");
        String acc662 = pickAccount(loan.interestAccountId(), "662");
        String acc572 = loan.bankAccountId() != null
                ? bankAccounts.resolveAccountingAccountId(loan.bankAccountId())
                : findAccountByPrefix(companyId, "572");
        if (acc170 == null || acc662 == null || acc572 == null) {
            throw bad("Faltan cuentas contables 170/662/572 — configura el plan PGC.");
        }

        String fiscalYearId = findFiscalYearId(companyId, paymentDate);
        if (fiscalYearId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No hay ejercicio fiscal abierto para " + paymentDate);
        }

        Integer max = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0) FROM journal_entries
                 WHERE company_id = ? AND fiscal_year_id = ?
                """, Integer.class, companyId, fiscalYearId);
        int entryNumber = (max == null ? 0 : max) + 1;
        String entryId = UUID.randomUUID().toString();
        String userId = safeUserId();

        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'LOAN_INSTALLMENT', ?, 'POSTED', FALSE, FALSE, ?)
                """,
                entryId, companyId, fiscalYearId, entryNumber,
                Date.valueOf(paymentDate),
                truncate("Cuota " + inst.installmentNumber() + " préstamo " + loan.code(), 240),
                installmentId, userId);

        insertLine(entryId, acc170, "Amortización principal", inst.principalAmount(), BigDecimal.ZERO);
        insertLine(entryId, acc662, "Intereses cuota", inst.interestAmount(), BigDecimal.ZERO);
        insertLine(entryId, acc572, "Pago cuota préstamo " + loan.code(),
                BigDecimal.ZERO, inst.totalAmount());

        jdbcTemplate.update("""
                UPDATE loan_installments
                   SET status = 'PAID', paid_at = CURRENT_TIMESTAMP,
                       journal_entry_id = ?
                 WHERE id = ?
                """, entryId, installmentId);

        // Si todas las cuotas pagadas, marcar préstamo PAID_OFF.
        Integer pending = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM loan_installments
                 WHERE loan_id = ? AND status IN ('PENDING','OVERDUE')
                """, Integer.class, loan.id());
        if (pending != null && pending == 0) {
            jdbcTemplate.update("""
                    UPDATE loans SET status = 'PAID_OFF' WHERE id = ?
                    """, loan.id());
        }
        return getInstallment(installmentId);
    }

    private InstallmentView getInstallment(String id) {
        List<InstallmentView> rows = jdbcTemplate.query("""
                SELECT id, loan_id, installment_number, due_date,
                       principal_amount, interest_amount, total_amount,
                       remaining_principal, status, paid_at,
                       journal_entry_id, bank_movement_id
                  FROM loan_installments
                 WHERE id = ? AND company_id = ?
                """, this::mapInstallment, id, tenantContext.getCurrentCompanyId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cuota no encontrada");
        return rows.get(0);
    }

    // ====================================================================
    //  Cuadro de amortización
    // ====================================================================

    private BigDecimal computeInstallment(LoanRequest req) {
        BigDecimal P = req.principalAmount();
        BigDecimal rate = req.interestRate();
        int n = req.termMonths();
        BigDecimal i = rate.divide(new BigDecimal("12"), 10, RM)
                .divide(new BigDecimal("100"), 10, RM);

        if ("BULLET".equals(req.method())) {
            return P.multiply(i).setScale(SCALE, RM);
        }
        if ("CONSTANT_PRINCIPAL".equals(req.method())) {
            BigDecimal principalPerMonth = P.divide(new BigDecimal(n), SCALE, RM);
            return principalPerMonth.add(P.multiply(i)).setScale(SCALE, RM);
        }
        // FRENCH (cuota constante)
        if (i.signum() == 0) return P.divide(new BigDecimal(n), SCALE, RM);
        BigDecimal onePlusI = BigDecimal.ONE.add(i);
        BigDecimal factor = onePlusI.pow(n);
        BigDecimal numerator = P.multiply(i).multiply(factor);
        BigDecimal denominator = factor.subtract(BigDecimal.ONE);
        return numerator.divide(denominator, SCALE, RM);
    }

    private void generateAmortizationTable(String loanId, LoanRequest req, BigDecimal cuota) {
        BigDecimal P = req.principalAmount();
        BigDecimal i = req.interestRate().divide(new BigDecimal("12"), 10, RM)
                .divide(new BigDecimal("100"), 10, RM);
        int n = req.termMonths();
        BigDecimal remaining = P;
        LocalDate due = req.firstInstallmentDate();
        String companyId = tenantContext.getCurrentCompanyId();

        for (int k = 1; k <= n; k++) {
            BigDecimal interest = remaining.multiply(i).setScale(SCALE, RM);
            BigDecimal principal;
            BigDecimal total;
            if ("BULLET".equals(req.method())) {
                principal = k == n ? remaining : BigDecimal.ZERO;
                total = interest.add(principal);
            } else if ("CONSTANT_PRINCIPAL".equals(req.method())) {
                principal = P.divide(new BigDecimal(n), SCALE, RM);
                if (k == n) principal = remaining; // ajuste último
                total = principal.add(interest);
            } else {
                principal = cuota.subtract(interest);
                if (k == n) principal = remaining; // ajuste último
                total = principal.add(interest);
            }
            remaining = remaining.subtract(principal);
            if (remaining.signum() < 0) remaining = BigDecimal.ZERO;

            jdbcTemplate.update("""
                    INSERT INTO loan_installments (
                        id, company_id, loan_id, installment_number, due_date,
                        principal_amount, interest_amount, total_amount,
                        remaining_principal, status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                    """,
                    UUID.randomUUID().toString(), companyId, loanId, k,
                    Date.valueOf(due),
                    principal, interest, total, remaining);
            due = due.plusMonths(1);
        }
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private String pickAccount(String configured, String fallbackPrefix) {
        if (configured != null && !configured.isBlank()) return configured;
        return findAccountByPrefix(tenantContext.getCurrentCompanyId(), fallbackPrefix);
    }

    private String findAccountByPrefix(String companyId, String prefix) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE AND code LIKE ?
                 ORDER BY LENGTH(code), code LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, prefix + "%");
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String findFiscalYearId(String companyId, LocalDate date) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM fiscal_years
                 WHERE company_id = ? AND start_date <= ? AND end_date >= ? LIMIT 1
                """, (rs, n) -> rs.getString("id"),
                companyId, Date.valueOf(date), Date.valueOf(date));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void insertLine(String entryId, String accountId, String desc,
                              BigDecimal debit, BigDecimal credit) {
        jdbcTemplate.update("""
                INSERT INTO journal_entry_lines (
                    id, journal_entry_id, account_id, description, debit, credit
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entryId, accountId,
                truncate(desc, 240), debit, credit);
    }

    private void validate(LoanRequest req) {
        if (req.code() == null || req.code().isBlank()) throw bad("Código obligatorio.");
        if (req.description() == null || req.description().isBlank()) throw bad("Descripción obligatoria.");
        if (req.principalAmount() == null || req.principalAmount().signum() <= 0) throw bad("Capital > 0 obligatorio.");
        if (req.interestRate() == null || req.interestRate().signum() < 0) throw bad("Tipo de interés inválido.");
        if (req.termMonths() <= 0) throw bad("Plazo en meses > 0.");
        if (req.startDate() == null || req.firstInstallmentDate() == null) throw bad("Fechas obligatorias.");
    }

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    private LoanView mapLoan(ResultSet rs, int n) throws SQLException {
        java.sql.Timestamp ca = rs.getTimestamp("created_at");
        java.sql.Timestamp ua = rs.getTimestamp("updated_at");
        return new LoanView(
                rs.getString("id"), rs.getString("company_id"),
                rs.getString("code"), rs.getString("description"),
                rs.getString("lender_name"), rs.getString("lender_nif"),
                rs.getBigDecimal("principal_amount"),
                rs.getBigDecimal("interest_rate"),
                rs.getInt("term_months"),
                rs.getDate("start_date").toLocalDate(),
                rs.getDate("first_installment_date").toLocalDate(),
                rs.getBigDecimal("installment_amount"),
                rs.getString("method"),
                rs.getString("long_term_account_id"),
                rs.getString("short_term_account_id"),
                rs.getString("interest_account_id"),
                rs.getString("bank_account_id"),
                rs.getString("status"), rs.getString("notes"),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant());
    }

    private InstallmentView mapInstallment(ResultSet rs, int n) throws SQLException {
        java.sql.Timestamp pa = rs.getTimestamp("paid_at");
        return new InstallmentView(
                rs.getString("id"), rs.getString("loan_id"),
                rs.getInt("installment_number"),
                rs.getDate("due_date").toLocalDate(),
                rs.getBigDecimal("principal_amount"),
                rs.getBigDecimal("interest_amount"),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("remaining_principal"),
                rs.getString("status"),
                pa == null ? null : pa.toInstant(),
                rs.getString("journal_entry_id"),
                rs.getString("bank_movement_id"));
    }

    private static String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static String normalizeNif(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase();
    }
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // ====================================================================
    //  DTOs
    // ====================================================================

    public record LoanRequest(
            String code, String description,
            String lenderName, String lenderNif,
            BigDecimal principalAmount, BigDecimal interestRate, int termMonths,
            LocalDate startDate, LocalDate firstInstallmentDate,
            String method,
            String longTermAccountId, String shortTermAccountId,
            String interestAccountId, String bankAccountId,
            String notes
    ) {}

    public record PaymentRequest(LocalDate paymentDate) {}

    public record LoanView(
            String id, String companyId, String code, String description,
            String lenderName, String lenderNif,
            BigDecimal principalAmount, BigDecimal interestRate, int termMonths,
            LocalDate startDate, LocalDate firstInstallmentDate,
            BigDecimal installmentAmount, String method,
            String longTermAccountId, String shortTermAccountId,
            String interestAccountId, String bankAccountId,
            String status, String notes,
            Instant createdAt, Instant updatedAt
    ) {}

    public record InstallmentView(
            String id, String loanId, int installmentNumber, LocalDate dueDate,
            BigDecimal principalAmount, BigDecimal interestAmount,
            BigDecimal totalAmount, BigDecimal remainingPrincipal,
            String status, Instant paidAt,
            String journalEntryId, String bankMovementId
    ) {}
}
