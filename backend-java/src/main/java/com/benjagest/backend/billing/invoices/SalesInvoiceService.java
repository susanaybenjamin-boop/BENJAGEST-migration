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

        // Resolvemos la serie en el servidor por el invoice_type
        // (NORMAL → STANDARD, RECTIFYING → RECT, etc). Ignoramos lo que
        // mande el cliente: si el editor de la UI dejara de mandar
        // seriesId, sigue funcionando; si lo manda mal, no podemos darle
        // una serie incongruente con el kind. Decision 2026-06-02 — el
        // server elige, el usuario no.
        String resolvedSeriesId = seriesService.findActiveByKind(request.invoiceType()).id();

        SalesInvoice header = new SalesInvoice(
                id,
                null,
                request.customerId(),
                null,
                resolvedSeriesId,
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

        // El invoice_type se PRESERVA del borrador existente. Si el cliente
        // intenta cambiar el tipo (p.ej. "NORMAL" en una rectificativa
        // creada via /void), lo ignoramos. Solo createDraft fija el tipo;
        // una vez creado, no se cambia. Asi la cascada de anulacion
        // (RECTIFYING → original VOIDED al validar) no se puede esquivar
        // editando el tipo del borrador.
        String preservedType = existing.invoiceType();
        String resolvedSeriesId = seriesService.findActiveByKind(preservedType).id();

        SalesInvoice header = new SalesInvoice(
                id, null, request.customerId(), null,
                resolvedSeriesId, existing.invoiceNumber(),
                invoiceDate, dueDate,
                preservedType,
                existing.status(), existing.paymentStatus(),
                totals.subtotal(), totals.vatTotal(), totals.retentionTotal(), totals.total(),
                existing.paidAmount(), existing.currency(),
                // El vinculo con la factura original (si lo hay) tampoco se
                // edita desde aqui — solo lo fija voidValidated() al crear
                // el borrador rectificativa.
                existing.originalInvoiceId(), existing.rectifyingInvoiceId(),
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
        return validateInternal(id);
    }

    /**
     * Cuerpo de la validación. Está separado del método público para
     * poder ser reutilizado desde {@link #voidValidated(String)} sin
     * que self-invocation rompa el proxy AOP de @Transactional (Spring
     * NO aplica el aspecto cuando un método de la misma clase llama
     * directamente a otro). Al llamarse desde voidValidated, que ya
     * lleva su propio @Transactional, todo viaja en la misma tx.
     */
    private SalesInvoice validateInternal(String id) {
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

        // Cascada de anulacion: si esta factura es una RECTIFYING que
        // apunta a una original, al validarla la original pasa a VOIDED
        // y se rellena su rectifying_invoice_id apuntando a esta.
        // Este es el momento legal en que la factura original queda
        // "anulada con vinculo" — ni antes ni despues, ni con cancelar
        // sueltos.
        if ("RECTIFYING".equals(validated.invoiceType())
                && validated.originalInvoiceId() != null
                && !validated.originalInvoiceId().isBlank()) {
            repository.markVoided(validated.originalInvoiceId());
            repository.setRectifyingInvoiceId(validated.originalInvoiceId(), id);
        }

        return get(id);
    }

    /**
     * Anulación con vínculo (decisión 2026-06-03): dada una factura
     * VALIDATED, emitimos en UNA SOLA TRANSACCIÓN una factura rectificativa
     * ya VALIDATED enlazada a la original. La original pasa a VOIDED.
     *
     * Por qué ya validada y no como borrador editable:
     *   - Una vez validada la original, su rectificativa es un acto
     *     legal: emitirla como borrador abriría una ventana en la que
     *     el usuario podría manipular cifras antes de "confirmar". Eso
     *     rompe el sentido de la rectificativa por anulación.
     *   - La rectificativa parcial (cambiar líneas concretas) será un
     *     flujo aparte cuando lleguemos al slice "Rectificativa parcial
     *     R1-R5" — ahí sí tiene sentido revisar antes de validar.
     *
     * Las líneas se generan como copia de las originales con quantity
     * invertida → totales negativos. El número y el hash VeriFactu se
     * emiten ya. La original queda VOIDED con rectifying_invoice_id
     * apuntando a la nueva.
     */
    @Transactional
    public SalesInvoice voidValidated(String validatedId) {
        SalesInvoice original = get(validatedId);
        if (!"VALIDATED".equals(original.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se puede anular una factura VALIDATED. Para borrar borradores usa DELETE.");
        }
        if (original.rectifyingInvoiceId() != null && !original.rectifyingInvoiceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta factura ya tiene una rectificativa creada: " + original.rectifyingInvoiceId());
        }

        // Resolvemos la serie RECTIFYING del cliente activo. La V16
        // garantiza que existe; si no, el server responde 428 (claro)
        // en vez de NPE confuso.
        String rectSeriesId = seriesService.findActiveByKind("RECTIFYING").id();

        String newId = UUID.randomUUID().toString();
        LocalDate today = LocalDate.now();
        List<InvoiceLineInput> negatedInputs = new ArrayList<>();
        for (InvoiceLine line : original.lines()) {
            // Ojo: el record InvoiceLineInput espera description ANTES de
            // catalogItemId. El bug previo intercambiaba los dos primeros
            // → description llegaba null y computeLines reventaba con NPE
            // en .trim().
            negatedInputs.add(new InvoiceLineInput(
                    line.description(),
                    line.catalogItemId(),
                    line.quantity() == null ? null : line.quantity().negate(),
                    line.unitPrice(),
                    line.vatPercent(),
                    line.retentionPercent()
            ));
        }
        List<InvoiceLine> lines = computeLines(newId, negatedInputs);
        Totals totals = aggregateTotals(lines);

        // Insertamos primero como DRAFT (mismo formato que createDraft)
        // para reusar el flujo de validate. La factura nunca queda en
        // DRAFT visible para el usuario: la validamos en la misma
        // transacción inmediatamente despues — todo o nada.
        SalesInvoice draft = new SalesInvoice(
                newId,
                null,
                original.customerId(),
                null,
                rectSeriesId,
                null,
                today,
                today.plusDays(30),
                "RECTIFYING",
                "DRAFT",
                "PENDING",
                totals.subtotal(), totals.vatTotal(), totals.retentionTotal(), totals.total(),
                BigDecimal.ZERO,
                "EUR",
                original.id(),
                null,
                "Rectificativa por anulacion de "
                        + (original.invoiceNumber() == null ? original.id() : original.invoiceNumber()),
                null,
                null,
                null,
                lines
        );
        repository.insertHeader(draft);
        for (InvoiceLine line : lines) {
            repository.insertLine(line);
        }

        // Validamos YA mismo, en la misma transacción → la rectificativa
        // sale numerada (claimNextNumber), VeriFactu registra el hash, la
        // original pasa a VOIDED, y devolvemos el documento legal final.
        // Llamamos a validateInternal (sin proxy) para evitar el problema
        // de self-invocation con @Transactional.
        return validateInternal(newId);
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
