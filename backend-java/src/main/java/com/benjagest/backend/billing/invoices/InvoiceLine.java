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
        BigDecimal lineTotal,
        /**
         * FAC-IVA (2026-07-10): QUÉ tipo del catálogo vat_rates eligió la
         * línea. Con dos tipos al mismo % (dos 0% con conceptos distintos)
         * el % no basta para saber qué texto legal imprime el PDF. NULL en
         * histórico/imports: se cae al comportamiento por porcentaje.
         */
        String vatRateId
) {
    /** Backwards-compat para callers sin tipo de IVA del catálogo. */
    public InvoiceLine(String id, String invoiceId, String catalogItemId,
                        String description, BigDecimal quantity, BigDecimal unitPrice,
                        BigDecimal vatPercent, BigDecimal retentionPercent,
                        BigDecimal lineSubtotal, BigDecimal lineVat,
                        BigDecimal lineRetention, BigDecimal lineTotal) {
        this(id, invoiceId, catalogItemId, description, quantity, unitPrice,
                vatPercent, retentionPercent, lineSubtotal, lineVat,
                lineRetention, lineTotal, null);
    }
}
