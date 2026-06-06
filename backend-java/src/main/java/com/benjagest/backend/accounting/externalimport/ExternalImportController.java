package com.benjagest.backend.accounting.externalimport;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API REST de importación desde sistemas externos.
 *
 * <ul>
 *   <li>{@code POST /api/accounting/external-imports} — sube contenido y
 *       lo procesa. Devuelve resumen total/imported/skipped/errors.</li>
 *   <li>{@code GET  /api/accounting/external-imports} — histórico de
 *       batches con resultados.</li>
 * </ul>
 *
 * <p>El body de POST es un JSON pequeño: {@code format}, {@code targetKind},
 * {@code fileName} (opcional), {@code content} (string con el archivo, codificado).
 */
@RestController
@RequestMapping("/api/accounting/external-imports")
@RequiresModule("accounting")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class ExternalImportController {

    private final ExternalImportService service;

    public ExternalImportController(ExternalImportService service) {
        this.service = service;
    }

    @PostMapping
    public ExternalImportService.BatchResult upload(
            @RequestBody ExternalImportService.ImportRequest req) {
        return service.importData(req);
    }

    @GetMapping
    public List<ExternalImportService.BatchView> list(
            @RequestParam(value = "targetKind", required = false) String targetKind,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return service.listBatches(targetKind, limit);
    }
}
