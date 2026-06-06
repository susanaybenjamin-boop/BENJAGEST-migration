package com.benjagest.backend.accounting;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Generador de asientos contables del cierre de ejercicio.
 *
 * <p>Tres asientos clásicos del PGC español (RD 1514/2007):
 *
 * <ol>
 *   <li><b>Regularización</b>: lleva el saldo neto de los grupos 6 (gastos) y
 *       7 (ingresos) a la cuenta 129 (Resultado del ejercicio).
 *       <pre>
 *         Debe   700x (ingresos)  saldo acreedor
 *                       Haber  129              ingresos
 *         Debe   129              gastos
 *                       Haber  600x (gastos)    saldo deudor
 *       </pre>
 *       Resultado: si los ingresos &gt; gastos, 129 queda con saldo acreedor
 *       (beneficio); si gastos &gt; ingresos, 129 queda con saldo deudor
 *       (pérdida).</li>
 *
 *   <li><b>Cierre</b>: pone TODAS las cuentas con saldo a 0.
 *       <pre>
 *         Debe (todas las cuentas con saldo acreedor) por su saldo
 *                       Haber (todas las cuentas con saldo deudor) por su saldo
 *       </pre>
 *       Tras este asiento, ninguna cuenta tiene saldo. La balanza es 0.</li>
 *
 *   <li><b>Apertura</b> (año siguiente): invierso del cierre, pero excluye
 *       grupos 6 y 7 (que se cerraron contra 129). Para el momento, este
 *       servicio NO crea el asiento de apertura — lo dejamos como hueco
 *       técnico para un sub-slice futuro donde la UI permita "Cerrar y
 *       abrir siguiente" en un solo paso. La razón: requiere crear el
 *       fiscal_year siguiente, decidir la fecha de apertura, y mover saldos
 *       de cuentas patrimoniales — todo eso es operacional, no contable.</li>
 * </ol>
 *
 * <p>Idempotencia: si ya existe un asiento de regularización o cierre para
 * ese fiscal_year_id, el servicio devuelve el existente sin volver a crearlo.
 * Para regenerar, primero se debe anular el asiento (status='VOIDED').
 */
@Service
public class ClosingEntriesService {

    public static final String SRC_REGULARIZATION = "YEAR_CLOSE_REGULARIZATION";
    public static final String SRC_CLOSING = "YEAR_CLOSE_CLOSING";

    /** Cuenta donde va el resultado del ejercicio (PGC: 129). */
    private static final String RESULT_ACCOUNT_CODE = "129";

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public ClosingEntriesService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    /**
     * Devuelve el resumen de saldos que se van a usar para regularizar.
     * Útil para mostrar en UI antes de cerrar. No genera asientos.
     */
    public RegularizationPreview previewRegularization(int periodYear) {
        String companyId = tenantContext.getCurrentCompanyId();
        FiscalYearRef fy = findFiscalYear(companyId, periodYear);

        BigDecimal expenses = sumGroupBalance(companyId, fy, "6"); // Debe - Haber
        BigDecimal incomes = sumGroupBalance(companyId, fy, "7").negate(); // Haber - Debe
        BigDecimal result = incomes.subtract(expenses);

        return new RegularizationPreview(fy.id(), periodYear, expenses, incomes, result);
    }

    /**
     * Genera (si no existe) el asiento de regularización para el ejercicio
     * indicado. Si ya hay uno DRAFT/POSTED, lo devuelve.
     */
    @Transactional
    public String generateRegularizationEntry(int periodYear, String userId) {
        String companyId = tenantContext.getCurrentCompanyId();
        FiscalYearRef fy = findFiscalYear(companyId, periodYear);

        // 1) Idempotencia: si ya hay regularización, devolverla.
        String existing = findExistingEntry(companyId, fy.id(), SRC_REGULARIZATION);
        if (existing != null) return existing;

        // 2) Cuenta 129 — debe existir tras V46.
        String acc129 = findAccountByCode(companyId, RESULT_ACCOUNT_CODE);
        if (acc129 == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No existe la cuenta 129 (Resultado del ejercicio). "
                            + "Aplica V46 (PGC PYMES) antes de cerrar.");
        }

