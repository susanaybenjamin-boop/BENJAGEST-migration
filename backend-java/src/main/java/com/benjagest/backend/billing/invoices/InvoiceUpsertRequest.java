package com.benjagest.backend.billing.invoices;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * Payload de POST (crear) y PUT (editar) de factura en estado DRAFT.
 *
 * - customerId: obligatorio.
 * - seriesId: opcional al crear; obligatorio al validar (sin serie no
 *             hay numero). Hoy lo aceptamos en draft para que el
 *             usuario pueda decidir despues.
 * - invoiceType: NORMAL por defecto. RECTIFYING usa originalInvoiceId.
 * - lines: al menos una linea (una factura sin lineas no tiene sentido
 *          ni legal ni fiscalmente).
 */
public record InvoiceUpsertRequest(
        @NotBlank String customerId,
        String seriesId,
        @NotBlank @Size(max = 30) String invoiceType,
        LocalDate invoiceDate,
        LocalDate dueDate,
        String originalInvoiceId,
        String concept,
        String notes,
        @NotEmpty @Valid List<InvoiceLineInput> lines
) {
}
