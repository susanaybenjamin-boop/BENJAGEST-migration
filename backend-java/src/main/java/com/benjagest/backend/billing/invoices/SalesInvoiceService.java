package com.benjagest.backend.billing.invoices;

import com.benjagest.backend.billing.series.SeriesService;
import com.benjagest.backend.billing.verifactu.VerifactuRegistryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Logica de negocio de facturas emitidas. Aqui viven los calculos de
 * totales, el cambio de estado DRAFT -> VALIDATED, y la integracion con
 * SeriesService para emitir el numero.
 *
 * Reglas:
 *   - Solo DRAFT se puede editar o cancelar (softCancelDraft).
 *   - validate exige series_id (sin serie no hay numero).
 *   - Al validar, los totales del header se recalculan a partir de las
 *     lineas; no se respeta lo que envie el cliente (defensa contra
 *     desincronizacion).
 *   - Los totales se redondean a 2 decimales (HALF_UP, lo estandar
 *     en facturacion ES).
 */
@Service
public class SalesInvoiceService {

    private final SalesInvoiceRepository repository;
    private final SeriesService seriesService;
    private final VerifactuRegistryService verifactuRegistryService;

    public SalesInvoiceService(SalesInvoiceRepository repository,
                               SeriesService seriesService,
                               VerifactuRegistryService verifactuRegistryService) {
        this.repository = repository;
        this.seriesService = seriesService;
        this.verifactuRegistryService = verifactuRegistryService;
    }

    public List<SalesInvoice> list(String statusFilter,
                                   String paymentStatusFilter,
                                   String customerIdFilter,
                                   int limit) {
        return repository.findAll(statusFilter, paymentStatusFilter, customerIdFilter, limit);
    }

