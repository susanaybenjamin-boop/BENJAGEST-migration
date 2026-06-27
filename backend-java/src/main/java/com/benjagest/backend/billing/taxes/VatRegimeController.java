package com.benjagest.backend.billing.taxes;

import com.benjagest.backend.auth.RequiresRole;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** VAT-REGIME — régimen de IVA de la empresa (General / Prorrata / Criterio de caja). */
@RestController
@RequestMapping("/api/billing/vat-regime")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class VatRegimeController {

    private final VatRegimeService service;

    public VatRegimeController(VatRegimeService service) {
        this.service = service;
    }

    @GetMapping
    public VatRegimeService.Regime get() {
        return service.get();
    }

    public record SaveRequest(String regime, BigDecimal prorrataPercent) {}

    @PutMapping
    public VatRegimeService.Regime save(@RequestBody SaveRequest req) {
        return service.save(req.regime(), req.prorrataPercent());
    }
}
