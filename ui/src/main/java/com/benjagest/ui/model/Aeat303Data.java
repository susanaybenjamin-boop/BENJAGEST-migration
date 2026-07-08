package com.benjagest.ui.model;

/**
 * MOD-PREFILL — Datos prefill del modelo 303 (IVA trimestral), calculados por
 * el backend desde las facturas VALIDADAS del trimestre. Bases de IVA
 * repercutido por tipo (4/10/21) y base/cuota de IVA soportado deducible. Las
 * cuotas repercutidas (base×tipo) y el resultado los calcula la UI. Campos
 * String para edición posterior.
 */
public class Aeat303Data {
    public String base4 = "0";
    public String base10 = "0";
    public String base21 = "0";
    public String baseSoportada = "0";
    public String cuotaSoportada = "0";
    // IVA-COMP: cuotas a compensar de periodos anteriores (casilla 110),
    // que el backend arrastra desde el saldo inicial + trimestres previos.
    public String compensacionPrevia = "0";
    // 303-FULL: modificación de bases y cuotas (casillas 14/15) — las
    // rectificativas del trimestre, con su signo. El backend las clasifica
    // aquí (no en el régimen general 01-09), como el 303 oficial.
    public String modBase = "0";
    public String modCuota = "0";
    // OPTYPE-2: soportado ruteado por tipo de operación desde la clasificación
    // fiscal de cada compra. Bienes de inversión (30/31), importaciones (32/33),
    // adq. intracom deducible (36/37) y su autorrepercusión en el devengado:
    // intracom (10/11) e inversión del sujeto pasivo (12/13).
    public String baseInv = "0";       // 30
    public String cuotaInv = "0";      // 31
    public String baseImport = "0";    // 32
    public String cuotaImport = "0";   // 33
    public String baseIntraDed = "0";  // 36
    public String cuotaIntraDed = "0"; // 37
    public String baseIntra = "0";     // 10 (devengado)
    public String cuotaIntra = "0";    // 11
    public String baseIsp = "0";       // 12 (devengado)
    public String cuotaIsp = "0";      // 13
}
