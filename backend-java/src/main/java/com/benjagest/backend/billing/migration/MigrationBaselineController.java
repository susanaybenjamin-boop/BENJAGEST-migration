package com.benjagest.backend.billing.migration;

import com.benjagest.backend.auth.RequiresRole;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * MIG-1 — API del punto de partida de facturación (migración desde otro
 * programa). Empresario y asesoría (vía X-Company-Id del cliente gestionado).
 */
@RestController
@RequestMapping("/api/billing/migration-baseline")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class MigrationBaselineController {

    private final MigrationBaselineService service;

    public MigrationBaselineController(MigrationBaselineService service) {
        this.service = service;
    }

    public record ExtractPayload(String pdfBase64) {}

    /** Sube el PDF de la última factura y devuelve los campos autorellenados (OCR). */
    @PostMapping("/extract")
    public MigrationBaselineService.Extracted extract(@RequestBody ExtractPayload p) {
        if (p == null || p.pdfBase64() == null || p.pdfBase64().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta el PDF.");
        }
        return service.extract(Base64.getDecoder().decode(p.pdfBase64()));
    }

    public record ConfirmPayload(String seriesId, String declaredSeriesCode, String declaredFullNumber,
                                 Integer declaredNumber, String declaredDate, Integer declaredYear,
                                 String emitterNif, String customerNif, String customerName,
                                 BigDecimal totalAmount, String ocrConfidence, boolean declarationSigned,
                                 String declarationText, String pdfBase64) {}

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody ConfirmPayload p) {
        byte[] pdf = p.pdfBase64() == null || p.pdfBase64().isBlank()
                ? null : Base64.getDecoder().decode(p.pdfBase64());
        String id = service.confirm(new MigrationBaselineService.ConfirmRequest(
                p.seriesId(), p.declaredSeriesCode(), p.declaredFullNumber(), p.declaredNumber(),
                p.declaredDate(), p.declaredYear(), p.emitterNif(), p.customerNif(), p.customerName(),
                p.totalAmount(), p.ocrConfidence(), p.declarationSigned(), p.declarationText(), pdf));
        return Map.of("ok", true, "id", id);
    }

    @GetMapping
    public List<MigrationBaselineService.BaselineRow> list() {
        return service.list();
    }

    /** MIG-3 — PDF de prueba de una baseline. */
    @GetMapping(value = "/{id}/evidence", produces = org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
    public org.springframework.http.ResponseEntity<byte[]> evidence(
            @org.springframework.web.bind.annotation.PathVariable("id") String id) {
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(service.evidence(id));
    }
}