    public SalesInvoice get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura no encontrada"));
    }

    @Transactional
    public SalesInvoice createDraft(InvoiceUpsertRequest request) {
        String id = UUID.randomUUID().toString();
        LocalDate invoiceDate = request.invoiceDate() == null ? LocalDate.now() : request.invoiceDate();
        LocalDate dueDate = request.dueDate() == null ? invoiceDate.plusDays(30) : request.dueDate();

        List<InvoiceLine> lines = computeLines(id, request.lines());
        Totals totals = aggregateTotals(lines);

        SalesInvoice header = new SalesInvoice(
                id,
                null,
                request.customerId(),
                null,
                blankToNull(request.seriesId()),
                null,
                invoiceDate,
                dueDate,
                request.invoiceType(),
                "DRAFT",
                "PENDING",
                totals.subtotal(),
                totals.vatTotal(),
                totals.retentionTotal(),
                totals.total(),
                BigDecimal.ZERO,
                "EUR",
                blankToNull(request.originalInvoiceId()),
                null,
                blankToNull(request.notes()),
                null,
                null,
                null,
                lines
        );
        repository.insertHeader(header);
        for (InvoiceLine line : lines) {
            repository.insertLine(line);
        }
        return get(id);
    }

    @Transactional
    public SalesInvoice updateDraft(String id, InvoiceUpsertRequest request) {
        SalesInvoice existing = get(id);
        if (!"DRAFT".equals(existing.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se editan facturas en borrador (status DRAFT)");
        }

        LocalDate invoiceDate = request.invoiceDate() == null ? existing.invoiceDate() : request.invoiceDate();
        LocalDate dueDate = request.dueDate() == null ? existing.dueDate() : request.dueDate();
        List<InvoiceLine> lines = computeLines(id, request.lines());
        Totals totals = aggregateTotals(lines);

        SalesInvoice header = new SalesInvoice(
                id, null, request.customerId(), null,
                blankToNull(request.seriesId()), existing.invoiceNumber(),
                invoiceDate, dueDate,
                request.invoiceType(),
                existing.status(), existing.paymentStatus(),
                totals.subtotal(), totals.vatTotal(), totals.retentionTotal(), totals.total(),
                existing.paidAmount(), existing.currency(),
                blankToNull(request.originalInvoiceId()), existing.rectifyingInvoiceId(),
                blankToNull(request.notes()), existing.validatedAt(),
                existing.createdAt(), existing.updatedAt(),
                lines
        );

        int affected = repository.updateHeader(id, header);
        if (affected == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La factura ya no esta en DRAFT");
        }
        repository.deleteLinesForInvoice(id);
        for (InvoiceLine line : lines) {
            repository.insertLine(line);
        }
        return get(id);
    }

    @Transactional
    public SalesInvoice validate(String id) {
        SalesInvoice existing = get(id);
        if (!"DRAFT".equals(existing.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se valida una factura en DRAFT");
        }
        if (existing.seriesId() == null || existing.seriesId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta serie de numeracion: asigna una serie antes de validar");
        }
        if (existing.lines() == null || existing.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Una factura sin lineas no se puede validar");
        }

        Totals totals = aggregateTotals(existing.lines());
        // Emite el numero. Como SeriesService.claimNextNumber es
        // @Transactional, el FOR UPDATE forma parte de la misma
        // transaccion que esta validacion.
        SeriesService.ClaimedNumber claimed = seriesService.claimNextNumber(existing.seriesId());

        int affected = repository.markValidated(
                id, claimed.formatted(),
                totals.subtotal(), totals.vatTotal(), totals.retentionTotal(), totals.total()
        );
        if (affected == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La factura ya no esta en DRAFT");
        }

        // Hook VeriFactu: si la empresa tiene mode != OFF, registramos
        // la huella encadenada. Si el modo es OFF, no se crea registro
        // (la cadena solo existe a partir del momento en que se activa).
        SalesInvoice validated = get(id);
        verifactuRegistryService.registerIfActive(validated);
        return get(id);
    }

    @Transactional
    public void deleteDraft(String id) {
        SalesInvoice existing = get(id);
        if (!"DRAFT".equals(existing.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se pueden borrar facturas en DRAFT. Para una validada hay que anular (otro slice).");
        }
        repository.softCancelDraft(id);
    }

    /**
     * Calcula los importes derivados de cada linea (subtotal, vat,
     * retention, total) a partir de quantity * unit_price y los
     * porcentajes. Lo hace el backend para evitar que el cliente
     * mande totales manipulados.
     */
    List<InvoiceLine> computeLines(String invoiceId, List<InvoiceLineInput> inputs) {
        List<InvoiceLine> result = new ArrayList<>();
        for (InvoiceLineInput input : inputs) {
            BigDecimal qty = input.quantity() == null ? BigDecimal.ONE : input.quantity();
            BigDecimal unit = input.unitPrice() == null ? BigDecimal.ZERO : input.unitPrice();
            BigDecimal vatPct = input.vatPercent() == null ? BigDecimal.ZERO : input.vatPercent();
            BigDecimal retPct = input.retentionPercent() == null ? BigDecimal.ZERO : input.retentionPercent();

            BigDecimal subtotal = qty.multiply(unit).setScale(2, RoundingMode.HALF_UP);
            BigDecimal vat = subtotal.multiply(vatPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal retention = subtotal.multiply(retPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal total = subtotal.add(vat).subtract(retention);

            result.add(new InvoiceLine(
                    UUID.randomUUID().toString(),
                    invoiceId,
                    blankToNull(input.catalogItemId()),
                    input.description().trim(),
                    qty,
                    unit,
                    vatPct,
                    retPct,
                    subtotal,
                    vat,
                    retention,
                    total
            ));
        }
        return result;
    }

    Totals aggregateTotals(List<InvoiceLine> lines) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        BigDecimal retention = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (InvoiceLine line : lines) {
            subtotal = subtotal.add(line.lineSubtotal());
            vat = vat.add(line.lineVat());
            retention = retention.add(line.lineRetention());
            total = total.add(line.lineTotal());
        }
        return new Totals(
                subtotal.setScale(2, RoundingMode.HALF_UP),
                vat.setScale(2, RoundingMode.HALF_UP),
                retention.setScale(2, RoundingMode.HALF_UP),
                total.setScale(2, RoundingMode.HALF_UP)
        );
    }

    public record Totals(BigDecimal subtotal, BigDecimal vatTotal, BigDecimal retentionTotal, BigDecimal total) {
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
