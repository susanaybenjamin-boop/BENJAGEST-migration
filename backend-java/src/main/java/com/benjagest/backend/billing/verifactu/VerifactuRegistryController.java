package com.benjagest.backend.billing.verifactu;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Listado del registro VeriFactu para investigar el estado de envio
 * de cada factura. Filtros opcionales:
 *   - mode   (TEST | PROD)
 *   - status (PENDING | SENT | ACKNOWLEDGED | ERROR)
 *   - limit  (1..500, default 100)
 *
 * No expone POST: el alta del registro se hace automaticamente al
 * validar una factura (hook en SalesInvoiceService.validate). El
 * usuario solo lee y debugea.
 */
@RestController
@RequestMapping("/api/billing/verifactu-registry")
@RequiresModule("billing")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class VerifactuRegistryController {

    private final VerifactuRegistryService service;

    public VerifactuRegistryController(VerifactuRegistryService service) {
        this.service = service;
    }

    @GetMapping
    public List<VerifactuRegistryEntry> list(@RequestParam(value = "mode", required = false) String mode,
                                             @RequestParam(value = "status", required = false) String status,
                                             @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return service.list(mode, status, limit);
    }

    /**
     * Verifica la integridad de la cadena hash para el modo indicado
     * (TEST por defecto). Recorre todos los registros en orden cronológico
     * y recalcula el hash con el mismo input. Si en algún punto no
     * coincide, devuelve la primera factura sospechosa y para; cualquier
     * cosa posterior ya no es de fiar.
     *
     * Si la empresa nunca ha emitido nada en ese modo, devuelve ok=true,
     * totalChecked=0 (cadena vacía es válida).
     */
    @GetMapping("/verify")
    public VerifactuRegistryService.IntegrityReport verify(
            @RequestParam(value = "mode", defaultValue = "TEST") String mode) {
        return service.verifyChain(mode);
    }
}
