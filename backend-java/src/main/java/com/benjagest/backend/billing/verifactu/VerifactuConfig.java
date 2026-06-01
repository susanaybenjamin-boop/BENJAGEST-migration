package com.benjagest.backend.billing.verifactu;

/**
 * Snapshot de la configuracion VeriFactu de la empresa activa.
 *
 *   - mode: OFF / TEST / PROD.
 *   - certificateId: id del .p12 que se usa para firmar (puede ser null
 *                    si todavia no hay certificado seleccionado).
 *   - certificateAlias: alias humano del certificado (LEFT JOIN para
 *                    pintar en la UI sin tener que pedir el detalle).
 *   - invoiceFooterTemplate: texto que aparece en el pie de cada factura.
 */
public record VerifactuConfig(
        String mode,
        String certificateId,
        String certificateAlias,
        String invoiceFooterTemplate
) {
}
