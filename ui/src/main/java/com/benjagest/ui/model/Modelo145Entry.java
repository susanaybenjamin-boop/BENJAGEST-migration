package com.benjagest.ui.model;

/** Datos del modelo 145 (situación personal y familiar) de un empleado. */
public record Modelo145Entry(
        int familySituation,            // 1 monoparental, 2 cónyuge sin rentas, 3 resto
        String spouseNif,
        int descendants,
        int descendantsUnder3,
        int descendantsDisability33,
        int descendantsDisability65,
        boolean exclusiveCustody,
        int ascendantsOver65,
        int ascendantsOver75,
        String ownDisability,           // NONE / D33 / D65
        boolean ownMobility,
        boolean taxpayerOver65,
        boolean taxpayerOver75,
        boolean contractUnderYear,
        boolean geographicMobility,
        boolean mortgageBefore2013
) {}
