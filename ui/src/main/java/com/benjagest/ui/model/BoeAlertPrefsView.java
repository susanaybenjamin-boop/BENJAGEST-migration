package com.benjagest.ui.model;

import java.util.List;

/**
 * BOE-FILTRO — preferencias de avisos del BOE de la empresa activa, mas lo que
 * la pantalla necesita para OFRECER opciones (GET /api/boe-alerts/prefs).
 *
 * <p>Para cada una de las tres listas, <b>vacia = no filtrar por eso</b>.
 */
public record BoeAlertPrefsView(
        boolean enabled,
        List<String> sections,
        List<String> departments,
        List<String> keywords,
        /** Catalogo de secciones del BOE (codigo + nombre) para pintar las casillas. */
        List<BoeSection> sectionCatalog,
        /**
         * Ministerios vistos realmente en los avisos ya recibidos. Se ofrecen
         * como sugerencia en vez de una lista fija, que se quedaria obsoleta en
         * cuanto renombren un ministerio.
         */
        List<String> knownDepartments,
        /** Palabras de fabrica, para el boton "Restaurar". */
        List<String> defaultKeywords
) {

    public record BoeSection(String code, String name) {}
}
