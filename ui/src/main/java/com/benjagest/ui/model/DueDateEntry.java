package com.benjagest.ui.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** PAGO-PROVEEDOR — un vencimiento de factura (compra o venta). */
public record DueDateEntry(
        String id,
        String invoiceId,
        String invoiceKind,   // PURCHASE | SALES
        int seq,
        LocalDate dueDate,
        BigDecimal amount,
        String status,        // PENDING | PAID
        LocalDate paidDate,
        String paymentMethod,
        String treasuryAccountCode,
        String journalEntryId,
        String notes
) {}
