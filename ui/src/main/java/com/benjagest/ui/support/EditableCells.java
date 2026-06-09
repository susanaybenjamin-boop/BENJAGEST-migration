package com.benjagest.ui.support;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
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
            picker.focusedProperty().addListener((obs, was, now) -> {
                if (was && !now && isEditing()) {
                    String typed = picker.getEditor().getText();
                    LocalDate parsed = parseIso(typed);
                    if (parsed != null) commitEdit(parsed);
                    else cancelEdit();
                }
            });
            picker.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
                if (ev.getCode() == KeyCode.ESCAPE) {
                    cancelEdit();
                    ev.consume();
                } else if (ev.getCode() == KeyCode.TAB) {
                    String typed = picker.getEditor().getText();
                    LocalDate parsed = parseIso(typed);
                    if (parsed != null) commitEdit(parsed);
                    else cancelEdit();
                }
            });
        }

        private static LocalDate parseIso(String s) {
            if (s == null || s.isBlank()) return null;
            try { return LocalDate.parse(s.trim(), ISO); }
            catch (DateTimeParseException ex) { return null; }
        }
    }
}
