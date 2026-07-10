package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API REST de contabilidad para la asesoría:
 *
 * <ul>
 *   <li>{@code /journal-entries} → CRUD asientos manuales.</li>
 *   <li>{@code /diary} → Libro Diario por rango.</li>
 *   <li>{@code /ledger/{accountId}} → Libro Mayor por cuenta.</li>
 *   <li>{@code /balance} → Balance de Sumas y Saldos.</li>
 * </ul>
 *
 * <p>Roles: el módulo accounting solo lo manejan OWNER, ADMIN o
 * ACCOUNTANT. Las facturas las puede meter cualquiera (vienen por sus
 * propios endpoints), pero los asientos manuales son material del
 * contable.
 */
@RestController
@RequestMapping("/api/accounting")
@RequiresModule("accounting")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class AccountingJournalController {

    private final ManualJournalEntryService manualService;
    private final JournalQueryService queryService;
    private final ReclassifyJournalService reclassifyService;
    private final SalesAndExpensesKpiService kpiService;

    public AccountingJournalController(ManualJournalEntryService manualService,
                                         JournalQueryService queryService,
                                         ReclassifyJournalService reclassifyService,
                                         SalesAndExpensesKpiService kpiService) {
        this.manualService = manualService;
        this.queryService = queryService;
        this.reclassifyService = reclassifyService;
        this.kpiService = kpiService;
    }

    /**
     * KPIs rápidos para la pestaña "Ventas y Gastos" del cliente NO
     * vinculado: ventas, gastos, IVA repercutido, IVA soportado y
     * Modelo 303 estimado en el rango indicado.
     *
     * <p>El parámetro de fechas es opcional; si falta, se asume el
     * trimestre actual.
     */
    @GetMapping("/kpis/sales-and-expenses")
    public SalesAndExpensesKpiService.Kpis salesAndExpensesKpis(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from == null || to == null) {
            // Trimestre actual por defecto.
            LocalDate today = LocalDate.now();
            int q = (today.getMonthValue() - 1) / 3;
            from = LocalDate.of(today.getYear(), q * 3 + 1, 1);
            to = from.plusMonths(3).minusDays(1);
        }
        return kpiService.compute(from, to);
    }

    /**
     * Reclasifica asientos DRAFT: vuelve a calcular su cuenta 6xx/7xx con el
     * chain completo (histórico → classifier). Solo toca líneas con cuenta
     * genérica (600/629 en gastos, 700/759 en ventas). Idempotente.
     */
    @PostMapping("/reclassify")
    public ReclassifyJournalService.ReclassifyResult reclassifyDrafts() {
        return reclassifyService.reclassifyDrafts();
    }

    /**
     * Borra físicamente una lista de asientos importados (con
     * source_pdf_sha256). Pensado para resolver duplicados desde la
     * UI: el asesor elige qué copias eliminar y el resto sobrevive.
     *
     * <p>Restricciones: solo borra asientos del periodo NO cerrado y
     * que tengan source_type IN ('SALES_PDF_IMPORT', 'PURCHASE_INVOICE')
     * — los asientos manuales o de venta validada no se tocan aunque
     * el caller pase su id. Idempotente.
     */
    @PostMapping("/duplicates/delete")
    public Map<String, Object> deleteDuplicates(@RequestBody DeleteIdsBody body) {
        int n = manualService.deleteImportedByIds(
                body == null ? java.util.List.of() : body.ids());
        return Map.of("deleted", n);
    }

    public record DeleteIdsBody(List<String> ids) {}

    /**
     * Actualiza el concepto de un asiento existente. Pensado para que
     * el asesor pueda corregir desde el "Avisos → sin nº de factura"
     * sin tener que ir al editor completo del Diario.
     */
    @PostMapping("/journal-entries/{id}/update-concept")
    public Map<String, Object> updateConcept(
            @PathVariable("id") String id,
            @RequestBody UpdateConceptBody body) {
        String newConcept = body == null ? null : body.concept();
        int n = manualService.updateConcept(id, newConcept);
        return Map.of("id", id, "updated", n > 0);
    }

    public record UpdateConceptBody(String concept) {}

    /**
     * Re-extrae los datos del PDF asociado a un asiento usando la
     * regex/heurística ACTUAL. Útil cuando importamos un PDF antes
     * de mejorar el extractor y queremos retroceder y aplicar el
     * extractor nuevo sin volver a importar.
     *
     * <p>Devuelve los campos extraídos (nº factura, fechas, totales)
     * para que la UI los muestre como propuesta de edición — NO
     * persiste cambios automáticamente.
     */
    @PostMapping("/journal-entries/{id}/re-extract")
    public Map<String, Object> reExtractFromPdf(@PathVariable("id") String id) {
        return manualService.reExtractFromPdf(id);
    }

    // ====================================================================
    //  Asientos manuales
    // ====================================================================

    @PostMapping("/journal-entries")
    public ManualJournalEntryService.ManualEntryView create(
            @RequestBody ManualJournalEntryService.ManualEntryRequest req) {
        return manualService.createDraft(req);
    }

    @GetMapping("/journal-entries/{id}")
    public ManualJournalEntryService.ManualEntryView get(@PathVariable("id") String id) {
        return manualService.get(id);
    }

    @PutMapping("/journal-entries/{id}")
    public ManualJournalEntryService.ManualEntryView update(
            @PathVariable("id") String id,
            @RequestBody ManualJournalEntryService.ManualEntryRequest req) {
        return manualService.updateDraft(id, req);
    }

    @PostMapping("/journal-entries/{id}/post")
    public ManualJournalEntryService.ManualEntryView post(
            @PathVariable("id") String id,
            // DUP-VALIDAR: force=true valida aunque haya un posible gasto
            // duplicado (decisión del usuario tras el aviso 409).
            @org.springframework.web.bind.annotation.RequestParam(
                    value = "force", defaultValue = "false") boolean force) {
        return manualService.post(id, force);
    }

    /**
     * Valida un lote de asientos DRAFT (multiselección desde tab
     * "Por validar" del módulo Contabilidad). Cada uno pasa a POSTED,
     * los que ya estaban POSTED se cuentan como SKIPPED. Devuelve
     * resumen con detalle por id.
     */
    @PostMapping("/journal-entries/post-batch")
    public ManualJournalEntryService.BatchPostResult postBatch(
            @RequestBody ManualJournalEntryService.BatchPostRequest body) {
        return manualService.postBatch(body == null ? java.util.List.of() : body.ids());
    }

    @DeleteMapping("/journal-entries/{id}")
    public Map<String, Object> voidEntry(
            @PathVariable("id") String id,
            @RequestParam(value = "reason", required = false) String reason) {
        manualService.voidEntry(id, reason);
        return Map.of("id", id, "voided", true);
    }

    // ====================================================================
    //  Libros
    // ====================================================================

    @GetMapping("/diary")
    public List<JournalQueryService.DiaryEntry> diary(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return queryService.diary(from, to, status, sourceType,
                limit == null ? 500 : limit);
    }

    @GetMapping("/ledger/{accountId}")
    public JournalQueryService.LedgerView ledger(
            @PathVariable("accountId") String accountId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return queryService.ledger(accountId, from, to);
    }

    @GetMapping("/balance")
    public List<JournalQueryService.BalanceRow> balance(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "prefix", required = false) String prefix) {
        return queryService.balance(from, to, prefix);
    }

    /**
     * Listado de cuentas del plan contable de la empresa, para alimentar
     * los combos de selección de cuenta en el editor de asientos. Filtra
     * por substring (code o name) cuando llega {@code search}.
     */
    @GetMapping("/accounts")
    public java.util.List<java.util.Map<String, Object>> accounts(
            @RequestParam(value = "search", required = false) String search) {
        return queryService.listAccountsForCombos(search);
    }
}
