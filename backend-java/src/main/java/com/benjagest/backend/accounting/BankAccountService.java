package com.benjagest.backend.accounting;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CRUD de cuentas bancarias para el módulo contable. Cada cuenta lleva
 * IBAN, alias humano, opcionalmente referencia a la cuenta contable
 * (típicamente del grupo 572). Si no hay accounting_account_id, los
 * cobros/pagos generados se enlazarán a la 572 genérica de la empresa.
 */
@Service
public class BankAccountService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public BankAccountService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public List<BankAccountView> list(Boolean activeOnly) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, company_id, alias, iban, bic, bank_name,
                       accounting_account_id, currency,
                       opening_balance, opening_date, active, notes,
                       created_at, updated_at
                  FROM bank_accounts
                 WHERE company_id = ?
                """);
        if (Boolean.TRUE.equals(activeOnly)) sql.append(" AND active = TRUE");
        sql.append(" ORDER BY active DESC, alias ASC");
        return jdbcTemplate.query(sql.toString(), this::mapRow,
                tenantContext.getCurrentCompanyId());
    }

    public BankAccountView get(String id) {
        List<BankAccountView> rows = jdbcTemplate.query("""
                SELECT id, company_id, alias, iban, bic, bank_name,
                       accounting_account_id, currency,
                       opening_balance, opening_date, active, notes,
                       created_at, updated_at
                  FROM bank_accounts
                 WHERE company_id = ? AND id = ?
                """, this::mapRow, tenantContext.getCurrentCompanyId(), id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cuenta bancaria no encontrada");
        }
        return rows.get(0);
    }

    @Transactional
    public BankAccountView create(BankAccountRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO bank_accounts (
                    id, company_id, alias, iban, bic, bank_name,
                    accounting_account_id, currency,
                    opening_balance, opening_date, active, notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                """,
                id, tenantContext.getCurrentCompanyId(),
                req.alias().trim(),
                blank(req.iban()), blank(req.bic()), blank(req.bankName()),
                blank(req.accountingAccountId()),
                req.currency() == null ? "EUR" : req.currency(),
                req.openingBalance() == null ? BigDecimal.ZERO : req.openingBalance(),
                req.openingDate() == null ? null : java.sql.Date.valueOf(req.openingDate()),
                blank(req.notes()));
        return get(id);
    }

    @Transactional
    public BankAccountView update(String id, BankAccountRequest req) {
        validate(req);
        jdbcTemplate.update("""
                UPDATE bank_accounts
                   SET alias = ?, iban = ?, bic = ?, bank_name = ?,
                       accounting_account_id = ?, currency = ?,
                       opening_balance = ?, opening_date = ?, notes = ?
                 WHERE id = ? AND company_id = ?
                """,
                req.alias().trim(),
                blank(req.iban()), blank(req.bic()), blank(req.bankName()),
                blank(req.accountingAccountId()),
                req.currency() == null ? "EUR" : req.currency(),
                req.openingBalance() == null ? BigDecimal.ZERO : req.openingBalance(),
                req.openingDate() == null ? null : java.sql.Date.valueOf(req.openingDate()),
                blank(req.notes()),
                id, tenantContext.getCurrentCompanyId());
        return get(id);
    }

    @Transactional
    public void setActive(String id, boolean active) {
        jdbcTemplate.update("""
                UPDATE bank_accounts SET active = ?
                 WHERE id = ? AND company_id = ?
                """, active, id, tenantContext.getCurrentCompanyId());
    }

    /** Resuelve la cuenta contable 572 para esta cuenta bancaria. */
    String resolveAccountingAccountId(String bankAccountId) {
        BankAccountView ba = get(bankAccountId);
        if (ba.accountingAccountId() != null && !ba.accountingAccountId().isBlank()) {
            return ba.accountingAccountId();
        }
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE AND code LIKE '572%'
                 ORDER BY LENGTH(code), code
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), tenantContext.getCurrentCompanyId());
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void validate(BankAccountRequest req) {
        if (req.alias() == null || req.alias().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El alias es obligatorio");
        }
        if (req.iban() != null && !req.iban().isBlank()) {
            String iban = req.iban().replaceAll("\\s+", "").toUpperCase();
            if (iban.length() < 15 || iban.length() > 34) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "IBAN inválido (longitud).");
            }
        }
    }

    private BankAccountView mapRow(ResultSet rs, int n) throws SQLException {
        java.sql.Timestamp ca = rs.getTimestamp("created_at");
        java.sql.Timestamp ua = rs.getTimestamp("updated_at");
        java.sql.Date od = rs.getDate("opening_date");
        return new BankAccountView(
                rs.getString("id"), rs.getString("company_id"),
                rs.getString("alias"), rs.getString("iban"),
                rs.getString("bic"), rs.getString("bank_name"),
                rs.getString("accounting_account_id"),
                rs.getString("currency"),
                rs.getBigDecimal("opening_balance"),
                od == null ? null : od.toLocalDate(),
                rs.getBoolean("active"), rs.getString("notes"),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant());
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    public record BankAccountRequest(
            String alias, String iban, String bic, String bankName,
            String accountingAccountId, String currency,
            BigDecimal openingBalance, LocalDate openingDate, String notes
    ) {}

    public record BankAccountView(
            String id, String companyId,
            String alias, String iban, String bic, String bankName,
            String accountingAccountId, String currency,
            BigDecimal openingBalance, LocalDate openingDate,
            boolean active, String notes,
            Instant createdAt, Instant updatedAt
    ) {}
}
