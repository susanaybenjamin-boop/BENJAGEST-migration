package com.benjagest.ui.support;

import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/**
 * Pequeño utility que aplica a un {@link TableView} dos atajos uniformes
 * que el usuario espera en CUALQUIER listado del proyecto:
 *
 * <ul>
 *   <li><b>Tecla Escape</b> → limpia la selección actual.</li>
 *   <li><b>Click izquierdo en zona vacía</b> (debajo de las filas o en una
 *       fila sin item) → limpia la selección actual.</li>
 * </ul>
 *
 * <p>Razón: cuando una fila está seleccionada, sus botones de "Editar /
 * Eliminar / Validar…" suelen estar habilitados. Si el usuario quiere
 * "salir" de esa selección (porque va a crear algo nuevo, o porque ya
 * actuó), espera poder hacerlo con Escape o haciendo click en hueco.
 * Antes, el item se quedaba siempre seleccionado hasta que el usuario
 * hacía Ctrl+click para deseleccionar — incómodo.
 *
 * <p>Uso: justo después de crear el TableView, llamar
 * {@code TableSelectionHelper.install(table);}. Es idempotente; llamarlo
 * varias veces no añade handlers duplicados.
 *
 * <p>Compatible con {@code SelectionMode.MULTIPLE}: limpia toda la
 * selección (no solo el item actual).
 *
 * <p>Notas de implementación:
 * <ul>
 *   <li>El handler de Escape se añade como filtro a nivel de la propia
 *       tabla — solo dispara cuando la tabla tiene foco, así no interfiere
 *       con otros Escape globales (cerrar diálogos, etc.).</li>
 *   <li>El handler de click distingue "fila vacía" mirando si la cadena
 *       de targets del MouseEvent contiene un {@link TableRow} con item.
 *       Si no hay item (clic debajo del último registro o tabla vacía),
 *       deselecciona.</li>
 *   <li>Marca la tabla con una propiedad "tsh-installed" para evitar
 *       doble instalación.</li>
 * </ul>
 */
public final class TableSelectionHelper {

    private static final String INSTALLED_KEY = "tsh-installed";

    private TableSelectionHelper() {}

    /** Instala los dos atajos sobre el TableView. Idempotente. */
    public static <T> void install(TableView<T> table) {
        if (table == null) return;
        if (Boolean.TRUE.equals(table.getProperties().get(INSTALLED_KEY))) return;
        table.getProperties().put(INSTALLED_KEY, Boolean.TRUE);

        // Escape → clearSelection. Filtro para tener prioridad sobre el
        // comportamiento por defecto del TableView (que ignora Escape).
        EventHandler<KeyEvent> escHandler = ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) {
                if (!table.getSelectionModel().isEmpty()) {
                    table.getSelectionModel().clearSelection();
                    ev.consume();
                }
            }
        };
        table.addEventFilter(KeyEvent.KEY_PRESSED, escHandler);

        // Click izquierdo en zona vacía → clearSelection. Usamos un filtro
        // para no interferir con la selección normal de filas: solo
        // limpiamos cuando el target NO es un TableRow con item.
        EventHandler<MouseEvent> clickHandler = ev -> {
            if (ev.getButton() != MouseButton.PRIMARY) return;
            if (clickedEmptyArea(ev)) {
                if (!table.getSelectionModel().isEmpty()) {
                    table.getSelectionModel().clearSelection();
                }
            }
        };
        table.addEventFilter(MouseEvent.MOUSE_CLICKED, clickHandler);
    }

    /**
     * ¿El target del evento es área vacía (sin item)?
     * Camina la cadena padre-padre buscando un {@link TableRow}. Si lo
     * encuentra con item → es una fila válida (no es zona vacía). Si no
     * encuentra ningún TableRow, o el TableRow no tiene item, sí es zona
     * vacía.
     */
    private static boolean clickedEmptyArea(MouseEvent ev) {
        Node node = ev.getPickResult().getIntersectedNode();
        while (node != null) {
            if (node instanceof TableRow<?> row) {
                return row.getItem() == null;
            }
            node = node.getParent();
        }
        return true; // sin TableRow en la cadena → zona vacía
    }

    /** Atajo para instalar varios a la vez. */
    @SafeVarargs
    public static <T> void installAll(TableView<T>... tables) {
        for (TableView<T> t : tables) install(t);
    }

    /**
     * Devuelve los elementos seleccionados como copia inmutable —
     * conveniencia para callers que iteran tras un click batch sin
     * preocuparse de mutaciones concurrentes.
     */
    public static <T> ObservableList<T> selectedCopy(TableView<T> table) {
        ObservableList<T> sel = table.getSelectionModel().getSelectedItems();
        return javafx.collections.FXCollections.observableArrayList(sel);
    }

    /**
     * Instala una única vez los dos atajos sobre <b>toda la Scene</b>:
     * cualquier TableView que se monte en cualquier momento dentro de
     * esta Scene (presentes y futuros) recibe el comportamiento
     * automáticamente, sin tener que llamar {@link #install(TableView)}
     * en cada sitio.
     *
     * <p>Funcionamiento: en lugar de hookear cada TableView, registramos
     * dos filtros en la Scene que buscan en la cadena de target el primer
     * TableView ancestro y le aplican el efecto. Eso cubre el 100% de los
     * listados sin modificar 38 sitios distintos del código.
     *
     * <p>Casos importantes:
     * <ul>
     *   <li>Escape consumido solo si la tabla tenía algo seleccionado.
     *       Si no, Escape sigue propagándose (cerrar diálogos, etc.).</li>
     *   <li>El click en zona vacía solo deselecciona — no impide editar.
     *       Si el click cae sobre una fila con item, la selección normal
     *       ocurre.</li>
     * </ul>
     */
    public static void attachToScene(javafx.scene.Scene scene) {
        if (scene == null) return;
        if (Boolean.TRUE.equals(scene.getProperties().get(INSTALLED_KEY))) return;
        scene.getProperties().put(INSTALLED_KEY, Boolean.TRUE);

        // Escape: si hay un TableView en la cadena focusOwner y tiene
        // selección, limpiamos. Si no hay selección, no consumimos: dejamos
        // que Escape siga propagándose (modales/diálogos).
        scene.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() != KeyCode.ESCAPE) return;
            Node focused = scene.getFocusOwner();
            TableView<?> table = findTableAncestor(focused);
            if (table != null && !table.getSelectionModel().isEmpty()) {
                table.getSelectionModel().clearSelection();
                ev.consume();
            }
        });

        // Click izquierdo: si cayó dentro de un TableView en zona vacía
        // (sin TableRow con item), deselecciona. NUNCA consumimos el
        // evento — solo limpiamos selección.
        scene.addEventFilter(MouseEvent.MOUSE_CLICKED, ev -> {
            if (ev.getButton() != MouseButton.PRIMARY) return;
            Node target = ev.getPickResult().getIntersectedNode();
            TableView<?> table = findTableAncestor(target);
            if (table == null) return;
            if (clickedEmptyArea(ev) && !table.getSelectionModel().isEmpty()) {
                table.getSelectionModel().clearSelection();
            }
        });
    }

    private static TableView<?> findTableAncestor(Node node) {
        while (node != null) {
            if (node instanceof TableView<?> tv) return tv;
            node = node.getParent();
        }
        return null;
    }
}
