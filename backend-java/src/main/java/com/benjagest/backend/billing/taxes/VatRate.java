package com.benjagest.backend.billing.taxes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Tipo impositivo del catalogo (IVA o IRPF).
 *
 * <ul>
 *   <li>kind: VAT o WITHHOLDING.</li>
 *   <li>code: identificador corto (IVA21, IRPF15...). Unico por
 *       empresa + kind.</li>
 *   <li>label: etiqueta humana visible en combos.</li>
 *   <li>percent: porcentaje 0..100. El backend cobra la operacion
 *       sobre el subtotal de la linea (linea.subtotal * percent / 100).</li>
 *   <li>isDefault: el tipo que el editor preselecciona al crear linea
 *       nueva. Solo uno por kind y empresa.</li>
 *   <li>active: TRUE para aparecer en combos. FALSE conserva el tipo
 *       para facturas antiguas que lo usaron, pero lo oculta.</li>
 * </ul>
 */
public record VatRate(
        String id,
        String companyId,
        String kind,
        String code,
        String label,
        BigDecimal percent,
        boolean isDefault,
        boolean active,
        String notes,
        Instant createdAt,
        Instant updatedAt,
        /**
         * FAC-IVA (2026-07-10): texto legal PROPIO del tipo, impreso en el
         * PDF de la factura cuando alguna línea lo usa. Clave con dos tipos
         * al mismo % (dos 0% con conceptos legales distintos).
         */
        String legalText
) {
    /** Backwards-compat sin texto legal. */
    public VatRate(String id, String companyId, String kind, String code,
                    String label, BigDecimal percent, boolean isDefault,
                    boolean active, String notes, Instant createdAt, Instant updatedAt) {
        this(id, companyId, kind, code, label, percent, isDefault, active,
                notes, createdAt, updatedAt, null);
    }
}
