package com.benjagest.backend.settings;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pestana "Email" de la pantalla de Configuracion. Permite a OWNER/ADMIN
 * configurar el SMTP por empresa y enviar un correo de prueba.
 *
 * La password se guarda cifrada y NUNCA se devuelve al cliente; el GET
 * solo dice si esta o no configurada.
 */
@RestController
@RequestMapping("/api/settings/email-config")
@RequiresModule("settings")
@RequiresRole({"OWNER", "ADMIN"})
public class EmailConfigController {

    private final EmailConfigService service;

    public EmailConfigController(EmailConfigService service) {
        this.service = service;
    }

    @GetMapping
    public EmailConfigResponse get() {
        return service.get();
    }

    @PutMapping
    public EmailConfigResponse update(@Valid @RequestBody EmailConfigUpdateRequest request) {
        return service.update(request);
    }

    @PostMapping("/test-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendTestEmail(@Valid @RequestBody TestEmailRequest request) {
        service.sendTestEmail(request);
    }
}
