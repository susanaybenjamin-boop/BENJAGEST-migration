package com.benjagest.ui.model;

import java.math.BigDecimal;

/** Tipos de cotización a la Seguridad Social de un año (PARAM-YEAR). */
public record SsRateEntry(
        int year,
        BigDecimal eeCommon, BigDecimal eeUnemployment, BigDecimal eeTraining, BigDecimal eeMei,
        BigDecimal erCommon, BigDecimal erUnemployment, BigDecimal erFogasa,
        BigDecimal erTraining, BigDecimal erMei, BigDecimal defaultAtEp,
        String legalReference
) {}
