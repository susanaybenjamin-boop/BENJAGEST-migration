package com.benjagest.backend.accounting.importpdf;

import com.benjagest.backend.accounting.IncomeAccountClassifierService;
import com.benjagest.backend.accounting.TerceroAccountResolverService;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
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
 * <p>El asiento queda en DRAFT con {@code source_pdf_path} apuntando al
 * fichero archivado para que el asesor pueda revisarlo antes de validar.
 */
@Service
public class SalesPdfImportService {

    private static final String SRC_TYPE = "SALES_PDF_IMPORT";

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;
    private final TerceroAccountResolverService terceroResolver;
    private final IncomeAccountClassifierService classifier;
    private final ImportedPdfStorageService storage;

    public SalesPdfImportService(JdbcTemplate jdbcTemplate,
                                   TenantContext tenantContext,
                                   CurrentUserService currentUserService,
                                   TerceroAccountResolverService terceroResolver,
                                   IncomeAccountClassifierService classifier,
                                   ImportedPdfStorageService storage) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.terceroResolver = terceroResolver;
        this.classifier = classifier;
        this.storage = storage;
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
        String userId = currentUserService.require().userId();

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

        // 4) Crear journal_entry en DRAFT (entry_number=NULL — se asigna
        //    al validar, según V58).
        String entryId = UUID.randomUUID().toString();
        String concept = buildConcept(req);
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, created_by,
                    source_pdf_path, source_pdf_sha256
                ) VALUES (?, ?, ?, NULL, ?, ?, ?, NULL, 'DRAFT', FALSE, TRUE, ?, ?, ?)
                """,
                entryId, companyId, fiscalYearId,
                Date.valueOf(req.invoiceDate()),
                concept, SRC_TYPE, userId,
                stored == null ? null : stored.absolutePath(),
                stored == null ? null : stored.sha256());

        // 5) Líneas con descripciones específicas por línea (mismo patrón
        //    que SalesJournalEntryService.createForSales).
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

        insertLine(entryId, clientAcc.accountId(), clientDesc, clientDebit, BigDecimal.ZERO);
        if (acc473 != null) {
            insertLine(entryId, acc473, "Retención IRPF — " + clientDesc,
                    retention, BigDecimal.ZERO);
        }
        insertLine(entryId, acc7xx, incomeDesc, BigDecimal.ZERO, req.baseAmount());
        insertLine(entryId, acc477, vatDesc, BigDecimal.ZERO, req.vatAmount());

        return new Result(entryId, 0, stored == null ? null : stored.sha256());
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

    private void insertLine(String entryId, String accountId, String description,
                              BigDecimal debit, BigDecimal credit) {
        jdbcTemplate.update("""
                INSERT INTO journal_entry_lines (
                    id, journal_entry_id, account_id, description, debit, credit
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entryId, accountId,
                description == null || description.length() <= 240 ? description
                        : description.substring(0, 240),
                debit, credit);
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
