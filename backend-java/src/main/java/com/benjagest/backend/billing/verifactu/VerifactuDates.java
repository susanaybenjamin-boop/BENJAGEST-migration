package com.benjagest.backend.billing.verifactu;

import java.time.LocalDate;

/**
 * Fechas limite del RD 1007/2023 (VERI*FACTU) — slice VF-QR-TOGGLE.
 *
 * <p>Hasta su fecha de obligacion cada empresa puede emitir facturas SIN
 * el QR oficial AEAT (el toggle {@code verifactu_print_qr} lo apaga).
 * Desde esa fecha el toggle caduca: el QR se imprime siempre.
 *
 * <p>La fecha depende de la forma juridica, que deducimos del NIF:
 * <ul>
 *   <li>Empieza por letra de sociedad (A, B, ...) → 1-ene-2027
 *       (contribuyentes del Impuesto de Sociedades).</li>
 *   <li>Empieza por digito (DNI) o X/Y/Z (NIE) → 1-jul-2027
 *       (personas fisicas / autonomos).</li>
 * </ul>
 * Sin NIF (no deberia pasar en una factura valida) se aplica la fecha
 * mas temprana por prudencia.
 */
public final class VerifactuDates {

    /** Obligacion VERI*FACTU para sociedades (IS). */
    public static final LocalDate QR_DEADLINE_COMPANIES = LocalDate.of(2027, 1, 1);
    /** Obligacion VERI*FACTU para personas fisicas (autonomos). */
    public static final LocalDate QR_DEADLINE_INDIVIDUALS = LocalDate.of(2027, 7, 1);

    private VerifactuDates() {
    }

    /** Fecha desde la que el QR es obligatorio para el NIF dado. */
    public static LocalDate qrOptOutDeadline(String taxIdentifier) {
        if (taxIdentifier == null || taxIdentifier.isBlank()) {
            return QR_DEADLINE_COMPANIES;
        }
        char first = Character.toUpperCase(taxIdentifier.trim().charAt(0));
        boolean individual = Character.isDigit(first) || first == 'X' || first == 'Y' || first == 'Z';
        return individual ? QR_DEADLINE_INDIVIDUALS : QR_DEADLINE_COMPANIES;
    }

    /** True si hoy la empresa de ese NIF aun puede emitir sin QR. */
    public static boolean qrOptOutStillAllowed(String taxIdentifier) {
        return LocalDate.now().isBefore(qrOptOutDeadline(taxIdentifier));
    }
}
