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
}
