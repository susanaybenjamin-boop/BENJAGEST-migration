package com.benjagest.ui.screens;

import com.benjagest.ui.service.LaborApiClient;
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
 * NOM-6b — Planificación de jornada (sub-pestaña "Tiempo" del módulo Laboral,
 * bloque UIR; JOR-2). Extraída del God Object: plantillas de horario con bloques
 * por día (WORK/BREAK, copiar entre días) y asignación de plantillas a empleados
 * por vigencia. Movimiento puro: mismo comportamiento, mismas claves i18n.
 * Depende de {@link LaborApiClient} y los helpers de {@link ScreenBase}.
 */
public class ScheduleTemplatesScreen extends ScreenBase {

    private final LaborApiClient laborApiClient;

    public ScheduleTemplatesScreen(LaborApiClient laborApiClient,
                                   Function<String, String> tt, Router router) {
        super(tt, router);
        this.laborApiClient = laborApiClient;
    }

    private Label formLabel(String key) {
        Label l = new Label(t(key));
        l.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return l;
    }

    public Node buildScheduleTemplatesTab(java.util.List<com.benjagest.ui.model.EmployeeEntry> employees) {
        VBox content = new VBox(16);
        content.setPadding(new Insets(16));

        Label hint = new Label(t("labor.schedule.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        TableView<com.benjagest.ui.model.ScheduleTemplateEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.schedule.empty")));

