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
}
