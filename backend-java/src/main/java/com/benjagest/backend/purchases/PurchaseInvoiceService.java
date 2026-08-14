package com.benjagest.backend.purchases;

import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.audit.AuditService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Persistencia y consulta de facturas de compra (gastos).
 *
 * Flujo principal save(request):
 *   1) Dedup: si (company, sha, index) ya existe → 409 con id existente.
 *   2) Insertar la factura con status POSTED.
 *   3) Intentar crear asiento contable vía
 *      {@link PurchaseJournalEntryService}; si la empresa no tiene
 *      cuentas 600/472/400 o no hay fiscal_year OPEN, se persiste
 *      sin asiento (sub-slice contable lo regenera más tarde).
 *   4) Auditar PURCHASE_INVOICE_POSTED.
 */
@Service
public class PurchaseInvoiceService {

    private final PurchaseInvoiceRepository repository;
    private final PurchaseJournalEntryService journalService;
    private final CurrentUserService currentUserService;
    private final TenantContext tenantContext;
    private final AuditService auditService;
    private final com.benjagest.backend.accounting.FiscalYearGuardService fiscalGuard;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final com.benjagest.backend.billing.tpb.BillingAgreementGuard billingAgreementGuard;
    private final com.benjagest.backend.accounting.AccountingLearningService learningService;

    public PurchaseInvoiceService(PurchaseInvoiceRepository repository,
                                    PurchaseJournalEntryService journalService,
                                    CurrentUserService currentUserService,
                                    TenantContext tenantContext,
                                    AuditService auditService,
                                    com.benjagest.backend.accounting.FiscalYearGuardService fiscalGuard,
                                    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                                    com.benjagest.backend.billing.tpb.BillingAgreementGuard billingAgreementGuard,
                                    com.benjagest.backend.accounting.AccountingLearningService learningService) {
        this.repository = repository;
        this.journalService = journalService;
        this.currentUserService = currentUserService;
        this.tenantContext = tenantContext;
        this.auditService = auditService;
        this.fiscalGuard = fiscalGuard;
        this.jdbcTemplate = jdbcTemplate;
        this.billingAgreementGuard = billingAgreementGuard;
        this.learningService = learningService;
    }

