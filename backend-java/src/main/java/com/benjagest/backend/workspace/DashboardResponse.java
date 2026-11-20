package com.benjagest.backend.workspace;

import java.math.BigDecimal;
import java.util.List;

public record DashboardResponse(
        /**
         * DASH-E1 - ejercicio al que se refieren TODAS las cifras de abajo
         * (ano natural en curso). Lo manda el backend para que la UI pueda
         * rotularlo en las tarjetas: sin el, un "Facturado 12.400 EUR" no dice
         * de que periodo habla y el usuario no puede cuadrarlo con nada.
         */
        int periodYear,
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
