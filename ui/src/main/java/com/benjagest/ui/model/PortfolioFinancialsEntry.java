package com.benjagest.ui.model;

import java.math.BigDecimal;

/** PANORAMA-ASESORIA — KPIs agregados cross-client. */
public record PortfolioFinancialsEntry(
        BigDecimal billedThisMonth,
        BigDecimal pendingPayment,
        int overdueInvoices,
        int activeClientsThisMonth,
        int pendingTpbApprovals
) {}
