package com.benjagest.backend.aeat;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API REST de los modelos AEAT extra (347, 390, 190).
 *
 * <ul>
 *   <li>{@code GET  /api/aeat/extras/347/{year}/preview} → calcula sin persistir.</li>
 *   <li>{@code POST /api/aeat/extras/347/{year}/generate} → calcula + persiste DRAFT.</li>
 *   <li>(idem 390 y 190)</li>
 * </ul>
 *
 * <p>El preview es útil para mostrar al asesor antes de generar el filing
 * oficial. El generate crea/actualiza el {@code tax_filings} con DRAFT;
 * tras revisar, el asesor lo marca READY desde el módulo Modelos AEAT
 * existente.
 */
@RestController
@RequestMapping("/api/aeat/extras")
@RequiresModule("tax")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class AeatExtraModelsController {

    private final AeatExtraModelsService service;
    private final VatCompensationBaselineService vatBaselineService;

    public AeatExtraModelsController(AeatExtraModelsService service,
                                     VatCompensationBaselineService vatBaselineService) {
        this.service = service;
        this.vatBaselineService = vatBaselineService;
    }

    // -- 347 --
    @GetMapping("/347/{year}/preview")
    public AeatExtraModelsService.Model347View preview347(@PathVariable("year") int year) {
        return service.generate347(year, false);
    }

    @PostMapping("/347/{year}/generate")
    public AeatExtraModelsService.Model347View generate347(@PathVariable("year") int year) {
        return service.generate347(year, true);
    }

    // -- 390 --
    @GetMapping("/390/{year}/preview")
    public AeatExtraModelsService.Model390View preview390(@PathVariable("year") int year) {
        return service.generate390(year, false);
    }

    @PostMapping("/390/{year}/generate")
    public AeatExtraModelsService.Model390View generate390(@PathVariable("year") int year) {
        return service.generate390(year, true);
    }

    // -- 190 --
    @GetMapping("/190/{year}/preview")
    public AeatExtraModelsService.Model190View preview190(@PathVariable("year") int year) {
        return service.generate190(year, false);
    }

    @PostMapping("/190/{year}/generate")
    public AeatExtraModelsService.Model190View generate190(@PathVariable("year") int year) {
        return service.generate190(year, true);
    }

    // ---- Modelo 303 (IVA trimestral) ----
    @GetMapping("/303/{year}/{quarter}/preview")
    public AeatExtraModelsService.Model303View preview303(
            @PathVariable("year") int year, @PathVariable("quarter") int quarter) {
        return service.generate303(year, quarter, false);
    }

    @PostMapping("/303/{year}/{quarter}/generate")
    public AeatExtraModelsService.Model303View generate303(
            @PathVariable("year") int year, @PathVariable("quarter") int quarter) {
        return service.generate303(year, quarter, true);
    }

    // ---- Modelo 130 (IRPF pago fraccionado) ----
    @GetMapping("/130/{year}/{quarter}/preview")
    public AeatExtraModelsService.Model130View preview130(
            @PathVariable("year") int year, @PathVariable("quarter") int quarter) {
        return service.generate130(year, quarter, false);
    }

    @PostMapping("/130/{year}/{quarter}/generate")
    public AeatExtraModelsService.Model130View generate130(
            @PathVariable("year") int year, @PathVariable("quarter") int quarter) {
        return service.generate130(year, quarter, true);
    }

    // ---- IVA-COMP: saldo inicial de cuotas de IVA a compensar (303) ----
    @GetMapping("/303/compensation-baseline")
    public VatCompensationBaselineService.Baseline getVatBaseline() {
        return vatBaselineService.get();
    }

    @PostMapping("/303/compensation-baseline")
    public VatCompensationBaselineService.Baseline setVatBaseline(
            @org.springframework.web.bind.annotation.RequestBody
            VatCompensationBaselineService.Baseline body) {
        return vatBaselineService.upsert(body);
    }
}
