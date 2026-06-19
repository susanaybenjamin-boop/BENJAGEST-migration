package com.benjagest.ui.layout;

import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ScrollPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Utility responsiva — módulo central de detección de pantalla.
 *
 * <p>Calcula el alto util REAL de la pantalla del dispositivo en cada
 * momento (visualBounds ya descuenta taskbar de Windows / menubar de
 * macOS) y expone helpers que envuelven contenido en ScrollPane con
 * un maxHeight dinámico, garantizando que los botones del pie de un
 * Dialog o de una pantalla nunca queden por debajo del borde inferior
 * de la ventana — ni en portátil 13" ni en monitor 4K.
 *
 * <h3>Por qué un módulo central y no un helper local</h3>
 *
 * <p>Los helpers anteriores (dialogScroll local con maxHeight=560 fijo)
 * funcionan en una resolución concreta pero fallan al cambiar de
 * dispositivo. Centralizar aquí:
 *
 * <ul>
 *   <li>asegura que cualquier UI nueva use la misma política;</li>
 *   <li>permite que en un portátil con pantalla 768px el cap sea más
 *     agresivo y en un monitor 1440p el cap permita ver formularios
 *     completos sin scroll;</li>
 *   <li>deja un sitio único para ajustar márgenes (chrome del Stage +
 *     button bar del Dialog) si JavaFX cambia de versión.</li>
 * </ul>
 *
 * <h3>API mínima</h3>
 *
 * <ul>
 *   <li>{@link #dialog(Node)} — envuelve el contenido de un Dialog en
 *     ScrollPane con maxHeight = (alto pantalla - chrome dialog). Los
 *     botones del DialogPane viven en su button bar y NO se envuelven
 *     en el scroll: quedan SIEMPRE visibles.</li>
 *   <li>{@link #screen(Node)} — envuelve el contenido principal de una
 *     pantalla / tab cuando no usa el helper tabLayout(). Sin
 *     maxHeight fijo: deja que crezca, pero garantiza scroll si excede.</li>
 *   <li>{@link #maxDialogContentHeight()} / {@link #maxScreenContentHeight()}
 *     — para usos avanzados (set manual sobre algún ScrollPane existente).</li>
 * </ul>
 *
 * <h3>Idempotencia</h3>
 *
 * <p>Envolver un ScrollPane dentro de otro ScrollPane es técnicamente
 * posible pero crea dos barras y consume layout. Para evitarlo, los
 * métodos públicos detectan si el contenido ya es un ScrollPane y lo
 * devuelven sin envolver.
 *
 * <h3>Detección de pantalla</h3>
 *
 * <p>Se usa {@link Screen#getPrimary()}; si el usuario mueve la
 * ventana a otra pantalla, los maxHeight calculados al construir el
 * diálogo siguen vigentes durante esa sesión. Para un comportamiento
 * verdaderamente reactivo habría que escuchar el cambio de Stage entre
 * pantallas — slice futuro si surge necesidad.
 */
public final class ResponsiveLayout {

    private ResponsiveLayout() {
    }

    /**
     * Margen reservado para el chrome de la ventana del diálogo
     * (barra de título del Stage + button bar del DialogPane +
     * paddings). Empírico: suficiente en JavaFX 21 sobre Windows.
     */
    private static final double DIALOG_CHROME_RESERVE = 180.0;

    /**
     * Margen reservado para el chrome de la pantalla principal
     * (header de la app + sidebar + márgenes externos).
     */
    private static final double SCREEN_CHROME_RESERVE = 160.0;

    /** Fallback mínimo si Screen.getPrimary() devuelve algo absurdo. */
    private static final double MIN_USABLE_HEIGHT = 360.0;

    /**
     * Alto util disponible para el contenido (scrolleable) de un diálogo
     * en la pantalla actual. Garantiza al menos MIN_USABLE_HEIGHT
     * para no quedarnos sin nada visible si la detección falla.
     */
    public static double maxDialogContentHeight() {
        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        return Math.max(MIN_USABLE_HEIGHT, vb.getHeight() - DIALOG_CHROME_RESERVE);
    }

    /**
     * Alto util disponible para el contenido de una pantalla / tab.
     */
    public static double maxScreenContentHeight() {
        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        return Math.max(MIN_USABLE_HEIGHT + 40, vb.getHeight() - SCREEN_CHROME_RESERVE);
    }

    /**
     * Envuelve el contenido de un Dialog en un ScrollPane con maxHeight
     * dinámico. Idempotente: si {@code content} ya es un ScrollPane lo
     * devuelve tal cual.
     */
    public static Node dialog(Node content) {
        if (content instanceof ScrollPane existing) {
            // Ya estaba envuelto — solo aplicamos el cap dinámico para
            // que también este funcione en portatil pequeno.
            existing.setMaxHeight(maxDialogContentHeight());
            existing.setFitToWidth(true);
            return existing;
        }
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        double maxH = maxDialogContentHeight();
        sp.setMaxHeight(maxH);
        sp.setPrefViewportHeight(maxH);
        sp.getStyleClass().add("dialog-scroll");
        return sp;
    }

    /**
     * Envuelve una pantalla / tab en un ScrollPane. Sin maxHeight
     * fijo: el ScrollPane absorbe el espacio que le dé el padre y
     * activa el scrollbar SOLO si el contenido excede. Idempotente.
     */
    public static Node screen(Node content) {
        if (content instanceof ScrollPane existing) {
            existing.setFitToWidth(true);
            return existing;
        }
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        // FIT-TO-HEIGHT: el contenido llena el alto del viewport (no toma su
        // altura "natural"), de modo que VBox.setVgrow(tabla, ALWAYS) y los
        // SplitPane funcionan: las tablas hacen scroll INTERNO y los totales/
        // pies/paneles inferiores quedan SIEMPRE visibles, sin scroll global.
        // El scrollbar exterior solo aparece si el contenido MÍNIMO excede el
        // viewport (ventanas muy pequeñas). Antes faltaba esta línea y por eso
        // había que bajar mucho para ver datos del fondo (Mayor, Sumas, Bancos…).
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.getStyleClass().add("screen-scroll");
        return sp;
    }

    /**
     * Configura un Dialog para que NUNCA exceda el alto de pantalla:
     *
     *   1) Envuelve {@code content} en un ScrollPane con maxHeight
     *      dinámico (igual que {@link #dialog(Node)}).
     *   2) Aplica {@code dialogPane.setMaxHeight()} = alto pantalla
     *      menos chrome del Stage (~80px titlebar + bordes). Esto
     *      es lo que JavaFX respeta al medir el contenido.
     *   3) Cuando el Dialog se muestra, captura su Stage y le aplica
     *      {@code setMaxHeight()} igual al alto de pantalla, además
     *      de centrar verticalmente. Esto cubre el caso en el que
     *      JavaFX ignora el maxHeight del DialogPane (ocurre con
     *      DialogPane sin layout explícito y prefHeight calculado
     *      mayor que la pantalla).
     *   4) Hace el Dialog redimensionable, por si el usuario quiere
     *      cambiar tamaño manualmente.
     *
     * Llamar a este método EN LUGAR DE {@code dialog.getDialogPane()
     * .setContent(X)} cubre los tres puntos en una sola línea.
     *
     * Tras esto los botones del button bar (Aceptar / Cancelar /
     * Guardar) están SIEMPRE en pantalla, incluso si el contenido
     * tiene 50 campos, porque el ScrollPane interno absorbe el
     * exceso.
     */
    public static void installDialog(Dialog<?> dialog, Node content) {
        installDialog(dialog, content, true);
    }

    /**
     * Variante con control sobre el wrap en ScrollPane. Cuando el
     * contenido ya gestiona su propio scroll (por ejemplo un SplitPane
     * con visor PDF + formulario), {@code wrapInScroll=false} evita el
     * "doble scroll" en que el usuario tiene que mover dos barras
     * para llegar al final.
     */
    public static void installDialog(Dialog<?> dialog, Node content,
                                       boolean wrapInScroll) {
        DialogPane pane = dialog.getDialogPane();
        Rectangle2D vb = Screen.getPrimary().getVisualBounds();

        if (!wrapInScroll) {
            // Sin envoltorio externo: confiamos en que el contenido
            // tiene sus propias zonas scrollables. Solo aplicamos el
            // cap de altura al DialogPane + Stage.
            pane.setContent(content);
            double paneMaxHRaw = Math.max(MIN_USABLE_HEIGHT + 60,
                    vb.getHeight() - 80);
            pane.setMaxHeight(paneMaxHRaw);
            dialog.setResizable(true);
            installStageCap(dialog, vb);
            return;
        }

        // 1) ScrollPane interno con maxHeight dinámico.
        ScrollPane sp;
        if (content instanceof ScrollPane existing) {
            sp = existing;
            sp.setFitToWidth(true);
            sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        } else {
            sp = new ScrollPane(content);
            sp.setFitToWidth(true);
            sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            sp.getStyleClass().add("dialog-scroll");
        }
        // El ScrollPane tiene como cap el alto util menos el chrome
        // del button bar y un margen extra para el padding interno
        // del DialogPane.
        double scrollMaxH = Math.max(MIN_USABLE_HEIGHT, vb.getHeight() - 200);
        sp.setMaxHeight(scrollMaxH);
        sp.setPrefViewportHeight(scrollMaxH);
        pane.setContent(sp);

        // 2) Cap del DialogPane: alto pantalla menos titlebar Stage.
        double paneMaxH = Math.max(MIN_USABLE_HEIGHT + 60, vb.getHeight() - 80);
        pane.setMaxHeight(paneMaxH);

        dialog.setResizable(true);
        installStageCap(dialog, vb);
    }

    /**
     * Cap del Stage cuando aparezca + centrado vertical. Cierra el caso
     * en que el Stage hereda prefHeight calculado del contenido y se
     * salta el cap del DialogPane tras un layout pass.
     */
    private static void installStageCap(Dialog<?> dialog, Rectangle2D vb) {
        DialogPane pane = dialog.getDialogPane();
        dialog.setOnShown(ev -> {
            Scene scene = pane.getScene();
            if (scene == null) return;
            Window w = scene.getWindow();
            if (!(w instanceof Stage stage)) return;
            stage.setMaxHeight(vb.getHeight());
            if (stage.getHeight() > vb.getHeight()) {
                stage.setHeight(vb.getHeight() - 10);
            }
            double freeSpace = vb.getHeight() - stage.getHeight();
            if (freeSpace > 0) {
                stage.setY(vb.getMinY() + freeSpace / 2);
            } else {
                stage.setY(vb.getMinY());
            }
        });
    }
}
