package com.benjagest.ui.screens;

import com.benjagest.ui.service.LaborApiClient;
import com.benjagest.ui.support.Router;
import java.util.function.Function;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.Node;

/**
 * NOM-6a — Kioscos/PDA de fichaje (sub-pestaña "Tiempo" del módulo Laboral,
 * bloque UIR; bloque FM). Extraída del God Object: alta de dispositivos, código
 * de activación (QR) y asignación de empleados. Movimiento puro: mismo
 * comportamiento, mismas claves i18n. Depende de {@link LaborApiClient} y los
 * helpers de {@link ScreenBase}.
 */
public class KioskDevicesScreen extends ScreenBase {

    private final LaborApiClient laborApiClient;

    public KioskDevicesScreen(LaborApiClient laborApiClient,
                              Function<String, String> tt, Router router) {
        super(tt, router);
        this.laborApiClient = laborApiClient;
    }

    /** Helper genérico para añadir columnas con un getter String. */
    private <T> void addCol(javafx.scene.control.TableView<T> table, String header,
                              java.util.function.Function<T, String> getter, double width) {
        javafx.scene.control.TableColumn<T, String> c = new javafx.scene.control.TableColumn<>(header);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(getter.apply(cd.getValue())));
        table.getColumns().add(c);
    }

    private void highlightMissing(javafx.scene.control.Control field) {
        if (field == null) return;
        if (!field.getStyleClass().contains("field-error")) {
            field.getStyleClass().add("field-error");
        }
        field.requestFocus();
    }

    public Node buildKioskDevicesTab(java.util.List<com.benjagest.ui.model.EmployeeEntry> employees) {
        Label hint = new Label(t("labor.kiosk.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        TableView<com.benjagest.ui.model.KioskDeviceEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.kiosk.empty")));
        addCol(table, t("labor.kiosk.col.name"), com.benjagest.ui.model.KioskDeviceEntry::name, 200);
        addCol(table, t("labor.kiosk.col.activated"),
                d -> d.activated() ? t("labor.kiosk.yes") : t("labor.kiosk.no"), 110);
        addCol(table, t("labor.kiosk.col.photo"),
                d -> d.requirePhoto() ? t("labor.kiosk.yes") : t("labor.kiosk.no"), 90);
        addCol(table, t("labor.kiosk.col.active"),
                d -> d.active() ? "✓" : "✗", 70);

        Runnable reload = () -> {
            Task<java.util.List<com.benjagest.ui.model.KioskDeviceEntry>> tk = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.KioskDeviceEntry> call() throws Exception {
                    return laborApiClient.listKioskDevices();
                }
            };
            tk.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(tk.getValue())));
            tk.setOnFailed(ev -> showError(t("labor.kiosk.fail"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "kiosk-list");
        };

        Button newBtn = new Button(t("labor.kiosk.new"));
        newBtn.getStyleClass().add("button-primary");
        newBtn.setGraphic(icon("fas-plus"));
        newBtn.setOnAction(e -> showKioskEditor(reload));

        Button activationBtn = new Button(t("labor.kiosk.activation"));
        activationBtn.setGraphic(icon("fas-qrcode"));
        activationBtn.setDisable(true);
        activationBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) showKioskActivation(sel);
        });

        Button empBtn = new Button(t("labor.kiosk.employees"));
        empBtn.setGraphic(icon("fas-users"));
        empBtn.setDisable(true);
        empBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) showKioskEmployees(sel, employees);
        });

        Button delBtn = new Button(t("labor.kiosk.delete"));
        delBtn.setGraphic(icon("fas-trash"));
        delBtn.setDisable(true);
        delBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert c = new Alert(Alert.AlertType.CONFIRMATION,
                    t("labor.kiosk.delete.body") + " " + sel.name(), ButtonType.OK, ButtonType.CANCEL);
            c.setHeaderText(t("labor.kiosk.delete"));
            c.showAndWait().ifPresent(bt -> {
                if (bt != ButtonType.OK) return;
                Task<Void> tk = new Task<>() {
                    @Override protected Void call() throws Exception { laborApiClient.deleteKioskDevice(sel.id()); return null; }
                };
                tk.setOnSucceeded(ev -> reload.run());
                tk.setOnFailed(ev -> showError(t("labor.kiosk.fail"),
                        tk.getException() == null ? "" : tk.getException().getMessage()));
                start(tk, "kiosk-delete");
            });
        });

        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            boolean none = nv == null;
            activationBtn.setDisable(none);
            empBtn.setDisable(none);
            delBtn.setDisable(none);
        });

        reload.run();
        javafx.scene.layout.FlowPane actions = actionFlow(newBtn, activationBtn, empBtn, delBtn);
        VBox box = new VBox(12, hint, actions, table);
        box.setPadding(new Insets(16));
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    private void showKioskEditor(Runnable onSaved) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.kiosk.new"));
        ButtonType save = new ButtonType(t("labor.kiosk.save"), ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        TextField nameF = new TextField();
        nameF.setPromptText(t("labor.kiosk.name.prompt"));
        CheckBox photoF = new CheckBox(t("labor.kiosk.require_photo"));
        Label legal = new Label(t("labor.kiosk.photo.legal"));
        legal.setWrapText(true); legal.getStyleClass().add("settings-hint");
        VBox box = new VBox(10, new Label(t("labor.kiosk.col.name")), nameF, photoF, legal);
        box.setPadding(new Insets(12));
        installDialog(d, box);
        final javafx.scene.Node saveNode = d.getDialogPane().lookupButton(save);
        saveNode.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            if (nameF.getText() == null || nameF.getText().isBlank()) {
                ev.consume();
                toast(d.getDialogPane().getScene().getWindow(), t("labor.kiosk.name.required"));
                highlightMissing(nameF);
            }
        });
        d.showAndWait().ifPresent(bt -> {
            if (bt != save) return;
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.createKioskDevice(nameF.getText().trim(), null, photoF.isSelected());
                    return null;
                }
            };
            tk.setOnSucceeded(ev -> onSaved.run());
            tk.setOnFailed(ev -> showError(t("labor.kiosk.fail"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "kiosk-create");
        });
    }

    private void showKioskActivation(com.benjagest.ui.model.KioskDeviceEntry device) {
        Task<String> tk = new Task<>() {
            @Override protected String call() throws Exception {
                return laborApiClient.generateKioskActivationToken(device.id());
            }
        };
        tk.setOnSucceeded(ev -> {
            String code = tk.getValue();
            Dialog<ButtonType> d = new Dialog<>();
            d.setTitle(t("labor.kiosk.activation") + " — " + device.name());
            d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            Label step1 = new Label(t("labor.kiosk.activation.body"));
            step1.setWrapText(true);
            TextArea codeArea = new TextArea(code);
            codeArea.setEditable(false); codeArea.setWrapText(true); codeArea.setPrefRowCount(2);
            codeArea.setStyle("-fx-font-size:16px; -fx-font-weight:bold;");
            Label urlLbl = new Label(t("labor.kiosk.activation.url"));
            urlLbl.setWrapText(true); urlLbl.getStyleClass().add("settings-hint");
            Label expire = new Label(t("labor.kiosk.activation.expire"));
            expire.getStyleClass().add("settings-hint");
            VBox box = new VBox(10, step1, codeArea, urlLbl, expire);
            box.setPadding(new Insets(12)); box.setPrefWidth(440);
            installDialog(d, box);
            d.showAndWait();
        });
        tk.setOnFailed(ev -> showError(t("labor.kiosk.fail"),
                tk.getException() == null ? "" : tk.getException().getMessage()));
        start(tk, "kiosk-activation");
    }

    private void showKioskEmployees(com.benjagest.ui.model.KioskDeviceEntry device,
                                    java.util.List<com.benjagest.ui.model.EmployeeEntry> employees) {
        Task<java.util.List<String>> load = new Task<>() {
            @Override protected java.util.List<String> call() throws Exception {
                return laborApiClient.listKioskEmployeeIds(device.id());
            }
        };
        load.setOnSucceeded(ev -> {
            java.util.Set<String> assigned = new java.util.HashSet<>(load.getValue());
            Dialog<ButtonType> d = new Dialog<>();
            d.setTitle(t("labor.kiosk.employees") + " — " + device.name());
            ButtonType save = new ButtonType(t("labor.kiosk.save"), ButtonBar.ButtonData.OK_DONE);
            d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
            VBox list = new VBox(6);
            java.util.Map<String, CheckBox> boxes = new java.util.LinkedHashMap<>();
            for (var e : employees) {
                if (!e.active()) continue;
                CheckBox cb = new CheckBox(e.fullName());
                cb.setSelected(assigned.contains(e.id()));
                boxes.put(e.id(), cb);
                list.getChildren().add(cb);
            }
            if (boxes.isEmpty()) list.getChildren().add(new Label(t("labor.kiosk.no_employees")));
            ScrollPane sp = new ScrollPane(list);
            sp.setFitToWidth(true); sp.setPrefHeight(360);
            VBox box = new VBox(8, new Label(t("labor.kiosk.employees.hint")), sp);
            box.setPadding(new Insets(12)); box.setPrefWidth(420);
            installDialog(d, box);
            d.showAndWait().ifPresent(bt -> {
                if (bt != save) return;
                java.util.List<String> toAssign = new java.util.ArrayList<>();
                java.util.List<String> toRemove = new java.util.ArrayList<>();
                for (var en : boxes.entrySet()) {
                    boolean checked = en.getValue().isSelected();
                    boolean was = assigned.contains(en.getKey());
                    if (checked) toAssign.add(en.getKey());
                    if (!checked && was) toRemove.add(en.getKey());
                }
                Task<Void> tk = new Task<>() {
                    @Override protected Void call() throws Exception {
                        if (!toAssign.isEmpty()) laborApiClient.assignKioskEmployees(device.id(), toAssign);
                        for (String id : toRemove) laborApiClient.removeKioskEmployee(device.id(), id);
                        return null;
                    }
                };
                tk.setOnFailed(ev2 -> showError(t("labor.kiosk.fail"),
                        tk.getException() == null ? "" : tk.getException().getMessage()));
                start(tk, "kiosk-assign");
            });
        });
        load.setOnFailed(ev -> showError(t("labor.kiosk.fail"),
                load.getException() == null ? "" : load.getException().getMessage()));
        start(load, "kiosk-emp-load");
    }
}
