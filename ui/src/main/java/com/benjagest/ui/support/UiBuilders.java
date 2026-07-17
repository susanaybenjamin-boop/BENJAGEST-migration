package com.benjagest.ui.support;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
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

    /** Contenedor estandar de contenido de modulo (VBox con clase "content"). */
    public static VBox content() {
        VBox content = new VBox(22);
        content.getStyleClass().add("content");
        return content;
    }

    /** Burbuja con un icono (StackPane) y las clases CSS indicadas. */
    public static StackPane iconBubble(String iconLiteral, String... styleClasses) {
        StackPane bubble = new StackPane(Icons.icon(iconLiteral));
        bubble.getStyleClass().addAll(styleClasses);
        return bubble;
    }

    /**
     * Barra de acciones que ENVUELVE (FlowPane) en vez de hacer overflow
     * horizontal sin scroll. Si los botones no caben en el ancho de la ventana
     * (portatil), pasan a la linea siguiente y se ven todos. Fija
     * minWidth=USE_PREF_SIZE en cada control para que el texto salga entero (sin
     * "..."). Reutilizable en cualquier pantalla con muchos botones de accion.
     * Espejo del helper homonimo de BenjagestUiApplication / BillingInvoicesScreen.
     */
    public static FlowPane actionFlow(Node... children) {
        FlowPane fp = new FlowPane(6, 6, children);
        fp.getStyleClass().add("settings-actions");
        for (Node n : children) {
            if (n instanceof Region r) r.setMinWidth(Region.USE_PREF_SIZE);
        }
        return fp;
    }

    /** Cabecera de seccion: titulo + subtitulo a la izquierda, con spacer. */
    public static HBox sectionHeader(String title, String subtitle) {
        Label titleLabel = label(title, "section-title");
        Label subtitleLabel = label(subtitle, "section-subtitle");
        VBox copy = new VBox(2, titleLabel, subtitleLabel);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, copy, spacer);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }
}
