package com.benjagest.ui.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Vista UI de una factura de compra (gasto). Mismo shape que el
 * backend pero sin la trazabilidad interna (uploadedBy*) — la UI
 * solo necesita lo que muestra.
 */
public record PurchaseInvoiceEntry(
        String id,
        String supplierNif,
        String supplierName,
        String invoiceNumber,
        LocalDate invoiceDate,
        BigDecimal baseAmount,
        BigDecimal vatPercent,
        BigDecimal vatAmount,
        BigDecimal totalAmount,
        String documentSha256,
        int invoiceIndexInPdf,
        String status,
        String journalEntryId,
        String notes,
        Instant createdAt
) {
    public boolean hasJournal() { return journalEntryId != null && !journalEntryId.isBlank(); }
}
