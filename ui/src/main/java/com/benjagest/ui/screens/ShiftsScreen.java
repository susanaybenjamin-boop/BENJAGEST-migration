package com.benjagest.ui.screens;

import com.benjagest.ui.service.AltaApiClient;
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
 * NOM-6c — Partes / Jornadas (sub-pestaña "Tiempo" del módulo Laboral, bloque
 * UIR; PORT-2). Extraída del God Object: jornada REAL calculada desde fichajes
 * (JOR-1), Planificado-vs-Real descriptivo (JOR-4 + FICHA-REVIEW "dar por
 * bueno") e histórico de partes ({@code work_logs}). Movimiento puro: mismo
 * comportamiento, mismas claves i18n. Depende de {@link LaborApiClient}
 * (jornadas/plan-vs-real), {@link AltaApiClient} (histórico work_logs) y los
 * helpers de {@link ScreenBase}.
 */
public class ShiftsScreen extends ScreenBase {

    private final LaborApiClient laborApiClient;
    private final AltaApiClient altaApiClient;

    public ShiftsScreen(LaborApiClient laborApiClient, AltaApiClient altaApiClient,
                        Function<String, String> tt, Router router) {
        super(tt, router);
        this.laborApiClient = laborApiClient;
        this.altaApiClient = altaApiClient;
    }

    public Node buildShiftsTab(java.util.List<com.benjagest.ui.model.EmployeeEntry> employees) {
        VBox content = new VBox(16);
        content.setPadding(new Insets(16));

        java.util.Map<String, String> empById = new java.util.HashMap<>();
        for (var e : employees) empById.put(e.id(), e.fullName());

        Label hint = new Label(t("labor.shifts.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        // JOR-1 — Jornadas fichadas: jornada REAL calculada desde los fichajes.
        Node plannedSection = buildWorkdaysSection(empById);

        // JOR-4 — Planificado vs Real: cruza el horario asignado con lo fichado.
        Node planVsRealSection = buildPlanVsRealSection(empById);

        // Sección histórico — partes ya en BD (sincronizados desde el
        // work_logs viejo o futuros llegando desde el móvil).
        Label historyTitle = label(t("labor.shifts.history.title"), "settings-section-title");
        Label historyHint = new Label(t("labor.shifts.history.hint"));
        historyHint.setWrapText(true);
        historyHint.getStyleClass().add("settings-hint");

        java.time.LocalDate now = java.time.LocalDate.now();
        DatePicker fromPick = new DatePicker(now.withDayOfMonth(1));
        DatePicker toPick = new DatePicker(now.withDayOfMonth(1).plusMonths(1).minusDays(1));
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(fromPick);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(toPick);
        Button reloadBtn = new Button(t("labor.shifts.reload"));
        reloadBtn.setGraphic(icon("fas-sync-alt"));

        HBox filters = new HBox(8,
                new Label(t("labor.shifts.from")), fromPick,
                new Label(t("labor.shifts.to")), toPick,
                reloadBtn);
        filters.setAlignment(Pos.CENTER_LEFT);

        TableView<com.benjagest.ui.model.WorkLogEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.shifts.empty")));

        TableColumn<com.benjagest.ui.model.WorkLogEntry, String> cDate =
                new TableColumn<>(t("labor.shifts.col.date"));
        cDate.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().logDate()));
        cDate.setPrefWidth(110);
        cDate.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.WorkLogEntry, String> cEmp =
                new TableColumn<>(t("labor.shifts.col.employee"));
        cEmp.setCellValueFactory(c -> new SimpleStringProperty(
                empById.getOrDefault(c.getValue().employeeId(),
                        shortId(c.getValue().employeeId()))));
        TableColumn<com.benjagest.ui.model.WorkLogEntry, String> cMin =
                new TableColumn<>(t("labor.shifts.col.minutes"));
        cMin.setCellValueFactory(c -> new SimpleStringProperty(
                String.valueOf(c.getValue().minutesWorked())));
        cMin.setPrefWidth(90);
        cMin.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.WorkLogEntry, String> cDesc =
                new TableColumn<>(t("labor.shifts.col.description"));
        cDesc.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().description()));
        TableColumn<com.benjagest.ui.model.WorkLogEntry, String> cBill =
                new TableColumn<>(t("labor.shifts.col.billable"));
        cBill.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().billable() ? "✓" : "—"));
        cBill.setPrefWidth(80);
        TableColumn<com.benjagest.ui.model.WorkLogEntry, String> cAmount =
                new TableColumn<>(t("labor.shifts.col.amount"));
        cAmount.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().billableAmount() == null ? ""
                        : money(c.getValue().billableAmount().toPlainString())));
        cAmount.setPrefWidth(100);
        cAmount.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.WorkLogEntry, String> cStatus =
                new TableColumn<>(t("labor.shifts.col.status"));
        cStatus.setCellValueFactory(c -> new SimpleStringProperty(localizedEnum("worklog_status", c.getValue().status())));
        cStatus.setPrefWidth(90);

        table.getColumns().addAll(java.util.List.of(cDate, cEmp, cMin, cDesc, cBill, cAmount, cStatus));

        Runnable reload = () -> {
            java.time.LocalDate from = fromPick.getValue();
            java.time.LocalDate to = toPick.getValue();
            if (from == null || to == null) return;
            Task<java.util.List<com.benjagest.ui.model.WorkLogEntry>> task = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.WorkLogEntry> call() throws Exception {
                    return altaApiClient.listWorkLogs(from, to, null, null, false);
                }
            };
            task.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(task.getValue())));
            task.setOnFailed(ev -> showError(t("labor.shifts.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "shifts-load");
        };
        reloadBtn.setOnAction(ev -> reload.run());
        reload.run();

        VBox.setVgrow(table, Priority.ALWAYS);
        content.getChildren().addAll(
                hint,
                plannedSection,
                new Separator(),
                planVsRealSection,
                new Separator(),
                historyTitle, historyHint,
                filters, table);
        return content;
    }

    private Node buildWorkdaysSection(java.util.Map<String, String> empById) {
        VBox box = new VBox(8);
        box.getStyleClass().add("settings-section");
        Label title = label(t("labor.workdays.title"), "settings-section-title");
        Label hint = new Label(t("labor.workdays.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        java.time.LocalDate now = java.time.LocalDate.now();
        DatePicker fromPick = new DatePicker(now.withDayOfMonth(1));
        DatePicker toPick = new DatePicker(now);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(fromPick);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(toPick);
        Button reloadBtn = new Button(t("labor.workdays.reload"));
        reloadBtn.setGraphic(icon("fas-sync-alt"));
        HBox filters = new HBox(8,
                new Label(t("labor.shifts.from")), fromPick,
                new Label(t("labor.shifts.to")), toPick, reloadBtn);
        filters.setAlignment(Pos.CENTER_LEFT);

        TableView<com.benjagest.ui.model.WorkdayEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.workdays.empty")));
        table.setPrefHeight(220);

        TableColumn<com.benjagest.ui.model.WorkdayEntry, String> cDate =
                new TableColumn<>(t("labor.shifts.col.date"));
        cDate.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().date()));
        cDate.setPrefWidth(110);
        cDate.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.WorkdayEntry, String> cEmp =
                new TableColumn<>(t("labor.shifts.col.employee"));
        cEmp.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().employeeName() != null && !c.getValue().employeeName().isBlank()
                        ? c.getValue().employeeName()
                        : empById.getOrDefault(c.getValue().employeeId(), shortId(c.getValue().employeeId()))));
        TableColumn<com.benjagest.ui.model.WorkdayEntry, String> cIn =
                new TableColumn<>(t("labor.workdays.col.in"));
        cIn.setCellValueFactory(c -> new SimpleStringProperty(hhmm(c.getValue().firstIn())));
        cIn.setPrefWidth(90);
        TableColumn<com.benjagest.ui.model.WorkdayEntry, String> cOut =
                new TableColumn<>(t("labor.workdays.col.out"));
        cOut.setCellValueFactory(c -> new SimpleStringProperty(hhmm(c.getValue().lastOut())));
        cOut.setPrefWidth(90);
        TableColumn<com.benjagest.ui.model.WorkdayEntry, String> cWorked =
                new TableColumn<>(t("labor.workdays.col.worked"));
        cWorked.setCellValueFactory(c -> new SimpleStringProperty(fmtMinutes(c.getValue().workedMinutes())));
        cWorked.setPrefWidth(100);
        TableColumn<com.benjagest.ui.model.WorkdayEntry, String> cPause =
                new TableColumn<>(t("labor.workdays.col.pause"));
        cPause.setCellValueFactory(c -> new SimpleStringProperty(fmtMinutes(c.getValue().pauseMinutes())));
        cPause.setPrefWidth(100);
        table.getColumns().addAll(java.util.List.of(cDate, cEmp, cIn, cOut, cWorked, cPause));

        Runnable reload = () -> {
            java.time.LocalDate from = fromPick.getValue();
            java.time.LocalDate to = toPick.getValue();
            if (from == null || to == null) return;
            Task<java.util.List<com.benjagest.ui.model.WorkdayEntry>> task = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.WorkdayEntry> call() throws Exception {
                    return laborApiClient.listWorkdays(from, to, null);
                }
            };
            task.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(task.getValue())));
            task.setOnFailed(ev -> showError(t("labor.workdays.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "workdays-load");
        };
        reloadBtn.setOnAction(ev -> reload.run());
        reload.run();

        box.getChildren().addAll(title, hint, filters, table);
        return box;
    }

    /**
     * JOR-4 — Planificado vs Real. Informe DESCRIPTIVO: por empleado y día cruza
     * los minutos del horario asignado (bloques WORK, JOR-2) con lo realmente
     * fichado (JOR-1) y muestra la diferencia (verde = trabajó más, rojo = menos).
     * No opina de tolerancias ni festivos (eso es "excepciones de calendario").
     */
    private Node buildPlanVsRealSection(java.util.Map<String, String> empById) {
        VBox box = new VBox(8);
        box.getStyleClass().add("settings-section");
        Label title = label(t("labor.planreal.title"), "settings-section-title");
        Label hint = new Label(t("labor.planreal.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        java.time.LocalDate now = java.time.LocalDate.now();
        DatePicker fromPick = new DatePicker(now.withDayOfMonth(1));
        DatePicker toPick = new DatePicker(now);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(fromPick);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(toPick);
        Button reloadBtn = new Button(t("labor.workdays.reload"));
        reloadBtn.setGraphic(icon("fas-sync-alt"));
        HBox filters = new HBox(8,
                new Label(t("labor.shifts.from")), fromPick,
                new Label(t("labor.shifts.to")), toPick, reloadBtn);
        filters.setAlignment(Pos.CENTER_LEFT);

        TableView<com.benjagest.ui.model.PlanVsRealEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.planreal.empty")));
        table.setPrefHeight(220);

        TableColumn<com.benjagest.ui.model.PlanVsRealEntry, String> cDate =
                new TableColumn<>(t("labor.shifts.col.date"));
        cDate.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().holiday()
                        ? c.getValue().date() + "  · " + t("labor.planreal.holiday")
                        : c.getValue().date()));
        cDate.setPrefWidth(150);
        cDate.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.PlanVsRealEntry, String> cEmp =
                new TableColumn<>(t("labor.shifts.col.employee"));
        cEmp.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().employeeName() != null && !c.getValue().employeeName().isBlank()
                        ? c.getValue().employeeName()
                        : empById.getOrDefault(c.getValue().employeeId(), shortId(c.getValue().employeeId()))));
        TableColumn<com.benjagest.ui.model.PlanVsRealEntry, String> cPlanned =
                new TableColumn<>(t("labor.planreal.col.planned"));
        cPlanned.setCellValueFactory(c -> new SimpleStringProperty(fmtMinutes(c.getValue().plannedMinutes())));
        cPlanned.setPrefWidth(100);
        TableColumn<com.benjagest.ui.model.PlanVsRealEntry, String> cWorked =
                new TableColumn<>(t("labor.workdays.col.worked"));
        cWorked.setCellValueFactory(c -> new SimpleStringProperty(fmtMinutes(c.getValue().workedMinutes())));
        cWorked.setPrefWidth(100);
        TableColumn<com.benjagest.ui.model.PlanVsRealEntry, String> cDiff =
                new TableColumn<>(t("labor.planreal.col.diff"));
        cDiff.setCellValueFactory(c -> new SimpleStringProperty(fmtMinutesSigned(c.getValue().diffMinutes())));
        cDiff.setPrefWidth(110);
        cDiff.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); setStyle(""); return; }
                setText(v);
                if (v.startsWith("-")) setStyle("-fx-text-fill: #c0392b;");       // trabajó menos
                else if (v.startsWith("+")) setStyle("-fx-text-fill: #1e7e34;");  // trabajó más
                else setStyle("");
            }
        });
        // FICHA-REVIEW — Estado: Festivo / ✓ Revisado / ⚠ Incidencia / OK.
        TableColumn<com.benjagest.ui.model.PlanVsRealEntry, String> cState =
                new TableColumn<>(t("labor.planreal.col.state"));
        cState.setCellValueFactory(c -> new SimpleStringProperty(planRealState(c.getValue())));
        cState.setPrefWidth(130);
        cState.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); setStyle(""); return; }
                setText(v);
                if (v.startsWith("✓")) setStyle("-fx-text-fill: #1e7e34; -fx-font-weight: bold;");
                else if (v.startsWith("⚠")) setStyle("-fx-text-fill: #c0392b; -fx-font-weight: bold;");
                else setStyle("-fx-text-fill: #6b7280;");
            }
        });
        table.getColumns().addAll(java.util.List.of(cDate, cEmp, cPlanned, cWorked, cDiff, cState));

        Runnable reload = () -> {
            java.time.LocalDate from = fromPick.getValue();
            java.time.LocalDate to = toPick.getValue();
            if (from == null || to == null) return;
            Task<java.util.List<com.benjagest.ui.model.PlanVsRealEntry>> task = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.PlanVsRealEntry> call() throws Exception {
                    return laborApiClient.listPlanVsReal(from, to, null);
                }
            };
            task.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(task.getValue())));
            task.setOnFailed(ev -> showError(t("labor.planreal.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "plan-vs-real-load");
        };
        reloadBtn.setOnAction(ev -> reload.run());

        // FICHA-REVIEW — "Dar por bueno" / "Quitar revisado" del día seleccionado.
        Button approveBtn = new Button(t("labor.planreal.action.approve"));
        approveBtn.setGraphic(icon("fas-check"));
        approveBtn.getStyleClass().add("button-secondary");
        approveBtn.setDisable(true);
        Button unapproveBtn = new Button(t("labor.planreal.action.unapprove"));
        unapproveBtn.setGraphic(icon("fas-undo"));
        unapproveBtn.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            approveBtn.setDisable(nv == null || nv.holiday() || nv.reviewed());
            unapproveBtn.setDisable(nv == null || !nv.reviewed());
        });
        approveBtn.setOnAction(ev -> doReviewPlanReal(table, true, reload));
        unapproveBtn.setOnAction(ev -> doReviewPlanReal(table, false, reload));
        HBox reviewActions = new HBox(8, approveBtn, unapproveBtn);
        reviewActions.setAlignment(Pos.CENTER_LEFT);

        reload.run();

        VBox.setVgrow(table, Priority.ALWAYS);
        box.getChildren().addAll(title, hint, filters, reviewActions, table);
        return box;
    }

    /** Estado de un día en Planificado-vs-Real (FICHA-REVIEW). */
    private String planRealState(com.benjagest.ui.model.PlanVsRealEntry e) {
        if (e.holiday()) return t("labor.planreal.holiday");
        if (e.reviewed()) return "✓ " + t("labor.planreal.state.reviewed");
        if (e.diffMinutes() < 0) return "⚠ " + t("labor.planreal.state.incidence");
        return t("labor.planreal.state.ok");
    }

    private void doReviewPlanReal(TableView<com.benjagest.ui.model.PlanVsRealEntry> table,
                                   boolean reviewed, Runnable reload) {
        var sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                laborApiClient.reviewPlanVsReal(sel.employeeId(), sel.date(), reviewed, null);
                return null;
            }
        };
        task.setOnSucceeded(e -> reload.run());
        task.setOnFailed(e -> showError(t("labor.planreal.fail.title"),
                task.getException() == null ? "" : task.getException().getMessage()));
        start(task, "plan-vs-real-review");
    }

    /** Diferencia de minutos con signo: "+1h 30m" / "-0h 45m" / "0h 00m". */
    private String fmtMinutesSigned(int min) {
        String sign = min > 0 ? "+" : (min < 0 ? "-" : "");
        int a = Math.abs(min);
        return sign + String.format("%dh %02dm", a / 60, a % 60);
    }

    private String fmtMinutes(int min) {
        if (min <= 0) return "—";
        return String.format("%dh %02dm", min / 60, min % 60);
    }

    private String hhmm(String iso) {
        if (iso == null || iso.isBlank()) return "—";
        try {
            return java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
                    .toLocalTime().withSecond(0).withNano(0).toString();
        } catch (Exception e) {
            return iso.length() >= 16 ? iso.substring(11, 16) : iso;
        }
    }
}
