package com.benjagest.ui.model;

/** Entrada de navegacion del sidebar. Extraido del God Object en UIR-2. */
public record ModuleLink(String id, String title, String icon, boolean advisoryOnly) {
    public ModuleLink(String id, String title, String icon) {
        this(id, title, icon, false);
    }
}
