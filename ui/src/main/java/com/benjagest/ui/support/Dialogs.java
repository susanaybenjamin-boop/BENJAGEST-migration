package com.benjagest.ui.support;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;

/** Diálogos y avisos reutilizables (error/info/toast). Extraido del God Object en UIR-3.
 *  El mensaje de error ya debe venir humanizado por el caller (frontera en
 *  BenjagestUiApplication.showError). */
public final class Dialogs {

    private Dialogs() {}

    /** Alerta de error modal. {@code message} ya humanizado por el caller. */
    public static void error(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
            alert.setTitle("BENJAGEST");
            alert.setHeaderText(title);
            alert.showAndWait();
        });
    }

    /** Alerta informativa modal. */
    public static void info(String title, String body) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, body);
        a.setHeaderText(title);
        a.showAndWait();
    }

    /**
     * Globo NO modal (toast) arriba-centro de la ventana indicada; se desvanece
     * solo a los ~3 s. No bloquea el flujo (avisos del tipo "te falta un campo").
     */
    public static void toast(javafx.stage.Window owner, String message) {
        if (owner == null || message == null) return;
        Label label = new Label(message);
        label.getStyleClass().add("toast");
        label.setWrapText(true);
        label.setMaxWidth(360);

        javafx.stage.Popup popup = new javafx.stage.Popup();
        popup.setAutoFix(true);
        popup.getContent().add(label);
        popup.show(owner);
        Platform.runLater(() -> {
            popup.setX(owner.getX() + (owner.getWidth() - label.getWidth()) / 2);
            popup.setY(owner.getY() + 72);
        });

        javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(
                javafx.util.Duration.millis(450), label);
        fade.setDelay(javafx.util.Duration.seconds(2.4));
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> popup.hide());
        fade.play();
    }

    /** Toast sobre la ventana activa (sin pasarla explicitamente). */
    public static void toast(String message) {
        javafx.stage.Window w = javafx.stage.Window.getWindows().stream()
                .filter(javafx.stage.Window::isFocused).findFirst()
                .orElseGet(() -> javafx.stage.Window.getWindows().stream()
                        .filter(javafx.stage.Window::isShowing).findFirst().orElse(null));
        toast(w, message);
    }
}
