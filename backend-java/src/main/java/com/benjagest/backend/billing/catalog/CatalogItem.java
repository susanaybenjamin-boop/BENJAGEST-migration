package com.benjagest.backend.billing.catalog;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * CONC-1 — Un concepto GUARDADO en el catálogo de la empresa
 * (tabla {@code catalog_items}, existente desde V2 y encendida en este
 * slice).
 *
 * <p>{@code name} es la etiqueta corta que se ve en el selector;
 * {@code description} es el texto EXACTO que se vuelca en la línea de
 * factura (puede ser más largo y con saltos de línea). Si viene vacía se
 * usa el nombre.
 */
public record CatalogItem(
        String id,
        String companyId,
        String customerId,
        String itemType,
        String name,
        String description,
        String category,
        BigDecimal unitPrice,
        BigDecimal defaultVatPercent,
        BigDecimal defaultRetentionPercent,
        String defaultVatRateId,
        boolean billable,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    /** Texto que se vuelca en la línea de factura al elegir el concepto. */
    public String invoiceText() {
        return description == null || description.isBlank() ? name : description;
    }
}
