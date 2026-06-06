package com.benjagest.backend.billing.invoices;

import com.benjagest.backend.accounting.AccountingLearningService;
import com.benjagest.backend.accounting.IncomeAccountClassifierService;
import com.benjagest.backend.accounting.TerceroAccountResolverService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea un asiento contable a partir de una factura emitida cuando la
 * empresa tiene el plan contable mínimo activo. Espejo de
 * PurchaseJournalEntryService pero con las cuentas de ventas.
 *
 * <p>Asiento de venta estándar (PGC español):
 *
 * <pre>
 *   Debe   430x (Clientes)              = total - retención
 *   Debe   473x (HP retenciones a/c)    = retención (si aplica)
 *                Haber  700x (Ventas)               = base_amount
 *                Haber  477x (HP IVA repercutido)    = vat_amount
 * </pre>
 *
 * <p>Si no encuentra alguna cuenta o no hay fiscal_year OPEN para la
 * fecha, devuelve {@code null} (la validación de la factura continúa
 * sin asiento). Mismo nivel de scope que purchases: el módulo
 * contable maduro (subcuentas analíticas por cliente, libros oficiales)
 * llegará en otro slice.
 *
 * <p>Decisión de scope (espejo de purchases):
 * <ul>
 *   <li>Cuentas por prefijo: 430/700/477/473. Tolera sub-cuentas
 *       (4300xx por cliente, 7000xx por producto, etc.) si existen.</li>
 *   <li>Si no hay retención, no se crea la línea 473.</li>
 *   <li>Anulación: marca status=VOIDED del asiento (igual que purchases).
 *       La inversión real con asiento espejo llega en YEAR-CLOSE.</li>
 * </ul>
 */
@Service
public class SalesJournalEntryService {

    private static final String SRC_TYPE = "SALES_INVOICE";

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final AccountingLearningService learning;
    private final TerceroAccountResolverService terceroResolver;
    private final IncomeAccountClassifierService classifier;

