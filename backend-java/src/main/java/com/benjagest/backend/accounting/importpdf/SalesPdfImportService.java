package com.benjagest.backend.accounting.importpdf;

import com.benjagest.backend.accounting.IncomeAccountClassifierService;
import com.benjagest.backend.accounting.ManualJournalEntryService;
import com.benjagest.backend.accounting.ManualJournalEntryService.LineRequest;
import com.benjagest.backend.accounting.ManualJournalEntryService.ManualEntryRequest;
import com.benjagest.backend.accounting.ManualJournalEntryService.ManualEntryView;
import com.benjagest.backend.accounting.TerceroAccountResolverService;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.io.IOException;
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
 * Crea un asiento contable de venta DIRECTAMENTE desde un PDF importado.
 *
 * <p>Por qué este flujo en vez del de SalesJournalEntryService:
 * {@link com.benjagest.backend.billing.invoices.SalesJournalEntryService}
 * arranca de una {@code sales_invoice} validada por el empresario emisor.
 * Pero cuando la asesoría gestiona un cliente NO vinculado, el empresario
 * emitió la factura en otro sistema y la asesoría solo tiene el PDF. No
 * tiene sentido crear una factura legal en BENJAGEST (no es el emisor),
 * solo el asiento contable para el modelo 303.
 *
 * <p>Asiento estándar de venta:
 * <pre>
 *   Debe   430xxx (sub-cuenta del cliente)    = total - retención
 *   Debe   473    (retenciones a cuenta)      = retención (si aplica)
 *                Haber  7xx (ingreso)         = base imponible
 *                Haber  477 (IVA repercutido) = cuota IVA
 * </pre>
 *
 * <p>TEMA 2 (2026-07-12): antes solo se creaba un asiento DRAFT y la factura
 * NO aparecía en Facturación (el listado lee de {@code sales_invoices}). Ahora,
 * como el import del diario CONTENDO, se crea también la factura como
 * <b>HISTORICAL/VALIDATED</b> en {@code sales_invoices} + su línea, y el asiento
 * entra <b>POSTED enlazado</b> ({@code source_type=SALES_INVOICE} +
 * {@code source_id}) para que el 303 la cuente <b>una sola vez</b> (igual que el
 * diario; NUNCA vía {@code SalesInvoiceService} — línea roja SIF). El
 * {@code source_pdf_path} se conserva en el asiento para el visor.
 */
