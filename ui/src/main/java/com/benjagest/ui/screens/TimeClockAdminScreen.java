package com.benjagest.ui.screens;

import com.benjagest.ui.model.Language;
import com.benjagest.ui.service.LaborApiClient;
import com.benjagest.ui.support.Router;
import java.time.LocalDate;
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
 * NOM-5 — Fichajes (admin) del módulo Laboral (bloque UIR): las dos pestañas
 * autocontenidas de la categoría "Tiempo" que NO comparten la maquinaria del
 * fichaje personal: <b>Auditoría</b> de fichajes (resumen + detalle +
 * corrección RD 8/2019, inalterable) y <b>Config. tipos de fichaje</b> (TC-CFG:
 * alta/edición de tipos con selector de icono). Movimiento puro: mismo
 * comportamiento, mismas claves i18n. Depende de {@link LaborApiClient}, el
 * idioma actual y un callback {@code refreshLabor} (el editor/borrado de tipos
 * recarga el módulo Laboral, igual que hacía {@code laborRefresh}). El owner del
 * diálogo de corrección era el {@code root} del shell; ahora se resuelve por el
 * propio nodo montado ({@code viewRoot}).
 *
 * <p>La pestaña de <b>fichar</b> ({@code buildTimeClockTab}) NO se extrae: reusa
 * la maquinaria del fichaje personal del shell ({@code punch}/{@code
 * reloadTimeClock}/{@code timeClockTable}), zona sensible RD 8/2019.
 */
public class TimeClockAdminScreen extends ScreenBase {

    private final LaborApiClient laborApiClient;
    private final Language language;
    private final Runnable refreshLabor;
    private Node viewRoot;

    public TimeClockAdminScreen(LaborApiClient laborApiClient, Language language,
                                Function<String, String> tt, Router router, Runnable refreshLabor) {
        super(tt, router);
        this.laborApiClient = laborApiClient;
        this.language = language;
        this.refreshLabor = refreshLabor;
    }

    private javafx.stage.Window window() {
        return viewRoot == null || viewRoot.getScene() == null
                ? null : viewRoot.getScene().getWindow();
    }

    // ----- Helpers compartidos con el fichaje personal (copiados) -----

    private String localizedPunchType(String code) {
        if (code == null) return "";
        // Para los 4 tipos estándar usamos las keys legacy que ya existían.
        // Para tipos custom (creados por el OWNER) caemos a humanizeFromKey
        // con prefix labor.cfg_timeclock.code.* (mismo prefix que el listado
        // de configuración). Así una cláusula "COMIDA" muestra "Comida"
        // si hay key, y "COMIDA" como último recurso — pero NUNCA debería
        // salir el código en bruto si el i18n está completo.
        return switch (code) {
            case "IN" -> t("timeclock.type.in");
            case "OUT" -> t("timeclock.type.out");
            case "BREAK_START" -> t("timeclock.type.break_start");
            case "BREAK_END" -> t("timeclock.type.break_end");
            default -> humanizeFromKey("labor.cfg_timeclock.code." + code,
                    humanizeCodeSafe(code));
        };
    }

    /** Última red de seguridad: COMIDA_LIBRE → "Comida libre". */
    private static String humanizeCodeSafe(String code) {
        if (code == null || code.isEmpty()) return "";
        String low = code.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(low.charAt(0)) + low.substring(1);
    }

    private String humanizeFromKey(String key, String fallback) {
        if (key == null || key.isBlank()) return fallback == null ? "" : fallback;
        String translated = t(key);
        return key.equals(translated) ? (fallback == null ? "" : fallback) : translated;
    }

    // ===================================================================
    //  Auditoría de fichajes
    // ===================================================================

