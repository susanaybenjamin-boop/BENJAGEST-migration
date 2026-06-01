package com.benjagest.backend.billing.invoices;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints REST de facturas emitidas.
 *
 *   GET    /api/billing/invoices                lista con filtros opcionales
 *   GET    /api/billing/invoices/{id}           una con lineas
 *   POST   /api/billing/invoices                crear (status DRAFT)
 *   PUT    /api/billing/invoices/{id}           editar DRAFT
 *   POST   /api/billing/invoices/{id}/validate  pasa de DRAFT a VALIDATED
 *                                               (emite numero de serie)
 *   DELETE /api/billing/invoices/{id}           soft cancel DRAFT
 *
 * Filtros del listado (query params):
 *   status        DRAFT | VALIDATED | CANCELLED | VOIDED
 *   paymentStatus PENDING | PARTIAL | PAID | OVERDUE
 *   customerId    UUID del cliente
 *   limit         1..500 (default 100)
 */
@RestController
@RequestMapping("/api/billing/invoices")
@RequiresModule("billing")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class SalesInvoiceController {

    private final SalesInvoiceService service;

    public SalesInvoiceController(SalesInvoiceService service) {
        this.service = service;
    }

    @GetMapping
    public List<SalesInvoice> list(@RequestParam(value = "status", required = false) String status,
                                   @RequestParam(value = "paymentStatus", required = false) String paymentStatus,
                                   @RequestParam(value = "customerId", required = false) String customerId,
                                   @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return service.list(status, paymentStatus, customerId, limit);
    }

    @GetMapping("/{id}")
    public SalesInvoice get(@PathVariable("id") String id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SalesInvoice create(@Valid @RequestBody InvoiceUpsertRequest request) {
        return service.createDraft(request);
    }

    @PutMapping("/{id}")
    public SalesInvoice update(@PathVariable("id") String id,
                               @Valid @RequestBody InvoiceUpsertRequest request) {
        return service.updateDraft(id, request);
    }

    @PostMapping("/{id}/validate")
    public SalesInvoice validate(@PathVariable("id") String id) {
        return service.validate(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        service.deleteDraft(id);
    }
}
