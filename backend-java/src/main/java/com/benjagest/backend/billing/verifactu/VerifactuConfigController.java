package com.benjagest.backend.billing.verifactu;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/billing/verifactu-config -> configuracion actual.
 * PUT /api/billing/verifactu-config -> guarda modo + certificado + pie.
 *
 * Solo OWNER/ADMIN: activar o cambiar el modo VeriFactu es una decision
 * que afecta a todos los empleados que facturan.
 */
@RestController
@RequestMapping("/api/billing/verifactu-config")
@RequiresModule("billing")
@RequiresRole({"OWNER", "ADMIN"})
public class VerifactuConfigController {

    private final VerifactuConfigService service;

    public VerifactuConfigController(VerifactuConfigService service) {
        this.service = service;
    }

    @GetMapping
    public VerifactuConfig get() {
        return service.get();
    }

    @PutMapping
    public VerifactuConfig update(@Valid @RequestBody VerifactuConfigUpdateRequest request) {
        return service.update(request);
    }
}
