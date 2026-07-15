package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * LIQ-BACKFILL — una liquidación del 303 que falta por contabilizar, tal y como
 * se le enseña a Benjamin ANTES de crear nada.
 *
 * <p>Decisión suya (2026-07-15): la regularización de trimestres anteriores se
 * hace con vista previa y confirmación, nunca con un script que se ejecute solo
 * al actualizar — son sus libros.
 */
public record PendingLiquidationEntry(
        String filingId,
        String periodLabel,
        int year,
        int quarter,
        BigDecimal saldo477,
        BigDecimal saldo472,
        BigDecimal resultado,
        /** 4750 si sale a ingresar, 4700 si a devolver/compensar. */
        String cuentaHacienda,
        boolean puedeAplicarse,
        /** Por qué no se puede aplicar (null si se puede). */
        String motivo
) {
}
