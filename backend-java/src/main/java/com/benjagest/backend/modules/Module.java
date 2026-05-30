package com.benjagest.backend.modules;

/**
 * Una entrada del catalogo de modulos (puede ser categoria o sub-modulo).
 * Si parentSlug es null, es una categoria raiz.
 */
public record Module(
        String slug,
        String label,
        String description,
        String parentSlug,
        String requiresSlug,
        String icon,
        int displayOrder,
        boolean advisoryOnly,
        boolean activeInCatalog
) {
}
