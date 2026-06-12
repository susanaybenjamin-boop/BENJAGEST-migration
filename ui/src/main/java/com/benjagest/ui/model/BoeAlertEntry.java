package com.benjagest.ui.model;

/** BOE-RSS — alerta del Boletin Oficial del Estado. */
public record BoeAlertEntry(
        String id,
        String alertDate,
        String boeId,
        String title,
        String url,
        String department,
        String keywordsMatched,
        String createdAt
) {}
