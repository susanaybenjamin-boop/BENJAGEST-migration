package com.benjagest.backend.settings;

/**
 * Una entrada para la pestana "Modulos" de Configuracion: muestra cada
 * modulo del catalogo con su estado activo/inactivo para la empresa
 * actual. Permite a la UI pintar el arbol con switches.
 */
public record CompanyModuleView(
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
