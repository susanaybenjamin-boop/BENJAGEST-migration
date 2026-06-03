package com.benjagest.backend.billing.invoices;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.billing.pdf.InvoicePdfGenerator;
import com.benjagest.backend.billing.pdf.InvoiceQrService;
import com.benjagest.backend.billing.pdf.InvoiceStorageService;
import com.benjagest.backend.billing.texts.InvoiceTextsController.InvoiceTextsService;
import com.benjagest.backend.billing.verifactu.VerifactuConfig;
import com.benjagest.backend.billing.verifactu.VerifactuConfigRepository;
import com.benjagest.backend.billing.verifactu.VerifactuRegistryService;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.settings.CompanyDataService;
import java.io.IOException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints REST de facturas emitidas.
 *
 *   GET    /api/billing/invoices                lista con filtros opcionales
 *   GET    /api/billing/invoices/{id}           una con lineas
 *   POST   /api/billing/invoices                crear (status DRAFT)
 *   PUT    /api/billing/invoices/{id}           editar DRAFT
 *   POST   /api/billing/invoices/{id}/validate  pasa de DRAFT a VALIDATED
 *                                               (emite numero de serie)
 *   POST   /api/billing/invoices/{id}/void      crea borrador RECTIFYING
 *                                               enlazado (anulacion con
 *                                               vinculo). La original NO
 *                                               cambia de estado hasta que
 *                                               el borrador rect se valide.
 *   DELETE /api/billing/invoices/{id}           soft cancel DRAFT
 *
 * Filtros del listado (query params):
 *   status        DRAFT | VALIDATED | CANCELLED | VOIDED
 *   paymentStatus PENDING | PARTIAL | PAID | OVERDUE
 *   customerId    UUID del cliente
 *   limit         1..500 (default 100)
 */
@RestController
@RequestMapping("/api/billing/invoices")
@RequiresModule("billing")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class SalesInvoiceController {

    private final SalesInvoiceService service;
    private final InvoicePdfGenerator pdfGenerator;
    private final InvoiceQrService qrService;
    private final CompanyDataService companyDataService;
    private final InvoiceTextsService invoiceTextsService;
    private final VerifactuRegistryService verifactuRegistryService;
    private final VerifactuConfigRepository verifactuConfigRepository;
    private final InvoiceStorageService storageService;

    public SalesInvoiceController(SalesInvoiceService service,
                                  InvoicePdfGenerator pdfGenerator,
                                  InvoiceQrService qrService,
                                  CompanyDataService companyDataService,
                                  InvoiceTextsService invoiceTextsService,
                                  VerifactuRegistryService verifactuRegistryService,
                                  VerifactuConfigRepository verifactuConfigRepository,
                                  InvoiceStorageService storageService) {
        this.service = service;
        this.pdfGenerator = pdfGenerator;
        this.qrService = qrService;
        this.companyDataService = companyDataService;
        this.invoiceTextsService = invoiceTextsService;
        this.verifactuRegistryService = verifactuRegistryService;
        this.verifactuConfigRepository = verifactuConfigRepository;
        this.storageService = storageService;
    }

    @GetMapping
    public List<SalesInvoice> list(@RequestParam(value = "status", required = false) String status,
                                   @RequestParam(value = "paymentStatus", required = false) String paymentStatus,
                                   @RequestParam(value = "customerId", required = false) String customerId,
                                   @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return service.list(status, paymentStatus, customerId, limit);
    }

    @GetMapping("/{id}")
    public SalesInvoice get(@PathVariable("id") String id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SalesInvoice create(@Valid @RequestBody InvoiceUpsertRequest request) {
        return service.createDraft(request);
    }

    @PutMapping("/{id}")
    public SalesInvoice update(@PathVariable("id") String id,
                               @Valid @RequestBody InvoiceUpsertRequest request) {
        return service.updateDraft(id, request);
    }

    @PostMapping("/{id}/validate")
    public SalesInvoice validate(@PathVariable("id") String id) {
        return service.validate(id);
    }

    @PostMapping("/{id}/void")
    public SalesInvoice voidValidated(@PathVariable("id") String id) {
        return service.voidValidated(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        service.deleteDraft(id);
    }

    /**
     * Genera y devuelve el PDF de la factura. F4b.
     *
     *   - application/pdf inline (los navegadores lo abren incrustado).
     *   - Filename = invoiceNumber.pdf, o draft-<shortId>.pdf si todavia
     *     no está validada.
     *   - Solo facturas accesibles por el TenantContext actual: la
     *     defensa es la misma de get(id) (filtra por company_id), asi
     *     que un OWNER no puede sacar PDFs de otras empresas aunque
     *     sepa el id.
     */
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable("id") String id) {
        SalesInvoice invoice = service.get(id);

        // Slice F-STORAGE: si la factura tiene PDF guardado en disco
        // (todas las VALIDATED desde este slice), leemos esa copia. Es
        // la legalmente vinculante — no la regeneramos por si cambio
        // el InvoicePdfGenerator entre la validacion y ahora.
        //
        // Si no existe pdf_path (factura legacy validada antes de
        // F-STORAGE, o borrador descargado) generamos on-the-fly como
        // antes. Asi mantenemos compat hacia atras sin migracion de
        // datos masiva.
        byte[] bytes;
        if (storageService.exists(invoice.pdfPath())) {
            try {
                bytes = storageService.read(invoice.pdfPath());
            } catch (IOException ioe) {
                // El archivo existe pero no se puede leer (permisos,
                // disco corrupto). Caemos a regeneracion en vez de 500.
                bytes = regenerate(invoice, id);
            }
        } else {
            bytes = regenerate(invoice, id);
        }

        String filename = (invoice.invoiceNumber() == null || invoice.invoiceNumber().isBlank())
                ? "borrador-" + invoice.id().substring(0, 8) + ".pdf"
                : invoice.invoiceNumber().replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf";

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + filename + "\"")
                .body(bytes);
    }

    private byte[] regenerate(SalesInvoice invoice, String id) {
        String verifactuHash = verifactuRegistryService
                .findCurrentHashForPdf(id)
                .orElse(null);
        var company = companyDataService.getCurrent();
        VerifactuConfig vfConfig = verifactuConfigRepository.findCurrent().orElse(null);
        byte[] qrPng = qrService.generatePng(invoice, company, vfConfig);
        String complianceLabel = qrService.complianceLabel(vfConfig);
        return pdfGenerator.generate(invoice,
                company,
                invoiceTextsService.get(),
                verifactuHash,
                qrPng,
                complianceLabel);
    }
}
