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
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class AccountingJournalController {

    private final ManualJournalEntryService manualService;
    private final JournalQueryService queryService;

    public AccountingJournalController(ManualJournalEntryService manualService,
                                         JournalQueryService queryService) {
        this.manualService = manualService;
        this.queryService = queryService;
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
    public ManualJournalEntryService.ManualEntryView post(@PathVariable("id") String id) {
        return manualService.post(id);
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
