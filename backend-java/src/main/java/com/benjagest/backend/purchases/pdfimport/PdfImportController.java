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
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class PdfImportController {

    private final PdfTextExtractor textExtractor;
    private final InvoiceFieldsExtractor fieldsExtractor;
    private final SupplierTemplateService templateService;

    public PdfImportController(PdfTextExtractor textExtractor,
                                InvoiceFieldsExtractor fieldsExtractor,
                                SupplierTemplateService templateService) {
        this.textExtractor = textExtractor;
        this.fieldsExtractor = fieldsExtractor;
        this.templateService = templateService;
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
            if (layout.pages().isEmpty()
                    || layout.allLines().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "El PDF no contiene texto extraible (puede ser una imagen escaneada). "
                                + "El soporte OCR para PDFs escaneados se anyadira en un slice futuro.");
            }
            // Multi-factura: Amazon puede empaquetar varias facturas en
            // un único PDF (cada una con su "Página 1 de N"). El
            // extractor devuelve una lista; la UI procesa una por una.
            var rawResults = fieldsExtractor.extractAll(layout, bytes);
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
