package com.benjagest.ui.model;

/**
 * Resultado de verificar la cadena hash del Registro de Eventos del
 * SIF (NO VeriFactu). Mismo patron que {@link VerifactuIntegrityResult}
 * pero referido a eventos en vez de a facturas.
 */
public record SifEventIntegrityResult(
        boolean ok,
        int totalChecked,
        String brokenEventId,
        String brokenEventType,
        String reason
) {
}
