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
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class AeatExtraModelsController {

    private final AeatExtraModelsService service;

    public AeatExtraModelsController(AeatExtraModelsService service) {
        this.service = service;
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
}
