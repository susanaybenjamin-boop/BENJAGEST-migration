package com.benjagest.backend.aeat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * SIM (2026-11-21) — qué régimen fiscal tiene un cliente.
 *
 * <p>La regla ya existía dentro de {@code SalesAndExpensesKpiService} como un
 * método privado que solo respondía sí/no al 130. El simulador necesita saber
 * <b>cuál</b> es el régimen, no solo si aplica el 130, porque el aviso que hay
 * que dar es distinto en cada caso. Se extrae aquí para que haya <b>una sola
 * definición</b>: tener la misma regla escrita en dos sitios es lo que acabó
 * dando tres cifras distintas de "facturado" en la app.
 *
 * <p>Las dos fuentes son {@code companies.legal_form} (V120) y
 * {@code client_advisory_config.tax_regime} (V119), las dos editables desde la
 * ficha del cliente.
 */
@Service
public class TaxRegimeResolver {

    /**
     * Qué le pasa al cliente cuando gasta más.
     */
    public enum Regime {
        /**
         * Autónomo en estimación directa: el 130 es el 20% del rendimiento, así
         * que un gasto deducible <b>sí</b> baja el pago. Es el caso para el que
         * el simulador tiene sentido completo.
         */
        ESTIMACION_DIRECTA,
        /**
         * Autónomo en módulos (estimación objetiva, modelo 131): el pago sale de
         * los módulos (personal, metros, potencia), <b>no de los gastos</b>.
         * Gastar más NO baja el 131 — solo el IVA, y ni eso siempre. El
         * simulador tiene que decirlo claramente o estaría engañando.
         */
        MODULOS,
        /**
         * Sociedad: no presenta 130 sino 202/200. El simulador solo puede
         * hablar del IVA.
         */
        SOCIEDAD,
        /**
         * Ficha a medio rellenar. Se trata como estimación directa (fail-open,
         * igual que hace la tarjeta del 130 del cuadro de mando) pero el
         * resultado lo marca para que la pantalla lo advierta: es una
         * suposición, no un dato.
         */
        DESCONOCIDO
    }

    private final JdbcTemplate jdbc;

    public TaxRegimeResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Regime resolve(String companyId) {
        String legalForm = jdbc.query(
                "SELECT legal_form FROM companies WHERE id = ?",
                rs -> rs.next() ? rs.getString("legal_form") : null, companyId);
        String taxRegime = jdbc.query(
                "SELECT tax_regime FROM client_advisory_config WHERE company_id = ?",
                rs -> rs.next() ? rs.getString("tax_regime") : null, companyId);
        return classify(legalForm, taxRegime);
    }

    /**
     * Parte PURA, para poder probarla sin BD.
     *
     * <p>Manda el régimen fiscal cuando está relleno, porque es el dato
     * específico; la forma jurídica solo decide cuando no hay régimen. Un
     * autónomo cuya ficha diga MODULOS está en módulos aunque la forma
     * jurídica diga AUTONOMO: no son datos contradictorios, son dos cosas
     * distintas.
     */
    public static Regime classify(String legalForm, String taxRegime) {
        boolean regimeSet = taxRegime != null && !taxRegime.isBlank();
        boolean formSet = legalForm != null && !legalForm.isBlank();

        if (regimeSet) {
            if ("MODULOS".equalsIgnoreCase(taxRegime)
                    || "ESTIMACION_OBJETIVA".equalsIgnoreCase(taxRegime)) {
                return Regime.MODULOS;
            }
            if ("SOCIEDADES".equalsIgnoreCase(taxRegime)
                    || "SOCIEDAD".equalsIgnoreCase(taxRegime)) {
                return Regime.SOCIEDAD;
            }
            if ("ESTIMACION_DIRECTA".equalsIgnoreCase(taxRegime)) {
                // Aun con regimen de estimacion directa, una SL no presenta 130.
                if (formSet && !"AUTONOMO".equalsIgnoreCase(legalForm)) return Regime.SOCIEDAD;
                return Regime.ESTIMACION_DIRECTA;
            }
        }
        if (formSet) {
            return "AUTONOMO".equalsIgnoreCase(legalForm)
                    ? Regime.ESTIMACION_DIRECTA : Regime.SOCIEDAD;
        }
        return Regime.DESCONOCIDO;
    }

    /** ¿Los gastos deducibles bajan el pago fraccionado de IRPF? */
    public static boolean expensesReduceIrpf(Regime regime) {
        return regime == Regime.ESTIMACION_DIRECTA || regime == Regime.DESCONOCIDO;
    }
}