    public Node buildTimeClockAuditTab(java.util.List<com.benjagest.ui.model.EmployeeEntry> allEmployees) {
        Label hint = new Label(t("labor.audit.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        // Filtros
        LocalDate defaultFrom = LocalDate.now().withDayOfMonth(1);
        LocalDate defaultTo = LocalDate.now();
        TextField fromField = new TextField(defaultFrom.toString());
        TextField toField = new TextField(defaultTo.toString());
        fromField.setPrefColumnCount(12);
        toField.setPrefColumnCount(12);

        ComboBox<com.benjagest.ui.model.EmployeeEntry> empCombo = new ComboBox<>();
        empCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.EmployeeEntry e) {
                return e == null ? t("labor.audit.all") : e.fullName();
            }
            @Override public com.benjagest.ui.model.EmployeeEntry fromString(String s) { return null; }
        });
        // El item null = "Todos" debe verse claramente en el desplegable
        // (con el cell factory por defecto sale en blanco) para poder volver
        // a seleccionarlo tras filtrar por un empleado.
        empCombo.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(com.benjagest.ui.model.EmployeeEntry e, boolean empty) {
                super.updateItem(e, empty);
                setText(empty ? null : (e == null ? t("labor.audit.all") : e.fullName()));
            }
        });
        empCombo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(com.benjagest.ui.model.EmployeeEntry e, boolean empty) {
                super.updateItem(e, empty);
                setText(empty ? null : (e == null ? t("labor.audit.all") : e.fullName()));
            }
        });
        empCombo.getItems().add(null); // "Todos"
        empCombo.getItems().addAll(allEmployees);
        empCombo.setValue(null); // seleccionado "Todos" al abrir

        TextField eventTypeField = new TextField();
        eventTypeField.setPromptText("IN, OUT, COMIDA…");
        eventTypeField.setPrefColumnCount(8);

        // Tabla resumen por empleado
        TableView<com.benjagest.ui.model.TimeClockAuditSummary> summaryTable = new TableView<>();
        summaryTable.getStyleClass().add("data-table");
        summaryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        summaryTable.setPlaceholder(new Label(t("labor.audit.summary.placeholder.empty")));
        summaryTable.setPrefHeight(180);
        summaryTable.setTooltip(new javafx.scene.control.Tooltip(t("labor.audit.incidence.tooltip")));

        TableColumn<com.benjagest.ui.model.TimeClockAuditSummary, String> sName =
                new TableColumn<>(t("labor.audit.col.employee"));
        sName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().employeeName()));
        sName.setPrefWidth(200);
        TableColumn<com.benjagest.ui.model.TimeClockAuditSummary, String> sTotal =
                new TableColumn<>(t("labor.audit.col.total"));
        sTotal.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().totalEvents())));
        sTotal.setPrefWidth(80);
        sTotal.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TimeClockAuditSummary, String> sIns =
                new TableColumn<>(t("labor.audit.col.ins"));
        sIns.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().ins())));
        sIns.setPrefWidth(70);
        sIns.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TimeClockAuditSummary, String> sOuts =
                new TableColumn<>(t("labor.audit.col.outs"));
        sOuts.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().outs())));
        sOuts.setPrefWidth(70);
        sOuts.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TimeClockAuditSummary, String> sPauses =
                new TableColumn<>(t("labor.audit.col.pauses"));
        sPauses.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().pauses())));
        sPauses.setPrefWidth(80);
        sPauses.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TimeClockAuditSummary, String> sCorr =
                new TableColumn<>(t("labor.audit.col.corrections"));
        sCorr.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().corrections())));
        sCorr.setPrefWidth(110);
        sCorr.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TimeClockAuditSummary, String> sInc =
                new TableColumn<>(t("labor.audit.col.incidence"));
        sInc.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().hasIncidence() ? "⚠ " + t("labor.audit.incidence.yes") : t("labor.audit.incidence.no")));
        sInc.setPrefWidth(130);
        summaryTable.getColumns().addAll(java.util.List.of(sName, sTotal, sIns, sOuts, sPauses, sCorr, sInc));

        // Tabla detalle (eventos)
        TableView<com.benjagest.ui.model.TimeClockAuditEntry> detailTable = new TableView<>();
        detailTable.getStyleClass().add("data-table");
        detailTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        detailTable.setPlaceholder(new Label(t("labor.audit.detail.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.TimeClockAuditEntry, String> dWhen =
                new TableColumn<>(t("labor.audit.col.when"));
        dWhen.setCellValueFactory(c -> new SimpleStringProperty(shortIso(c.getValue().eventTime())));
        dWhen.setPrefWidth(160);
        dWhen.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TimeClockAuditEntry, String> dEmp =
                new TableColumn<>(t("labor.audit.col.employee"));
        dEmp.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().employeeName()));
        dEmp.setPrefWidth(180);
        TableColumn<com.benjagest.ui.model.TimeClockAuditEntry, String> dType =
                new TableColumn<>(t("labor.audit.col.type"));
        dType.setCellValueFactory(c -> new SimpleStringProperty(localizedPunchType(c.getValue().eventType())));
        dType.setPrefWidth(120);
        TableColumn<com.benjagest.ui.model.TimeClockAuditEntry, String> dOrigin =
                new TableColumn<>(t("labor.audit.col.origin"));
        dOrigin.setCellValueFactory(c -> new SimpleStringProperty(localizedEnum("timeclock_origin", c.getValue().origin())));
        dOrigin.setPrefWidth(90);
        TableColumn<com.benjagest.ui.model.TimeClockAuditEntry, String> dStatus =
                new TableColumn<>(t("labor.audit.col.status"));
        dStatus.setCellValueFactory(c -> new SimpleStringProperty(localizedEnum("timeclock_status", c.getValue().status())));
        dStatus.setPrefWidth(100);
        TableColumn<com.benjagest.ui.model.TimeClockAuditEntry, String> dCorr =
                new TableColumn<>(t("labor.audit.col.has_corrections"));
        dCorr.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().correctionCount() > 0
                        ? "✎ " + c.getValue().correctionCount() : ""));
        dCorr.setPrefWidth(90);
        TableColumn<com.benjagest.ui.model.TimeClockAuditEntry, String> dCsv =
                new TableColumn<>(t("labor.audit.col.csv"));
        dCsv.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().csv()));
        detailTable.getColumns().addAll(java.util.List.of(dWhen, dEmp, dType, dOrigin, dStatus, dCorr, dCsv));

        Runnable reload = () -> {
            String from = fromField.getText().trim();
            String to = toField.getText().trim();
            var emp = empCombo.getValue();
            String empId = emp == null ? null : emp.id();
            String ev = eventTypeField.getText().trim();
            // resumen
            Task<java.util.List<com.benjagest.ui.model.TimeClockAuditSummary>> sumTask = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.TimeClockAuditSummary> call() throws Exception {
                    return laborApiClient.auditSummary(from, to);
                }
            };
            sumTask.setOnSucceeded(ev2 -> summaryTable.setItems(FXCollections.observableArrayList(sumTask.getValue())));
            sumTask.setOnFailed(ev2 -> summaryTable.getItems().clear());
            start(sumTask, "tc-audit-summary");
            // detalle
            Task<java.util.List<com.benjagest.ui.model.TimeClockAuditEntry>> detTask = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.TimeClockAuditEntry> call() throws Exception {
                    return laborApiClient.queryAudit(from, to, empId, ev);
                }
            };
            detTask.setOnSucceeded(ev2 -> detailTable.setItems(FXCollections.observableArrayList(detTask.getValue())));
            detTask.setOnFailed(ev2 -> detailTable.getItems().clear());
            start(detTask, "tc-audit-detail");
        };

        Button reloadBtn = new Button(t("labor.audit.action.reload"));
        reloadBtn.setGraphic(icon("fas-sync-alt"));
        reloadBtn.setOnAction(ev -> reload.run());

        Button exportBtn = new Button(t("labor.audit.action.export"));
        exportBtn.setGraphic(icon("fas-file-export"));
        exportBtn.setDisable(true); // habilitado tras TC-EXPORT (próximo slice)
        exportBtn.setTooltip(new javafx.scene.control.Tooltip(t("labor.audit.export.tooltip")));

        // FJ-5 — "Corregir…": con un fichaje del detalle seleccionado, abre el
        // diálogo de corrección (RD 8/2019: no toca el original, crea una
        // corrección PENDING). Hasta ahora "Revisar" no llevaba a ninguna acción.
        Button correctBtn = new Button(t("labor.audit.correct.btn"));
        correctBtn.setGraphic(icon("fas-pen"));
        correctBtn.setDisable(true);
        detailTable.getSelectionModel().selectedItemProperty().addListener(
                (o, ov, nv) -> correctBtn.setDisable(nv == null));
        correctBtn.setOnAction(ev -> {
            var sel = detailTable.getSelectionModel().getSelectedItem();
            if (sel == null) { showInfo(t("labor.audit.correct.btn"), t("labor.audit.correct.none")); return; }
            openTimeClockCorrectionDialog(sel, reload);
        });

        HBox filters = new HBox(8,
                new Label(t("labor.audit.filter.from")), fromField,
                new Label(t("labor.audit.filter.to")), toField,
                new Label(t("labor.audit.filter.employee")), empCombo,
                new Label(t("labor.audit.filter.type")), eventTypeField,
                reloadBtn, exportBtn, correctBtn);
        filters.setAlignment(Pos.CENTER_LEFT);

        // Click sobre fila del resumen → filtra el detalle por ese empleado
        summaryTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (nv == null) return;
            for (var e : empCombo.getItems()) {
                if (e != null && nv.employeeId().equals(e.id())) {
                    empCombo.setValue(e);
                    reload.run();
                    return;
                }
            }
        });

        Label summaryTitle = label(t("labor.audit.section.summary"), "settings-section-title");
        Label detailTitle = label(t("labor.audit.section.detail"), "settings-section-title");

        VBox body = new VBox(10,
                hint, filters,
                summaryTitle, summaryTable,
                detailTitle, detailTable);
        VBox.setVgrow(detailTable, Priority.ALWAYS);
        body.setPadding(new Insets(12));
        this.viewRoot = body;

        // Carga inicial
        Task<Void> initial = new Task<>() {
            @Override protected Void call() throws Exception { Thread.sleep(50); return null; }
        };
        initial.setOnSucceeded(ev -> reload.run());
        start(initial, "tc-audit-initial");
        // NOTIF-RT — refresco en vivo cuando llega un fichaje (del móvil/kiosco).
        com.benjagest.ui.support.RefreshBus.subscribe(
                com.benjagest.ui.support.RefreshBus.TOPIC_AUDIT, reload, detailTable);

        return screenScroll(body);
    }

    /**
     * FJ-5 — Diálogo de corrección de un fichaje (RD 8/2019 art. 34.9: el original
     * es inalterable; se registra una corrección PENDING que un responsable revisa).
     * Tres tipos: ajustar hora (TIME_ADJUST), cambiar tipo (TYPE_CHANGE) o anular
     * (VOID). El motivo es obligatorio. {@code onDone} recarga la auditoría al éxito.
     */
    private void openTimeClockCorrectionDialog(com.benjagest.ui.model.TimeClockAuditEntry entry, Runnable onDone) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle(t("labor.audit.correct.title"));
        dialog.initOwner(window());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Tipo de corrección.
        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("TIME_ADJUST", "TYPE_CHANGE", "VOID");
        typeCombo.setValue("TIME_ADJUST");
        typeCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String c) { return c == null ? "" : t("labor.audit.correct.type." + c.toLowerCase()); }
            @Override public String fromString(String s) { return null; }
        });

        // Nueva hora (para TIME_ADJUST), prerrellenada con la hora local del evento.
        TextField timeField = new TextField(localDateTimeForEdit(entry.eventTime()));
        timeField.setPromptText("aaaa-MM-dd HH:mm");

        // Nuevo tipo (para TYPE_CHANGE).
        ComboBox<String> newTypeCombo = new ComboBox<>();
        newTypeCombo.getItems().addAll("IN", "OUT", "BREAK_START", "BREAK_END");
        newTypeCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String c) { return c == null ? "" : localizedPunchType(c); }
            @Override public String fromString(String s) { return null; }
        });

        TextArea reasonArea = new TextArea();
        reasonArea.setPromptText(t("labor.audit.correct.reason"));
        reasonArea.setPrefRowCount(3);
        reasonArea.setWrapText(true);

        Label timeLbl = new Label(t("labor.audit.correct.new_time"));
        Label newTypeLbl = new Label(t("labor.audit.correct.new_type"));
        // Solo se muestra el campo relevante según el tipo elegido.
        Runnable refreshVisibility = () -> {
            boolean adjust = "TIME_ADJUST".equals(typeCombo.getValue());
            boolean change = "TYPE_CHANGE".equals(typeCombo.getValue());
            timeLbl.setManaged(adjust); timeLbl.setVisible(adjust);
            timeField.setManaged(adjust); timeField.setVisible(adjust);
            newTypeLbl.setManaged(change); newTypeLbl.setVisible(change);
            newTypeCombo.setManaged(change); newTypeCombo.setVisible(change);
        };
        typeCombo.valueProperty().addListener((o, ov, nv) -> refreshVisibility.run());
        refreshVisibility.run();

        Label current = new Label(localizedPunchType(entry.eventType()) + "  ·  " + shortIso(entry.eventTime()));
        current.getStyleClass().add("settings-hint");

        VBox content = new VBox(10,
                current,
                new Label(t("labor.audit.correct.type")), typeCombo,
                timeLbl, timeField,
                newTypeLbl, newTypeCombo,
                new Label(t("labor.audit.correct.reason")), reasonArea);
        content.setPadding(new Insets(12));
        content.setPrefWidth(420);
        dialog.getDialogPane().setContent(content);

        // Validación sin cerrar el diálogo si falta algo (patrón BUG-UX-2).
        Button okBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            String type = typeCombo.getValue();
            String reason = reasonArea.getText() == null ? "" : reasonArea.getText().trim();
            if (reason.isEmpty()) { ev.consume(); showError(t("labor.audit.correct.title"), t("labor.audit.correct.reason.required")); return; }
            if ("TIME_ADJUST".equals(type) && editTextToInstantIso(timeField.getText()) == null) {
                ev.consume(); showError(t("labor.audit.correct.title"), t("labor.audit.correct.time.invalid")); return;
            }
            if ("TYPE_CHANGE".equals(type) && newTypeCombo.getValue() == null) {
                ev.consume(); showError(t("labor.audit.correct.title"), t("labor.audit.correct.type.required")); return;
            }
        });

        dialog.setResultConverter(bt -> bt == ButtonType.OK ? Boolean.TRUE : null);
        if (!Boolean.TRUE.equals(dialog.showAndWait().orElse(null))) return; // cancelado

        // Validado por el event filter: aquí los datos son correctos.
        String type = typeCombo.getValue();
        String proposedType = "TYPE_CHANGE".equals(type) ? newTypeCombo.getValue() : null;
        String proposedTimeIso = "TIME_ADJUST".equals(type) ? editTextToInstantIso(timeField.getText()) : null;
        String reason = reasonArea.getText().trim();

        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                laborApiClient.requestCorrection(entry.id(), type, proposedType, proposedTimeIso, reason);
                return null;
            }
        };
        task.setOnSucceeded(ev -> {
            showInfo(t("labor.audit.correct.ok.title"), t("labor.audit.correct.ok.body"));
            if (onDone != null) onDone.run();
        });
        task.setOnFailed(ev -> showError(t("labor.audit.correct.fail.title"), t("labor.audit.correct.fail.body")));
        start(task, "tc-audit-correction");
    }

    /** Convierte el instante ISO del evento a "aaaa-MM-dd HH:mm" en zona local para editar. */
    private String localDateTimeForEdit(String eventTimeIso) {
        try {
            java.time.Instant i = java.time.Instant.parse(eventTimeIso);
            java.time.LocalDateTime ldt = java.time.LocalDateTime.ofInstant(i, java.time.ZoneId.systemDefault());
            return ldt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception e) {
            return "";
        }
    }

    /** Parsea "aaaa-MM-dd HH:mm" (zona local) a instante ISO-8601, o null si no es válido. */
    private String editTextToInstantIso(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(text.trim(),
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ===================================================================
    //  Config. tipos de fichaje (TC-CFG)
    // ===================================================================

    public Node buildEventTypeConfigTab(java.util.List<com.benjagest.ui.model.TimeClockEventTypeEntry> types) {
        Label hint = new Label(t("labor.cfg_timeclock.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        TableView<com.benjagest.ui.model.TimeClockEventTypeEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.cfg_timeclock.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.TimeClockEventTypeEntry, String> cOrder =
                new TableColumn<>(t("labor.cfg_timeclock.col.order"));
        cOrder.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().displayOrder())));
        cOrder.setPrefWidth(70);
        cOrder.setComparator(NUMERIC_STRING_COMPARATOR);
        // Columna "Etiqueta" — muestra label_es o label_en según idioma
        // actual. Si la BD no trae label (caso raro de tipos huérfanos),
        // hacemos humanize del code para no enseñar "BREAK_START" crudo.
        TableColumn<com.benjagest.ui.model.TimeClockEventTypeEntry, String> cLabel =
                new TableColumn<>(t("labor.cfg_timeclock.col.label"));
        cLabel.setCellValueFactory(c -> {
            var e = c.getValue();
            boolean es = language == Language.ES;
            String label = es ? e.labelEs() : e.labelEn();
            if (label == null || label.isBlank()) {
                label = humanizeFromKey("labor.cfg_timeclock.code." + e.code(), e.code());
            }
            return new SimpleStringProperty(label);
        });
        cLabel.setPrefWidth(280);
        // Decisión 2026-06-08: NO mostrar la columna "Código" en el listado.
        // El código (IN/OUT/BREAK_START…) es un identificador interno; el
        // OWNER solo necesita ver la etiqueta humanizada. El código sigue
        // siendo visible/editable dentro del diálogo de edición porque es
        // necesario al CREAR un tipo nuevo.
        TableColumn<com.benjagest.ui.model.TimeClockEventTypeEntry, String> cFlags =
                new TableColumn<>(t("labor.cfg_timeclock.col.flags"));
        cFlags.setCellValueFactory(c -> {
            var e = c.getValue();
            StringBuilder sb = new StringBuilder();
            if (e.isWorkTime()) sb.append(t("labor.cfg_timeclock.flag.work")).append(" ");
            if (e.isPause()) sb.append(t("labor.cfg_timeclock.flag.pause")).append(" ");
            if (!e.active()) sb.append(t("labor.cfg_timeclock.inactive"));
            return new SimpleStringProperty(sb.toString().trim());
        });
        cFlags.setPrefWidth(160);
        table.getColumns().addAll(java.util.List.of(cOrder, cLabel, cFlags));
        table.setItems(FXCollections.observableArrayList(types));

        Button addBtn = new Button(t("labor.cfg_timeclock.action.add"));
        addBtn.setGraphic(icon("fas-plus"));
        addBtn.setOnAction(ev -> showEventTypeEditor(null));

        Button editBtn = new Button(t("labor.cfg_timeclock.action.edit"));
        editBtn.setGraphic(icon("fas-edit"));
        editBtn.setDisable(true);
        editBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) showEventTypeEditor(sel);
        });

        Button delBtn = new Button(t("labor.cfg_timeclock.action.delete"));
        delBtn.setGraphic(icon("fas-trash"));
        delBtn.setDisable(true);
        delBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) deleteEventType(sel);
        });

        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            editBtn.setDisable(nv == null);
            delBtn.setDisable(nv == null);
        });

        Label legend = new Label(t("labor.cfg_timeclock.legend"));
        legend.setWrapText(true);
        legend.getStyleClass().add("settings-hint");

        HBox actions = new HBox(8, addBtn, editBtn, delBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(10, hint, actions, table, legend);
        VBox.setVgrow(table, Priority.ALWAYS);
        body.setPadding(new Insets(12));
        return screenScroll(body);
    }

    private void showEventTypeEditor(com.benjagest.ui.model.TimeClockEventTypeEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? t("labor.cfg_timeclock.editor.title_new")
                : t("labor.cfg_timeclock.editor.title_edit"));
        ButtonType saveBt = new ButtonType(t("labor.cfg_timeclock.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField codeField = new TextField(existing == null ? "" : existing.code());
        codeField.setDisable(existing != null);
        codeField.setPromptText(t("labor.cfg_timeclock.editor.code.auto"));
        TextField esField = new TextField(existing == null ? "" : existing.labelEs());
        TextField enField = new TextField(existing == null ? "" : existing.labelEn());
        // Selector VISUAL de icono (en vez de teclear "fas-coffee").
        final String[] selectedIcon = { existing == null || existing.icon() == null
                || existing.icon().isBlank() ? "fas-clock" : existing.icon() };
        Button iconBtn = new Button("  " + t("labor.cfg_timeclock.editor.pick_icon"));
        iconBtn.setGraphic(icon(selectedIcon[0]));
        iconBtn.setOnAction(e -> showIconChooser(selectedIcon[0], chosen -> {
            selectedIcon[0] = chosen;
            iconBtn.setGraphic(icon(chosen));
        }));
        TextField orderField = new TextField(existing == null ? "" : String.valueOf(existing.displayOrder()));
        CheckBox isWork = new CheckBox(t("labor.cfg_timeclock.editor.is_work_time"));
        isWork.setSelected(existing == null || existing.isWorkTime());
        CheckBox isPause = new CheckBox(t("labor.cfg_timeclock.editor.is_pause"));
        isPause.setSelected(existing != null && existing.isPause());
        CheckBox active = new CheckBox(t("labor.cfg_timeclock.editor.active"));
        active.setSelected(existing == null || existing.active());

        // Intuitivo: una PAUSA no cuenta como trabajada (y viceversa). Al marcar
        // una, se desmarca la otra → "Comida"/"Pausa laboral" se reconocen como pausa.
        isPause.selectedProperty().addListener((o, ov, nv) -> { if (nv) isWork.setSelected(false); });
        isWork.selectedProperty().addListener((o, ov, nv) -> { if (nv) isPause.setSelected(false); });

        // Intuitivo: el CÓDIGO (técnico) se genera solo del nombre al crear, salvo
        // que el usuario lo edite a mano.
        if (existing == null) {
            final boolean[] codeTouched = { false };
            codeField.setOnKeyTyped(ev -> codeTouched[0] = true);
            esField.textProperty().addListener((o, ov, nv) -> {
                if (!codeTouched[0]) codeField.setText(deriveEventTypeCode(nv));
            });
        }

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(10));
        g.add(new Label(t("labor.cfg_timeclock.editor.code")), 0, 0); g.add(codeField, 1, 0);
        g.add(new Label(t("labor.cfg_timeclock.editor.label_es")), 0, 1); g.add(esField, 1, 1);
        g.add(new Label(t("labor.cfg_timeclock.editor.label_en")), 0, 2); g.add(enField, 1, 2);
        g.add(new Label(t("labor.cfg_timeclock.editor.icon")), 0, 3); g.add(iconBtn, 1, 3);
        g.add(new Label(t("labor.cfg_timeclock.editor.order")), 0, 4); g.add(orderField, 1, 4);
        g.add(isWork, 1, 5);
        g.add(isPause, 1, 6);
        g.add(active, 1, 7);
        Label flagsHint = new Label(t("labor.cfg_timeclock.editor.flags_hint"));
        flagsHint.setWrapText(true);
        flagsHint.getStyleClass().add("settings-hint");
        g.add(flagsHint, 0, 8, 2, 1);
        installDialog(dialog, g);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            Integer order = parseIntSafe(orderField.getText());
            Task<com.benjagest.ui.model.TimeClockEventTypeEntry> task = new Task<>() {
                @Override protected com.benjagest.ui.model.TimeClockEventTypeEntry call() throws Exception {
                    if (existing == null) {
                        return laborApiClient.createEventType(
                                codeField.getText().trim().toUpperCase(),
                                esField.getText().trim(),
                                enField.getText().trim(),
                                selectedIcon[0],
                                order, isWork.isSelected(), isPause.isSelected(), active.isSelected());
                    }
                    return laborApiClient.updateEventType(
                            existing.id(),
                            existing.code(),
                            esField.getText().trim(),
                            enField.getText().trim(),
                            selectedIcon[0],
                            order, isWork.isSelected(), isPause.isSelected(), active.isSelected());
                }
            };
            task.setOnSucceeded(ev -> refreshLabor.run());
            task.setOnFailed(ev -> showError(t("labor.cfg_timeclock.editor.fail.title"),
                    t("labor.cfg_timeclock.editor.fail.body")));
            start(task, "tc-cfg-save");
        });
    }

    /** Deriva un código técnico del nombre: "Pausa laboral" → "PAUSA_LABORAL". */
    private String deriveEventTypeCode(String name) {
        if (name == null) return "";
        String s = java.text.Normalizer.normalize(name.trim().toUpperCase(),
                        java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return s.replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /** Selector VISUAL de icono: rejilla de iconos (imágenes) para fichajes. */
    private void showIconChooser(String current, java.util.function.Consumer<String> onPick) {
        String[] icons = {
            "fas-clock", "fas-sign-in-alt", "fas-sign-out-alt", "fas-coffee", "fas-utensils",
            "fas-mug-hot", "fas-users", "fas-user", "fas-briefcase", "fas-business-time",
            "fas-calendar-day", "fas-calendar-week", "fas-hourglass-half", "fas-car", "fas-truck",
            "fas-walking", "fas-home", "fas-building", "fas-map-marker-alt", "fas-phone",
            "fas-laptop", "fas-tools", "fas-hard-hat", "fas-box", "fas-warehouse",
            "fas-clipboard-list", "fas-user-injured", "fas-umbrella-beach", "fas-graduation-cap",
            "fas-stethoscope", "fas-plane", "fas-handshake", "fas-bolt", "fas-bed",
            "fas-smoking", "fas-phone-volume"
        };
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("labor.cfg_timeclock.editor.pick_icon"));
        javafx.scene.layout.FlowPane grid = new javafx.scene.layout.FlowPane(8, 8);
        grid.setPrefWrapLength(380);
        grid.setPadding(new Insets(10));
        for (String code : icons) {
            Button b = new Button();
            b.setGraphic(icon(code));
            b.setPrefSize(46, 46);
            if (code.equals(current)) b.getStyleClass().add("nav-item-selected");
            b.setOnAction(e -> {
                onPick.accept(code);
                dialog.setResult(ButtonType.CLOSE);
                dialog.close();
            });
            grid.getChildren().add(b);
        }
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        installDialog(dialog, grid);
        dialog.showAndWait();
    }

    private void deleteEventType(com.benjagest.ui.model.TimeClockEventTypeEntry type) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("labor.cfg_timeclock.delete.body") + " " + type.code(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("labor.cfg_timeclock.delete.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.deleteEventType(type.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> refreshLabor.run());
            task.setOnFailed(ev -> showError(t("labor.cfg_timeclock.editor.fail.title"),
                    t("labor.cfg_timeclock.editor.fail.body")));
            start(task, "tc-cfg-delete");
        });
    }
}
