package com.benjagest.backend.accounting.compensation;

import com.benjagest.backend.accounting.ManualJournalEntryService;
import com.benjagest.backend.accounting.ManualJournalEntryService.LineRequest;
import com.benjagest.backend.accounting.ManualJournalEntryService.ManualEntryRequest;
import com.benjagest.backend.accounting.ManualJournalEntryService.ManualEntryView;
import com.benjagest.backend.accounting.TerceroAccountResolverService;
import com.benjagest.backend.accounting.TerceroAccountResolverService.ResolvedAccount;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * BLOQUE COMP- — Compensación (netting) de facturas: saldar una VENTA (me
 * deben, cuenta 430) contra una COMPRA (yo debo, cuenta 400) del <b>mismo
 * tercero</b> (por NIF), extinguiendo ambas deudas hasta la cantidad
 * concurrente (Código Civil arts. 1195-1202). Detalle legal y contable en
 * {@code docs/compensacion-legal.md}.
 *
 * <p><b>COMP-1 — detección (solo lectura).</b> Este servicio NO escribe nada:
 * detecta terceros que a la vez tienen ventas pendientes/parciales (430) y
 * compras pendientes (400) casando por NIF normalizado, y propone la
 * compensación. El asesor confirma; la ejecución (asiento 400/430) vive en
 * COMP-2.
 *
 * <p>OJO nomenclatura: "compensación" en este backend ya se usa para el IVA
 * ({@code vat_compensation_baseline}, casilla 110 del 303). Esto es otra cosa:
 * netting de facturas. El {@code source_type} del asiento será
 * {@code 'COMPENSATION'} (namespace del diario, sin colisión).
 *
 * <p>Pendiente de VENTA = {@code total - paid_amount} (la proyección oficial;
 * fuente de verdad = vencimientos, ya reflejada en {@code paid_amount}).
 * Pendiente de COMPRA = {@code total_amount - Σ vencimientos PAID}; las compras
 * marcadas {@code paid=TRUE} (flag V167) se consideran saldadas y se excluyen.
 */
@Service
public class InvoiceCompensationService {

    private static final BigDecimal CENT = new BigDecimal("0.005");

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final ManualJournalEntryService manualEntry;
    private final TerceroAccountResolverService terceros;
    private final CurrentUserService currentUser;

