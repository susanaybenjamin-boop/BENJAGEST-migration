package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * DASH-E3 — un mes de la evolucion de facturacion
 * (GET /api/billing/kpis/monthly?year). Siempre llegan 12 puntos; los meses
 * sin facturar vienen a cero.
 */
public record BillingMonthPoint(
        int month,
        int count,
        BigDecimal base,
        BigDecimal vat,
        BigDecimal total
) {
}
