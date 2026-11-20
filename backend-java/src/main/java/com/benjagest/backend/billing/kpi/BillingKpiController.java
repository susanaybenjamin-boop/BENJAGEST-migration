package com.benjagest.backend.billing.kpi;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * DASH-E2 — cuadro de mando de Facturacion.
 *
 * <pre>
 *   GET /api/billing/kpis?from=2026-01-01&amp;to=2026-11-20   indicadores del rango
 *   GET /api/billing/kpis/monthly?year=2026                  serie mensual (12 puntos)
 * </pre>
 *
 * <p>Operacional (lo usa el empresario en su propia pantalla de Facturacion),
 * asi que lleva EMPLOYEE en {@code @RequiresRole} igual que
 * {@code SalesInvoiceController} — no es administracion interna de la asesoria.
 */
@RestController
@RequestMapping("/api/billing/kpis")
@RequiresModule("billing")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class BillingKpiController {

    private final BillingKpiService service;

    public BillingKpiController(BillingKpiService service) {
        this.service = service;
    }

    @GetMapping
    public BillingKpiService.BillingKpis kpis(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha 'desde' no puede ser posterior a 'hasta'");
        }
        return service.compute(from, to);
    }

    @GetMapping("/monthly")
    public List<BillingKpiService.BillingMonthPoint> monthly(
            @RequestParam(value = "year", required = false) Integer year) {
        int y = year == null ? LocalDate.now().getYear() : year;
        if (y < 1990 || y > 2999) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ejercicio fuera de rango");
        }
        return service.monthlySeries(y);
    }
}
