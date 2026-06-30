package com.benjagest.ui.support;

/** Utilidades de texto sin estado. Extraido del God Object en UIR-5c. */
public final class Texts {

    private Texts() {}

    /** "" o blanco -> null; en otro caso, trim. */
    public static String blankToNullOrSelf(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** Primeros 8 caracteres de un id (para mostrar). */
    public static String shortId(String id) {
        if (id == null || id.length() < 8) {
            return id == null ? "" : id;
        }
        return id.substring(0, 8);
    }
}
