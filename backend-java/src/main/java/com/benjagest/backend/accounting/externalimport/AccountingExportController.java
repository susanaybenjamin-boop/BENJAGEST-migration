package com.benjagest.backend.accounting.externalimport;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Descarga de archivos de exportación contable.
 *
 * <p>Endpoint principal: {@code GET /api/accounting/exports?format=CSV&targetKind=JOURNAL_ENTRIES}.
 * Devuelve el archivo como adjunto descargable; el navegador o la UI
 * lo guarda directamente.
 */
@RestController
@RequestMapping("/api/accounting/exports")
@RequiresModule("accounting")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class AccountingExportController {

    private final AccountingExportService service;

    public AccountingExportController(AccountingExportService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<byte[]> download(
            @RequestParam("format") String format,
            @RequestParam("targetKind") String targetKind,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "includeDrafts", required = false) Boolean includeDrafts) {
        AccountingExportService.ExportPayload payload = service.export(
                new AccountingExportService.ExportRequest(
                        format, targetKind, from, to, includeDrafts));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(payload.mimeType()));
        headers.setContentDispositionFormData("attachment", payload.filename());
        return new ResponseEntity<>(payload.content(), headers, 200);
    }
}
