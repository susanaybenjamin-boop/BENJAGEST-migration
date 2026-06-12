package com.benjagest.backend.billing.tpb;

import com.benjagest.backend.auth.RequiresRole;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * API REST de los acuerdos de facturación por tercero.
 *
 * <p>Sin {@code @RequiresModule}: el acuerdo es prerrequisito legal
 * antes de poder emitir nada por tercero, así que está disponible
 * para cualquier tenant aunque el módulo facturación no esté activo.
 * El control de quién puede operar (asesoría vs cliente) lo aplica el
 * service según el {@code TenantContext}.
 */
@RestController
@RequestMapping("/api/billing/third-party-agreements")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class ThirdPartyBillingAgreementController {

    private final ThirdPartyBillingAgreementService service;
    private final TpbMagicLinkService magicLinkService;

    public ThirdPartyBillingAgreementController(ThirdPartyBillingAgreementService service,
                                                  TpbMagicLinkService magicLinkService) {
        this.service = service;
        this.magicLinkService = magicLinkService;
    }

    @GetMapping
    public List<ThirdPartyBillingAgreement> list() {
        return service.listForCurrentTenant();
    }

    @GetMapping("/current")
    public ThirdPartyBillingAgreement current(@RequestParam("otherCompanyId") String otherCompanyId) {
        return service.findCurrent(otherCompanyId).orElse(null);
    }

    @PostMapping
    public ThirdPartyBillingAgreement propose(
            @RequestBody ThirdPartyBillingAgreementService.ProposeRequest req) {
        return service.propose(req);
    }

    @PostMapping("/{id}/sign-with-pin")
    public ThirdPartyBillingAgreement signWithPin(
            @PathVariable("id") String id,
            @RequestBody SignWithPinRequest body) {
        return service.signWithPin(id, body.pin());
    }

    @PostMapping(value = "/{id}/sign-with-offline-pdf",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ThirdPartyBillingAgreement signWithOfflinePdf(
            @PathVariable("id") String id,
            @RequestPart("file") MultipartFile file) {
        return service.signWithOfflinePdf(id, file);
    }

    @GetMapping("/{id}/proposal-pdf")
    public ResponseEntity<byte[]> proposalPdf(@PathVariable("id") String id) {
        byte[] pdf = service.generateProposalPdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition",
                        "attachment; filename=\"acuerdo-facturacion-tercero-" + id + ".pdf\"")
                .body(pdf);
    }

    @GetMapping("/{id}/signed-pdf")
    public ResponseEntity<byte[]> signedPdf(@PathVariable("id") String id) {
        byte[] pdf = service.downloadSignedPdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition",
                        "attachment; filename=\"acuerdo-firmado-" + id + ".pdf\"")
                .body(pdf);
    }

    /**
     * Magic Link + OTP: la asesoria pide al backend enviar al email del
     * cliente un enlace de firma electronica (eIDAS art. 25). Decision
     * Benjamin 2026-06-12 — sustituye al flujo offline-PDF bloqueado.
     */
    @PostMapping("/{id}/magic-link/send")
    public TpbMagicLinkService.SendResult sendMagicLink(
            @PathVariable("id") String id,
            @RequestBody SendMagicLinkRequest body) {
        return magicLinkService.sendMagicLink(id, body.email());
    }

    public record SendMagicLinkRequest(String email) {}

    /**
     * Diagnostico / reparacion manual: fuerza la creacion de la serie
     * TPB del acuerdo. Idempotente: si ya existe la devuelve. Si falla,
     * propaga el error (a diferencia del catch silencioso de signWithPin).
     * Sirve para reparar acuerdos firmados antes del fix de auto-repair.
     */
    @PostMapping("/{id}/ensure-series")
    public Map<String, Object> ensureSeries(@PathVariable("id") String id) {
        var result = service.ensureSeriesForAgreement(id);
        return Map.of(
                "seriesId", result.seriesId(),
                "code", result.code(),
                "nextNumber", result.nextNumber(),
                "created", result.created());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> revoke(
            @PathVariable("id") String id,
            @RequestParam(value = "reason", required = false) String reason) {
        ThirdPartyBillingAgreement a = service.revoke(id, reason);
        return Map.of("id", a.id(), "status", a.status());
    }

    public record SignWithPinRequest(String pin) {}
}
