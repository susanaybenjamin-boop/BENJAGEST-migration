package com.benjagest.backend.billing.migration;

import com.benjagest.backend.auth.RequiresRole;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
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

    /** Sube el PDF de la última factura y devuelve los campos autorellenados (OCR). */
    @PostMapping("/extract")
    public MigrationBaselineService.Extracted extract(@RequestParam("file") MultipartFile file) {
        try {
            return service.extract(file.getBytes());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo leer el archivo.");
        }
    }

    public record ConfirmPayload(String seriesId, String declaredSeriesCode, String declaredFullNumber,
                                 Integer declaredNumber, String declaredDate, String emitterNif,
                                 String customerNif, String customerName, BigDecimal totalAmount,
                                 String ocrConfidence, boolean declarationSigned, String declarationText,
                                 String pdfBase64) {}

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody ConfirmPayload p) {
        byte[] pdf = p.pdfBase64() == null || p.pdfBase64().isBlank()
                ? null : Base64.getDecoder().decode(p.pdfBase64());
        String id = service.confirm(new MigrationBaselineService.ConfirmRequest(
                p.seriesId(), p.declaredSeriesCode(), p.declaredFullNumber(), p.declaredNumber(),
                p.declaredDate(), p.emitterNif(), p.customerNif(), p.customerName(), p.totalAmount(),
                p.ocrConfidence(), p.declarationSigned(), p.declarationText(), pdf));
        return Map.of("ok", true, "id", id);
    }

    @GetMapping
    public List<MigrationBaselineService.BaselineRow> list() {
        return service.list();
    }
}
