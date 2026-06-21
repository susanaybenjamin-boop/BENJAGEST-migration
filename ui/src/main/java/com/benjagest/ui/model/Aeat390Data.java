package com.benjagest.ui.model;

/**
 * AEAT-ED-2 — Datos editables del modelo 390 (resumen anual IVA), fiel a CONTENDO:
 * IVA devengado y deducible por tipo (4/10/21), operaciones exentas e
 * intracomunitarias y compensaciones. Las cuotas (base×tipo), totales, resultado,
 * resultado final y volumen se calculan en la UI. Campos String para edición.
 */
public class Aeat390Data {
    public String baseDev4 = "0";
    public String baseDev10 = "0";
    public String baseDev21 = "0";
    public String baseDed4 = "0";
    public String baseDed10 = "0";
    public String baseDed21 = "0";
    public String exentas = "0";
    public String intracom = "0";
    public String compensaciones = "0";
}
