package com.benjagest.ui.screens;

import com.benjagest.ui.model.*;
import com.benjagest.ui.service.*;
import com.benjagest.ui.support.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.control.cell.*;
import javafx.scene.layout.*;
import javafx.scene.text.*;
import javafx.stage.*;
import javafx.util.*;

/** RETA (autonomos) — perfiles + editores. Reutilizable: standalone (showRetaModule)
 *  y embebido en la ficha de cliente (buildHolder). Extraido en UIR-10. */
public class RetaProfilesScreen extends ScreenBase {

    private final LaborApiClient laborApiClient;
    private TableView<com.benjagest.ui.model.RetaProfileEntry> retaTable;

    public RetaProfilesScreen(LaborApiClient laborApiClient, Function<String, String> tt, Router router) {
        super(tt, router);
        this.laborApiClient = laborApiClient;
    }

    public void reloadRetaProfiles() {
        if (retaTable == null) return;
        Task<java.util.List<com.benjagest.ui.model.RetaProfileEntry>> task = new Task<>() {
            @Override protected java.util.List<com.benjagest.ui.model.RetaProfileEntry> call() throws Exception {
                return laborApiClient.listRetaProfiles(true);
            }
        };
        task.setOnSucceeded(ev -> retaTable.setItems(FXCollections.observableArrayList(task.getValue())));
        task.setOnFailed(ev -> { /* la tabla mantiene lo anterior */ });
        start(task, "reta-reload-profiles");
    }

