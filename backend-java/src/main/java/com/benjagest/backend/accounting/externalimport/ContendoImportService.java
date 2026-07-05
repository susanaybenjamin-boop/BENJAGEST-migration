package com.benjagest.backend.accounting.externalimport;

import com.benjagest.backend.accounting.ManualJournalEntryService;
import com.benjagest.backend.accounting.ManualJournalEntryService.LineRequest;
import com.benjagest.backend.accounting.ManualJournalEntryService.ManualEntryRequest;
import com.benjagest.backend.accounting.externalimport.ContendoCsvParser.Asiento;
import com.benjagest.backend.accounting.externalimport.ContendoCsvParser.Line;
import com.benjagest.backend.accounting.externalimport.ContendoCsvParser.ParsedFile;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Importa un "diario contable" exportado por CONTENDO y reconstruye, dentro de
 * la empresa activa (asesoria actuando como el cliente):
 * <ul>
 *   <li>Todos los asientos como POSTED (los datos historicos vienen cuadrados).</li>
 *   <li>Facturas de venta reales (modulo Ventas) + rectificativas.</li>
 *   <li>Facturas de compra reales (modulo Gastos) SOLO cuando llevan IVA
 *       soportado; los recibos sin IVA (SS/autonomo) quedan como asiento.</li>
 *   <li>Clientes y proveedores como entidades de negocio, aunque no haya NIF.</li>
 *   <li>Sub-cuentas de tercero (accounting_accounts) con el codigo LITERAL del
 *       CSV, marcadas con tercero_ref para que el resolver las reencuentre.</li>
 * </ul>
 *
 * <p><b>LINEA ROJA (RD 1007/2023):</b> las facturas historicas son documentos
 * YA emitidos en CONTENDO; NUNCA entran en la cadena VeriFactu/SIF. Este
 * servicio JAMAS llama a {@code SalesInvoiceService.validate()},
 * {@code seriesService.claimNextNumber()}, {@code verifactuRegistryService},
 * {@code sifEventService}, generacion de PDF, {@code salesJournalService} ni
 * {@code reflectionService}. Escribe las facturas directamente como
 * {@code invoice_type='HISTORICAL'}, {@code status='VALIDATED'}, con el numero
 * literal del CSV y {@code series_id=NULL}. El asiento de cada factura ES el
 * asiento del CSV (source_type SALES_INVOICE / PURCHASE_INVOICE), sin duplicar.
 */
