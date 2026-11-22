package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * PANORAMA-ASESORIA — KPIs agregados cross-client.
 *
 * <p>CARTERA-FILTRO (2026-11-22): {@code billed} y {@code activeClients} se
 * llamaban antes {@code billedThisMonth} y {@code activeClientsThisMonth}. Desde
 * que el periodo se elige, ese nombre mentiria al mirar un trimestre o un ano.
 * Los otros tres son fotos de HOY y no dependen del periodo.
 */
public record PortfolioFinancialsEntry(
        BigDecimal billed,
        BigDecimal pendingPayment,
        int overdueInvoices,
        int activeClients,
        int pendingTpbApprovals
) {}
