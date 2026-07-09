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
    private static final String SRC_TYPE_PAYMENT = "PURCHASE_PAYMENT";

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final AccountingLearningService learning;
    private final TerceroAccountResolverService terceroResolver;
    private final ExpenseAccountClassifierService classifier;
    private final com.benjagest.backend.accounting.FiscalYearGuardService fiscalGuard;

    public PurchaseJournalEntryService(JdbcTemplate jdbcTemplate,
                                         TenantContext tenantContext,
                                         AccountingLearningService learning,
                                         TerceroAccountResolverService terceroResolver,
                                         ExpenseAccountClassifierService classifier,
                                         com.benjagest.backend.accounting.FiscalYearGuardService fiscalGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.learning = learning;
        this.terceroResolver = terceroResolver;
        this.classifier = classifier;
        this.fiscalGuard = fiscalGuard;
    }

    /**
     * Intenta crear un asiento para la factura. Devuelve el id del
     * asiento o {@code null} si no fue posible (faltan cuentas o
     * fiscal_year). Nunca lanza excepción: el caller decide qué hacer
     * cuando devuelve null (típicamente loguear y persistir la factura
     * sin journal_entry_id).
     */
    @Transactional
    public String createForPurchase(PurchaseInvoice purchase, String userId, boolean postDirectly) {
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

        // (a) Gasto 6xx.
        //   GAS-1: si el gasto trae una cuenta fijada por el usuario
        //   (p.ej. 642 en un recibo de autónomo), se usa esa cuenta EXACTA
        //   y NO se ejecuta la cascada — el asesor la eligió, no adivinamos
        //   (auto_proposed = FALSE).
        String acc6xx;
        String proposedRuleId = null;
        java.math.BigDecimal proposedConfidence = null;
        String fixedCode = purchase.expenseAccountCode();
        if (fixedCode != null && !fixedCode.isBlank()) {
            acc6xx = findAccountByCode(companyId, fixedCode.trim());
        } else {
            // Cascada automática (port de CONTENDO):
            //   1. regla aprendida (NIF proveedor o keyword) → más fuerte
            Optional<AccountingLearningService.AccountProposal> proposal =
                    learning.proposeExpenseAccount(
                            purchase.supplierNif(), purchase.supplierName(),
                            purchase.invoiceNumber(), purchase.totalAmount());
            acc6xx = proposal.map(AccountingLearningService.AccountProposal::accountId)
                    .orElse(null);
            proposedRuleId = proposal.map(AccountingLearningService.AccountProposal::ruleId)
                    .orElse(null);
            proposedConfidence =
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
        }

        // (b) Proveedor 400 → sub-cuenta de tercero
        TerceroAccountResolverService.ResolvedAccount supplierAcc =
                terceroResolver.getOrCreateForSupplier(
                        purchase.supplierNif(), purchase.supplierName());
        String acc400 = supplierAcc.accountId();

        // (c) IVA soportado 472 — SOLO si el gasto tiene IVA. Un recibo sin
        //     IVA (p.ej. la cuota de autónomo de la TGSS) no lleva línea de
        //     472: el asiento es solo Debe 6xx / Haber 400 proveedor.
        boolean hasVat = purchase.vatAmount() != null
                && purchase.vatAmount().signum() != 0;
        String acc472 = hasVat ? findAccountByPrefix(companyId, "472") : null;

        if (acc6xx == null || acc400 == null || (hasVat && acc472 == null)) {
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
        // GAS-6/7: "validado directo" (POSTED con número) es SOLO del alta
        // MANUAL (postDirectly). El flujo automático (PDF/cascada) y el
        // recurrente con cuenta fija entran DRAFT + auto_proposed=TRUE para
        // que aparezcan en "Por validar" y el asesor los valide.
        boolean postNow = postDirectly;
        boolean autoProposed = !postNow;
        Integer entryNumber = postNow ? nextPostedEntryNumber(companyId, fiscalYearId) : null;
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, proposed_confidence,
                    created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entryId, companyId, fiscalYearId, entryNumber,
                Date.valueOf(purchase.invoiceDate()),
                concept, SRC_TYPE, purchase.id(),
                postNow ? "POSTED" : "DRAFT", false,
                autoProposed, proposedConfidence, userId);

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

        // Base (6xx) e IVA soportado (472) etiquetados con el tipo de IVA de la
        // compra, para que el 303/390 derive el IVA soportado por tipo desde la
        // contabilidad.
        insertLine(entryId, acc6xx, expenseDesc,
                purchase.baseAmount(), java.math.BigDecimal.ZERO, purchase.vatPercent());
        if (hasVat) {
            insertLine(entryId, acc472, vatDesc,
                    purchase.vatAmount(), java.math.BigDecimal.ZERO, purchase.vatPercent());
        }
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

        // IRPF-DED (2026-07-09): la factura hereda la deducibilidad IRPF de su
        // cuenta de gasto 6xx (subcuenta de vehículo = no deducible; genérica =
        // deducible). Así, si una regla de proveedor enruta el gasto a una
        // subcuenta de vehículo, la factura importada ya nace como no deducible
        // en el 130 sin tener que tocarla a mano.
        List<Integer> irpfDed = jdbcTemplate.query(
                "SELECT irpf_deductible_default FROM accounting_accounts WHERE id = ?",
                (rs, n) -> rs.getInt(1), acc6xx);
        if (!irpfDed.isEmpty()) {
            jdbcTemplate.update(
                    "UPDATE purchase_invoices SET expense_deductible = ? WHERE id = ? AND company_id = ?",
                    irpfDed.get(0), purchase.id(), companyId);
        }

        return entryId;
    }

    /**
     * GAS-2 — Crea el asiento de PAGO de una factura recibida / recibo:
     *
     *   Debe  400x (Proveedor)   = total
     *                Haber  572x (Banco)  = total
     *
     * Segundo asiento del gasto (el devengo se creó al guardar), igual que
     * en CONTENDO. Lanza excepción con mensaje legible si falta el ejercicio
     * abierto, la cuenta de banco o la del proveedor — el usuario pidió pagar
     * explícitamente, así que no fallamos en silencio.
     */
    @Transactional
    public String createPaymentForPurchase(PurchaseInvoice purchase,
                                           LocalDate paymentDate,
                                           String bankAccountCode,
                                           String userId) {
        String companyId = tenantContext.getCurrentCompanyId();
        if (paymentDate == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Falta la fecha de pago");
        }
        if (bankAccountCode == null || bankAccountCode.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Falta la cuenta de banco");
        }
        java.math.BigDecimal amount = purchase.totalAmount();
        if (amount == null || amount.signum() == 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "El gasto no tiene importe a pagar");
        }
        String fiscalYearId = findOpenFiscalYearId(companyId, paymentDate);
        if (fiscalYearId == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "No hay ejercicio contable abierto para la fecha de pago");
        }
        String acc572 = findAccountByCode(companyId, bankAccountCode.trim());
        if (acc572 == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "No existe la cuenta de banco " + bankAccountCode);
        }
        TerceroAccountResolverService.ResolvedAccount supplierAcc =
                terceroResolver.getOrCreateForSupplier(
                        purchase.supplierNif(), purchase.supplierName());
        String acc400 = supplierAcc.accountId();
        if (acc400 == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "No se pudo resolver la cuenta del proveedor");
        }

        String entryId = UUID.randomUUID().toString();
        String supplierName = purchase.supplierName() != null && !purchase.supplierName().isBlank()
                ? purchase.supplierName()
                : (purchase.supplierNif() != null ? purchase.supplierNif() : "Proveedor");
        String concept = "Pago"
                + (purchase.invoiceNumber() != null ? " Fra. " + purchase.invoiceNumber() : "")
                + " - " + supplierName;
        if (concept.length() > 240) concept = concept.substring(0, 240);
        // GAS-6: el pago manual entra VALIDADO directo (POSTED con número).
        int entryNumber = nextPostedEntryNumber(companyId, fiscalYearId);
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, proposed_confidence,
                    created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', FALSE, FALSE, NULL, ?)
                """,
                entryId, companyId, fiscalYearId, entryNumber,
                Date.valueOf(paymentDate),
                concept, SRC_TYPE_PAYMENT, purchase.id(),
                userId);

        // Debe 400 proveedor / Haber 572 banco.
        insertLine(entryId, acc400, supplierName, amount, java.math.BigDecimal.ZERO);
        insertLine(entryId, acc572, concept, java.math.BigDecimal.ZERO, amount);
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
        String companyId = tenantContext.getCurrentCompanyId();
        // Asiento(s) de PAGO (GAS-2). No están vinculados por
        // journal_entry_id (ese apunta al devengo); se localizan por
        // source_type/source_id.
        List<String> paymentEntries = jdbcTemplate.query("""
                SELECT id FROM journal_entries
                 WHERE company_id = ? AND source_type = ? AND source_id = ?
                """, (rs, n) -> rs.getString("id"),
                companyId, SRC_TYPE_PAYMENT, purchase.id());
        // LOCK (2026-07-07): ningún asiento de un ejercicio LOCKED/CLOSED
        // se borra. Se comprueban TODOS (devengo + pagos, que pueden caer
        // en años distintos) ANTES de borrar nada — todo-o-nada.
        if (purchase.journalEntryId() != null) {
            requireEntryOpen(companyId, purchase.journalEntryId());
        }
        for (String pe : paymentEntries) {
            requireEntryOpen(companyId, pe);
        }
        // 1) Asiento de devengo (vinculado por journal_entry_id).
        if (purchase.journalEntryId() != null) {
            deleteEntry(companyId, purchase.journalEntryId());
        }
        // 2) Asientos de pago.
        for (String pe : paymentEntries) {
            deleteEntry(companyId, pe);
        }
    }

    /** LOCK: 409 si el asiento existe y su fecha cae en ejercicio no OPEN. */
    private void requireEntryOpen(String companyId, String entryId) {
        List<java.time.LocalDate> dates = jdbcTemplate.query("""
                SELECT entry_date FROM journal_entries
                 WHERE id = ? AND company_id = ?
                """, (rs, n) -> rs.getDate("entry_date").toLocalDate(),
                entryId, companyId);
        for (java.time.LocalDate d : dates) {
            fiscalGuard.requireOpenForDate(companyId, d, "borrar el asiento de este gasto");
        }
    }

    /** Borra un asiento y sus dependencias (eventos de aprendizaje + líneas). */
    private void deleteEntry(String companyId, String entryId) {
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
        insertLine(entryId, accountId, description, debit, credit, null);
    }

    private void insertLine(String entryId, String accountId, String description,
                              java.math.BigDecimal debit, java.math.BigDecimal credit,
                              java.math.BigDecimal vatRate) {
        jdbcTemplate.update("""
                INSERT INTO journal_entry_lines (
                    id, journal_entry_id, account_id, description, debit, credit, vat_rate
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entryId, accountId,
                description == null ? null
                        : (description.length() > 240 ? description.substring(0, 240) : description),
                debit, credit, vatRate);
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
        // Recibo sin nº de factura (p.ej. cuota de autónomo): no tiene sentido
        // "Fra. - ..."; usamos el concepto tecleado o, si no, el proveedor.
        if (p.invoiceNumber() == null || p.invoiceNumber().isBlank()) {
            String base = (p.concept() != null && !p.concept().isBlank())
                    ? p.concept()
                    : (p.supplierName() != null ? p.supplierName() : "Gasto");
            return base.length() > 240 ? base.substring(0, 240) : base;
        }
        StringBuilder sb = new StringBuilder("Fra. ");
        sb.append(p.invoiceNumber()).append(' ');
        if (p.supplierName() != null) sb.append("- ").append(p.supplierName());
        String s = sb.toString();
        return s.length() > 240 ? s.substring(0, 240) : s;
    }

    /** Siguiente entry_number correlativo entre los POSTED del ejercicio. */
    private int nextPostedEntryNumber(String companyId, String fiscalYearId) {
        Integer max = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0)
                  FROM journal_entries
                 WHERE company_id = ? AND fiscal_year_id = ?
                   AND status = 'POSTED'
                   AND entry_number IS NOT NULL
                """, Integer.class, companyId, fiscalYearId);
        return (max == null ? 0 : max) + 1;
    }

    private String safe(Object v) {
        return v == null ? "" : v.toString();
    }

    /** Para tests del slice futuro de contabilidad — no usado todavía. */
    @SuppressWarnings("unused")
    private Map<String, Object> sentinel() { return Map.of(); }
}
