package com.benjagest.ui.model;

/**
 * Resultado de la verificación de la cadena hash VeriFactu para la
 * empresa actual y un modo (TEST/PROD). Lo devuelve
 * {@code GET /api/billing/verifactu-registry/verify}.
 *
 * - {@code ok=true} con {@code totalChecked=0}: cadena vacía (nunca se
 *   validó nada en ese modo); también es válido.
 * - {@code ok=true} con N > 0: las N facturas encadenan correctamente.
 * - {@code ok=false}: la verificación rompe en
 *   {@code brokenInvoiceNumber} (el id queda en {@code brokenInvoiceId});
 *   {@code reason} explica si fue hash recalculado vs. cadena rota.
 */
public record VerifactuIntegrityResult(
        boolean ok,
        int totalChecked,
        String brokenInvoiceId,
        String brokenInvoiceNumber,
        String reason
) {
}
