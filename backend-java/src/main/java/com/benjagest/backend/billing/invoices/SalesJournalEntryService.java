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
    private final com.benjagest.backend.accounting.FiscalYearGuardService fiscalGuard;

    public SalesJournalEntryService(JdbcTemplate jdbcTemplate,
                                      TenantContext tenantContext,
                                      AccountingLearningService learning,
                                      TerceroAccountResolverService terceroResolver,
                                      IncomeAccountClassifierService classifier,
                                      com.benjagest.backend.accounting.FiscalYearGuardService fiscalGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.learning = learning;
        this.terceroResolver = terceroResolver;
        this.classifier = classifier;
        this.fiscalGuard = fiscalGuard;
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

        // entry_number = NULL en DRAFT. Se asigna al validar (POSTED).
        String entryId = UUID.randomUUID().toString();
        String concept = buildConcept(invoice);
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, proposed_confidence,
                    created_by
                ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 'DRAFT', FALSE, TRUE, ?, ?)
                """,
                entryId, companyId, fiscalYearId,
                Date.valueOf(invoice.invoiceDate()),
                concept, SRC_TYPE, invoice.id(),
                proposedConfidence, userId);

        // Descripciones específicas por cuenta — el Libro Mayor de cada
        // cuenta debe leerse sin tener que abrir el asiento.
        //   • 4300xxx (cliente)  → nombre del cliente
        //   • 473    (retención) → "Retención IRPF — cliente"
        //   • 7xx    (ingreso)   → concept de la factura (si existe)
        //                           o nombre de la cuenta de ingreso
        //   • 477    (IVA rep.)  → "IVA repercutido XX% — cliente"
        String clientDesc = invoice.customerLegalName() != null
                && !invoice.customerLegalName().isBlank()
                ? invoice.customerLegalName() : "Cliente";
        String incomeDesc = buildIncomeLineDescription(invoice, acc7xx, companyId);
        String retentionDesc = "Retención IRPF — " + clientDesc;

        // Debe 430: cliente cobra total menos retención
        BigDecimal clientDebit = invoice.total().subtract(retention);
        insertLine(entryId, acc430, clientDesc, clientDebit, BigDecimal.ZERO);

        // Debe 473: si hay retención IRPF
        if (acc473 != null) {
            insertLine(entryId, acc473, retentionDesc, retention, BigDecimal.ZERO);
        }

        // Haber 7xx (base) + Haber 477 (IVA) DESGLOSADOS por tipo de IVA, a
        // partir de las líneas de la factura. Así una factura con 10% y 21%
        // genera una base y una cuota por cada tipo, y el 303/390 puede leer
        // las bases por tipo desde la contabilidad.
        insertSalesVatLines(entryId, invoice, acc7xx, acc477, incomeDesc, clientDesc);

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

    /**
     * Borra físicamente el asiento de una factura de venta cuando esta se
     * elimina. Mismo trato que purchases: hueco en la numeración del
     * Diario en lugar de VOIDED. Borra cascada (eventos → líneas → asiento).
     */
    @Transactional
    public void reverseForSales(String invoiceId) {
        String companyId = tenantContext.getCurrentCompanyId();
        // 1) recoger los ids de los asientos vinculados a esta factura.
        List<String> entryIds = jdbcTemplate.query("""
                SELECT id FROM journal_entries
                 WHERE source_type = ? AND source_id = ? AND company_id = ?
                """, (rs, n) -> rs.getString("id"),
                SRC_TYPE, invoiceId, companyId);
        // LOCK (2026-07-07): un asiento de un ejercicio LOCKED/CLOSED no
        // se borra — sus libros están cerrados. Se comprueba ANTES de
        // tocar nada para que la operación sea todo-o-nada. El caller
        // voidValidated absorbe este 409 y conserva el asiento (la
        // rectificativa de hoy contrarresta en el ejercicio corriente).
        for (String entryId : entryIds) {
            java.time.LocalDate entryDate = jdbcTemplate.queryForObject("""
                    SELECT entry_date FROM journal_entries WHERE id = ?
                    """, java.time.LocalDate.class, entryId);
            fiscalGuard.requireOpenForDate(companyId, entryDate,
                    "borrar el asiento de esta factura");
        }
        for (String entryId : entryIds) {
            jdbcTemplate.update("""
                    DELETE FROM accounting_learning_events
                     WHERE journal_entry_id = ? AND company_id = ?
                    """, entryId, companyId);
            jdbcTemplate.update("""
                    DELETE FROM journal_entry_lines
                     WHERE journal_entry_id = ?
                    """, entryId);
            jdbcTemplate.update("""
                    DELETE FROM journal_entries
                     WHERE id = ? AND company_id = ?
                    """, entryId, companyId);
        }
    }

    /**
     * Regenera el asiento de una factura ya validada para aplicar el desglose
     * de IVA por tipo, SIN cambiar el estado (POSTED) ni el número del asiento:
     * reescribe solo sus líneas en su sitio. Reutiliza las cuentas que ya
     * usaba el asiento (preserva la cuenta de ingreso elegida en su día). Si la
     * factura no tenía asiento, lo crea de cero.
     */
    @Transactional
    public String regenerateForSales(SalesInvoice invoice, String userId) {
        String companyId = tenantContext.getCurrentCompanyId();
        // LOCK (2026-07-07): reescribir las líneas de un asiento de un
        // ejercicio LOCKED/CLOSED altera libros cerrados.
        fiscalGuard.requireOpenForDate(companyId, invoice.invoiceDate(),
                "regenerar el asiento de esta factura");
        List<String> entryIds = jdbcTemplate.query("""
                SELECT id FROM journal_entries
                 WHERE source_type = ? AND source_id = ? AND company_id = ?
                 ORDER BY created_at LIMIT 1
                """, (rs, n) -> rs.getString("id"), SRC_TYPE, invoice.id(), companyId);
        if (entryIds.isEmpty()) {
            return createForSales(invoice, userId);
        }
        String entryId = entryIds.get(0);

        // Reutilizar las cuentas ya presentes en el asiento (conserva la 7xx).
        String acc430 = findLineAccountByPrefix(entryId, "430");
        String acc7xx = findLineAccountByPrefix(entryId, "7");
        String acc477 = findLineAccountByPrefix(entryId, "477");
        String acc473 = findLineAccountByPrefix(entryId, "473");
        if (acc430 == null) acc430 = findAccountByPrefix(companyId, "430");
        if (acc7xx == null) acc7xx = findAccountByPrefix(companyId, "700");
        if (acc477 == null) acc477 = findAccountByPrefix(companyId, "477");
        if (acc430 == null || acc7xx == null || acc477 == null) {
            // sin PGC suficiente no tocamos nada.
            return entryId;
        }
        BigDecimal retention = invoice.retentionTotal() == null
                ? BigDecimal.ZERO : invoice.retentionTotal();
        if (retention.signum() > 0 && acc473 == null) {
            acc473 = findAccountByPrefix(companyId, "473");
        }

        // Borrar líneas existentes (conservamos la cabecera: estado, número, fecha).
        jdbcTemplate.update("""
                DELETE FROM accounting_learning_events
                 WHERE journal_entry_id = ? AND company_id = ?
                """, entryId, companyId);
        jdbcTemplate.update("DELETE FROM journal_entry_lines WHERE journal_entry_id = ?", entryId);

        String clientDesc = invoice.customerLegalName() != null
                && !invoice.customerLegalName().isBlank()
                ? invoice.customerLegalName() : "Cliente";
        String incomeDesc = buildIncomeLineDescription(invoice, acc7xx, companyId);
        String retentionDesc = "Retención IRPF — " + clientDesc;

        insertLine(entryId, acc430, clientDesc, invoice.total().subtract(retention), BigDecimal.ZERO);
        if (acc473 != null && retention.signum() > 0) {
            insertLine(entryId, acc473, retentionDesc, retention, BigDecimal.ZERO);
        }
        insertSalesVatLines(entryId, invoice, acc7xx, acc477, incomeDesc, clientDesc);
        return entryId;
    }

    /** Id de la primera cuenta de una línea del asiento cuyo código empieza por {@code prefix}. */
    private String findLineAccountByPrefix(String entryId, String prefix) {
        List<String> ids = jdbcTemplate.query("""
                SELECT l.account_id
                  FROM journal_entry_lines l
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE l.journal_entry_id = ? AND a.code LIKE ?
                 ORDER BY LENGTH(a.code), a.code
                 LIMIT 1
                """, (rs, n) -> rs.getString("account_id"), entryId, prefix + "%");
        return ids.isEmpty() ? null : ids.get(0);
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

    /**
     * Busca el NIF del cliente en la tabla customers — null si no existe.
     * En BENJAGEST la columna se llama {@code tax_identifier} (no {@code nif}
     * como en CONTENDO).
     */
    private String resolveCustomerNif(String customerId) {
        if (customerId == null) return null;
        List<String> nifs = jdbcTemplate.query("""
                SELECT tax_identifier FROM customers
                 WHERE id = ? AND company_id = ?
                 LIMIT 1
                """, (rs, n) -> rs.getString("tax_identifier"),
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
        insertLine(entryId, accountId, description, debit, credit, null);
    }

    private void insertLine(String entryId, String accountId, String description,
                              BigDecimal debit, BigDecimal credit, BigDecimal vatRate) {
        jdbcTemplate.update("""
                INSERT INTO journal_entry_lines (
                    id, journal_entry_id, account_id, description,
                    debit, credit, vat_rate
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entryId, accountId,
                description, debit, credit, vatRate);
    }

    /**
     * Inserta las líneas de base (7xx) e IVA repercutido (477) DESGLOSADAS por
     * tipo, a partir de las líneas de la factura. Cada par (base, cuota) lleva
     * su {@code vat_rate}. Ajusta el redondeo del último tramo para que la suma
     * de bases cuadre con {@code subtotal} y la de cuotas con {@code vatTotal}
     * de la cabecera (el asiento debe cuadrar al céntimo).
     */
    private void insertSalesVatLines(String entryId, SalesInvoice invoice,
                                     String acc7xx, String acc477,
                                     String incomeDesc, String clientDesc) {
        // Agrupar bases y cuotas por tipo (orden ascendente por tipo).
        java.util.TreeMap<BigDecimal, BigDecimal[]> byRate = new java.util.TreeMap<>();
        if (invoice.lines() != null) {
            for (InvoiceLine l : invoice.lines()) {
                BigDecimal rate = l.vatPercent() == null ? BigDecimal.ZERO : l.vatPercent();
                BigDecimal base = l.lineSubtotal() == null ? BigDecimal.ZERO : l.lineSubtotal();
                BigDecimal cuota = base.multiply(rate)
                        .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
                byRate.merge(rate, new BigDecimal[]{base, cuota},
                        (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])});
            }
        }
        // Si la factura no tuviera líneas (caso defensivo), un solo tramo con
        // los totales de cabecera para no perder el asiento.
        if (byRate.isEmpty()) {
            insertLine(entryId, acc7xx, incomeDesc, BigDecimal.ZERO, invoice.subtotal(), null);
            insertLine(entryId, acc477, "IVA repercutido — " + clientDesc,
                    BigDecimal.ZERO, invoice.vatTotal(), null);
            return;
        }
        // Ajuste de cuadre: la base/cuota del tramo de mayor base absorbe la
        // diferencia de redondeo frente a la cabecera.
        BigDecimal sumBase = BigDecimal.ZERO, sumCuota = BigDecimal.ZERO;
        BigDecimal topRate = byRate.firstKey();
        for (var e : byRate.entrySet()) {
            sumBase = sumBase.add(e.getValue()[0]);
            sumCuota = sumCuota.add(e.getValue()[1]);
            if (e.getValue()[0].compareTo(byRate.get(topRate)[0]) > 0) topRate = e.getKey();
        }
        BigDecimal diffBase = (invoice.subtotal() == null ? sumBase : invoice.subtotal()).subtract(sumBase);
        BigDecimal diffCuota = (invoice.vatTotal() == null ? sumCuota : invoice.vatTotal()).subtract(sumCuota);
        byRate.get(topRate)[0] = byRate.get(topRate)[0].add(diffBase);
        byRate.get(topRate)[1] = byRate.get(topRate)[1].add(diffCuota);

        for (var e : byRate.entrySet()) {
            BigDecimal rate = e.getKey();
            BigDecimal base = e.getValue()[0];
            BigDecimal cuota = e.getValue()[1];
            String rateLabel = rate.stripTrailingZeros().toPlainString();
            insertLine(entryId, acc7xx,
                    incomeDesc + (rate.signum() > 0 ? " (" + rateLabel + "% IVA)" : ""),
                    BigDecimal.ZERO, base, rate);
            // 477 solo si hay tipo (>0); las líneas exentas (0%) no generan IVA.
            if (rate.signum() > 0) {
                insertLine(entryId, acc477,
                        "IVA repercutido " + rateLabel + "% — " + clientDesc,
                        BigDecimal.ZERO, cuota, rate);
            }
        }
    }

    /**
     * Descripción de la línea de ingreso (cuenta 7xx):
     * <ul>
     *   <li>Si la factura tiene {@code concept} → ese texto.</li>
     *   <li>Si no → nombre de la cuenta 7xx resuelta + cliente
     *       (p. ej. "Ventas de mercaderías — Marcos SL").</li>
     *   <li>Como último fallback → "Venta".</li>
     * </ul>
     */
    private String buildIncomeLineDescription(SalesInvoice invoice, String acc7xxId, String companyId) {
        if (invoice.concept() != null && !invoice.concept().isBlank()) {
            String c = invoice.concept().trim();
            return c.length() > 240 ? c.substring(0, 240) : c;
        }
        String accountName = jdbcTemplate.query("""
                SELECT name FROM accounting_accounts WHERE id = ? LIMIT 1
                """, (rs, n) -> rs.getString("name"), acc7xxId)
                .stream().findFirst().orElse(null);
        StringBuilder sb = new StringBuilder();
        if (accountName != null && !accountName.isBlank()) {
            sb.append(accountName);
            if (invoice.customerLegalName() != null && !invoice.customerLegalName().isBlank()) {
                sb.append(" — ").append(invoice.customerLegalName());
            }
        } else if (invoice.customerLegalName() != null && !invoice.customerLegalName().isBlank()) {
            sb.append("Venta ").append(invoice.customerLegalName());
        } else {
            sb.append("Venta");
        }
        String s = sb.toString();
        return s.length() > 240 ? s.substring(0, 240) : s;
    }

    private String buildConcept(SalesInvoice invoice) {
        StringBuilder sb = new StringBuilder("Venta ");
        if (invoice.invoiceNumber() != null) sb.append(invoice.invoiceNumber()).append(' ');
        if (invoice.customerLegalName() != null) sb.append("a ").append(invoice.customerLegalName());
        return sb.toString().trim();
    }

}
