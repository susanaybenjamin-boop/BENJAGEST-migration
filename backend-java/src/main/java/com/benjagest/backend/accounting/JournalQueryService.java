package com.benjagest.backend.accounting;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Consultas de los libros contables: Diario, Mayor, Sumas y Saldos.
 *
 * <p>Estas vistas son OBLIGATORIAS según el Código de Comercio art. 25
 * y la legislación tributaria (Reglamento IVA art. 62 ss.). Se entregan
 * en formato consultable y exportable.
 *
 * <ul>
 *   <li><b>Libro Diario</b>: TODOS los asientos POSTED en orden cronológico
 *       y por entry_number. Incluye los anulados (VOIDED) pero con marca.</li>
 *   <li><b>Libro Mayor</b>: movimientos de una cuenta concreta con saldo
 *       progresivo. Una vista por cuenta o agrupada.</li>
 *   <li><b>Sumas y Saldos</b>: por cuenta, suma de debes + suma de haberes
 *       + saldo deudor o acreedor a fecha. Útil para revisión y para los
 *       modelos AEAT.</li>
 * </ul>
 *
 * <p>Los asientos {@code DRAFT} NO computan en sumas y saldos — solo
 * aparecen en el Diario marcados como pendientes. Es decisión legal:
 * solo lo POSTED es contabilidad oficial.
 */
