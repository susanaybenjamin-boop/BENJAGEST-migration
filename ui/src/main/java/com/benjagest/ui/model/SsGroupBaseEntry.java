package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * Base de cotización a la SS por grupo de cotización (1-11) y año (V121).
 * Grupos 8-11 usan base diaria ({@code daily=true}). {@code pendingValidation}
 * marca las cifras provisionales 2026 que Benjamin debe validar.
 */
public record SsGroupBaseEntry(
        int yearNumber,
        int cotizGroup,
        String label,
        BigDecimal baseMin,
        BigDecimal baseMax,
        boolean daily,
        boolean pendingValidation) {}
