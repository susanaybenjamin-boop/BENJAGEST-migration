package com.benjagest.ui.model;

/**
 * AEAT-ED-1 — Una fila del modelo 347 (operación con un tercero), editable en la UI.
 * Campos mutables (String) para edición directa en la TableView; los importes se
 * parsean al guardar. clave = "A" (compras/proveedor) | "B" (ventas/cliente),
 * fiel al modelo de CONTENDO (tipo cliente/proveedor + NIF + trimestres + total).
 */
public class Aeat347Row {
    public String clave;
    public String nif;
    public String name;
    public String q1;
    public String q2;
    public String q3;
    public String q4;

    public Aeat347Row() {
        this("B", "", "", "0", "0", "0", "0");
    }

    public Aeat347Row(String clave, String nif, String name,
                      String q1, String q2, String q3, String q4) {
        this.clave = clave == null || clave.isBlank() ? "B" : clave;
        this.nif = nif == null ? "" : nif;
        this.name = name == null ? "" : name;
        this.q1 = blankZero(q1);
        this.q2 = blankZero(q2);
        this.q3 = blankZero(q3);
        this.q4 = blankZero(q4);
    }

    private static String blankZero(String v) {
        return v == null || v.isBlank() ? "0" : v.trim();
    }
}
