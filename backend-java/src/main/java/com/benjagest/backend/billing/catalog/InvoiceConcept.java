package com.benjagest.backend.billing.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CONC-1 — Lo que ve el selector de conceptos del editor de factura: la
 * UNIÓN de las dos fuentes que pidió Benjamin.
 *
 * <ul>
 *   <li>{@code source=CATALOG} — concepto guardado a mano en
 *       {@code catalog_items}. Lleva {@code id} (se puede editar/borrar).</li>
 *   <li>{@code source=HISTORY} — concepto que ya se usó en facturas
 *       anteriores, agregado al vuelo desde {@code sales_invoice_lines}.
 *       {@code id} es null: no hay fila que editar, pero se puede
 *       "guardar en el catálogo" (POST) para tenerlo fijo.</li>
 * </ul>
 *
 * <p>{@code usageCount} / {@code lastUsedAt} salen SIEMPRE del histórico
 * real de facturas, también para los del catálogo (así el catálogo se
 * ordena por lo que de verdad se factura, no por lo que se guardó).
 */
public record InvoiceConcept(
        String id,
        String source,
        String name,
        String description,
        BigDecimal unitPrice,
        BigDecimal vatPercent,
        BigDecimal retentionPercent,
        String vatRateId,
        int usageCount,
        LocalDate lastUsedAt
) {
    public static final String SOURCE_CATALOG = "CATALOG";
    public static final String SOURCE_HISTORY = "HISTORY";
}
