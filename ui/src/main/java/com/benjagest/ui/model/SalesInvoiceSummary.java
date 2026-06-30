package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * Datos minimos de una factura para pintar el listado de Facturacion.
 * No incluye lineas: solo el header. Para el detalle (lineas) se pide
 * la factura completa con otro endpoint, asi el listado se mantiene
 * ligero aunque haya cientos.
 *
 * pdfStored = TRUE cuando el backend ya ha guardado el PDF en la
 * ruta configurada (slice F-STORAGE). La UI usa esto para mutar el
 * boton entre "Abrir PDF" (true) y "Guardar PDF" (false).
 */
public record SalesInvoiceSummary(
        String id,
        String invoiceNumber,
        String customerLegalName,
        // NIF del cliente (de customers.tax_identifier vía JOIN). Solo viene en el
        // listado/header; sirve para prefijar el NIF al "Hacer recurrente".
        String customerTaxId,
        String invoiceDate,
        String dueDate,
        String status,
        String paymentStatus,
        BigDecimal total,
        BigDecimal paidAmount,
        // Solo viene rellenado cuando el backend devuelve el header
        // detallado (getInvoiceById). En el listado normal puede ser null.
        String invoiceType,
        String originalInvoiceId,
        Boolean pdfStored
) {
    /** Convenience cuando la API no devuelve los campos extra (listado). */
    public SalesInvoiceSummary(String id, String invoiceNumber, String customerLegalName,
                                String invoiceDate, String dueDate, String status,
                                String paymentStatus, BigDecimal total, BigDecimal paidAmount) {
        this(id, invoiceNumber, customerLegalName, null, invoiceDate, dueDate, status,
                paymentStatus, total, paidAmount, null, null, Boolean.FALSE);
    }
}
