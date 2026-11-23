package com.benjagest.backend.advisory;

import com.benjagest.backend.auth.RequiresRole;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MODE-UP — Subida de EMPRESARIO a ASESORIA.
 *
 * <p>Controller aparte, y a proposito: va <b>sin</b> {@code @RequiresModule}
 * porque quien llama es un empresario, que NO tiene el modulo "advisory"
 * activo. Si viviera en {@link AdvisoryInvitationController.AdvisoryEndpoints}
 * el interceptor de modulos lo rechazaria antes de llegar al servicio.
 *
 * <p>Solo OWNER: es un cambio estructural de la cuenta.
 */
@RestController
@RequestMapping("/api/advisory/upgrade")
@RequiresRole({"OWNER"})
public class AdvisoryUpgradeController {

    private final AdvisoryUpgradeService service;

    public AdvisoryUpgradeController(AdvisoryUpgradeService service) {
        this.service = service;
    }

    @PostMapping
    public AdvisoryUpgradeService.UpgradeResult upgrade() {
        return service.upgradeActiveCompany();
    }
}
