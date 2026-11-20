package com.benjagest.ui.model;

import java.util.List;

/**
 * BOE-FILTRO — resultado de la prueba en seco: que habria llegado de un dia
 * concreto del BOE con unas preferencias dadas (POST /api/boe-alerts/preview).
 *
 * <p>No guarda ni notifica nada: sirve para afinar el filtro contra un dia real
 * (uno de los que llenaron la bandeja) y ver el efecto ANTES de guardar, en vez
 * de cambiar palabras a ciegas y esperar a mañana a las 06:00.
 */
public record BoePreviewResult(
        String date,
        /** Articulos que publico el BOE ese dia. */
        int totalArticles,
        /** Cuantos te habrian llegado. */
        int matched,
        List<Item> items,
        /** Mensaje si la consulta al BOE fallo; null si fue bien. */
        String error
) {

    public record Item(
            String boeId,
            String title,
            String url,
            String department,
            String section,
            String keywordsMatched
    ) {}
}
