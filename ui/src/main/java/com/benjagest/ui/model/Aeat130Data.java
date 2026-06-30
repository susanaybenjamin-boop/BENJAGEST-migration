package com.benjagest.ui.model;

/**
 * MOD-PREFILL — Datos prefill del modelo 130 (IRPF pago fraccionado, estimación
 * directa), calculados por el backend: ingresos y gastos acumulados del año
 * hasta el trimestre (de facturas VALIDADAS), retenciones soportadas y pagos
 * fraccionados de trimestres anteriores. Campos String para edición posterior.
 */
public class Aeat130Data {
    public String ingresos = "0";
    public String gastos = "0";
    public String retenciones = "0";
    public String pagosPrevios = "0";
}
