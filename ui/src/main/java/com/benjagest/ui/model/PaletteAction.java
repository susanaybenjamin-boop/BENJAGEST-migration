package com.benjagest.ui.model;

/** Accion de la paleta de comandos (Command Palette). Extraido en UIR-2. */
public record PaletteAction(String label, String icon, Runnable action) {
}