        TableColumn<com.benjagest.ui.model.ScheduleTemplateEntry, String> cName =
                new TableColumn<>(t("labor.schedule.col.name"));
        cName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        TableColumn<com.benjagest.ui.model.ScheduleTemplateEntry, String> cDesc =
                new TableColumn<>(t("labor.schedule.col.description"));
        cDesc.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().description()));
        TableColumn<com.benjagest.ui.model.ScheduleTemplateEntry, String> cBlocks =
                new TableColumn<>(t("labor.schedule.col.blocks"));
        cBlocks.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().blocks())));
        cBlocks.setPrefWidth(80);
        TableColumn<com.benjagest.ui.model.ScheduleTemplateEntry, String> cAssign =
                new TableColumn<>(t("labor.schedule.col.assignments"));
        cAssign.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().assignments())));
        cAssign.setPrefWidth(90);
        TableColumn<com.benjagest.ui.model.ScheduleTemplateEntry, String> cActive =
                new TableColumn<>(t("labor.schedule.col.active"));
        cActive.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().active() ? "✓" : "—"));
        cActive.setPrefWidth(70);
        table.getColumns().addAll(java.util.List.of(cName, cDesc, cBlocks, cAssign, cActive));
        VBox.setVgrow(table, Priority.ALWAYS);

        Runnable reload = () -> {
            Task<java.util.List<com.benjagest.ui.model.ScheduleTemplateEntry>> task = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.ScheduleTemplateEntry> call() throws Exception {
                    return laborApiClient.listScheduleTemplates();
                }
            };
            task.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(task.getValue())));
            task.setOnFailed(ev -> showError(t("labor.schedule.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "schedule-load");
        };

        Button newBtn = new Button(t("labor.schedule.new"));
        newBtn.getStyleClass().add("primary-button");
        newBtn.setGraphic(icon("fas-plus"));
        newBtn.setOnAction(e -> showScheduleTemplateDialog(null, reload));

        Button editBtn = new Button(t("labor.schedule.edit"));
        editBtn.setGraphic(icon("fas-pen"));
        editBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) showScheduleTemplateDialog(sel, reload);
        });

        Button blocksBtn = new Button(t("labor.schedule.blocks"));
        blocksBtn.setGraphic(icon("fas-clock"));
        blocksBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) showScheduleBlocksDialog(sel, reload);
        });

        Button assignBtn = new Button(t("labor.schedule.assign"));
        assignBtn.setGraphic(icon("fas-user-plus"));
        assignBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) showScheduleAssignDialog(sel, employees, reload);
        });

        Button delBtn = new Button(t("labor.schedule.delete"));
        delBtn.setGraphic(icon("fas-trash"));
        delBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    t("labor.schedule.delete.confirm").replace("{name}", sel.name()),
                    ButtonType.YES, ButtonType.NO);
            confirm.setHeaderText(t("labor.schedule.delete"));
            confirm.showAndWait().ifPresent(bt -> {
                if (bt != ButtonType.YES) return;
                Task<Void> task = new Task<>() {
                    @Override protected Void call() throws Exception {
                        laborApiClient.deleteScheduleTemplate(sel.id());
                        return null;
                    }
                };
                task.setOnSucceeded(ev -> { toast(t("labor.schedule.deleted")); reload.run(); });
                task.setOnFailed(ev -> showError(t("labor.schedule.fail.title"),
                        task.getException() == null ? "" : task.getException().getMessage()));
                start(task, "schedule-delete");
            });
        });

        HBox actions = new HBox(8, newBtn, editBtn, blocksBtn, assignBtn, delBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        reload.run();
        content.getChildren().addAll(hint, actions, table);
        return content;
    }

    private void showScheduleTemplateDialog(
            com.benjagest.ui.model.ScheduleTemplateEntry existing, Runnable onSaved) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? t("labor.schedule.new") : t("labor.schedule.edit"));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField name = new TextField(existing == null ? "" : existing.name());
        TextField desc = new TextField(existing == null ? "" : existing.description());
        CheckBox active = new CheckBox(t("labor.schedule.col.active"));
        active.setSelected(existing == null || existing.active());

        javafx.scene.layout.GridPane gp = new javafx.scene.layout.GridPane();
        gp.setHgap(8); gp.setVgap(8); gp.setPadding(new Insets(12));
        gp.add(new Label(t("labor.schedule.col.name")), 0, 0); gp.add(name, 1, 0);
        gp.add(new Label(t("labor.schedule.col.description")), 0, 1); gp.add(desc, 1, 1);
        if (existing != null) gp.add(active, 1, 2);
        dialog.getDialogPane().setContent(gp);

        dialog.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            if (name.getText() == null || name.getText().isBlank()) {
                showError(t("labor.schedule.fail.title"), t("labor.schedule.name.required"));
                return null;
            }
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    if (existing == null) {
                        laborApiClient.createScheduleTemplate(name.getText().trim(), desc.getText());
                    } else {
                        laborApiClient.updateScheduleTemplate(existing.id(), name.getText().trim(),
                                desc.getText(), active.isSelected());
                    }
                    return null;
                }
            };
            task.setOnSucceeded(ev -> { toast(t("labor.schedule.saved")); onSaved.run(); });
            task.setOnFailed(ev -> showError(t("labor.schedule.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "schedule-save");
            return null;
        });
        dialog.showAndWait();
    }

    private void showScheduleBlocksDialog(
            com.benjagest.ui.model.ScheduleTemplateEntry template, Runnable onSaved) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(t("labor.schedule.blocks") + " — " + template.name());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(640);

        Label hint = new Label(t("labor.schedule.blocks.hint"));
        hint.setWrapText(true); hint.getStyleClass().add("settings-hint");

        // Todos los bloques (todos los días) en memoria; se guardan al Aceptar.
        final javafx.collections.ObservableList<com.benjagest.ui.model.ScheduleBlockEntry> all =
                FXCollections.observableArrayList();
        final int[] selDay = {1};

        // --- Selector de día (Lun..Dom) con contador de bloques por día ---
        javafx.scene.control.ToggleGroup tg = new javafx.scene.control.ToggleGroup();
        javafx.scene.control.ToggleButton[] dayBtns = new javafx.scene.control.ToggleButton[8];
        HBox dayBar = new HBox(6);
        dayBar.setAlignment(Pos.CENTER_LEFT);

        // --- Tabla SOLO del día seleccionado ---
        final javafx.collections.ObservableList<com.benjagest.ui.model.ScheduleBlockEntry> dayRows =
                FXCollections.observableArrayList();
        TableView<com.benjagest.ui.model.ScheduleBlockEntry> table = new TableView<>(dayRows);
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(200);
        table.setPlaceholder(new Label(t("labor.schedule.day.empty")));
        TableColumn<com.benjagest.ui.model.ScheduleBlockEntry, String> tc =
                new TableColumn<>(t("labor.schedule.col.type"));
        tc.setCellValueFactory(c -> new SimpleStringProperty(
                "BREAK".equals(c.getValue().blockType()) ? t("labor.schedule.type.break")
                        : t("labor.schedule.type.work")));
        TableColumn<com.benjagest.ui.model.ScheduleBlockEntry, String> sc =
                new TableColumn<>(t("labor.workdays.col.in"));
        sc.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().startTime()));
        TableColumn<com.benjagest.ui.model.ScheduleBlockEntry, String> ec =
                new TableColumn<>(t("labor.workdays.col.out"));
        ec.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().endTime()));
        table.getColumns().addAll(java.util.List.of(tc, sc, ec));

        // --- Formulario de añadir bloque al día seleccionado ---
        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList("WORK", "BREAK"));
        type.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) {
                return "BREAK".equals(s) ? t("labor.schedule.type.break") : t("labor.schedule.type.work");
            }
            @Override public String fromString(String s) { return null; }
        });
        type.getSelectionModel().selectFirst();
        type.setPrefWidth(150);
        TextField start = new TextField(); start.setPromptText("09:00"); start.setPrefWidth(80);
        TextField end = new TextField(); end.setPromptText("14:00"); end.setPrefWidth(80);
        com.benjagest.ui.support.EditableCells.installTimeMask(start);
        com.benjagest.ui.support.EditableCells.installTimeMask(end);

        java.util.Comparator<com.benjagest.ui.model.ScheduleBlockEntry> byStart =
                java.util.Comparator.comparing(com.benjagest.ui.model.ScheduleBlockEntry::startTime);

        Runnable refreshDay = () -> {
            dayRows.setAll(all.stream().filter(b -> b.weekday() == selDay[0]).sorted(byStart).toList());
            for (int d = 1; d <= 7; d++) {
                final int dd = d;
                long c = all.stream().filter(b -> b.weekday() == dd).count();
                dayBtns[d].setText(weekdayName(d) + (c > 0 ? " (" + c + ")" : ""));
            }
            // Pre-rellena la entrada del siguiente bloque con la salida del último (contiguo).
            start.setText(dayRows.isEmpty() ? "09:00" : dayRows.get(dayRows.size() - 1).endTime());
            end.clear();
        };

        for (int d = 1; d <= 7; d++) {
            javafx.scene.control.ToggleButton tb = new javafx.scene.control.ToggleButton(weekdayName(d));
            tb.setToggleGroup(tg);
            final int dd = d;
            tb.setOnAction(e -> { if (tb.isSelected()) { selDay[0] = dd; refreshDay.run(); } });
            dayBtns[d] = tb;
            dayBar.getChildren().add(tb);
        }
        dayBtns[1].setSelected(true);

        Button addRow = new Button(t("labor.schedule.block.add"));
        addRow.setGraphic(icon("fas-plus"));
        addRow.getStyleClass().add("primary-button");
        addRow.setOnAction(e -> {
            if (!isHhmm(start.getText()) || !isHhmm(end.getText())) {
                showError(t("labor.schedule.fail.title"), t("labor.schedule.time.invalid"));
                return;
            }
            java.time.LocalTime s = java.time.LocalTime.parse(start.getText().trim());
            java.time.LocalTime en = java.time.LocalTime.parse(end.getText().trim());
            if (!en.isAfter(s)) {
                showError(t("labor.schedule.fail.title"), t("labor.schedule.block.order"));
                return;
            }
            for (var b : dayRows) {
                if (s.isBefore(java.time.LocalTime.parse(b.endTime()))
                        && java.time.LocalTime.parse(b.startTime()).isBefore(en)) {
                    showError(t("labor.schedule.fail.title"), t("labor.schedule.block.overlap"));
                    return;
                }
            }
            all.add(new com.benjagest.ui.model.ScheduleBlockEntry(
                    null, selDay[0], type.getValue(), start.getText().trim(), end.getText().trim()));
            refreshDay.run();
        });
        Button delRow = new Button(t("labor.schedule.block.remove"));
        delRow.setGraphic(icon("fas-trash"));
        delRow.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) { all.remove(sel); refreshDay.run(); }
        });
        HBox form = new HBox(8,
                formLabel("labor.schedule.col.type"), type,
                formLabel("labor.workdays.col.in"), start,
                formLabel("labor.workdays.col.out"), end, addRow, delRow);
        form.setAlignment(Pos.CENTER_LEFT);

        // --- Acciones de día: copiar a otros días / vaciar ---
        Button copyBtn = new Button(t("labor.schedule.copy_days"));
        copyBtn.setGraphic(icon("fas-copy"));
        copyBtn.setOnAction(e -> {
            if (dayRows.isEmpty()) { showInfo(t("labor.schedule.copy_days"), t("labor.schedule.no_blocks")); return; }
            Dialog<Void> cd = new Dialog<>();
            cd.setTitle(t("labor.schedule.copy_days"));
            cd.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            Label ch = new Label(t("labor.schedule.copy_days.hint")
                    .replace("{day}", weekdayName(selDay[0])));
            ch.setWrapText(true); ch.getStyleClass().add("settings-hint");
            VBox checks = new VBox(6);
            java.util.List<CheckBox> cbs = new java.util.ArrayList<>();
            for (int d = 1; d <= 7; d++) {
                if (d == selDay[0]) continue;
                CheckBox cb = new CheckBox(weekdayName(d));
                cb.setUserData(d);
                cbs.add(cb);
                checks.getChildren().add(cb);
            }
            VBox cbox = new VBox(10, ch, checks);
            cbox.setPadding(new Insets(12));
            cd.getDialogPane().setContent(cbox);
            cd.setResultConverter(bt -> {
                if (bt != ButtonType.OK) return null;
                java.util.List<com.benjagest.ui.model.ScheduleBlockEntry> src =
                        new java.util.ArrayList<>(dayRows);
                for (CheckBox cb : cbs) {
                    if (!cb.isSelected()) continue;
                    int dd = (Integer) cb.getUserData();
                    all.removeIf(b -> b.weekday() == dd);
                    for (var b : src) {
                        all.add(new com.benjagest.ui.model.ScheduleBlockEntry(
                                null, dd, b.blockType(), b.startTime(), b.endTime()));
                    }
                }
                refreshDay.run();
                return null;
            });
            cd.showAndWait();
        });
        Button clearBtn = new Button(t("labor.schedule.clear_day"));
        clearBtn.setGraphic(icon("fas-eraser"));
        clearBtn.setOnAction(e -> {
            all.removeIf(b -> b.weekday() == selDay[0]);
            refreshDay.run();
        });
        HBox dayActions = new HBox(8, copyBtn, clearBtn);
        dayActions.setAlignment(Pos.CENTER_LEFT);

        Label dayLbl = new Label(t("labor.schedule.day") + ":");
        dayLbl.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox dayRow = new HBox(8, dayLbl, dayBar);
        dayRow.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, hint, dayRow, table, form, dayActions);
        box.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(box);

        // Carga inicial de los bloques existentes.
        Task<java.util.List<com.benjagest.ui.model.ScheduleBlockEntry>> load = new Task<>() {
            @Override protected java.util.List<com.benjagest.ui.model.ScheduleBlockEntry> call() throws Exception {
                return laborApiClient.getScheduleBlocks(template.id());
            }
        };
        load.setOnSucceeded(ev -> { all.setAll(load.getValue()); refreshDay.run(); });
        load.setOnFailed(ev -> showError(t("labor.schedule.fail.title"),
                load.getException() == null ? "" : load.getException().getMessage()));
        start(load, "schedule-blocks-load");
        refreshDay.run();

        dialog.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            java.util.List<com.benjagest.ui.model.ScheduleBlockEntry> snapshot =
                    new java.util.ArrayList<>(all);
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.replaceScheduleBlocks(template.id(), snapshot);
                    return null;
                }
            };
            task.setOnSucceeded(ev -> { toast(t("labor.schedule.saved")); onSaved.run(); });
            task.setOnFailed(ev -> showError(t("labor.schedule.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "schedule-blocks-save");
            return null;
        });
        dialog.showAndWait();
    }

    private void showScheduleAssignDialog(
            com.benjagest.ui.model.ScheduleTemplateEntry template,
            java.util.List<com.benjagest.ui.model.EmployeeEntry> employees, Runnable onSaved) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(t("labor.schedule.assign") + " — " + template.name());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setPrefWidth(660);

        javafx.collections.ObservableList<com.benjagest.ui.model.ScheduleAssignmentEntry> rows =
                FXCollections.observableArrayList();
        TableView<com.benjagest.ui.model.ScheduleAssignmentEntry> table = new TableView<>(rows);
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(200);
        TableColumn<com.benjagest.ui.model.ScheduleAssignmentEntry, String> ec =
                new TableColumn<>(t("labor.shifts.col.employee"));
        ec.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().employeeName()));
        TableColumn<com.benjagest.ui.model.ScheduleAssignmentEntry, String> fc =
                new TableColumn<>(t("labor.schedule.col.from"));
        fc.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().effectiveFrom()));
        fc.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.ScheduleAssignmentEntry, String> toc =
                new TableColumn<>(t("labor.schedule.col.to"));
        toc.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().effectiveTo() == null || c.getValue().effectiveTo().isBlank()
                        ? "—" : c.getValue().effectiveTo()));
        toc.setPrefWidth(110);
        table.getColumns().addAll(java.util.List.of(ec, fc, toc));

        Runnable reloadAssign = () -> {
            Task<java.util.List<com.benjagest.ui.model.ScheduleAssignmentEntry>> task = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.ScheduleAssignmentEntry> call() throws Exception {
                    return laborApiClient.listScheduleAssignments(template.id());
                }
            };
            task.setOnSucceeded(ev -> rows.setAll(task.getValue()));
            task.setOnFailed(ev -> showError(t("labor.schedule.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "schedule-assign-load");
        };

        ComboBox<com.benjagest.ui.model.EmployeeEntry> empCombo =
                new ComboBox<>(FXCollections.observableArrayList(employees));
        empCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.EmployeeEntry e) {
                return e == null ? "" : e.fullName();
            }
            @Override public com.benjagest.ui.model.EmployeeEntry fromString(String s) { return null; }
        });
        empCombo.setPrefWidth(280);  // nombres largos (apellidos + nombre) se truncaban a "..."
        DatePicker fromP = new DatePicker(java.time.LocalDate.now());
        DatePicker toP = new DatePicker();
        fromP.setPrefWidth(130);
        toP.setPrefWidth(130);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(fromP);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(toP);
        Button addBtn = new Button(t("labor.schedule.assign.add"));
        addBtn.getStyleClass().add("primary-button");
        addBtn.setGraphic(icon("fas-plus"));
        addBtn.setOnAction(e -> {
            var emp = empCombo.getValue();
            if (emp == null) { showError(t("labor.schedule.fail.title"), t("labor.schedule.employee.required")); return; }
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.assignSchedule(template.id(), emp.id(), fromP.getValue(), toP.getValue());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> { toast(t("labor.schedule.saved")); reloadAssign.run(); onSaved.run(); });
            task.setOnFailed(ev -> showError(t("labor.schedule.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "schedule-assign-add");
        });
        Button removeBtn = new Button(t("labor.schedule.assign.remove"));
        removeBtn.setGraphic(icon("fas-trash"));
        removeBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.removeScheduleAssignment(sel.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> { toast(t("labor.schedule.deleted")); reloadAssign.run(); onSaved.run(); });
            task.setOnFailed(ev -> showError(t("labor.schedule.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "schedule-assign-remove");
        });

        HBox form = new HBox(8, empCombo,
                formLabel("labor.schedule.col.from"), fromP,
                formLabel("labor.schedule.col.to"), toP, addBtn, removeBtn);
        form.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, table, form);
        box.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(box);

        reloadAssign.run();
        dialog.showAndWait();
    }

    private String weekdayName(int wd) {
        return switch (wd) {
            case 1 -> t("weekday.mon");
            case 2 -> t("weekday.tue");
            case 3 -> t("weekday.wed");
            case 4 -> t("weekday.thu");
            case 5 -> t("weekday.fri");
            case 6 -> t("weekday.sat");
            case 7 -> t("weekday.sun");
            default -> String.valueOf(wd);
        };
    }

    private boolean isHhmm(String s) {
        return s != null && s.trim().matches("([01]?\\d|2[0-3]):[0-5]\\d");
    }
}
