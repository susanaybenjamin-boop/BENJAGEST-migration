package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * Datos minimos de una factura para pintar el listado de Facturacion.
 * No incluye lineas: solo el header. Para el detalle (lineas) se pide
 * la factura completa con otro endpoint, asi el listado se mantiene
 * ligero aunque haya cientos.
 */
public record SalesInvoiceSummary(
        String id,
        String invoiceNumber,
        String customerLegalName,
        String invoiceDate,
        String dueDate,
        String status,
        String paymentStatus,
        BigDecimal total,
        BigDecimal paidAmount
) {
}
