package com.benjagest.ui.support;

import javafx.scene.Node;
import javafx.scene.control.Label;
import org.kordamp.ikonli.javafx.FontIcon;

/** Helper de iconos (FontAwesome via Ikonli). Extraido del God Object en UIR-3. */
public final class Icons {

    private Icons() {}

    /** Crea un nodo de icono a partir de un literal Ikonli (p. ej. "fas-home").
     *  Si el literal no existe, cae a "fas-circle"; si tampoco, a un Label vacio. */
    public static Node icon(String literal) {
        FontIcon icon = new FontIcon();
        try {
            icon.setIconLiteral(literal);
        } catch (RuntimeException ignored) {
            try {
                icon.setIconLiteral("fas-circle");
            } catch (RuntimeException ignoredAgain) {
                Label fallback = new Label("");
                fallback.getStyleClass().add("font-icon");
                return fallback;
            }
        }
        icon.getStyleClass().add("font-icon");
        return icon;
    }
}
