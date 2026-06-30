package com.benjagest.ui.support;

import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

/** Constructores de UI sin estado reutilizables por las pantallas. Extraido en UIR-5. */
public final class UiBuilders {

    private UiBuilders() {}

    /** Label con una clase CSS aplicada. */
    public static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    /** Envuelve un VBox en el ScrollPane estandar de contenido (fit-to-width). */
    public static ScrollPane scroll(VBox content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.getStyleClass().add("content-scroll");
        scroll.setFitToWidth(true);
        return scroll;
    }
}
