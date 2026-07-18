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

    // DOBLE-PANTALLA (MULTIMON) — ventana principal de la app. La fija
    // BenjagestUiApplication.start(). Sirve de owner por defecto para que los
    // diálogos aparezcan en el MISMO monitor que la app y no siempre en el
    // primario. Sin owner, JavaFX centra todo Alert/Dialog en la pantalla
    // primaria (bug reportado: el editor abría en el monitor principal aunque
    // la app estuviera en el segundo).
    private static javafx.stage.Window primary;

    /** La fija {@code start(Stage)} una vez al arrancar. */
    public static void setPrimaryWindow(javafx.stage.Window w) {
        primary = w;
    }

    /**
     * Ventana sobre la que anclar un diálogo nuevo: la ENFOCADA (donde está el
     * usuario ahora mismo, en el monitor que sea); si no, la principal; si no,
     * cualquiera visible. Resolver en el momento de mostrar → cae en el monitor
     * correcto aunque la app se haya movido de pantalla.
     */
    public static javafx.stage.Window activeOwner() {
        java.util.List<javafx.stage.Window> ws = javafx.stage.Window.getWindows();
        javafx.stage.Window focused = ws.stream()
                .filter(w -> w instanceof javafx.stage.Stage)
                .filter(javafx.stage.Window::isFocused)
                .findFirst().orElse(null);
        if (focused != null) return focused;
        if (primary != null && primary.isShowing()) return primary;
        return ws.stream()
                .filter(w -> w instanceof javafx.stage.Stage)
                .filter(javafx.stage.Window::isShowing)
                .findFirst().orElse(null);
    }

    /** Ancla un Dialog (Alert/ChoiceDialog/TextInputDialog) a la ventana activa. */
    public static void own(javafx.scene.control.Dialog<?> dialog) {
        if (dialog != null && dialog.getOwner() == null) {
            javafx.stage.Window o = activeOwner();
            if (o != null) dialog.initOwner(o);
        }
    }

    /**
     * Ancla un Stage hijo a la ventana activa y lo centra sobre ella (mismo
     * monitor). Llamar ANTES de {@code show()}. No pisa una posición si el Stage
     * ya trae owner (lo dejamos como está).
     */
    public static void ownAndCenter(javafx.stage.Stage child) {
        if (child == null || child.getOwner() != null) return;
        javafx.stage.Window o = activeOwner();
        if (o == null) return;
        try {
            child.initOwner(o);
        } catch (IllegalStateException alreadyShown) {
            return; // ya visible: no forzamos nada
        }
        child.setOnShown(ev -> {
            double x = o.getX() + Math.max(0, (o.getWidth() - child.getWidth()) / 2);
            double y = o.getY() + Math.max(0, (o.getHeight() - child.getHeight()) / 2);
            child.setX(x);
            child.setY(y);
        });
    }

    /**
     * MULTIMON (red de seguridad GLOBAL) — Instala UNA vez un vigilante sobre las
     * ventanas de la app: cuando aparece cualquier ventana de diálogo en un
     * monitor DISTINTO al de la app, la recoloca centrada sobre la pantalla de la
     * app. Cubre de golpe TODOS los diálogos que abren sin owner (validar,
     * reclasificar, editores, avisos…) sin tener que tocarlos uno a uno, y también
     * los que se creen en el futuro.
     *
     * <p>Solo actúa sobre {@link javafx.stage.Stage} (Alert/Dialog/Stage propio);
     * NO sobre los popups tipo desplegable de combo, tooltip o menú contextual
     * (son {@link javafx.stage.PopupWindow}, que van anclados a su control y NO se
     * deben mover). Y solo recoloca si la ventana cayó en OTRA pantalla — si ya
     * está en la de la app, no la toca (sin parpadeo en monitor único).
     */
    public static void installGlobalPositioner() {
        javafx.stage.Window.getWindows().addListener(
                (javafx.collections.ListChangeListener<javafx.stage.Window>) change -> {
            while (change.next()) {
                for (javafx.stage.Window w : change.getAddedSubList()) {
                    if (!(w instanceof javafx.stage.Stage stage)) continue; // no popups
                    if (stage == primary) continue;                          // no la ventana principal
                    stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWN,
                            ev -> rescueToAppScreen(stage));
                    // Red de seguridad: si WINDOW_SHOWN ya se disparó ANTES de que
                    // registráramos el handler (p.ej. el showAndWait del bloqueo/
                    // salvapantallas, que muestra la ventana en el acto), el evento
                    // no nos llega. Reintentamos en el próximo pulso, cuando la
                    // ventana ya está mostrada y posicionada.
                    Platform.runLater(() -> {
                        if (stage.isShowing()) rescueToAppScreen(stage);
                    });
                }
            }
        });
    }

    /** Si {@code stage} aparecio en otra pantalla que la de la app, lo centra en la de la app. */
    private static void rescueToAppScreen(javafx.stage.Stage stage) {
        javafx.stage.Window ref = stage.getOwner() != null
                ? stage.getOwner()
                : (primary != null && primary.isShowing() ? primary : null);
        if (ref == null) return;
        javafx.geometry.Rectangle2D appVb = screenBoundsOf(ref);
        javafx.geometry.Rectangle2D curVb = screenBoundsOf(stage);
        if (appVb == null || curVb == null || appVb.equals(curVb)) return; // ya está bien
        double w = stage.getWidth() > 0 ? stage.getWidth() : 400;
        double h = stage.getHeight() > 0 ? stage.getHeight() : 300;
        stage.setX(appVb.getMinX() + Math.max(0, (appVb.getWidth() - w) / 2));
        stage.setY(appVb.getMinY() + Math.max(0, (appVb.getHeight() - h) / 2));
    }

    /** visualBounds de la pantalla que CONTIENE el centro de la ventana (null si sin posición). */
    private static javafx.geometry.Rectangle2D screenBoundsOf(javafx.stage.Window w) {
        if (w == null || Double.isNaN(w.getX()) || Double.isNaN(w.getY())) return null;
        double cx = w.getX() + Math.max(1, w.getWidth()) / 2;
        double cy = w.getY() + Math.max(1, w.getHeight()) / 2;
        // bounds.contains directo (getScreensForRectangle mapea mal con escalados distintos).
        for (javafx.stage.Screen s : javafx.stage.Screen.getScreens()) {
            if (s.getBounds().contains(cx, cy)) return s.getVisualBounds();
        }
        return javafx.stage.Screen.getPrimary().getVisualBounds();
    }

    /** Alerta de error modal. {@code message} ya humanizado por el caller. */
    public static void error(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
            alert.setTitle("BENJAGEST");
            alert.setHeaderText(title);
            own(alert); // DOBLE-PANTALLA: aparece sobre la app, no en el primario
            alert.showAndWait();
        });
    }

    /** Alerta informativa modal. */
    public static void info(String title, String body) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, body);
        a.setHeaderText(title);
        own(a); // DOBLE-PANTALLA
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
