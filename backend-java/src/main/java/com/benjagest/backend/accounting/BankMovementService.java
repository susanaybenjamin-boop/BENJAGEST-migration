package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
 * Movimientos bancarios. Cada movimiento puede:
 *
 * <ul>
 *   <li><b>Quedar UNRECONCILED</b>: dato del extracto sin enlazar a nada.</li>
 *   <li><b>MATCHED</b>: enlazado a factura emitida o recibida pendiente.
 *       Auto-genera asiento: Debe 572/Haber 430 (cobro) o Debe 400/Haber 572 (pago).</li>
 *   <li><b>POSTED</b>: con asiento posteado.</li>
 *   <li><b>IGNORED</b>: el asesor decide no contabilizar (comisión &lt; X, etc.).</li>
 * </ul>
 *
 * <p>Convención de signo: {@code amount > 0} = ingreso en la cuenta
 * bancaria; {@code amount < 0} = cargo. El asiento se monta según el
 * signo y el tipo de factura enlazada.
 */
@Service
public class BankMovementService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(BankMovementService.class);

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final BankAccountService bankAccounts;
    private final FiscalYearGuardService fiscalGuard;
    private final CurrentUserService currentUserService;
    private final PaymentScheduleService paymentSchedule;
    /** CONTA-1 — cuenta de tercero real de la factura (no la generica). */
    private final InvoiceCounterpartyAccountResolver counterpartyResolver;

    public BankMovementService(JdbcTemplate jdbcTemplate, TenantContext tenantContext,
                                 BankAccountService bankAccounts,
                                 FiscalYearGuardService fiscalGuard,
                                 CurrentUserService currentUserService,
                                 PaymentScheduleService paymentSchedule,
                                 InvoiceCounterpartyAccountResolver counterpartyResolver) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.bankAccounts = bankAccounts;
        this.fiscalGuard = fiscalGuard;
        this.currentUserService = currentUserService;
        this.paymentSchedule = paymentSchedule;
        this.counterpartyResolver = counterpartyResolver;
    }

    // ====================================================================
    //  Crear movimientos
    // ====================================================================

    @Transactional
    public BankMovementView createManual(MovementRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        String companyId = tenantContext.getCurrentCompanyId();
        jdbcTemplate.update("""
                INSERT INTO bank_movements (
                    id, company_id, bank_account_id,
                    operation_date, value_date,
                    description, counterparty_name, counterparty_nif,
                    amount, balance_after, external_ref,
                    import_batch_id, status, notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'UNRECONCILED', ?)
                """,
                id, companyId, req.bankAccountId(),
                Date.valueOf(req.operationDate()),
                req.valueDate() == null ? null : Date.valueOf(req.valueDate()),
                truncate(req.description(), 240),
                blank(req.counterpartyName()),
                normalizeNif(req.counterpartyNif()),
                req.amount(), req.balanceAfter(), blank(req.externalRef()),
                blank(req.notes()));
        return get(id);
    }

    @Transactional
    public BankMovementView linkToInvoice(String movementId, LinkRequest req) {
        BankMovementView m = get(movementId);
        if (req.invoiceKind() == null || req.invoiceId() == null) {
            throw bad("Hay que indicar invoiceKind y invoiceId.");
        }
        if (!"SALES".equals(req.invoiceKind()) && !"PURCHASE".equals(req.invoiceKind())) {
            throw bad("invoiceKind debe ser SALES o PURCHASE.");
        }
        // BANK-DUP-4 (2026-07-16) — cinturón: un movimiento YA contabilizado no
        // genera un segundo asiento. Esto no iba a ningún sitio: no había NADA
        // entre la entrada y el INSERT (ni un if, ni un SELECT), y la BD tampoco
        // avisa porque no existe UNIQUE sobre (source_type, source_id). Con que
        // el usuario pulse "Conciliar" dos veces, o autoReconcileBatch pase dos
        // veces por el mismo movimiento, ya hay duplicado.
        //
        // Es idempotencia, no una validación "por si acaso": el hecho económico
        // (un apunte del extracto) ya está contabilizado; volver a pedirlo
        // devuelve lo que hay, no fabrica otro. Compras ya lo hace así
        // (PurchaseJournalEntryService busca por source_type+source_id antes de
        // insertar); este camino se quedó sin ello.
        if (m.journalEntryId() != null && !m.journalEntryId().isBlank()) {
            log.info("BANK-DUP-4: el movimiento {} ya tiene el asiento {}; no creo otro.",
                    movementId, m.journalEntryId());
            return m;
        }
        fiscalGuard.requireOpenForDate(m.operationDate(), "contabilizar este cobro/pago");

        String companyId = tenantContext.getCurrentCompanyId();
        String userId = safeUserId();

        // Resolver la cuenta contable bancaria (572).
        String acc572 = bankAccounts.resolveAccountingAccountId(m.bankAccountId());
        if (acc572 == null) {
            throw bad("La cuenta bancaria no tiene cuenta contable 572 asignada y no existe una 572 genérica.");
        }

        // Cuenta contrapartida: 430 (clientes) si SALES, 400 (proveedores) si PURCHASE.
        String contrapartida = req.counterpartyAccountId();
        if (contrapartida == null || contrapartida.isBlank()) {
            // CONTA-1 — la cuenta del TERCERO que uso la factura, no la
            // generica. Antes se buscaba solo por prefijo y el asiento de
            // conciliacion saldaba la 400/430 generica mientras la factura habia
            // cargado la subcuenta del proveedor/cliente: el tercero se quedaba
            // con saldo y la generica acumulaba el contrario.
            contrapartida = counterpartyResolver.resolve(
                    companyId, req.invoiceKind(), req.invoiceId());
            if (contrapartida == null) {
                throw bad("No se encontró cuenta "
                        + InvoiceCounterpartyAccountResolver.genericPrefix(req.invoiceKind())
                        + " para contrapartida.");
            }
        }

        // Generar asiento contable.
        String fiscalYearId = findFiscalYearId(companyId, m.operationDate());
        if (fiscalYearId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No hay ejercicio fiscal abierto para " + m.operationDate());
        }
        String entryId = createBankEntry(companyId, fiscalYearId, m,
                acc572, contrapartida, req.invoiceKind(), userId);

        // Actualizar el movimiento.
        jdbcTemplate.update("""
                UPDATE bank_movements
                   SET linked_invoice_id = ?, linked_invoice_kind = ?,
                       journal_entry_id = ?, status = 'POSTED'
                 WHERE id = ? AND company_id = ?
                """,
                req.invoiceId(), req.invoiceKind(), entryId,
                movementId, companyId);

        // Actualizar status de la factura si está disponible.
        updateInvoicePaymentStatus(req.invoiceKind(), req.invoiceId(), m);

        // PV-5 — marcar el/los vencimiento(s) de la factura como pagados por
        // esta conciliación (sin generar asiento: ya lo creó el cobro/pago).
        try {
            paymentSchedule.settleByBankMovement(req.invoiceKind(), req.invoiceId(),
                    movementId, entryId, m.operationDate(), m.amount());
        } catch (Exception ex) {
            // Best-effort: la conciliación cuenta igual por el asiento.
        }

        return get(movementId);
    }

    @Transactional
    public BankMovementView ignore(String movementId, String reason) {
        // CONCAT, no '||': en MariaDB '||' es OR lógico (salvo PIPES_AS_CONCAT) y
        // en modo estricto revienta con "Truncated incorrect DOUBLE value".
        jdbcTemplate.update("""
                UPDATE bank_movements
                   SET status = 'IGNORED', notes = CONCAT(COALESCE(notes, ''), ?)
                 WHERE id = ? AND company_id = ? AND status = 'UNRECONCILED'
                """, "\n[IGNORADO] " + (reason == null ? "" : reason),
                movementId, tenantContext.getCurrentCompanyId());
        return get(movementId);
    }

    // ====================================================================
    //  Consulta
    // ====================================================================

    public List<BankMovementView> list(String bankAccountId, String status,
                                         LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, company_id, bank_account_id, operation_date, value_date,
                       description, counterparty_name, counterparty_nif,
                       amount, balance_after, external_ref,
                       import_batch_id, journal_entry_id,
                       linked_invoice_id, linked_invoice_kind,
                       status, notes, created_at, updated_at
                  FROM bank_movements
                 WHERE company_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (bankAccountId != null) { sql.append(" AND bank_account_id = ?"); args.add(bankAccountId); }
        if (status != null && !status.isBlank()) { sql.append(" AND status = ?"); args.add(status); }
        if (from != null) { sql.append(" AND operation_date >= ?"); args.add(Date.valueOf(from)); }
        if (to != null)   { sql.append(" AND operation_date <= ?"); args.add(Date.valueOf(to)); }
        sql.append(" ORDER BY operation_date DESC, created_at DESC");
        return jdbcTemplate.query(sql.toString(), this::mapRow, args.toArray());
    }

    public BankMovementView get(String id) {
        List<BankMovementView> rows = jdbcTemplate.query("""
                SELECT id, company_id, bank_account_id, operation_date, value_date,
                       description, counterparty_name, counterparty_nif,
                       amount, balance_after, external_ref,
                       import_batch_id, journal_entry_id,
                       linked_invoice_id, linked_invoice_kind,
                       status, notes, created_at, updated_at
                  FROM bank_movements
                 WHERE id = ? AND company_id = ?
                """, this::mapRow, id, tenantContext.getCurrentCompanyId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Movimiento no encontrado");
        }
        return rows.get(0);
    }

    /**
     * Sugiere facturas candidatas para reconciliar un movimiento bancario
     * basándose en importe (±1€) y rango de fecha (±15 días).
     */
    public List<MatchCandidate> suggestMatches(String movementId) {
        BankMovementView m = get(movementId);
        String companyId = tenantContext.getCurrentCompanyId();
        BigDecimal absAmount = m.amount().abs();
        BigDecimal lower = absAmount.subtract(new BigDecimal("1.00"));
        BigDecimal upper = absAmount.add(new BigDecimal("1.00"));
        LocalDate from = m.operationDate().minusDays(15);
        LocalDate to = m.operationDate().plusDays(15);

        List<MatchCandidate> out = new ArrayList<>();
        if (m.amount().signum() > 0) {
            // Ingreso → buscar facturas emitidas pendientes de cobro.
            out.addAll(jdbcTemplate.query("""
                    SELECT id AS invoice_id, invoice_number, customer_legal_name AS counterparty,
                           total_amount AS amount, invoice_date,
                           'SALES' AS kind
                      FROM sales_invoices
                     WHERE company_id = ?
                       AND status IN ('VALIDATED','PARTIAL','OVERDUE')
                       AND total_amount BETWEEN ? AND ?
                       AND invoice_date BETWEEN ? AND ?
                     ORDER BY invoice_date DESC
                     LIMIT 5
                    """, this::mapCandidate,
                    companyId, lower, upper, Date.valueOf(from), Date.valueOf(to)));
        } else {
            // Cargo → buscar facturas recibidas PENDIENTES DE PAGO.
            //
            // BANK-DUP-1 (2026-07-16): el comentario decía "pendientes de pago"
            // y la consulta NO filtraba por pago — devolvía también las YA
            // PAGADAS. Y como autoReconcileBatch (BankImportService) concilia
            // SOLO cuando hay exactamente 1 candidato, un gasto ya pagado con
            // su cargo en el extracto se contabilizaba OTRA VEZ, sin que nadie
            // tocara nada. Ese es el "el gasto del autónomo me lo duplica".
            //
            // La columna `paid` la añadió la V167 LITERALMENTE "para impedir
            // pagar dos veces el mismo gasto" y aquí no se leía. Ojo: `paid` la
            // escribe "Registrar pago" (GAS-2), que NO deja rastro en
            // invoice_due_dates ni en bank_movements — por eso no basta con
            // mirar los pagos existentes (ver existingPaymentsFor).
            //
            // Se añade también status <> 'VOID': la rama de ventas de aquí al
            // lado sí filtra por estado y ésta no, así que una factura ANULADA
            // podía auto-conciliarse.
            out.addAll(jdbcTemplate.query("""
                    SELECT id AS invoice_id, invoice_number,
                           COALESCE(supplier_name, supplier_nif) AS counterparty,
                           total_amount AS amount, invoice_date,
                           'PURCHASE' AS kind
                      FROM purchase_invoices
                     WHERE company_id = ?
                       AND status <> 'VOID'
                       AND paid = FALSE
                       AND total_amount BETWEEN ? AND ?
                       AND invoice_date BETWEEN ? AND ?
                     ORDER BY invoice_date DESC
                     LIMIT 5
                    """, this::mapCandidate,
                    companyId, lower, upper, Date.valueOf(from), Date.valueOf(to)));
        }
        return out;
    }

    // ====================================================================
    //  F1-BANCO-REVIEW — revisión de conciliación (checkbox + estado + pago existente)
    // ====================================================================

    /**
     * Para cada movimiento UNRECONCILED de la cuenta, calcula la factura
     * candidata (por importe ±1€ y fecha ±15 días, según el signo), su ESTADO
     * (sin cobrar/pagar, ya cobrada/pagada, borrador, sin candidata) y, si ya
     * está saldada, los PAGOS EXISTENTES de esa factura (para enseñarlos debajo
     * como prueba y NO duplicar). Solo se auto-marcan (suggested=true) los que
     * están pendientes y son conciliables.
     */
    public List<ReconcileRow> reconcileReview(String bankAccountId) {
        String companyId = tenantContext.getCurrentCompanyId();
        List<BankMovementView> movs = list(bankAccountId, "UNRECONCILED", null, null);
        List<ReconcileRow> out = new ArrayList<>();
        for (BankMovementView m : movs) {
            Candidate c = findBestCandidate(companyId, m);
            if (c == null) {
                out.add(new ReconcileRow(m.id(), m.operationDate(), m.description(),
                        m.counterpartyName(), m.amount(),
                        null, null, null, null, null, null,
                        "NONE", false, List.of()));
                continue;
            }
            List<ExistingPayment> pays = existingPaymentsFor(companyId, c.kind, c.invoiceId);
            boolean settled = !pays.isEmpty()
                    || "PAID".equals(c.paymentStatus)
                    || (c.paidAmount != null && c.amount != null
                        && c.amount.signum() > 0 && c.paidAmount.compareTo(c.amount) >= 0);
            String state;
            boolean suggested;
            if ("SALES".equals(c.kind) && "DRAFT".equals(c.status)) {
                state = "DRAFT";
                suggested = false;
            } else if (settled) {
                state = "SALES".equals(c.kind) ? "SETTLED_SALES" : "SETTLED_PURCHASE";
                suggested = false;
            } else {
                state = "SALES".equals(c.kind) ? "PENDING_SALES" : "PENDING_PURCHASE";
                suggested = true;
            }
            out.add(new ReconcileRow(m.id(), m.operationDate(), m.description(),
                    m.counterpartyName(), m.amount(),
                    c.kind, c.invoiceId, c.invoiceNumber, c.counterparty,
                    c.amount, c.invoiceDate,
                    state, suggested, pays));
        }
        return out;
    }

    /** Mejor factura candidata para un movimiento (por signo, importe y fecha). */
    private Candidate findBestCandidate(String companyId, BankMovementView m) {
        BigDecimal abs = m.amount().abs();
        BigDecimal lower = abs.subtract(new BigDecimal("1.00"));
        BigDecimal upper = abs.add(new BigDecimal("1.00"));
        Date from = Date.valueOf(m.operationDate().minusDays(15));
        Date to = Date.valueOf(m.operationDate().plusDays(15));
        Date op = Date.valueOf(m.operationDate());
        List<Candidate> found;
        if (m.amount().signum() > 0) {
            found = jdbcTemplate.query("""
                    SELECT i.id, i.invoice_number, c.legal_name AS counterparty,
                           i.total AS amount, i.invoice_date, i.status, i.payment_status,
                           i.paid_amount
                      FROM sales_invoices i
                      LEFT JOIN customers c ON c.id = i.customer_id
                     WHERE i.company_id = ? AND i.status <> 'VOIDED'
                       AND i.total BETWEEN ? AND ?
                       AND i.invoice_date BETWEEN ? AND ?
                     ORDER BY ABS(i.total - ?) ASC, ABS(DATEDIFF(i.invoice_date, ?)) ASC
                     LIMIT 1
                    """, (rs, n) -> new Candidate("SALES",
                            rs.getString("id"), rs.getString("invoice_number"),
                            rs.getString("counterparty"), rs.getBigDecimal("amount"),
                            rs.getDate("invoice_date").toLocalDate(),
                            rs.getString("status"), rs.getString("payment_status"),
                            rs.getBigDecimal("paid_amount")),
                    companyId, lower, upper, from, to, abs, op);
        } else {
            // BANK-DUP-2 (2026-07-16). El comentario que había aquí decía:
            //   "purchase_invoices (V39/V40) NO tiene payment_status ni
            //    paid_amount: el ya pagada de una compra se deriva de sus
            //    pagos existentes".
            // Era FALSO desde la V167, MUY posterior a la V39/V40: añadió
            // `paid`/`paid_date`/`payment_account_code` y su propio comentario
            // dice que es "para impedir pagar dos veces el mismo gasto". Quien
            // escribió esto miró el CREATE TABLE y no la última migración que
            // toca la tabla — el caso exacto de la §10.bis del CLAUDE.md.
            //
            // Consecuencia: se pasaba `null` como paymentStatus, así que las
            // TRES condiciones de `settled` (arriba) salían false para toda
            // compra → estado PENDING_PURCHASE → suggested=true → checkbox
            // PREMARCADO sobre un gasto ya pagado → pago duplicado al conciliar.
            found = jdbcTemplate.query("""
                    SELECT id, invoice_number, COALESCE(supplier_name, supplier_nif) AS counterparty,
                           total_amount AS amount, invoice_date, status, paid
                      FROM purchase_invoices
                     WHERE company_id = ? AND status <> 'VOID'
                       AND total_amount BETWEEN ? AND ?
                       AND invoice_date BETWEEN ? AND ?
                     ORDER BY ABS(total_amount - ?) ASC, ABS(DATEDIFF(invoice_date, ?)) ASC
                     LIMIT 1
                    """, (rs, n) -> new Candidate("PURCHASE",
                            rs.getString("id"), rs.getString("invoice_number"),
                            rs.getString("counterparty"), rs.getBigDecimal("amount"),
                            rs.getDate("invoice_date").toLocalDate(),
                            rs.getString("status"),
                            // Reusa el "PAID" que `settled` ya sabe interpretar.
                            rs.getBoolean("paid") ? "PAID" : null, null),
                    companyId, lower, upper, from, to, abs, op);
        }
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Pagos ya contabilizados de una factura, unificando las tres vías del
     * sistema: vencimientos saldados, cobros de venta (pago múltiple) y
     * movimientos bancarios ya conciliados. Deduplicado por fecha+importe.
     */
    private List<ExistingPayment> existingPaymentsFor(String companyId, String kind, String invoiceId) {
        java.util.LinkedHashMap<String, ExistingPayment> byKey = new java.util.LinkedHashMap<>();
        java.util.function.BiConsumer<String, ExistingPayment> put = (k, p) -> byKey.putIfAbsent(k, p);

        // 1) Vencimientos saldados (compras y ventas).
        for (ExistingPayment p : jdbcTemplate.query("""
                SELECT dd.paid_date, dd.amount, dd.payment_method,
                       je.entry_number, je.concept
                  FROM invoice_due_dates dd
                  LEFT JOIN journal_entries je ON je.id = dd.journal_entry_id
                 WHERE dd.company_id = ? AND dd.invoice_kind = ? AND dd.invoice_id = ?
                   AND dd.status = 'PAID'
                 ORDER BY dd.paid_date
                """, (rs, n) -> new ExistingPayment("DUE_DATE",
                        rs.getDate("paid_date") == null ? null : rs.getDate("paid_date").toLocalDate(),
                        rs.getBigDecimal("amount"), rs.getString("payment_method"),
                        (Integer) rs.getObject("entry_number"), rs.getString("concept")),
                companyId, kind, invoiceId)) {
            put.accept(key(p), p);
        }

        // 2) Cobros de venta registrados (pago múltiple / manual).
        if ("SALES".equals(kind)) {
            for (ExistingPayment p : jdbcTemplate.query("""
                    SELECT payment_date, amount, payment_method, reference
                      FROM sales_invoice_payments
                     WHERE invoice_id = ?
                     ORDER BY payment_date
                    """, (rs, n) -> new ExistingPayment("SALES_PAYMENT",
                            rs.getDate("payment_date") == null ? null : rs.getDate("payment_date").toLocalDate(),
                            rs.getBigDecimal("amount"), rs.getString("payment_method"),
                            null, rs.getString("reference")),
                    invoiceId)) {
                put.accept(key(p), p);
            }
        }

        // 2.bis) BANK-DUP-3 (2026-07-16) — el pago de "Registrar pago" (GAS-2).
        //
        // Faltaba la CUARTA vía de pago del sistema. GAS-2 no escribe en
        // invoice_due_dates ni en bank_movements: solo hace
        //   UPDATE purchase_invoices SET paid, paid_date, payment_account_code
        // (ver PurchaseInvoiceRepository.markPaid). Así que un gasto pagado por
        // ahí salía aquí con CERO pagos y la pantalla decía "pendiente".
        //
        // No es hipotético: el script fix-duplicate-ss-autonomo.sql (9-jul)
        // mandaba textualmente "MARCAR PAGADAS las 6 facturas recurrentes de SS
        // desde la app (Registrar pago → banco → fecha fin de mes)". Esos 6
        // pagos son invisibles para las 3 consultas de arriba, y al importar el
        // extracto se contabilizaron otra vez: los 6 duplicados que Benjamin
        // estaba anulando a mano.
        if ("PURCHASE".equals(kind)) {
            for (ExistingPayment p : jdbcTemplate.query("""
                    SELECT paid_date, total_amount, payment_account_code
                      FROM purchase_invoices
                     WHERE company_id = ? AND id = ? AND paid = TRUE
                    """, (rs, n) -> new ExistingPayment("REGISTERED",
                            rs.getDate("paid_date") == null ? null : rs.getDate("paid_date").toLocalDate(),
                            rs.getBigDecimal("total_amount"),
                            rs.getString("payment_account_code"), null, null),
                    companyId, invoiceId)) {
                put.accept(key(p), p);
            }
        }

        // 3) Movimientos bancarios ya conciliados contra esta factura.
        for (ExistingPayment p : jdbcTemplate.query("""
                SELECT bm.operation_date, bm.amount, bm.description, je.entry_number
                  FROM bank_movements bm
                  LEFT JOIN journal_entries je ON je.id = bm.journal_entry_id
                 WHERE bm.company_id = ? AND bm.linked_invoice_kind = ?
                   AND bm.linked_invoice_id = ?
                 ORDER BY bm.operation_date
                """, (rs, n) -> new ExistingPayment("BANK",
                        rs.getDate("operation_date") == null ? null : rs.getDate("operation_date").toLocalDate(),
                        rs.getBigDecimal("amount") == null ? null : rs.getBigDecimal("amount").abs(),
                        null, (Integer) rs.getObject("entry_number"), rs.getString("description")),
                companyId, kind, invoiceId)) {
            put.accept(key(p), p);
        }
        return new ArrayList<>(byKey.values());
    }

    private static String key(ExistingPayment p) {
        return (p.payDate() == null ? "?" : p.payDate().toString()) + "|"
                + (p.payAmount() == null ? "?" : p.payAmount().stripTrailingZeros().toPlainString());
    }

    private record Candidate(
            String kind, String invoiceId, String invoiceNumber, String counterparty,
            BigDecimal amount, LocalDate invoiceDate, String status, String paymentStatus,
            BigDecimal paidAmount) {}

    /**
     * Un pago ya registrado de la factura candidata, sea cual sea la vía.
     *
     * <p><b>{@code paySource} es una CLAVE, no un texto.</b> Valores:
     * {@code DUE_DATE} (vencimiento saldado), {@code SALES_PAYMENT} (cobro de
     * venta), {@code REGISTERED} ("Registrar pago" / GAS-2) y {@code BANK}
     * (movimiento ya conciliado). La UI los traduce con
     * {@code bank.reconcile.paysource.*}.
     *
     * <p>Antes venían en español desde aquí ("Vencimiento", "Cobro", "Banco") y
     * la UI los pintaba crudos: en inglés se leía "Vencimiento". Al añadir
     * REGISTERED (BANK-DUP-3) se pasan los cuatro a clave, que es lo que manda
     * la §4 del CLAUDE.md. Si añades un valor aquí, añade su par ES+EN.
     */
    public record ExistingPayment(
            String paySource, LocalDate payDate, BigDecimal payAmount,
            String payMethod, Integer payEntryNumber, String payReference) {}

    public record ReconcileRow(
            String movementId, LocalDate operationDate, String description,
            String counterpartyName, BigDecimal amount,
            String invoiceKind, String invoiceId, String invoiceNumber,
            String invoiceCounterparty, BigDecimal invoiceAmount, LocalDate invoiceDate,
            String state, boolean suggested, List<ExistingPayment> existingPayments) {}

    public record ReconcileSelection(
            String movementId, String invoiceKind, String invoiceId) {}

    public record ReconcileResult(int reconciled, int failed, List<String> errors) {}

    // ====================================================================
    //  Auto-asiento de cobro/pago
    // ====================================================================

    private String createBankEntry(String companyId, String fiscalYearId,
                                     BankMovementView m,
                                     String acc572, String contrapartida,
                                     String invoiceKind, String userId) {
        Integer max = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0)
                  FROM journal_entries
                 WHERE company_id = ? AND fiscal_year_id = ?
                """, Integer.class, companyId, fiscalYearId);
        int entryNumber = (max == null ? 0 : max) + 1;

        String entryId = UUID.randomUUID().toString();
        String concept = "SALES".equals(invoiceKind)
                ? "Cobro " + safe(m.counterpartyName())
                : "Pago " + safe(m.counterpartyName());
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'BANK_MOVEMENT', ?, 'POSTED', FALSE, FALSE, ?)
                """,
                entryId, companyId, fiscalYearId, entryNumber,
                Date.valueOf(m.operationDate()),
                truncate(concept, 240),
                m.id(), userId);

        BigDecimal abs = m.amount().abs();
        if (m.amount().signum() > 0) {
            // Ingreso: Debe 572 / Haber 430
            insertLine(entryId, acc572, "Cobro factura " + safe(m.description()), abs, BigDecimal.ZERO);
            insertLine(entryId, contrapartida, "Cobrado " + safe(m.counterpartyName()), BigDecimal.ZERO, abs);
        } else {
            // Cargo: Debe 400 / Haber 572
            insertLine(entryId, contrapartida, "Pagado " + safe(m.counterpartyName()), abs, BigDecimal.ZERO);
            insertLine(entryId, acc572, "Pago factura " + safe(m.description()), BigDecimal.ZERO, abs);
        }
        return entryId;
    }

    private void insertLine(String entryId, String accountId, String desc,
                              BigDecimal debit, BigDecimal credit) {
        jdbcTemplate.update("""
                INSERT INTO journal_entry_lines (
                    id, journal_entry_id, account_id, description, debit, credit
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entryId, accountId,
                truncate(desc, 240), debit, credit);
    }

    private void updateInvoicePaymentStatus(String kind, String invoiceId, BankMovementView m) {
        try {
            if ("SALES".equals(kind)) {
                jdbcTemplate.update("""
                        UPDATE sales_invoices SET status = 'PAID', paid_at = CURRENT_TIMESTAMP
                         WHERE id = ? AND company_id = ?
                        """, invoiceId, tenantContext.getCurrentCompanyId());
            }
            // purchases no tiene status PAID actualmente (es POSTED desde V40).
        } catch (Exception ex) {
            // Best-effort: si la columna paid_at no existe en algún entorno,
            // el cobro se cuenta igual via el asiento.
        }
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private String findAccountByPrefix(String companyId, String prefix) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE AND code LIKE ?
                 ORDER BY LENGTH(code), code
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, prefix + "%");
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String findFiscalYearId(String companyId, LocalDate date) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM fiscal_years
                 WHERE company_id = ?
                   AND start_date <= ? AND end_date >= ?
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"),
                companyId, Date.valueOf(date), Date.valueOf(date));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void validate(MovementRequest req) {
        if (req.bankAccountId() == null) throw bad("Falta cuenta bancaria.");
        if (req.operationDate() == null) throw bad("Falta fecha operación.");
        if (req.amount() == null || req.amount().signum() == 0) throw bad("Importe inválido.");
    }

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    private BankMovementView mapRow(ResultSet rs, int n) throws SQLException {
        java.sql.Timestamp ca = rs.getTimestamp("created_at");
        java.sql.Timestamp ua = rs.getTimestamp("updated_at");
        java.sql.Date vd = rs.getDate("value_date");
        return new BankMovementView(
                rs.getString("id"), rs.getString("company_id"),
                rs.getString("bank_account_id"),
                rs.getDate("operation_date").toLocalDate(),
                vd == null ? null : vd.toLocalDate(),
                rs.getString("description"),
                rs.getString("counterparty_name"), rs.getString("counterparty_nif"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("balance_after"),
                rs.getString("external_ref"), rs.getString("import_batch_id"),
                rs.getString("journal_entry_id"),
                rs.getString("linked_invoice_id"), rs.getString("linked_invoice_kind"),
                rs.getString("status"), rs.getString("notes"),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant());
    }

    private MatchCandidate mapCandidate(ResultSet rs, int n) throws SQLException {
        return new MatchCandidate(
                rs.getString("invoice_id"), rs.getString("invoice_number"),
                rs.getString("counterparty"), rs.getBigDecimal("amount"),
                rs.getDate("invoice_date").toLocalDate(),
                rs.getString("kind"));
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String safe(String s) { return s == null ? "" : s; }
    private static String normalizeNif(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase();
    }
    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // ====================================================================
    //  DTOs
    // ====================================================================

    public record MovementRequest(
            String bankAccountId,
            LocalDate operationDate, LocalDate valueDate,
            String description, String counterpartyName, String counterpartyNif,
            BigDecimal amount, BigDecimal balanceAfter,
            String externalRef, String notes
    ) {}

    public record LinkRequest(
            String invoiceKind, String invoiceId, String counterpartyAccountId
    ) {}

    public record BankMovementView(
            String id, String companyId, String bankAccountId,
            LocalDate operationDate, LocalDate valueDate,
            String description, String counterpartyName, String counterpartyNif,
            BigDecimal amount, BigDecimal balanceAfter,
            String externalRef, String importBatchId, String journalEntryId,
            String linkedInvoiceId, String linkedInvoiceKind,
            String status, String notes,
            Instant createdAt, Instant updatedAt
    ) {}

    public record MatchCandidate(
            String invoiceId, String invoiceNumber, String counterparty,
            BigDecimal amount, LocalDate invoiceDate, String invoiceKind
    ) {}
}
