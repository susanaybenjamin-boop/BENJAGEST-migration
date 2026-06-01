package com.benjagest.backend.billing.invoices;

import java.math.BigDecimal;

/**
 * Una linea de factura. Los campos calculados (line_subtotal, line_vat,
 * line_retention, line_total) se rellenan en el Service a partir de
 * quantity * unit_price + porcentajes; el cliente no los manda.
 *
 * catalogItemId es opcional: hoy no se usa para nada productivo, queda
 * preparado para el slice futuro de "catalogo de productos/servicios"
 * (autocompletar precio y vat por defecto a partir del item).
 */
public record InvoiceLine(
        String id,
        String invoiceId,
        String catalogItemId,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal vatPercent,
        BigDecimal retentionPercent,
        BigDecimal lineSubtotal,
        BigDecimal lineVat,
        BigDecimal lineRetention,
        BigDecimal lineTotal
) {
}