    @Transactional
    public SaveResult save(SaveRequest req) {
        billingAgreementGuard.requireAgreementOrOwn(
                com.benjagest.backend.billing.tpb.BillingAgreementGuard.Scope.PURCHASES);
        // LOCK (2026-07-07): no se registra un gasto fechado en un
        // ejercicio LOCKED/CLOSED (sus libros y su 303 ya están
        // presentados). El gasto que aparece tarde se contabiliza en el
        // periodo corriente, no reabriendo el cerrado.
        if (req.invoiceDate() != null) {
            fiscalGuard.requireOpenForDate(req.invoiceDate(), "registrar un gasto con esa fecha");
        }
        // 1) Dedup por (company, sha, index).
        if (req.documentSha256() != null && !req.documentSha256().isBlank()) {
            var existing = repository.findByShaAndIndex(
                    req.documentSha256(), req.invoiceIndexInPdf());
            if (existing.isPresent()) {
                return new SaveResult(existing.get(), true, "Factura ya registrada");
            }
        }

        // 2) Auto-crear proveedor si tiene NIF y no existe ya: el sistema
        //    aprende a quién paga. La factura sigue guardando supplier_nif
        //    y supplier_name como texto libre (para mantener el dato
        //    original de la factura escaneada), pero también se asegura
        //    de tener el supplier persistido en la tabla suppliers.
        ensureSupplierExists(req.supplierNif(), req.supplierName());

        AuthenticatedUser user = currentUserService.require();
        String tenant = tenantContext.getCurrentCompanyId();
        String uploaderCompany = user.activeCompanyId();

        String id = UUID.randomUUID().toString();
        PurchaseInvoice draft = new PurchaseInvoice(
                id,
                tenant,
                normalize(req.supplierNif()),
                blankToNull(req.supplierName()),
                blankToNull(req.invoiceNumber()),
                req.invoiceDate(),
                req.baseAmount(),
                req.vatPercent(),
                req.vatAmount(),
                req.totalAmount(),
                blankToNull(req.documentSha256()),
                req.invoiceIndexInPdf(),
                PurchaseInvoice.STATUS_POSTED,
                null,
                blankToNull(req.expenseAccountCode()),
                false, null, null, // GAS-2: nace pendiente de pago
                blankToNull(req.concept()),
                blankToNull(req.notes()),
                user.userId(),
                uploaderCompany,
                null, null
        );

        // 2) Insertar.
        repository.insert(draft);

        // OPTYPE-3: auto-detectar el tipo de operación por el NIF del proveedor
        // y el IVA de la factura (un NIF de operador UE ≠ ES SIN IVA → adq.
        // intracomunitaria; si lleva IVA español es interior, p. ej. Amazon
        // EU). Solo escribe si difiere del INTERIOR por defecto; la asesoría
        // puede afinarlo luego con "Clasificación fiscal".
        String tipoOp = OperationTypeDetector.detect(req.supplierNif(), req.vatAmount());
        if (!"INTERIOR".equals(tipoOp)) {
            repository.updateOperationType(id, tipoOp);
        }

        // DEDUC (2026-07-09): si la asesoría tiene una REGLA de deducibilidad
        // para este proveedor ("Repsol = IVA 50% / IRPF 0%"), se PRECARGA en
        // la factura ANTES de generar el asiento (el reparto 6xx/472 la usa).
        // Solo precarga: la clasificación fiscal de la factura sigue editable.
        applySupplierDeductibilityRule(id, normalize(req.supplierNif()));

        // 3) Intentar asiento (best effort).
        String journalEntryId = null;
        try {
            journalEntryId = journalService.createForPurchase(
                    draft, user.userId(), req.postJournalDirectly());
        } catch (Exception ex) {
            // Tragamos: la factura ya está persistida; el asiento se
            // generará en sub-slice contable. Loguear sin romper.
            System.err.println("[purchases] no se pudo crear asiento para "
                    + id + ": " + ex.getMessage());
        }
        if (journalEntryId != null) {
            repository.updateJournalEntryFk(id, journalEntryId);
        }

        // 4) Auditoría.
        auditService.recordPurchaseInvoicePosted(user.userId(), tenant, id,
                draft.totalAmount(), journalEntryId != null);

        PurchaseInvoice persisted = repository.findById(id).orElseThrow();
        return new SaveResult(persisted, false, null);
    }

    public PurchaseInvoice get(String id) {
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Gasto no encontrado"));
    }

    public List<PurchaseInvoice> list(Integer year, String status, String supplierNif) {
        return repository.list(year, status, supplierNif);
    }

    /**
     * GAS-2 — Registra el PAGO de un gasto (segundo paso, como CONTENDO):
     * genera el asiento Debe 400 proveedor / Haber 572 banco y marca el
     * gasto como pagado. Idempotencia mínima: si ya está pagado, 409.
     */
    @Transactional
    public PurchaseInvoice registerPayment(String id, LocalDate paymentDate, String bankAccountCode) {
        billingAgreementGuard.requireAgreementOrOwn(
                com.benjagest.backend.billing.tpb.BillingAgreementGuard.Scope.PURCHASES);
        PurchaseInvoice existing = get(id);
        if (existing.paid()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El gasto ya esta pagado");
        }
        // El pago debe caer en un ejercicio abierto (mismo criterio que el devengo).
        fiscalGuard.requireOpenForDate(paymentDate, "registrar el pago");
        AuthenticatedUser user = currentUserService.require();
        String entryId = journalService.createPaymentForPurchase(
                existing, paymentDate, bankAccountCode, user.userId());
        repository.markPaid(id, paymentDate, bankAccountCode);
        settleDueDates(id, paymentDate, bankAccountCode, entryId);
        return repository.findById(id).orElseThrow();
    }

