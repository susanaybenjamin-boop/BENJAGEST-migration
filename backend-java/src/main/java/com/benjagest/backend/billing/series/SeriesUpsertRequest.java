package com.benjagest.backend.billing.series;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload comun de POST (crear) y PUT (editar) /api/billing/series.
 *
 * - code: identificador visible al usuario (ej. "F2026", "PROF").
 *         Unico por empresa.
 * - invoiceKind: STANDARD | PROFORMA | RECTIFYING | TEST (lo enforza
 *                el CHECK constraint de la BD; aqui validamos cliente
 *                para fallar 400 antes de tocar BD).
 * - numberingType: STANDARD | BY_YEAR | PREFIXED.
 * - formatTemplate: con placeholders {YYYY} y {0000}, {00000}, etc.
 * - initialNextNumber: solo aplica en CREATE; en UPDATE se ignora
 *                porque modificar el correlativo a mano abriria un
 *                agujero legal (un humano poniendo "vuelve al 1" en
 *                medio del ano).
 */
public record SeriesUpsertRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Pattern(regexp = "STANDARD|PROFORMA|RECTIFYING|TEST") String invoiceKind,
        @NotBlank @Pattern(regexp = "STANDARD|BY_YEAR|PREFIXED") String numberingType,
        @Size(max = 120) String formatTemplate,
        @Min(1) Integer initialNextNumber,
        Boolean locked
) {
}
