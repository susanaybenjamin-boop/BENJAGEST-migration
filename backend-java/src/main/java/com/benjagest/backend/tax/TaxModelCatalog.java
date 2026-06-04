package com.benjagest.backend.tax;

/**
 * Catalogo global de modelos AEAT (303, 130, 200, 347...).
 * Lo poblamos en V28 y se considera de solo-lectura desde la app.
 */
public record TaxModelCatalog(
        String code,
        String name,
        String description,
        String periodicity,
        String infoUrl,
        boolean active
) {}