    /**
     * PAGO-1 — Da por saldados los vencimientos PENDIENTES del gasto que se
     * acaba de pagar por "Registrar pago".
     *
     * <p>Hay dos caminos de pago que no se hablaban: este (GAS-2, escribe
     * {@code purchase_invoices.paid}) y el de vencimientos (PV-1, escribe
     * {@code invoice_due_dates.status}). Sin esto, tras pagar aquí el
     * vencimiento seguía PENDING y el diálogo "Vencimientos / Pago" dejaba
     * volver a pagarlo, generando un SEGUNDO asiento 400→572 por el mismo
     * gasto (la familia de bugs de BANK-DUP).
     *
     * <p>Se enlazan al asiento que ya se ha creado aquí; NO se genera otro.
     * Best-effort: el pago y su asiento son lo importante.
     */
    private void settleDueDates(String invoiceId, LocalDate paymentDate,
                                String bankAccountCode, String journalEntryId) {
        try {
            jdbcTemplate.update("""
                    UPDATE invoice_due_dates
                       SET status = 'PAID', paid_date = ?,
                           treasury_account_code = COALESCE(treasury_account_code, ?),
                           journal_entry_id = COALESCE(journal_entry_id, ?)
                     WHERE company_id = ? AND invoice_kind = 'PURCHASE'
                       AND invoice_id = ? AND status <> 'PAID'
                    """,
                    paymentDate == null ? null : java.sql.Date.valueOf(paymentDate),
                    bankAccountCode, journalEntryId,
                    tenantContext.getCurrentCompanyId(), invoiceId);
        } catch (Exception ignored) {
            // best-effort: el gasto queda pagado igual, con su asiento.
        }
    }

    /**
     * Borrado físico de la factura recibida.
     *
     * Decisión de diseño (ver {@code docs/legal-compras-gastos.md}):
     * las facturas RECIBIDAS no entran en VeriFactu/SIF, así que no
     * tienen obligación de inalterabilidad como las emitidas. La
     * práctica habitual en A3 / Sage / Contasol es permitir DELETE
     * mientras el período no esté cerrado.
     *
     * El audit_event se registra ANTES del DELETE para que la traza
     * quede aunque la fila desaparezca de la BD. Cuando se cierre el
     * slice de cierre fiscal, este método bloqueará el DELETE si la
     * factura cae en un fiscal_year LOCKED/CLOSED o en un período
     * presentado.
     */
    /**
     * Valida un lote de facturas DRAFT pasándolas a POSTED y generando
     * (si no lo tienen ya) el asiento contable automático correspondiente.
     *
     * <p>Para cada factura del lote:
     * <ul>
     *   <li>Si status != DRAFT, se salta (idempotente).</li>
     *   <li>Si está en un fiscal_year LOCKED/CLOSED, se reporta error.</li>
     *   <li>Si OK: status=POSTED, genera asiento via PurchaseJournalEntryService
     *       (mismo flujo que cuando se guarda una factura individual).</li>
     * </ul>
     *
     * @return resumen del proceso por id.
     */
    @Transactional
    public BatchValidateResult validateBatch(List<String> ids) {
        billingAgreementGuard.requireAgreementOrOwn(
                com.benjagest.backend.billing.tpb.BillingAgreementGuard.Scope.PURCHASES);
        if (ids == null || ids.isEmpty()) {
            return new BatchValidateResult(0, 0, 0, java.util.List.of());
        }
        AuthenticatedUser user = currentUserService.require();
        String tenant = tenantContext.getCurrentCompanyId();

        int posted = 0;
        int skipped = 0;
        int errors = 0;
        java.util.List<BatchValidateItem> items = new java.util.ArrayList<>();

        for (String id : ids) {
            try {
                PurchaseInvoice existing = repository.findById(id).orElse(null);
                if (existing == null) {
                    skipped++;
                    items.add(new BatchValidateItem(id, "SKIPPED", "no existe", null));
                    continue;
                }
                if (!"DRAFT".equals(existing.status())) {
                    skipped++;
                    items.add(new BatchValidateItem(id, "SKIPPED",
                            "status=" + existing.status(), existing.journalEntryId()));
                    continue;
                }
                // Verificar fiscal_year OPEN para la fecha (idempotente).
                try {
                    fiscalGuard.requireOpenForDate(existing.invoiceDate(), "validar gasto");
                } catch (Exception ex) {
                    errors++;
                    items.add(new BatchValidateItem(id, "ERROR", ex.getMessage(), null));
                    continue;
                }
                String entryId = existing.journalEntryId();
                if (entryId == null) {
                    try {
                        // Validación desde la lista de Compras: el asiento
                        // entra en "Por validar" (no directo), como el resto.
                        entryId = journalService.createForPurchase(existing, user.userId(), false);
                    } catch (Exception ex) {
                        errors++;
                        items.add(new BatchValidateItem(id, "ERROR",
                                "asiento: " + ex.getMessage(), null));
                        continue;
                    }
                }
                repository.updateStatus(id, "POSTED");
                if (entryId != null) {
                    repository.updateJournalEntryFk(id, entryId);
                }
                auditService.recordPurchaseInvoicePosted(user.userId(), tenant, id,
                        existing.totalAmount(), entryId != null);
                posted++;
                items.add(new BatchValidateItem(id, "POSTED", null, entryId));
            } catch (Exception ex) {
                errors++;
                items.add(new BatchValidateItem(id, "ERROR", ex.getMessage(), null));
            }
        }
        return new BatchValidateResult(ids.size(), posted, skipped + errors, items);
    }