@Service
public class SalesPdfImportService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;
    private final TerceroAccountResolverService terceroResolver;
    private final IncomeAccountClassifierService classifier;
    private final ImportedPdfStorageService storage;
    private final ManualJournalEntryService manualEntries;

    public SalesPdfImportService(JdbcTemplate jdbcTemplate,
                                   TenantContext tenantContext,
                                   CurrentUserService currentUserService,
                                   TerceroAccountResolverService terceroResolver,
                                   IncomeAccountClassifierService classifier,
                                   ImportedPdfStorageService storage,
                                   ManualJournalEntryService manualEntries) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.terceroResolver = terceroResolver;
        this.classifier = classifier;
        this.storage = storage;
        this.manualEntries = manualEntries;
    }

    public record Request(
            String customerNif,
            String customerName,
            LocalDate invoiceDate,
            BigDecimal baseAmount,
            BigDecimal vatPercent,
            BigDecimal vatAmount,
            BigDecimal retentionAmount,
            BigDecimal totalAmount,
            String invoiceNumber,
            String concept,
            byte[] pdfBytes,
            /** TRUE si el PDF es una factura rectificativa. */
            boolean rectifying,
            /** Nº de la factura ORIGINAL que se anula (opcional). */
            String rectifiedInvoiceNumber
    ) {
        // Constructor de compatibilidad con el callsite antiguo.
        public Request(String customerNif, String customerName,
                       LocalDate invoiceDate,
                       BigDecimal baseAmount, BigDecimal vatPercent,
                       BigDecimal vatAmount, BigDecimal retentionAmount,
                       BigDecimal totalAmount,
                       String invoiceNumber, String concept,
                       byte[] pdfBytes) {
            this(customerNif, customerName, invoiceDate,
                    baseAmount, vatPercent, vatAmount, retentionAmount,
                    totalAmount, invoiceNumber, concept, pdfBytes,
                    false, null);
        }
    }

    public record Result(String journalEntryId, int entryNumber, String pdfSha256) {}

    @Transactional
    public Result importAsJournalEntry(Request req) throws IOException {
        if (req.invoiceDate() == null
                || req.baseAmount() == null
                || req.vatAmount() == null
                || req.totalAmount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Faltan campos obligatorios (fecha, base, IVA, total).");
        }
        String companyId = tenantContext.getCurrentCompanyId();
        currentUserService.require(); // valida sesión (el created_by lo pone createImportedPosted)

        // 1) Resolver fiscal year OPEN.
        String fiscalYearId = findOpenFiscalYearId(companyId, req.invoiceDate());
        if (fiscalYearId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No hay ejercicio fiscal OPEN para esa fecha.");
        }

        // 2) Cuentas:
        //    - 430xxx → sub-cuenta del cliente (auto-creada por NIF/nombre)
        //    - 7xx     → classifier (concept → 700/705/759…) o fallback 700
        //    - 477     → IVA repercutido genérico
        //    - 473     → retenciones (solo si hay)
        var clientAcc = terceroResolver.getOrCreateForCustomer(
                req.customerNif(), req.customerName());
        if (clientAcc == null || clientAcc.accountId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se pudo crear/encontrar la sub-cuenta del cliente.");
        }

        String acc7xx = classifier.classify(req.concept(), req.customerName())
                .map(code -> findAccountByCode(companyId, code))
                .filter(java.util.Objects::nonNull)
                .orElseGet(() -> findAccountByPrefix(companyId, "700"));
        String acc477 = findAccountByPrefix(companyId, "477");
        if (acc7xx == null || acc477 == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se encontraron cuentas 7xx/477 en el plan.");
        }
        BigDecimal retention = req.retentionAmount() == null
                ? BigDecimal.ZERO : req.retentionAmount();
        String acc473 = null;
        if (retention.signum() > 0) {
            acc473 = findAccountByPrefix(companyId, "473");
        }

        // 3) Guardar el PDF en disco (deduplicado por SHA).
        ImportedPdfStorageService.Stored stored = req.pdfBytes() == null
                ? null
                : storage.store(companyId, req.invoiceDate().getYear(), req.pdfBytes());

        // 3b) DEDUP POR SHA DEL PDF: si ESTE MISMO PDF ya generó un asiento (por
        //     este flujo o por el antiguo SALES_PDF_IMPORT que NO creaba factura),
        //     no volver a importarlo. Sin esto, reimportar el mismo PDF duplicaba
        //     el asiento y contaba el IVA DOS veces en el 303 (bug 2026-07-12).
        if (stored != null && stored.sha256() != null
                && journalEntryWithPdfExists(companyId, stored.sha256())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta factura ya está importada (mismo PDF).");
        }

        // 4) Cliente (por NIF) + dedup por nº+cliente. La factura importada se
        //    guarda como HISTORICAL/VALIDATED (igual que el diario CONTENDO):
        //    NO se emite (línea roja SIF), solo se refleja para que aparezca en
        //    Facturación y cuente en el 303 vía el asiento POSTED enlazado.
        String customerId = ensureCustomer(companyId, req.customerNif(), req.customerName());
        String invNumber = req.invoiceNumber() == null ? null : req.invoiceNumber().trim();
        if (invNumber != null && !invNumber.isBlank()
                && salesInvoiceExists(companyId, invNumber, customerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta factura ya está importada (nº " + invNumber + ").");
        }

        // 5) Construir las líneas del asiento (mismo patrón que antes).
        String clientDesc = req.customerName() != null && !req.customerName().isBlank()
                ? req.customerName() : "Cliente";
        String incomeDesc = req.concept() != null && !req.concept().isBlank()
                ? req.concept().trim()
                : ("Venta " + clientDesc);
        BigDecimal vatRate = req.vatPercent() != null
                ? req.vatPercent()
                : (req.baseAmount().signum() > 0
                        ? req.vatAmount().multiply(java.math.BigDecimal.valueOf(100))
                                .divide(req.baseAmount(), 0, java.math.RoundingMode.HALF_UP)
                        : null);
        String vatDesc = "IVA repercutido"
                + (vatRate != null ? " " + vatRate + "%" : "")
                + " — " + clientDesc;
        BigDecimal clientDebit = req.totalAmount().subtract(retention);

        List<LineRequest> lines = new ArrayList<>();
        lines.add(new LineRequest(clientAcc.accountId(), null, clientDesc, clientDebit, BigDecimal.ZERO));
        if (acc473 != null) {
            lines.add(new LineRequest(acc473, null, "Retención IRPF — " + clientDesc,
                    retention, BigDecimal.ZERO));
        }
        lines.add(new LineRequest(acc7xx, null, incomeDesc, BigDecimal.ZERO, req.baseAmount()));
        lines.add(new LineRequest(acc477, null, vatDesc, BigDecimal.ZERO, req.vatAmount()));

        // 6) Asiento POSTED ENLAZADO a la factura (source_type SALES_INVOICE +
        //    source_id) — EXACTAMENTE como el diario, para que el 303 cuente 1
        //    sola vez. NUNCA vía SalesInvoiceService (línea roja SIF).
        String invoiceId = UUID.randomUUID().toString();
        String concept = buildConcept(req);
        ManualEntryView entry = manualEntries.createImportedPosted(
                new ManualEntryRequest(req.invoiceDate(), concept, lines, true),
                "SALES_INVOICE", invoiceId);
        String entryId = entry.id();

        // 7) Conservar el PDF en el asiento (para el visor "ver PDF").
        if (stored != null) {
            jdbcTemplate.update("""
                    UPDATE journal_entries SET source_pdf_path = ?, source_pdf_sha256 = ?
                     WHERE id = ? AND company_id = ?
                    """, stored.absolutePath(), stored.sha256(), entryId, companyId);
        }

        // 8) Factura HISTORICAL en sales_invoices (para que aparezca en
        //    Facturación) + su línea + ruta del PDF.
        insertSalesInvoice(invoiceId, companyId, customerId, invNumber, req.invoiceDate(),
                req.baseAmount(), req.vatAmount(), retention, req.totalAmount(),
                concept, stored == null ? null : stored.absolutePath());
        insertSalesLine(invoiceId, incomeDesc, req.baseAmount(), req.vatAmount(),
                vatRate == null ? BigDecimal.ZERO : vatRate, req.totalAmount());

        return new Result(entryId, entry.entryNumber(), stored == null ? null : stored.sha256());
    }

    // ---- helpers --------------------------------------------------------

    private String findOpenFiscalYearId(String companyId, LocalDate date) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM fiscal_years
                 WHERE company_id = ? AND status = 'OPEN'
                   AND ? BETWEEN start_date AND end_date
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, Date.valueOf(date));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String findAccountByCode(String companyId, String code) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE AND code = ? LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, code);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String findAccountByPrefix(String companyId, String prefix) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE AND code LIKE ?
                 ORDER BY LENGTH(code), code LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, prefix + "%");
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Cliente por NIF (estable) y, si no, por nombre. Lo crea si no existe. */
    private String ensureCustomer(String companyId, String nif, String name) {
        String normNif = nif == null ? "" : nif.trim();
        String legal = name == null || name.isBlank() ? "Cliente" : name.trim();
        if (!normNif.isBlank()) {
            List<String> byNif = jdbcTemplate.query("""
                    SELECT id FROM customers
                     WHERE company_id = ? AND tax_identifier = ? LIMIT 1
                    """, (rs, n) -> rs.getString("id"), companyId, normNif);
            if (!byNif.isEmpty()) return byNif.get(0);
        }
        List<String> byName = jdbcTemplate.query("""
                SELECT id FROM customers
                 WHERE company_id = ? AND legal_name = ? LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, legal);
        if (!byName.isEmpty()) return byName.get(0);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO customers (id, company_id, legal_name, tax_identifier,
                                        customer_type, notes, active)
                VALUES (?, ?, ?, ?, 'COMPANY', 'Importado de factura PDF', TRUE)
                """, id, companyId, truncate(legal, 180), normNif.isBlank() ? null : normNif);
        return id;
    }

    /** Dedup: ya existe un asiento importado de ESE MISMO PDF (por su SHA-256). */
    private boolean journalEntryWithPdfExists(String companyId, String sha256) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM journal_entries
                 WHERE company_id = ? AND source_pdf_sha256 = ?
                """, Integer.class, companyId, sha256);
        return n != null && n > 0;
    }

    /** Dedup: ya existe esa factura (nº) para ese cliente en la empresa. */
    private boolean salesInvoiceExists(String companyId, String invoiceNumber, String customerId) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sales_invoices
                 WHERE company_id = ? AND invoice_number = ? AND customer_id = ?
                """, Integer.class, companyId, invoiceNumber, customerId);
        return n != null && n > 0;
    }

    /**
     * Inserta la factura como HISTORICAL/VALIDATED por SQL directo (NUNCA vía
     * SalesInvoiceService: línea roja VeriFactu/SIF), igual que el import del
     * diario. Aparece en Facturación; el 303 la cuenta por el asiento POSTED
     * enlazado, no por esta fila.
     */
    private void insertSalesInvoice(String invoiceId, String companyId, String customerId,
                                    String invoiceNumber, LocalDate date, BigDecimal base,
                                    BigDecimal vat, BigDecimal retention, BigDecimal total,
                                    String concept, String pdfPath) {
        jdbcTemplate.update("""
                INSERT INTO sales_invoices (
                    id, company_id, customer_id, series_id, invoice_number,
                    invoice_date, due_date, invoice_type, status, payment_status,
                    subtotal, vat_total, retention_total, total, paid_amount,
                    currency, original_invoice_id, concept, pdf_path, validated_at
                ) VALUES (?, ?, ?, NULL, ?, ?, ?, 'HISTORICAL', 'VALIDATED', 'PENDING',
                          ?, ?, ?, ?, 0, 'EUR', NULL, ?, ?, CURRENT_TIMESTAMP)
                """,
                invoiceId, companyId, customerId, invoiceNumber,
                date == null ? null : Date.valueOf(date),
                date == null ? null : Date.valueOf(date),
                base, vat, retention == null ? BigDecimal.ZERO : retention, total,
                truncate(concept, 240), pdfPath);
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

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String buildConcept(Request req) {
        // Concepto que devuelve el cliente (si lo editó el usuario)
        // tiene prioridad. Sino, generamos uno sensato.
        if (req.concept() != null && !req.concept().isBlank()) {
            String s = req.concept().trim();
            return s.length() > 240 ? s.substring(0, 240) : s;
        }
        StringBuilder sb = new StringBuilder();
        // Prefijo "Fra. rectificativa" si es rectificativa.
        if (req.rectifying()) {
            sb.append("Fra. rectificativa ");
        } else {
            sb.append("Fra. ");
        }
        if (req.invoiceNumber() != null && !req.invoiceNumber().isBlank()) {
            sb.append(req.invoiceNumber().trim()).append(' ');
        }
        if (req.customerName() != null && !req.customerName().isBlank()) {
            sb.append("a ").append(req.customerName().trim());
        }
        // Si es rectificativa, indicamos qué factura anula.
        if (req.rectifying() && req.rectifiedInvoiceNumber() != null
                && !req.rectifiedInvoiceNumber().isBlank()) {
            sb.append(" (anula ").append(req.rectifiedInvoiceNumber().trim()).append(')');
        }
        if (sb.length() == 0) sb.append("Venta importada");
        String s = sb.toString();
        return s.length() > 240 ? s.substring(0, 240) : s;
    }

    /**
     * Busca la factura ORIGINAL que está siendo anulada por una
     * rectificativa, dentro del catálogo de sales_invoices del mismo
     * cliente (matching por NIF).
     *
     * <p>Si la encuentra, devuelve el id; el caller la marca como
     * VOIDED y enlaza la rectificativa. Si NO la encuentra (la
     * original está en el sistema anterior del cliente o nunca se
     * importó), devuelve null — el asesor verá el nº de la original
     * en el concepto del asiento y decide manualmente.
     *
     * <p>NO se usa todavía porque sales_invoices NO contiene las
     * facturas importadas (solo las emitidas desde BENJAGEST).
     * Reservado para cuando completemos el slice de "listado de
     * facturación con facturas importadas".
     */
    @SuppressWarnings("unused")
    private String findOriginalInvoiceId(String companyId, String customerNif,
                                           String originalNumber) {
        if (originalNumber == null || originalNumber.isBlank()) return null;
        if (customerNif == null || customerNif.isBlank()) return null;
        List<String> ids = jdbcTemplate.query("""
                SELECT si.id
                  FROM sales_invoices si
                  JOIN customers c ON c.id = si.customer_id
                 WHERE si.company_id = ?
                   AND si.invoice_number = ?
                   AND c.tax_identifier = ?
                 LIMIT 1
                """,
                (rs, n) -> rs.getString("id"),
                companyId, originalNumber.trim(), customerNif.trim());
        return ids.isEmpty() ? null : ids.get(0);
    }
}
