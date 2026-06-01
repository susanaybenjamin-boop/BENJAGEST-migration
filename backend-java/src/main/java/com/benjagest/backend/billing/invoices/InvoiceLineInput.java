package com.benjagest.backend.billing.invoices;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * Linea tal como la envia el cliente al crear o editar una factura.
 * No incluye campos calculados: el Service los rellena al guardar.
 */
public record InvoiceLineInput(
        @NotBlank String description,
        String catalogItemId,
        @DecimalMin("0") BigDecimal quantity,
        @DecimalMin("0") BigDecimal unitPrice,
        @DecimalMin("0") BigDecimal vatPercent,
        @DecimalMin("0") BigDecimal retentionPercent
) {
}