        // 3) Recolectar saldos por cuenta de grupos 6 y 7.
        List<AccountBalance> expensesBal = listAccountBalances(companyId, fy, "6");
        List<AccountBalance> incomesBal = listAccountBalances(companyId, fy, "7");
        if (expensesBal.isEmpty() && incomesBal.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No hay movimientos contables en los grupos 6/7 para "
                            + periodYear + ". No procede regularización.");
        }

        // 4) Crear asiento cabecera.
        int entryNumber = nextEntryNumber(companyId, fy.id());
        String entryId = UUID.randomUUID().toString();
        LocalDate entryDate = LocalDate.of(periodYear, Month.DECEMBER, 31);
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', FALSE, ?)
                """,
                entryId, companyId, fy.id(), entryNumber,
                Date.valueOf(entryDate),
                "Regularización ejercicio " + periodYear,
                SRC_REGULARIZATION, String.valueOf(periodYear),
                userId);

        // 5) Líneas: cierre del grupo 6 (Haber a sus saldos deudores)
        //    y del grupo 7 (Debe a sus saldos acreedores). La contrapartida
        //    es 129.
        BigDecimal sum129Debit = BigDecimal.ZERO;
        BigDecimal sum129Credit = BigDecimal.ZERO;

        for (AccountBalance ab : expensesBal) {
            BigDecimal saldoDeudor = ab.debit().subtract(ab.credit());
            if (saldoDeudor.signum() <= 0) continue;
            // Haber por su saldo deudor → cuenta queda en 0
            insertLine(entryId, ab.accountId(), "Cierre " + ab.code(),
                    BigDecimal.ZERO, saldoDeudor);
            sum129Debit = sum129Debit.add(saldoDeudor);
        }
        for (AccountBalance ab : incomesBal) {
            BigDecimal saldoAcreedor = ab.credit().subtract(ab.debit());
            if (saldoAcreedor.signum() <= 0) continue;
            // Debe por su saldo acreedor → cuenta queda en 0
            insertLine(entryId, ab.accountId(), "Cierre " + ab.code(),
                    saldoAcreedor, BigDecimal.ZERO);
            sum129Credit = sum129Credit.add(saldoAcreedor);
        }
        // Línea 129: la diferencia. Si beneficio (ingresos>gastos) va al Haber.
        BigDecimal diff = sum129Credit.subtract(sum129Debit);
        if (diff.signum() > 0) {
            insertLine(entryId, acc129, "Beneficio del ejercicio " + periodYear,
                    BigDecimal.ZERO, diff);
        } else if (diff.signum() < 0) {
            insertLine(entryId, acc129, "Pérdida del ejercicio " + periodYear,
                    diff.negate(), BigDecimal.ZERO);
        }
        return entryId;
    }

    /**
     * Genera (si no existe) el asiento de cierre que pone TODAS las cuentas
     * a saldo 0. Debe ejecutarse DESPUÉS del de regularización.
     */
    @Transactional
    public String generateClosingEntry(int periodYear, String userId) {
        String companyId = tenantContext.getCurrentCompanyId();
        FiscalYearRef fy = findFiscalYear(companyId, periodYear);

        String existing = findExistingEntry(companyId, fy.id(), SRC_CLOSING);
        if (existing != null) return existing;

        // Comprobar que la regularización ya está hecha.
        if (findExistingEntry(companyId, fy.id(), SRC_REGULARIZATION) == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Antes del asiento de cierre debe existir el de "
                            + "regularización. Genera regularización primero.");
        }

        // Recolectar saldos vivos de TODAS las cuentas con movimiento.
        List<AccountBalance> all = listAllAccountBalances(companyId, fy);
        if (all.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No hay cuentas con movimientos en " + periodYear + ".");
        }

        int entryNumber = nextEntryNumber(companyId, fy.id());
        String entryId = UUID.randomUUID().toString();
        LocalDate entryDate = LocalDate.of(periodYear, Month.DECEMBER, 31);
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', FALSE, ?)
                """,
                entryId, companyId, fy.id(), entryNumber,
                Date.valueOf(entryDate),
                "Cierre ejercicio " + periodYear,
                SRC_CLOSING, String.valueOf(periodYear),
                userId);

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (AccountBalance ab : all) {
            BigDecimal saldo = ab.debit().subtract(ab.credit());
            if (saldo.signum() == 0) continue;
            if (saldo.signum() > 0) {
                // Saldo deudor → Haber por el saldo
                insertLine(entryId, ab.accountId(), "Cierre " + ab.code(),
                        BigDecimal.ZERO, saldo);
                totalCredit = totalCredit.add(saldo);
            } else {
                // Saldo acreedor → Debe por el saldo (positivo)
                BigDecimal positivo = saldo.negate();
                insertLine(entryId, ab.accountId(), "Cierre " + ab.code(),
                        positivo, BigDecimal.ZERO);
                totalDebit = totalDebit.add(positivo);
            }
        }
        // Sanity check.
        if (totalDebit.subtract(totalCredit).abs().compareTo(new BigDecimal("0.02")) > 0) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "El asiento de cierre no cuadra: Debe=" + totalDebit
                            + " Haber=" + totalCredit
                            + ". ¿La regularización dejó residuos?");
        }
        return entryId;
    }

    // ====================================================================
    //  Helpers de consulta
    // ====================================================================

    private FiscalYearRef findFiscalYear(String companyId, int year) {
        List<FiscalYearRef> ids = jdbcTemplate.query("""
                SELECT id, status, start_date, end_date
                  FROM fiscal_years
                 WHERE company_id = ?
                   AND year_number = ?
                 LIMIT 1
                """,
                (rs, n) -> new FiscalYearRef(
                        rs.getString("id"), rs.getString("status"),
                        rs.getDate("start_date").toLocalDate(),
                        rs.getDate("end_date").toLocalDate()),
                companyId, year);
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No existe fiscal_year " + year + " para la empresa.");
        }
        return ids.get(0);
    }

    private String findAccountByCode(String companyId, String code) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND code = ? AND active = TRUE
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, code);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String findExistingEntry(String companyId, String fiscalYearId, String sourceType) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM journal_entries
                 WHERE company_id = ?
                   AND fiscal_year_id = ?
                   AND source_type = ?
                   AND status != 'VOIDED'
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"),
                companyId, fiscalYearId, sourceType);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private int nextEntryNumber(String companyId, String fiscalYearId) {
        Integer max = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0)
                  FROM journal_entries
                 WHERE company_id = ? AND fiscal_year_id = ?
                """, Integer.class, companyId, fiscalYearId);
        return (max == null ? 0 : max) + 1;
    }

    /** Saldos por cuenta para un prefijo de código (grupo 6 o 7). */
    private List<AccountBalance> listAccountBalances(String companyId, FiscalYearRef fy, String prefix) {
        return jdbcTemplate.query("""
                SELECT a.id AS account_id, a.code AS code,
                       COALESCE(SUM(l.debit), 0)  AS total_debit,
                       COALESCE(SUM(l.credit), 0) AS total_credit
                  FROM accounting_accounts a
                  JOIN journal_entry_lines l ON l.account_id = a.id
                  JOIN journal_entries je   ON je.id = l.journal_entry_id
                 WHERE a.company_id = ?
                   AND je.fiscal_year_id = ?
                   AND je.status != 'VOIDED'
                   AND (je.source_type IS NULL
                        OR je.source_type NOT IN ('YEAR_CLOSE_REGULARIZATION','YEAR_CLOSE_CLOSING'))
                   AND a.code LIKE ?
                 GROUP BY a.id, a.code
                HAVING (COALESCE(SUM(l.debit), 0) <> 0
                     OR COALESCE(SUM(l.credit), 0) <> 0)
                """,
                (rs, n) -> new AccountBalance(
                        rs.getString("account_id"),
                        rs.getString("code"),
                        rs.getBigDecimal("total_debit"),
                        rs.getBigDecimal("total_credit")),
                companyId, fy.id(), prefix + "%");
    }

    /** Saldos vivos por cuenta para TODAS las cuentas tras regularización. */
    private List<AccountBalance> listAllAccountBalances(String companyId, FiscalYearRef fy) {
        return jdbcTemplate.query("""
                SELECT a.id AS account_id, a.code AS code,
                       COALESCE(SUM(l.debit), 0)  AS total_debit,
                       COALESCE(SUM(l.credit), 0) AS total_credit
                  FROM accounting_accounts a
                  JOIN journal_entry_lines l ON l.account_id = a.id
                  JOIN journal_entries je   ON je.id = l.journal_entry_id
                 WHERE a.company_id = ?
                   AND je.fiscal_year_id = ?
                   AND je.status != 'VOIDED'
                   AND (je.source_type IS NULL OR je.source_type <> 'YEAR_CLOSE_CLOSING')
                 GROUP BY a.id, a.code
                HAVING COALESCE(SUM(l.debit), 0) <> COALESCE(SUM(l.credit), 0)
                """,
                (rs, n) -> new AccountBalance(
                        rs.getString("account_id"),
                        rs.getString("code"),
                        rs.getBigDecimal("total_debit"),
                        rs.getBigDecimal("total_credit")),
                companyId, fy.id());
    }

    private BigDecimal sumGroupBalance(String companyId, FiscalYearRef fy, String prefix) {
        BigDecimal v = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(l.debit), 0) - COALESCE(SUM(l.credit), 0)
                  FROM journal_entry_lines l
                  JOIN journal_entries je   ON je.id = l.journal_entry_id
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE a.company_id = ?
                   AND je.fiscal_year_id = ?
                   AND je.status != 'VOIDED'
                   AND (je.source_type IS NULL
                        OR je.source_type NOT IN ('YEAR_CLOSE_REGULARIZATION','YEAR_CLOSE_CLOSING'))
                   AND a.code LIKE ?
                """, BigDecimal.class, companyId, fy.id(), prefix + "%");
        return v == null ? BigDecimal.ZERO : v;
    }

    private void insertLine(String entryId, String accountId, String description,
                              BigDecimal debit, BigDecimal credit) {
        jdbcTemplate.update("""
                INSERT INTO journal_entry_lines (
                    id, journal_entry_id, account_id, description,
                    debit, credit
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entryId, accountId,
                description, debit, credit);
    }

    // ====================================================================
    //  DTOs públicos
    // ====================================================================

    public record RegularizationPreview(
            String fiscalYearId,
            int periodYear,
            BigDecimal expensesTotal,
            BigDecimal incomesTotal,
            BigDecimal resultAmount
    ) {}

    private record FiscalYearRef(String id, String status, LocalDate startDate, LocalDate endDate) {}

    private record AccountBalance(String accountId, String code, BigDecimal debit, BigDecimal credit) {}

    /** Sentinel — placeholder para futuras métricas. */
    @SuppressWarnings("unused")
    private static Map<String, Object> _sentinel() { return Map.of(); }

    /** Sentinel — futuras listas de detalle. */
    @SuppressWarnings("unused")
    private static List<Object> _sentinelList() { return new ArrayList<>(); }
}
