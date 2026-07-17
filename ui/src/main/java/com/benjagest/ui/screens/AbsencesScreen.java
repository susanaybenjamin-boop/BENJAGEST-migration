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
 * NOM-4 — Categoría "Ausencias" del módulo Laboral (bloque UIR). Reúne las tres
 * pestañas de ausencia que estaban en el God Object: solicitudes de ausencia
 * pedidas por el empleado (aprobar/rechazar + adjuntos), bajas médicas (IT) y
 * vacaciones. Movimiento puro: mismo comportamiento, mismas claves i18n.
 * Depende de {@link LaborApiClient} (solicitudes + vacaciones), {@link
 * AltaApiClient} (bajas IT) y los helpers de {@link ScreenBase}. El owner de los
 * diálogos de solicitudes era el {@code root} del shell; ahora se resuelve por
 * el propio nodo montado ({@code viewRoot}).
 */
public class AbsencesScreen extends ScreenBase {

    private final LaborApiClient laborApiClient;
    private final AltaApiClient altaApiClient;
    private Node viewRoot;

    public AbsencesScreen(LaborApiClient laborApiClient, AltaApiClient altaApiClient,
                          Function<String, String> tt, Router router) {
        super(tt, router);
        this.laborApiClient = laborApiClient;
        this.altaApiClient = altaApiClient;
    }

    private static String humanizeBackendError(String raw) {
        return com.benjagest.ui.support.BackendErrors.humanize(raw);
    }

    private javafx.stage.Window window() {
        return viewRoot == null || viewRoot.getScene() == null
                ? null : viewRoot.getScene().getWindow();
    }

    // ===================================================================
    //  Solicitudes de ausencia (MEMP-4) — aprobar/rechazar + adjuntos
    // ===================================================================

    public Node buildLeaveRequestsTab(java.util.List<com.benjagest.ui.model.EmployeeEntry> allEmployees) {
        Label hint = new Label(t("labor.leavereq.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        ComboBox<String> statusFilter = new ComboBox<>();
        statusFilter.getItems().addAll("", "REQUESTED", "APPROVED", "REJECTED", "CANCELLED");
        statusFilter.setValue("REQUESTED");
        statusFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) {
                return s == null || s.isBlank() ? t("labor.leavereq.filter.all")
                        : t("labor.leavereq.status." + s.toLowerCase());
            }
            @Override public String fromString(String s) { return null; }
        });

