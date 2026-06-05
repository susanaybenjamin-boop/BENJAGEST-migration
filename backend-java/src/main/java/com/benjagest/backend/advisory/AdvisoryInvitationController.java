package com.benjagest.backend.advisory;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API REST de invitaciones asesoría↔empresario.
 *
 * Dos áreas:
 *
 *   /api/advisory/invitations        — endpoints de la ASESORÍA
 *     (requiere módulo advisory activo en el tenant actual).
 *   /api/advisory/my-invitations     — endpoints del EMPRESARIO
 *     (no requiere módulo advisory porque el cliente NO lo tiene).
 *
 * Esta separación de paths evita que el RequiresModule rechace al
 * empresario por no tener "advisory" activo — su lado del flujo vive
 * en un controller independiente.
 */
public class AdvisoryInvitationController {

    @RestController
    @RequestMapping("/api/advisory/invitations")
    @RequiresModule("advisory")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class AdvisoryEndpoints {
        private final AdvisoryInvitationService service;

        public AdvisoryEndpoints(AdvisoryInvitationService service) {
            this.service = service;
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public AdvisoryInvitation create(
                @Valid @RequestBody AdvisoryInvitationService.CreateRequest req) {
            return service.create(req);
        }

        @GetMapping
        public List<AdvisoryInvitation> listMine() {
            return service.listForCurrentAdvisory();
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void revoke(@PathVariable("id") String id) {
            service.revoke(id);
        }
    }

    /**
     * Endpoints del empresario — accesibles desde el tenant CLIENT
     * sin requerir el módulo advisory.
     */
    @RestController
    @RequestMapping("/api/advisory/my-invitations")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class ClientEndpoints {
        private final AdvisoryInvitationService service;

        public ClientEndpoints(AdvisoryInvitationService service) {
            this.service = service;
        }

        /** Invitaciones PENDING que apuntan a mi NIF o email. */
        @GetMapping
        public List<AdvisoryInvitation> listPending() {
            return service.listPendingForCurrentClient();
        }

        /** Acepta una invitación por token. */
        @PostMapping("/accept")
        public AdvisoryInvitation accept(@RequestParam("token") String token) {
            return service.accept(token);
        }

        /** Rechaza una invitación por token. */
        @PostMapping("/reject")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void reject(@RequestParam("token") String token) {
            service.reject(token);
        }

        /**
         * Información de la asesoría a la que estoy vinculado (si la
         * hay). Útil para mostrar "Mi asesoría: X" en la UI.
         */
        @GetMapping("/linked")
        public AdvisoryInvitationService.LinkedAdvisoryInfo linkedAdvisory() {
            return service.findCurrentLinkedAdvisory().orElse(null);
        }

        /** Rompe el vínculo con la asesoría actual. */
        @PostMapping("/unlink")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void unlink() {
            service.unlinkCurrentAdvisory();
        }
    }
}
