package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * DASH-E3 — indicadores del cuadro de mando de Facturacion
 * (GET /api/billing/kpis?from&amp;to).
 *
 * <p>Espejo de {@code BillingKpiService.BillingKpis} del backend. Todas las
 * cifras de importe son de facturas EMITIDAS Y VIVAS (validadas, sin las
 * rectificativas que netean con una anulada) con fecha dentro del rango.
 * Borradores y anuladas van en campos aparte porque son avisos, no
 * facturacion.
 */
public record BillingKpis(
        String from,
        String to,
        int issuedCount,
        BigDecimal billedBase,
        BigDecimal vatCharged,
        BigDecimal retention,
        BigDecimal billedTotal,
        BigDecimal avgTicket,
        BigDecimal collected,
        BigDecimal pendingCollections,
        int pendingCount,
        BigDecimal overdueAmount,
        int overdueCount,
        int draftCount,
        BigDecimal draftBase,
        int voidedCount,
        int customersBilled,
        /** Puede venir vacio si no hay ninguna factura en el rango. */
        String topCustomerName,
        BigDecimal topCustomerBase
) {
}