    private VBox retaView(java.util.List<com.benjagest.ui.model.RetaProfileEntry> profiles) {
        VBox content = content();
        Label title = new Label(t("reta.title"));
        title.getStyleClass().add("module-detail-title");
        Label subtitle = new Label(t("reta.subtitle"));
        subtitle.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, subtitle);
        StackPane moduleIcon = iconBubble("fas-user-tie", "module-title-icon");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button newBtn = new Button(t("reta.action.new"));
        newBtn.setGraphic(icon("fas-plus"));
        newBtn.setOnAction(ev -> showRetaEditor(null));
        Button tramoBtn = new Button(t("reta.action.suggest_tramo"));
        tramoBtn.setGraphic(icon("fas-calculator"));
        tramoBtn.setOnAction(ev -> showRetaTramoSuggester());
        HBox header = new HBox(16, titleBox, moduleIcon, spacer, tramoBtn, newBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        retaTable = new TableView<>();
        retaTable.getStyleClass().add("data-table");
        retaTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        retaTable.setPlaceholder(retaEmptyState());
        TableColumn<com.benjagest.ui.model.RetaProfileEntry, String> colName =
                new TableColumn<>(t("reta.col.name"));
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().fullName()));
        TableColumn<com.benjagest.ui.model.RetaProfileEntry, String> colNif =
                new TableColumn<>(t("reta.col.nif"));
        colNif.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().taxIdentifier()));
        colNif.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.RetaProfileEntry, String> colBase =
                new TableColumn<>(t("reta.col.base"));
        colBase.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().currentBase() == null ? "" : c.getValue().currentBase().toPlainString() + " €"));
        colBase.setPrefWidth(110);
        colBase.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.RetaProfileEntry, String> colQuota =
                new TableColumn<>(t("reta.col.quota"));
        colQuota.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().currentQuota() == null ? "" : c.getValue().currentQuota().toPlainString() + " €"));
        colQuota.setPrefWidth(110);
        colQuota.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.RetaProfileEntry, String> colFlags =
                new TableColumn<>(t("reta.col.flags"));
        colFlags.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().tarifaPlana() ? "★ " : "")
                + (c.getValue().pluriactividad() ? "‡ " : "")
                + (c.getValue().active() ? "" : t("reta.inactive"))));
        colFlags.setPrefWidth(80);
        retaTable.getColumns().addAll(java.util.List.of(colName, colNif, colBase, colQuota, colFlags));
        retaTable.setItems(FXCollections.observableArrayList(profiles));
        retaTable.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                var sel = retaTable.getSelectionModel().getSelectedItem();
                if (sel != null) showRetaEditor(sel);
            }
        });

        Button editBtn = new Button(t("reta.action.edit"));
        editBtn.setGraphic(icon("fas-edit"));
        editBtn.setDisable(true);
        editBtn.setOnAction(ev -> {
            var sel = retaTable.getSelectionModel().getSelectedItem();
            if (sel != null) showRetaEditor(sel);
        });
        Button changesBtn = new Button(t("reta.action.changes"));
        changesBtn.setGraphic(icon("fas-history"));
        changesBtn.setDisable(true);
        changesBtn.setOnAction(ev -> {
            var sel = retaTable.getSelectionModel().getSelectedItem();
            if (sel != null) showRetaChanges(sel);
        });
        Button delBtn = new Button(t("reta.action.delete"));
        delBtn.setGraphic(icon("fas-user-slash"));
        delBtn.setDisable(true);
        delBtn.setOnAction(ev -> {
            var sel = retaTable.getSelectionModel().getSelectedItem();
            if (sel != null) deleteRetaProfile(sel);
        });
        retaTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            editBtn.setDisable(nv == null);
            changesBtn.setDisable(nv == null);
            delBtn.setDisable(nv == null || !nv.active());
        });
        HBox actions = new HBox(8, editBtn, changesBtn, delBtn);
        actions.getStyleClass().add("settings-actions");

        VBox body = new VBox(12, retaTable);
        VBox.setVgrow(retaTable, Priority.ALWAYS);
        content.getChildren().addAll(header, body, actions);
        return content;
    }

    /**
     * Estado vacío ACCIONABLE de la lista de perfiles RETA. Si la empresa aún no
     * tiene forma jurídica = AUTONOMO, el perfil no se autocrea y la tabla sale
     * vacía. Este panel permite marcarla como autónomo (fija companies.legal_form
     * y crea el perfil) sin tener que buscar la pestaña Configuración. Resuelve la
     * confusión recurrente "marqué autónomo y no aparece" (Benjamin 2026-06-16):
     * el "Tipo" del editor de cliente-receptor (customers.customer_type) NO es lo
     * mismo que la forma jurídica de la empresa gestionada (companies.legal_form),
     * que es lo que mira RETA.
     */
    private Node retaEmptyState() {
        Label hint = new Label(t("reta.empty.hint"));
        hint.getStyleClass().add("settings-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(520);
        Button mark = new Button(t("reta.empty.mark_autonomo"));
        mark.setGraphic(icon("fas-check"));
        mark.getStyleClass().add("button-primary");
        mark.setOnAction(ev -> markCompanyAutonomoAndCreateProfile());
        VBox box = new VBox(12, hint, mark);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(24));
        return box;
    }

    /**
     * Fija companies.legal_form = AUTONOMO en la empresa actual (tenant) y crea su
     * perfil RETA. Preserva el resto de la config de la asesoría. Tras crear,
     * recarga la tabla en su sitio (sin reconstruir el centro).
     */
    private void markCompanyAutonomoAndCreateProfile() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("reta.empty.mark_confirm"), ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("reta.empty.mark_autonomo"));
        confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    var cfg = laborApiClient.getClientAdvisoryConfig();
                    var upd = new com.benjagest.ui.model.ClientConfigModels.AdvisoryConfigEntry(
                            cfg == null ? null : cfg.fiscalPeriod(),
                            cfg == null ? null : cfg.taxRegime(),
                            cfg == null ? null : cfg.contactChannel(),
                            cfg == null ? null : cfg.contactValue(),
                            cfg == null ? null : cfg.internalNotes(),
                            "AUTONOMO",
                            cfg == null || cfg.provisionExtraPay(),
                            cfg == null || cfg.reflejoAutoEnabled());
                    laborApiClient.saveClientAdvisoryConfig(upd);
                    laborApiClient.ensureRetaProfiles();
                    return null;
                }
            };
            task.setOnSucceeded(ev -> {
                reloadRetaProfiles();
                Alert ok = new Alert(Alert.AlertType.INFORMATION,
                        t("reta.empty.marked_ok"), ButtonType.OK);
                ok.setHeaderText(null);
                ok.showAndWait();
            });
            task.setOnFailed(ev -> showError(t("reta.load_failed"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "reta-mark-autonomo");
        });
    }

    /**
     * Hace un ComboBox editable FILTRABLE: al teclear, el desplegable muestra
     * solo los items que CONTIENEN el texto (en código o descripción), ignorando
     * mayúsculas y acentos. Evita tener que recorrer listas largas (CNAE/IAE).
     */
    private void installComboFilter(ComboBox<String> combo, java.util.List<String> all) {
        final java.util.List<String> master = new java.util.ArrayList<>(all);
        combo.getItems().setAll(master);
        final boolean[] guard = {false};
        combo.getEditor().textProperty().addListener((obs, ov, nv) -> {
            if (guard[0]) return;
            guard[0] = true;
            try {
                String q = nv == null ? "" : stripDiacritics(nv.toLowerCase()).trim();
                if (q.isEmpty()) {
                    combo.getItems().setAll(master);
                } else {
                    java.util.List<String> f = new java.util.ArrayList<>();
                    for (String it : master) {
                        if (stripDiacritics(it.toLowerCase()).contains(q)) f.add(it);
                    }
                    combo.getItems().setAll(f);
                    if (!combo.isShowing()) combo.show();
                }
                // setAll puede tocar el editor: restauramos lo tecleado + caret.
                combo.getEditor().setText(nv);
                combo.getEditor().positionCaret(nv == null ? 0 : nv.length());
            } finally {
                guard[0] = false;
            }
        });
    }

    /** De "6201 — Actividades de programación" devuelve "6201" (máx 20). Si el
     *  usuario tecleó un valor libre sin separador, lo devuelve tal cual. */
    private String retaCodePart(String s) {
        if (s == null) return null;
        String v = s.contains(" — ") ? s.substring(0, s.indexOf(" — ")) : s;
        v = v.trim();
        return v.length() > 20 ? v.substring(0, 20) : v;
    }

    private void showRetaEditor(com.benjagest.ui.model.RetaProfileEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? t("reta.editor.title_new") : t("reta.editor.title_edit"));
        ButtonType saveBt = new ButtonType(t("reta.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField nameField = new TextField(existing == null ? "" : existing.fullName());
        TextField nifField = new TextField(existing == null ? "" : existing.taxIdentifier());
        TextField nussField = new TextField(existing == null ? "" : existing.socialSecurityNumber());
        TextField startField = new TextField(existing == null || existing.retaStartDate() == null ? "" : existing.retaStartDate().toString());
        startField.setPromptText("AAAA-MM-DD");
        com.benjagest.ui.support.EditableCells.installIsoDateMask(startField);
        TextField endField = new TextField(existing == null || existing.retaEndDate() == null ? "" : existing.retaEndDate().toString());
        endField.setPromptText("AAAA-MM-DD");
        com.benjagest.ui.support.EditableCells.installIsoDateMask(endField);
        CheckBox pluri = new CheckBox(t("reta.editor.pluriactividad"));
        pluri.setSelected(existing != null && existing.pluriactividad());
        CheckBox tarifa = new CheckBox(t("reta.editor.tarifa_plana"));
        tarifa.setSelected(existing != null && existing.tarifaPlana());
        TextField tarifaUntil = new TextField(existing == null || existing.tarifaPlanaUntil() == null ? "" : existing.tarifaPlanaUntil().toString());
        tarifaUntil.setPromptText("AAAA-MM-DD");
        com.benjagest.ui.support.EditableCells.installIsoDateMask(tarifaUntil);
        // Combos editables (lista de valores ya usados + custom escribiendo).
        ComboBox<String> actCode = new ComboBox<>(); actCode.setEditable(true); actCode.setMaxWidth(Double.MAX_VALUE);
        actCode.getEditor().setText(existing == null || existing.activityCode() == null ? "" : existing.activityCode());
        ComboBox<String> actDesc = new ComboBox<>(); actDesc.setEditable(true); actDesc.setMaxWidth(Double.MAX_VALUE);
        actDesc.getEditor().setText(existing == null || existing.activityDescription() == null ? "" : existing.activityDescription());
        ComboBox<String> iae = new ComboBox<>(); iae.setEditable(true); iae.setMaxWidth(Double.MAX_VALUE);
        iae.getEditor().setText(existing == null || existing.iaeEpigraph() == null ? "" : existing.iaeEpigraph());
        // Cargar el CATÁLOGO OFICIAL (CNAE para actividad, IAE para epígrafe) en
        // los combos como "código — descripción"; siguen siendo editables (custom).
        Task<java.util.List<String>> cnaeTask = new Task<>() {
            @Override protected java.util.List<String> call() throws Exception {
                return laborApiClient.activityCatalog("CNAE");
            }
        };
        cnaeTask.setOnSucceeded(e2 -> installComboFilter(actCode, cnaeTask.getValue()));
        start(cnaeTask, "reta-cnae");
        Task<java.util.List<String>> iaeTask = new Task<>() {
            @Override protected java.util.List<String> call() throws Exception {
                return laborApiClient.activityCatalog("IAE");
            }
        };
        iaeTask.setOnSucceeded(e2 -> installComboFilter(iae, iaeTask.getValue()));
        start(iaeTask, "reta-iae");
        // Descripción: valores ya usados (la descripción se autocompleta al elegir CNAE).
        Task<java.util.Map<String, java.util.List<String>>> catTask = new Task<>() {
            @Override protected java.util.Map<String, java.util.List<String>> call() throws Exception {
                return laborApiClient.retaCatalogs();
            }
        };
        catTask.setOnSucceeded(e2 ->
                installComboFilter(actDesc, catTask.getValue().getOrDefault("activityDescriptions", java.util.List.of())));
        start(catTask, "reta-catalogs");
        // Al elegir una actividad CNAE ("código — descripción"), autocompletar la
        // descripción si está vacía.
        actCode.valueProperty().addListener((o, ov, nv) -> {
            if (nv != null && nv.contains(" — ") && actDesc.getEditor().getText().isBlank()) {
                actDesc.getEditor().setText(nv.substring(nv.indexOf(" — ") + 3).trim());
            }
        });

        TextField netIncome = new TextField(existing == null || existing.expectedNetIncome() == null
                ? "" : existing.expectedNetIncome().toPlainString());
        TextField base = new TextField(existing == null || existing.currentBase() == null
                ? "" : existing.currentBase().toPlainString());
        TextField quota = new TextField(existing == null || existing.currentQuota() == null
                ? "" : existing.currentQuota().toPlainString());
        // B1 — Sugerir base+cuota desde el rendimiento neto previsto (tramos en BD).
        Button suggestBtn = new Button(t("reta.editor.suggest"));
        suggestBtn.setGraphic(icon("fas-wand-magic-sparkles"));
        Label suggestInfo = new Label("");
        suggestInfo.getStyleClass().add("settings-hint");
        suggestBtn.setOnAction(ev -> {
            java.math.BigDecimal net = parseDecSafe(netIncome.getText());
            if (net == null) { suggestInfo.setText(t("reta.editor.suggest_need_income")); return; }
            int y = java.time.Year.now().getValue();
            Task<com.benjagest.ui.model.RetaTramoEntry> sTask = new Task<>() {
                @Override protected com.benjagest.ui.model.RetaTramoEntry call() throws Exception {
                    return laborApiClient.suggestRetaTramo(y, net);
                }
            };
            sTask.setOnSucceeded(e2 -> {
                var tr = sTask.getValue();
                if (tr.baseMin() != null) base.setText(tr.baseMin().toPlainString());
                if (tr.quotaMin() != null) quota.setText(tr.quotaMin().toPlainString());
                suggestInfo.setText(t("reta.editor.suggest_done")
                        .replace("{t}", tr.label() == null ? "" : tr.label())
                        .replace("{min}", tr.baseMin() == null ? "" : tr.baseMin().toPlainString())
                        .replace("{max}", tr.baseMax() == null ? "" : tr.baseMax().toPlainString()));
            });
            sTask.setOnFailed(e2 -> suggestInfo.setText(t("reta.editor.suggest_fail")));
            start(sTask, "reta-suggest");
        });
        TextArea notes = new TextArea(existing == null ? "" : existing.notes());
        notes.setPrefRowCount(2);
        CheckBox active = new CheckBox(t("reta.editor.active"));
        active.setSelected(existing == null || existing.active());

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(6); g.setPadding(new Insets(10));
        // Anchos de columna: las etiquetas (col 0 y 2) con ancho mínimo para que
        // no se trunquen a "..."; los campos (col 1 y 3) crecen.
        javafx.scene.layout.ColumnConstraints gcLabel = new javafx.scene.layout.ColumnConstraints();
        gcLabel.setMinWidth(165); gcLabel.setHalignment(javafx.geometry.HPos.LEFT);
        javafx.scene.layout.ColumnConstraints gcField = new javafx.scene.layout.ColumnConstraints();
        gcField.setHgrow(Priority.ALWAYS); gcField.setMinWidth(120); gcField.setFillWidth(true);
        javafx.scene.layout.ColumnConstraints gcLabel2 = new javafx.scene.layout.ColumnConstraints();
        gcLabel2.setMinWidth(110); gcLabel2.setHalignment(javafx.geometry.HPos.LEFT);
        javafx.scene.layout.ColumnConstraints gcField2 = new javafx.scene.layout.ColumnConstraints();
        gcField2.setHgrow(Priority.ALWAYS); gcField2.setMinWidth(90); gcField2.setFillWidth(true);
        g.getColumnConstraints().addAll(gcLabel, gcField, gcLabel2, gcField2);
        int row = 0;
        g.add(new Label(t("reta.editor.name")), 0, row); g.add(nameField, 1, row, 3, 1); row++;
        g.add(new Label(t("reta.editor.nif")), 0, row); g.add(nifField, 1, row);
        g.add(new Label(t("reta.editor.nuss")), 2, row); g.add(nussField, 3, row); row++;
        g.add(new Label(t("reta.editor.start")), 0, row); g.add(startField, 1, row);
        g.add(new Label(t("reta.editor.end")), 2, row); g.add(endField, 3, row); row++;
        g.add(pluri, 1, row); g.add(tarifa, 3, row); row++;
        g.add(new Label(t("reta.editor.tarifa_until")), 0, row); g.add(tarifaUntil, 1, row, 3, 1); row++;
        g.add(new Separator(), 0, row++, 4, 1);
        g.add(new Label(t("reta.editor.activity_code")), 0, row); g.add(actCode, 1, row);
        g.add(new Label(t("reta.editor.iae")), 2, row); g.add(iae, 3, row); row++;
        g.add(new Label(t("reta.editor.activity_desc")), 0, row); g.add(actDesc, 1, row, 3, 1); row++;
        g.add(new Separator(), 0, row++, 4, 1);
        g.add(new Label(t("reta.editor.net_income")), 0, row); g.add(netIncome, 1, row);
        g.add(suggestBtn, 2, row, 2, 1); row++;
        g.add(new Label(t("reta.editor.base")), 0, row); g.add(base, 1, row);
        g.add(new Label(t("reta.editor.quota")), 2, row); g.add(quota, 3, row); row++;
        g.add(suggestInfo, 0, row++, 4, 1);
        g.add(active, 1, row++);
        g.add(new Label(t("reta.editor.notes")), 0, row); g.add(notes, 1, row, 3, 1);

        ScrollPane sp = new ScrollPane(g);
        sp.setFitToWidth(true);
        sp.setPrefViewportHeight(520);
        dialog.getDialogPane().setContent(sp);
        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            com.benjagest.ui.model.RetaProfileEntry payload = new com.benjagest.ui.model.RetaProfileEntry(
                    existing == null ? null : existing.id(),
                    null, null,
                    nameField.getText().trim(),
                    blankToNullOrSelf(nifField.getText()),
                    blankToNullOrSelf(nussField.getText()),
                    parseDateSafe(startField.getText()),
                    parseDateSafe(endField.getText()),
                    pluri.isSelected(), tarifa.isSelected(),
                    parseDateSafe(tarifaUntil.getText()),
                    blankToNullOrSelf(retaCodePart(actCode.getEditor().getText())),
                    blankToNullOrSelf(actDesc.getEditor().getText()),
                    blankToNullOrSelf(retaCodePart(iae.getEditor().getText())),
                    parseDecSafe(netIncome.getText()),
                    parseDecSafe(base.getText()),
                    parseDecSafe(quota.getText()),
                    blankToNullOrSelf(notes.getText()),
                    active.isSelected()
            );
            Task<com.benjagest.ui.model.RetaProfileEntry> task = new Task<>() {
                @Override protected com.benjagest.ui.model.RetaProfileEntry call() throws Exception {
                    return existing == null
                            ? laborApiClient.createRetaProfile(payload)
                            : laborApiClient.updateRetaProfile(existing.id(), payload);
                }
            };
            task.setOnSucceeded(ev -> reloadRetaProfiles());
            task.setOnFailed(ev -> showError(t("reta.editor.fail.title"), t("reta.editor.fail.body")));
            start(task, "reta-save");
        });
    }

    private void deleteRetaProfile(com.benjagest.ui.model.RetaProfileEntry p) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("reta.delete.body") + " " + p.fullName(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("reta.delete.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.deleteRetaProfile(p.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> reloadRetaProfiles());
            task.setOnFailed(ev -> showError(t("reta.editor.fail.title"), t("reta.editor.fail.body")));
            start(task, "reta-delete");
        });
    }

    private void showRetaChanges(com.benjagest.ui.model.RetaProfileEntry profile) {
        int year = LocalDate.now().getYear();
        Task<java.util.List<com.benjagest.ui.model.RetaBaseChangeEntry>> task = new Task<>() {
            @Override protected java.util.List<com.benjagest.ui.model.RetaBaseChangeEntry> call() throws Exception {
                return laborApiClient.listRetaChanges(profile.id(), year);
            }
        };
        task.setOnSucceeded(ev -> {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle(t("reta.changes.title") + " — " + profile.fullName() + " (" + year + ")");
            ButtonType newBt = new ButtonType(t("reta.changes.new"), ButtonBar.ButtonData.LEFT);
            dialog.getDialogPane().getButtonTypes().addAll(newBt, ButtonType.CLOSE);

            TableView<com.benjagest.ui.model.RetaBaseChangeEntry> tbl = new TableView<>();
            tbl.getStyleClass().add("data-table");
            tbl.setPrefHeight(260);
            tbl.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            tbl.setPlaceholder(new Label(t("reta.changes.placeholder.empty")));
            TableColumn<com.benjagest.ui.model.RetaBaseChangeEntry, String> cDate =
                    new TableColumn<>(t("reta.changes.col.date"));
            cDate.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().effectiveDate() == null ? "" : c.getValue().effectiveDate().toString()));
            cDate.setComparator(ISO_DATE_COMPARATOR);  // VG-FULL-SCAN
            cDate.setPrefWidth(110);
            TableColumn<com.benjagest.ui.model.RetaBaseChangeEntry, String> cBase =
                    new TableColumn<>(t("reta.changes.col.base"));
            cBase.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().newBase() == null ? "" : c.getValue().newBase().toPlainString() + " €"));
            cBase.setComparator(NUMERIC_STRING_COMPARATOR);  // VG-FULL-SCAN
            cBase.setPrefWidth(110);
            TableColumn<com.benjagest.ui.model.RetaBaseChangeEntry, String> cQuota =
                    new TableColumn<>(t("reta.changes.col.quota"));
            cQuota.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().newQuota() == null ? "" : c.getValue().newQuota().toPlainString() + " €"));
            cQuota.setComparator(NUMERIC_STRING_COMPARATOR);  // VG-FULL-SCAN
            cQuota.setPrefWidth(110);
            TableColumn<com.benjagest.ui.model.RetaBaseChangeEntry, String> cReason =
                    new TableColumn<>(t("reta.changes.col.reason"));
            cReason.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().changeReason()));
            TableColumn<com.benjagest.ui.model.RetaBaseChangeEntry, String> cSent =
                    new TableColumn<>(t("reta.changes.col.sent"));
            cSent.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().submittedToSs() ? "✓" : ""));
            cSent.setPrefWidth(60);
            tbl.getColumns().addAll(java.util.List.of(cDate, cBase, cQuota, cReason, cSent));
            tbl.setItems(FXCollections.observableArrayList(task.getValue()));

            VBox body = new VBox(8, new Label(t("reta.changes.hint")), tbl);
            body.setPadding(new Insets(10));
            installDialog(dialog, body);

            // Interceptamos el boton de la izquierda para abrir el sub-editor
            Button newButton = (Button) dialog.getDialogPane().lookupButton(newBt);
            newButton.addEventFilter(javafx.event.ActionEvent.ACTION, btnEv -> {
                btnEv.consume();
                showRetaChangeEditor(profile);
                dialog.close();
            });
            dialog.setResizable(true);
            dialog.showAndWait();
        });
        task.setOnFailed(ev -> showError(t("reta.changes.load.fail"), t("reta.changes.load.fail.body")));
        start(task, "reta-changes-load");
    }

    private void showRetaChangeEditor(com.benjagest.ui.model.RetaProfileEntry profile) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("reta.change.editor.title") + " — " + profile.fullName());
        ButtonType saveBt = new ButtonType(t("reta.change.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField effective = new TextField(LocalDate.now().toString());
        effective.setPromptText("AAAA-MM-DD");
        com.benjagest.ui.support.EditableCells.installIsoDateMask(effective);
        TextField reason = new TextField();
        reason.setPromptText(t("reta.change.editor.reason.prompt"));
        TextField base = new TextField(profile.currentBase() == null ? "" : profile.currentBase().toPlainString());
        TextField quota = new TextField(profile.currentQuota() == null ? "" : profile.currentQuota().toPlainString());
        TextField netIncome = new TextField(profile.expectedNetIncome() == null
                ? "" : profile.expectedNetIncome().toPlainString());
        CheckBox sent = new CheckBox(t("reta.change.editor.submitted"));
        TextArea notes = new TextArea(); notes.setPrefRowCount(2);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(10));
        g.add(new Label(t("reta.change.editor.effective")), 0, 0); g.add(effective, 1, 0);
        g.add(new Label(t("reta.change.editor.reason")), 0, 1); g.add(reason, 1, 1);
        g.add(new Label(t("reta.change.editor.new_base")), 0, 2); g.add(base, 1, 2);
        g.add(new Label(t("reta.change.editor.new_quota")), 0, 3); g.add(quota, 1, 3);
        g.add(new Label(t("reta.change.editor.net_income")), 0, 4); g.add(netIncome, 1, 4);
        g.add(sent, 1, 5);
        g.add(new Label(t("reta.change.editor.notes")), 0, 6); g.add(notes, 1, 6);
        installDialog(dialog, g);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            com.benjagest.ui.model.RetaBaseChangeEntry payload = new com.benjagest.ui.model.RetaBaseChangeEntry(
                    null, profile.id(),
                    parseDateSafe(effective.getText()),
                    blankToNullOrSelf(reason.getText()),
                    parseDecSafe(base.getText()),
                    parseDecSafe(quota.getText()),
                    parseDecSafe(netIncome.getText()),
                    sent.isSelected(),
                    blankToNullOrSelf(notes.getText())
            );
            Task<com.benjagest.ui.model.RetaBaseChangeEntry> task = new Task<>() {
                @Override protected com.benjagest.ui.model.RetaBaseChangeEntry call() throws Exception {
                    return laborApiClient.createRetaChange(profile.id(), payload);
                }
            };
            task.setOnSucceeded(ev -> reloadRetaProfiles());
            task.setOnFailed(ev -> showError(t("reta.change.editor.fail.title"),
                    t("reta.change.editor.fail.body")));
            start(task, "reta-change-save");
        });
    }

    private void showRetaTramoSuggester() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("reta.tramo.title"));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        TextField netField = new TextField();
        netField.setPromptText(t("reta.tramo.net.prompt"));
        Button calc = new Button(t("reta.tramo.calc"));
        Label result = new Label();
        result.setWrapText(true);
        result.getStyleClass().add("settings-section-title");

        calc.setOnAction(ev -> {
            java.math.BigDecimal annual;
            try {
                annual = new java.math.BigDecimal(netField.getText().trim().replace(",", "."));
            } catch (NumberFormatException ex) {
                result.setText(t("reta.tramo.invalid"));
                return;
            }
            Task<com.benjagest.ui.model.RetaTramoSuggestion> task = new Task<>() {
                @Override protected com.benjagest.ui.model.RetaTramoSuggestion call() throws Exception {
                    return laborApiClient.suggestRetaTramo(annual);
                }
            };
            task.setOnSucceeded(e -> {
                var s = task.getValue();
                result.setText(String.format(
                        "%s%n%s: %s – %s €%n%s: %s €%n%s: %s €/mes",
                        s.tramoLabel(),
                        t("reta.tramo.result.base_range"), s.baseMinima(), s.baseMaxima(),
                        t("reta.tramo.result.quota"), s.cuotaMinima(),
                        t("reta.tramo.result.monthly_income"), s.monthlyIncome()));
            });
            task.setOnFailed(e -> result.setText(t("reta.tramo.fail")));
            start(task, "reta-tramo-suggest");
        });

        VBox body = new VBox(10,
                new Label(t("reta.tramo.hint")),
                new HBox(8, new Label(t("reta.tramo.net.label")), netField, calc),
                new Separator(),
                result);
        body.setPadding(new Insets(10));
        body.setPrefWidth(500);
        installDialog(dialog, body);
        dialog.showAndWait();
    }

    // ===================================================================
    //  N1 — Modulo DEHu: bandeja de notificaciones
    // ===================================================================

    private com.benjagest.ui.screens.DehuScreen dehuScreen;

    public Node buildHolder() {
        VBox holder = new VBox();
        Label loading = new Label(t("panorama.loading"));
        loading.getStyleClass().add("settings-hint");
        loading.setPadding(new Insets(12));
        holder.getChildren().add(loading);
        VBox.setVgrow(holder, Priority.ALWAYS);
        Task<java.util.List<com.benjagest.ui.model.RetaProfileEntry>> task = new Task<>() {
            @Override protected java.util.List<com.benjagest.ui.model.RetaProfileEntry> call() throws Exception {
                // RETA-4: crea el perfil del autónomo si falta (empresa AUTONOMO o
                // titular RETA) antes de listar, para que no salga vacío.
                try { laborApiClient.ensureRetaProfiles(); } catch (Exception ignored) { }
                return laborApiClient.listRetaProfiles(true);
            }
        };
        task.setOnSucceeded(ev -> holder.getChildren().setAll(scroll(retaView(task.getValue()))));
        task.setOnFailed(ev -> holder.getChildren().setAll(errorPanel(t("reta.load_failed"))));
        start(task, "client-reta-load");
        return holder;
    }
}
