package com.benjagest.ui.model;

/**
 * PORT-3 SUG — Sugerencia/mejora/bug enviada por el usuario al
 * fabricante. Per-tenant.
 */
public record SuggestionEntry(
        String id,
        String title,
        String description,
        String category,
        String status,
        String answer,
        String createdAt
) {}
