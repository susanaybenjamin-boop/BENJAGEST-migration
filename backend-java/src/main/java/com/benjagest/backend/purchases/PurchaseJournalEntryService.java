package com.benjagest.backend.purchases;

import com.benjagest.backend.accounting.AccountingLearningService;
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

    public PurchaseJournalEntryService(JdbcTemplate jdbcTemplate,
                                         TenantContext tenantContext,
                                         AccountingLearningService learning) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.learning = learning;
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

        // 2) Cuentas 472/400 (prefijo) + 6xx propuesta por aprendizaje.
        //    Si hay una regla aprendida para este proveedor (NIF) o para
        //    una palabra clave del nombre/concepto, la usamos como cuenta
        //    de gasto en lugar del 600 genérico. La regla se "marca" en
        //    el asiento via proposedRuleId para que, cuando el asesor lo
        //    valide, se refuerce automáticamente. El asiento queda en
        //    DRAFT — el asesor decide.
        Optional<AccountingLearningService.AccountProposal> proposal =
                learning.proposeExpenseAccount(
                        purchase.supplierNif(), purchase.supplierName(),
                        purchase.invoiceNumber(), purchase.totalAmount());
        String acc6xx = proposal.map(AccountingLearningService.AccountProposal::accountId)
                .orElseGet(() -> findAccountByPrefix(companyId, "600"));
        String proposedRuleId = proposal.map(AccountingLearningService.AccountProposal::ruleId)
                .orElse(null);
        java.math.BigDecimal proposedConfidence =
                proposal.map(AccountingLearningService.AccountProposal::confidence).orElse(null);

        String acc472 = findAccountByPrefix(companyId, "472");
        String acc400 = findAccountByPrefix(companyId, "400");
        if (acc6xx == null || acc472 == null || acc400 == null) {
            return null;
        }

        // 3) entry_number = siguiente para este fiscal_year. Posibilidad
        //    de race en concurrencia alta — aceptable hasta que llegue
        //    el slice contable serio (con secuencia oficial).
        Integer maxEntryNumber = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0)
                  FROM journal_entries
                 WHERE company_id = ?
                   AND fiscal_year_id = ?
                """, Integer.class, companyId, fiscalYearId);
        int entryNumber = (maxEntryNumber == null ? 0 : maxEntryNumber) + 1;

        // 4) Crear el asiento. auto_proposed=TRUE indica que la cuenta
        //    de gasto vino de una regla aprendida (o del fallback 600).
        //    proposed_confidence guarda la fuerza de la regla para que la
        //    UI muestre un chip de confianza al asesor.
        String entryId = UUID.randomUUID().toString();
        String concept = buildConcept(purchase);
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, proposed_confidence,
                    created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', FALSE, TRUE, ?, ?)
                """,
                entryId, companyId, fiscalYearId, entryNumber,
                Date.valueOf(purchase.invoiceDate()),
                concept, SRC_TYPE, purchase.id(),
                proposedConfidence, userId);

        // 5) Líneas: 2 debes (6xx base, 472 iva) + 1 haber (400 total).
        //    Descripción: usamos el concepto de la factura como base
        //    (proveedor + nº factura). El usuario puede editar línea a
        //    línea en el editor del asiento; al cambiar de cuenta esa
        //    descripción se preserva.
        String baseDescription = buildLineDescription(purchase);
        insertLine(entryId, acc6xx, baseDescription,
                purchase.baseAmount(), java.math.BigDecimal.ZERO);
        insertLine(entryId, acc472, baseDescription
                + " (IVA " + safe(purchase.vatPercent()) + "%)",
                purchase.vatAmount(), java.math.BigDecimal.ZERO);
        insertLine(entryId, acc400, baseDescription,
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

    /** Revierte el asiento (anula el asiento original y crea uno opuesto). */
    @Transactional
    public void reverseForPurchase(PurchaseInvoice purchase) {
        if (purchase.journalEntryId() == null) return;
        // MVP: marcar el asiento como VOIDED. La inversión real (asiento
        // espejo con debe/haber cambiados) llega cuando el módulo
        // contable madure.
        jdbcTemplate.update("""
                UPDATE journal_entries
                   SET status = 'VOIDED'
                 WHERE id = ?
                   AND company_id = ?
                """, purchase.journalEntryId(), tenantContext.getCurrentCompanyId());
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
     * Descripción de línea: incluye nº factura + proveedor para que el
     * Libro Mayor y el Diario se lean bien sin tener que abrir el asiento.
     * Si el asesor edita la descripción en el editor, la suya se respeta
     * y NO se sobrescribe al cambiar de cuenta.
     */
    private String buildLineDescription(PurchaseInvoice p) {
        StringBuilder sb = new StringBuilder();
        if (p.invoiceNumber() != null && !p.invoiceNumber().isBlank()) {
            sb.append("Fra. ").append(p.invoiceNumber()).append(' ');
        }
        if (p.supplierName() != null && !p.supplierName().isBlank()) {
            sb.append("- ").append(p.supplierName());
        } else if (p.supplierNif() != null) {
            sb.append("- ").append(p.supplierNif());
        }
        String s = sb.toString().trim();
        if (s.isEmpty()) s = "Compra";
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
