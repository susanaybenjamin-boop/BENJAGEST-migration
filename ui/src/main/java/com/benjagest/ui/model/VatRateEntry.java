package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * Una fila del catalogo de tipos impositivos (IVA o IRPF).
 *
 *   - kind: VAT / WITHHOLDING.
 *   - code: identificador corto (IVA21, IRPF15...).
 *   - label: etiqueta humana.
 *   - percent: 0..100.
 *   - isDefault: el que el editor preselecciona al crear linea.
 *   - active: TRUE para aparecer en combos.
 */
public record VatRateEntry(
        String id,
        String kind,
        String code,
        String label,
        BigDecimal percent,
        boolean isDefault,
        boolean active,
        String notes,
        /** FAC-IVA: texto legal propio del tipo (se imprime en el PDF). */
        String legalText
) {
    /** Backwards-compat sin texto legal. */
    public VatRateEntry(String id, String kind, String code, String label,
                         BigDecimal percent, boolean isDefault, boolean active,
                         String notes) {
        this(id, kind, code, label, percent, isDefault, active, notes, null);
    }
}
