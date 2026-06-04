package com.benjagest.ui.model;

/** Fila del catalogo de modelos AEAT (303, 130, 200...). */
public record TaxModelEntry(
        String code,
        String name,
        String description,
        String periodicity,
        String infoUrl,
        boolean active
) {}
