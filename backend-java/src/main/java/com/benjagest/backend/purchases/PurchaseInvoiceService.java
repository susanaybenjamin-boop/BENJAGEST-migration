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

    public PurchaseInvoiceService(PurchaseInvoiceRepository repository,
                                    PurchaseJournalEntryService journalService,
                                    CurrentUserService currentUserService,
                                    TenantContext tenantContext,
                                    AuditService auditService,
                                    com.benjagest.backend.accounting.FiscalYearGuardService fiscalGuard,
                                    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.journalService = journalService;
        this.currentUserService = currentUserService;
        this.tenantContext = tenantContext;
        this.auditService = auditService;
        this.fiscalGuard = fiscalGuard;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public SaveResult save(SaveRequest req) {
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
                blankToNull(req.concept()),
                blankToNull(req.notes()),
                user.userId(),
                uploaderCompany,
                null, null
        );

        // 2) Insertar.
        repository.insert(draft);

        // 3) Intentar asiento (best effort).
        String journalEntryId = null;
        try {
            journalEntryId = journalService.createForPurchase(draft, user.userId());
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
                        entryId = journalService.createForPurchase(existing, user.userId());
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
        PurchaseInvoice existing = get(id);
        // PURCHASES-CIERRE-FISCAL: si la factura cae en un ejercicio
        // LOCKED/CLOSED, no se puede borrar. La AEAT considera
        // alteración de un periodo presentado. Hay que rectificar
        // (factura con signo negativo en el ejercicio actual). El
        // guard lanza 409 con mensaje legible que la UI muestra.
        fiscalGuard.requireOpenForDate(existing.invoiceDate(), "eliminar esta factura");
        AuthenticatedUser user = currentUserService.require();
        String tenant = tenantContext.getCurrentCompanyId();
        // Auditoría primero: necesitamos la traza aunque luego el
        // DELETE no se materializara por algún motivo.
        auditService.recordPurchaseInvoiceDeleted(user.userId(), tenant,
                id, existing.totalAmount(), existing.supplierName(),
                existing.invoiceNumber());
        if (existing.journalEntryId() != null) {
            try {
                journalService.reverseForPurchase(existing);
            } catch (Exception ex) {
                System.err.println("[purchases] no se pudo revertir asiento "
                        + ex.getMessage());
            }
        }
        repository.deletePhysical(id);
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
            String notes
    ) {
        // Constructor compacto retro-compatible: callers viejos sin concept.
        public SaveRequest(String supplierNif, String supplierName,
                            String invoiceNumber, LocalDate invoiceDate,
                            BigDecimal baseAmount, BigDecimal vatPercent,
                            BigDecimal vatAmount, BigDecimal totalAmount,
                            String documentSha256, int invoiceIndexInPdf,
                            String notes) {
            this(supplierNif, supplierName, invoiceNumber, invoiceDate,
                    baseAmount, vatPercent, vatAmount, totalAmount,
                    documentSha256, invoiceIndexInPdf, null, notes);
        }
    }

    public record SaveResult(
            PurchaseInvoice invoice,
            boolean duplicate,
            String message
    ) {}

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
