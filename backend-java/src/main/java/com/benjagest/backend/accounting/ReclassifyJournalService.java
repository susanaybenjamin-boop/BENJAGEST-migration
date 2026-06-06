package com.benjagest.backend.accounting;

import com.benjagest.backend.tenant.TenantContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recorre asientos DRAFT (sin validar) y vuelve a sugerir su cuenta 6xx/7xx
 * aplicando el chain completo: histórico → classifier → fallback.
 *
 * <p>Port de {@code revisarCuentasAsientos} (línea 2081 de CONTENDO
 * {@code contabilidadService.js}). Caso de uso: el asesor activó un nuevo
 * proveedor con regla histórica, importó facturas viejas que quedaron todas
 * a 600 genérico — un clic reclassifica el lote sin tener que abrir asiento
 * por asiento.
 *
 * <p>Estrategia conservadora:
 * <ul>
 *   <li>Solo toca asientos en estado <b>DRAFT</b> (sin validar).</li>
 *   <li>Solo recambia la línea 6xx/7xx (la cuenta de gasto/ingreso).
 *       No toca terceros (400/430) — eso se hace en otro slice.</li>
 *   <li>Solo recambia si la cuenta actual es la <b>genérica</b> (600/700/629)
 *       — si el asesor ya editó la cuenta a algo específico, se respeta.</li>
 *   <li>Devuelve resumen con conteos para que la UI muestre lo hecho.</li>
 * </ul>
 */
@Service
public class ReclassifyJournalService {

    private static final List<String> GENERIC_EXPENSE_CODES = List.of("600", "629");
    private static final List<String> GENERIC_INCOME_CODES = List.of("700", "759");

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final AccountingLearningService learning;
    private final ExpenseAccountClassifierService expenseClassifier;
    private final IncomeAccountClassifierService incomeClassifier;

