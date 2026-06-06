package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Asientos contables del inmovilizado (PGC grupo 2 / RD 1514/2007).
 *
 * <p>Tres escenarios:
 * <ul>
 *   <li><b>Alta</b>: Debe 21x (inmovilizado) + 472 (IVA soportado) / Haber
 *       523 (proveedor inmovilizado a corto) o 572 (banco) si se pagó al contado.</li>
 *   <li><b>Amortización periódica</b>: Debe 681 (Dotación amortización) /
 *       Haber 281 (Amortización acumulada). Por defecto anual, también
 *       mensual.</li>
 *   <li><b>Baja</b>: Debe 281 (acumulada) + Banco (si se vende) + 671
 *       (pérdida) / Haber 21x (valor inicial) + 771 (beneficio).</li>
 * </ul>
 *
 * <p>Para encontrar las cuentas usamos prefijos: 21 para tipo de
 * inmovilizado (categoría → cuenta), 281 para acumulada, 681 para
 * dotación, 671/771 para pérdida/beneficio. Cada empresa puede tener
 * sub-cuentas analíticas — usamos la más específica que matchee.
 */
@Service
public class FixedAssetEntriesService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final FiscalYearGuardService fiscalGuard;
    private final CurrentUserService currentUserService;

    public FixedAssetEntriesService(JdbcTemplate jdbcTemplate, TenantContext tenantContext,
                                      FiscalYearGuardService fiscalGuard,
                                      CurrentUserService currentUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.fiscalGuard = fiscalGuard;
        this.currentUserService = currentUserService;
    }

    /** Lista el inmovilizado de la empresa actual para la UI. */
    public List<java.util.Map<String, Object>> listAssets(boolean includeInactive) {
        String sql = """
                SELECT id, code, name, category, accounting_account_id,
                       acquisition_date, acquisition_cost, residual_value,
                       useful_life_years, depreciation_method,
                       in_service_date, disposed_at, supplier_name,
                       invoice_reference, active
                  FROM fixed_assets
                 WHERE company_id = ?"""
                + (includeInactive ? "" : " AND active = TRUE")
                + " ORDER BY code";
        return jdbcTemplate.query(sql, (rs, n) -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("code", rs.getString("code"));
            m.put("name", rs.getString("name"));
            m.put("category", rs.getString("category"));
            m.put("accountingAccountId", rs.getString("accounting_account_id"));
            m.put("acquisitionDate", rs.getDate("acquisition_date") == null
                    ? null : rs.getDate("acquisition_date").toLocalDate().toString());
            m.put("acquisitionCost", rs.getBigDecimal("acquisition_cost"));
            m.put("residualValue", rs.getBigDecimal("residual_value"));
            m.put("usefulLifeYears", rs.getBigDecimal("useful_life_years"));
            m.put("depreciationMethod", rs.getString("depreciation_method"));
            m.put("supplierName", rs.getString("supplier_name"));
            m.put("invoiceReference", rs.getString("invoice_reference"));
            m.put("active", rs.getBoolean("active"));
            return m;
        }, tenantContext.getCurrentCompanyId());
    }

    /** Genera asiento de adquisición del inmovilizado. */
    @Transactional
    public String createAcquisitionEntry(String fixedAssetId, BigDecimal vatAmount,
                                           String paymentAccountId) {
        AssetSnapshot a = loadAsset(fixedAssetId);
        fiscalGuard.requireOpenForDate(a.acquisitionDate, "registrar alta inmovilizado");

        String companyId = tenantContext.getCurrentCompanyId();
        String acc21x = a.accountingAccountId != null ? a.accountingAccountId
                : findCategoryAccount(companyId, a.category);
        if (acc21x == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se encontró cuenta del grupo 2 para categoría " + a.category);
        }
        String acc472 = findAccountByPrefix(companyId, "472");
        String payAcc = paymentAccountId != null && !paymentAccountId.isBlank()
                ? paymentAccountId
                : findAccountByPrefix(companyId, "523");
        if (payAcc == null) payAcc = findAccountByPrefix(companyId, "572");

        if (acc21x == null || payAcc == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Faltan cuentas para el asiento de inmovilizado.");
        }

        BigDecimal base = a.acquisitionCost;
        BigDecimal vat = vatAmount == null ? BigDecimal.ZERO : vatAmount;
        BigDecimal total = base.add(vat);

        String fiscalYearId = findFiscalYearId(companyId, a.acquisitionDate);
        if (fiscalYearId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No hay ejercicio fiscal para " + a.acquisitionDate);
        }
        int entryNumber = nextEntryNumber(companyId, fiscalYearId);
        String entryId = UUID.randomUUID().toString();
        String userId = safeUserId();

        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'ASSET_ACQUISITION', ?, 'DRAFT', FALSE, TRUE, ?)
                """,
                entryId, companyId, fiscalYearId, entryNumber,
                Date.valueOf(a.acquisitionDate),
                truncate("Alta inmovilizado " + a.code + " - " + a.name, 240),
                fixedAssetId, userId);
        insertLine(entryId, acc21x, "Alta " + a.name, base, BigDecimal.ZERO);
        if (vat.signum() > 0 && acc472 != null) {
            insertLine(entryId, acc472, "IVA soportado inmovilizado", vat, BigDecimal.ZERO);
        } else {
            total = base; // sin IVA → total a pagar = base
        }
        insertLine(entryId, payAcc, "Pdte. pago/financiación", BigDecimal.ZERO, total);
        return entryId;
    }

    /**
     * Genera asiento de amortización para un periodo (mes o año).
     * Llamar tras ejecutar el cálculo del FixedAssetService que persiste
     * en fixed_asset_depreciations. Esta función crea el asiento contable.
     */
    @Transactional
    public String createDepreciationEntry(String assetId, int periodYear, Integer periodMonth) {
        String companyId = tenantContext.getCurrentCompanyId();
        AssetSnapshot a = loadAsset(assetId);

        // Suma de amortización del periodo en fixed_asset_depreciations.
        BigDecimal amount;
        if (periodMonth == null) {
            Number n = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(depreciation_amount), 0)
                      FROM fixed_asset_depreciations
                     WHERE company_id = ? AND asset_id = ? AND period_year = ?
                    """, Number.class, companyId, assetId, periodYear);
            amount = n == null ? BigDecimal.ZERO : new BigDecimal(n.toString());
        } else {
            Number n = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(depreciation_amount), 0)
                      FROM fixed_asset_depreciations
                     WHERE company_id = ? AND asset_id = ?
                       AND period_year = ? AND period_month = ?
                    """, Number.class, companyId, assetId, periodYear, periodMonth);
            amount = n == null ? BigDecimal.ZERO : new BigDecimal(n.toString());
        }
        if (amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No hay amortización calculada para ese periodo.");
        }

        LocalDate entryDate = periodMonth == null
                ? LocalDate.of(periodYear, Month.DECEMBER, 31)
                : LocalDate.of(periodYear, periodMonth, 1).withDayOfMonth(
                        LocalDate.of(periodYear, periodMonth, 1).lengthOfMonth());
        fiscalGuard.requireOpenForDate(entryDate, "dotar amortización");

        // 681 prefijo + 281 acumulada (mismo dígito que la 21x del bien).
        String acc681 = findAccountByPrefix(companyId, "681");
        String acc281 = findAccountByPrefix(companyId, "281");
        if (acc681 == null || acc281 == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Faltan cuentas 681 / 281.");
        }

        String fiscalYearId = findFiscalYearId(companyId, entryDate);
        if (fiscalYearId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No hay ejercicio fiscal para " + entryDate);
        }
        int entryNumber = nextEntryNumber(companyId, fiscalYearId);
        String entryId = UUID.randomUUID().toString();
        String userId = safeUserId();

        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'ASSET_DEPRECIATION', ?, 'POSTED', FALSE, FALSE, ?)
                """,
                entryId, companyId, fiscalYearId, entryNumber,
                Date.valueOf(entryDate),
                truncate("Amortización " + a.code + " " + periodYear
                        + (periodMonth == null ? "" : "/" + periodMonth), 240),
                assetId, userId);
        insertLine(entryId, acc681, "Dotación " + a.name, amount, BigDecimal.ZERO);
        insertLine(entryId, acc281, "Amortización acumulada", BigDecimal.ZERO, amount);

        // Marcar las filas como POSTED.
        if (periodMonth == null) {
            jdbcTemplate.update("""
                    UPDATE fixed_asset_depreciations
                       SET status = 'POSTED', journal_entry_id = ?
                     WHERE asset_id = ? AND period_year = ? AND company_id = ?
                    """, entryId, assetId, periodYear, companyId);
        } else {
            jdbcTemplate.update("""
                    UPDATE fixed_asset_depreciations
                       SET status = 'POSTED', journal_entry_id = ?
                     WHERE asset_id = ? AND period_year = ? AND period_month = ? AND company_id = ?
                    """, entryId, assetId, periodYear, periodMonth, companyId);
        }
        return entryId;
    }

    /** Asiento de baja con o sin venta. */
    @Transactional
    public String createDisposalEntry(String assetId, LocalDate disposalDate,
                                        BigDecimal saleAmount, String bankAccountId) {
        AssetSnapshot a = loadAsset(assetId);
        fiscalGuard.requireOpenForDate(disposalDate, "dar de baja inmovilizado");
        String companyId = tenantContext.getCurrentCompanyId();

        // Acumulada hasta la fecha.
        Number nAcc = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(depreciation_amount), 0)
                  FROM fixed_asset_depreciations
                 WHERE asset_id = ? AND company_id = ?
                """, Number.class, assetId, companyId);
        BigDecimal accumulated = nAcc == null ? BigDecimal.ZERO : new BigDecimal(nAcc.toString());
        BigDecimal netValue = a.acquisitionCost.subtract(accumulated);
        BigDecimal sale = saleAmount == null ? BigDecimal.ZERO : saleAmount;
        BigDecimal difference = sale.subtract(netValue);

        String acc21x = a.accountingAccountId != null ? a.accountingAccountId
                : findCategoryAccount(companyId, a.category);
        String acc281 = findAccountByPrefix(companyId, "281");
        String accBank = bankAccountId == null ? findAccountByPrefix(companyId, "572") : bankAccountId;
        String acc671 = findAccountByPrefix(companyId, "671");
        String acc771 = findAccountByPrefix(companyId, "771");
        if (acc21x == null || acc281 == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Faltan cuentas 21x/281.");
        }

        String fiscalYearId = findFiscalYearId(companyId, disposalDate);
        int entryNumber = nextEntryNumber(companyId, fiscalYearId);
        String entryId = UUID.randomUUID().toString();
        String userId = safeUserId();

        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'ASSET_DISPOSAL', ?, 'DRAFT', FALSE, TRUE, ?)
                """,
                entryId, companyId, fiscalYearId, entryNumber,
                Date.valueOf(disposalDate),
                truncate("Baja " + a.code + " - " + a.name, 240),
                assetId, userId);

        insertLine(entryId, acc281, "Amortización acumulada", accumulated, BigDecimal.ZERO);
        if (sale.signum() > 0 && accBank != null) {
            insertLine(entryId, accBank, "Venta " + a.name, sale, BigDecimal.ZERO);
        }
        if (difference.signum() < 0 && acc671 != null) {
            insertLine(entryId, acc671, "Pérdida por venta", difference.negate(), BigDecimal.ZERO);
        }
        insertLine(entryId, acc21x, "Baja " + a.name, BigDecimal.ZERO, a.acquisitionCost);
        if (difference.signum() > 0 && acc771 != null) {
            insertLine(entryId, acc771, "Beneficio por venta", BigDecimal.ZERO, difference);
        }

        jdbcTemplate.update("""
                UPDATE fixed_assets
                   SET disposed_at = ?, disposal_value = ?, active = FALSE
                 WHERE id = ? AND company_id = ?
                """, Date.valueOf(disposalDate), sale, assetId, companyId);
        return entryId;
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private AssetSnapshot loadAsset(String id) {
        List<AssetSnapshot> rows = jdbcTemplate.query("""
                SELECT id, code, name, category, accounting_account_id,
                       acquisition_date, acquisition_cost
                  FROM fixed_assets
                 WHERE id = ? AND company_id = ?
                """,
                (rs, n) -> {
                    AssetSnapshot a = new AssetSnapshot();
                    a.id = rs.getString("id");
                    a.code = rs.getString("code");
                    a.name = rs.getString("name");
                    a.category = rs.getString("category");
                    a.accountingAccountId = rs.getString("accounting_account_id");
                    a.acquisitionDate = rs.getDate("acquisition_date").toLocalDate();
                    a.acquisitionCost = rs.getBigDecimal("acquisition_cost");
                    return a;
                }, id, tenantContext.getCurrentCompanyId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Inmovilizado no encontrado");
        return rows.get(0);
    }

    private String findCategoryAccount(String companyId, String category) {
        // Mapeo PGC: cada categoría a un prefijo 21x estándar.
        String prefix = switch (category) {
            case "BUILDING" -> "211";
            case "LAND" -> "210";
            case "MACHINERY" -> "213";
            case "VEHICLE" -> "218";
            case "IT_EQUIPMENT" -> "217";
            case "OFFICE_FURNITURE" -> "216";
            case "SOFTWARE" -> "206";
            case "INTANGIBLE" -> "20";
            default -> "21";
        };
        return findAccountByPrefix(companyId, prefix);
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

    private int nextEntryNumber(String companyId, String fiscalYearId) {
        Integer max = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0) FROM journal_entries
                 WHERE company_id = ? AND fiscal_year_id = ?
                """, Integer.class, companyId, fiscalYearId);
        return (max == null ? 0 : max) + 1;
    }

    private void insertLine(String entryId, String accountId, String desc,
                              BigDecimal debit, BigDecimal credit) {
        jdbcTemplate.update("""
                INSERT INTO journal_entry_lines (
                    id, journal_entry_id, account_id, description, debit, credit
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entryId, accountId,
                truncate(desc, 240), debit.setScale(2, RoundingMode.HALF_UP),
                credit.setScale(2, RoundingMode.HALF_UP));
    }

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static class AssetSnapshot {
        String id; String code; String name; String category;
        String accountingAccountId;
        LocalDate acquisitionDate;
        BigDecimal acquisitionCost;
    }
}
