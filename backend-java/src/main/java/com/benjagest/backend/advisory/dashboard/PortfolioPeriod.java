package com.benjagest.backend.advisory.dashboard;

import java.time.LocalDate;

/**
 * CARTERA-FILTRO (2026-11-22) — periodo por el que se miran los KPIs de "Mi
 * cartera".
 *
 * <p>Petición de Benjamin: el bloque sumaba siempre el mes en curso de TODA la
 * cartera y no había forma de mirar un cliente suelto ni otro trimestre.
 *
 * <p><b>El periodo NO se aplica a los cinco indicadores, y es a propósito.</b>
 * Solo dos miran un rango de fechas — lo facturado y cuántos clientes
 * facturaron. Los otros tres (pendiente de cobro, vencidas y TPB sin aceptar)
 * son fotos de <b>hoy</b>: "lo que está vencido" o "lo que falta por cobrar" no
 * significa nada acotado al trimestre pasado, y acotarlo daría un número que
 * parece correcto y no lo es.
 *
 * <p>El rango es <b>[desde, hasta)</b>: el primer día entra y el último no. Así
 * no hay que pensar en horas ni en el último día del mes, que es justo donde
 * salen los errores de un día.
 */
public enum PortfolioPeriod {

    CURRENT_MONTH,
    PREVIOUS_MONTH,
    CURRENT_QUARTER,
    PREVIOUS_QUARTER,
    CURRENT_YEAR,
    PREVIOUS_YEAR;

    /** Lo que se usa si no llega ninguno, o llega algo que no se reconoce. */
    public static final PortfolioPeriod DEFAULT = CURRENT_MONTH;

    /**
     * Traduce lo que venga por la URL. Nunca falla: un valor desconocido cae al
     * de siempre en lugar de reventar la pantalla entera.
     */
    public static PortfolioPeriod parse(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return DEFAULT;
        }
    }

    /** Primer día del periodo, incluido. */
    public LocalDate from(LocalDate today) {
        return switch (this) {
            case CURRENT_MONTH -> today.withDayOfMonth(1);
            case PREVIOUS_MONTH -> today.withDayOfMonth(1).minusMonths(1);
            case CURRENT_QUARTER -> quarterStart(today);
            case PREVIOUS_QUARTER -> quarterStart(today).minusMonths(3);
            case CURRENT_YEAR -> today.withDayOfYear(1);
            case PREVIOUS_YEAR -> today.withDayOfYear(1).minusYears(1);
        };
    }

    /** Primer día del periodo siguiente: <b>NO</b> entra en el rango. */
    public LocalDate toExclusive(LocalDate today) {
        return switch (this) {
            case CURRENT_MONTH -> today.withDayOfMonth(1).plusMonths(1);
            case PREVIOUS_MONTH -> today.withDayOfMonth(1);
            case CURRENT_QUARTER -> quarterStart(today).plusMonths(3);
            case PREVIOUS_QUARTER -> quarterStart(today);
            case CURRENT_YEAR -> today.withDayOfYear(1).plusYears(1);
            case PREVIOUS_YEAR -> today.withDayOfYear(1);
        };
    }

    /**
     * Primer día del trimestre natural al que pertenece la fecha: 1-ene, 1-abr,
     * 1-jul o 1-oct. Son los trimestres de Hacienda, que es como mira los
     * números una asesoría.
     */
    private static LocalDate quarterStart(LocalDate d) {
        int firstMonthOfQuarter = ((d.getMonthValue() - 1) / 3) * 3 + 1;
        return LocalDate.of(d.getYear(), firstMonthOfQuarter, 1);
    }
}
