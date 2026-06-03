package com.benjagest.backend.billing.sign;

import com.benjagest.backend.billing.sif.SifEventRepository;
import com.benjagest.backend.billing.verifactu.AeatVerifactuClient;
import com.benjagest.backend.billing.verifactu.VerifactuRegistryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Slice VF4 — reintento periodico de firma para registros que se
 * quedaron en status PENDING.
 *
 * Casos tipicos en los que un registro queda PENDING al validar:
 *   - La empresa todavia no tenia certificado .p12 configurado.
 *   - El certificado existia pero el .p12 era ilegible / contrasena
 *     incorrecta (luego se corrigio en Configuracion -> Certificado).
 *   - Excepcion transitoria al firmar (recurso bloqueado, OOM en pico).
 *
 * Cuando el operador termina de configurar el certificado, este job
 * encuentra los PENDING y los firma sin necesidad de regenerar nada.
 * La cadena hash NO se altera — solo se anyade la firma encima.
 *
 * Frecuencia: cada 10 minutos. Lote pequenyo (max 100 por ciclo) para
 * no monopolizar la BD ni el thread de scheduling.
 *
 * Cuando llegue VF3-SOAP, esta misma logica se ampliara con un segundo
 * @Scheduled que recorra registros SIGNED y los envie a AEAT,
 * pasandolos a SENT y luego ACKNOWLEDGED segun respuesta.
 */
