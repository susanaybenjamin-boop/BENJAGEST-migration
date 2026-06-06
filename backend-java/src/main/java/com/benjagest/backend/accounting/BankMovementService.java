package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
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
 * Movimientos bancarios. Cada movimiento puede:
 *
 * <ul>
 *   <li><b>Quedar UNRECONCILED</b>: dato del extracto sin enlazar a nada.</li>
 *   <li><b>MATCHED</b>: enlazado a factura emitida o recibida pendiente.
 *       Auto-genera asiento: Debe 572/Haber 430 (cobro) o Debe 400/Haber 572 (pago).</li>
 *   <li><b>POSTED</b>: con asiento posteado.</li>
 *   <li><b>IGNORED</b>: el asesor decide no contabilizar (comisión &lt; X, etc.).</li>
 * </ul>
 *
 * <p>Convención de signo: {@code amount > 0} = ingreso en la cuenta
 * bancaria; {@code amount < 0} = cargo. El asiento se monta según el
 * signo y el tipo de factura enlazada.
 */
@Service
public class BankMovementService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final BankAccountService bankAccounts;
    private final FiscalYearGuardService fiscalGuard;
    private final CurrentUserService currentUserService;

    public BankMovementService(JdbcTemplate jdbcTemplate, TenantContext tenantContext,
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
    //  Crear movimientos
    // ====================================================================

    @Transactional
    public BankMovementView createManual(MovementRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        String companyId = tenantContext.getCurrentCompanyId();
        jdbcTemplate.update("""
                INSERT INTO bank_movements (
                    id, company_id, bank_account_id,
                    operation_date, value_date,
                    description, counterparty_name, counterparty_nif,
                    amount, balance_after, external_ref,
                    import_batch_id, status, notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'UNRECONCILED', ?)
                """,
                id, companyId, req.bankAccountId(),
                Date.valueOf(req.operationDate()),
                req.valueDate() == null ? null : Date.valueOf(req.valueDate()),
                truncate(req.description(), 240),
                blank(req.counterpartyName()),
                normalizeNif(req.counterpartyNif()),
                req.amount(), req.balanceAfter(), blank(req.externalRef()),
                blank(req.notes()));
        return get(id);
    }

    @Transactional
    public BankMovementView linkToInvoice(String movementId, LinkRequest req) {
        BankMovementView m = get(movementId);
        if (req.invoiceKind() == null || req.invoiceId() == null) {
            throw bad("Hay que indicar invoiceKind y invoiceId.");
        }
        if (!"SALES".equals(req.invoiceKind()) && !"PURCHASE".equals(req.invoiceKind())) {
            throw bad("invoiceKind debe ser SALES o PURCHASE.");
        }
        fiscalGuard.requireOpenForDate(m.operationDate(), "contabilizar este cobro/pago");

        String companyId = tenantContext.getCurrentCompanyId();
        String userId = safeUserId();

        // Resolver la cuenta contable bancaria (572).
        String acc572 = bankAccounts.resolveAccountingAccountId(m.bankAccountId());
        if (acc572 == null) {
            throw bad("La cuenta bancaria no tiene cuenta contable 572 asignada y no existe una 572 genérica.");
        }

        // Cuenta contrapartida: 430 (clientes) si SALES, 400 (proveedores) si PURCHASE.
        String contrapartida = req.counterpartyAccountId();
        if (contrapartida == null || contrapartida.isBlank()) {
            String prefix = "SALES".equals(req.invoiceKind()) ? "430" : "400";
            contrapartida = findAccountByPrefix(companyId, prefix);
            if (contrapartida == null) {
                throw bad("No se encontró cuenta " + prefix + " para contrapartida.");
            }
        }

        // Generar asiento contable.
        String fiscalYearId = findFiscalYearId(companyId, m.operationDate());
        if (fiscalYearId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No hay ejercicio fiscal abierto para " + m.operationDate());
        }
        String entryId = createBankEntry(companyId, fiscalYearId, m,
                acc572, contrapartida, req.invoiceKind(), userId);

        // Actualizar el movimiento.
        jdbcTemplate.update("""
                UPDATE bank_movements
                   SET linked_invoice_id = ?, linked_invoice_kind = ?,
                       journal_entry_id = ?, status = 'POSTED'
                 WHERE id = ? AND company_id = ?
                """,
                req.invoiceId(), req.invoiceKind(), entryId,
                movementId, companyId);

        // Actualizar status de la factura si está disponible.
        updateInvoicePaymentStatus(req.invoiceKind(), req.invoiceId(), m);

        return get(movementId);
    }

    @Transactional
    public BankMovementView ignore(String movementId, String reason) {
        jdbcTemplate.update("""
                UPDATE bank_movements
                   SET status = 'IGNORED', notes = COALESCE(notes, '') || ?
                 WHERE id = ? AND company_id = ? AND status = 'UNRECONCILED'
                """, "\n[IGNORADO] " + (reason == null ? "" : reason),
                movementId, tenantContext.getCurrentCompanyId());
        return get(movementId);
    }

    // ====================================================================
    //  Consulta
    // ====================================================================

    public List<BankMovementView> list(String bankAccountId, String status,
                                         LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, company_id, bank_account_id, operation_date, value_date,
                       description, counterparty_name, counterparty_nif,
                       amount, balance_after, external_ref,
                       import_batch_id, journal_entry_id,
                       linked_invoice_id, linked_invoice_kind,
                       status, notes, created_at, updated_at
                  FROM bank_movements
                 WHERE company_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (bankAccountId != null) { sql.append(" AND bank_account_id = ?"); args.add(bankAccountId); }
        if (status != null && !status.isBlank()) { sql.append(" AND status = ?"); args.add(status); }
        if (from != null) { sql.append(" AND operation_date >= ?"); args.add(Date.valueOf(from)); }
        if (to != null)   { sql.append(" AND operation_date <= ?"); args.add(Date.valueOf(to)); }
        sql.append(" ORDER BY operation_date DESC, created_at DESC");
        return jdbcTemplate.query(sql.toString(), this::mapRow, args.toArray());
    }

    public BankMovementView get(String id) {
        List<BankMovementView> rows = jdbcTemplate.query("""
                SELECT id, company_id, bank_account_id, operation_date, value_date,
                       description, counterparty_name, counterparty_nif,
                       amount, balance_after, external_ref,
                       import_batch_id, journal_entry_id,
                       linked_invoice_id, linked_invoice_kind,
                       status, notes, created_at, updated_at
                  FROM bank_movements
                 WHERE id = ? AND company_id = ?
                """, this::mapRow, id, tenantContext.getCurrentCompanyId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Movimiento no encontrado");
        }
        return rows.get(0);
    }

    /**
     * Sugiere facturas candidatas para reconciliar un movimiento bancario
     * basándose en importe (±1€) y rango de fecha (±15 días).
     */
    public List<MatchCandidate> suggestMatches(String movementId) {
        BankMovementView m = get(movementId);
        String companyId = tenantContext.getCurrentCompanyId();
        BigDecimal absAmount = m.amount().abs();
        BigDecimal lower = absAmount.subtract(new BigDecimal("1.00"));
        BigDecimal upper = absAmount.add(new BigDecimal("1.00"));
        LocalDate from = m.operationDate().minusDays(15);
        LocalDate to = m.operationDate().plusDays(15);

        List<MatchCandidate> out = new ArrayList<>();
        if (m.amount().signum() > 0) {
            // Ingreso → buscar facturas emitidas pendientes de cobro.
            out.addAll(jdbcTemplate.query("""
                    SELECT id AS invoice_id, invoice_number, customer_legal_name AS counterparty,
                           total_amount AS amount, invoice_date,
                           'SALES' AS kind
                      FROM sales_invoices
                     WHERE company_id = ?
                       AND status IN ('VALIDATED','PARTIAL','OVERDUE')
                       AND total_amount BETWEEN ? AND ?
                       AND invoice_date BETWEEN ? AND ?
                     ORDER BY invoice_date DESC
                     LIMIT 5
                    """, this::mapCandidate,
                    companyId, lower, upper, Date.valueOf(from), Date.valueOf(to)));
        } else {
            // Cargo → buscar facturas recibidas pendientes de pago.
            out.addAll(jdbcTemplate.query("""
                    SELECT id AS invoice_id, invoice_number,
                           COALESCE(supplier_name, supplier_nif) AS counterparty,
                           total_amount AS amount, invoice_date,
                           'PURCHASE' AS kind
                      FROM purchase_invoices
                     WHERE company_id = ?
                       AND total_amount BETWEEN ? AND ?
                       AND invoice_date BETWEEN ? AND ?
                     ORDER BY invoice_date DESC
                     LIMIT 5
                    """, this::mapCandidate,
                    companyId, lower, upper, Date.valueOf(from), Date.valueOf(to)));
        }
        return out;
    }

    // ====================================================================
    //  Auto-asiento de cobro/pago
    // ====================================================================

    private String createBankEntry(String companyId, String fiscalYearId,
                                     BankMovementView m,
                                     String acc572, String contrapartida,
                                     String invoiceKind, String userId) {
        Integer max = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0)
                  FROM journal_entries
                 WHERE company_id = ? AND fiscal_year_id = ?
                """, Integer.class, companyId, fiscalYearId);
        int entryNumber = (max == null ? 0 : max) + 1;

        String entryId = UUID.randomUUID().toString();
        String concept = "SALES".equals(invoiceKind)
                ? "Cobro " + safe(m.counterpartyName())
                : "Pago " + safe(m.counterpartyName());
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'BANK_MOVEMENT', ?, 'POSTED', FALSE, FALSE, ?)
                """,
                entryId, companyId, fiscalYearId, entryNumber,
                Date.valueOf(m.operationDate()),
                truncate(concept, 240),
                m.id(), userId);

        BigDecimal abs = m.amount().abs();
        if (m.amount().signum() > 0) {
            // Ingreso: Debe 572 / Haber 430
            insertLine(entryId, acc572, "Cobro factura " + safe(m.description()), abs, BigDecimal.ZERO);
            insertLine(entryId, contrapartida, "Cobrado " + safe(m.counterpartyName()), BigDecimal.ZERO, abs);
        } else {
            // Cargo: Debe 400 / Haber 572
            insertLine(entryId, contrapartida, "Pagado " + safe(m.counterpartyName()), abs, BigDecimal.ZERO);
            insertLine(entryId, acc572, "Pago factura " + safe(m.description()), BigDecimal.ZERO, abs);
        }
        return entryId;
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

    private void updateInvoicePaymentStatus(String kind, String invoiceId, BankMovementView m) {
        try {
            if ("SALES".equals(kind)) {
                jdbcTemplate.update("""
                        UPDATE sales_invoices SET status = 'PAID', paid_at = CURRENT_TIMESTAMP
                         WHERE id = ? AND company_id = ?
                        """, invoiceId, tenantContext.getCurrentCompanyId());
            }
            // purchases no tiene status PAID actualmente (es POSTED desde V40).
        } catch (Exception ex) {
            // Best-effort: si la columna paid_at no existe en algún entorno,
            // el cobro se cuenta igual via el asiento.
        }
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private String findAccountByPrefix(String companyId, String prefix) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE AND code LIKE ?
                 ORDER BY LENGTH(code), code
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, prefix + "%");
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String findFiscalYearId(String companyId, LocalDate date) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM fiscal_years
                 WHERE company_id = ?
                   AND start_date <= ? AND end_date >= ?
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"),
                companyId, Date.valueOf(date), Date.valueOf(date));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void validate(MovementRequest req) {
        if (req.bankAccountId() == null) throw bad("Falta cuenta bancaria.");
        if (req.operationDate() == null) throw bad("Falta fecha operación.");
        if (req.amount() == null || req.amount().signum() == 0) throw bad("Importe inválido.");
    }

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    private BankMovementView mapRow(ResultSet rs, int n) throws SQLException {
        java.sql.Timestamp ca = rs.getTimestamp("created_at");
        java.sql.Timestamp ua = rs.getTimestamp("updated_at");
        java.sql.Date vd = rs.getDate("value_date");
        return new BankMovementView(
                rs.getString("id"), rs.getString("company_id"),
                rs.getString("bank_account_id"),
                rs.getDate("operation_date").toLocalDate(),
                vd == null ? null : vd.toLocalDate(),
                rs.getString("description"),
                rs.getString("counterparty_name"), rs.getString("counterparty_nif"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("balance_after"),
                rs.getString("external_ref"), rs.getString("import_batch_id"),
                rs.getString("journal_entry_id"),
                rs.getString("linked_invoice_id"), rs.getString("linked_invoice_kind"),
                rs.getString("status"), rs.getString("notes"),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant());
    }

    private MatchCandidate mapCandidate(ResultSet rs, int n) throws SQLException {
        return new MatchCandidate(
                rs.getString("invoice_id"), rs.getString("invoice_number"),
                rs.getString("counterparty"), rs.getBigDecimal("amount"),
                rs.getDate("invoice_date").toLocalDate(),
                rs.getString("kind"));
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String safe(String s) { return s == null ? "" : s; }
    private static String normalizeNif(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase();
    }
    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // ====================================================================
    //  DTOs
    // ====================================================================

    public record MovementRequest(
            String bankAccountId,
            LocalDate operationDate, LocalDate valueDate,
            String description, String counterpartyName, String counterpartyNif,
            BigDecimal amount, BigDecimal balanceAfter,
            String externalRef, String notes
    ) {}

    public record LinkRequest(
            String invoiceKind, String invoiceId, String counterpartyAccountId
    ) {}

    public record BankMovementView(
            String id, String companyId, String bankAccountId,
            LocalDate operationDate, LocalDate valueDate,
            String description, String counterpartyName, String counterpartyNif,
            BigDecimal amount, BigDecimal balanceAfter,
            String externalRef, String importBatchId, String journalEntryId,
            String linkedInvoiceId, String linkedInvoiceKind,
            String status, String notes,
            Instant createdAt, Instant updatedAt
    ) {}

    public record MatchCandidate(
            String invoiceId, String invoiceNumber, String counterparty,
            BigDecimal amount, LocalDate invoiceDate, String invoiceKind
    ) {}
}
