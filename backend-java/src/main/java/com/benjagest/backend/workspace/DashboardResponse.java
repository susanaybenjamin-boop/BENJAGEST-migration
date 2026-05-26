package com.benjagest.backend.workspace;

import java.math.BigDecimal;
import java.util.List;

public record DashboardResponse(
        long customers,
        long invoices,
        long employees,
        long openAlerts,
        BigDecimal billed,
        BigDecimal pending,
        BigDecimal expenses,
        BigDecimal payrollNet,
        List<DashboardItem> latestInvoices,
        List<DashboardItem> alerts,
        List<DashboardItem> calendar
) {
}
