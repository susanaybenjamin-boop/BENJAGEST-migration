package com.benjagest.ui.support;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.util.Callback;

/**
 * Factories de TableCell editables que aplican las convenciones de
 * UX que Benjamin pidió uniformemente (workcal CAL-IMPORT 2026-06-09):
 *
 * <ul>
 *   <li><b>Commit-on-blur</b>: cuando la celda pierde el foco (o se
 *       pulsa Tab para navegar) el valor se guarda automáticamente.
 *       No hace falta pulsar Enter.</li>
 *   <li><b>Tab navega entre columnas</b>: Tab confirma y mueve a la
 *       siguiente celda editable de la misma fila; Shift+Tab al revés.</li>
 *   <li><b>Escape cancela edición</b>: vuelve al valor anterior y
 *       cierra el editor sin guardar.</li>
 *   <li><b>DatePicker integrado</b>: para fechas, abre un calendario
 *       desplegable; al elegir un día se commit-ea automáticamente sin
 *       que el usuario pulse Enter.</li>
 * </ul>
 *
 * <p>Estos helpers son drop-in replacement de los TableCell estándar
 * de JavaFX ({@code TextFieldTableCell.forTableColumn()} y similar).
 */
public final class EditableCells {

    private EditableCells() {}

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Lista de formatos aceptados al parsear texto tecleado en un
     * DatePicker. El orden importa — probamos primero ISO (formato
     * canónico del proyecto) y luego patrones humanos comunes en
     * España.
     *
     * <p>El converter por defecto de JavaFX para {@code es_ES} usa
     * {@code FormatStyle.SHORT} que es {@code "dd/MM/yy"} (año de 2
     * dígitos). Si el usuario teclea "31/12/2026" (año de 4 dígitos)
     * el converter falla — por eso aquí aceptamos AMBOS explícitamente.
     * Bug Benjamin 2026-06-09.
     */
    private static final DateTimeFormatter[] DATE_PARSERS = {
            ISO,                                          // 2026-12-31
            DateTimeFormatter.ofPattern("d/M/yyyy"),      // 3/6/2026, 31/12/2026
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),    // 03/06/2026
            DateTimeFormatter.ofPattern("d-M-yyyy"),      // 3-6-2026
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),    // 03-06-2026
            DateTimeFormatter.ofPattern("d/M/yy"),        // 3/6/26
            DateTimeFormatter.ofPattern("d.M.yyyy"),      // 3.6.2026 (DE/AT)
    };

    /**
     * Parsea texto a {@link LocalDate} aceptando varios formatos
     * comunes. Devuelve null si ninguno encaja.
     */
    public static LocalDate parseFlexibleDate(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        for (DateTimeFormatter f : DATE_PARSERS) {
            try { return LocalDate.parse(t, f); }
            catch (DateTimeParseException ignored) { /* try next */ }
        }
        return null;
    }

    /**
     * Sustituye el converter por defecto de un {@link DatePicker} por
     * uno que acepta varios formatos comunes (ISO, dd/MM/yyyy, etc.).
     *
     * <p>El converter por defecto de JavaFX para locale es_ES usa
     * {@code FormatStyle.SHORT} = {@code dd/MM/yy} (año de 2 dígitos).
     * Si el usuario teclea "31/12/2026" el converter falla → el valor
     * queda null al perder el foco y la entrada se pierde silenciosa.
     *
     * <p>Aplicar este helper directamente tras construir el DatePicker:
     * <pre>
     *   DatePicker picker = new DatePicker();
     *   EditableCells.installFlexibleConverter(picker);
     * </pre>
     *
     * <p>El toString sigue usando el formato del locale (lo que ve el
     * usuario al hacer click en el calendario), pero al parsear lo
     * tecleado acepta todos los formatos de {@link #parseFlexibleDate}.
     */
    public static void installFlexibleConverter(DatePicker picker) {
        if (picker == null) return;
        javafx.util.StringConverter<LocalDate> original = picker.getConverter();
        picker.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(LocalDate v) {
                return original == null ? (v == null ? "" : v.toString())
                        : original.toString(v);
            }
            @Override
            public LocalDate fromString(String s) {
                LocalDate v = parseFlexibleDate(s);
                if (v != null) return v;
                // Fallback al converter original (formato del locale).
                if (original != null) {
                    try { return original.fromString(s); }
                    catch (Exception ignored) { /* devuelve null */ }
                }
                return null;
            }
        });
    }

    /**
     * Fuerza el commit de cualquier {@link DatePicker} dentro de la
     * tabla que tenga texto tecleado en su editor pero sin confirmar.
     *
     * <p>Necesario antes de leer los valores de las filas en un botón
     * de acción ({@code "Volcar al calendario"}) — los focus listeners
     * de cada celda pueden no haber disparado todavía cuando el usuario
     * pulsa el botón directamente desde un editor abierto.
     */
    public static void commitPendingDatePickerEdits(TableView<?> table) {
        if (table == null) return;
        for (Node n : table.lookupAll(".date-picker")) {
            if (n instanceof DatePicker dp) {
                String typed = dp.getEditor() == null ? null : dp.getEditor().getText();
                if (typed == null || typed.isBlank()) continue;
                LocalDate parsed = parseFlexibleDate(typed);
                if (parsed == null && dp.getConverter() != null) {
                    try { parsed = dp.getConverter().fromString(typed.trim()); }
                    catch (Exception ignored) { /* ignore */ }
                }
                if (parsed != null && !parsed.equals(dp.getValue())) {
                    // Setear valueProperty dispara el listener de la
                    // celda → commitEdit → onEditCommit → row.dateValue.
                    dp.setValue(parsed);
                }
            }
        }
    }

    /**
     * Factoría de celda con TextField que commit-ea al perder foco
     * y permite Tab para navegar. Equivalente a
     * {@code TextFieldTableCell.forTableColumn()} pero sin requerir
     * Enter explícito.
     */
    public static <S> Callback<TableColumn<S, String>, TableCell<S, String>>
            textFieldCommitOnBlur() {
        return col -> new TextFieldBlurCell<>();
    }

    /**
     * Factoría de celda con DatePicker. Al hacer click en una fila/día
     * del calendario, commit-ea automáticamente. También acepta
     * teclear la fecha en formato ISO {@code yyyy-MM-dd}.
     */
    public static <S> Callback<TableColumn<S, LocalDate>, TableCell<S, LocalDate>>
            datePicker() {
        return col -> new DatePickerCell<>();
    }

    // ============================================================
    //  TextField cell commit-on-blur
    // ============================================================

    private static class TextFieldBlurCell<S> extends TableCell<S, String> {
        private TextField field;

        @Override
        public void startEdit() {
            if (!isEditable() || getTableView() == null
                    || !getTableView().isEditable()
                    || !getTableColumn().isEditable()) {
                return;
            }
            super.startEdit();
            if (field == null) buildField();
            field.setText(getItem() == null ? "" : getItem());
            setText(null);
            setGraphic(field);
            field.selectAll();
            field.requestFocus();
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            setText(getItem() == null ? "" : getItem());
            setGraphic(null);
        }

        @Override
        protected void updateItem(String v, boolean empty) {
            super.updateItem(v, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (isEditing()) {
                if (field != null) field.setText(v == null ? "" : v);
                setText(null);
                setGraphic(field);
            } else {
                setText(v == null ? "" : v);
                setGraphic(null);
            }
        }

        private void buildField() {
            field = new TextField();
            field.setOnAction(ev -> commitEdit(field.getText()));
            field.focusedProperty().addListener((obs, was, now) -> {
                if (was && !now && isEditing()) {
                    commitEdit(field.getText());
                }
            });
            field.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
                if (ev.getCode() == KeyCode.ESCAPE) {
                    cancelEdit();
                    ev.consume();
                } else if (ev.getCode() == KeyCode.TAB) {
                    commitEdit(field.getText());
                    // No consumimos para que JavaFX mueva foco al
                    // siguiente nodo focusable (TableView gestiona
                    // navegación entre celdas con Tab si la fila
                    // está en modo edición).
                }
            });
        }
    }

    // ============================================================
    //  DatePicker cell — commit-on-select
    // ============================================================

    private static class DatePickerCell<S> extends TableCell<S, LocalDate> {
        private DatePicker picker;
        private ChangeListener<LocalDate> valueListener;

        @Override
        public void startEdit() {
            if (!isEditable() || getTableView() == null
                    || !getTableView().isEditable()
                    || !getTableColumn().isEditable()) {
                return;
            }
            super.startEdit();
            if (picker == null) buildPicker();
            picker.setValue(getItem());
            setText(null);
            setGraphic(picker);
            picker.requestFocus();
            // Abrir el popup automáticamente — lo más natural para que
            // el usuario haga click en un día.
            picker.show();
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            setText(getItem() == null ? "" : ISO.format(getItem()));
            setGraphic(null);
        }

        @Override
        protected void updateItem(LocalDate v, boolean empty) {
            super.updateItem(v, empty);
            if (empty || v == null) {
                setText(empty ? null : "");
                setGraphic(null);
                return;
            }
            if (isEditing()) {
                if (picker != null) picker.setValue(v);
                setText(null);
                setGraphic(picker);
            } else {
                setText(ISO.format(v));
                setGraphic(null);
            }
        }

        private void buildPicker() {
            picker = new DatePicker();
            // Commit cuando el usuario elige un día (click en popup).
            valueListener = (obs, was, now) -> {
                if (isEditing() && now != null && !now.equals(was)) {
                    commitEdit(now);
                }
            };
            picker.valueProperty().addListener(valueListener);
            // Commit por blur si el usuario tecleó manualmente sin Enter.
            // IMPORTANTE: usamos el converter del propio DatePicker para
            // parsear, así soporta el formato del locale (dd/MM/yyyy en
            // España) + ISO como fallback. Antes solo aceptaba ISO y
            // perdíamos las fechas tecleadas en formato europeo (bug
            // Benjamin 2026-06-09: 4 filas se quedaban en 2026-01-01).
            picker.focusedProperty().addListener((obs, was, now) -> {
                if (was && !now && isEditing()) {
                    LocalDate parsed = parseTypedDate(picker.getEditor().getText());
                    if (parsed != null) commitEdit(parsed);
                    else cancelEdit();
                }
            });
            picker.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
                if (ev.getCode() == KeyCode.ESCAPE) {
                    cancelEdit();
                    ev.consume();
                } else if (ev.getCode() == KeyCode.TAB
                        || ev.getCode() == KeyCode.ENTER) {
                    LocalDate parsed = parseTypedDate(picker.getEditor().getText());
                    if (parsed != null) commitEdit(parsed);
                    else cancelEdit();
                }
            });
        }

        /**
         * Parsea texto tecleado en el DatePicker probando primero
         * varios formatos humanos comunes (ISO, dd/MM/yyyy, etc.) y
         * como fallback el converter del propio control. El converter
         * por defecto de JavaFX para es_ES usa año de 2 dígitos —
         * insuficiente si el usuario teclea "31/12/2026". Por eso
         * priorizamos los parsers flexibles.
         */
        private LocalDate parseTypedDate(String s) {
            LocalDate v = parseFlexibleDate(s);
            if (v != null) return v;
            if (picker != null && picker.getConverter() != null && s != null) {
                try { return picker.getConverter().fromString(s.trim()); }
                catch (Exception ignored) { /* none matched */ }
            }
            return null;
        }
    }
}