    public InvoiceCompensationService(JdbcTemplate jdbc, TenantContext tenant,
                                      ManualJournalEntryService manualEntry,
                                      TerceroAccountResolverService terceros,
                                      CurrentUserService currentUser) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.manualEntry = manualEntry;
        this.terceros = terceros;
        this.currentUser = currentUser;
    }

    /**
     * Propone compensaciones para la empresa activa: un grupo por cada NIF que
     * tenga a la vez ventas pendientes y compras pendientes. Solo lectura.
     */
    public List<CompensationProposal> findProposals() {
        String companyId = tenant.getCurrentCompanyId();
        LocalDate today = LocalDate.now();

        // 1) Ventas pendientes/parciales (430 = me deben).
        List<RawLine> sales = jdbc.query("""
                SELECT si.id, si.invoice_number, si.invoice_date, si.due_date,
                       si.total, COALESCE(si.paid_amount, 0) AS paid_amount,
                       c.legal_name, c.tax_identifier
                  FROM sales_invoices si
                  JOIN customers c ON c.id = si.customer_id
                 WHERE si.company_id = ?
                   AND si.status = 'VALIDATED'
                   AND si.payment_status IN ('PENDING', 'PARTIAL')
                   AND c.tax_identifier IS NOT NULL AND c.tax_identifier <> ''
                """, (rs, n) -> {
                    BigDecimal total = rs.getBigDecimal("total");
                    BigDecimal paid = rs.getBigDecimal("paid_amount");
                    BigDecimal pending = total.subtract(paid);
                    LocalDate invDate = rs.getDate("invoice_date") == null ? null
                            : rs.getDate("invoice_date").toLocalDate();
                    LocalDate dueDate = rs.getDate("due_date") == null ? null
                            : rs.getDate("due_date").toLocalDate();
                    return new RawLine(rs.getString("tax_identifier"),
                            rs.getString("legal_name"),
                            new Line(rs.getString("id"), rs.getString("invoice_number"),
                                    invDate, dueDate, total, pending,
                                    isDue(dueDate, invDate, today)));
                }, companyId);

        // 2) Compras pendientes (400 = yo debo). Excluye las pagadas por flag
        //    (paid=TRUE); resta los vencimientos PAID de las que usan ese modelo.
        List<RawLine> purchases = jdbc.query("""
                SELECT pi.id, pi.invoice_number, pi.invoice_date,
                       pi.total_amount, pi.supplier_nif, pi.supplier_name,
                       COALESCE((
                           SELECT SUM(dd.amount) FROM invoice_due_dates dd
                            WHERE dd.company_id = pi.company_id
                              AND dd.invoice_kind = 'PURCHASE'
                              AND dd.invoice_id = pi.id
                              AND dd.status = 'PAID'
                       ), 0) AS paid_dd
                  FROM purchase_invoices pi
                 WHERE pi.company_id = ?
                   AND pi.status = 'POSTED'
                   AND pi.paid = FALSE
                   AND pi.total_amount IS NOT NULL
                   AND pi.supplier_nif IS NOT NULL AND pi.supplier_nif <> ''
                """, (rs, n) -> {
                    BigDecimal total = rs.getBigDecimal("total_amount");
                    BigDecimal paidDd = rs.getBigDecimal("paid_dd");
                    BigDecimal pending = total.subtract(paidDd);
                    LocalDate invDate = rs.getDate("invoice_date") == null ? null
                            : rs.getDate("invoice_date").toLocalDate();
                    return new RawLine(rs.getString("supplier_nif"),
                            rs.getString("supplier_name"),
                            new Line(rs.getString("id"), rs.getString("invoice_number"),
                                    invDate, null, total, pending,
                                    // sin due_date en compras: exigible desde su fecha
                                    isDue(null, invDate, today)));
                }, companyId);

        // 3+4) Cruce puro (agrupar por NIF, quedarnos con los terceros que
        //      tienen ambos lados, calcular el compensable). Testeable aparte.
        return crossMatch(sales, purchases);
    }

    /**
     * Cruce PURO venta↔compra por NIF normalizado (patrón compute130: sin BD,
     * testeable). Devuelve una propuesta por cada tercero con ventas Y compras
     * pendientes; el compensable es el menor de los dos totales pendientes.
     * Ordena por compensable descendente.
     */
    static List<CompensationProposal> crossMatch(List<RawLine> sales, List<RawLine> purchases) {
        Map<String, Bucket> byNif = new LinkedHashMap<>();
        for (RawLine r : sales) {
            if (r.line().pending().signum() <= 0) continue;
            String key = normNif(r.nif());
            if (key.isEmpty()) continue;
            byNif.computeIfAbsent(key, k -> new Bucket(r.nif(), r.name()))
                    .sales.add(r.line());
        }
        for (RawLine r : purchases) {
            if (r.line().pending().signum() <= 0) continue;
            String key = normNif(r.nif());
            if (key.isEmpty()) continue;
            Bucket b = byNif.get(key);
            if (b == null) continue; // sin venta pendiente de ese NIF → no compensa
            if (b.name == null || b.name.isBlank()) b.name = r.name();
            b.purchases.add(r.line());
        }

        List<CompensationProposal> out = new ArrayList<>();
        for (Bucket b : byNif.values()) {
            if (b.sales.isEmpty() || b.purchases.isEmpty()) continue;
            BigDecimal salesPending = sum(b.sales);
            BigDecimal purchasePending = sum(b.purchases);
            BigDecimal compensable = salesPending.min(purchasePending);
            if (compensable.signum() <= 0) continue;
            out.add(new CompensationProposal(b.nif, b.name, salesPending,
                    purchasePending, compensable, b.sales, b.purchases));
        }
        out.sort((a, c) -> c.compensable().compareTo(a.compensable()));
        return out;
    }

    private static boolean isDue(LocalDate dueDate, LocalDate invoiceDate, LocalDate today) {
        LocalDate ref = dueDate != null ? dueDate : invoiceDate;
        return ref == null || !ref.isAfter(today);
    }

    private static BigDecimal sum(List<Line> lines) {
        BigDecimal s = BigDecimal.ZERO;
        for (Line l : lines) s = s.add(l.pending());
        return s;
    }

    /** Normaliza el NIF para casar ventas y compras: mayúsculas, sin puntos/guiones/espacios. */
    private static String normNif(String s) {
        if (s == null) return "";
        return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /**
     * Resumen plano por tercero (sin arrays anidados) para la UI: el cliente
     * usa parser regex y no debe lidiar con JSON anidado (CLAUDE.md §6).
     */
    public List<ProposalSummary> proposalSummaries() {
        List<ProposalSummary> out = new ArrayList<>();
        for (CompensationProposal p : findProposals()) {
            out.add(new ProposalSummary(p.nif(), p.counterpartyName(),
                    p.salesPending(), p.purchasePending(), p.compensable()));
        }
        return out;
    }

    /** Líneas (ventas + compras) pendientes de un tercero por NIF, planas. */
    public List<FlatLine> invoicesForNif(String nif) {
        String key = normNif(nif);
        List<FlatLine> out = new ArrayList<>();
        for (CompensationProposal p : findProposals()) {
            if (!key.equals(normNif(p.nif()))) continue;
            for (Line l : p.sales()) out.add(FlatLine.of("SALES", l));
            for (Line l : p.purchases()) out.add(FlatLine.of("PURCHASE", l));
        }
        return out;
    }

    // ====================================================================
    //  COMP-2 — Ejecución (asiento 400/430 + saldado)
    // ====================================================================

    /**
     * Ejecuta una compensación: saldar las ventas seleccionadas (430) contra
     * las compras seleccionadas (400) del MISMO tercero, hasta la cantidad
     * concurrente. Crea un asiento POSTED (Debe 400 / Haber 430) y salda cada
     * factura por sus vencimientos (medio de pago = compensación, sin asiento
     * de tesorería). Transaccional: todo o nada.
     *
     * <p>Reglas legales/contables (docs/compensacion-legal.md):
     * <ul>
     *   <li>Mismo NIF en todas las facturas (art. 1196 CC: reciprocidad).</li>
     *   <li>compensable = min(Σventas pendiente, Σcompras pendiente).</li>
     *   <li>El resto (diferencia) queda pendiente y se concilia por banco.</li>
     *   <li>NO toca la cadena SIF (no crea factura); NO dispara REFLEJO
     *       (compensación intra-empresa).</li>
     * </ul>
     */
    @Transactional
    public CompensationResult execute(ExecuteRequest req) {
        String companyId = tenant.getCurrentCompanyId();
        String userId = safeUserId();
        LocalDate date = req == null || req.date() == null ? LocalDate.now() : req.date();
        if (req == null
                || req.salesInvoiceIds() == null || req.salesInvoiceIds().isEmpty()
                || req.purchaseInvoiceIds() == null || req.purchaseInvoiceIds().isEmpty()) {
            throw bad("Selecciona al menos una venta y una compra para compensar.");
        }

        List<InvoiceRow> sales = loadSales(companyId, req.salesInvoiceIds());
        List<InvoiceRow> purchases = loadPurchases(companyId, req.purchaseInvoiceIds());

        // 1) Mismo NIF en todas (reciprocidad, art. 1196 CC).
        String nif = null;
        for (InvoiceRow r : concat(sales, purchases)) {
            String k = normNif(r.nif());
            if (k.isEmpty()) throw bad("Una factura seleccionada no tiene NIF de tercero.");
            if (nif == null) nif = k;
            else if (!nif.equals(k)) {
                throw bad("Todas las facturas deben ser del mismo tercero (mismo NIF).");
            }
        }

        BigDecimal salesPending = sumPending(sales);
        BigDecimal purchasePending = sumPending(purchases);
        BigDecimal compensable = salesPending.min(purchasePending).setScale(2, RoundingMode.HALF_UP);
        if (compensable.signum() <= 0) {
            throw bad("No hay importe compensable (alguna de las partes no tiene pendiente).");
        }

        // 2) Cuentas 400 (proveedor) y 430 (cliente) del tercero, por NIF.
        String supplierName = firstName(purchases);
        String customerName = firstName(sales);
        ResolvedAccount acc400 = terceros.getOrCreateForSupplier(rawNif(purchases), supplierName);
        ResolvedAccount acc430 = terceros.getOrCreateForCustomer(rawNif(sales), customerName);
        if (acc400 == null || acc400.accountId() == null
                || acc430 == null || acc430.accountId() == null) {
            throw bad("No se pudieron resolver las cuentas 400/430 del tercero.");
        }

        // 3) Reparto FIFO del compensable en cada lado (más antiguas primero).
        List<Alloc> purchaseAllocs = allocateFifo(purchases, compensable);
        List<Alloc> salesAllocs = allocateFifo(sales, compensable);

        // 4) Asiento POSTED: Debe 400 (compras) / Haber 430 (ventas).
        String compensationId = UUID.randomUUID().toString();
        List<LineRequest> lines = new ArrayList<>();
        for (Alloc a : purchaseAllocs) {
            lines.add(new LineRequest(acc400.accountId(), acc400.code(),
                    "Compensación Fra. " + nz(a.row().number()), a.amount(), BigDecimal.ZERO));
        }
        for (Alloc a : salesAllocs) {
            lines.add(new LineRequest(acc430.accountId(), acc430.code(),
                    "Compensación Fra. " + nz(a.row().number()), BigDecimal.ZERO, a.amount()));
        }
        String concept = "Compensación " + (customerName != null ? customerName
                : (supplierName != null ? supplierName : nif));
        ManualEntryView entry = manualEntry.createImportedPosted(
                new ManualEntryRequest(date, concept, lines, true),
                "COMPENSATION", compensationId);

        // 5) Registrar la compensación (para justificante COMP-4 y reversión COMP-5).
        jdbc.update("""
                INSERT INTO invoice_compensations (
                    id, company_id, counterparty_nif, counterparty_name,
                    compensation_date, amount, journal_entry_id, status, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                """,
                compensationId, companyId, nif,
                customerName != null ? customerName : supplierName,
                Date.valueOf(date), compensable, entry.id(), userId);

        // 6) Saldar cada factura por sus vencimientos, enlazados al asiento.
        for (Alloc a : purchaseAllocs) {
            insertCompensationLine(compensationId, "PURCHASE", a.row(), a.amount());
            settleInvoice(companyId, "PURCHASE", a.row(), a.amount(), entry.id(), date);
        }
        for (Alloc a : salesAllocs) {
            insertCompensationLine(compensationId, "SALES", a.row(), a.amount());
            settleInvoice(companyId, "SALES", a.row(), a.amount(), entry.id(), date);
        }

        return new CompensationResult(compensationId, entry.id(), entry.entryNumber(),
                nif, customerName != null ? customerName : supplierName,
                date, compensable, salesPending, purchasePending,
                salesAllocs, purchaseAllocs);
    }

    // ====================================================================
    //  COMP-5 — Reversión (contraasiento + facturas a pendiente)
    // ====================================================================

    /**
     * Revierte una compensación ACTIVE: crea un contraasiento POSTED (Debe 430
     * / Haber 400, invirtiendo el original — no se borra un asiento POSTED) y
     * devuelve las facturas a pendiente (los vencimientos saldados por esta
     * compensación vuelven a PENDING). Transaccional.
     */
    @Transactional
    public CompensationResult reverse(String compensationId) {
        String companyId = tenant.getCurrentCompanyId();
        LocalDate date = LocalDate.now();

        Comp comp = jdbc.query("""
                SELECT id, counterparty_nif, counterparty_name, compensation_date,
                       amount, journal_entry_id, status
                  FROM invoice_compensations
                 WHERE id = ? AND company_id = ?
                """, rs -> rs.next() ? new Comp(rs.getString("id"),
                        rs.getString("counterparty_nif"), rs.getString("counterparty_name"),
                        rs.getBigDecimal("amount"), rs.getString("journal_entry_id"),
                        rs.getString("status")) : null,
                compensationId, companyId);
        if (comp == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Compensación no encontrada.");
        if (!"ACTIVE".equals(comp.status())) throw bad("Esta compensación ya está revertida.");

        // 1) Contraasiento POSTED invirtiendo las líneas del original.
        if (comp.journalEntryId() != null) {
            List<LineRequest> reversed = jdbc.query("""
                    SELECT account_id, description, debit, credit
                      FROM journal_entry_lines WHERE journal_entry_id = ?
                    """, (rs, n) -> new LineRequest(rs.getString("account_id"), null,
                            "Reversión — " + nz(rs.getString("description")),
                            rs.getBigDecimal("credit"), rs.getBigDecimal("debit")),
                    comp.journalEntryId());
            if (!reversed.isEmpty()) {
                manualEntry.createImportedPosted(
                        new ManualEntryRequest(date,
                                "Reversión compensación " + nz(comp.counterpartyName()),
                                reversed, true),
                        "COMPENSATION_REVERSAL", compensationId);
            }
        }

        // 2) Devolver a PENDING los vencimientos saldados por esta compensación.
        List<Object[]> lines = jdbc.query("""
                SELECT invoice_kind, invoice_id FROM invoice_compensation_lines
                 WHERE compensation_id = ?
                """, (rs, n) -> new Object[]{rs.getString("invoice_kind"), rs.getString("invoice_id")},
                compensationId);
        jdbc.update("""
                UPDATE invoice_due_dates
                   SET status = 'PENDING', paid_date = NULL, payment_method = NULL,
                       journal_entry_id = NULL
                 WHERE company_id = ? AND journal_entry_id = ? AND payment_method = 'COMPENSATION'
                """, companyId, comp.journalEntryId());
        for (Object[] l : lines) {
            String kind = (String) l[0];
            String invoiceId = (String) l[1];
            if ("SALES".equals(kind)) {
                BigDecimal paid = jdbc.queryForObject("""
                        SELECT COALESCE(SUM(amount), 0) FROM invoice_due_dates
                         WHERE company_id = ? AND invoice_kind = 'SALES'
                           AND invoice_id = ? AND status = 'PAID'
                        """, BigDecimal.class, companyId, invoiceId);
                BigDecimal total = jdbc.queryForObject(
                        "SELECT total FROM sales_invoices WHERE id = ? AND company_id = ?",
                        BigDecimal.class, invoiceId, companyId);
                BigDecimal p = paid == null ? BigDecimal.ZERO : paid;
                String st = p.signum() <= 0 ? "PENDING"
                        : (total != null && p.add(new BigDecimal("0.01")).compareTo(total) >= 0
                                ? "PAID" : "PARTIAL");
                jdbc.update("UPDATE sales_invoices SET payment_status = ?, paid_amount = ? WHERE id = ? AND company_id = ?",
                        st, p, invoiceId, companyId);
            } else {
                jdbc.update("UPDATE purchase_invoices SET paid = FALSE, paid_date = NULL WHERE id = ? AND company_id = ?",
                        invoiceId, companyId);
            }
        }

        // 3) Marcar la compensación como revertida.
        jdbc.update("""
                UPDATE invoice_compensations SET status = 'REVERSED', reversed_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND company_id = ?
                """, compensationId, companyId);

        return new CompensationResult(compensationId, comp.journalEntryId(), 0,
                comp.nif(), comp.counterpartyName(), date, comp.amount(),
                null, null, List.of(), List.of());
    }

    private record Comp(String id, String nif, String counterpartyName, BigDecimal amount,
                        String journalEntryId, String status) {}

    // ====================================================================
    //  COMP-4 — Listado + detalle (justificante)
    // ====================================================================

    /** Compensaciones ejecutadas por la empresa activa (recientes primero). */
    public List<CompRow> listCompensations() {
        String companyId = tenant.getCurrentCompanyId();
        return jdbc.query("""
                SELECT ic.id, ic.counterparty_nif, ic.counterparty_name,
                       ic.compensation_date, ic.amount, ic.status, je.entry_number
                  FROM invoice_compensations ic
                  LEFT JOIN journal_entries je ON je.id = ic.journal_entry_id
                 WHERE ic.company_id = ?
                 ORDER BY ic.created_at DESC
                """, (rs, n) -> new CompRow(rs.getString("id"),
                        rs.getString("counterparty_nif"), rs.getString("counterparty_name"),
                        rs.getDate("compensation_date").toLocalDate(),
                        rs.getBigDecimal("amount"), rs.getString("status"),
                        rs.getObject("entry_number") == null ? 0 : rs.getInt("entry_number")),
                companyId);
    }

    /** Detalle de una compensación para el justificante. */
    public CompensationDetail getDetail(String id) {
        String companyId = tenant.getCurrentCompanyId();
        CompRow head = jdbc.query("""
                SELECT ic.id, ic.counterparty_nif, ic.counterparty_name,
                       ic.compensation_date, ic.amount, ic.status, je.entry_number
                  FROM invoice_compensations ic
                  LEFT JOIN journal_entries je ON je.id = ic.journal_entry_id
                 WHERE ic.id = ? AND ic.company_id = ?
                """, rs -> rs.next() ? new CompRow(rs.getString("id"),
                        rs.getString("counterparty_nif"), rs.getString("counterparty_name"),
                        rs.getDate("compensation_date").toLocalDate(),
                        rs.getBigDecimal("amount"), rs.getString("status"),
                        rs.getObject("entry_number") == null ? 0 : rs.getInt("entry_number")) : null,
                id, companyId);
        if (head == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Compensación no encontrada.");
        List<DetailLine> lines = jdbc.query("""
                SELECT invoice_kind, invoice_number, amount FROM invoice_compensation_lines
                 WHERE compensation_id = ? ORDER BY invoice_kind, invoice_number
                """, (rs, n) -> new DetailLine(rs.getString("invoice_kind"),
                        rs.getString("invoice_number"), rs.getBigDecimal("amount")), id);
        return new CompensationDetail(head, lines);
    }

    public record CompRow(String id, String nif, String counterpartyName, LocalDate date,
                          BigDecimal amount, String status, int entryNumber) {}

    public record DetailLine(String invoiceKind, String invoiceNumber, BigDecimal amount) {}

    public record CompensationDetail(CompRow header, List<DetailLine> lines) {}

    // ---- carga de facturas seleccionadas --------------------------------

    private List<InvoiceRow> loadSales(String companyId, List<String> ids) {
        List<InvoiceRow> out = new ArrayList<>();
        for (String id : ids) {
            InvoiceRow r = jdbc.query("""
                    SELECT si.id, si.invoice_number, si.invoice_date, si.total,
                           COALESCE(si.paid_amount, 0) AS paid_amount,
                           si.status, si.payment_status,
                           c.legal_name, c.tax_identifier
                      FROM sales_invoices si
                      JOIN customers c ON c.id = si.customer_id
                     WHERE si.id = ? AND si.company_id = ?
                    """, rs -> {
                        if (!rs.next()) return null;
                        BigDecimal pending = rs.getBigDecimal("total")
                                .subtract(rs.getBigDecimal("paid_amount"));
                        if (!"VALIDATED".equals(rs.getString("status"))) {
                            throw bad("La venta " + rs.getString("invoice_number")
                                    + " no está validada.");
                        }
                        return new InvoiceRow(rs.getString("id"), rs.getString("invoice_number"),
                                rs.getDate("invoice_date") == null ? null
                                        : rs.getDate("invoice_date").toLocalDate(),
                                rs.getBigDecimal("total"), pending,
                                rs.getString("tax_identifier"), rs.getString("legal_name"));
                    }, id, companyId);
            if (r == null) throw bad("Venta no encontrada: " + id);
            if (r.pending().signum() <= 0) {
                throw bad("La venta " + nz(r.number()) + " no tiene importe pendiente.");
            }
            out.add(r);
        }
        return out;
    }

    private List<InvoiceRow> loadPurchases(String companyId, List<String> ids) {
        List<InvoiceRow> out = new ArrayList<>();
        for (String id : ids) {
            InvoiceRow r = jdbc.query("""
                    SELECT pi.id, pi.invoice_number, pi.invoice_date, pi.total_amount,
                           pi.status, pi.paid, pi.supplier_nif, pi.supplier_name,
                           COALESCE((
                               SELECT SUM(dd.amount) FROM invoice_due_dates dd
                                WHERE dd.company_id = pi.company_id
                                  AND dd.invoice_kind = 'PURCHASE'
                                  AND dd.invoice_id = pi.id AND dd.status = 'PAID'
                           ), 0) AS paid_dd
                      FROM purchase_invoices pi
                     WHERE pi.id = ? AND pi.company_id = ?
                    """, rs -> {
                        if (!rs.next()) return null;
                        if (!"POSTED".equals(rs.getString("status"))) {
                            throw bad("La compra " + rs.getString("invoice_number")
                                    + " no está contabilizada.");
                        }
                        if (rs.getBoolean("paid")) {
                            throw bad("La compra " + rs.getString("invoice_number")
                                    + " ya está pagada.");
                        }
                        BigDecimal pending = rs.getBigDecimal("total_amount")
                                .subtract(rs.getBigDecimal("paid_dd"));
                        return new InvoiceRow(rs.getString("id"), rs.getString("invoice_number"),
                                rs.getDate("invoice_date") == null ? null
                                        : rs.getDate("invoice_date").toLocalDate(),
                                rs.getBigDecimal("total_amount"), pending,
                                rs.getString("supplier_nif"), rs.getString("supplier_name"));
                    }, id, companyId);
            if (r == null) throw bad("Compra no encontrada: " + id);
            if (r.pending().signum() <= 0) {
                throw bad("La compra " + nz(r.number()) + " no tiene importe pendiente.");
            }
            out.add(r);
        }
        return out;
    }

    // ---- reparto FIFO ----------------------------------------------------

    /** Reparte 'amount' entre las facturas por fecha ascendente (FIFO), sin exceder su pendiente. */
    static List<Alloc> allocateFifo(List<InvoiceRow> rows, BigDecimal amount) {
        List<InvoiceRow> ordered = new ArrayList<>(rows);
        ordered.sort((a, b) -> {
            LocalDate da = a.invoiceDate(), db = b.invoiceDate();
            if (da == null && db == null) return 0;
            if (da == null) return 1;
            if (db == null) return -1;
            return da.compareTo(db);
        });
        List<Alloc> out = new ArrayList<>();
        BigDecimal remaining = amount;
        for (InvoiceRow r : ordered) {
            if (remaining.signum() <= 0) break;
            BigDecimal take = r.pending().min(remaining).setScale(2, RoundingMode.HALF_UP);
            if (take.signum() <= 0) continue;
            out.add(new Alloc(r, take));
            remaining = remaining.subtract(take);
        }
        return out;
    }

    // ---- saldado por vencimientos (sin asiento de tesorería) ------------

    /**
     * Salda 'amount' de una factura consumiendo sus vencimientos PENDING FIFO,
     * enlazándolos al asiento de compensación (medio de pago = compensación).
     * Si un vencimiento se cubre parcialmente, se parte (deja PENDING el resto).
     * No crea asiento de tesorería ni dispara REFLEJO.
     */
    private void settleInvoice(String companyId, String kind, InvoiceRow inv,
                               BigDecimal amount, String entryId, LocalDate date) {
        ensureDefaultDueDate(companyId, kind, inv);
        consumePendingDueDates(companyId, kind, inv.id(), amount, entryId, date);
        if ("SALES".equals(kind)) {
            BigDecimal paid = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(amount), 0) FROM invoice_due_dates
                     WHERE company_id = ? AND invoice_kind = 'SALES'
                       AND invoice_id = ? AND status = 'PAID'
                    """, BigDecimal.class, companyId, inv.id());
            BigDecimal p = paid == null ? BigDecimal.ZERO : paid;
            String st = p.signum() <= 0 ? "PENDING"
                    : (p.add(new BigDecimal("0.01")).compareTo(inv.total()) >= 0 ? "PAID" : "PARTIAL");
            jdbc.update("""
                    UPDATE sales_invoices SET payment_status = ?, paid_amount = ?
                     WHERE id = ? AND company_id = ?
                    """, st, p, inv.id(), companyId);
        } else {
            BigDecimal pend = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(amount), 0) FROM invoice_due_dates
                     WHERE company_id = ? AND invoice_kind = 'PURCHASE'
                       AND invoice_id = ? AND status = 'PENDING'
                    """, BigDecimal.class, companyId, inv.id());
            if (pend == null || pend.compareTo(new BigDecimal("0.01")) < 0) {
                jdbc.update("""
                        UPDATE purchase_invoices SET paid = TRUE, paid_date = ?
                         WHERE id = ? AND company_id = ?
                        """, Date.valueOf(date), inv.id(), companyId);
            }
        }
    }

    /** Si la factura no tiene vencimientos, crea uno PENDING = total (patrón ensureDefault). */
    private void ensureDefaultDueDate(String companyId, String kind, InvoiceRow inv) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM invoice_due_dates
                 WHERE company_id = ? AND invoice_kind = ? AND invoice_id = ?
                """, Integer.class, companyId, kind, inv.id());
        if (n != null && n > 0) return;
        LocalDate due = inv.invoiceDate() != null ? inv.invoiceDate() : LocalDate.now();
        jdbc.update("""
                INSERT INTO invoice_due_dates (
                    id, company_id, invoice_id, invoice_kind, seq, due_date, amount, status
                ) VALUES (?, ?, ?, ?, 1, ?, ?, 'PENDING')
                """, UUID.randomUUID().toString(), companyId, inv.id(), kind,
                Date.valueOf(due), inv.total());
    }

    private void consumePendingDueDates(String companyId, String kind, String invoiceId,
                                        BigDecimal amount, String entryId, LocalDate date) {
        List<Object[]> pend = jdbc.query("""
                SELECT id, seq, amount, due_date FROM invoice_due_dates
                 WHERE company_id = ? AND invoice_kind = ? AND invoice_id = ? AND status = 'PENDING'
                 ORDER BY seq, due_date
                """, (rs, n) -> new Object[]{rs.getString("id"), rs.getInt("seq"),
                        rs.getBigDecimal("amount"), rs.getDate("due_date")},
                companyId, kind, invoiceId);
        BigDecimal remaining = amount;
        for (Object[] dd : pend) {
            if (remaining.compareTo(CENT) <= 0) break;
            String ddId = (String) dd[0];
            BigDecimal ddAmount = (BigDecimal) dd[2];
            java.sql.Date ddDue = (java.sql.Date) dd[3];
            if (ddAmount.subtract(remaining).compareTo(CENT) <= 0) {
                // vencimiento entero
                jdbc.update("""
                        UPDATE invoice_due_dates
                           SET status = 'PAID', paid_date = ?, payment_method = 'COMPENSATION',
                               journal_entry_id = ?
                         WHERE id = ? AND company_id = ?
                        """, Date.valueOf(date), entryId, ddId, companyId);
                remaining = remaining.subtract(ddAmount);
            } else {
                // parcial: deja PENDING el resto y crea un PAID por lo compensado
                jdbc.update("""
                        UPDATE invoice_due_dates SET amount = ? WHERE id = ? AND company_id = ?
                        """, ddAmount.subtract(remaining), ddId, companyId);
                Integer maxSeq = jdbc.queryForObject("""
                        SELECT COALESCE(MAX(seq), 0) FROM invoice_due_dates
                         WHERE company_id = ? AND invoice_kind = ? AND invoice_id = ?
                        """, Integer.class, companyId, kind, invoiceId);
                jdbc.update("""
                        INSERT INTO invoice_due_dates (
                            id, company_id, invoice_id, invoice_kind, seq, due_date, amount,
                            status, paid_date, payment_method, journal_entry_id
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PAID', ?, 'COMPENSATION', ?)
                        """, UUID.randomUUID().toString(), companyId, invoiceId, kind,
                        (maxSeq == null ? 0 : maxSeq) + 1, ddDue, remaining,
                        Date.valueOf(date), entryId);
                remaining = BigDecimal.ZERO;
            }
        }
    }

    private void insertCompensationLine(String compensationId, String kind, InvoiceRow inv,
                                        BigDecimal amount) {
        jdbc.update("""
                INSERT INTO invoice_compensation_lines (
                    id, compensation_id, invoice_kind, invoice_id, invoice_number, amount
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), compensationId, kind,
                inv.id(), inv.number(), amount);
    }

    // ---- helpers menores -------------------------------------------------

    private static List<InvoiceRow> concat(List<InvoiceRow> a, List<InvoiceRow> b) {
        List<InvoiceRow> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static BigDecimal sumPending(List<InvoiceRow> rows) {
        BigDecimal s = BigDecimal.ZERO;
        for (InvoiceRow r : rows) s = s.add(r.pending());
        return s;
    }

    private static String firstName(List<InvoiceRow> rows) {
        for (InvoiceRow r : rows) {
            if (r.name() != null && !r.name().isBlank()) return r.name();
        }
        return null;
    }

    private static String rawNif(List<InvoiceRow> rows) {
        for (InvoiceRow r : rows) {
            if (r.nif() != null && !r.nif().isBlank()) return r.nif();
        }
        return null;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private String safeUserId() {
        try { return currentUser.require().userId(); } catch (Exception ex) { return null; }
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // ---- DTOs ----

    /** Una factura pendiente (venta o compra) con su importe pendiente. */
    public record Line(String invoiceId, String invoiceNumber, LocalDate invoiceDate,
                       LocalDate dueDate, BigDecimal total, BigDecimal pending,
                       boolean due) {}

    /** Propuesta de compensación para un tercero (mismo NIF). */
    public record CompensationProposal(String nif, String counterpartyName,
                                       BigDecimal salesPending, BigDecimal purchasePending,
                                       BigDecimal compensable,
                                       List<Line> sales, List<Line> purchases) {}

    /** Resumen plano por tercero (sin arrays) para la UI. */
    public record ProposalSummary(String nif, String counterpartyName,
                                  BigDecimal salesPending, BigDecimal purchasePending,
                                  BigDecimal compensable) {}

    /** Línea plana de factura pendiente (venta o compra) para la UI. */
    public record FlatLine(String invoiceKind, String invoiceId, String invoiceNumber,
                           LocalDate invoiceDate, BigDecimal total, BigDecimal pending,
                           boolean due) {
        static FlatLine of(String kind, Line l) {
            return new FlatLine(kind, l.invoiceId(), l.invoiceNumber(), l.invoiceDate(),
                    l.total(), l.pending(), l.due());
        }
    }

    /** Línea cruda con su NIF/nombre de tercero antes de agrupar (paquete-visible para test). */
    record RawLine(String nif, String name, Line line) {}

    /** Factura seleccionada para compensar, con su pendiente ya calculado. */
    record InvoiceRow(String id, String number, LocalDate invoiceDate,
                      BigDecimal total, BigDecimal pending, String nif, String name) {}

    /** Importe compensado asignado a una factura concreta. */
    public record Alloc(InvoiceRow row, BigDecimal amount) {}

    /** Petición de ejecución: ventas + compras a compensar (mismo tercero). */
    public record ExecuteRequest(List<String> salesInvoiceIds,
                                 List<String> purchaseInvoiceIds, LocalDate date) {}

    /** Resultado de una compensación ejecutada. */
    public record CompensationResult(String compensationId, String journalEntryId,
                                     int entryNumber, String nif, String counterpartyName,
                                     LocalDate date, BigDecimal compensated,
                                     BigDecimal salesPending, BigDecimal purchasePending,
                                     List<Alloc> sales, List<Alloc> purchases) {}

    private static final class Bucket {
        final String nif;
        String name;
        final List<Line> sales = new ArrayList<>();
        final List<Line> purchases = new ArrayList<>();
        Bucket(String nif, String name) { this.nif = nif; this.name = name; }
    }

    @RestController
    @RequestMapping("/api/accounting/compensation")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class Controller {
        private final InvoiceCompensationService service;
        private final InvoiceCompensationPdfGenerator pdfGenerator;

        public Controller(InvoiceCompensationService service,
                          InvoiceCompensationPdfGenerator pdfGenerator) {
            this.service = service;
            this.pdfGenerator = pdfGenerator;
        }

        /** COMP-1 — propuestas de compensación (resumen plano por tercero). */
        @GetMapping("/suggestions")
        public List<ProposalSummary> suggestions() {
            return service.proposalSummaries();
        }

        /** COMP-1 — facturas pendientes (ventas+compras) de un tercero por NIF. */
        @GetMapping("/invoices")
        public List<FlatLine> invoices(@org.springframework.web.bind.annotation.RequestParam("nif") String nif) {
            return service.invoicesForNif(nif);
        }

        /** COMP-2 — ejecuta una compensación (asiento 400/430 + saldado). */
        @PostMapping("/execute")
        public CompensationResult execute(@RequestBody ExecuteRequest req) {
            return service.execute(req);
        }

        /** COMP-4 — compensaciones ejecutadas (para listado). */
        @GetMapping("/list")
        public List<CompRow> list() {
            return service.listCompensations();
        }

        /** COMP-4 — detalle de una compensación. */
        @GetMapping("/{id}")
        public CompensationDetail detail(@PathVariable("id") String id) {
            return service.getDetail(id);
        }

        /** COMP-4 — justificante PDF (acuerdo de compensación). */
        @GetMapping("/{id}/pdf")
        public org.springframework.http.ResponseEntity<byte[]> pdf(@PathVariable("id") String id) {
            byte[] bytes = pdfGenerator.generate(service.getDetail(id));
            return org.springframework.http.ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "application/pdf")
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"compensacion-" + id + ".pdf\"")
                    .body(bytes);
        }

        /** COMP-5 — revierte una compensación (contraasiento + facturas a pendiente). */
        @PostMapping("/{id}/reverse")
        public CompensationResult reverse(@PathVariable("id") String id) {
            return service.reverse(id);
        }
    }
}
