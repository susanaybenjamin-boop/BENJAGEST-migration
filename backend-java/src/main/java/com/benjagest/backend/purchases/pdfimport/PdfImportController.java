package com.benjagest.backend.purchases.pdfimport;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Endpoint de extraccion de campos desde un PDF de factura recibida
 * (C3). Devuelve los campos detectados sin persistir nada — el
 * frontend muestra al usuario un formulario rellenable con los
 * valores extraidos, que el usuario corrige y guarda en el modulo
 * de compras (cuando ese exista).
 *
 * Hoy NO persiste en `purchase_invoices` porque ese modulo no esta
 * implementado todavia. Cuando se cierre el slice de compras, esta
 * misma extraccion sera el primer paso del flujo "subir PDF" del
 * modulo.
 */
@RestController
@RequestMapping("/api/purchases/pdf-import")
@RequiresModule("purchases")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class PdfImportController {

    private final PdfTextExtractor textExtractor;
    private final InvoiceFieldsExtractor fieldsExtractor;
    private final SupplierTemplateService templateService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final com.benjagest.backend.tenant.TenantContext tenantContext;

    public PdfImportController(PdfTextExtractor textExtractor,
                                InvoiceFieldsExtractor fieldsExtractor,
                                SupplierTemplateService templateService,
                                org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                                com.benjagest.backend.tenant.TenantContext tenantContext) {
        this.textExtractor = textExtractor;
        this.fieldsExtractor = fieldsExtractor;
        this.templateService = templateService;
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    /**
     * PDF-EXTRACT-1 — identidad (NIF + razón social) de la empresa activa,
     * para que el extractor no la confunda con el emisor de la factura.
     */
    private InvoiceFieldsExtractor.OwnParty ownParty() {
        try {
            var rows = jdbcTemplate.queryForList("""
                    SELECT tax_identifier, legal_name FROM companies WHERE id = ?
                    """, tenantContext.getCurrentCompanyId());
            if (!rows.isEmpty()) {
                return new InvoiceFieldsExtractor.OwnParty(
                        (String) rows.get(0).get("tax_identifier"),
                        (String) rows.get(0).get("legal_name"));
            }
        } catch (Exception ignored) {
            // sin identidad propia el extractor funciona igual (sin exclusión)
        }
        return null;
    }

    /**
     * Acepta un PDF multipart, extrae texto + campos, devuelve resultado
     * para previsualizacion.
     *
     * Limites: tamano maximo del PDF lo controla
     * spring.servlet.multipart.max-file-size en application.properties
     * (default 1MB, se puede subir a 10MB si hay PDFs grandes).
     */
    @PostMapping
    public List<InvoiceFieldsExtractor.ExtractionResult> extract(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archivo PDF requerido");
        }
        if (!"application/pdf".equalsIgnoreCase(file.getContentType())
                && (file.getOriginalFilename() == null
                        || !file.getOriginalFilename().toLowerCase().endsWith(".pdf"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El archivo debe ser un PDF");
        }
        try {
            byte[] bytes = file.getBytes();
            // v2: extraemos preservando layout (X/Y por span). Las
            // facturas son tablas — sin posiciones se mezclan etiqueta
            // y valor de columnas distintas.
            LayoutDocument layout = textExtractor.extractLayout(bytes);
            // PDF-EXTRACT-1: extractLayout ya cae a OCR con escaneados y con
            // text-layer entrelazado. Si aun así no hay líneas, no hay nada
            // que leer (imagen ilegible o OCR no disponible).
            if (layout.pages().isEmpty()
                    || layout.allLines().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No se pudo extraer texto del PDF (imagen ilegible u OCR no disponible).");
            }
            // Multi-factura: Amazon puede empaquetar varias facturas en
            // un único PDF (cada una con su "Página 1 de N"). El
            // extractor devuelve una lista; la UI procesa una por una.
            var rawResults = fieldsExtractor.extractAll(layout, bytes, ownParty());
            List<InvoiceFieldsExtractor.ExtractionResult> out = new ArrayList<>();
            for (var r : rawResults) {
                var template = templateService.findByNif(r.emitterNif());
                out.add(templateService.apply(r, template));
            }
            return out;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No se pudo leer el PDF: " + e.getMessage());
        }
    }
}
