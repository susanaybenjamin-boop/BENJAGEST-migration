package com.benjagest.ui.support;

import java.util.function.Function;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * NOM-7a — Editor de complementos salariales: filas (concepto, importe, cotiza,
 * tributa) + botón añadir + catálogo de típicos. Componente reutilizable
 * compartido por Nómina (finiquito/cálculo, {@code PayslipsScreen}) y Contratos
 * (editor, {@code ContractsScreen}); antes era una inner class del God Object.
 * Extraído tal cual (bloque UIR): mismo comportamiento, mismas claves i18n.
 * Recibe la función {@code t} de i18n; el salario base va aparte.
 *
 * <p>{@code monthlyMode=true} (contrato): el usuario teclea importe MENSUAL y se
 * guarda anual (×12). {@code false} (nómina): importe del mes tal cual.
 */
public final class SalaryComplementsEditor {

    /** Catálogo de complementos típicos (alta de un clic): {nombre, cotiza, tributa}. */
    private static final java.util.List<String[]> TYPICAL_COMPLEMENTS = java.util.List.of(
            new String[]{"Antigüedad", "1", "1"},
            new String[]{"Plus convenio", "1", "1"},
            new String[]{"Mejora voluntaria", "1", "1"},
            new String[]{"Plus de transporte", "1", "1"},
            new String[]{"Plus de productividad", "1", "1"},
            new String[]{"Plus de asistencia / puntualidad", "1", "1"},
            new String[]{"Complemento de puesto", "1", "1"},
            new String[]{"Plus de responsabilidad", "1", "1"},
            new String[]{"Plus de idiomas", "1", "1"},
            new String[]{"Plus de nocturnidad", "1", "1"},
            new String[]{"Plus de peligrosidad / penosidad", "1", "1"},
            new String[]{"Horas extraordinarias", "1", "1"},
            new String[]{"Dietas (exentas)", "0", "0"},
            new String[]{"Kilometraje (exento)", "0", "0"});

