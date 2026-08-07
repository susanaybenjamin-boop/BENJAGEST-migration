package com.benjagest.backend.billing.verifactu;

/**
 * Snapshot de la configuracion VeriFactu de la empresa activa tras
 * el slice VF-OFF-DEPRECATE (V17). Dos conceptos separados:
 *
 *   - modality (legal): VERIFACTU | NO_VERIFACTU. Antes vivia mezclado
 *                       con el environment en `mode='OFF/TEST/PROD'`,
 *                       pero la ley (RD 1007/2023) solo reconoce dos
 *                       modalidades — ambas con hash + QR; la diferencia
 *                       es solo si se envia a AEAT en tiempo real.
 *   - mode (technical): TEST | PROD. Entorno del cliente AEAT cuando la
 *                       modalidad es VERIFACTU. Se ignora cuando la
 *                       modalidad es NO_VERIFACTU (pero se conserva por
 *                       si la empresa cambia de modalidad mas tarde).
 *
 * Reglas:
 *   - El hash encadenado de facturas se genera SIEMPRE (en ambas
 *     modalidades). Solo cambia el envio.
 *   - El registro de eventos del SIF (Auditoria) solo es obligatorio en
 *     NO_VERIFACTU (en VeriFactu AEAT ya tiene la informacion).
 *
 *   - certificateId: id del .p12 que se usa para firmar (puede ser null
 *                    si todavia no hay certificado seleccionado).
 *   - certificateAlias: alias humano del certificado (LEFT JOIN para
 *                    pintar en la UI sin tener que pedir el detalle).
 *   - invoiceFooterTemplate: texto que aparece en el pie de cada factura.
 *   - invoiceStorageRoot: ruta local donde se guardan los PDFs al
 *                    validar (slice F-STORAGE). NULL = usar el default
 *                    del backend (`benjagest.invoices.storage-root`).
 *                    Estructura: {root}/{companyId}/{YYYY}/T{q}/{nº}.pdf.
 *   - printQr (VF-QR-TOGGLE): si FALSE, los PDF de factura NO llevan el
 *                    QR oficial AEAT ni su etiqueta — SOLO mientras la
 *                    empresa no este obligada (ver qrOptOutUntil). Desde
 *                    esa fecha el backend ignora el toggle.
 *   - qrOptOutUntil: fecha (ISO, computada del NIF via VerifactuDates)
 *                    hasta la que se permite emitir sin QR. Solo lectura.
 */
public record VerifactuConfig(
        String modality,
        String mode,
        String certificateId,
        String certificateAlias,
        String invoiceFooterTemplate,
        String invoiceStorageRoot,
        Boolean printQr,
        String qrOptOutUntil
) {
}
