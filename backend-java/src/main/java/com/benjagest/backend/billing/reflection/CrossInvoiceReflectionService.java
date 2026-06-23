package com.benjagest.backend.billing.reflection;

import com.benjagest.backend.billing.invoices.SalesInvoice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * BLOQUE REFLEJO — refleja una factura EMITIDA dentro de la cartera de la
 * asesoría como factura RECIBIDA (gasto) + asiento, EN BORRADOR (por validar),
 * en los libros de la empresa de la cartera que la recibe.
 *
 * <p>El enlace customer ⇒ empresa-cliente es por NIF
 * ({@code customers.tax_identifier} = {@code companies.tax_identifier}). El
 * target debe estar en la misma cartera que el emisor (la asesoría, o un
 * cliente que cuelga de ella por {@code parent_company_id}).
 *
 * <p>Características clave (decisiones Benjamin 2026-06-23):
 * <ul>
 *   <li>Gasto y asiento nacen <b>por validar</b> (DRAFT / auto_proposed) →
 *       aparecen en los avisos "facturas por validar" y "asientos por validar".</li>
 *   <li>Cuenta de gasto por defecto <b>623</b> (servicios profesionales),
 *       editable por el asesor al validar.</li>
 *   <li>Espejo del asiento de venta: 623/472 al Debe, 4751 (retención IRPF) y
 *       400 (proveedor = emisor) al Haber.</li>
 *   <li><b>Idempotente</b> por {@code source_sales_invoice_id}: re-validar no
 *       duplica.</li>
 *   <li><b>Best-effort y NUNCA lanza</b>: un fallo del reflejo no rompe la
 *       validación legal ni la cadena SIF de la factura original.</li>
 * </ul>
 *
 * <p>Deliberadamente <b>NO</b> depende de {@code TenantContext}: escribe con el
 * {@code companyId} del cliente de forma explícita (la validación corre en el
 * scope del emisor). No toca los servicios de compra/venta existentes.
 */
@Service
public class CrossInvoiceReflectionService {

    private static final Logger log = LoggerFactory.getLogger(CrossInvoiceReflectionService.class);
    private static final String SRC_TYPE = "PURCHASE_INVOICE";

    private final JdbcTemplate jdbc;

    public CrossInvoiceReflectionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Refleja la factura emitida (ya VALIDATED) como gasto + asiento por validar
     * en el cliente, si procede. Best-effort: cualquier excepción se traga y se
     * loguea — el caller (validación de la factura) no debe verse afectado.
     */
    public void reflectSalesInvoice(SalesInvoice invoice, String userId) {
        try {
            doReflect(invoice, userId);
        } catch (Exception ex) {
            log.warn("REFLEJO: no se pudo reflejar la factura {} como gasto del cliente: {}",
                    invoice == null ? "?" : invoice.id(),
                    ex.getMessage(), ex);
        }
    }

