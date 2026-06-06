package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.util.List;
import java.util.Map;
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
 * API REST para gestionar el aprendizaje contable.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/accounting/learning/rules} → lista de reglas activas.</li>
 *   <li>{@code GET  /api/accounting/learning/entries/{id}/events} → histórico
 *       de propuestas y correcciones del asiento.</li>
 *   <li>{@code POST /api/accounting/learning/corrections} → registra una
 *       corrección puntual (sin necesidad de revalidar todo el asiento).</li>
 *   <li>{@code POST /api/accounting/learning/entries/{id}/accept} → marca
 *       el asiento como aceptado tal cual (refuerza reglas aplicadas).</li>
 *   <li>{@code PUT/DELETE /api/accounting/learning/rules/{id}} → activar/
 *       desactivar o borrar la regla manualmente.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/accounting/learning")
@RequiresModule("accounting")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class AccountingLearningController {

    private final AccountingLearningService service;
    private final CurrentUserService currentUserService;

    public AccountingLearningController(AccountingLearningService service,
                                         CurrentUserService currentUserService) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/rules")
    public List<AccountingLearningService.LearningRule> listRules(
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "active", required = false) Boolean active) {
        return service.listRules(kind, active);
    }

    @PutMapping("/rules/{id}")
    public Map<String, Object> updateRule(
            @PathVariable("id") String ruleId,
            @RequestBody RuleUpdate body) {
        boolean active = body == null || body.active() == null ? true : body.active();
        service.setRuleActive(ruleId, active);
        return Map.of("id", ruleId, "active", active);
    }

    @DeleteMapping("/rules/{id}")
    public Map<String, Object> deleteRule(@PathVariable("id") String ruleId) {
        service.deleteRule(ruleId);
        return Map.of("id", ruleId, "deleted", true);
    }

    @GetMapping("/entries/{id}/events")
    public List<AccountingLearningService.LearningEvent> entryEvents(
            @PathVariable("id") String entryId) {
        return service.listEventsForEntry(entryId);
    }

    @PostMapping("/entries/{id}/accept")
    public Map<String, Object> acceptEntry(
            @PathVariable("id") String entryId,
            @RequestBody AcceptanceRequest body) {
        String userId = safeUserId();
        service.recordAcceptance(entryId,
                body == null ? List.of() : body.appliedRuleIds(),
                userId);
        return Map.of("id", entryId, "accepted", true);
    }

    @PostMapping("/corrections")
    public Map<String, Object> registerCorrection(
            @RequestBody AccountingLearningService.CorrectionRequest req) {
        String userId = safeUserId();
        String newRuleId = service.recordCorrection(req, userId);
        return Map.of(
                "entryId", req.journalEntryId(),
                "lineId", req.lineId() == null ? "" : req.lineId(),
                "newRuleId", newRuleId == null ? "" : newRuleId);
    }

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    public record RuleUpdate(Boolean active) {}
    public record AcceptanceRequest(List<String> appliedRuleIds) {}
}
