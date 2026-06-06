package com.benjagest.backend.purchases;

import com.benjagest.backend.accounting.AccountingLearningService;
import com.benjagest.backend.accounting.ExpenseAccountClassifierService;
import com.benjagest.backend.accounting.TerceroAccountResolverService;
import com.benjagest.backend.tenant.TenantContext;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea un asiento contable a partir de una factura de compra cuando
 * la empresa tiene el plan contable mínimo activo.
 *
 * Asiento de compra estándar (PGC español):
 *
 *   Debe  600x (Compras) o 6xx por defecto       = base_amount
 *   Debe  472x (IVA soportado)                    = vat_amount
 *                Haber  400x (Proveedores)         = total_amount
 *
 * El service busca cuentas por prefijo (600/472/400) para tolerar
 * sub-cuentas analíticas (600001, 472001, 400015...). Si no encuentra
 * alguna o no hay fiscal_year OPEN para la fecha de la factura,
 * devuelve {@code null} (la factura se guarda sin asiento) — el
 * módulo contable maduro lo creará en otro slice.
 *
 * Decisión de scope: este service es deliberadamente simple para el
 * caso 80%. Asientos complejos (intracomunitarios con autorrepercusión,
 * prorrata IVA, retenciones IRPF a proveedores…) llegarán cuando se
 * cierre el módulo contable completo.
 */
@Service
public class PurchaseJournalEntryService {

    private static final String SRC_TYPE = "PURCHASE_INVOICE";

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final AccountingLearningService learning;
    private final TerceroAccountResolverService terceroResolver;
    private final ExpenseAccountClassifierService classifier;

