package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * PAGO-PROVEEDOR (PV-2) — Vencimientos de factura + pago contra tesorería.
 *
 * <p>Cada factura (compra o venta) puede tener N vencimientos
 * ({@code invoice_due_dates}). Cada vencimiento se salda contra una cuenta de
 * tesorería (Banco 572 / Caja 570), generando el asiento:
 * <ul>
 *   <li>Compra: Debe 400 (proveedor) / Haber 572·570.</li>
 *   <li>Venta:  Debe 572·570 / Haber 430 (cliente).</li>
 * </ul>
 * Cubre los tres casos que pidió Benjamin: extracto bancario (vía
 * conciliación, PV-5), ticket y caja (pago directo aquí).
 *
 * <p>Tenant-scoped. El asiento se replica del patrón ya probado de
 * {@code BankMovementService} (cuentas por código/prefijo + ejercicio fiscal).
 */
@Service
public class PaymentScheduleService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final CurrentUserService currentUser;
    private final com.benjagest.backend.billing.reflection.CrossInvoiceReflectionService reflectionService;
    private final com.benjagest.backend.billing.tpb.BillingAgreementGuard billingAgreementGuard;

    public PaymentScheduleService(JdbcTemplate jdbc, TenantContext tenant,
                                    CurrentUserService currentUser,
                                    com.benjagest.backend.billing.reflection.CrossInvoiceReflectionService reflectionService,
                                    com.benjagest.backend.billing.tpb.BillingAgreementGuard billingAgreementGuard) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.currentUser = currentUser;
        this.reflectionService = reflectionService;
        this.billingAgreementGuard = billingAgreementGuard;
    }

    // ====================================================================
    //  Consulta + planificación
    // ====================================================================

    /** Vencimientos de una factura (crea uno por defecto si no tiene). */
    @Transactional
    public List<DueDate> listByInvoice(String invoiceKind, String invoiceId) {
        kindCheck(invoiceKind);
        ensureDefault(invoiceKind, invoiceId);
        return jdbc.query("""
                SELECT id, invoice_id, invoice_kind, seq, due_date, amount, status,
                       paid_date, payment_method, treasury_account_code,
                       journal_entry_id, notes
                  FROM invoice_due_dates
                 WHERE company_id = ? AND invoice_kind = ? AND invoice_id = ?
                 ORDER BY seq
                """, this::map, tenant.getCurrentCompanyId(), invoiceKind, invoiceId);
    }

    /**
     * Si la factura no tiene vencimientos, crea uno = total a la fecha de
     * factura (PENDING). Idempotente.
     */
    @Transactional
    public void ensureDefault(String invoiceKind, String invoiceId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM invoice_due_dates
                 WHERE company_id = ? AND invoice_kind = ? AND invoice_id = ?
                """, Integer.class, tenant.getCurrentCompanyId(), invoiceKind, invoiceId);
        if (n != null && n > 0) return;
        InvoiceInfo info = loadInvoice(invoiceKind, invoiceId);
        insertDueDate(invoiceKind, invoiceId, 1, info.date(), info.total(), null);
    }

    /**
     * Reemplaza el cuadro de vencimientos PENDIENTES de una factura. Valida
     * que la suma (incluidos los ya pagados) cuadre con el total de la factura.
     * No toca los vencimientos ya PAGADOS.
     */
    @Transactional
    public List<DueDate> replaceSchedule(String invoiceKind, String invoiceId,
                                           List<DueDateRequest> rows) {
        kindCheck(invoiceKind);
        billingAgreementGuard.requireAgreementOrOwnForInvoiceKind(invoiceKind);
        if (rows == null || rows.isEmpty()) throw bad("Indica al menos un vencimiento.");
        InvoiceInfo info = loadInvoice(invoiceKind, invoiceId);

        BigDecimal paidSum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM invoice_due_dates
                 WHERE company_id = ? AND invoice_kind = ? AND invoice_id = ? AND status = 'PAID'
                """, BigDecimal.class, tenant.getCurrentCompanyId(), invoiceKind, invoiceId);
        BigDecimal newSum = BigDecimal.ZERO;
        for (DueDateRequest r : rows) {
            if (r.amount() == null || r.amount().signum() <= 0) throw bad("Importe de vencimiento inválido.");
            if (r.dueDate() == null) throw bad("Falta la fecha de un vencimiento.");
            newSum = newSum.add(r.amount());
        }
        BigDecimal total = newSum.add(paidSum == null ? BigDecimal.ZERO : paidSum);
        if (total.subtract(info.total()).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw bad("La suma de vencimientos (" + total.toPlainString()
                    + ") no cuadra con el total de la factura (" + info.total().toPlainString() + ").");
        }

        // Borra solo los PENDIENTES y reinserta; conserva los pagados.
        jdbc.update("""
                DELETE FROM invoice_due_dates
                 WHERE company_id = ? AND invoice_kind = ? AND invoice_id = ? AND status = 'PENDING'
                """, tenant.getCurrentCompanyId(), invoiceKind, invoiceId);
        Integer maxSeq = jdbc.queryForObject("""
                SELECT COALESCE(MAX(seq), 0) FROM invoice_due_dates
                 WHERE company_id = ? AND invoice_kind = ? AND invoice_id = ?
                """, Integer.class, tenant.getCurrentCompanyId(), invoiceKind, invoiceId);
        int seq = maxSeq == null ? 0 : maxSeq;
        for (DueDateRequest r : rows) {
            insertDueDate(invoiceKind, invoiceId, ++seq, r.dueDate(), r.amount(), r.notes());
        }
        return listByInvoice(invoiceKind, invoiceId);
    }

    // ====================================================================
    //  Pago / cobro de un vencimiento
    // ====================================================================

    @Transactional
    public DueDate pay(String dueDateId, PayRequest req) {
        String companyId = tenant.getCurrentCompanyId();
        DueDate dd = getOne(dueDateId);
        billingAgreementGuard.requireAgreementOrOwnForInvoiceKind(dd.invoiceKind());
        if ("PAID".equals(dd.status())) throw bad("Este vencimiento ya está pagado.");
        if (req.treasuryAccountCode() == null || req.treasuryAccountCode().isBlank()) {
            throw bad("Indica la cuenta de tesorería (Banco 572 / Caja 570).");
        }
        LocalDate payDate = req.paidDate() == null ? LocalDate.now() : req.paidDate();

        String treasuryId = resolveAccount(companyId, req.treasuryAccountCode());
        if (treasuryId == null) throw bad("La cuenta de tesorería " + req.treasuryAccountCode() + " no existe.");
        String counterPrefix = "SALES".equals(dd.invoiceKind()) ? "430" : "400";
        String counterId = findAccountByPrefix(companyId, counterPrefix);
        if (counterId == null) throw bad("No se encontró cuenta " + counterPrefix + " de contrapartida.");

        String fiscalYearId = findFiscalYearId(companyId, payDate);
        if (fiscalYearId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No hay ejercicio fiscal abierto para " + payDate + ".");
        }
        String entryId = createPaymentEntry(companyId, fiscalYearId, dd,
                treasuryId, counterId, payDate, req.concept());

        jdbc.update("""
                UPDATE invoice_due_dates
                   SET status = 'PAID', paid_date = ?, payment_method = ?,
                       treasury_account_code = ?, journal_entry_id = ?
                 WHERE id = ? AND company_id = ?
                """, Date.valueOf(payDate), blank(req.paymentMethod()),
                req.treasuryAccountCode().trim(), entryId, dueDateId, companyId);
        String method = "570".equals(req.treasuryAccountCode().trim()) ? "EFECTIVO"
                : blank(req.paymentMethod());
        if ("SALES".equals(dd.invoiceKind())) {
            syncSalesPaymentStatus(dd.invoiceId());
            // REFLEJO-4a: el cobro del vencimiento de VENTA se refleja como pago del
            // gasto en el cliente (por validar). Tesorería 570 si caja/efectivo, 572 resto.
            reflectionService.reflectPayment(dd.invoiceId(), dd.amount(), payDate,
                    method, dueDateId, safeUserId());
        } else if ("PURCHASE".equals(dd.invoiceKind())) {
            // PAGO-1: proyectar a purchase_invoices.paid (si no, el gasto seguía
            // figurando como pendiente de pago en los listados).
            syncPurchasePaidFlag(dd.invoiceId());
            // REFLEJO-4b (inverso): si el gasto pagado es un reflejo, reflejar el
            // COBRO en los libros del emisor (la asesoría) — por validar.
            reflectionService.reflectCollection(dd.invoiceId(), dd.amount(), payDate,
                    method, dueDateId, safeUserId());
        }
        return getOne(dueDateId);
    }

    /**
     * PV-7 — Unificación: el estado de cobro de la factura de VENTA
     * (`sales_invoices.payment_status` + `paid_amount`) se deriva de la suma de
     * sus vencimientos PAGADOS, para que el cobro por plazos y el modelo de
     * cobro existente (listados, KPIs, conciliación) queden coherentes. Los
     * vencimientos son la fuente; aquí se proyecta a la factura. Best-effort.
     */
    private void syncSalesPaymentStatus(String invoiceId) {
        try {
            String companyId = tenant.getCurrentCompanyId();
            BigDecimal total = loadInvoice("SALES", invoiceId).total();
            BigDecimal paid = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(amount), 0) FROM invoice_due_dates
                     WHERE company_id = ? AND invoice_kind = 'SALES'
                       AND invoice_id = ? AND status = 'PAID'
                    """, BigDecimal.class, companyId, invoiceId);
            BigDecimal p = paid == null ? BigDecimal.ZERO : paid;
            String st = p.signum() <= 0 ? "PENDING"
                    : (p.add(new BigDecimal("0.01")).compareTo(total) >= 0 ? "PAID" : "PARTIAL");
            jdbc.update("""
                    UPDATE sales_invoices SET payment_status = ?, paid_amount = ?
                     WHERE id = ? AND company_id = ?
                    """, st, p, invoiceId, companyId);
        } catch (Exception ex) {
            // best-effort: el cobro cuenta por el asiento aunque no se proyecte.
        }
    }

    /**
     * PAGO-1 — Lo mismo que {@link #syncSalesPaymentStatus} pero para COMPRAS:
     * proyecta a {@code purchase_invoices.paid} el estado de sus vencimientos.
     *
     * <p>Sin esto había DOS verdades desincronizadas sobre si un gasto está
     * pagado: el flag {@code paid} (que escribe "Registrar pago", GAS-2) y los
     * vencimientos (que escribe este servicio, PV-1). Pagar por vencimientos
     * dejaba la factura con {@code paid = FALSE} para siempre, así que cualquier
     * listado de "pendientes de pago" mentía — y encima se podía volver a pagar
     * por el otro camino, duplicando el asiento.
     *
     * <p>Criterio: pagada cuando tiene vencimientos y TODOS están PAID (aquí no
     * hay pago parcial: {@code paid} es booleano). Best-effort, igual que en
     * ventas: el pago ya cuenta por su asiento aunque la proyección falle.
     */
    private void syncPurchasePaidFlag(String invoiceId) {
        try {
            String companyId = tenant.getCurrentCompanyId();
            Integer total = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM invoice_due_dates
                     WHERE company_id = ? AND invoice_kind = 'PURCHASE' AND invoice_id = ?
                    """, Integer.class, companyId, invoiceId);
            Integer pending = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM invoice_due_dates
                     WHERE company_id = ? AND invoice_kind = 'PURCHASE' AND invoice_id = ?
                       AND status <> 'PAID'
                    """, Integer.class, companyId, invoiceId);
            if (total == null || total == 0) {
                return; // sin vencimientos no hay nada que proyectar
            }
            boolean fullyPaid = pending != null && pending == 0;
            if (fullyPaid) {
                jdbc.update("""
                        UPDATE purchase_invoices
                           SET paid = TRUE,
                               paid_date = (SELECT MAX(paid_date) FROM invoice_due_dates
                                             WHERE company_id = ? AND invoice_kind = 'PURCHASE'
                                               AND invoice_id = ?),
                               payment_account_code = COALESCE(payment_account_code,
                                   (SELECT MAX(treasury_account_code) FROM invoice_due_dates
                                     WHERE company_id = ? AND invoice_kind = 'PURCHASE'
                                       AND invoice_id = ?))
                         WHERE id = ? AND company_id = ?
                        """, companyId, invoiceId, companyId, invoiceId, invoiceId, companyId);
            } else {
                jdbc.update("""
                        UPDATE purchase_invoices
                           SET paid = FALSE, paid_date = NULL
                         WHERE id = ? AND company_id = ?
                        """, invoiceId, companyId);
            }
        } catch (Exception ex) {
            // best-effort: el pago cuenta por el asiento aunque no se proyecte.
        }
    }

    /**
     * PV-5 — Marca como PAGADOS los vencimientos de una factura al CONCILIAR un
     * movimiento bancario con ella. NO genera asiento (la conciliación ya creó
     * el suyo, 400→572 / 572→430): solo vincula el vencimiento al movimiento y
     * al asiento existente. Marca los PENDIENTES más antiguos mientras el
     * importe del movimiento los cubra por completo (sin pago parcial de un
     * vencimiento). Best-effort: cualquier fallo se ignora (la conciliación
     * cuenta igual por el asiento).
     */
    @Transactional
    public void settleByBankMovement(String invoiceKind, String invoiceId,
                                       String bankMovementId, String journalEntryId,
                                       LocalDate paidDate, BigDecimal amount) {
        if (!"PURCHASE".equals(invoiceKind) && !"SALES".equals(invoiceKind)) return;
        ensureDefault(invoiceKind, invoiceId);
        List<DueDate> pend = jdbc.query("""
                SELECT id, invoice_id, invoice_kind, seq, due_date, amount, status,
                       paid_date, payment_method, treasury_account_code, journal_entry_id, notes
                  FROM invoice_due_dates
                 WHERE company_id = ? AND invoice_kind = ? AND invoice_id = ? AND status = 'PENDING'
                 ORDER BY seq
                """, this::map, tenant.getCurrentCompanyId(), invoiceKind, invoiceId);
        BigDecimal remaining = amount == null ? BigDecimal.ZERO : amount.abs();
        for (DueDate dd : pend) {
            if (remaining.add(new BigDecimal("0.01")).compareTo(dd.amount()) < 0) break;
            jdbc.update("""
                    UPDATE invoice_due_dates
                       SET status = 'PAID', paid_date = ?, payment_method = 'TRANSFER',
                           treasury_account_code = '572', journal_entry_id = ?,
                           bank_movement_id = ?
                     WHERE id = ? AND company_id = ?
                    """, paidDate == null ? null : Date.valueOf(paidDate), journalEntryId,
                    bankMovementId, dd.id(), tenant.getCurrentCompanyId());
            remaining = remaining.subtract(dd.amount());
            // REFLEJO-4: conciliación bancaria (siempre banco, 572). Best-effort.
            if ("SALES".equals(invoiceKind)) {
                // 4a: cobro de VENTA → pago del gasto en el cliente.
                reflectionService.reflectPayment(invoiceId, dd.amount(), paidDate,
                        "TRANSFER", dd.id(), safeUserId());
            } else if ("PURCHASE".equals(invoiceKind)) {
                // 4b: pago de un gasto reflejado → cobro en el emisor.
                reflectionService.reflectCollection(invoiceId, dd.amount(), paidDate,
                        "TRANSFER", dd.id(), safeUserId());
            }
        }
        if ("SALES".equals(invoiceKind)) {
            syncSalesPaymentStatus(invoiceId);
        } else if ("PURCHASE".equals(invoiceKind)) {
            syncPurchasePaidFlag(invoiceId); // PAGO-1
        }
    }

    /** Revierte el pago de un vencimiento (borra el asiento, vuelve a PENDING). */
    @Transactional
    public DueDate unpay(String dueDateId) {
        String companyId = tenant.getCurrentCompanyId();
        DueDate dd = getOne(dueDateId);
        billingAgreementGuard.requireAgreementOrOwnForInvoiceKind(dd.invoiceKind());
        if (!"PAID".equals(dd.status())) throw bad("Este vencimiento no está pagado.");
        if (dd.journalEntryId() != null) {
            jdbc.update("DELETE FROM journal_entry_lines WHERE journal_entry_id = ?", dd.journalEntryId());
            jdbc.update("DELETE FROM journal_entries WHERE id = ? AND company_id = ?",
                    dd.journalEntryId(), companyId);
        }
        jdbc.update("""
                UPDATE invoice_due_dates
                   SET status = 'PENDING', paid_date = NULL, payment_method = NULL,
                       treasury_account_code = NULL, journal_entry_id = NULL,
                       bank_movement_id = NULL
                 WHERE id = ? AND company_id = ?
                """, dueDateId, companyId);
        if ("SALES".equals(dd.invoiceKind())) {
            syncSalesPaymentStatus(dd.invoiceId());
            // REFLEJO-3b: deshacer el cobro de VENTA revierte el PAGO reflejado en el cliente.
            reflectionService.reflectUnpayPayment(dd.invoiceId(), dueDateId, safeUserId());
        } else if ("PURCHASE".equals(dd.invoiceKind())) {
            // PAGO-1: al deshacer, el gasto vuelve a contar como pendiente.
            syncPurchasePaidFlag(dd.invoiceId());
            // REFLEJO-3b: deshacer el pago de un gasto reflejado revierte el COBRO en el emisor.
            reflectionService.reflectUnpayCollection(dd.invoiceId(), dd.amount(), dueDateId, safeUserId());
        }
        return getOne(dueDateId);
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private String createPaymentEntry(String companyId, String fiscalYearId, DueDate dd,
                                        String treasuryId, String counterId, LocalDate payDate,
                                        String customConcept) {
        Integer max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0) FROM journal_entries
                 WHERE company_id = ? AND fiscal_year_id = ?
                """, Integer.class, companyId, fiscalYearId);
        int entryNumber = (max == null ? 0 : max) + 1;
        String entryId = UUID.randomUUID().toString();
        boolean sales = "SALES".equals(dd.invoiceKind());
        // COB-3 — concepto del asesor si lo escribió; si no, el automático de
        // siempre. journal_entries.concept es VARCHAR(240) (V2), de ahí el corte.
        String concept = (sales ? "Cobro vto. " : "Pago vto. ") + dd.seq();
        if (customConcept != null && !customConcept.isBlank()) {
            concept = customConcept.trim();
            if (concept.length() > 240) concept = concept.substring(0, 240);
        }

        jdbc.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'DUE_DATE_PAYMENT', ?, 'POSTED', FALSE, FALSE, ?)
                """, entryId, companyId, fiscalYearId, entryNumber,
                Date.valueOf(payDate), concept, dd.id(), safeUserId());

        BigDecimal amt = dd.amount();
        if (sales) {
            // Cobro: Debe tesorería / Haber 430
            insertLine(entryId, treasuryId, "Cobro", amt, BigDecimal.ZERO);
            insertLine(entryId, counterId, "Cobro cliente", BigDecimal.ZERO, amt);
        } else {
            // Pago: Debe 400 / Haber tesorería
            insertLine(entryId, counterId, "Pago proveedor", amt, BigDecimal.ZERO);
            insertLine(entryId, treasuryId, "Pago", BigDecimal.ZERO, amt);
        }
        return entryId;
    }

    private void insertLine(String entryId, String accountId, String desc,
                              BigDecimal debit, BigDecimal credit) {
        jdbc.update("""
                INSERT INTO journal_entry_lines (id, journal_entry_id, account_id, description, debit, credit)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), entryId, accountId, desc, debit, credit);
    }

    private void insertDueDate(String kind, String invoiceId, int seq,
                                 LocalDate dueDate, BigDecimal amount, String notes) {
        jdbc.update("""
                INSERT INTO invoice_due_dates (
                    id, company_id, invoice_id, invoice_kind, seq, due_date, amount, status, notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, UUID.randomUUID().toString(), tenant.getCurrentCompanyId(),
                invoiceId, kind, seq, Date.valueOf(dueDate), amount, blank(notes));
    }

    private DueDate getOne(String id) {
        List<DueDate> rows = jdbc.query("""
                SELECT id, invoice_id, invoice_kind, seq, due_date, amount, status,
                       paid_date, payment_method, treasury_account_code, journal_entry_id, notes
                  FROM invoice_due_dates
                 WHERE id = ? AND company_id = ?
                """, this::map, id, tenant.getCurrentCompanyId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vencimiento no encontrado.");
        return rows.get(0);
    }

    private record InvoiceInfo(BigDecimal total, LocalDate date) {}

    private InvoiceInfo loadInvoice(String kind, String invoiceId) {
        String companyId = tenant.getCurrentCompanyId();
        String sql = "SALES".equals(kind)
                ? "SELECT total AS t, invoice_date AS d FROM sales_invoices WHERE id = ? AND company_id = ?"
                : "SELECT total_amount AS t, invoice_date AS d FROM purchase_invoices WHERE id = ? AND company_id = ?";
        List<InvoiceInfo> rows = jdbc.query(sql, (rs, n) -> {
            BigDecimal t = rs.getBigDecimal("t");
            Date d = rs.getDate("d");
            return new InvoiceInfo(t == null ? BigDecimal.ZERO : t,
                    d == null ? LocalDate.now() : d.toLocalDate());
        }, invoiceId, companyId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura no encontrada.");
        return rows.get(0);
    }

    /** Resuelve cuenta por código exacto y, si no, por prefijo (subcuenta). */
    private String resolveAccount(String companyId, String code) {
        String c = code.trim();
        List<String> ids = jdbc.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND code = ? AND active = TRUE LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, c);
        if (!ids.isEmpty()) return ids.get(0);
        return findAccountByPrefix(companyId, c);
    }

    private String findAccountByPrefix(String companyId, String prefix) {
        List<String> ids = jdbc.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE AND code LIKE ?
                 ORDER BY LENGTH(code), code LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, prefix + "%");
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String findFiscalYearId(String companyId, LocalDate date) {
        List<String> ids = jdbc.query("""
                SELECT id FROM fiscal_years
                 WHERE company_id = ? AND start_date <= ? AND end_date >= ? LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, Date.valueOf(date), Date.valueOf(date));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private DueDate map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Date pd = rs.getDate("paid_date");
        Date du = rs.getDate("due_date");
        return new DueDate(
                rs.getString("id"), rs.getString("invoice_id"), rs.getString("invoice_kind"),
                rs.getInt("seq"), du == null ? null : du.toLocalDate(),
                rs.getBigDecimal("amount"), rs.getString("status"),
                pd == null ? null : pd.toLocalDate(),
                rs.getString("payment_method"), rs.getString("treasury_account_code"),
                rs.getString("journal_entry_id"), rs.getString("notes"));
    }

    private void kindCheck(String kind) {
        if (!"PURCHASE".equals(kind) && !"SALES".equals(kind)) {
            throw bad("invoiceKind debe ser PURCHASE o SALES.");
        }
    }

    private String safeUserId() {
        try { return currentUser.require().userId(); } catch (Exception ex) { return null; }
    }

    private static String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // ====================================================================
    //  DTOs
    // ====================================================================

    public record DueDate(
            String id, String invoiceId, String invoiceKind, int seq,
            LocalDate dueDate, BigDecimal amount, String status,
            LocalDate paidDate, String paymentMethod, String treasuryAccountCode,
            String journalEntryId, String notes
    ) {}

    public record DueDateRequest(LocalDate dueDate, BigDecimal amount, String notes) {}

    /**
     * COB-3 — {@code concept} es OPCIONAL: si viene en blanco (o el cliente es
     * antiguo y no lo manda), el asiento sigue llevando el concepto automático
     * de siempre ("Cobro vto. N" / "Pago vto. N"). Petición de Benjamin
     * (2026-09-05): el asesor necesita poder decir QUÉ está cobrando.
     */
    public record PayRequest(String treasuryAccountCode, LocalDate paidDate,
                             String paymentMethod, String concept) {}
}
