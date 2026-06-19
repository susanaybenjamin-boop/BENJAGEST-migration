package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ME-2 + ME-3 — ayudas del editor de asiento manual:
 *   GET /api/accounting/assist/open-invoices?accountCode=  → facturas pendientes del tercero.
 *   GET /api/accounting/assist/suggest?anchor=&exclude=a,b → cuentas sugeridas (histórico+reglas).
 */
@RestController
@RequestMapping("/api/accounting/assist")
@RequiresModule("accounting")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class ManualEntryAssistController {

    private final ManualEntryAssistService service;

    public ManualEntryAssistController(ManualEntryAssistService service) {
        this.service = service;
    }

    @GetMapping("/open-invoices")
    public List<ManualEntryAssistService.OpenInvoice> openInvoices(
            @RequestParam("accountCode") String accountCode) {
        return service.openInvoicesForAccount(accountCode);
    }

    @GetMapping("/suggest")
    public List<ManualEntryAssistService.SuggestedAccount> suggest(
            @RequestParam("anchor") String anchor,
            @RequestParam(value = "exclude", required = false) String exclude) {
        List<String> ex = (exclude == null || exclude.isBlank())
                ? List.of() : Arrays.asList(exclude.split(","));
        return service.suggest(anchor, ex);
    }
}