    public ReclassifyJournalService(JdbcTemplate jdbcTemplate,
                                      TenantContext tenantContext,
                                      AccountingLearningService learning,
                                      ExpenseAccountClassifierService expenseClassifier,
                                      IncomeAccountClassifierService incomeClassifier) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.learning = learning;
        this.expenseClassifier = expenseClassifier;
        this.incomeClassifier = incomeClassifier;
    }

    public record ReclassifyResult(int entriesScanned, int linesUpdated, List<String> notes) {}

    @Transactional
    public ReclassifyResult reclassifyDrafts() {
        String companyId = tenantContext.getCurrentCompanyId();
        List<String> notes = new ArrayList<>();
        int linesUpdated = 0;

        // 1. Recoger asientos DRAFT con factura de compra/venta como source.
        List<Map<String, Object>> drafts = jdbcTemplate.queryForList("""
                SELECT e.id, e.source_type, e.source_id
                  FROM journal_entries e
                 WHERE e.company_id = ?
                   AND e.status = 'DRAFT'
                   AND e.source_type IN ('PURCHASE_INVOICE', 'SALES_INVOICE')
                 ORDER BY e.entry_date
                """, companyId);

        for (Map<String, Object> row : drafts) {
            String entryId = (String) row.get("id");
            String srcType = (String) row.get("source_type");
            String srcId = (String) row.get("source_id");
            if ("PURCHASE_INVOICE".equals(srcType)) {
                linesUpdated += reclassifyPurchaseEntry(companyId, entryId, srcId, notes);
            } else if ("SALES_INVOICE".equals(srcType)) {
                linesUpdated += reclassifySalesEntry(companyId, entryId, srcId, notes);
            }
        }
        return new ReclassifyResult(drafts.size(), linesUpdated, notes);
    }

    private int reclassifyPurchaseEntry(String companyId, String entryId, String purchaseId, List<String> notes) {
        Map<String, Object> p;
        try {
            p = jdbcTemplate.queryForMap("""
                    SELECT supplier_nif, supplier_name, invoice_number, notes
                      FROM purchase_invoices
                     WHERE id = ? AND company_id = ?
                    """, purchaseId, companyId);
        } catch (Exception ex) {
            return 0;
        }

        // Buscar líneas 6xx que sean cuenta genérica.
        List<Map<String, Object>> lines = jdbcTemplate.queryForList("""
                SELECT l.id AS line_id, l.account_id, a.code
                  FROM journal_entry_lines l
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE l.journal_entry_id = ?
                   AND a.code LIKE '6%'
                   AND l.debit > 0
                """, entryId);

        int updated = 0;
        for (Map<String, Object> ln : lines) {
            String currentCode = (String) ln.get("code");
            if (!isGenericExpense(currentCode)) continue;

            String newAccountId = chooseExpenseAccount(companyId,
                    (String) p.get("supplier_nif"),
                    (String) p.get("supplier_name"),
                    (String) p.get("invoice_number"),
                    (String) p.get("notes"));
            if (newAccountId == null) continue;
            String lineId = (String) ln.get("line_id");
            String oldId = (String) ln.get("account_id");
            if (newAccountId.equals(oldId)) continue;

            jdbcTemplate.update(
                    "UPDATE journal_entry_lines SET account_id = ? WHERE id = ?",
                    newAccountId, lineId);
            updated++;
            notes.add("entry=" + entryId + " line=" + lineId + " " + currentCode + " → reclasificada");
        }
        return updated;
    }

    private int reclassifySalesEntry(String companyId, String entryId, String invoiceId, List<String> notes) {
        Map<String, Object> s;
        try {
            s = jdbcTemplate.queryForMap("""
                    SELECT customer_id, invoice_number, notes
                      FROM sales_invoices
                     WHERE id = ? AND company_id = ?
                    """, invoiceId, companyId);
        } catch (Exception ex) {
            return 0;
        }
        String customerNif = resolveCustomerNif(companyId, (String) s.get("customer_id"));
        String customerName = resolveCustomerName(companyId, (String) s.get("customer_id"));

        List<Map<String, Object>> lines = jdbcTemplate.queryForList("""
                SELECT l.id AS line_id, l.account_id, a.code
                  FROM journal_entry_lines l
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE l.journal_entry_id = ?
                   AND a.code LIKE '7%'
                   AND l.credit > 0
                """, entryId);

        int updated = 0;
        for (Map<String, Object> ln : lines) {
            String currentCode = (String) ln.get("code");
            if (!isGenericIncome(currentCode)) continue;

            String newAccountId = chooseIncomeAccount(companyId,
                    customerNif, customerName,
                    (String) s.get("invoice_number"),
                    (String) s.get("notes"));
            if (newAccountId == null) continue;
            String lineId = (String) ln.get("line_id");
            String oldId = (String) ln.get("account_id");
            if (newAccountId.equals(oldId)) continue;

            jdbcTemplate.update(
                    "UPDATE journal_entry_lines SET account_id = ? WHERE id = ?",
                    newAccountId, lineId);
            updated++;
            notes.add("entry=" + entryId + " line=" + lineId + " " + currentCode + " → reclasificada");
        }
        return updated;
    }

    // ---- cuenta sugerida (chain idéntico al de los services de invoices) --

    private String chooseExpenseAccount(String companyId, String nif, String name,
                                          String invoiceNumber, String notes) {
        Optional<AccountingLearningService.HistoricMatch> hist =
                learning.findHistoricExpenseAccountForSupplier(nif, name);
        if (hist.isPresent()) return hist.get().accountId();
        Optional<String> code = expenseClassifier.classify(
                (notes == null ? "" : notes) + " " + (invoiceNumber == null ? "" : invoiceNumber), name);
        if (code.isPresent()) {
            String id = findByCode(companyId, code.get());
            if (id != null) return id;
        }
        return null;
    }

    private String chooseIncomeAccount(String companyId, String nif, String name,
                                         String invoiceNumber, String notes) {
        Optional<AccountingLearningService.HistoricMatch> hist =
                learning.findHistoricIncomeAccountForCustomer(nif, name);
        if (hist.isPresent()) return hist.get().accountId();
        Optional<String> code = incomeClassifier.classify(
                (notes == null ? "" : notes) + " " + (invoiceNumber == null ? "" : invoiceNumber), name);
        if (code.isPresent()) {
            String id = findByCode(companyId, code.get());
            if (id != null) return id;
        }
        return null;
    }

    // ---- helpers ---------------------------------------------------------

    private boolean isGenericExpense(String code) {
        return code != null && GENERIC_EXPENSE_CODES.contains(code);
    }

    private boolean isGenericIncome(String code) {
        return code != null && GENERIC_INCOME_CODES.contains(code);
    }

    private String findByCode(String companyId, String code) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE AND code = ?
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, code);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String resolveCustomerNif(String companyId, String customerId) {
        if (customerId == null) return null;
        List<String> nifs = jdbcTemplate.query("""
                SELECT tax_identifier FROM customers WHERE id = ? AND company_id = ? LIMIT 1
                """, (rs, n) -> rs.getString("tax_identifier"), customerId, companyId);
        return nifs.isEmpty() ? null : nifs.get(0);
    }

    private String resolveCustomerName(String companyId, String customerId) {
        if (customerId == null) return null;
        List<String> names = jdbcTemplate.query("""
                SELECT legal_name FROM customers WHERE id = ? AND company_id = ? LIMIT 1
                """, (rs, n) -> rs.getString("legal_name"), customerId, companyId);
        return names.isEmpty() ? null : names.get(0);
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> emptyMap() { return new HashMap<>(); }
}
