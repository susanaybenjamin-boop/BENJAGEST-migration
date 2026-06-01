package com.benjagest.ui.model;

/**
 * Una entrada de la pestana "Modulos" del UI de Configuracion. Refleja
 * un modulo del catalogo con su estado activo/inactivo para la empresa
 * actual.
 */
public record CompanyModuleEntry(
        String slug,
        String label,
        String description,
        String parentSlug,
        String requiresSlug,
        String icon,
        int displayOrder,
        boolean advisoryOnly,
        boolean active
) {
}
