package com.benjagest.backend.billing.sif;

import java.time.Instant;

/**
 * Entrada del Registro de Eventos del SIF (RD 1007/2023 + Orden
 * HAC/1177/2024 art. 16). Cadena hash propia, independiente de la
 * cadena de facturas. Solo se genera en modalidad NO VeriFactu — en
 * VeriFactu AEAT ya tiene la trazabilidad por el envio en tiempo real.
 *
 * payload contiene un JSON corto con el detalle del evento (p.ej. id
 * de la factura validada, ruta exportada, motivo del backup...). El
 * hash se calcula sobre los campos canonicos (NIF, tipo, payload,
 * huella anterior, fecha generacion) — el formato exacto que define
 * la Orden HAC se ajustara cuando VF3 lo exija para el envio.
 */
public record SifEvent(
        String id,
        String companyId,
        String eventType,
        String payload,
        String hashCurrent,
        String hashPrevious,
        Instant generatedAt,
        Instant signedAt,
        String signatureData,
        String status
) {
}