@Service
public class ContendoImportService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;
    private final ManualJournalEntryService manualEntries;

    public ContendoImportService(JdbcTemplate jdbcTemplate, TenantContext tenantContext,
                                 CurrentUserService currentUserService,
                                 ManualJournalEntryService manualEntries) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.manualEntries = manualEntries;
    }

    public record Summary(
            String batchId,
            int asientosTotal, int asientosImportados, int asientosSaltados,
            int facturasVenta, int rectificativas, int gastos,
            int clientesCreados, int proveedoresCreados, int cuentasCreadas,
            int cobrosVinculados, int pagosVinculados,
            int errores, List<String> avisos) {}

    /** Movimiento de tesoreria (cobro/pago) pendiente de enlazar en la pasada 2. */
    record PaymentLink(String dueDateId, ContendoCsvParser.Kind kind,
                       String invoiceNumber, String partyName, String partyAccountCode,
                       BigDecimal amount, LocalDate date, String treasuryCode,
                       String journalEntryId) {}

    /** Rectificativa pendiente de enlazar con su original en la pasada 2. */
    record RectLink(String rectInvoiceId, String originalInvoiceNumber) {}

    private static final BigDecimal CENT = new BigDecimal("0.01");

    @Transactional
    public Summary importDiario(String fileName, String content) {
        String companyId = tenantContext.getCurrentCompanyId();

        // 1. Idempotencia por hash del contenido.
        String sha = sha256(content);
        if (batchAlreadyImported(companyId, sha)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este fichero ya se importo antes (mismo contenido). Si has anadido "
                    + "asientos nuevos, exporta de nuevo el diario completo desde CONTENDO.");
        }

        // 2. Parseo (cabecera invalida -> 400).
        ParsedFile pf;
        try {
            pf = ContendoCsvParser.parse(content);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }

        // 3. Pre-vuelo de ejercicios fiscales (crear OPEN los que falten;
        //    abortar si alguno existe CERRADO/BLOQUEADO).
        ensureFiscalYearsOpen(companyId, pf.asientos());

        // 4. Pasada 1.
        Counters c = new Counters();
        List<String> avisos = new ArrayList<>(pf.errors());
        c.errores += pf.errors().size();
        List<PaymentLink> payments = new ArrayList<>();
        List<RectLink> rects = new ArrayList<>();

        for (Asiento a : pf.asientos()) {
            c.total++;
            try {
                importOne(a, sha, c, payments, rects, avisos);
            } catch (Exception ex) {
                c.errores++;
                avisos.add("Asiento " + a.number() + ": " + msg(ex));
            }
        }

        // 5. Pasada 2: enlazar cobros/pagos y rectificativas.
        linkPayments(payments, rects, c, avisos);

        // 6. Registrar el lote.
        String batchId = insertBatch(companyId, fileName, content, sha, c, avisos);

        return new Summary(batchId, c.total, c.imported, c.skipped,
                c.ventas, c.rectificativas, c.gastos,
                c.clientes, c.proveedores, c.cuentas,
                c.cobros, c.pagos, c.errores, avisos);
    }

    // ====================================================================
    //  Pasada 1 — un asiento
    // ====================================================================

    private void importOne(Asiento a, String batchSha, Counters c,
                           List<PaymentLink> payments, List<RectLink> rects, List<String> avisos) {
        switch (a.kind()) {
            case VENTA, RECTIFICATIVA -> importSale(a, c, rects);
            case GASTO -> importPurchase(a, batchSha, c);
            case COBRO, PAGO -> importPayment(a, c, payments);
            case OTRO -> importOther(a, c);
        }
    }

    private void importSale(Asiento a, Counters c, List<RectLink> rects) {
        boolean rect = a.kind() == ContendoCsvParser.Kind.RECTIFICATIVA;
        String invNumber = a.invoiceNumber();
        if (invNumber == null) {
            // Sin numero no podemos crear factura fiable: dejamos solo el asiento.
            createEntry(a, "SALES_INVOICE", null, c);
            return;
        }
        // Dedup: si ya existe esa factura en la empresa, saltar (incl. su asiento).
        if (salesInvoiceExists(invNumber)) {
            c.skipped++;
            return;
        }
        String customerId = ensureCustomer(a.partyName(), c);
        String invoiceId = UUID.randomUUID().toString();

        BigDecimal base = nz(a.base());
        BigDecimal vat = nz(a.vat());
        BigDecimal total = nz(a.total());
        if (rect) { base = base.negate(); vat = vat.negate(); total = total.negate(); }
        BigDecimal vatPercent = base.signum() == 0 ? BigDecimal.ZERO
                : vat.multiply(new BigDecimal("100")).divide(base, 2, RoundingMode.HALF_UP);

        insertSalesInvoice(invoiceId, customerId, invNumber, a.date(),
                base, vat, total, a.concept());
        insertSalesLine(invoiceId, a.concept(), base, vat, vatPercent, total);

        // El asiento del CSV es el asiento de la factura (source_type SALES_INVOICE).
        // La factura ya es la autoridad de dedup (salesInvoiceExists arriba); el
        // asiento se crea salvo que uno equivalente existiera de una importacion
        // parcial previa.
        createEntry(a, "SALES_INVOICE", invoiceId, c);
        if (rect) {
            c.rectificativas++;
            if (a.originalInvoiceNumber() != null) {
                rects.add(new RectLink(invoiceId, a.originalInvoiceNumber()));
            }
        } else {
            c.ventas++;
        }
    }

    private void importPurchase(Asiento a, String batchSha, Counters c) {
        BigDecimal base = nz(a.base());
        BigDecimal vat = nz(a.vat());
        BigDecimal total = nz(a.total());
        // Dedup estable entre importaciones: proveedor + fecha + total + concepto.
        if (purchaseExists(a.partyName(), a.date(), total, a.concept())) {
            c.skipped++;
            return;
        }
        ensureSupplier(a.partyName(), c);
        String purchaseId = UUID.randomUUID().toString();
        String entryId = createEntry(a, "PURCHASE_INVOICE", purchaseId, c);
        BigDecimal vatPercent = base.signum() == 0 ? BigDecimal.ZERO
                : vat.multiply(new BigDecimal("100")).divide(base, 2, RoundingMode.HALF_UP);
        String docSha = sha256("CONTENDO:" + a.number() + ":" + a.date() + ":" + total.toPlainString());

        jdbcTemplate.update("""
                INSERT INTO purchase_invoices (
                    id, company_id, supplier_nif, supplier_name,
                    invoice_number, invoice_date,
                    base_amount, vat_percent, vat_amount, total_amount,
                    document_sha256, invoice_index_in_pdf,
                    status, journal_entry_id, concept, notes,
                    uploaded_by_user_id, uploaded_by_company_id
                ) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'POSTED', ?, ?, NULL, ?, ?)
                """,
                purchaseId, tenantContext.getCurrentCompanyId(),
                a.partyName(), a.invoiceNumber(),
                a.date() == null ? null : java.sql.Date.valueOf(a.date()),
                base, vatPercent, vat, total,
                docSha, entryId, truncate(a.concept(), 240),
                safeUserId(), tenantContext.getCurrentCompanyId());
        c.gastos++;
    }

    private void importPayment(Asiento a, Counters c, List<PaymentLink> payments) {
        String dueDateId = UUID.randomUUID().toString();
        String entryId = createEntry(a, "DUE_DATE_PAYMENT", dueDateId, c);
        if (entryId == null) return; // asiento duplicado -> ya estaba
        payments.add(new PaymentLink(dueDateId, a.kind(), a.invoiceNumber(),
                a.partyName(), a.partyAccountCode(), nz(a.total()), a.date(),
                a.treasuryCode(), entryId));
    }

    private void importOther(Asiento a, Counters c) {
        createEntry(a, "HISTORICAL_IMPORT", null, c);
    }

    // ====================================================================
    //  Pasada 2 — se completa en IMP-H4
    // ====================================================================

    /**
     * Enlaza cobros con facturas de venta y pagos con facturas de compra
     * (insertando UNA fila invoice_due_dates PAID por movimiento — sin generar
     * asiento nuevo, ya lo trae el CSV) y vincula rectificativas con su
     * original (marcandola VOIDED). El orden importa: primero cobros/pagos
     * (para que una original ya cobrada conserve su estado), luego anulaciones.
     */
    private void linkPayments(List<PaymentLink> payments, List<RectLink> rects,
                              Counters c, List<String> avisos) {
        for (PaymentLink p : payments) {
            try {
                if (p.kind() == ContendoCsvParser.Kind.COBRO) {
                    if (linkCobro(p)) c.cobros++;
                    else avisos.add("Cobro sin factura de venta identificable: "
                            + (p.invoiceNumber() != null ? p.invoiceNumber() : p.partyName())
                            + " (" + p.amount().toPlainString() + " EUR, " + p.date() + ")");
                } else {
                    if (linkPago(p)) c.pagos++;
                    else avisos.add("Pago sin gasto identificable: "
                            + (p.partyName() != null ? p.partyName() : p.partyAccountCode())
                            + " (" + p.amount().toPlainString() + " EUR, " + p.date() + ")");
                }
            } catch (Exception ex) {
                avisos.add("Error enlazando movimiento " + p.date() + " "
                        + p.amount().toPlainString() + ": " + msg(ex));
            }
        }
        for (RectLink r : rects) {
            try {
                linkRectificativa(r, avisos);
            } catch (Exception ex) {
                avisos.add("Error enlazando rectificativa " + r.originalInvoiceNumber()
                        + ": " + msg(ex));
            }
        }
    }

    private boolean linkCobro(PaymentLink p) {
        String companyId = tenantContext.getCurrentCompanyId();
        String invoiceId = null;
        BigDecimal invoiceTotal = null;
        // (a) Por numero de factura del concepto.
        if (p.invoiceNumber() != null) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT id, total FROM sales_invoices
                     WHERE company_id = ? AND invoice_number = ? LIMIT 1
                    """, companyId, p.invoiceNumber());
            if (!rows.isEmpty()) {
                invoiceId = (String) rows.get(0).get("id");
                invoiceTotal = (BigDecimal) rows.get(0).get("total");
            }
        }
        // (b) Fallback: factura del mismo cliente, mismo total, aun PENDING.
        if (invoiceId == null && p.partyName() != null) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT si.id, si.total FROM sales_invoices si
                      JOIN customers cu ON cu.id = si.customer_id
                     WHERE si.company_id = ? AND cu.legal_name = ?
                       AND ABS(si.total - ?) < 0.01
                       AND si.payment_status = 'PENDING'
                       AND si.invoice_date <= ?
                     ORDER BY si.invoice_date ASC LIMIT 1
                    """, companyId, p.partyName(), p.amount(),
                    java.sql.Date.valueOf(p.date()));
            if (!rows.isEmpty()) {
                invoiceId = (String) rows.get(0).get("id");
                invoiceTotal = (BigDecimal) rows.get(0).get("total");
            }
        }
        if (invoiceId == null) return false;
        insertPaidDueDate("SALES", invoiceId, p);
        projectSalesPaymentStatus(invoiceId, invoiceTotal);
        return true;
    }

    private boolean linkPago(PaymentLink p) {
        String companyId = tenantContext.getCurrentCompanyId();
        // Gasto del mismo proveedor, mismo total, sin vencimiento aun, mas antiguo.
        List<String> ids = jdbcTemplate.query("""
                SELECT pi.id FROM purchase_invoices pi
                 WHERE pi.company_id = ? AND pi.supplier_name = ?
                   AND ABS(pi.total_amount - ?) < 0.01
                   AND pi.invoice_date <= ?
                   AND NOT EXISTS (SELECT 1 FROM invoice_due_dates dd
                                    WHERE dd.invoice_kind = 'PURCHASE' AND dd.invoice_id = pi.id)
                 ORDER BY pi.invoice_date ASC LIMIT 1
                """, (rs, n) -> rs.getString("id"),
                companyId, p.partyName(), p.amount(), java.sql.Date.valueOf(p.date()));
        if (ids.isEmpty()) return false;
        insertPaidDueDate("PURCHASE", ids.get(0), p);
        return true;
    }

    private void insertPaidDueDate(String kind, String invoiceId, PaymentLink p) {
        String companyId = tenantContext.getCurrentCompanyId();
        Integer maxSeq = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(seq), 0) FROM invoice_due_dates
                 WHERE invoice_kind = ? AND invoice_id = ?
                """, Integer.class, kind, invoiceId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        boolean cash = p.treasuryCode() != null && p.treasuryCode().startsWith("570");
        jdbcTemplate.update("""
                INSERT INTO invoice_due_dates (
                    id, company_id, invoice_id, invoice_kind, seq, due_date, amount,
                    status, paid_date, payment_method, treasury_account_code, journal_entry_id, notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PAID', ?, ?, ?, ?, 'Importado del diario historico CONTENDO')
                """,
                p.dueDateId(), companyId, invoiceId, kind, seq,
                java.sql.Date.valueOf(p.date()), p.amount(),
                java.sql.Date.valueOf(p.date()),
                cash ? "CASH" : "TRANSFER",
                p.treasuryCode(), p.journalEntryId());
    }

    /** Proyecta payment_status/paid_amount de una venta (misma formula que
     *  PaymentScheduleService.syncSalesPaymentStatus). */
    private void projectSalesPaymentStatus(String invoiceId, BigDecimal total) {
        String companyId = tenantContext.getCurrentCompanyId();
        BigDecimal paid = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM invoice_due_dates
                 WHERE company_id = ? AND invoice_kind = 'SALES'
                   AND invoice_id = ? AND status = 'PAID'
                """, BigDecimal.class, companyId, invoiceId);
        BigDecimal p = paid == null ? BigDecimal.ZERO : paid;
        BigDecimal t = total == null ? BigDecimal.ZERO : total;
        String st = p.signum() <= 0 ? "PENDING"
                : (p.add(CENT).compareTo(t) >= 0 ? "PAID" : "PARTIAL");
        jdbcTemplate.update("""
                UPDATE sales_invoices SET payment_status = ?, paid_amount = ?
                 WHERE id = ? AND company_id = ?
                """, st, p, invoiceId, companyId);
    }

    private void linkRectificativa(RectLink r, List<String> avisos) {
        String companyId = tenantContext.getCurrentCompanyId();
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM sales_invoices
                 WHERE company_id = ? AND invoice_number = ? LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, r.originalInvoiceNumber());
        if (ids.isEmpty()) {
            avisos.add("Rectificativa sin factura original: " + r.originalInvoiceNumber());
            return;
        }
        String originalId = ids.get(0);
        // Enlace bidireccional + anulacion de la original (solo si VALIDATED).
        jdbcTemplate.update("""
                UPDATE sales_invoices SET original_invoice_id = ?
                 WHERE id = ? AND company_id = ?
                """, originalId, r.rectInvoiceId(), companyId);
        jdbcTemplate.update("""
                UPDATE sales_invoices SET rectifying_invoice_id = ?
                 WHERE id = ? AND company_id = ?
                """, r.rectInvoiceId(), originalId, companyId);
        jdbcTemplate.update("""
                UPDATE sales_invoices SET status = 'VOIDED'
                 WHERE id = ? AND company_id = ? AND status = 'VALIDATED'
                """, originalId, companyId);
    }

    // ====================================================================
    //  Asientos
    // ====================================================================

    /**
     * Crea el asiento POSTED del CSV. Devuelve el id del asiento, o null si un
     * asiento equivalente (misma fecha + concepto + total debe) ya existia
     * (dedup entre reimportaciones). Resuelve/crea cada cuenta por su codigo.
     */
    private String createEntry(Asiento a, String sourceType, String sourceId, Counters c) {
        BigDecimal totalDebit = BigDecimal.ZERO;
        for (Line l : a.lines()) totalDebit = totalDebit.add(nz(l.debit()));
        if (journalExists(a.date(), a.concept(), totalDebit)) {
            c.skipped++;
            return null;
        }
        List<LineRequest> lines = new ArrayList<>();
        for (Line l : a.lines()) {
            String accountId = ensureAccount(l.accountCode(), l.accountName(), c);
            lines.add(new LineRequest(accountId, l.accountCode(), l.description(),
                    nz(l.debit()), nz(l.credit())));
        }
        var view = manualEntries.createImportedPosted(
                new ManualEntryRequest(a.date(), a.concept(), lines, true),
                sourceType, sourceId);
        c.imported++;
        return view.id();
    }

    // ====================================================================
    //  Cuentas / terceros
    // ====================================================================

    /** Resuelve una cuenta por codigo; si no existe la crea (verbatim del CSV). */
    private String ensureAccount(String code, String name, Counters c) {
        String companyId = tenantContext.getCurrentCompanyId();
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND code = ? LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, code);
        if (!ids.isEmpty()) return ids.get(0);

        String accountId = UUID.randomUUID().toString();
        boolean cliente = code.startsWith("43") && code.length() > 3;
        boolean proveedor = code.startsWith("40") && code.length() > 3;
        if (cliente || proveedor) {
            String parentCode = cliente ? "430" : "400";
            String parentName = cliente ? "Clientes" : "Proveedores";
            String accountType = cliente ? "ASSET" : "LIABILITY";
            String terceroType = cliente ? "cliente" : "proveedor";
            String parentId = ensureParentAccount(companyId, parentCode, parentName, accountType);
            String ref = normalize(stripPrefix(name));
            jdbcTemplate.update("""
                    INSERT INTO accounting_accounts
                        (id, company_id, code, name, account_type, parent_account_id,
                         active, is_standard, tercero_type, tercero_ref, tercero_nif)
                    VALUES (?, ?, ?, ?, ?, ?, TRUE, FALSE, ?, ?, NULL)
                    """,
                    accountId, companyId, code, truncate(name, 180), accountType, parentId,
                    terceroType, ref.isEmpty() ? null : ref);
        } else {
            // Cuenta estandar del PGC que no venia sembrada: crearla suelta.
            jdbcTemplate.update("""
                    INSERT INTO accounting_accounts
                        (id, company_id, code, name, account_type, active, is_standard)
                    VALUES (?, ?, ?, ?, 'OTHER', TRUE, TRUE)
                    """,
                    accountId, companyId, code, truncate(name, 180));
        }
        c.cuentas++;
        return accountId;
    }

    private String ensureParentAccount(String companyId, String parentCode,
                                       String parentName, String accountType) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND code = ? LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, parentCode);
        if (!ids.isEmpty()) return ids.get(0);
        String parentId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO accounting_accounts
                    (id, company_id, code, name, account_type, active, is_standard)
                VALUES (?, ?, ?, ?, ?, TRUE, TRUE)
                """, parentId, companyId, parentCode, parentName, accountType);
        return parentId;
    }

    // ====================================================================
    //  Entidades de negocio (clientes / proveedores)
    // ====================================================================

    /** Cliente por nombre (sin NIF). Dedup por legal_name exacto en la empresa. */
    private String ensureCustomer(String name, Counters c) {
        String companyId = tenantContext.getCurrentCompanyId();
        String legal = name == null || name.isBlank() ? "Cliente sin nombre" : name.trim();
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM customers
                 WHERE company_id = ? AND legal_name = ? LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, legal);
        if (!ids.isEmpty()) return ids.get(0);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO customers (id, company_id, legal_name, tax_identifier,
                                        customer_type, notes, active)
                VALUES (?, ?, ?, NULL, 'COMPANY', 'Importado del diario historico CONTENDO', TRUE)
                """, id, companyId, truncate(legal, 180));
        c.clientes++;
        return id;
    }

    /** Proveedor por nombre (sin NIF). Dedup por legal_name exacto en la empresa. */
    private void ensureSupplier(String name, Counters c) {
        String companyId = tenantContext.getCurrentCompanyId();
        String legal = name == null || name.isBlank() ? "Proveedor sin nombre" : name.trim();
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM suppliers WHERE company_id = ? AND legal_name = ?
                """, Integer.class, companyId, legal);
        if (existing != null && existing > 0) return;
        jdbcTemplate.update("""
                INSERT INTO suppliers (id, company_id, legal_name, tax_identifier, country, active)
                VALUES (?, ?, ?, NULL, 'Espana', TRUE)
                """, UUID.randomUUID().toString(), companyId, truncate(legal, 180));
        c.proveedores++;
    }

    // ====================================================================
    //  Facturas (SQL directo — NUNCA via SalesInvoiceService: linea roja
    //  VeriFactu/SIF). status VALIDATED + invoice_type HISTORICAL + numero
    //  literal del CSV + series_id NULL.
    // ====================================================================

    private void insertSalesInvoice(String invoiceId, String customerId, String invoiceNumber,
                                    LocalDate date, BigDecimal base, BigDecimal vat,
                                    BigDecimal total, String concept) {
        // original_invoice_id / rectifying_invoice_id se enlazan en la pasada 2
        // (linkRectificativas), cuando ya existen ambas facturas.
        jdbcTemplate.update("""
                INSERT INTO sales_invoices (
                    id, company_id, customer_id, series_id, invoice_number,
                    invoice_date, due_date, invoice_type, status, payment_status,
                    subtotal, vat_total, retention_total, total, paid_amount,
                    currency, original_invoice_id, concept, validated_at
                ) VALUES (?, ?, ?, NULL, ?, ?, ?, 'HISTORICAL', 'VALIDATED', 'PENDING',
                          ?, ?, 0, ?, 0, 'EUR', NULL, ?, CURRENT_TIMESTAMP)
                """,
                invoiceId, tenantContext.getCurrentCompanyId(), customerId, invoiceNumber,
                date == null ? null : java.sql.Date.valueOf(date),
                date == null ? null : java.sql.Date.valueOf(date),
                base, vat, total, truncate(concept, 240));
    }

    private void insertSalesLine(String invoiceId, String concept, BigDecimal base,
                                 BigDecimal vat, BigDecimal vatPercent, BigDecimal total) {
        jdbcTemplate.update("""
                INSERT INTO sales_invoice_lines (
                    id, invoice_id, catalog_item_id, description,
                    quantity, unit_price, vat_percent, retention_percent,
                    line_subtotal, line_vat, line_retention, line_total
                ) VALUES (?, ?, NULL, ?, 1, ?, ?, 0, ?, ?, 0, ?)
                """,
                UUID.randomUUID().toString(), invoiceId, truncate(concept, 500),
                base, vatPercent, base, vat, total);
    }

    // ====================================================================
    //  Dedup / existencia
    // ====================================================================

    private boolean salesInvoiceExists(String invoiceNumber) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sales_invoices
                 WHERE company_id = ? AND invoice_number = ?
                """, Integer.class, tenantContext.getCurrentCompanyId(), invoiceNumber);
        return n != null && n > 0;
    }

    private boolean purchaseExists(String supplierName, LocalDate date, BigDecimal total, String concept) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM purchase_invoices
                 WHERE company_id = ? AND supplier_name = ? AND invoice_date = ?
                   AND ABS(total_amount - ?) < 0.01 AND concept = ?
                """, Integer.class, tenantContext.getCurrentCompanyId(),
                supplierName, date == null ? null : java.sql.Date.valueOf(date), total, concept);
        return n != null && n > 0;
    }

    private boolean journalExists(LocalDate date, String concept, BigDecimal totalDebit) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM journal_entries je
                 WHERE je.company_id = ? AND je.entry_date = ? AND je.concept = ?
                   AND ABS((SELECT COALESCE(SUM(debit),0) FROM journal_entry_lines
                             WHERE journal_entry_id = je.id) - ?) < 0.01
                """, Integer.class, tenantContext.getCurrentCompanyId(),
                date == null ? null : java.sql.Date.valueOf(date), concept, totalDebit);
        return n != null && n > 0;
    }

    private boolean batchAlreadyImported(String companyId, String sha) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM external_import_batches
                 WHERE company_id = ? AND content_sha256 = ? AND rows_errors = 0
                """, Integer.class, companyId, sha);
        return n != null && n > 0;
    }

    // ====================================================================
    //  Ejercicios fiscales
    // ====================================================================

    private void ensureFiscalYearsOpen(String companyId, List<Asiento> asientos) {
        java.util.Set<Integer> years = new java.util.TreeSet<>();
        for (Asiento a : asientos) if (a.date() != null) years.add(a.date().getYear());
        for (int year : years) {
            List<String> status = jdbcTemplate.query("""
                    SELECT status FROM fiscal_years WHERE company_id = ? AND year_number = ? LIMIT 1
                    """, (rs, n) -> rs.getString("status"), companyId, year);
            if (status.isEmpty()) {
                jdbcTemplate.update("""
                        INSERT INTO fiscal_years (id, company_id, year_number, start_date, end_date, status)
                        VALUES (?, ?, ?, ?, ?, 'OPEN')
                        """, UUID.randomUUID().toString(), companyId, year,
                        java.sql.Date.valueOf(LocalDate.of(year, 1, 1)),
                        java.sql.Date.valueOf(LocalDate.of(year, 12, 31)));
            } else if (!"OPEN".equals(status.get(0))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "El ejercicio " + year + " esta " + status.get(0)
                        + ": no se pueden importar asientos historicos en un ejercicio cerrado.");
            }
        }
    }

    // ====================================================================
    //  Lote
    // ====================================================================

    private String insertBatch(String companyId, String fileName, String content,
                               String sha, Counters c, List<String> avisos) {
        String batchId = UUID.randomUUID().toString();
        String errorLog = avisos.isEmpty() ? null : truncate(String.join("\n", avisos), 4000);
        jdbcTemplate.update("""
                INSERT INTO external_import_batches (
                    id, company_id, source_format, target_kind, file_name,
                    file_size_bytes, rows_total, rows_imported, rows_skipped,
                    rows_errors, error_log, imported_by_user_id, notes, content_sha256
                ) VALUES (?, ?, 'CSV_CONTENDO', 'FULL_HISTORY', ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                """,
                batchId, companyId, truncate(fileName, 240),
                content.getBytes(StandardCharsets.UTF_8).length,
                c.total, c.imported, c.skipped, c.errores, errorLog, safeUserId(), sha);
        return batchId;
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private static final class Counters {
        int total, imported, skipped, ventas, rectificativas, gastos,
                clientes, proveedores, cuentas, cobros, pagos, errores;
    }

    private static BigDecimal nz(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    private static String msg(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String stripPrefix(String name) {
        if (name == null) return "";
        return name.trim()
                .replaceFirst("(?i)^clientes\\s*-\\s*", "")
                .replaceFirst("(?i)^proveedores\\s*-\\s*", "")
                .trim();
    }

    /** Misma normalizacion que TerceroAccountResolverService para que el
     *  resolver reencuentre las cuentas importadas (sin duplicar). */
    private static String normalize(String s) {
        if (s == null) return "";
        String t = s.trim().toUpperCase(Locale.ROOT);
        t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return t.replaceAll("\\s+", " ");
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    public record ImportRequest(String fileName, String content) {}
}