        TableView<com.benjagest.ui.model.LeaveRequestEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.leavereq.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.LeaveRequestEntry, String> cEmp = new TableColumn<>(t("labor.leavereq.col.employee"));
        cEmp.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().employeeName()));
        cEmp.setPrefWidth(170);
        TableColumn<com.benjagest.ui.model.LeaveRequestEntry, String> cKind = new TableColumn<>(t("labor.leavereq.col.type"));
        cKind.setCellValueFactory(c -> new SimpleStringProperty(t("labor.leavereq.kind." + c.getValue().kind().toLowerCase())));
        cKind.setPrefWidth(130);
        TableColumn<com.benjagest.ui.model.LeaveRequestEntry, String> cFrom = new TableColumn<>(t("labor.leavereq.col.from"));
        cFrom.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().startDate()));
        cFrom.setPrefWidth(100);
        TableColumn<com.benjagest.ui.model.LeaveRequestEntry, String> cTo = new TableColumn<>(t("labor.leavereq.col.to"));
        cTo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().endDate()));
        cTo.setPrefWidth(100);
        TableColumn<com.benjagest.ui.model.LeaveRequestEntry, String> cDays = new TableColumn<>(t("labor.leavereq.col.days"));
        cDays.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().days()));
        cDays.setPrefWidth(60);
        TableColumn<com.benjagest.ui.model.LeaveRequestEntry, String> cStatus = new TableColumn<>(t("labor.leavereq.col.status"));
        cStatus.setCellValueFactory(c -> new SimpleStringProperty(t("labor.leavereq.status." + c.getValue().status().toLowerCase())));
        cStatus.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.LeaveRequestEntry, String> cAtt = new TableColumn<>(t("labor.leavereq.col.attachments"));
        cAtt.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().attachmentCount() > 0
                ? "📎 " + c.getValue().attachmentCount() : ""));
        cAtt.setPrefWidth(90);
        TableColumn<com.benjagest.ui.model.LeaveRequestEntry, String> cReason = new TableColumn<>(t("labor.leavereq.col.reason"));
        cReason.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().reason() == null ? "" : c.getValue().reason())
                + (c.getValue().reviewNotes() == null || c.getValue().reviewNotes().isBlank()
                        ? "" : "  · " + c.getValue().reviewNotes())));
        table.getColumns().addAll(java.util.List.of(cEmp, cKind, cFrom, cTo, cDays, cStatus, cAtt, cReason));

        Runnable reload = () -> {
            String st = statusFilter.getValue();
            Task<java.util.List<com.benjagest.ui.model.LeaveRequestEntry>> task = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.LeaveRequestEntry> call() throws Exception {
                    return laborApiClient.listLeaveRequests(st, null);
                }
            };
            task.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(task.getValue())));
            task.setOnFailed(ev -> table.getItems().clear());
            start(task, "leavereq-list");
        };

        Button reloadBtn = new Button(t("labor.leavereq.action.reload"));
        reloadBtn.setGraphic(icon("fas-sync-alt"));
        reloadBtn.setOnAction(ev -> reload.run());
        statusFilter.valueProperty().addListener((o, ov, nv) -> reload.run());

        Button approveBtn = new Button(t("labor.leavereq.action.approve"));
        approveBtn.setGraphic(icon("fas-check"));
        approveBtn.getStyleClass().add("invoice-validate-action");
        approveBtn.setDisable(true);
        Button rejectBtn = new Button(t("labor.leavereq.action.reject"));
        rejectBtn.setGraphic(icon("fas-times"));
        rejectBtn.setDisable(true);
        Button attBtn = new Button(t("labor.leavereq.action.view_attachments"));
        attBtn.setGraphic(icon("fas-paperclip"));
        attBtn.setDisable(true);

        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            boolean req = nv != null && "REQUESTED".equals(nv.status());
            approveBtn.setDisable(!req);
            rejectBtn.setDisable(!req);
            attBtn.setDisable(nv == null || nv.attachmentCount() == 0);
        });

        approveBtn.setOnAction(ev -> reviewLeaveRequest(table.getSelectionModel().getSelectedItem(), true, reload));
        rejectBtn.setOnAction(ev -> reviewLeaveRequest(table.getSelectionModel().getSelectedItem(), false, reload));
        attBtn.setOnAction(ev -> viewLeaveAttachments(table.getSelectionModel().getSelectedItem()));

        // El filtro (etiqueta+combo) se agrupa para que no se separe al envolver.
        HBox statusGroup = new HBox(6, new Label(t("labor.leavereq.filter.status")), statusFilter);
        statusGroup.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.layout.FlowPane controls = actionFlow(
                statusGroup, reloadBtn, approveBtn, rejectBtn, attBtn);

        VBox body = new VBox(10, hint, controls, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        body.setPadding(new Insets(12));
        this.viewRoot = body;

        Task<Void> initial = new Task<>() { @Override protected Void call() throws Exception { Thread.sleep(50); return null; } };
        initial.setOnSucceeded(ev -> reload.run());
        start(initial, "leavereq-initial");
        // NOTIF-RT — refresco en vivo cuando llega una solicitud nueva o se resuelve.
        com.benjagest.ui.support.RefreshBus.subscribe(
                com.benjagest.ui.support.RefreshBus.TOPIC_LEAVE_REQUESTS, reload, table);
        return screenScroll(body);
    }

    /** Aprueba o rechaza una solicitud pidiendo una nota opcional. */
    private void reviewLeaveRequest(com.benjagest.ui.model.LeaveRequestEntry sel, boolean approve, Runnable onDone) {
        if (sel == null) { showInfo(t("labor.leavereq.action.approve"), t("labor.leavereq.select.none")); return; }
        TextInputDialog dlg = new TextInputDialog();
        dlg.initOwner(window());
        dlg.setTitle(approve ? t("labor.leavereq.approve.title") : t("labor.leavereq.reject.title"));
        dlg.setHeaderText(approve ? t("labor.leavereq.approve.header") : t("labor.leavereq.reject.header"));
        dlg.setContentText(t("labor.leavereq.note"));
        java.util.Optional<String> res = dlg.showAndWait();
        if (res.isEmpty()) return; // cancelado
        String notes = res.get().trim();
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                if (approve) laborApiClient.approveLeaveRequest(sel.id(), notes);
                else laborApiClient.rejectLeaveRequest(sel.id(), notes);
                return null;
            }
        };
        task.setOnSucceeded(ev -> {
            showInfo(approve ? t("labor.leavereq.ok.approved") : t("labor.leavereq.ok.rejected"), "");
            if (onDone != null) onDone.run();
        });
        task.setOnFailed(ev -> showError(t("labor.leavereq.fail.title"), t("labor.leavereq.fail.body")));
        start(task, "leavereq-review");
    }

    /** Lista los adjuntos de la solicitud y permite descargarlos. */
    private void viewLeaveAttachments(com.benjagest.ui.model.LeaveRequestEntry sel) {
        if (sel == null) return;
        Task<java.util.List<com.benjagest.ui.model.LeaveAttachmentMeta>> task = new Task<>() {
            @Override protected java.util.List<com.benjagest.ui.model.LeaveAttachmentMeta> call() throws Exception {
                return laborApiClient.listLeaveAttachments(sel.id());
            }
        };
        task.setOnSucceeded(ev -> {
            var metas = task.getValue();
            if (metas.isEmpty()) { showInfo(t("labor.leavereq.attach.title"), t("labor.leavereq.attach.none")); return; }
            if (metas.size() == 1) { downloadLeaveAttachment(metas.get(0)); return; }
            // Varios: elegir por etiqueta única "i. nombre" y mapear de vuelta.
            java.util.Map<String, com.benjagest.ui.model.LeaveAttachmentMeta> byLabel = new java.util.LinkedHashMap<>();
            int i = 1;
            for (var m : metas) byLabel.put((i++) + ". " + (m.filename() == null ? "adjunto" : m.filename()), m);
            java.util.List<String> labels = new java.util.ArrayList<>(byLabel.keySet());
            ChoiceDialog<String> picker = new ChoiceDialog<>(labels.get(0), labels);
            picker.initOwner(window());
            picker.setTitle(t("labor.leavereq.attach.title"));
            picker.setHeaderText(t("labor.leavereq.attach.choose"));
            picker.setContentText("");
            picker.showAndWait().ifPresent(lbl -> downloadLeaveAttachment(byLabel.get(lbl)));
        });
        task.setOnFailed(ev -> showError(t("labor.leavereq.fail.title"), t("labor.leavereq.fail.body")));
        start(task, "leavereq-attachments");
    }

    private void downloadLeaveAttachment(com.benjagest.ui.model.LeaveAttachmentMeta meta) {
        Task<byte[]> task = new Task<>() {
            @Override protected byte[] call() throws Exception {
                return laborApiClient.downloadLeaveAttachment(meta.id());
            }
        };
        task.setOnSucceeded(ev -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setInitialFileName(meta.filename() == null ? "adjunto" : meta.filename());
            java.io.File target = fc.showSaveDialog(window());
            if (target == null) return;
            try {
                java.nio.file.Files.write(target.toPath(), task.getValue());
                showInfo(t("labor.leavereq.attach.title"),
                        t("labor.leavereq.attach.saved") + "\n" + target.getAbsolutePath());
            } catch (java.io.IOException ex) {
                showError(t("labor.leavereq.fail.title"), ex.getMessage());
            }
        });
        task.setOnFailed(ev -> showError(t("labor.leavereq.fail.title"), t("labor.leavereq.fail.body")));
        start(task, "leavereq-att-download");
    }

    // ===================================================================
    //  Bajas médicas (IT)
    // ===================================================================

    public Node buildMedicalLeavesTab(
            java.util.List<com.benjagest.ui.model.EmployeeEntry> employees) {
        VBox content = new VBox(12);
        content.setPadding(new Insets(16));

        java.util.Map<String, String> empById = new java.util.HashMap<>();
        for (var e : employees) empById.put(e.id(), e.fullName());

        Label hint = new Label(t("labor.leaves.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        TableView<com.benjagest.ui.model.MedicalLeaveEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.leaves.placeholder.empty")));
        com.benjagest.ui.support.TableSelectionHelper.install(table);

        TableColumn<com.benjagest.ui.model.MedicalLeaveEntry, String> cEmp =
                new TableColumn<>(t("labor.leaves.col.employee"));
        cEmp.setCellValueFactory(c -> new SimpleStringProperty(
                empById.getOrDefault(c.getValue().employeeId(),
                        shortId(c.getValue().employeeId()))));
        cEmp.setPrefWidth(180);

        TableColumn<com.benjagest.ui.model.MedicalLeaveEntry, String> cType =
                new TableColumn<>(t("labor.leaves.col.type"));
        cType.setCellValueFactory(c -> new SimpleStringProperty(
                t("labor.leaves.type." + c.getValue().leaveType())));
        cType.setPrefWidth(180);

        TableColumn<com.benjagest.ui.model.MedicalLeaveEntry, String> cStart =
                new TableColumn<>(t("labor.leaves.col.start"));
        cStart.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().startDate() == null ? "" : c.getValue().startDate().toString()));
        cStart.setPrefWidth(110);
        cStart.setComparator(ISO_DATE_COMPARATOR);

        TableColumn<com.benjagest.ui.model.MedicalLeaveEntry, String> cEnd =
                new TableColumn<>(t("labor.leaves.col.end"));
        cEnd.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().endDate() == null ? "" : c.getValue().endDate().toString()));
        cEnd.setPrefWidth(110);
        cEnd.setComparator(ISO_DATE_COMPARATOR);

        TableColumn<com.benjagest.ui.model.MedicalLeaveEntry, String> cStatus =
                new TableColumn<>(t("labor.leaves.col.status"));
        cStatus.setCellValueFactory(c -> new SimpleStringProperty(
                t("labor.leaves.status." + c.getValue().status())));
        cStatus.setPrefWidth(100);

        TableColumn<com.benjagest.ui.model.MedicalLeaveEntry, String> cNotes =
                new TableColumn<>(t("labor.leaves.col.notes"));
        cNotes.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().notes() == null ? "" : c.getValue().notes()));

        table.getColumns().addAll(java.util.List.of(cEmp, cType, cStart, cEnd, cStatus, cNotes));

        Runnable reload = () -> {
            Task<java.util.List<com.benjagest.ui.model.MedicalLeaveEntry>> t = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.MedicalLeaveEntry> call()
                        throws Exception {
                    return altaApiClient.listMedicalLeaves(null);
                }
            };
            t.setOnSucceeded(ev -> table.setItems(
                    FXCollections.observableArrayList(t.getValue())));
            t.setOnFailed(ev -> showError(t("labor.leaves.load_failed"),
                    t.getException() == null ? "" : t.getException().getMessage()));
            start(t, "labor-leaves-load");
        };
        reload.run();

        Button newBtn = new Button(t("labor.leaves.btn.new"));
        newBtn.setGraphic(icon("fas-plus"));
        newBtn.getStyleClass().add("primary-button");
        newBtn.setOnAction(ev -> openMedicalLeaveDialog(null, employees, reload));

        Button editBtn = new Button(t("labor.leaves.btn.edit"));
        editBtn.setGraphic(icon("fas-pen"));
        editBtn.setDisable(true);
        editBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) openMedicalLeaveDialog(sel, employees, reload);
        });

        Button delBtn = new Button(t("labor.leaves.btn.delete"));
        delBtn.setGraphic(icon("fas-trash"));
        delBtn.setDisable(true);
        delBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(t("labor.leaves.confirm.delete.title"));
            confirm.setHeaderText(t("labor.leaves.confirm.delete.body"));
            confirm.showAndWait().ifPresent(rsp -> {
                if (rsp == javafx.scene.control.ButtonType.OK) {
                    Task<Void> del = new Task<>() {
                        @Override protected Void call() throws Exception {
                            altaApiClient.deleteMedicalLeave(sel.id());
                            return null;
                        }
                    };
                    del.setOnSucceeded(s -> reload.run());
                    del.setOnFailed(s -> showError(t("labor.leaves.delete_failed"),
                            del.getException() == null ? "" : del.getException().getMessage()));
                    start(del, "labor-leaves-delete");
                }
            });
        });

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            boolean has = newV != null;
            editBtn.setDisable(!has);
            delBtn.setDisable(!has);
        });

        HBox actions = new HBox(8, newBtn, editBtn, delBtn);
        VBox.setVgrow(table, Priority.ALWAYS);
        content.getChildren().addAll(hint, table, actions);
        return content;
    }

    /** Diálogo formulario para nueva/editar baja médica. */
    private void openMedicalLeaveDialog(
            com.benjagest.ui.model.MedicalLeaveEntry existing,
            java.util.List<com.benjagest.ui.model.EmployeeEntry> employees,
            Runnable onSuccess) {
        Dialog<javafx.scene.control.ButtonType> dlg = new Dialog<>();
        dlg.setTitle(existing == null
                ? t("labor.leaves.editor.title_new")
                : t("labor.leaves.editor.title_edit"));
        dlg.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        ComboBox<com.benjagest.ui.model.EmployeeEntry> empCombo = new ComboBox<>();
        empCombo.getItems().addAll(employees);
        empCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.EmployeeEntry e) {
                return e == null ? "" : e.fullName();
            }
            @Override public com.benjagest.ui.model.EmployeeEntry fromString(String s) { return null; }
        });
        if (existing != null) {
            for (var e : employees) if (e.id().equals(existing.employeeId())) empCombo.setValue(e);
            empCombo.setDisable(true);
        }

        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll(
                com.benjagest.ui.model.MedicalLeaveEntry.TYPE_COMMON_DISEASE,
                com.benjagest.ui.model.MedicalLeaveEntry.TYPE_WORK_ACCIDENT,
                com.benjagest.ui.model.MedicalLeaveEntry.TYPE_MATERNITY,
                com.benjagest.ui.model.MedicalLeaveEntry.TYPE_PATERNITY,
                com.benjagest.ui.model.MedicalLeaveEntry.TYPE_OTHER);
        typeCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) {
                return s == null ? "" : t("labor.leaves.type." + s);
            }
            @Override public String fromString(String s) { return s; }
        });
        typeCombo.setValue(existing == null
                ? com.benjagest.ui.model.MedicalLeaveEntry.TYPE_COMMON_DISEASE
                : existing.leaveType());

        DatePicker startPicker = new DatePicker(
                existing == null ? java.time.LocalDate.now() : existing.startDate());
        DatePicker endPicker = new DatePicker(
                existing == null ? null : existing.endDate());
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(startPicker);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(endPicker);

        ComboBox<String> statusCombo = new ComboBox<>();
        statusCombo.getItems().addAll(
                com.benjagest.ui.model.MedicalLeaveEntry.STATUS_OPEN,
                com.benjagest.ui.model.MedicalLeaveEntry.STATUS_CLOSED,
                com.benjagest.ui.model.MedicalLeaveEntry.STATUS_DRAFT);
        statusCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) {
                return s == null ? "" : t("labor.leaves.status." + s);
            }
            @Override public String fromString(String s) { return s; }
        });
        statusCombo.setValue(existing == null
                ? com.benjagest.ui.model.MedicalLeaveEntry.STATUS_OPEN
                : existing.status());

        TextArea notesArea = new TextArea(existing == null ? "" : existing.notes());
        notesArea.setPrefRowCount(3);
        notesArea.setWrapText(true);

        int r = 0;
        grid.addRow(r++, new Label(t("labor.leaves.field.employee")), empCombo);
        grid.addRow(r++, new Label(t("labor.leaves.field.type")), typeCombo);
        grid.addRow(r++, new Label(t("labor.leaves.field.start")), startPicker);
        grid.addRow(r++, new Label(t("labor.leaves.field.end")), endPicker);
        grid.addRow(r++, new Label(t("labor.leaves.field.status")), statusCombo);
        grid.addRow(r++, new Label(t("labor.leaves.field.notes")), notesArea);

        dlg.getDialogPane().setContent(grid);
        dlg.showAndWait().ifPresent(rsp -> {
            if (rsp != javafx.scene.control.ButtonType.OK) return;
            if (empCombo.getValue() == null) {
                showError(t("labor.leaves.error"), t("labor.leaves.error.no_employee"));
                return;
            }
            if (startPicker.getValue() == null) {
                showError(t("labor.leaves.error"), t("labor.leaves.error.no_start"));
                return;
            }
            // Auto-status si el usuario no toca el combo:
            // - OPEN si no hay endDate
            // - CLOSED si hay endDate
            String status = statusCombo.getValue();
            if (status == null) {
                status = endPicker.getValue() != null
                        ? com.benjagest.ui.model.MedicalLeaveEntry.STATUS_CLOSED
                        : com.benjagest.ui.model.MedicalLeaveEntry.STATUS_OPEN;
            }
            String notes = notesArea.getText() == null || notesArea.getText().isBlank()
                    ? null : notesArea.getText().trim();
            final String finalStatus = status;
            Task<Void> save = new Task<>() {
                @Override protected Void call() throws Exception {
                    if (existing == null) {
                        altaApiClient.createMedicalLeave(
                                empCombo.getValue().id(),
                                typeCombo.getValue(),
                                startPicker.getValue(),
                                endPicker.getValue(),
                                finalStatus,
                                notes);
                    } else {
                        altaApiClient.updateMedicalLeave(
                                existing.id(),
                                typeCombo.getValue(),
                                startPicker.getValue(),
                                endPicker.getValue(),
                                finalStatus,
                                notes);
                    }
                    return null;
                }
            };
            save.setOnSucceeded(s -> onSuccess.run());
            save.setOnFailed(s -> showError(t("labor.leaves.save_failed"),
                    save.getException() == null ? "" : save.getException().getMessage()));
            start(save, "labor-leaves-save");
        });
    }

    // ===================================================================
    //  Vacaciones (CV-VAC)
    // ===================================================================

    public Node buildVacationsTab(java.util.List<com.benjagest.ui.model.EmployeeEntry> employees) {
        VBox content = new VBox(12);
        content.setPadding(new Insets(16));
        Label hint = new Label(t("labor.vac.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        TableView<com.benjagest.ui.model.VacationEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.vac.empty")));

        TableColumn<com.benjagest.ui.model.VacationEntry, String> cEmp = new TableColumn<>(t("labor.vac.col.employee"));
        cEmp.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().employeeName()));
        TableColumn<com.benjagest.ui.model.VacationEntry, String> cFrom = new TableColumn<>(t("labor.vac.col.from"));
        cFrom.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().startDate() == null ? "" : c.getValue().startDate().toString()));
        cFrom.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.VacationEntry, String> cTo = new TableColumn<>(t("labor.vac.col.to"));
        cTo.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().endDate() == null ? "" : c.getValue().endDate().toString()));
        cTo.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.VacationEntry, String> cDays = new TableColumn<>(t("labor.vac.col.days"));
        cDays.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().days() == null ? "" : c.getValue().days().toPlainString()));
        cDays.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.VacationEntry, String> cStatus = new TableColumn<>(t("labor.vac.col.status"));
        cStatus.setCellValueFactory(c -> new SimpleStringProperty(t("labor.vac.status." + c.getValue().status())));
        table.getColumns().addAll(java.util.List.of(cEmp, cFrom, cTo, cDays, cStatus));

        Runnable reload = () -> {
            Task<java.util.List<com.benjagest.ui.model.VacationEntry>> tk = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.VacationEntry> call() throws Exception {
                    return laborApiClient.listVacations(null, null);
                }
            };
            tk.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(tk.getValue())));
            tk.setOnFailed(ev -> showError(t("labor.vac.title"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "vac-list");
        };

        Button newBtn = new Button(t("labor.vac.new"));
        newBtn.setGraphic(icon("fas-plus"));
        newBtn.setOnAction(e -> showVacationEditor(employees, null, reload));
        Button editBtn = new Button(t("common.edit"));
        editBtn.setDisable(true);
        editBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) showVacationEditor(employees, sel, reload);
        });
        Button delBtn = new Button(t("common.delete"));
        delBtn.setDisable(true);
        delBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception { laborApiClient.deleteVacation(sel.id()); return null; }
            };
            tk.setOnSucceeded(ev -> reload.run());
            tk.setOnFailed(ev -> showError(t("labor.vac.title"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "vac-del");
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            editBtn.setDisable(nv == null);
            delBtn.setDisable(nv == null);
        });

        HBox actions = new HBox(8, newBtn, editBtn, delBtn);
        actions.setAlignment(Pos.CENTER_LEFT);
        reload.run();
        VBox body = new VBox(10, hint, actions, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        return screenScroll(body);
    }

    private void showVacationEditor(java.util.List<com.benjagest.ui.model.EmployeeEntry> employees,
                                     com.benjagest.ui.model.VacationEntry existing, Runnable onSaved) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.vac.title"));
        ButtonType save = new ButtonType(t("labor.ssrates.save_btn"), ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        ComboBox<com.benjagest.ui.model.EmployeeEntry> empCombo = new ComboBox<>();
        empCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.EmployeeEntry e) { return e == null ? "" : e.fullName(); }
            @Override public com.benjagest.ui.model.EmployeeEntry fromString(String s) { return null; }
        });
        empCombo.getItems().addAll(employees.stream().filter(com.benjagest.ui.model.EmployeeEntry::active).toList());
        if (existing != null) {
            employees.stream().filter(e -> e.id().equals(existing.employeeId())).findFirst()
                    .ifPresent(empCombo.getSelectionModel()::select);
        }
        DatePicker fromDate = new DatePicker(existing == null ? java.time.LocalDate.now() : existing.startDate());
        DatePicker toDate = new DatePicker(existing == null ? java.time.LocalDate.now() : existing.endDate());
        TextField daysField = new TextField(existing == null || existing.days() == null
                ? "" : existing.days().toPlainString());
        daysField.setPromptText(t("labor.vac.col.days"));
        ComboBox<String> statusCombo = new ComboBox<>();
        statusCombo.getItems().addAll("APPROVED", "REQUESTED", "REJECTED");
        statusCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) { return s == null ? "" : t("labor.vac.status." + s); }
            @Override public String fromString(String s) { return null; }
        });
        statusCombo.getSelectionModel().select(existing == null ? "APPROVED" : existing.status());
        TextField notesField = new TextField(existing == null ? "" : existing.notes());

        // Sugerir días laborables al elegir fechas (lunes-viernes), editable.
        Runnable suggestDays = () -> {
            if (fromDate.getValue() == null || toDate.getValue() == null) return;
            long d2 = 0;
            for (java.time.LocalDate x = fromDate.getValue(); !x.isAfter(toDate.getValue()); x = x.plusDays(1)) {
                if (x.getDayOfWeek().getValue() <= 5) d2++;
            }
            daysField.setText(String.valueOf(d2));
        };
        fromDate.valueProperty().addListener((o, ov, nv) -> suggestDays.run());
        toDate.valueProperty().addListener((o, ov, nv) -> suggestDays.run());

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12));
        int r = 0;
        g.add(new Label(t("labor.vac.col.employee")), 0, r); g.add(empCombo, 1, r++);
        g.add(new Label(t("labor.vac.col.from")), 0, r); g.add(fromDate, 1, r++);
        g.add(new Label(t("labor.vac.col.to")), 0, r); g.add(toDate, 1, r++);
        g.add(new Label(t("labor.vac.col.days")), 0, r); g.add(daysField, 1, r++);
        g.add(new Label(t("labor.vac.col.status")), 0, r); g.add(statusCombo, 1, r++);
        g.add(new Label(t("labor.vac.col.notes")), 0, r); g.add(notesField, 1, r++);
        installDialog(d, g);
        d.showAndWait().ifPresent(bt -> {
            if (bt != save) return;
            var emp = empCombo.getValue();
            if (emp == null) { showError(t("labor.vac.title"), t("labor.payslips.calc.fail.no_employee")); return; }
            com.benjagest.ui.model.VacationEntry payload = new com.benjagest.ui.model.VacationEntry(
                    existing == null ? null : existing.id(), emp.id(), null,
                    fromDate.getValue(), toDate.getValue(), parseDecSafe(daysField.getText()),
                    statusCombo.getValue(), blankToNullOrSelf(notesField.getText()));
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception {
                    if (existing == null) laborApiClient.createVacation(payload);
                    else laborApiClient.updateVacation(existing.id(), payload);
                    return null;
                }
            };
            tk.setOnSucceeded(ev -> onSaved.run());
            tk.setOnFailed(ev -> showError(t("labor.vac.title"),
                    humanizeBackendError(tk.getException() == null ? "" : tk.getException().getMessage())));
            start(tk, "vac-save");
        });
    }
}
