package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * CONC-2 — Un concepto reutilizable para las líneas de factura, tal como
 * lo devuelve {@code GET /api/billing/catalog-items}.
 *
 * <p>{@code source} distingue las dos fuentes:
 * <ul>
 *   <li>{@code CATALOG} — guardado a mano; tiene {@code id} y se puede
 *       editar o quitar del catálogo.</li>
 *   <li>{@code HISTORY} — sacado de facturas anteriores; {@code id} viene
 *       vacío y solo se puede usar o guardar en el catálogo.</li>
 * </ul>
 *
 * <p>{@code name} es la etiqueta corta del selector; {@code description}
 * es el texto que se vuelca tal cual en la línea de la factura.
 */
public record ConceptEntry(
        String id,
        String source,
        String name,
        String description,
        BigDecimal unitPrice,
        BigDecimal vatPercent,
        BigDecimal retentionPercent,
        String vatRateId,
        int usageCount,
        String lastUsedAt
) {
    public static final String SOURCE_CATALOG = "CATALOG";
    public static final String SOURCE_HISTORY = "HISTORY";

    /** true si está guardado en el catálogo (se puede editar/quitar). */
    public boolean saved() {
        return id != null && !id.isBlank();
    }

    /** Texto que se vuelca en la línea de factura. */
    public String invoiceText() {
        return description == null || description.isBlank() ? name : description;
    }
}