@Component
public class SignatureRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SignatureRetryScheduler.class);
    private static final long TEN_MIN_MS = 10L * 60L * 1000L;
    private static final long INITIAL_DELAY_MS = 5L * 60L * 1000L;
    private static final int BATCH_SIZE = 100;

    private final VerifactuRegistryRepository registryRepository;
    private final SifEventRepository eventRepository;
    private final XmlSignerService signerService;
    private final AeatVerifactuClient aeatClient;

    public SignatureRetryScheduler(VerifactuRegistryRepository registryRepository,
                                    SifEventRepository eventRepository,
                                    XmlSignerService signerService,
                                    AeatVerifactuClient aeatClient) {
        this.registryRepository = registryRepository;
        this.eventRepository = eventRepository;
        this.signerService = signerService;
        this.aeatClient = aeatClient;
    }

    @Scheduled(fixedDelay = TEN_MIN_MS, initialDelay = INITIAL_DELAY_MS)
    public void retry() {
        int signedRegistry = retryInvoices();
        int signedEvents = retryEvents();
        int sentRegistry = sendSignedInvoicesToAeat();
        if (signedRegistry > 0 || signedEvents > 0 || sentRegistry > 0) {
            log.info("VF4: ciclo terminado — facturas firmadas={}, eventos firmados={}, facturas enviadas AEAT={}",
                    signedRegistry, signedEvents, sentRegistry);
        }
    }

    /**
     * VF3-SOAP: recorre los registros SIGNED y los envia a AEAT
     * (modalidad VERIFACTU solo). Actualiza status SENT al recibir 2xx,
     * ACKNOWLEDGED al detectar "Aceptado" en respuesta, ERROR si falla.
     * El cliente AEAT esta sin probar contra real (ver AeatVerifactuClient),
     * asi que ahora todos los intentos terminan en ERROR hasta que se
     * tenga FNMT + ajuste XSD.
     */
    private int sendSignedInvoicesToAeat() {
        List<VerifactuRegistryRepository.PendingForSending> pending;
        try {
            pending = registryRepository.findPendingForSending(BATCH_SIZE);
        } catch (RuntimeException ex) {
            log.warn("VF3-SOAP: no se pudo listar pending de envio", ex);
            return 0;
        }
        int count = 0;
        for (var row : pending) {
            try {
                AeatVerifactuClient.SendResult result = aeatClient.sendInvoice(
                        row.companyId(), row.mode(),
                        row.taxIdentifier(), row.companyLegalName(),
                        row.signatureData());
                if (result.ok()) {
                    registryRepository.markSent(row.invoiceId(), row.mode(), row.companyId());
                    if ("Aceptado".equals(result.message())
                            || "AceptadoConErrores".equals(result.message())) {
                        registryRepository.markAcknowledged(
                                row.invoiceId(), row.mode(), row.companyId());
                    }
                    count++;
                } else {
                    registryRepository.markError(row.invoiceId(), row.mode(),
                            row.companyId(), result.message());
                }
            } catch (RuntimeException ex) {
                log.warn("VF3-SOAP: error enviando factura {} (mode={})",
                        row.invoiceId(), row.mode(), ex);
            }
        }
        return count;
    }

    private int retryInvoices() {
        List<VerifactuRegistryRepository.PendingForSigning> pending;
        try {
            pending = registryRepository.findPendingForSigning(BATCH_SIZE);
        } catch (RuntimeException ex) {
            log.warn("VF4: no se pudo listar pending facturas", ex);
            return 0;
        }
        int count = 0;
        for (var row : pending) {
            try {
                String xml = buildRegistryXml(row);
                var signed = signerService.signForCompany(xml, row.companyId());
                if (signed.isPresent()) {
                    registryRepository.setSignatureForCompany(
                            row.invoiceId(), row.mode(), row.companyId(), signed.get());
                    count++;
                }
            } catch (RuntimeException ex) {
                log.warn("VF4: error firmando factura {} (mode={})",
                        row.invoiceId(), row.mode(), ex);
            }
        }
        return count;
    }

    private int retryEvents() {
        List<SifEventRepository.PendingEvent> pending;
        try {
            pending = eventRepository.findPendingForSigning(BATCH_SIZE);
        } catch (RuntimeException ex) {
            log.warn("VF4: no se pudo listar pending eventos", ex);
            return 0;
        }
        int count = 0;
        for (var row : pending) {
            try {
                String xml = buildEventXml(row);
                var signed = signerService.signForCompany(xml, row.companyId());
                if (signed.isPresent()) {
                    eventRepository.setSignatureForCompany(
                            row.id(), row.companyId(), signed.get());
                    count++;
                }
            } catch (RuntimeException ex) {
                log.warn("VF4: error firmando evento {}", row.id(), ex);
            }
        }
        return count;
    }

    /**
     * Reproduce EXACTAMENTE el XML que se intento firmar al validar
     * (mismo orden de campos, mismo escape) — si lo cambiamos, la
     * firma seria sobre un XML distinto y al verificar pareceria
     * incoherente. Mantener sincronizado con
     * VerifactuRegistryService.buildRegistryXml.
     */
    private String buildRegistryXml(VerifactuRegistryRepository.PendingForSigning row) {
        StringBuilder sb = new StringBuilder();
        sb.append("<RegistroFacturacion xmlns=\"urn:benjagest:verifactu-registry\">");
        sb.append("<IDEmisor>").append(escape(row.taxIdentifier())).append("</IDEmisor>");
        sb.append("<NumSerieFactura>").append(escape(row.invoiceNumber())).append("</NumSerieFactura>");
        sb.append("<FechaExpedicion>").append(row.invoiceDate()).append("</FechaExpedicion>");
        sb.append("<CuotaTotal>").append(nv(row.vatTotal())).append("</CuotaTotal>");
        sb.append("<ImporteTotal>").append(nv(row.total())).append("</ImporteTotal>");
        sb.append("<HuellaActual>").append(escape(row.hashCurrent())).append("</HuellaActual>");
        sb.append("<HuellaAnterior>").append(escape(row.hashPrevious() == null ? "" : row.hashPrevious())).append("</HuellaAnterior>");
        sb.append("<FechaHoraGenRegistro>").append(row.generatedAt()).append("</FechaHoraGenRegistro>");
        sb.append("</RegistroFacturacion>");
        return sb.toString();
    }

    /**
     * Mantener sincronizado con SifEventService.buildEventXml.
     */
    private String buildEventXml(SifEventRepository.PendingEvent row) {
        StringBuilder sb = new StringBuilder();
        sb.append("<RegistroEvento xmlns=\"urn:benjagest:sif-event\">");
        sb.append("<IDEmisor>").append(escape(row.taxIdentifier())).append("</IDEmisor>");
        sb.append("<TipoEvento>").append(escape(row.eventType())).append("</TipoEvento>");
        sb.append("<Payload>").append(escape(row.payload() == null ? "" : row.payload())).append("</Payload>");
        sb.append("<HuellaActual>").append(escape(row.hashCurrent())).append("</HuellaActual>");
        sb.append("<HuellaAnterior>").append(escape(row.hashPrevious() == null ? "" : row.hashPrevious())).append("</HuellaAnterior>");
        sb.append("<FechaHoraGenRegistro>").append(row.generatedAt()).append("</FechaHoraGenRegistro>");
        sb.append("</RegistroEvento>");
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String nv(BigDecimal v) {
        return v == null ? "0.00" : v.toPlainString();
    }
}
