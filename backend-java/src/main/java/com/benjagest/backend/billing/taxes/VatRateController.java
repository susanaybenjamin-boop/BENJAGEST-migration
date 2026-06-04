package com.benjagest.backend.billing.taxes;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gestor del catalogo de tipos impositivos (IVA + IRPF).
 *
 *   GET    /api/billing/vat-rates              activos para la empresa.
 *   GET    /api/billing/vat-rates?all=true     todos (UI de configuracion).
 *   GET    /api/billing/vat-rates?kind=VAT     solo IVA / solo IRPF.
 *   POST   /api/billing/vat-rates              crea uno nuevo.
 *   PUT    /api/billing/vat-rates/{id}         actualiza (no cambia code/kind).
 *   DELETE /api/billing/vat-rates/{id}         borra si no es default.
 *
 * Disponible para los dos modos (asesoria/cliente) — requiere modulo
 * billing activo, rol OWNER/ADMIN/ACCOUNTANT.
 */
@RestController
@RequestMapping("/api/billing/vat-rates")
@RequiresModule("billing")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class VatRateController {

    private final VatRateService service;

    public VatRateController(VatRateService service) {
        this.service = service;
    }

    @GetMapping
    public List<VatRate> list(@RequestParam(value = "kind", required = false) String kind,
                              @RequestParam(value = "all", defaultValue = "false") boolean all) {
        return all ? service.listAll() : service.listActive(kind);
    }

    @PostMapping
    public VatRate create(@RequestBody VatRateService.UpsertRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public VatRate update(@PathVariable("id") String id,
                          @RequestBody VatRateService.UpsertRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        service.delete(id);
    }
}