    private static java.math.BigDecimal parseDecSafe(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new java.math.BigDecimal(s.trim().replace(",", ".")); }
        catch (NumberFormatException ex) { return null; }
    }

    private final Function<String, String> tt;
    private String t(String key) { return tt.apply(key); }

    public final VBox node = new VBox(6);
    private final VBox rowsBox = new VBox(4);
    private final java.util.List<Row> rows = new java.util.ArrayList<>();
    private final String amountPromptKey;
    // monthlyMode=true (contrato): el usuario teclea importe MENSUAL; se
    // guarda anual (×12). false (nómina): importe del mes tal cual.
    private final boolean monthlyMode;

    private String displayAmount(java.math.BigDecimal annual) {
        if (annual == null) return "";
        java.math.BigDecimal v = monthlyMode
                ? annual.divide(java.math.BigDecimal.valueOf(12), 2, java.math.RoundingMode.HALF_UP)
                : annual;
        return v.toPlainString();
    }

    private final class Row {
        final HBox box;
        final TextField name;
        final TextField amount;
        final CheckBox cot;
        final CheckBox tax;
        Row(com.benjagest.ui.model.SalaryItemEntry it) {
            name = new TextField(it.conceptName() == null ? "" : it.conceptName());
            name.setPromptText(t("labor.contract.salary.concept"));
            name.setPrefWidth(200);
            amount = new TextField(displayAmount(it.annualAmount()));
            amount.setPromptText(t(amountPromptKey));
            amount.setPrefWidth(110);
            cot = new CheckBox(t("labor.contract.salary.cotizes"));
            cot.setSelected(it.cotizes());
            tax = new CheckBox(t("labor.contract.salary.taxable"));
            tax.setSelected(it.taxable());
            Button del = new Button("✕");
            del.getStyleClass().add("button-secondary");
            box = new HBox(8, name, amount, cot, tax, del);
            box.setAlignment(Pos.CENTER_LEFT);
            del.setOnAction(e -> { rows.remove(this); rowsBox.getChildren().remove(box); });
        }
    }

    public SalaryComplementsEditor(Function<String, String> tt,
                                   java.util.List<com.benjagest.ui.model.SalaryItemEntry> initial) {
        this(tt, initial, "labor.contract.salary.monthly", "labor.contract.salary.title",
                "labor.contract.salary.hint", true);
    }

    public SalaryComplementsEditor(Function<String, String> tt,
                                   java.util.List<com.benjagest.ui.model.SalaryItemEntry> initial,
                                   String amountPromptKey, String titleKey, String hintKey) {
        this(tt, initial, amountPromptKey, titleKey, hintKey, false);
    }

    public SalaryComplementsEditor(Function<String, String> tt,
                                   java.util.List<com.benjagest.ui.model.SalaryItemEntry> initial,
                                   String amountPromptKey, String titleKey, String hintKey,
                                   boolean monthlyMode) {
        this.tt = tt;
        this.amountPromptKey = amountPromptKey;
        this.monthlyMode = monthlyMode;
        Label title = new Label(t(titleKey));
        title.getStyleClass().add("settings-hint");
        Label hint = new Label(t(hintKey));
        hint.getStyleClass().add("settings-hint");
        hint.setWrapText(true);
        Button add = new Button(t("labor.contract.salary.add"));
        add.getStyleClass().add("button-secondary");
        add.setOnAction(e -> addRow(new com.benjagest.ui.model.SalaryItemEntry(
                null, "", "COMPLEMENT", null, true, true)));
        // Catálogo de complementos típicos (alta de un clic). Cada uno trae
        // sus marcas SS/IRPF por defecto (p. ej. dietas/kilometraje exentos).
        ComboBox<String[]> catalog = new ComboBox<>();
        catalog.getItems().addAll(TYPICAL_COMPLEMENTS);
        catalog.setPromptText(t("labor.contract.salary.catalog"));
        catalog.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String[] c) { return c == null ? "" : c[0]; }
            @Override public String[] fromString(String s) { return null; }
        });
        catalog.valueProperty().addListener((o, ov, nv) -> {
            if (nv == null) return;
            addRow(new com.benjagest.ui.model.SalaryItemEntry(
                    null, nv[0], "COMPLEMENT", null, "1".equals(nv[1]), "1".equals(nv[2])));
            javafx.application.Platform.runLater(() -> catalog.getSelectionModel().clearSelection());
        });
        HBox addRowBar = new HBox(8, add, catalog);
        addRowBar.setAlignment(Pos.CENTER_LEFT);
        node.getChildren().addAll(title, hint, rowsBox, addRowBar);
        if (initial != null) for (var it : initial) addRow(it);
    }

    private void addRow(com.benjagest.ui.model.SalaryItemEntry it) {
        Row r = new Row(it);
        rows.add(r);
        rowsBox.getChildren().add(r.box);
    }

    /** Añade un complemento desde código (p. ej. el plus de objetivo). */
    public void addComplement(com.benjagest.ui.model.SalaryItemEntry it) { addRow(it); }

    /** Vacía todas las filas (p. ej. al recalcular un finiquito). */
    public void clear() { rows.clear(); rowsBox.getChildren().clear(); }

    /** Si ya existe una fila con ese concepto, actualiza su importe; si no,
     *  la añade. Hace idempotente "proponer plus" (no duplica filas). */
    public void setOrAddComplement(com.benjagest.ui.model.SalaryItemEntry it) {
        for (Row r : rows) {
            if (it.conceptName() != null && it.conceptName().equals(r.name.getText())) {
                r.amount.setText(displayAmount(it.annualAmount()));
                r.cot.setSelected(it.cotizes());
                r.tax.setSelected(it.taxable());
                return;
            }
        }
        addRow(it);
    }

    public java.util.List<com.benjagest.ui.model.SalaryItemEntry> getComplements() {
        java.util.List<com.benjagest.ui.model.SalaryItemEntry> out = new java.util.ArrayList<>();
        for (Row r : rows) {
            String nm = r.name.getText() == null ? "" : r.name.getText().trim();
            if (nm.isEmpty()) continue;
            java.math.BigDecimal amt = parseDecSafe(r.amount.getText());
            // En modo mensual el usuario teclea €/mes; persistimos anual (×12).
            if (monthlyMode && amt != null) amt = amt.multiply(java.math.BigDecimal.valueOf(12));
            out.add(new com.benjagest.ui.model.SalaryItemEntry(
                    null, nm, "COMPLEMENT", amt,
                    r.cot.isSelected(), r.tax.isSelected()));
        }
        return out;
    }
}
