package com.benjagest.backend.billing.email;

import com.benjagest.backend.billing.invoices.SalesInvoice;
import com.benjagest.backend.billing.invoices.SalesInvoiceService;
import com.benjagest.backend.billing.pdf.InvoicePdfGenerator;
import com.benjagest.backend.billing.pdf.InvoiceQrService;
import com.benjagest.backend.billing.pdf.InvoiceStorageService;
import com.benjagest.backend.billing.texts.InvoiceTextsController.InvoiceTextsService;
import com.benjagest.backend.billing.verifactu.VerifactuConfig;
import com.benjagest.backend.billing.verifactu.VerifactuConfigRepository;
import com.benjagest.backend.billing.verifactu.VerifactuRegistryService;
import com.benjagest.backend.settings.CompanyDataResponse;
import com.benjagest.backend.settings.CompanyDataService;
import com.benjagest.backend.settings.EmailSenderService;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Envia el PDF de una factura validada al email del cliente. Slice
 * F-EMAIL.
 *
 * Reglas:
 *   - Solo VALIDATED (DRAFT no tiene sentido enviar; CANCELLED/VOIDED
 *     se pueden enviar para que el cliente sepa que se ha anulado, pero
 *     por defecto solo VALIDATED — si llega caso de uso de "enviar
 *     anulacion" se abre como slice).
 *   - El recipient se resuelve por orden: argumento explicito (override
 *     del usuario en la UI) -> primary_contact.email del cliente. Si
 *     no hay ninguno -> 400.
 *   - El PDF lee de disco (F-STORAGE) si existe; si no, regenera
 *     on-the-fly (compat con facturas legacy o casos de PDF perdido).
 *   - El asunto y el cuerpo se generan con plantilla por defecto si no
 *     vienen como argumento — para que el usuario pueda mandar de un
 *     click sin pensar.
 */
@Service
public class InvoiceEmailService {

    private final SalesInvoiceService invoiceService;
    private final InvoicePdfGenerator pdfGenerator;
    private final InvoiceQrService qrService;
    private final InvoiceStorageService storageService;
    private final CompanyDataService companyDataService;
    private final InvoiceTextsService invoiceTextsService;
    private final VerifactuRegistryService verifactuRegistryService;
    private final VerifactuConfigRepository verifactuConfigRepository;
    private final EmailSenderService emailSenderService;
    private final JdbcTemplate jdbcTemplate;

    public InvoiceEmailService(SalesInvoiceService invoiceService,
                               InvoicePdfGenerator pdfGenerator,
                               InvoiceQrService qrService,
                               InvoiceStorageService storageService,
                               CompanyDataService companyDataService,
                               InvoiceTextsService invoiceTextsService,
                               VerifactuRegistryService verifactuRegistryService,
                               VerifactuConfigRepository verifactuConfigRepository,
                               EmailSenderService emailSenderService,
                               JdbcTemplate jdbcTemplate) {
        this.invoiceService = invoiceService;
        this.pdfGenerator = pdfGenerator;
        this.qrService = qrService;
        this.storageService = storageService;
        this.companyDataService = companyDataService;
        this.invoiceTextsService = invoiceTextsService;
        this.verifactuRegistryService = verifactuRegistryService;
        this.verifactuConfigRepository = verifactuConfigRepository;
        this.emailSenderService = emailSenderService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public SentReceipt send(String invoiceId, String recipientOverride, String customSubject, String customBody) {
        SalesInvoice invoice = invoiceService.get(invoiceId);
        if (!"VALIDATED".equals(invoice.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Solo se envian facturas VALIDATED por email. Estado actual: " + invoice.status());
        }

        String to = StringUtils.hasText(recipientOverride)
                ? recipientOverride.trim()
                : lookupCustomerEmail(invoice.customerId());
        if (!StringUtils.hasText(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El cliente no tiene email registrado. Indica una direccion manualmente.");
        }

        byte[] pdfBytes = resolvePdf(invoice);
        String filename = sanitizeFilename(invoice.invoiceNumber()) + ".pdf";

        CompanyDataResponse company = companyDataService.getCurrent();
        String subject = StringUtils.hasText(customSubject)
                ? customSubject
                : defaultSubject(invoice, company);
        String body = StringUtils.hasText(customBody)
                ? customBody
                : defaultBody(invoice, company);

        emailSenderService.send(to, subject, body, pdfBytes, filename);
        return new SentReceipt(to, subject, filename);
    }

    private String lookupCustomerEmail(String customerId) {
        if (!StringUtils.hasText(customerId)) return null;
        return jdbcTemplate.query("""
                SELECT email
                  FROM customer_contacts
                 WHERE customer_id = ?
                   AND primary_contact = TRUE
                   AND active = TRUE
                 LIMIT 1
                """,
                rs -> rs.next() ? rs.getString("email") : null,
                customerId);
    }

    private byte[] resolvePdf(SalesInvoice invoice) {
        if (storageService.exists(invoice.pdfPath())) {
            try {
                return storageService.read(invoice.pdfPath());
            } catch (IOException ignored) {
                // cae al regenerate
            }
        }
        return regenerate(invoice);
    }

    private byte[] regenerate(SalesInvoice invoice) {
        CompanyDataResponse company = companyDataService.getCurrent();
        String hash = verifactuRegistryService.findCurrentHashForPdf(invoice.id()).orElse(null);
        VerifactuConfig vfConfig = verifactuConfigRepository.findCurrent().orElse(null);
        byte[] qrPng = qrService.generatePng(invoice, company, vfConfig);
        String label = qrService.complianceLabel(vfConfig);
        return pdfGenerator.generate(invoice, company, invoiceTextsService.get(),
                hash, qrPng, label);
    }

    private String defaultSubject(SalesInvoice invoice, CompanyDataResponse company) {
        return "Factura " + invoice.invoiceNumber()
                + " - " + (company.legalName() == null ? "" : company.legalName());
    }

    private String defaultBody(SalesInvoice invoice, CompanyDataResponse company) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hola,\n\n");
        sb.append("Adjunto te enviamos la factura ").append(invoice.invoiceNumber());
        if (invoice.invoiceDate() != null) {
            sb.append(" con fecha ").append(invoice.invoiceDate().toString());
        }
        sb.append(".\n\n");
        sb.append("Si tienes cualquier duda, responde a este correo y te atenderemos.\n\n");
        sb.append("Saludos,\n");
        sb.append(company.legalName() == null ? "" : company.legalName()).append("\n");
        return sb.toString();
    }

    private String sanitizeFilename(String number) {
        if (!StringUtils.hasText(number)) return "factura";
        return number.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public record SentReceipt(String recipient, String subject, String filename) {}
}
