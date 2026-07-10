package com.benjagest.ui.screens;

import com.benjagest.ui.service.BillingApiClient;
import com.benjagest.ui.support.Router;
import java.util.function.Function;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * FAC-4 — Gestión de tipos de IVA/IRPF (catálogo {@code vat_rates}), extraída
 * del config tab de facturación. Construye la sección (tabla + acciones) vía
 * {@link #buildSection()}; el config tab la incrusta. Autocontenida: solo
 * depende de {@link BillingApiClient} y los helpers de {@link ScreenBase}.
 */
public class VatRatesScreen extends ScreenBase {

    private final BillingApiClient billingApiClient;
    private TableView<com.benjagest.ui.model.VatRateEntry> vatRatesTable;

    public VatRatesScreen(BillingApiClient billingApiClient,
                          Function<String, String> tt, Router router) {
        super(tt, router);
        this.billingApiClient = billingApiClient;
    }

    public Node buildSection() {
        Label header = label(t("billing.config.vat.section"), "settings-section-title");
        Label hint = new Label(t("billing.config.vat.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        vatRatesTable = new TableView<>();
        vatRatesTable.getStyleClass().add("data-table");
        vatRatesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        vatRatesTable.setPlaceholder(new Label(t("billing.config.vat.placeholder.empty")));
        vatRatesTable.setPrefHeight(240);

        TableColumn<com.benjagest.ui.model.VatRateEntry, String> colKind =
                new TableColumn<>(t("billing.config.vat.col.kind"));
        colKind.setCellValueFactory(c -> new SimpleStringProperty(
                "VAT".equals(c.getValue().kind()) ? t("billing.config.vat.kind.vat")
                        : t("billing.config.vat.kind.withholding")));
        colKind.setPrefWidth(100);
        TableColumn<com.benjagest.ui.model.VatRateEntry, String> colCode =
                new TableColumn<>(t("billing.config.vat.col.code"));
        colCode.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().code()));
        colCode.setPrefWidth(80);
        TableColumn<com.benjagest.ui.model.VatRateEntry, String> colLabel =
                new TableColumn<>(t("billing.config.vat.col.label"));
        colLabel.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().label()));
        TableColumn<com.benjagest.ui.model.VatRateEntry, String> colPct =
                new TableColumn<>(t("billing.config.vat.col.percent"));
        colPct.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().percent() == null ? "" : c.getValue().percent().toPlainString() + " %"));
        colPct.setPrefWidth(80);
        colPct.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.VatRateEntry, String> colFlags =
                new TableColumn<>(t("billing.config.vat.col.flags"));
        colFlags.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().isDefault() ? "★ " : "")
                + (c.getValue().active() ? "" : t("billing.config.vat.inactive"))));
        colFlags.setPrefWidth(100);
        vatRatesTable.getColumns().addAll(java.util.List.of(colKind, colCode, colLabel, colPct, colFlags));

        Button addBtn = new Button(t("billing.config.vat.action.add"));
        addBtn.setGraphic(icon("fas-plus"));
        addBtn.setOnAction(ev -> showVatRateEditor(null));

        Button editBtn = new Button(t("billing.config.vat.action.edit"));
        editBtn.setGraphic(icon("fas-edit"));
        editBtn.setDisable(true);
        editBtn.setOnAction(ev -> {
            var sel = vatRatesTable.getSelectionModel().getSelectedItem();
            if (sel != null) showVatRateEditor(sel);
        });

        Button deleteBtn = new Button(t("billing.config.vat.action.delete"));
        deleteBtn.setGraphic(icon("fas-trash"));
        deleteBtn.setDisable(true);
        deleteBtn.setOnAction(ev -> {
            var sel = vatRatesTable.getSelectionModel().getSelectedItem();
            if (sel != null) deleteVatRate(sel);
        });

        vatRatesTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            editBtn.setDisable(newV == null);
            deleteBtn.setDisable(newV == null || newV.isDefault());
        });

        Button loadStdBtn = new Button(t("billing.config.vat.action.load_standard"));
        loadStdBtn.setGraphic(icon("fas-layer-group"));
        loadStdBtn.setOnAction(ev -> loadStandardVatRates());

        HBox btnRow = new HBox(8, addBtn, editBtn, deleteBtn, loadStdBtn);
        btnRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        reloadVatRates();
        return new VBox(8, header, hint, vatRatesTable, btnRow);
    }

    private javafx.stage.Window window() {
        return vatRatesTable == null || vatRatesTable.getScene() == null
                ? null : vatRatesTable.getScene().getWindow();
    }

    /**
     * Crea los tipos de IVA/IRPF estándar que falten (por código), idempotente.
     * 21/10/4 % + Exento (art. 20) + Inversión del sujeto pasivo + retenciones
     * IRPF 15/7/19 %. Quedan elegibles en Nueva factura.
     */
    private void loadStandardVatRates() {
        java.util.Set<String> have = new java.util.HashSet<>();
        boolean hasVatDefault = false;
        for (var r : vatRatesTable.getItems()) {
            if (r.code() != null) have.add(r.code().toUpperCase());
            if ("VAT".equals(r.kind()) && r.isDefault()) hasVatDefault = true;
        }
        record Std(String kind, String code, String label, String pct, boolean def) {}
        final boolean genDefault = !hasVatDefault;
        java.util.List<Std> std = java.util.List.of(
                new Std("VAT", "GEN21", "IVA general (21%)", "21", genDefault),
                new Std("VAT", "RED10", "IVA reducido (10%)", "10", false),
                new Std("VAT", "SUP4", "IVA superreducido (4%)", "4", false),
                new Std("VAT", "EXE0", "Exento de IVA (art. 20 Ley 37/1992)", "0", false),
                new Std("VAT", "ISP0", "Inversión del sujeto pasivo (art. 84.Uno.2º)", "0", false),
                new Std("WITHHOLDING", "IRPF15", "Retención profesionales (15%)", "15", false),
                new Std("WITHHOLDING", "IRPF7", "Retención profesionales reducida (7%)", "7", false),
                new Std("WITHHOLDING", "IRPF19", "Retención arrendamientos (19%)", "19", false));
        java.util.List<Std> toCreate = new java.util.ArrayList<>();
        for (Std s : std) if (!have.contains(s.code())) toCreate.add(s);
        javafx.stage.Window win = window();
        if (toCreate.isEmpty()) {
            toast(win, t("billing.config.vat.load_standard.exists"));
            return;
        }
        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception {
                int n = 0;
                for (Std s : toCreate) {
                    billingApiClient.createVatRate(s.kind(), s.code(), s.label(),
                            new java.math.BigDecimal(s.pct()), s.def());
                    n++;
                }
                return n;
            }
        };
        task.setOnSucceeded(ev -> {
            reloadVatRates();
            toast(win, t("billing.config.vat.load_standard.done")
                    .replace("{n}", String.valueOf(task.getValue())));
        });
        task.setOnFailed(ev -> showError(t("billing.config.vat.section"),
                task.getException() == null ? "" : task.getException().getMessage()));
        start(task, "vat-load-standard");
    }

    private void reloadVatRates() {
        if (vatRatesTable == null) return;
        Task<java.util.List<com.benjagest.ui.model.VatRateEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.VatRateEntry> call() throws Exception {
                return billingApiClient.listVatRates(true);
            }
        };
        task.setOnSucceeded(ev -> vatRatesTable.setItems(FXCollections.observableArrayList(task.getValue())));
        task.setOnFailed(ev -> vatRatesTable.getItems().clear());
        start(task, "billing-vat-rates-reload");
    }

    private void showVatRateEditor(com.benjagest.ui.model.VatRateEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? t("billing.config.vat.editor.title_new")
                : t("billing.config.vat.editor.title_edit"));
        ButtonType saveBt = new ButtonType(t("billing.config.vat.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        ComboBox<String> kindCombo = new ComboBox<>();
        kindCombo.getItems().addAll("VAT", "WITHHOLDING");
        localizeEnumCombo(kindCombo, "vat_kind");
        kindCombo.getSelectionModel().select(existing == null ? "VAT" : existing.kind());
        kindCombo.setDisable(existing != null);

        TextField codeField = new TextField(existing == null ? "" : existing.code());
        codeField.setDisable(existing != null);
        TextField labelField = new TextField(existing == null ? "" : existing.label());
        TextField pctField = new TextField(existing == null ? "21" : existing.percent().toPlainString());
        CheckBox defaultCb = new CheckBox(t("billing.config.vat.editor.is_default"));
        defaultCb.setSelected(existing != null && existing.isDefault());
        CheckBox activeCb = new CheckBox(t("billing.config.vat.editor.active"));
        activeCb.setSelected(existing == null || existing.active());
        // FAC-IVA: texto legal PROPIO del tipo — se imprime en el PDF cuando
        // una línea de la factura usa este tipo (clave con dos 0% distintos).
        javafx.scene.control.TextArea legalArea = new javafx.scene.control.TextArea(
                existing == null || existing.legalText() == null ? "" : existing.legalText());
        legalArea.setPrefRowCount(3);
        legalArea.setWrapText(true);
        legalArea.setPromptText(t("billing.config.vat.editor.legal_prompt"));

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.add(new Label(t("billing.config.vat.editor.kind")), 0, 0); grid.add(kindCombo, 1, 0);
        grid.add(new Label(t("billing.config.vat.editor.code")), 0, 1); grid.add(codeField, 1, 1);
        grid.add(new Label(t("billing.config.vat.editor.label")), 0, 2); grid.add(labelField, 1, 2);
        grid.add(new Label(t("billing.config.vat.editor.percent")), 0, 3); grid.add(pctField, 1, 3);
        grid.add(defaultCb, 1, 4);
        grid.add(activeCb, 1, 5);
        grid.add(new Label(t("billing.config.vat.editor.legal")), 0, 6);
        grid.add(legalArea, 1, 6);
        installDialog(dialog, grid);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            try {
                java.math.BigDecimal pct = new java.math.BigDecimal(pctField.getText().trim().replace(",", "."));
                Task<com.benjagest.ui.model.VatRateEntry> task = new Task<>() {
                    @Override
                    protected com.benjagest.ui.model.VatRateEntry call() throws Exception {
                        if (existing == null) {
                            return billingApiClient.createVatRate(
                                    kindCombo.getValue(),
                                    codeField.getText().trim().toUpperCase(),
                                    labelField.getText().trim(),
                                    pct,
                                    defaultCb.isSelected(),
                                    legalArea.getText().trim());
                        }
                        return billingApiClient.updateVatRate(
                                existing.id(),
                                existing.kind(),
                                existing.code(),
                                labelField.getText().trim(),
                                pct,
                                defaultCb.isSelected(),
                                activeCb.isSelected(),
                                legalArea.getText().trim());
                    }
                };
                task.setOnSucceeded(ev -> reloadVatRates());
                task.setOnFailed(ev -> showError(t("billing.config.vat.editor.fail.title"),
                        t("billing.config.vat.editor.fail.body")));
                start(task, "billing-vat-rates-save");
            } catch (NumberFormatException ex) {
                showError(t("billing.config.vat.editor.fail.title"),
                        t("billing.config.vat.editor.invalid_percent"));
            }
        });
    }

    private void deleteVatRate(com.benjagest.ui.model.VatRateEntry entry) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("billing.config.vat.delete.body") + " " + entry.label(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("billing.config.vat.delete.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    billingApiClient.deleteVatRate(entry.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> reloadVatRates());
            task.setOnFailed(ev -> showError(t("billing.config.vat.delete.fail.title"),
                    t("billing.config.vat.delete.fail.body")));
            start(task, "billing-vat-rates-delete");
        });
    }
}
