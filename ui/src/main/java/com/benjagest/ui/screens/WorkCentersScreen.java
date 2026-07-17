package com.benjagest.ui.screens;

import com.benjagest.ui.service.AltaApiClient;
import com.benjagest.ui.support.Router;
import java.util.function.Function;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * NOM-3 — Centros de trabajo (sub-pestaña "Tiempo" del módulo Laboral, bloque
 * UIR; port CONTENDO {@code centros_trabajo_180}). Extraída del God Object:
 * listado + alta/edición con geocodificación ("Buscar coordenadas") y política
 * de geolocalización de fichaje. Movimiento puro: mismo comportamiento, mismas
 * claves i18n. Depende de {@link AltaApiClient} y los helpers de
 * {@link ScreenBase}.
 */
public class WorkCentersScreen extends ScreenBase {

    private final AltaApiClient altaApiClient;

    public WorkCentersScreen(AltaApiClient altaApiClient,
                             Function<String, String> tt, Router router) {
        super(tt, router);
        this.altaApiClient = altaApiClient;
    }

    public Node buildWorkCentersTab() {
        VBox content = new VBox(12);
        content.setPadding(new Insets(16));

        Label hint = new Label(t("labor.centers.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        Button reloadBtn = new Button(t("labor.centers.reload"));
        reloadBtn.setGraphic(icon("fas-sync-alt"));
        Button addBtn = new Button(t("labor.centers.add"));
        addBtn.setGraphic(icon("fas-plus"));
        addBtn.getStyleClass().add("button-primary");
        Button editBtn = new Button(t("labor.centers.edit"));
        editBtn.setGraphic(icon("fas-pen"));
        editBtn.setDisable(true);
        Button delBtn = new Button(t("labor.centers.delete"));
        delBtn.setGraphic(icon("fas-trash"));
        delBtn.setDisable(true);

        TableView<com.benjagest.ui.model.WorkCenterEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.centers.empty")));

        TableColumn<com.benjagest.ui.model.WorkCenterEntry, String> cName =
                new TableColumn<>(t("labor.centers.col.name"));
        cName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        cName.setPrefWidth(180);
        TableColumn<com.benjagest.ui.model.WorkCenterEntry, String> cAddr =
                new TableColumn<>(t("labor.centers.col.address"));
        cAddr.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().address()));
        TableColumn<com.benjagest.ui.model.WorkCenterEntry, String> cCity =
                new TableColumn<>(t("labor.centers.col.city"));
        cCity.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().city()));
        cCity.setPrefWidth(120);
        TableColumn<com.benjagest.ui.model.WorkCenterEntry, String> cGeo =
                new TableColumn<>(t("labor.centers.col.geo"));
        cGeo.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().lat() != null && c.getValue().lng() != null
                        ? c.getValue().lat() + ", " + c.getValue().lng()
                        + " (" + c.getValue().radioM() + "m)"
                        : "—"));
        cGeo.setPrefWidth(180);
        TableColumn<com.benjagest.ui.model.WorkCenterEntry, String> cPol =
                new TableColumn<>(t("labor.centers.col.policy"));
        cPol.setCellValueFactory(c -> new SimpleStringProperty(
                humanizeGeoPolicy(c.getValue().geoPolicy())));
        cPol.setPrefWidth(80);

        table.getColumns().addAll(java.util.List.of(cName, cAddr, cCity, cGeo, cPol));

        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            boolean sel = nv != null;
            editBtn.setDisable(!sel);
            delBtn.setDisable(!sel);
        });

        Runnable reload = () -> {
            Task<java.util.List<com.benjagest.ui.model.WorkCenterEntry>> task = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.WorkCenterEntry> call() throws Exception {
                    return altaApiClient.listWorkCenters();
                }
            };
            task.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(task.getValue())));
            task.setOnFailed(ev -> showError(t("labor.centers.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "centers-load");
        };
        reloadBtn.setOnAction(ev -> reload.run());
        addBtn.setOnAction(ev -> openWorkCenterDialog(null, reload));
        editBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openWorkCenterDialog(sel, reload);
        });
        delBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(t("labor.centers.delete"));
            confirm.setHeaderText(t("labor.centers.delete.confirm"));
            confirm.showAndWait().ifPresent(rsp -> {
                if (rsp == javafx.scene.control.ButtonType.OK) {
                    Task<Void> d = new Task<>() {
                        @Override protected Void call() throws Exception {
                            altaApiClient.deleteWorkCenter(sel.id());
                            return null;
                        }
                    };
                    d.setOnSucceeded(s -> reload.run());
                    d.setOnFailed(s -> showError(t("labor.centers.fail.title"),
                            d.getException() == null ? "" : d.getException().getMessage()));
                    start(d, "centers-delete");
                }
            });
        });
        reload.run();

        javafx.scene.layout.FlowPane actions = actionFlow(reloadBtn, addBtn, editBtn, delBtn);
        VBox.setVgrow(table, Priority.ALWAYS);
        content.getChildren().addAll(hint, actions, table);
        return content;
    }

    private void openWorkCenterDialog(
            com.benjagest.ui.model.WorkCenterEntry edit, Runnable onSaved) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(t(edit == null ? "labor.centers.add" : "labor.centers.edit"));

        TextField name = new TextField(edit == null ? "" : edit.name());
        TextField addr = new TextField(edit == null ? "" : edit.address());
        TextField city = new TextField(edit == null ? "" : edit.city());
        TextField prov = new TextField(edit == null ? "" : edit.province());
        TextField cp = new TextField(edit == null ? "" : edit.postalCode());
        TextField lat = new TextField(edit == null || edit.lat() == null ? "" : edit.lat().toPlainString());
        TextField lng = new TextField(edit == null || edit.lng() == null ? "" : edit.lng().toPlainString());
        TextField radio = new TextField(edit == null ? "100" : String.valueOf(edit.radioM()));
        ComboBox<String> policy = new ComboBox<>(FXCollections.observableArrayList(
                "none", "info", "soft", "strict"));
        policy.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String code) {
                if (code == null) return "";
                return switch (code) {
                    case "none" -> t("labor.centers.policy.none");
                    case "info" -> t("labor.centers.policy.info");
                    case "soft" -> t("labor.centers.policy.soft");
                    case "strict" -> t("labor.centers.policy.strict");
                    default -> code;
                };
            }
            @Override public String fromString(String s) { return s; }
        });
        policy.setValue(edit == null ? "info" : edit.geoPolicy());
        TextArea notes = new TextArea(edit == null ? "" : edit.notes());
        notes.setPrefRowCount(3);

        Button geocodeBtn = new Button(t("labor.centers.geocode"));
        geocodeBtn.setGraphic(icon("fas-search-location"));
        Label geocodeStatus = new Label("");
        geocodeStatus.getStyleClass().add("settings-hint");
        geocodeStatus.setWrapText(true);
        geocodeBtn.setOnAction(e -> {
            if (addr.getText() == null || addr.getText().isBlank()
                    || city.getText() == null || city.getText().isBlank()) {
                geocodeStatus.setText(t("labor.centers.geocode.empty"));
                return;
            }
            geocodeStatus.setText(t("labor.centers.geocode.searching"));
            geocodeBtn.setDisable(true);
            String street = addr.getText();
            String postal = cp.getText();
            String ct = city.getText();
            String st = prov.getText();
            Task<com.benjagest.ui.service.AltaApiClient.GeocodeResult> gt = new Task<>() {
                @Override
                protected com.benjagest.ui.service.AltaApiClient.GeocodeResult call() throws Exception {
                    return altaApiClient.geocodeWorkCenter(street, postal, ct, st);
                }
            };
            gt.setOnSucceeded(ev -> {
                geocodeBtn.setDisable(false);
                var res = gt.getValue();
                lat.setText(res.lat().toPlainString());
                lng.setText(res.lng().toPlainString());
                geocodeStatus.setText(t("labor.centers.geocode.ok") + " "
                        + (res.displayName() == null ? "" : res.displayName()));
            });
            gt.setOnFailed(ev -> {
                geocodeBtn.setDisable(false);
                geocodeStatus.setText(t("labor.centers.geocode.fail") + " "
                        + (gt.getException() == null ? "" : gt.getException().getMessage()));
            });
            start(gt, "centers-geocode");
        });

        VBox form = new VBox(8,
                new Label(t("labor.centers.col.name")), name,
                new Label(t("labor.centers.col.address")), addr,
                new Label(t("labor.centers.col.city")), city,
                new Label(t("labor.centers.col.province")), prov,
                new Label(t("labor.centers.col.postal_code")), cp,
                new Label("Lat / Lng / Radio (m)"),
                new HBox(8, lat, lng, radio),
                geocodeBtn, geocodeStatus,
                new Label(t("labor.centers.col.policy")), policy,
                new Label(t("labor.centers.col.notes")), notes);
        form.setPadding(new Insets(16));
        form.setPrefWidth(420);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);

        dialog.setResultConverter(bt -> {
            if (bt == javafx.scene.control.ButtonType.OK) {
                if (name.getText() == null || name.getText().isBlank()) {
                    showError(t("labor.centers.fail.title"), t("labor.centers.error.name_required"));
                    return null;
                }
                java.math.BigDecimal latVal = parseBdOrNull(lat.getText());
                java.math.BigDecimal lngVal = parseBdOrNull(lng.getText());
                Integer radioVal;
                try {
                    radioVal = radio.getText() == null || radio.getText().isBlank()
                            ? null : Integer.valueOf(radio.getText().trim());
                } catch (NumberFormatException ex) {
                    showError(t("labor.centers.fail.title"), t("labor.centers.error.numeric"));
                    return null;
                }
                Task<Void> t = new Task<>() {
                    @Override protected Void call() throws Exception {
                        altaApiClient.saveWorkCenter(
                                edit == null ? null : edit.id(),
                                name.getText(), addr.getText(), city.getText(),
                                prov.getText(), cp.getText(),
                                latVal, lngVal, radioVal,
                                policy.getValue(), notes.getText());
                        return null;
                    }
                };
                t.setOnSucceeded(s -> onSaved.run());
                t.setOnFailed(s -> showError(t("labor.centers.fail.title"),
                        t.getException() == null ? "" : t.getException().getMessage()));
                start(t, "centers-save");
            }
            return null;
        });
        dialog.showAndWait();
    }

    private static java.math.BigDecimal parseBdOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new java.math.BigDecimal(s.trim()); }
        catch (NumberFormatException ex) { return null; }
    }

    /** Tarde 2026-06-10: política geo del centro de trabajo humanizada. */
    private String humanizeGeoPolicy(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return switch (raw.trim().toLowerCase()) {
            case "none" -> t("labor.centers.policy.none");
            case "info" -> t("labor.centers.policy.info");
            case "soft" -> t("labor.centers.policy.soft");
            case "strict" -> t("labor.centers.policy.strict");
            default -> raw;
        };
    }
}
