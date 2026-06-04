package com.benjagest.backend.billing.manufacturer;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expone la declaracion responsable del fabricante del SIF al
 * usuario final. RD 1007/2023 + Orden HAC/1177/2024 art. 15.
 *
 * Es informacion publica del producto — no requiere autenticacion
 * fuerte, pero limitamos a roles internos para que no aparezca en
 * cualquier vista publica accidentalmente.
 *
 * Endpoints:
 *   - GET /api/billing/manufacturer-declaration  → declaracion completa.
 */
@RestController
@RequestMapping("/api/billing/manufacturer-declaration")
@RequiresModule("billing")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class ManufacturerDeclarationController {

    @GetMapping
    public ManufacturerDeclaration get() {
        return ManufacturerDeclaration.current();
    }
}
