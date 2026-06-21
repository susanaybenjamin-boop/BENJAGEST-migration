package com.benjagest.ui.model;

/**
 * AEAT-ED-3 — Una fila del modelo 190 (perceptor de retenciones IRPF), editable.
 * clave = "A" (rendimientos del trabajo) | "G" (actividades profesionales), fiel a
 * CONTENDO (nombre, NIF, clave, retribuciones íntegras, retenciones). Campos String.
 */
public class Aeat190Row {
    public String clave;
    public String nif;
    public String name;
    public String base;       // retribuciones íntegras
    public String retencion;

    public Aeat190Row() {
        this("A", "", "", "0", "0");
    }

    public Aeat190Row(String clave, String nif, String name, String base, String retencion) {
        this.clave = clave == null || clave.isBlank() ? "A" : clave;
        this.nif = nif == null ? "" : nif;
        this.name = name == null ? "" : name;
        this.base = base == null || base.isBlank() ? "0" : base.trim();
        this.retencion = retencion == null || retencion.isBlank() ? "0" : retencion.trim();
    }
}