    /**
     * Cuerpo del reflejo. SIN {@code @Transactional}: las escrituras viajan en
     * la transacción del caller ({@code SalesInvoiceService.validate()}). Así,
     * si {@link #reflectSalesInvoice} captura una excepción, NO queda un límite
     * transaccional interno que marque rollback-only y envenene la validación.
     * Todas las precondiciones (cuentas, ejercicio) se comprueban antes de
     * insertar, de modo que un fallo a mitad de inserción es prácticamente
     * imposible salvo caída de BD (que abortaría la validación de todos modos).
     */
    private void doReflect(SalesInvoice invoice, String userId) {
        if (invoice == null) return;
        // Solo facturas NORMALES: las proformas no son documento fiscal y las
        // rectificativas se tratan en la cascada de anulación (REFLEJO-3).
        if (!"NORMAL".equals(invoice.invoiceType())) return;
        if (invoice.invoiceDate() == null || invoice.subtotal() == null
                || invoice.vatTotal() == null || invoice.total() == null) return;
        if (invoice.customerId() == null || invoice.customerId().isBlank()) return;

        String issuerCompanyId = invoice.companyId();
        if (issuerCompanyId == null) return;

        // 1) Datos del emisor (será el proveedor del gasto reflejado).
        CompanyRef issuer = loadCompany(issuerCompanyId);
        if (issuer == null) return;
        String advisoryId = "INTERNAL".equals(issuer.companyType())
                ? issuer.id()
                : issuer.parentCompanyId();
        if (advisoryId == null) return; // emisor fuera de una cartera → nada que reflejar

        // 2) NIF del cliente de la factura.
        String customerNif = jdbc.query("""
                SELECT tax_identifier FROM customers
                 WHERE id = ? AND company_id = ?
                 LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : null,
                invoice.customerId(), issuerCompanyId);
        if (customerNif == null || customerNif.isBlank()) return;

        // 3) Empresa target de la cartera que casa por NIF (no el propio emisor).
        String targetId = jdbc.query("""
                SELECT id FROM companies
                 WHERE active = TRUE
                   AND tax_identifier = ?
                   AND id <> ?
                   AND (id = ? OR parent_company_id = ?)
                 LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : null,
                customerNif, issuerCompanyId, advisoryId, advisoryId);
        if (targetId == null) return; // el cliente no es una empresa de la cartera

        // 4) Idempotencia: ¿ya reflejada?
        Integer dup = jdbc.queryForObject("""
                SELECT COUNT(*) FROM purchase_invoices
                 WHERE company_id = ? AND source_sales_invoice_id = ?
                """, Integer.class, targetId, invoice.id());
        if (dup != null && dup > 0) return;

        // 5) Crear la factura RECIBIDA (gasto) en DRAFT (por validar).
        BigDecimal base = invoice.subtotal();
        BigDecimal vat = invoice.vatTotal();
        BigDecimal total = invoice.total();
        BigDecimal vatPercent = base.signum() > 0
                ? vat.multiply(BigDecimal.valueOf(100)).divide(base, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        String concept = buildConcept(invoice, issuer.legalName());

        String purchaseId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO purchase_invoices (
                    id, company_id, supplier_nif, supplier_name, invoice_number,
                    invoice_date, base_amount, vat_percent, vat_amount, total_amount,
                    invoice_index_in_pdf, status, concept,
                    source_sales_invoice_id, source_company_id,
                    uploaded_by_user_id, uploaded_by_company_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'DRAFT', ?, ?, ?, ?, ?)
                """,
                purchaseId, targetId, issuer.taxIdentifier(), issuer.legalName(),
                invoice.invoiceNumber(),
                Date.valueOf(invoice.invoiceDate()),
                base, vatPercent, vat, total,
                concept, invoice.id(), issuerCompanyId,
                userId, issuerCompanyId);

        // El emisor pasa a ser proveedor en los libros del cliente (para 347,
        // vistas de proveedor, etc.). Best-effort, no rompe el reflejo.
        ensureSupplier(targetId, issuer.taxIdentifier(), issuer.legalName());

        // 6) Asiento DRAFT / auto_proposed (espejo del de venta). Best-effort:
        //    si faltan cuentas o ejercicio, la factura queda sin asiento y el
        //    asesor lo completa al validar.
        String entryId = tryBuildJournalEntry(targetId, invoice, purchaseId, concept,
                issuer.legalName(), userId);
        if (entryId != null) {
            jdbc.update("UPDATE purchase_invoices SET journal_entry_id = ? WHERE id = ?",
                    entryId, purchaseId);
        }

        log.info("REFLEJO: factura {} reflejada como gasto {} (asiento {}) en empresa {}",
                invoice.invoiceNumber(), purchaseId,
                entryId == null ? "—" : entryId, targetId);
    }

    /**
     * REFLEJO-4 — Refleja un COBRO de la factura emitida como PAGO del gasto en
     * los libros del cliente que la recibió, POR VALIDAR. Best-effort, nunca lanza.
     *
     * <p>En el cliente: {@code Debe 400 (proveedor=emisor) / Haber 572 banco · 570
     * caja}. La tesorería se elige por el método de pago (efectivo → 570, resto →
     * 572). Idempotente por {@code source_type='REFLECTED_PAYMENT' + source_id =
     * sourceKey}: si ese cobro ya se reflejó, no duplica.
     *
     * @param salesInvoiceId factura emitida cobrada (origen del reflejo del gasto)
     * @param amount         importe cobrado en este evento
     * @param payDate        fecha del cobro
     * @param paymentMethod  método (para elegir 570/572)
     * @param sourceKey      clave única del cobro de origen (id del pago/vencimiento)
     * @param userId         usuario que registra
     */
    public void reflectPayment(String salesInvoiceId, BigDecimal amount, LocalDate payDate,
                               String paymentMethod, String sourceKey, String userId) {
        try {
            doReflectPayment(salesInvoiceId, amount, payDate, paymentMethod, sourceKey, userId);
        } catch (Exception ex) {
            log.warn("REFLEJO: no se pudo reflejar el cobro de la factura {}: {}",
                    salesInvoiceId, ex.getMessage(), ex);
        }
    }

    private void doReflectPayment(String salesInvoiceId, BigDecimal amount, LocalDate payDate,
                                  String paymentMethod, String sourceKey, String userId) {
        if (salesInvoiceId == null || amount == null || amount.signum() <= 0) return;
        if (payDate == null) payDate = LocalDate.now();
        if (sourceKey == null || sourceKey.isBlank()) return;

        // 1) ¿Esta factura se reflejó como gasto en algún cliente? Si no, nada.
        var ref = jdbc.query("""
                SELECT company_id, supplier_name FROM purchase_invoices
                 WHERE source_sales_invoice_id = ?
                 LIMIT 1
                """, rs -> rs.next()
                        ? new String[]{rs.getString("company_id"), rs.getString("supplier_name")}
                        : null,
                salesInvoiceId);
        if (ref == null) return;
        String targetId = ref[0];
        String supplierName = ref[1];

        // 2) Idempotencia: ¿ya reflejado este cobro concreto?
        Integer dup = jdbc.queryForObject("""
                SELECT COUNT(*) FROM journal_entries
                 WHERE company_id = ? AND source_type = 'REFLECTED_PAYMENT' AND source_id = ?
                """, Integer.class, targetId, sourceKey);
        if (dup != null && dup > 0) return;

        // 3) Ejercicio fiscal OPEN del cliente para la fecha del cobro.
        String fiscalYearId = jdbc.query("""
                SELECT id FROM fiscal_years
                 WHERE company_id = ? AND status = 'OPEN'
                   AND ? BETWEEN start_date AND end_date
                 LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : null,
                targetId, Date.valueOf(payDate));
        if (fiscalYearId == null) return;

        // 4) Cuentas: proveedor 400 (Debe) y tesorería (Haber).
        String acc400 = findAccountByPrefix(targetId, "400");
        boolean cash = isCash(paymentMethod);
        String treasury = findAccountByPrefix(targetId, cash ? "570" : "572");
        if (treasury == null) treasury = findAccountByPrefix(targetId, cash ? "572" : "570");
        if (treasury == null) treasury = findAccountByPrefix(targetId, "57");
        if (acc400 == null || treasury == null) return;

        // 5) Asiento DRAFT / auto_proposed (por validar).
        String supplierDesc = supplierName == null || supplierName.isBlank()
                ? "Proveedor" : supplierName;
        String concept = "Pago Fra. — " + supplierDesc;
        String entryId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, proposed_confidence, created_by
                ) VALUES (?, ?, ?, NULL, ?, ?, 'REFLECTED_PAYMENT', ?, 'DRAFT', FALSE, TRUE, NULL, ?)
                """,
                entryId, targetId, fiscalYearId, Date.valueOf(payDate), concept, sourceKey, userId);

        BigDecimal amt = amount.setScale(2, RoundingMode.HALF_UP);
        insertLine(entryId, acc400, supplierDesc, amt, BigDecimal.ZERO);
        insertLine(entryId, treasury, cash ? "Pago en efectivo" : "Pago por banco",
                BigDecimal.ZERO, amt);

        log.info("REFLEJO: cobro {} de la factura {} reflejado como pago (asiento {}) en empresa {}",
                amt, salesInvoiceId, entryId, targetId);
    }

    /**
     * REFLEJO-4b (inverso) — Cuando el cliente PAGA un gasto que es una factura
     * reflejada, refleja el COBRO en los libros del emisor (la asesoría): marca su
     * factura de venta cobrada y crea el asiento de cobro POR VALIDAR.
     *
     * <p>En el emisor: {@code Debe 572 banco · 570 caja / Haber 430 (cliente)}.
     * Idempotente por {@code source_type='REFLECTED_COLLECTION' + source_id}.
     * Best-effort, nunca lanza.
     *
     * @param purchaseInvoiceId el gasto pagado (debe ser un reflejo con origen)
     */
    public void reflectCollection(String purchaseInvoiceId, BigDecimal amount, LocalDate payDate,
                                  String paymentMethod, String sourceKey, String userId) {
        try {
            doReflectCollection(purchaseInvoiceId, amount, payDate, paymentMethod, sourceKey, userId);
        } catch (Exception ex) {
            log.warn("REFLEJO: no se pudo reflejar el cobro del gasto {}: {}",
                    purchaseInvoiceId, ex.getMessage(), ex);
        }
    }

    private void doReflectCollection(String purchaseInvoiceId, BigDecimal amount, LocalDate payDate,
                                     String paymentMethod, String sourceKey, String userId) {
        if (purchaseInvoiceId == null || amount == null || amount.signum() <= 0) return;
        if (payDate == null) payDate = LocalDate.now();
        if (sourceKey == null || sourceKey.isBlank()) return;

        // 1) ¿El gasto es un reflejo? (tiene factura de venta + empresa de origen)
        var ref = jdbc.query("""
                SELECT source_sales_invoice_id, source_company_id FROM purchase_invoices
                 WHERE id = ? AND source_sales_invoice_id IS NOT NULL
                 LIMIT 1
                """, rs -> rs.next()
                        ? new String[]{rs.getString("source_sales_invoice_id"),
                                rs.getString("source_company_id")}
                        : null,
                purchaseInvoiceId);
        if (ref == null) return;
        String salesInvoiceId = ref[0];
        String issuerId = ref[1];
        if (issuerId == null) return;

        // 2) Idempotencia.
        Integer dup = jdbc.queryForObject("""
                SELECT COUNT(*) FROM journal_entries
                 WHERE company_id = ? AND source_type = 'REFLECTED_COLLECTION' AND source_id = ?
                """, Integer.class, issuerId, sourceKey);
        if (dup != null && dup > 0) return;

        // 3) Datos de la factura de venta en el emisor.
        var inv = jdbc.query("""
                SELECT total, paid_amount, customer_id FROM sales_invoices
                 WHERE id = ? AND company_id = ?
                 LIMIT 1
                """, rs -> rs.next()
                        ? new Object[]{rs.getBigDecimal("total"), rs.getBigDecimal("paid_amount"),
                                rs.getString("customer_id")}
                        : null,
                salesInvoiceId, issuerId);
        if (inv == null) return;
        BigDecimal total = (BigDecimal) inv[0];
        BigDecimal paid = (BigDecimal) inv[1];

        // 4) Asiento de cobro DRAFT/auto_proposed en el emisor: Debe tesorería / Haber 430.
        String fiscalYearId = jdbc.query("""
                SELECT id FROM fiscal_years
                 WHERE company_id = ? AND status = 'OPEN'
                   AND ? BETWEEN start_date AND end_date
                 LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : null,
                issuerId, Date.valueOf(payDate));
        String acc430 = findAccountByPrefix(issuerId, "430");
        boolean cash = isCash(paymentMethod);
        String treasury = findAccountByPrefix(issuerId, cash ? "570" : "572");
        if (treasury == null) treasury = findAccountByPrefix(issuerId, cash ? "572" : "570");
        if (treasury == null) treasury = findAccountByPrefix(issuerId, "57");

        BigDecimal amt = amount.setScale(2, RoundingMode.HALF_UP);
        if (fiscalYearId != null && acc430 != null && treasury != null) {
            String entryId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO journal_entries (
                        id, company_id, fiscal_year_id, entry_number,
                        entry_date, concept, source_type, source_id,
                        status, reviewed, auto_proposed, proposed_confidence, created_by
                    ) VALUES (?, ?, ?, NULL, ?, ?, 'REFLECTED_COLLECTION', ?, 'DRAFT', FALSE, TRUE, NULL, ?)
                    """,
                    entryId, issuerId, fiscalYearId, Date.valueOf(payDate),
                    "Cobro factura", sourceKey, userId);
            insertLine(entryId, treasury, cash ? "Cobro en efectivo" : "Cobro por banco",
                    amt, BigDecimal.ZERO);
            insertLine(entryId, acc430, "Cliente", BigDecimal.ZERO, amt);
        }

        // 5) Marcar la factura de venta cobrada (proyección comercial) para que la
        //    asesoría la vea cobrada en sus listados/KPIs.
        BigDecimal newPaid = (paid == null ? BigDecimal.ZERO : paid).add(amt);
        String newStatus = newPaid.compareTo(total) >= 0 ? "PAID"
                : (newPaid.signum() > 0 ? "PARTIAL" : "PENDING");
        jdbc.update("""
                UPDATE sales_invoices SET paid_amount = ?, payment_status = ?
                 WHERE id = ? AND company_id = ?
                """, newPaid, newStatus, salesInvoiceId, issuerId);

        log.info("REFLEJO: pago {} del gasto {} reflejado como cobro en empresa {} (factura {})",
                amt, purchaseInvoiceId, issuerId, salesInvoiceId);
    }

    private boolean isCash(String method) {
        if (method == null) return false;
        String m = method.toUpperCase();
        return m.contains("EFECT") || m.contains("CASH") || m.contains("CONTADO")
                || m.contains("METAL") || m.contains("CAJA");
    }

    /** Construye el asiento de compra espejo o devuelve null si no es posible. */
    private String tryBuildJournalEntry(String companyId, SalesInvoice invoice,
                                        String purchaseId, String concept,
                                        String supplierName, String userId) {
        String fiscalYearId = jdbc.query("""
                SELECT id FROM fiscal_years
                 WHERE company_id = ? AND status = 'OPEN'
                   AND ? BETWEEN start_date AND end_date
                 LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : null,
                companyId, Date.valueOf(invoice.invoiceDate()));
        if (fiscalYearId == null) return null;

        String acc623 = findAccountByPrefix(companyId, "623"); // servicios profesionales
        String acc472 = findAccountByPrefix(companyId, "472"); // IVA soportado
        String acc400 = findAccountByPrefix(companyId, "400"); // proveedores
        if (acc623 == null || acc472 == null || acc400 == null) return null;

        BigDecimal base = invoice.subtotal();
        BigDecimal vat = invoice.vatTotal();
        BigDecimal total = invoice.total();
        BigDecimal retention = invoice.retentionTotal() == null
                ? BigDecimal.ZERO : invoice.retentionTotal();

        // Retención IRPF: en el cliente es HP acreedora por retenciones
        // practicadas (4751). Si hay retención pero no existe la cuenta, no
        // creamos un asiento descuadrado: lo dejamos sin asiento.
        String acc4751 = null;
        if (retention.signum() > 0) {
            acc4751 = findAccountByPrefix(companyId, "4751");
            if (acc4751 == null) acc4751 = findAccountByPrefix(companyId, "475");
            if (acc4751 == null) return null;
        }

        String entryId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, proposed_confidence, created_by
                ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 'DRAFT', FALSE, TRUE, NULL, ?)
                """,
                entryId, companyId, fiscalYearId,
                Date.valueOf(invoice.invoiceDate()), concept, SRC_TYPE, purchaseId,
                userId);

        String supplierDesc = supplierName == null || supplierName.isBlank()
                ? "Proveedor" : supplierName;
        BigDecimal vatRate = base.signum() > 0
                ? vat.multiply(BigDecimal.valueOf(100)).divide(base, 0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Debe 623 = base ; Debe 472 = IVA ; Haber 4751 = retención ; Haber 400 = total
        insertLine(entryId, acc623, concept, base, BigDecimal.ZERO);
        insertLine(entryId, acc472, "IVA soportado " + vatRate + "% — " + supplierDesc,
                vat, BigDecimal.ZERO);
        if (acc4751 != null) {
            insertLine(entryId, acc4751, "Retención IRPF practicada — " + supplierDesc,
                    BigDecimal.ZERO, retention);
        }
        insertLine(entryId, acc400, supplierDesc, BigDecimal.ZERO, total);
        return entryId;
    }

    private void insertLine(String entryId, String accountId, String description,
                            BigDecimal debit, BigDecimal credit) {
        String d = description == null ? null
                : (description.length() > 240 ? description.substring(0, 240) : description);
        jdbc.update("""
                INSERT INTO journal_entry_lines (
                    id, journal_entry_id, account_id, description, debit, credit
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entryId, accountId, d, debit, credit);
    }

    private String findAccountByPrefix(String companyId, String prefix) {
        List<String> ids = jdbc.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE AND code LIKE ?
                 ORDER BY LENGTH(code), code
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, prefix + "%");
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void ensureSupplier(String companyId, String nif, String name) {
        if (nif == null || nif.isBlank()) return;
        try {
            Integer n = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM suppliers
                     WHERE company_id = ? AND tax_identifier = ?
                    """, Integer.class, companyId, nif);
            if (n != null && n > 0) return;
            jdbc.update("""
                    INSERT INTO suppliers (id, company_id, legal_name, tax_identifier, country, active)
                    VALUES (?, ?, ?, ?, 'Espana', TRUE)
                    """,
                    UUID.randomUUID().toString(), companyId,
                    name == null || name.isBlank() ? nif : name, nif);
        } catch (Exception ex) {
            log.debug("REFLEJO: supplier no creado en {}: {}", companyId, ex.getMessage());
        }
    }

    private CompanyRef loadCompany(String id) {
        return jdbc.query("""
                SELECT id, legal_name, tax_identifier, company_type, parent_company_id
                  FROM companies WHERE id = ? LIMIT 1
                """, rs -> rs.next()
                        ? new CompanyRef(rs.getString("id"), rs.getString("legal_name"),
                                rs.getString("tax_identifier"), rs.getString("company_type"),
                                rs.getString("parent_company_id"))
                        : null,
                id);
    }

    private String buildConcept(SalesInvoice invoice, String issuerName) {
        StringBuilder sb = new StringBuilder("Fra. ");
        if (invoice.invoiceNumber() != null) sb.append(invoice.invoiceNumber()).append(' ');
        if (issuerName != null) sb.append("- ").append(issuerName);
        String s = sb.toString().trim();
        return s.length() > 240 ? s.substring(0, 240) : s;
    }

    private record CompanyRef(String id, String legalName, String taxIdentifier,
                              String companyType, String parentCompanyId) {}
}
