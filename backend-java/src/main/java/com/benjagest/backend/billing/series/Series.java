package com.benjagest.backend.billing.series;

import java.time.Instant;

/**
 * Una serie de numeracion de facturacion. Cada empresa puede tener
 * varias series simultaneamente para separar:
 *
 *   - facturas estandar (STANDARD)
 *   - proformas (PROFORMA): cotizaciones, no son factura legal
 *   - rectificativas (RECTIFYING): corrigen una factura anterior
 *   - test (TEST): pruebas, no van a AEAT
 *
 * El numero emitido se compone de format_template + next_number, con
 * placeholders {YYYY} para el ano y {0000} (o {00000}, etc.) para el
 * correlativo con padding de ceros a la izquierda. Ejemplo:
 * "F-{YYYY}-{0000}" + next_number=4 + currentYear=2026 -> "F-2026-0004".
 *
 * numbering_type:
 *   - STANDARD: el correlativo NUNCA se resetea (continuo, p.ej. 1, 2,
 *               3...).
 *   - BY_YEAR: el correlativo se resetea a 1 cada vez que cambia
 *               current_year. Es la convencion habitual en Espana.
 *   - PREFIXED: el formato lleva un prefijo fijo distinto al ano (lo
 *               decide format_template; reset no aplica).
 */
public record Series(
        String id,
        String companyId,
        String code,
        String invoiceKind,
        String numberingType,
        String formatTemplate,
        int nextNumber,
        Integer currentYear,
        boolean locked,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
