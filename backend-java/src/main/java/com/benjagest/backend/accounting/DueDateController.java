package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.RequiresRole;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PAGO-PROVEEDOR (PV-3) — Vencimientos de factura (compras y ventas).
 *
 *   GET  /api/due-dates?invoiceKind=&invoiceId=   -> lista (crea 1 por defecto)
 *   PUT  /api/due-dates?invoiceKind=&invoiceId=   -> reemplaza el cuadro (valida total)
 *   POST /api/due-dates/{id}/pay                  -> salda contra tesorería
 *   POST /api/due-dates/{id}/unpay                -> revierte el pago
 *
 * Cross-cutting (se usa desde Compras y Facturación). Solo rol operacional;
 * el módulo se valida en la pantalla que lo invoca.
 */
@RestController
@RequestMapping("/api/due-dates")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class DueDateController {

    private final PaymentScheduleService service;

    public DueDateController(PaymentScheduleService service) {
        this.service = service;
    }

    @GetMapping
    public List<PaymentScheduleService.DueDate> list(
            @RequestParam("invoiceKind") String invoiceKind,
            @RequestParam("invoiceId") String invoiceId) {
        return service.listByInvoice(invoiceKind, invoiceId);
    }

    @PutMapping
    public List<PaymentScheduleService.DueDate> replace(
            @RequestParam("invoiceKind") String invoiceKind,
            @RequestParam("invoiceId") String invoiceId,
            @RequestBody List<PaymentScheduleService.DueDateRequest> rows) {
        return service.replaceSchedule(invoiceKind, invoiceId, rows);
    }

    @PostMapping("/{id}/pay")
    public PaymentScheduleService.DueDate pay(
            @PathVariable("id") String id,
            @RequestBody PaymentScheduleService.PayRequest req) {
        return service.pay(id, req);
    }

    @PostMapping("/{id}/unpay")
    public PaymentScheduleService.DueDate unpay(@PathVariable("id") String id) {
        return service.unpay(id);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true);
    }
}
