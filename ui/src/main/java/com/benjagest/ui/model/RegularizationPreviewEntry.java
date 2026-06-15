package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * Vista previa del asiento de regularización (grupos 6x/7x → 129)
 * antes de cerrar el ejercicio. La devuelve el endpoint
 * {@code GET /accounting/year-close/{year}/preview-regularization}.
 * No crea ningún asiento: solo informa de los totales.
 */
public record RegularizationPreviewEntry(
        int periodYear,
        BigDecimal expensesTotal,
        BigDecimal incomesTotal,
        BigDecimal resultAmount
) {}