@Service
public class JournalQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public JournalQueryService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    // ====================================================================
    //  Libro Diario
    // ====================================================================

    public List<DiaryEntry> diary(LocalDate from, LocalDate to,
                                    String statusFilter,
                                    String sourceTypeFilter,
                                    Integer limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT je.id, je.entry_number, je.entry_date,
                       je.concept, je.source_type, je.source_id,
                       je.status, je.reviewed, je.auto_proposed,
                       je.proposed_confidence,
                       je.source_pdf_sha256,
                       (SELECT COALESCE(SUM(debit),0)
                          FROM journal_entry_lines
                         WHERE journal_entry_id = je.id) AS total_debit,
                       (SELECT COALESCE(SUM(credit),0)
                          FROM journal_entry_lines
                         WHERE journal_entry_id = je.id) AS total_credit,
                       (SELECT COUNT(*)
                          FROM journal_entry_lines
                         WHERE journal_entry_id = je.id) AS num_lines
                  FROM journal_entries je
                 WHERE je.company_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());

        if (from != null) { sql.append(" AND je.entry_date >= ?"); args.add(Date.valueOf(from)); }
        if (to != null)   { sql.append(" AND je.entry_date <= ?"); args.add(Date.valueOf(to)); }
        if (statusFilter != null && !statusFilter.isBlank()) {
            sql.append(" AND je.status = ?"); args.add(statusFilter);
        }
        if (sourceTypeFilter != null && !sourceTypeFilter.isBlank()) {
            // Slice 3U — soportar CSV: "SALES_INVOICE,SALES_PDF_IMPORT,
            // RECURRING_ACCOUNTING" para unificar el listado de ventas
            // del cliente (todas las ventas vengan de donde vengan).
            // Si incluye "MANUAL" en la lista, eso significa "source_type
            // IS NULL" — se combina con OR.
            String[] parts = sourceTypeFilter.split(",");
            List<String> nonManual = new ArrayList<>();
            boolean includeManual = false;
            for (String p : parts) {
                String t = p.trim();
                if (t.isEmpty()) continue;
                if ("MANUAL".equalsIgnoreCase(t)) includeManual = true;
                else nonManual.add(t);
            }
            if (!nonManual.isEmpty() || includeManual) {
                sql.append(" AND (");
                boolean first = true;
                if (!nonManual.isEmpty()) {
                    sql.append("je.source_type IN (");
                    for (int i = 0; i < nonManual.size(); i++) {
                        if (i > 0) sql.append(',');
                        sql.append('?');
                        args.add(nonManual.get(i));
                    }
                    sql.append(')');
                    first = false;
                }
                if (includeManual) {
                    if (!first) sql.append(" OR ");
                    sql.append("je.source_type IS NULL");
                }
                sql.append(')');
            }
        }
        sql.append(" ORDER BY je.entry_date ASC, je.entry_number ASC");
        if (limit != null && limit > 0) {
            sql.append(" LIMIT ?"); args.add(limit);
        }
        return jdbcTemplate.query(sql.toString(), this::mapDiary, args.toArray());
    }

    // ====================================================================
    //  Libro Mayor (por cuenta)
    // ====================================================================

    public LedgerView ledger(String accountId, LocalDate from, LocalDate to) {
        String companyId = tenantContext.getCurrentCompanyId();
        // Saldo de apertura: suma debe-haber de movimientos POSTED antes de `from`.
        BigDecimal opening = BigDecimal.ZERO;
        if (from != null) {
            BigDecimal v = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(l.debit - l.credit), 0)
                      FROM journal_entry_lines l
                      JOIN journal_entries je ON je.id = l.journal_entry_id
                     WHERE je.company_id = ?
                       AND l.account_id = ?
                       AND je.status = 'POSTED'
                       AND je.entry_date < ?
                    """, BigDecimal.class, companyId, accountId, Date.valueOf(from));
            opening = v == null ? BigDecimal.ZERO : v;
        }

        StringBuilder sql = new StringBuilder("""
                SELECT je.id AS entry_id, je.entry_number, je.entry_date,
                       je.concept, je.status,
                       l.id AS line_id, l.description AS line_description,
                       l.debit, l.credit
                  FROM journal_entry_lines l
                  JOIN journal_entries je ON je.id = l.journal_entry_id
                 WHERE je.company_id = ?
                   AND l.account_id = ?
                   AND je.status = 'POSTED'
                """);
        List<Object> args = new ArrayList<>();
        args.add(companyId);
        args.add(accountId);
        if (from != null) { sql.append(" AND je.entry_date >= ?"); args.add(Date.valueOf(from)); }
        if (to != null)   { sql.append(" AND je.entry_date <= ?"); args.add(Date.valueOf(to)); }
        sql.append(" ORDER BY je.entry_date ASC, je.entry_number ASC, l.id ASC");

        List<LedgerLine> raw = jdbcTemplate.query(sql.toString(),
                (rs, n) -> new LedgerLine(
                        rs.getString("entry_id"), rs.getInt("entry_number"),
                        rs.getDate("entry_date").toLocalDate(),
                        rs.getString("concept"), rs.getString("status"),
                        rs.getString("line_id"), rs.getString("line_description"),
                        rs.getBigDecimal("debit"), rs.getBigDecimal("credit"),
                        BigDecimal.ZERO),
                args.toArray());

        // Calcular saldo progresivo.
        List<LedgerLine> withBalance = new ArrayList<>(raw.size());
        BigDecimal running = opening;
        for (LedgerLine l : raw) {
            running = running.add(l.debit()).subtract(l.credit());
            withBalance.add(new LedgerLine(l.entryId(), l.entryNumber(), l.entryDate(),
                    l.concept(), l.status(), l.lineId(), l.lineDescription(),
                    l.debit(), l.credit(), running));
        }

        // Datos de la cuenta.
        List<String[]> info = jdbcTemplate.query("""
                SELECT code, name FROM accounting_accounts
                 WHERE id = ? AND company_id = ?
                """, (rs, n) -> new String[]{rs.getString("code"), rs.getString("name")},
                accountId, companyId);
        String code = info.isEmpty() ? "?" : info.get(0)[0];
        String name = info.isEmpty() ? "?" : info.get(0)[1];

        return new LedgerView(accountId, code, name, opening, running, withBalance);
    }

    // ====================================================================
    //  Sumas y Saldos
    // ====================================================================

    public List<BalanceRow> balance(LocalDate from, LocalDate to,
                                      String accountPrefix) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.id AS account_id, a.code, a.name,
                       COALESCE(SUM(l.debit), 0) AS total_debit,
                       COALESCE(SUM(l.credit), 0) AS total_credit
                  FROM accounting_accounts a
                  LEFT JOIN journal_entry_lines l ON l.account_id = a.id
                  LEFT JOIN journal_entries je ON je.id = l.journal_entry_id
                                              AND je.status = 'POSTED'
                """);
        List<Object> args = new ArrayList<>();
        sql.append(" WHERE a.company_id = ?");
        args.add(tenantContext.getCurrentCompanyId());
        if (from != null) { sql.append(" AND (je.entry_date IS NULL OR je.entry_date >= ?)"); args.add(Date.valueOf(from)); }
        if (to != null)   { sql.append(" AND (je.entry_date IS NULL OR je.entry_date <= ?)"); args.add(Date.valueOf(to)); }
        if (accountPrefix != null && !accountPrefix.isBlank()) {
            sql.append(" AND a.code LIKE ?"); args.add(accountPrefix + "%");
        }
        sql.append("""
                 GROUP BY a.id, a.code, a.name
                HAVING (COALESCE(SUM(l.debit), 0) <> 0
                     OR COALESCE(SUM(l.credit), 0) <> 0)
                 ORDER BY a.code ASC
                """);
        return jdbcTemplate.query(sql.toString(), (rs, n) -> {
            BigDecimal d = rs.getBigDecimal("total_debit");
            BigDecimal c = rs.getBigDecimal("total_credit");
            BigDecimal saldo = d.subtract(c);
            return new BalanceRow(
                    rs.getString("account_id"), rs.getString("code"),
                    rs.getString("name"),
                    d, c,
                    saldo.signum() >= 0 ? saldo : BigDecimal.ZERO,
                    saldo.signum() < 0 ? saldo.negate() : BigDecimal.ZERO);
        }, args.toArray());
    }

    // ====================================================================
    //  Mappers
    // ====================================================================

    private DiaryEntry mapDiary(ResultSet rs, int n) throws SQLException {
        return new DiaryEntry(
                rs.getString("id"), rs.getInt("entry_number"),
                rs.getDate("entry_date").toLocalDate(),
                rs.getString("concept"),
                rs.getString("source_type"), rs.getString("source_id"),
                rs.getString("status"), rs.getBoolean("reviewed"),
                rs.getBoolean("auto_proposed"),
                rs.getBigDecimal("proposed_confidence"),
                rs.getBigDecimal("total_debit"),
                rs.getBigDecimal("total_credit"),
                rs.getInt("num_lines"),
                rs.getString("source_pdf_sha256"));
    }

    // ====================================================================
    //  DTOs
    // ====================================================================

    public record DiaryEntry(
            String id, int entryNumber, LocalDate entryDate, String concept,
            String sourceType, String sourceId,
            String status, boolean reviewed, boolean autoProposed,
            BigDecimal proposedConfidence,
            BigDecimal totalDebit, BigDecimal totalCredit,
            int numLines,
            /** SHA-256 del PDF importado. Sirve para detectar duplicados
             * en el cliente (si dos asientos comparten SHA, mismo PDF). */
            String sourcePdfSha256
    ) {}

    public record LedgerLine(
            String entryId, int entryNumber, LocalDate entryDate,
            String concept, String status,
            String lineId, String lineDescription,
            BigDecimal debit, BigDecimal credit,
            BigDecimal runningBalance
    ) {}

    public record LedgerView(
            String accountId, String accountCode, String accountName,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            List<LedgerLine> movements
    ) {}

    public record BalanceRow(
            String accountId, String code, String name,
            BigDecimal totalDebit, BigDecimal totalCredit,
            BigDecimal saldoDeudor, BigDecimal saldoAcreedor
    ) {}

    /**
     * Listado plano de cuentas para alimentar combos de selección. Si
     * {@code search} viene, filtra por {@code code LIKE ?% OR LOWER(name) LIKE %?%}.
     * Limitado a 500 — los combos no muestran más.
     */
    public List<java.util.Map<String, Object>> listAccountsForCombos(String search) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, code, name, account_type
                  FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (search != null && !search.isBlank()) {
            String lower = search.toLowerCase().trim();
            sql.append(" AND (code LIKE ? OR LOWER(name) LIKE ?)");
            args.add(search.trim() + "%");
            args.add("%" + lower + "%");
        }
        sql.append(" ORDER BY code LIMIT 500");
        return jdbcTemplate.query(sql.toString(), (rs, n) -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("code", rs.getString("code"));
            m.put("name", rs.getString("name"));
            m.put("accountType", rs.getString("account_type"));
            return m;
        }, args.toArray());
    }
}