    public SalesJournalEntryService(JdbcTemplate jdbcTemplate,
                                      TenantContext tenantContext,
                                      AccountingLearningService learning,
                                      TerceroAccountResolverService terceroResolver,
                                      IncomeAccountClassifierService classifier) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.learning = learning;
        this.terceroResolver = terceroResolver;
        this.classifier = classifier;
    }

    /**
     * Intenta crear un asiento para la factura validada. Devuelve el
     * id del asiento o {@code null} si no fue posible. Nunca lanza:
     * el caller (SalesInvoiceService.validateInternal) decide qué
     * hacer cuando devuelve null — típicamente loguea y continúa,
     * porque la validación legal de la factura es independiente de la
     * generación del asiento.
     */
    @Transactional
    public String createForSales(SalesInvoice invoice, String userId) {
        if (invoice.invoiceDate() == null
                || invoice.subtotal() == null
                || invoice.vatTotal() == null
                || invoice.total() == null) {
            return null;
        }
        String companyId = tenantContext.getCurrentCompanyId();

        String fiscalYearId = findOpenFiscalYearId(companyId, invoice.invoiceDate());
        if (fiscalYearId == null) return null;

        // Cliente 430 → sub-cuenta de tercero (port CONTENDO getOrCreateCuentaTercero)
        // Resolvemos NIF si está disponible vía customers.
        String customerNif = resolveCustomerNif(invoice.customerId());
        TerceroAccountResolverService.ResolvedAccount customerAcc =
                terceroResolver.getOrCreateForCustomer(customerNif, invoice.customerLegalName());
        String acc430 = customerAcc.accountId();

        // 7xx — cuenta de ingreso en cascada:
        //   1. regla aprendida (cliente/keyword)
        //   2. histórico del cliente (cuenta 7xx más usada)
        //   3. classifier por descripción
        //   4. fallback 700 genérico
        Optional<AccountingLearningService.AccountProposal> proposal =
                learning.proposeIncomeAccount(
                        customerNif, invoice.customerLegalName(),
                        invoice.invoiceNumber(), invoice.total());
        String acc7xx = proposal.map(AccountingLearningService.AccountProposal::accountId)
                .orElse(null);
        String proposedRuleId = proposal.map(AccountingLearningService.AccountProposal::ruleId)
                .orElse(null);
        java.math.BigDecimal proposedConfidence =
                proposal.map(AccountingLearningService.AccountProposal::confidence).orElse(null);

        if (acc7xx == null) {
            Optional<AccountingLearningService.HistoricMatch> historic =
                    learning.findHistoricIncomeAccountForCustomer(
                            customerNif, invoice.customerLegalName());
            if (historic.isPresent()) acc7xx = historic.get().accountId();
        }
        if (acc7xx == null) {
            String descForClassifier = safe(invoice.notes())
                    + " " + safe(invoice.invoiceNumber());
            Optional<String> code = classifier.classify(descForClassifier, invoice.customerLegalName());
            if (code.isPresent()) {
                String id = findAccountByCode(companyId, code.get());
                if (id != null) acc7xx = id;
            }
        }
        if (acc7xx == null) {
            acc7xx = findAccountByPrefix(companyId, "700");
        }

        String acc477 = findAccountByPrefix(companyId, "477"); // HP IVA repercutido
        if (acc430 == null || acc7xx == null || acc477 == null) {
            return null;
        }
        // 473 solo si hay retención.
        BigDecimal retention = invoice.retentionTotal() == null
                ? BigDecimal.ZERO : invoice.retentionTotal();
        String acc473 = null;
        if (retention.signum() > 0) {
            acc473 = findAccountByPrefix(companyId, "473"); // HP retenciones a cuenta
            if (acc473 == null) return null;
        }

        Integer maxEntryNumber = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0)
                  FROM journal_entries
                 WHERE company_id = ?
                   AND fiscal_year_id = ?
                """, Integer.class, companyId, fiscalYearId);
        int entryNumber = (maxEntryNumber == null ? 0 : maxEntryNumber) + 1;

        String entryId = UUID.randomUUID().toString();
        String concept = buildConcept(invoice);
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, proposed_confidence,
                    created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', FALSE, TRUE, ?, ?)
                """,
                entryId, companyId, fiscalYearId, entryNumber,
                Date.valueOf(invoice.invoiceDate()),
                concept, SRC_TYPE, invoice.id(),
                proposedConfidence, userId);

        // Descripción base — todas las líneas del asiento usan el mismo
        // texto (Fra. N - Cliente) para que el Libro Mayor se lea bien
        // sin abrir el asiento. El asesor puede editar línea por línea
        // en el editor; al cambiar de cuenta, su descripción se respeta.
        String baseDesc = buildLineDescription(invoice);

        // Debe 430: cliente cobra total menos retención
        BigDecimal clientDebit = invoice.total().subtract(retention);
        insertLine(entryId, acc430, baseDesc, clientDebit, BigDecimal.ZERO);

        // Debe 473: si hay retención IRPF
        if (acc473 != null) {
            insertLine(entryId, acc473, baseDesc + " (retención IRPF)",
                    retention, BigDecimal.ZERO);
        }

        // Haber 7xx: ventas (base imponible) — cuenta propuesta por el
        // aprendizaje o fallback 700.
        insertLine(entryId, acc7xx, baseDesc, BigDecimal.ZERO, invoice.subtotal());

        // Haber 477: IVA repercutido
        insertLine(entryId, acc477, baseDesc + " (IVA repercutido)",
                BigDecimal.ZERO, invoice.vatTotal());

        // Si la cuenta 7xx vino de regla aprendida, registramos la
        // propuesta para que el flujo de aceptación pueda reforzarla.
        if (proposedRuleId != null) {
            jdbcTemplate.update("""
                    INSERT INTO accounting_learning_events (
                        id, company_id, journal_entry_id, line_id, event_kind,
                        to_account_id, related_rule_id, actor_user_id
                    ) VALUES (?, ?, ?, NULL, 'AUTO_PROPOSED', ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), companyId, entryId,
                    acc7xx, proposedRuleId, userId);
        }

        return entryId;
    }

    /** Marca el asiento de una factura como VOIDED al anularla. */
    @Transactional
    public void reverseForSales(String invoiceId) {
        jdbcTemplate.update("""
                UPDATE journal_entries
                   SET status = 'VOIDED'
                 WHERE source_type = ?
                   AND source_id = ?
                   AND company_id = ?
                   AND status = 'DRAFT'
                """, SRC_TYPE, invoiceId, tenantContext.getCurrentCompanyId());
    }

    private String findOpenFiscalYearId(String companyId, LocalDate date) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id
                  FROM fiscal_years
                 WHERE company_id = ?
                   AND status = 'OPEN'
                   AND ? BETWEEN start_date AND end_date
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, Date.valueOf(date));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String findAccountByCode(String companyId, String code) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE AND code = ?
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, code);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Busca el NIF del cliente en la tabla customers — null si no existe. */
    private String resolveCustomerNif(String customerId) {
        if (customerId == null) return null;
        List<String> nifs = jdbcTemplate.query("""
                SELECT nif FROM customers
                 WHERE id = ? AND company_id = ?
                 LIMIT 1
                """, (rs, n) -> rs.getString("nif"),
                customerId, tenantContext.getCurrentCompanyId());
        return nifs.isEmpty() ? null : nifs.get(0);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private String findAccountByPrefix(String companyId, String prefix) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id
                  FROM accounting_accounts
                 WHERE company_id = ?
                   AND active = TRUE
                   AND code LIKE ?
                 ORDER BY LENGTH(code), code
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, prefix + "%");
        return ids.isEmpty() ? null : ids.get(0);
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

    /**
     * Descripción de línea: nº factura + cliente. Si el asesor edita una
     * línea concreta, su texto se conserva — al cambiar de cuenta solo
     * el accountId cambia, no la description (ver AccountingScreen).
     */
    private String buildLineDescription(SalesInvoice invoice) {
        StringBuilder sb = new StringBuilder();
        if (invoice.invoiceNumber() != null && !invoice.invoiceNumber().isBlank()) {
            sb.append("Fra. ").append(invoice.invoiceNumber()).append(' ');
        }
        if (invoice.customerLegalName() != null && !invoice.customerLegalName().isBlank()) {
            sb.append("- ").append(invoice.customerLegalName());
        }
        String s = sb.toString().trim();
        if (s.isEmpty()) s = "Venta";
        return s.length() > 240 ? s.substring(0, 240) : s;
    }

    private String buildConcept(SalesInvoice invoice) {
        StringBuilder sb = new StringBuilder("Venta ");
        if (invoice.invoiceNumber() != null) sb.append(invoice.invoiceNumber()).append(' ');
        if (invoice.customerLegalName() != null) sb.append("a ").append(invoice.customerLegalName());
        return sb.toString().trim();
    }

}
