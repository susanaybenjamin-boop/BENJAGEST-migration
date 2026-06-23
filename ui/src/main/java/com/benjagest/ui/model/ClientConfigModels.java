package com.benjagest.ui.model;

import java.math.BigDecimal;

/** Modelos del tab "Configuración" de la ficha del cliente (CLIENT-CONFIG). */
public final class ClientConfigModels {
    private ClientConfigModels() {}

    /** Cifra manual de un periodo (quarter 0 = anual, 1..4 = trimestre). */
    public record ManualFinancialEntry(
            String id, int periodYear, int periodQuarter,
            BigDecimal income, BigDecimal expenses, BigDecimal netResult, String notes) {}

    /** Config interna del cliente gestionada por la asesoría. */
    public record AdvisoryConfigEntry(
            String fiscalPeriod, String taxRegime,
            String contactChannel, String contactValue, String internalNotes,
            String legalForm, boolean provisionExtraPay, boolean reflejoAutoEnabled) {}
}
