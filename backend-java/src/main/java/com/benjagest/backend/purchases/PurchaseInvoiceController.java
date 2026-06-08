package com.benjagest.backend.purchases;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API REST de facturas de compra (gastos).
 *
 *   POST   /api/purchases/invoices           — guardar (201 ó 409 dedup)
 *   GET    /api/purchases/invoices           — listar (filtros opcionales)
 *   GET    /api/purchases/invoices/{id}      — detalle
 *   DELETE /api/purchases/invoices/{id}      — anular (status=VOID)
 *
 * Restringido al módulo {@code purchases} y a roles OWNER/ADMIN/ACCOUNTANT
 * (la asesoría usa ACCOUNTANT para registrar gastos en nombre del cliente
 * al switchear X-Company-Id).
 */
@RestController
@RequestMapping("/api/purchases/invoices")
@RequiresModule("purchases")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class PurchaseInvoiceController {

    private final PurchaseInvoiceService service;

    public PurchaseInvoiceController(PurchaseInvoiceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PurchaseInvoice> save(
            @Valid @RequestBody PurchaseInvoiceService.SaveRequest req) {
        var result = service.save(req);
        if (result.duplicate()) {
            // 409 Conflict + la factura ya existente (la UI redirige al detalle).
            return ResponseEntity.status(HttpStatus.CONFLICT).body(result.invoice());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.invoice());
    }

    @GetMapping
    public List<PurchaseInvoice> list(
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "supplierNif", required = false) String supplierNif) {
        return service.list(year, status, supplierNif);
    }

    @GetMapping("/{id}")
    public PurchaseInvoice get(@PathVariable("id") String id) {
        return service.get(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteInvoice(@PathVariable("id") String id) {
        service.deleteInvoice(id);
    }

    /**
     * Valida un lote de facturas DRAFT (multiselección).
     * Devuelve resumen por id: POSTED/SKIPPED/ERROR + journalEntryId si
     * se generó asiento contable. La UI presenta el resultado.
     */
    @PostMapping("/validate-batch")
    public PurchaseInvoiceService.BatchValidateResult validateBatch(
            @RequestBody PurchaseInvoiceService.BatchValidateRequest body) {
        return service.validateBatch(body == null ? List.of() : body.ids());
    }
}
