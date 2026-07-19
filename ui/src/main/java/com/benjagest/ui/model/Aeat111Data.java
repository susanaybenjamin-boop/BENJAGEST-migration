package com.benjagest.ui.model;

/**
 * LIQ-111-UI — Datos prefill del modelo 111 (retenciones IRPF trimestrales),
 * calculados por el backend desde las NÓMINAS del trimestre (I. Rendimientos
 * del trabajo, casillas 01/02/03) y las FACTURAS RECIBIDAS con retención de
 * profesionales (II. Actividades económicas, casillas 07/08/09). El total
 * (casilla 30, resultado a ingresar) lo trae el backend en {@code resultado}.
 * Campos String para edición posterior en el editor.
 */
public class Aeat111Data {
    // I. Rendimientos del trabajo (01/02/03)
    public String trabajoPerceptores = "0";
    public String trabajoBase = "0";
    public String trabajoRetencion = "0";
    // II. Rendimientos de actividades económicas (07/08/09)
    public String actividadesPerceptores = "0";
    public String actividadesBase = "0";
    public String actividadesRetencion = "0";
    // Casilla 30 (resultado a ingresar) = suma de retenciones.
    public String total = "0";
}
