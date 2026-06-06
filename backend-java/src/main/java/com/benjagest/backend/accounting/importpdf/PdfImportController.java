package com.benjagest.backend.accounting.importpdf;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoints para la multi-importación de PDFs:
 *
 * <ul>
 *   <li>{@code POST /api/accounting/import-pdf/sales} → crea un asiento
 *       DRAFT directo a partir de los datos extraídos + el PDF.</li>
 *   <li>{@code GET /api/accounting/journal-entries/{id}/source-pdf} →
 *       descarga el PDF asociado a un asiento (para el visor en la
 *       pestaña "Por validar").</li>
 * </ul>
 *
 * <p>El PDF llega en base64 dentro del JSON. Para 1-3 páginas es ~200-500 KB
 * codificado, perfectamente manejable. Si en el futuro hacemos batches
 * enormes pasamos a multipart, pero hoy el formato es uniforme con el
 * resto de la API.
 */
@RestController
@RequestMapping("/api/accounting")
@RequiresModule("accounting")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class PdfImportController {

    private final SalesPdfImportService salesImport;
    private final ImportedPdfStorageService storage;
    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public PdfImportController(SalesPdfImportService salesImport,
                                 ImportedPdfStorageService storage,
                                 JdbcTemplate jdbcTemplate,
                                 TenantContext tenantContext) {
        this.salesImport = salesImport;
        this.storage = storage;
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public record SalesImportPayload(
            String customerNif,
            String customerName,
            LocalDate invoiceDate,
            BigDecimal baseAmount,
            BigDecimal vatPercent,
            BigDecimal vatAmount,
            BigDecimal retentionAmount,
            BigDecimal totalAmount,
            String invoiceNumber,
            String concept,
            /** PDF original codificado en base64. Opcional pero recomendado. */
            String pdfBase64
    ) {}

    @PostMapping("/import-pdf/sales")
    public SalesPdfImportService.Result importSales(
            @RequestBody SalesImportPayload p) throws IOException {
        byte[] pdfBytes = p.pdfBase64() == null || p.pdfBase64().isBlank()
                ? null
                : Base64.getDecoder().decode(p.pdfBase64());
        return salesImport.importAsJournalEntry(new SalesPdfImportService.Request(
                p.customerNif(), p.customerName(),
                p.invoiceDate(),
                p.baseAmount(), p.vatPercent(), p.vatAmount(),
                p.retentionAmount(), p.totalAmount(),
                p.invoiceNumber(), p.concept(),
                pdfBytes));
    }

    /**
     * Devuelve el PDF binario asociado a un asiento (Content-Type
     * application/pdf). Usado por la UI para mostrar el visor.
     */
    @GetMapping("/journal-entries/{id}/source-pdf")
    public ResponseEntity<byte[]> downloadSourcePdf(@PathVariable String id)
            throws IOException {
        String companyId = tenantContext.getCurrentCompanyId();
        List<String> paths = jdbcTemplate.query("""
                SELECT source_pdf_path FROM journal_entries
                 WHERE id = ? AND company_id = ?
                 LIMIT 1
                """, (rs, n) -> rs.getString("source_pdf_path"), id, companyId);
        if (paths.isEmpty() || paths.get(0) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "El asiento no tiene PDF asociado.");
        }
        byte[] data = storage.read(paths.get(0));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .body(data);
    }
}