    public PurchaseJournalEntryService(JdbcTemplate jdbcTemplate,
                                         TenantContext tenantContext,
                                         AccountingLearningService learning,
                                         TerceroAccountResolverService terceroResolver,
                                         ExpenseAccountClassifierService classifier) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.learning = learning;
        this.terceroResolver = terceroResolver;
        this.classifier = classifier;
    }

    /**
     * Intenta crear un asiento para la factura. Devuelve el id del
     * asiento o {@code null} si no fue posible (faltan cuentas o
     * fiscal_year). Nunca lanza excepción: el caller decide qué hacer
     * cuando devuelve null (típicamente loguear y persistir la factura
     * sin journal_entry_id).
     */
    @Transactional
    public String createForPurchase(PurchaseInvoice purchase, String userId) {
        if (purchase.invoiceDate() == null
                || purchase.baseAmount() == null
                || purchase.vatAmount() == null
                || purchase.totalAmount() == null) {
            return null;
        }
        String companyId = tenantContext.getCurrentCompanyId();

        // 1) Fiscal year OPEN para la fecha de la factura.
        String fiscalYearId = findOpenFiscalYearId(companyId, purchase.invoiceDate());
        if (fiscalYearId == null) return null;

        // 2) Resolución de cuentas en cascada (port de CONTENDO):
        //    a) cuenta 6xx — gasto:
        //       1. regla aprendida (NIF proveedor o keyword) → más fuerte
        //       2. histórico del proveedor (cuenta 6xx más usada en asientos
        //          previos del mismo NIF/nombre)
        //       3. classifier por descripción (regex de keywords PGC)
        //       4. fallback 600 genérico
        //    b) cuenta 400 — proveedor: sub-cuenta 4000xx por tercero
        //       (TerceroAccountResolver crea automáticamente si no existe).
        //    c) cuenta 472 — IVA soportado: prefijo genérico (no se
        //       analítica por tercero todavía).

        // (a) Gasto 6xx
        Optional<AccountingLearningService.AccountProposal> proposal =
                learning.proposeExpenseAccount(
                        purchase.supplierNif(), purchase.supplierName(),
                        purchase.invoiceNumber(), purchase.totalAmount());
        String acc6xx = proposal.map(AccountingLearningService.AccountProposal::accountId)
                .orElse(null);
        String proposedRuleId = proposal.map(AccountingLearningService.AccountProposal::ruleId)
                .orElse(null);
        java.math.BigDecimal proposedConfidence =
                proposal.map(AccountingLearningService.AccountProposal::confidence).orElse(null);

        // 2. histórico del proveedor (si no hay regla)
        if (acc6xx == null) {
            Optional<AccountingLearningService.HistoricMatch> historic =
                    learning.findHistoricExpenseAccountForSupplier(
                            purchase.supplierNif(), purchase.supplierName());
            if (historic.isPresent()) {
                acc6xx = historic.get().accountId();
            }
        }

        // 3. classifier por regex (keywords PGC español)
        if (acc6xx == null) {
            String descForClassifier = safe(purchase.notes())
                    + " " + safe(purchase.invoiceNumber());
            Optional<String> code = classifier.classify(descForClassifier, purchase.supplierName());
            if (code.isPresent()) {
                String id = findAccountByCode(companyId, code.get());
                if (id != null) acc6xx = id;
            }
        }

        // 4. fallback 600 genérico
        if (acc6xx == null) {
            acc6xx = findAccountByPrefix(companyId, "600");
        }

        // (b) Proveedor 400 → sub-cuenta de tercero
        TerceroAccountResolverService.ResolvedAccount supplierAcc =
                terceroResolver.getOrCreateForSupplier(
                        purchase.supplierNif(), purchase.supplierName());
        String acc400 = supplierAcc.accountId();

        // (c) IVA soportado 472 genérico
        String acc472 = findAccountByPrefix(companyId, "472");

        if (acc6xx == null || acc472 == null || acc400 == null) {
            return null;
        }

        // 3) Crear el asiento en DRAFT — entry_number = NULL.
        //    El número definitivo del Diario se asigna al VALIDAR (POSTED),
        //    no al crear el borrador. Así el orden del Diario refleja el
        //    orden de validación (que es lo que el asesor controla), no el
        //    orden de creación de las facturas. Si validas la #3 antes
        //    que la #1, en el Diario será la #1.
        //
        //    auto_proposed=TRUE indica que la cuenta de gasto vino de una
        //    regla aprendida (o del fallback 600). proposed_confidence
        //    guarda la fuerza de la regla para que la UI muestre un chip
        //    de confianza al asesor.
        String entryId = UUID.randomUUID().toString();
        String concept = buildConcept(purchase);
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, proposed_confidence,
                    created_by
                ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 'DRAFT', FALSE, TRUE, ?, ?)
                """,
                entryId, companyId, fiscalYearId,
                Date.valueOf(purchase.invoiceDate()),
                concept, SRC_TYPE, purchase.id(),
                proposedConfidence, userId);

        // 5) Líneas con descripción específica por tipo de cuenta — el
        //    asesor lee el Libro Mayor de cada cuenta sin tener que abrir
        //    el asiento, así que cada línea debe decir lo suyo:
        //    • 6xx (gasto)     → concepto de la factura (lo que el asesor
        //                        tecleó como "qué fue ese gasto"), o si no
        //                        tiene concepto, el nombre de la cuenta
        //                        resuelta como fallback.
        //    • 472 (IVA sopor.) → "IVA soportado XX% — proveedor"
        //    • 4000xxx (prov.)  → nombre del proveedor (limpio, sin "Fra. N")
        //
        //    Si el asesor edita una línea concreta, su texto se conserva al
        //    cambiar de cuenta — el editor del asiento lo respeta.
        String expenseDesc = buildExpenseLineDescription(purchase, acc6xx, companyId);
        String vatDesc = "IVA soportado " + safe(purchase.vatPercent()) + "%"
                + (purchase.supplierName() != null && !purchase.supplierName().isBlank()
                        ? " — " + purchase.supplierName() : "");
        String supplierDesc = purchase.supplierName() != null && !purchase.supplierName().isBlank()
                ? purchase.supplierName()
                : (purchase.supplierNif() != null ? purchase.supplierNif() : "Proveedor");

        insertLine(entryId, acc6xx, expenseDesc,
                purchase.baseAmount(), java.math.BigDecimal.ZERO);
        insertLine(entryId, acc472, vatDesc,
                purchase.vatAmount(), java.math.BigDecimal.ZERO);
        insertLine(entryId, acc400, supplierDesc,
                java.math.BigDecimal.ZERO, purchase.totalAmount());

        // 6) Si vino de regla aprendida, no la reforzamos todavía — eso
        //    ocurre cuando el asesor valida (POSTED). Lo único que dejamos
        //    es el rastro para que el flujo de aceptación sepa qué regla
        //    aplicar. Lo persistimos como evento AUTO_PROPOSED en
        //    accounting_learning_events.
        if (proposedRuleId != null) {
            jdbcTemplate.update("""
                    INSERT INTO accounting_learning_events (
                        id, company_id, journal_entry_id, line_id, event_kind,
                        to_account_id, related_rule_id, actor_user_id
                    ) VALUES (?, ?, ?, NULL, 'AUTO_PROPOSED', ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), companyId, entryId,
                    acc6xx, proposedRuleId, userId);
        }

        return entryId;
    }

    /**
     * Borra físicamente el asiento de una factura de compra que se
     * elimina. Acordado con el asesor: si el gasto desaparece, su asiento
     * desaparece también — queda un "hueco" en la numeración del Diario
     * (entre #4 y #6 falta el #5, p. ej.) que es la marca legítima de la
     * eliminación. Esto rompe la inalterabilidad estricta pero refleja la
     * realidad práctica de la asesoría: si la factura no existió, el
     * asiento tampoco.
     *
     * <p>Orden de borrado:
     * <ol>
     *   <li>accounting_learning_events que referencian el asiento.</li>
     *   <li>journal_entry_lines.</li>
     *   <li>journal_entries.</li>
     * </ol>
     */
    @Transactional
    public void reverseForPurchase(PurchaseInvoice purchase) {
        if (purchase.journalEntryId() == null) return;
        String companyId = tenantContext.getCurrentCompanyId();
        String entryId = purchase.journalEntryId();
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

    /** Busca cuenta por código exacto. */
    private String findAccountByCode(String companyId, String code) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id
                  FROM accounting_accounts
                 WHERE company_id = ?
                   AND active = TRUE
                   AND code = ?
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, code);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * Busca la primera cuenta cuyo code empieza por el prefijo dado.
     * Si la empresa tiene la cuenta exacta (e.g. "600"), gana sobre
     * sub-cuentas (e.g. "600001"). Si solo tiene sub-cuentas, devuelve
     * la primera alfabéticamente.
     */
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
                              java.math.BigDecimal debit, java.math.BigDecimal credit) {
        jdbcTemplate.update("""
                INSERT INTO journal_entry_lines (
                    id, journal_entry_id, account_id, description, debit, credit
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entryId, accountId,
                description == null ? null
                        : (description.length() > 240 ? description.substring(0, 240) : description),
                debit, credit);
    }

    /**
     * Descripción de la línea de gasto (cuenta 6xx):
     * <ul>
     *   <li>Si la factura tiene {@code concept} (el asesor lo capturó al
     *       importar/editar) → ese texto.</li>
     *   <li>Si no → el nombre de la cuenta de gasto resuelta
     *       (p. ej. "Compras de otros aprovisionamientos") más, si hay,
     *       el proveedor breve para no perder el contexto.</li>
     *   <li>Como último fallback si nada está disponible → "Gasto".</li>
     * </ul>
     */
    private String buildExpenseLineDescription(PurchaseInvoice p, String acc6xxId, String companyId) {
        if (p.concept() != null && !p.concept().isBlank()) {
            String c = p.concept().trim();
            return c.length() > 240 ? c.substring(0, 240) : c;
        }
        // Fallback: nombre de la cuenta de gasto + breve referencia al proveedor.
        String accountName = jdbcTemplate.query("""
                SELECT name FROM accounting_accounts WHERE id = ? LIMIT 1
                """, (rs, n) -> rs.getString("name"), acc6xxId)
                .stream().findFirst().orElse(null);
        StringBuilder sb = new StringBuilder();
        if (accountName != null && !accountName.isBlank()) {
            sb.append(accountName);
            if (p.supplierName() != null && !p.supplierName().isBlank()) {
                sb.append(" — ").append(p.supplierName());
            }
        } else if (p.supplierName() != null && !p.supplierName().isBlank()) {
            sb.append("Gasto ").append(p.supplierName());
        } else {
            sb.append("Gasto");
        }
        String s = sb.toString();
        return s.length() > 240 ? s.substring(0, 240) : s;
    }

    private String buildConcept(PurchaseInvoice p) {
        StringBuilder sb = new StringBuilder("Fra. ");
        if (p.invoiceNumber() != null) sb.append(p.invoiceNumber()).append(' ');
        if (p.supplierName() != null) sb.append("- ").append(p.supplierName());
        String s = sb.toString();
        return s.length() > 240 ? s.substring(0, 240) : s;
    }

    private String safe(Object v) {
        return v == null ? "" : v.toString();
    }

    /** Para tests del slice futuro de contabilidad — no usado todavía. */
    @SuppressWarnings("unused")
    private Map<String, Object> sentinel() { return Map.of(); }
}
