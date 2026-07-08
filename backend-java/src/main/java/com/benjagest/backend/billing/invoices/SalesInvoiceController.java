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
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class SalesInvoiceController {

    private final SalesInvoiceService service;
    private final InvoicePdfGenerator pdfGenerator;
    private final InvoiceQrService qrService;
    private final CompanyDataService companyDataService;
    private final InvoiceTextsService invoiceTextsService;
    private final VerifactuRegistryService verifactuRegistryService;
    private final VerifactuConfigRepository verifactuConfigRepository;
    private final InvoiceStorageService storageService;
    private final com.benjagest.backend.billing.email.InvoiceEmailService emailService;

    public SalesInvoiceController(SalesInvoiceService service,
                                  InvoicePdfGenerator pdfGenerator,
                                  InvoiceQrService qrService,
                                  CompanyDataService companyDataService,
                                  InvoiceTextsService invoiceTextsService,
                                  VerifactuRegistryService verifactuRegistryService,
                                  VerifactuConfigRepository verifactuConfigRepository,
                                  InvoiceStorageService storageService,
                                  com.benjagest.backend.billing.email.InvoiceEmailService emailService) {
        this.service = service;
        this.pdfGenerator = pdfGenerator;
        this.qrService = qrService;
        this.companyDataService = companyDataService;
        this.invoiceTextsService = invoiceTextsService;
        this.verifactuRegistryService = verifactuRegistryService;
        this.verifactuConfigRepository = verifactuConfigRepository;
        this.storageService = storageService;
        this.emailService = emailService;
    }

    @GetMapping
    public List<SalesInvoice> list(@RequestParam(value = "status", required = false) String status,
                                   @RequestParam(value = "paymentStatus", required = false) String paymentStatus,
                                   @RequestParam(value = "customerId", required = false) String customerId,
                                   @RequestParam(value = "invoiceType", required = false) String invoiceType,
                                   @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return service.list(status, paymentStatus, customerId, invoiceType, limit);
    }

    @GetMapping("/{id}")
    public SalesInvoice get(@PathVariable("id") String id) {
        return service.get(id);
    }

    /** REFLEJO — facturas emitidas que se reflejaron como gasto, con el cliente destino. */
    @GetMapping("/reflections")
    public List<java.util.Map<String, String>> reflections() {
        return service.reflectionsForIssuer();
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

    /**
     * Regenera el asiento contable de una factura ya validada para aplicar el
     * desglose de IVA por tipo, sin cambiar el estado ni el número del asiento.
     */
    @PostMapping("/{id}/regenerate-journal")
    public java.util.Map<String, Object> regenerateJournal(@PathVariable("id") String id) {
        service.regenerateJournal(id);
        return java.util.Map.of("ok", true);
    }

    /**
     * TPB-3 — El cliente aprueba una factura emitida por su asesoría
     * por tercero. Solo accesible desde el tenant del cliente.
     */
    @PostMapping("/{id}/client-approve")
    public SalesInvoice clientApprove(@PathVariable("id") String id) {
        return service.approveByClient(id);
    }

    /** TPB-3 — El cliente rechaza con motivo. Vuelve a DRAFT. */
    @PostMapping("/{id}/client-reject")
    public SalesInvoice clientReject(@PathVariable("id") String id,
                                       @RequestBody(required = false)
                                       java.util.Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return service.rejectByClient(id, reason);
    }

    /** TPB-3 — Listado de facturas pendientes de aprobación del cliente. */
    @org.springframework.web.bind.annotation.GetMapping("/pending-client-approval")
    public java.util.List<SalesInvoice> pendingClientApproval() {
        return service.listPendingClientApproval();
    }

    /**
     * Valida un lote de facturas DRAFT (multiselección). Recorre cada id
     * y dispara {@link SalesInvoiceService#validate}: PDF, hash chain,
     * SIF event y asiento contable. Devuelve resumen por id.
     */
    @PostMapping("/validate-batch")
    public java.util.Map<String, Object> validateBatch(
            @RequestBody java.util.Map<String, java.util.List<String>> body) {
        java.util.List<String> ids = body == null ? java.util.List.of() : body.get("ids");
        if (ids == null) ids = java.util.List.of();
        int validated = 0, skipped = 0, errors = 0;
        java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
        for (String id : ids) {
            try {
                SalesInvoice cur = service.get(id);
                if (!"DRAFT".equals(cur.status())) {
                    skipped++;
                    items.add(java.util.Map.of("id", id, "status", "SKIPPED",
                            "reason", "status=" + cur.status()));
                    continue;
                }
                SalesInvoice v = service.validate(id);
                validated++;
                items.add(java.util.Map.of("id", id, "status", v.status(),
                        "number", v.invoiceNumber() == null ? "" : v.invoiceNumber()));
            } catch (Exception ex) {
                errors++;
                items.add(java.util.Map.of("id", id, "status", "ERROR",
                        "reason", ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            }
        }
        return java.util.Map.of("total", ids.size(),
                "validated", validated, "skipped", skipped, "errors", errors,
                "items", items);
    }

    /**
     * RECT: la anulación lleva causa legal (R1-R4; R5 automática si la
     * original es simplificada). Body opcional {"rectificationCode":"R3"}
     * — sin body sale R4 (resto de causas), compatible con clientes
     * antiguos que llamaban sin cuerpo.
     */
    @PostMapping("/{id}/void")
    public SalesInvoice voidValidated(@PathVariable("id") String id,
                                      @RequestBody(required = false) RectifyRequest body) {
        return service.voidValidated(id, body == null ? null : body.rectificationCode());
    }

    /**
     * RECT — rectificativa PARCIAL: crea un borrador RECTIFYING (scope
     * PARTIAL) con las líneas de la original en negativo para que el
     * usuario las ajuste en el editor y valide. La original NO se anula.
     */
    @PostMapping("/{id}/rectify")
    public SalesInvoice rectifyPartial(@PathVariable("id") String id,
                                       @RequestBody(required = false) RectifyRequest body) {
        return service.rectifyPartial(id, body == null ? null : body.rectificationCode());
    }

    public record RectifyRequest(String rectificationCode) {}

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        service.deleteDraft(id);
    }

    /**
     * Convierte una proforma DRAFT en factura NORMAL DRAFT (o validada
     * si {@code validate=true}). El invoice_type cambia a NORMAL, se
     * resuelve la serie STANDARD y, si validate=true, se valida en la
     * misma transacción.
     *
     * Reglas:
     *   - Solo aplica a PROFORMA en DRAFT (400 en otro caso).
     *   - Una proforma sin líneas no se puede convertir + validar
     *     (defensa común con validate normal).
     */
    @PostMapping("/{id}/convert-to-standard")
    public SalesInvoice convertProformaToStandard(@PathVariable("id") String id,
                                                  @RequestParam(value = "validate", defaultValue = "false") boolean validate) {
        return service.convertProformaToStandard(id, validate);
    }

    /**
     * Genera el PDF y lo guarda en la ruta configurada por la empresa
     * (slice F-STORAGE). Usado por el botón "Guardar PDF" del listado
     * para facturas que aún no tienen archivo en disco (legacy,
     * borrado manual, fallo de escritura en validate).
     *
     * Crea las subcarpetas {YYYY}/T{q}/ si no existen
     * (Files.createDirectories del InvoiceStorageService).
     *
     * Devuelve {@code {"pdfPath": "..."} } con la ruta absoluta para
     * que la UI lo muestre en el alert de éxito y refresque el
     * listado (al volver, pdfStored=true y el botón se renombra).
     */
    @PostMapping(value = "/{id}/store-pdf", produces = MediaType.APPLICATION_JSON_VALUE)
    public java.util.Map<String, String> storePdf(@PathVariable("id") String id) {
        String absPath = service.storePdfNow(id);
        return java.util.Map.of("pdfPath", absPath);
    }

    /**
     * Envia la factura por email al cliente — slice F-EMAIL.
     *
     * Body:
     *   { "recipient": "opcional@override", "subject": "opcional",
     *     "body": "opcional" }
     *
     * Si no se manda recipient, se busca el email del primary_contact
     * del cliente. Si no hay y no se manda override, 400.
     *
     * Devuelve un acuse mini: a quien se envio, asunto, nombre del
     * adjunto. Util para la UI mostrar "Factura enviada a X" sin
     * tener que volver a consultar.
     */
    @PostMapping("/{id}/email")
    public com.benjagest.backend.billing.email.InvoiceEmailService.SentReceipt sendByEmail(
            @PathVariable("id") String id,
            @RequestBody(required = false) SendInvoiceEmailRequest request) {
        String recipient = request == null ? null : request.recipient();
        String subject = request == null ? null : request.subject();
        String body = request == null ? null : request.body();
        return emailService.send(id, recipient, subject, body);
    }

    public record SendInvoiceEmailRequest(String recipient, String subject, String body) {
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