    @Transactional
    public void deleteInvoice(String id) {
        billingAgreementGuard.requireAgreementOrOwn(
                com.benjagest.backend.billing.tpb.BillingAgreementGuard.Scope.PURCHASES);
        PurchaseInvoice existing = get(id);
        // PURCHASES-CIERRE-FISCAL: si la factura cae en un ejercicio
        // LOCKED/CLOSED, no se puede borrar. La AEAT considera
        // alteración de un periodo presentado. Hay que rectificar
        // (factura con signo negativo en el ejercicio actual). El
        // guard lanza 409 con mensaje legible que la UI muestra.
        fiscalGuard.requireOpenForDate(existing.invoiceDate(), "eliminar esta factura");
        // AUDIT-T3 (2026-07-09): el ejercicio anual puede seguir OPEN pero el
        // TRIMESTRE de la factura puede estar ya declarado (303/130
        // presentados). Borrar entonces descuadra una declaración presentada.
        requirePeriodNotPresented(existing.invoiceDate(), "eliminar esta factura");
        deleteInternal(existing);
    }

    /**
     * DUP-VALIDAR (2026-07-10) — borrado de un gasto DUPLICADO confirmado por
     * el usuario. A diferencia de deleteInvoice, NO aplica el guard por FECHA
     * del periodo presentado: exige en su lugar que (a) exista OTRO gasto
     * POSTED idéntico (mismo NIF + base + total) que se conserva, y (b) este
     * gasto NO estuviera incluido en la declaración (creado DESPUÉS de la
     * última presentación de su periodo). Así se limpia el duplicado recién
     * importado aunque su fecha caiga en un trimestre ya declarado.
     */
    @Transactional
    public void deleteDuplicate(String id) {
        billingAgreementGuard.requireAgreementOrOwn(
                com.benjagest.backend.billing.tpb.BillingAgreementGuard.Scope.PURCHASES);
        PurchaseInvoice existing = get(id);
        fiscalGuard.requireOpenForDate(existing.invoiceDate(), "eliminar este gasto duplicado");
        String companyId = tenantContext.getCurrentCompanyId();
        Integer twins = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM purchase_invoices
                 WHERE company_id = ? AND id <> ? AND status = 'POSTED'
                   AND total_amount = ?
                   AND (supplier_nif IS NULL OR ? IS NULL OR supplier_nif = ?)
                   AND invoice_date = ?
                """, Integer.class, companyId, id, existing.totalAmount(),
                existing.supplierNif(), existing.supplierNif(), existing.invoiceDate());
        if (twins == null || twins == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No hay otro gasto idéntico: esto no es un duplicado. Usa el borrado normal.");
        }
        // Solo se elimina la copia MÁS NUEVA de la pareja: la más antigua es
        // la única que pudo entrar en la declaración presentada y se conserva.
        // (La heurística anterior por updated_at del modelo fallaba si el
        // trimestre se marcaba como PAGADO después de reimportar — el
        // timestamp se renovaba y ambos parecían "incluidos".)
        java.sql.Timestamp oldestTwin = jdbcTemplate.query("""
                SELECT MIN(created_at) FROM purchase_invoices
                 WHERE company_id = ? AND id <> ? AND status = 'POSTED'
                   AND total_amount = ?
                   AND (supplier_nif IS NULL OR ? IS NULL OR supplier_nif = ?)
                   AND invoice_date = ?
                """, rs -> rs.next() ? rs.getTimestamp(1) : null,
                companyId, id, existing.totalAmount(),
                existing.supplierNif(), existing.supplierNif(), existing.invoiceDate());
        if (oldestTwin != null && existing.createdAt() != null
                && existing.createdAt().isBefore(oldestTwin.toInstant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este gasto es la copia MÁS ANTIGUA (la que entró en la declaración): se conserva. "
                    + "Elimina el duplicado más reciente o rectifica en el periodo corriente.");
        }
        deleteInternal(existing);
    }

    /** Cuerpo común del borrado físico (auditoría + asientos + factura). */
    private void deleteInternal(PurchaseInvoice existing) {
        String id = existing.id();
        AuthenticatedUser user = currentUserService.require();
        String tenant = tenantContext.getCurrentCompanyId();
        // Auditoría primero: necesitamos la traza aunque luego el
        // DELETE no se materializara por algún motivo.
        auditService.recordPurchaseInvoiceDeleted(user.userId(), tenant,
                id, existing.totalAmount(), existing.supplierName(),
                existing.invoiceNumber());
        // La factura referencia su asiento de devengo por journal_entry_id
        // (FK fk_purchase_invoices_journal_v40). Hay que romper esa referencia
        // ANTES de borrar el asiento: si no, el DELETE del asiento choca con la
        // FK (error 1451), marca la transacción como rollback-only y el
        // deletePhysical posterior revienta con UnexpectedRollbackException
        // (el usuario veía "No se pudo eliminar").
        if (existing.journalEntryId() != null) {
            repository.clearJournalEntryLink(id);
        }
        // Borra el/los asiento(s) del gasto (devengo + pagos, todo-o-nada). NO
        // se traga la excepción: si un asiento cae en un ejercicio cerrado,
        // reverseForPurchase lanza 409 con mensaje legible y no se borra nada
        // (la transacción revierte, incluido el enlace anterior).
        journalService.reverseForPurchase(existing);
        repository.deletePhysical(id);
    }

    /**
     * IRPF-DED (2026-07-09) — "Crear regla" desde un gasto: manda TODAS las
     * facturas de este proveedor a la cuenta indicada (p.ej. una subcuenta de
     * vehículo no deducible). Hace tres cosas de una:
     *   1) Reclasifica la línea de gasto 6xx de ESTE asiento a la cuenta destino.
     *   2) Aprende la regla proveedor NIF → cuenta (idempotente) para que las
     *      importaciones futuras de ese proveedor caigan solas ahí.
     *   3) Sincroniza la factura: fija su cuenta y hereda la deducibilidad IRPF
     *      de la cuenta destino.
     * Una factura concreta se "rescata" luego llevándola a la cuenta genérica.
     */
    @Transactional
    public void createExpenseRuleFromInvoice(String id, String targetAccountCode) {
        if (targetAccountCode == null || targetAccountCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta la cuenta destino.");
        }
        String code = targetAccountCode.trim();
        String companyId = tenantContext.getCurrentCompanyId();
        PurchaseInvoice inv = get(id); // 404 si no existe
        fiscalGuard.requireOpenForDate(inv.invoiceDate(), "reclasificar este gasto");
        // AUDIT-T3: no reclasificar un gasto cuyo trimestre ya está declarado.
        requirePeriodNotPresented(inv.invoiceDate(), "reclasificar este gasto");

        List<Object[]> acc = jdbcTemplate.query("""
                SELECT id, irpf_deductible_default FROM accounting_accounts
                 WHERE company_id = ? AND code = ? AND active = TRUE LIMIT 1
                """, (rs, n) -> new Object[]{rs.getString("id"), rs.getInt("irpf_deductible_default")},
                companyId, code);
        if (acc.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La cuenta " + code + " no existe.");
        }
        String toAccountId = (String) acc.get(0)[0];
        int irpfDed = (Integer) acc.get(0)[1];

        // Línea 6xx (Debe) del asiento de devengo — la que se reclasifica.
        String entryId = inv.journalEntryId();
        String lineId = null, fromAccountId = null;
        if (entryId != null) {
            List<String[]> lines = jdbcTemplate.query("""
                    SELECT l.id, l.account_id FROM journal_entry_lines l
                      JOIN accounting_accounts a ON a.id = l.account_id
                     WHERE l.journal_entry_id = ? AND a.code LIKE '6%' AND l.debit > 0
                     LIMIT 1
                    """, (rs, n) -> new String[]{rs.getString("id"), rs.getString("account_id")}, entryId);
            if (!lines.isEmpty()) {
                lineId = lines.get(0)[0];
                fromAccountId = lines.get(0)[1];
                jdbcTemplate.update("UPDATE journal_entry_lines SET account_id = ? WHERE id = ?",
                        toAccountId, lineId);
            }
        }

        // Aprender la regla proveedor NIF → cuenta destino (idempotente).
        if (inv.supplierNif() != null && !inv.supplierNif().isBlank()) {
            String userId;
            try { userId = currentUserService.require().userId(); } catch (Exception ex) { userId = null; }
            learningService.recordCorrection(
                    new com.benjagest.backend.accounting.AccountingLearningService.CorrectionRequest(
                            entryId, lineId, fromAccountId, toAccountId, code,
                            inv.supplierNif(), null, null, null, "Regla creada desde el gasto"),
                    userId);
        }

        // Sincronizar la factura con la cuenta destino.
        jdbcTemplate.update("""
                UPDATE purchase_invoices
                   SET expense_account_code = ?, expense_deductible = ?
                 WHERE id = ? AND company_id = ?
                """, code, irpfDed, id, companyId);
    }

    /**
     * AUDIT-T3 (2026-07-09) — 409 si la fecha cae en un periodo cuyo 303/130
     * (o un modelo anual: 390/347/349) ya está PRESENTADO/PAID. El 130 es
     * acumulado, así que un gasto del 1T también alimenta el 2T-4T: se bloquea
     * si hay CUALQUIER presentación del año con trimestre &gt;= al de la fecha,
     * o una anual. El camino legal es la rectificación en el periodo corriente.
     */
    public void requirePeriodNotPresented(LocalDate date, String action) {
        if (date == null) return;
        int q = (date.getMonthValue() - 1) / 3 + 1;
        Integer hits = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tax_filings
                 WHERE company_id = ? AND status IN ('PRESENTED', 'PAID')
                   AND period_year = ?
                   AND (period_quarter IS NULL OR period_quarter >= ?)
                """, Integer.class, tenantContext.getCurrentCompanyId(),
                date.getYear(), q);
        if (hits != null && hits > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede " + action + ": su periodo (" + q + "T " + date.getYear()
                    + ") ya está incluido en una declaración presentada. Registra la "
                    + "corrección/rectificación en el periodo corriente.");
        }
    }

    /**
     * DEDUC (2026-07-09) — aplica la regla de deducibilidad del proveedor
     * (si existe) a una factura recién creada: precarga los % de IVA e IRPF
     * deducibles. No pisa nada más; el asesor puede corregirlos después en
     * "Clasificación fiscal".
     */
    private void applySupplierDeductibilityRule(String invoiceId, String supplierNif) {
        if (supplierNif == null || supplierNif.isBlank()) return;
        try {
            jdbcTemplate.update("""
                    UPDATE purchase_invoices p
                      JOIN supplier_deductibility_rules r
                        ON r.company_id = p.company_id AND r.supplier_nif = ?
                       SET p.vat_deductible_percent = r.vat_deductible_percent,
                           p.irpf_deductible_percent = r.irpf_deductible_percent
                     WHERE p.id = ? AND p.company_id = ?
                    """, supplierNif.toUpperCase(), invoiceId,
                    tenantContext.getCurrentCompanyId());
        } catch (Exception ex) {
            // best effort: sin regla (o tabla aún no migrada) la factura queda
            // con los defaults (100/hereda de cuenta).
        }
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
    private String normalize(String nif) {
        return nif == null || nif.isBlank() ? null : nif.trim().toUpperCase();
    }

    /**
     * Auto-crea un Supplier si tiene NIF y aún no existe en esta empresa.
     * Idempotente: si ya está, no toca nada.
     * <p>
     * El INSERT IGNORE deja que el UK (company_id, tax_identifier) se
     * encargue de la deduplicación — si dos requests concurrentes
     * intentan crear el mismo, solo el primero gana y el segundo es
     * silencioso (no error).
     */
    private void ensureSupplierExists(String supplierNif, String supplierName) {
        String nif = normalize(supplierNif);
        if (nif == null) return;
        String name = blankToNull(supplierName);
        if (name == null) name = nif; // si no hay nombre, usamos el NIF como placeholder
        try {
            Integer existing = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM suppliers
                     WHERE company_id = ? AND tax_identifier = ?
                    """, Integer.class,
                    tenantContext.getCurrentCompanyId(), nif);
            if (existing != null && existing > 0) return;
            jdbcTemplate.update("""
                    INSERT INTO suppliers (
                        id, company_id, legal_name, tax_identifier, country, active
                    ) VALUES (?, ?, ?, ?, 'Espana', TRUE)
                    """,
                    UUID.randomUUID().toString(),
                    tenantContext.getCurrentCompanyId(),
                    name, nif);
        } catch (Exception ex) {
            // No interrumpimos el guardado de la factura si la creación
            // del supplier falla (p.ej. concurrencia, race con otro
            // INSERT). El supplier se podrá crear luego manualmente.
            System.err.println("[purchases] supplier no creado para "
                    + nif + ": " + ex.getMessage());
        }
    }

    // ====================================================================
    //  DTOs públicos
    // ====================================================================

    public record SaveRequest(
            String supplierNif,
            String supplierName,
            String invoiceNumber,
            LocalDate invoiceDate,
            BigDecimal baseAmount,
            BigDecimal vatPercent,
            BigDecimal vatAmount,
            BigDecimal totalAmount,
            String documentSha256,
            int invoiceIndexInPdf,
            String concept,
            String notes,
            // GAS-1: cuenta de gasto (6xx) fijada por el usuario. Si es NULL,
            // el asiento la resuelve por la cascada automática habitual.
            String expenseAccountCode,
            // GAS-7: TRUE solo en el alta MANUAL (gasto/recibo), para que el
            // asiento entre validado directo. PDF/cascada y recurrente lo
            // dejan en FALSE -> el asiento entra en "Por validar".
            boolean postJournalDirectly
    ) {
        // Constructor compacto retro-compatible: callers viejos sin concept,
        // ni expenseAccountCode, ni postJournalDirectly.
        public SaveRequest(String supplierNif, String supplierName,
                            String invoiceNumber, LocalDate invoiceDate,
                            BigDecimal baseAmount, BigDecimal vatPercent,
                            BigDecimal vatAmount, BigDecimal totalAmount,
                            String documentSha256, int invoiceIndexInPdf,
                            String notes) {
            this(supplierNif, supplierName, invoiceNumber, invoiceDate,
                    baseAmount, vatPercent, vatAmount, totalAmount,
                    documentSha256, invoiceIndexInPdf, null, notes, null, false);
        }
    }

    public record SaveResult(
            PurchaseInvoice invoice,
            boolean duplicate,
            String message
    ) {}

    /** GAS-2 — Petición de pago de un gasto (fecha + cuenta de banco 572). */
    public record PayRequest(LocalDate paymentDate, String bankAccountCode) {}

    public record BatchValidateRequest(java.util.List<String> ids) {}

    public record BatchValidateItem(
            String id, String status, String message, String journalEntryId
    ) {}

    public record BatchValidateResult(
            int total, int posted, int failed,
            java.util.List<BatchValidateItem> items
    ) {}

    /** Sin uso — sentinel para futuros campos calculados. */
    @SuppressWarnings("unused")
    private Map<String, Object> _sentinel() { return Map.of(); }
}
