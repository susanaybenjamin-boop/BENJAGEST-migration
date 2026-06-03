package com.benjagest.backend.billing.invoices;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Vista interna de una factura emitida. Los totales (subtotal, vatTotal,
 * retentionTotal, total) son agregados de las lineas, calculados por el
 * Service al validar; estan en el record para que el Repository los
 * mapee y los devuelva al cliente sin recalcular.
 *
 * status:
 *   - DRAFT: editable, no enviada a AEAT, no cuenta legalmente.
 *   - VALIDATED: numerada, totales finales, lista para hash+envio. Una
 *                vez validada, ya no se edita: para corregir hay que
 *                emitir una rectificativa (original_invoice_id apunta
 *                a esta, rectifying_invoice_id se rellena al revez).
 *   - CANCELLED: anulada antes de validar (solo desde DRAFT).
 *   - VOIDED: anulada legalmente despues de validar (envia evento
 *             ANULACION a AEAT, mantiene historico).
 *
 * paymentStatus se mantiene independiente del status legal: una
 * factura VALIDATED puede estar PENDING/PARTIAL/PAID/OVERDUE.
 */
public record SalesInvoice(
        String id,
        String companyId,
        String customerId,
        String customerLegalName,
        String seriesId,
        String invoiceNumber,
        LocalDate invoiceDate,
        LocalDate dueDate,
        String invoiceType,
        String status,
        String paymentStatus,
        BigDecimal subtotal,
        BigDecimal vatTotal,
        BigDecimal retentionTotal,
        BigDecimal total,
        BigDecimal paidAmount,
        String currency,
        String originalInvoiceId,
        String rectifyingInvoiceId,
        String notes,
        String pdfPath,
        Instant validatedAt,
        Instant createdAt,
        Instant updatedAt,
        List<InvoiceLine> lines
) {
}
