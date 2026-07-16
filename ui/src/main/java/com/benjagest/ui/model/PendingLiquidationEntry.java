package com.benjagest.ui.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * LIQ-BACKFILL — un asiento fiscal que falta por contabilizar, tal y como se le
 * enseña a Benjamin ANTES de crear nada.
 *
 * <p>Decisión suya (2026-07-15): la regularización de trimestres anteriores se
 * hace con vista previa y confirmación, nunca con un script que se ejecute solo
 * al actualizar — son sus libros.
 *
 * <p>LIQ-130-BF (2026-07-16): antes esto solo representaba LIQUIDACIONES del
 * 303. Ahora un trimestre puede dar dos filas (la liquidación y su pago) y
 * también cubre el 130, que solo tiene pago.
 */
public record PendingLiquidationEntry(
        String filingId,
        String periodLabel,
        int year,
        int quarter,
        /** "303" o "130". */
        String modelCode,
        /** CLAVE, no texto: LIQUIDATION o PAYMENT. La UI la traduce. */
        String kind,
        /** Solo en la liquidación; null en un pago. */
        BigDecimal saldo477,
        /** Solo en la liquidación; null en un pago. */
        BigDecimal saldo472,
        /** En la liquidación, 477-472. En el pago, el importe que se paga. */
        BigDecimal resultado,
        /** Liquidación: 4750 a ingresar / 4700 a devolver. Pago: 4750 (303) o 473 (130). */
        String cuentaHacienda,
        /** Con qué fecha nacerá. En un pago regularizado, el plazo del modelo — no hoy. */
        LocalDate fechaAsiento,
        boolean puedeAplicarse,
        /** Por qué no se puede aplicar (null si se puede). */
        String motivo
) {
}
