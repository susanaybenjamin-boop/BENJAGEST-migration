package com.benjagest.ui.model;

import java.util.List;

public record DashboardData(
        /** DASH-E1 - ejercicio de las cifras (lo rotulan las tarjetas). */
        String periodYear,
        String customers,
        String invoices,
        String employees,
        String openAlerts,
        String billed,
        String pending,
        String expenses,
        String payrollNet,
        List<DashboardItem> latestInvoices,
        List<DashboardItem> alerts,
        List<DashboardItem> calendar
) {
}
