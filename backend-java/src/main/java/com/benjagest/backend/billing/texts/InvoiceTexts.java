package com.benjagest.backend.billing.texts;

/**
 * Bloque de textos legales y opciones de presentacion que aparecen al
 * pie de cada factura emitida por la empresa.
 *
 * Cada empresa tiene UNA fila de textos (viven en la tabla companies
 * desde V15). Los seis textos cubren los escenarios que aparecen en
 * facturacion espanola:
 *
 *   - pie: texto general que aparece en TODA factura (datos de contacto
 *          adicionales, IBAN si se quiere, gracias por la compra...).
 *   - exempt: texto que aparece SOLO si la factura tiene lineas con
 *          IVA 0% por exencion (educacion, sanidad, etc.). Articulo 20
 *          Ley IVA.
 *   - reverseCharge: "sujeto pasivo" — la factura NO lleva IVA porque
 *          quien lo declara es el destinatario (servicios intracomu-
 *          nitarios B2B, ejecuciones de obra inmobiliaria, etc.).
 *   - reducedVat: nota informativa cuando se aplica IVA reducido (4% o
 *          10%).
 *   - rectifying: texto que aparece SOLO en facturas rectificativas
 *          (referencia a la factura rectificada, motivo).
 *   - legalTerms: terminos legales (condiciones de pago, demora,
 *          jurisdiccion).
 *
 *   - showIban: si la factura debe imprimir el IBAN de la empresa.
 */
public record InvoiceTexts(
        String pie,
        String exempt,
        String reverseCharge,
        String reducedVat,
        String rectifying,
        String legalTerms,
        boolean showIban
) {
}
