package com.benjagest.backend.billing.catalog;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * CONC-1 — Conceptos reutilizables de las líneas de factura.
 *
 * <pre>
 *   GET    /api/billing/catalog-items       guardados + usados en facturas.
 *   POST   /api/billing/catalog-items       guarda uno (idempotente por nombre).
 *   PUT    /api/billing/catalog-items/{id}  edita uno guardado.
 *   DELETE /api/billing/catalog-items/{id}  baja LOGICA (active=FALSE).
 * </pre>
 *
 * Operacional (lo usa cualquiera que facture): mismo guard que el resto de
 * facturacion — modulo billing + OWNER/ADMIN/ACCOUNTANT/EMPLOYEE.
 */
@RestController
@RequestMapping("/api/billing/catalog-items")
@RequiresModule("billing")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class CatalogItemController {

    private final CatalogItemService service;

    public CatalogItemController(CatalogItemService service) {
        this.service = service;
    }

    @GetMapping
    public List<InvoiceConcept> list() {
        return service.listConcepts();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogItem create(@RequestBody CatalogItemService.UpsertRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public CatalogItem update(@PathVariable("id") String id,
                              @RequestBody CatalogItemService.UpsertRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        service.delete(id);
    }
}
