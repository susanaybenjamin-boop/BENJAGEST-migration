package com.benjagest.ui.screens;

import com.benjagest.ui.service.AltaApiClient;
import com.benjagest.ui.support.Router;
import java.util.List;
import java.util.function.Function;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/**
 * NOM-2 — Calendario laboral (sub-pestaña "Tiempo" del módulo Laboral, bloque
 * UIR). Extraída del God Object: gestión de calendarios + festivos (crear/
 * borrar calendario, cargar nacionales, volcar/quitar de la Agenda) e
 * importación desde PDF oficial (BOE/CCAA/convenio) con modal side-by-side
 * (detectados vs importables, replica CONTENDO {@code calendarioParser.v3.js}).
 * Movimiento puro: mismo comportamiento, mismas claves i18n. Depende de
 * {@link AltaApiClient} y los helpers de {@link ScreenBase}. El owner del
 * FileChooser era el {@code root} del shell; ahora se resuelve por el propio
 * nodo montado ({@code viewRoot}).
 */
public class WorkCalendarScreen extends ScreenBase {

    private final AltaApiClient altaApiClient;
    private Node viewRoot;

    public WorkCalendarScreen(AltaApiClient altaApiClient,
                              Function<String, String> tt, Router router) {
        super(tt, router);
        this.altaApiClient = altaApiClient;
    }

    private String humanizeScope(String scope) {
        if (scope == null) return "";
        return switch (scope) {
            case "NATIONAL" -> t("workcal.scope.national");
            case "CCAA"     -> t("workcal.scope.ccaa");
            case "LOCAL"    -> t("workcal.scope.local");
            default         -> scope;
        };
    }

    public Node buildWorkCalendarTab() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));
        Label hint = new Label(t("workcal.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        // Botón principal: importar desde PDF oficial (BOE/BOJA/BOPV/
        // convenio). Decisión 2026-06-09: quitamos el bootstrap auto
        // con seed hardcoded porque NO estaba verificado contra los
        // boletines oficiales — solo los 9 nacionales son ley fija;
        // los autonómicos los puso Claude de memoria sin cruzar con
        // BOJA/BOPV/DOGC/etc. El único camino fiable es el PDF
        // oficial que el usuario descarga de su CCAA.
        Button newCalBtn = new Button(t("workcal.btn.new_calendar"));
        newCalBtn.setGraphic(icon("fas-plus-circle"));
        Button importPdfBtn = new Button(t("workcal.btn.import_pdf"));
        importPdfBtn.setGraphic(icon("fas-file-upload"));
        importPdfBtn.getStyleClass().add("primary-button");
        // Eliminar calendario al lado de "Importar PDF" — Benjamin pidió
        // que esté arriba, no al pie de página (sesión 2026-06-09).
        Button delCalBtn = new Button(t("workcal.btn.delete_calendar"));
        delCalBtn.setGraphic(icon("fas-times-circle"));
        delCalBtn.setDisable(true);
        // Volcar calendario laboral → Agenda general. Útil para que los
        // festivos/ajustes aparezcan en la vista general del módulo
        // Calendar (idempotente — reemplaza, no duplica).
        Button dumpAgendaBtn = new Button(t("workcal.btn.dump_to_agenda"));
        dumpAgendaBtn.setGraphic(icon("fas-calendar-alt"));
        dumpAgendaBtn.setDisable(true);
        // PORT-5 CAL-B — Inversa: quita de la Agenda general los eventos
        // volcados desde este calendario. NO toca eventos manuales.
        Button removeAgendaBtn = new Button(t("workcal.btn.remove_from_agenda"));
        removeAgendaBtn.setGraphic(icon("fas-calendar-minus"));
        removeAgendaBtn.setDisable(true);
        // PORT-5 CAL-C — Carga los 10 festivos nacionales fijos del anio
        // del calendario seleccionado (idempotente).
        Button loadNationalBtn = new Button(t("workcal.btn.load_national"));
        loadNationalBtn.setGraphic(icon("fas-flag"));
        loadNationalBtn.setDisable(true);
        // 6 botones con etiquetas largas: el HBox los cortaba. actionFlow los
        // envuelve manteniendo el texto entero.
        javafx.scene.layout.FlowPane topBar = actionFlow(newCalBtn, importPdfBtn, delCalBtn,
                dumpAgendaBtn, removeAgendaBtn, loadNationalBtn);

        // Tabla calendarios.
        TableView<com.benjagest.ui.model.WorkCalendarEntry> calTable = new TableView<>();
        calTable.getStyleClass().add("data-table");
        calTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        calTable.setPlaceholder(new Label(t("workcal.empty.calendars")));
        TableColumn<com.benjagest.ui.model.WorkCalendarEntry, String> cYear =
                new TableColumn<>(t("workcal.col.year"));
        cYear.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().year())));
        cYear.setPrefWidth(80);
        TableColumn<com.benjagest.ui.model.WorkCalendarEntry, String> cName =
                new TableColumn<>(t("workcal.col.name"));
        cName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        TableColumn<com.benjagest.ui.model.WorkCalendarEntry, String> cCcaa =
                new TableColumn<>(t("workcal.col.ccaa"));
        cCcaa.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().regionCcaa() == null ? "—" : c.getValue().regionCcaa()));
        cCcaa.setPrefWidth(80);
        TableColumn<com.benjagest.ui.model.WorkCalendarEntry, String> cActive =
                new TableColumn<>(t("workcal.col.active"));
        cActive.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().active() ? "✓" : ""));
        cActive.setPrefWidth(60);
        calTable.getColumns().addAll(List.of(cYear, cName, cCcaa, cActive));

        // Tabla festivos.
        TableView<com.benjagest.ui.model.HolidayEntry> holTable = new TableView<>();
        holTable.getStyleClass().add("data-table");
        holTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        holTable.setPlaceholder(new Label(t("workcal.empty.holidays")));
        TableColumn<com.benjagest.ui.model.HolidayEntry, String> hDate =
                new TableColumn<>(t("workcal.col.date"));
        hDate.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().holidayDate() == null ? "" : c.getValue().holidayDate().toString()));
        hDate.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.HolidayEntry, String> hName =
                new TableColumn<>(t("workcal.col.holiday_name"));
        hName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        TableColumn<com.benjagest.ui.model.HolidayEntry, String> hScope =
                new TableColumn<>(t("workcal.col.scope"));
        hScope.setCellValueFactory(c -> new SimpleStringProperty(humanizeScope(c.getValue().scope())));
        hScope.setPrefWidth(140);
        TableColumn<com.benjagest.ui.model.HolidayEntry, String> hNotes =
                new TableColumn<>(t("workcal.col.notes"));
        hNotes.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().notes() == null ? "" : c.getValue().notes()));
        holTable.getColumns().addAll(List.of(hDate, hName, hScope, hNotes));

        // Botones de acción sobre los festivos (delCalBtn vive en el
        // top bar arriba, no aquí).
        Button addHolBtn = new Button(t("workcal.btn.add_holiday"));
        addHolBtn.setGraphic(icon("fas-plus"));
        addHolBtn.setDisable(true);
        Button delHolBtn = new Button(t("workcal.btn.remove_holiday"));
        delHolBtn.setGraphic(icon("fas-trash"));
        delHolBtn.setDisable(true);
        HBox actionBar = new HBox(8, addHolBtn, delHolBtn);
        actionBar.setAlignment(Pos.CENTER_LEFT);

        // Carga inicial.
        Runnable reloadCalendars = () -> {
            Task<List<com.benjagest.ui.model.WorkCalendarEntry>> task = new Task<>() {
                @Override protected List<com.benjagest.ui.model.WorkCalendarEntry> call() throws Exception {
                    return altaApiClient.listWorkCalendars();
                }
            };
            task.setOnSucceeded(ev -> {
                calTable.setItems(FXCollections.observableArrayList(task.getValue()));
                holTable.getItems().clear();
                addHolBtn.setDisable(true);
                delHolBtn.setDisable(true);
                delCalBtn.setDisable(true);
            });
            task.setOnFailed(ev -> showError(t("workcal.load_failed.title"),
                    task.getException() == null ? t("workcal.load_failed.body")
                            : task.getException().getMessage()));
            start(task, "workcal-load");
        };
        Runnable reloadHolidays = () -> {
            var sel = calTable.getSelectionModel().getSelectedItem();
            if (sel == null) { holTable.getItems().clear(); return; }
            Task<List<com.benjagest.ui.model.HolidayEntry>> task = new Task<>() {
                @Override protected List<com.benjagest.ui.model.HolidayEntry> call() throws Exception {
                    return altaApiClient.listHolidaysFor(sel.id());
                }
            };
            task.setOnSucceeded(ev ->
                    holTable.setItems(FXCollections.observableArrayList(task.getValue())));
            task.setOnFailed(ev -> showError(t("workcal.load_failed.title"),
                    task.getException() == null ? t("workcal.load_failed.body")
                            : task.getException().getMessage()));
            start(task, "workcal-holidays-load");
        };

        // Selección de calendario → recargar festivos.
        calTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            boolean sel = nv != null;
            addHolBtn.setDisable(!sel);
            delCalBtn.setDisable(!sel);
            dumpAgendaBtn.setDisable(!sel);
            removeAgendaBtn.setDisable(!sel);
            loadNationalBtn.setDisable(!sel);
            delHolBtn.setDisable(true);  // se habilita al seleccionar festivo
            if (sel) reloadHolidays.run();
            else holTable.getItems().clear();
        });
        holTable.getSelectionModel().selectedItemProperty().addListener(
                (o, ov, nv) -> delHolBtn.setDisable(nv == null));

        // Acciones.
        // 'Crear calendario': solo crea el calendario vacío (sin
        // sembrar festivos). El usuario los carga después con el
        // botón 'Importar desde PDF'. Reusamos el mismo diálogo de
        // bootstrap (año + CCAA + nombre) pero con flag noSeed=true.
        newCalBtn.setOnAction(ev -> openNewCalendarDialog(reloadCalendars));
        importPdfBtn.setOnAction(ev -> {
            var sel = calTable.getSelectionModel().getSelectedItem();
            if (sel == null) {
                showError(t("workcal.error"), t("workcal.import_pdf.select_calendar"));
                return;
            }
            openImportPdfDialog(sel, reloadHolidays);
        });
        addHolBtn.setOnAction(ev -> {
            var sel = calTable.getSelectionModel().getSelectedItem();
            if (sel != null) openAddHolidayDialog(sel.id(), reloadHolidays);
        });
        delHolBtn.setOnAction(ev -> {
            var calSel = calTable.getSelectionModel().getSelectedItem();
            var holSel = holTable.getSelectionModel().getSelectedItem();
            if (calSel == null || holSel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(t("workcal.confirm.delete_holiday.title"));
            confirm.setHeaderText(t("workcal.confirm.delete_holiday.body"));
            confirm.showAndWait().ifPresent(rsp -> {
                if (rsp == javafx.scene.control.ButtonType.OK) {
                    Task<Void> del = new Task<>() {
                        @Override protected Void call() throws Exception {
                            altaApiClient.removeHoliday(calSel.id(), holSel.id());
                            return null;
                        }
                    };
                    del.setOnSucceeded(s -> reloadHolidays.run());
                    del.setOnFailed(s -> showError(t("workcal.error"),
                            del.getException() == null ? "" : del.getException().getMessage()));
                    start(del, "workcal-del-holiday");
                }
            });
        });
        delCalBtn.setOnAction(ev -> {
            var calSel = calTable.getSelectionModel().getSelectedItem();
            if (calSel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(t("workcal.confirm.delete_calendar.title"));
            confirm.setHeaderText(t("workcal.confirm.delete_calendar.body").replace(
                    "{year}", String.valueOf(calSel.year())));
            confirm.showAndWait().ifPresent(rsp -> {
                if (rsp == javafx.scene.control.ButtonType.OK) {
                    Task<Void> del = new Task<>() {
                        @Override protected Void call() throws Exception {
                            altaApiClient.deleteWorkCalendar(calSel.id());
                            return null;
                        }
                    };
                    del.setOnSucceeded(s -> reloadCalendars.run());
                    del.setOnFailed(s -> showError(t("workcal.error"),
                            del.getException() == null ? "" : del.getException().getMessage()));
                    start(del, "workcal-del-cal");
                }
            });
        });
        // Volcar a la Agenda general — copia festivos/ajustes/cierres
        // a calendar_events del módulo Calendar. Idempotente.
        dumpAgendaBtn.setOnAction(ev -> {
            var calSel = calTable.getSelectionModel().getSelectedItem();
            if (calSel == null) return;
            Task<Integer> dump = new Task<>() {
                @Override protected Integer call() throws Exception {
                    return altaApiClient.dumpWorkCalendarToAgenda(calSel.id());
                }
            };
            dump.setOnSucceeded(s -> {
                Alert ok = new Alert(Alert.AlertType.INFORMATION);
                ok.setTitle(t("workcal.dump_agenda.success.title"));
                ok.setHeaderText(t("workcal.dump_agenda.success.body")
                        .replace("{n}", String.valueOf(dump.getValue())));
                ok.showAndWait();
            });
            dump.setOnFailed(s -> showError(t("workcal.dump_agenda.fail.title"),
                    dump.getException() == null ? "" : dump.getException().getMessage()));
            start(dump, "workcal-dump-agenda");
        });

        // PORT-5 CAL-C — Cargar los 10 festivos nacionales fijos del anio
        // del calendario seleccionado. Idempotente — solo anade los que
        // faltan. El usuario completa con los autonomicos via PDF o a mano.
        loadNationalBtn.setOnAction(ev -> {
            var calSel = calTable.getSelectionModel().getSelectedItem();
            if (calSel == null) return;
            Task<Integer> load = new Task<>() {
                @Override protected Integer call() throws Exception {
                    return altaApiClient.loadNationalHolidays(calSel.id());
                }
            };
            load.setOnSucceeded(s -> {
                Alert ok = new Alert(Alert.AlertType.INFORMATION);
                ok.setTitle(t("workcal.load_national.success.title"));
                ok.setHeaderText(t("workcal.load_national.success.body")
                        .replace("{n}", String.valueOf(load.getValue()))
                        .replace("{year}", String.valueOf(calSel.year())));
                ok.showAndWait();
                reloadHolidays.run();
            });
            load.setOnFailed(s -> showError(
                    t("workcal.load_national.fail.title"),
                    load.getException() == null ? "" : load.getException().getMessage()));
            start(load, "workcal-load-national");
        });

        // PORT-5 CAL-B — Quitar de la Agenda general los eventos volcados
        // desde este calendario laboral. Idempotente y solo borra eventos
        // con source_type='WORK_CALENDAR'; los eventos manuales sobreviven.
        removeAgendaBtn.setOnAction(ev -> {
            var calSel = calTable.getSelectionModel().getSelectedItem();
            if (calSel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(t("workcal.remove_agenda.confirm.title"));
            confirm.setHeaderText(t("workcal.remove_agenda.confirm.body")
                    .replace("{year}", String.valueOf(calSel.year())));
            confirm.showAndWait().ifPresent(rsp -> {
                if (rsp == javafx.scene.control.ButtonType.OK) {
                    Task<Integer> rem = new Task<>() {
                        @Override protected Integer call() throws Exception {
                            return altaApiClient.removeWorkCalendarFromAgenda(calSel.id());
                        }
                    };
                    rem.setOnSucceeded(s -> {
                        Alert ok = new Alert(Alert.AlertType.INFORMATION);
                        ok.setTitle(t("workcal.remove_agenda.success.title"));
                        ok.setHeaderText(t("workcal.remove_agenda.success.body")
                                .replace("{n}", String.valueOf(rem.getValue())));
                        ok.showAndWait();
                    });
                    rem.setOnFailed(s -> showError(
                            t("workcal.remove_agenda.fail.title"),
                            rem.getException() == null ? "" : rem.getException().getMessage()));
                    start(rem, "workcal-remove-agenda");
                }
            });
        });

        reloadCalendars.run();

        VBox.setVgrow(calTable, Priority.SOMETIMES);
        VBox.setVgrow(holTable, Priority.ALWAYS);
        box.getChildren().addAll(hint, topBar,
                new Label(t("workcal.section.calendars")), calTable,
                new Label(t("workcal.section.holidays")), holTable, actionBar);
        this.viewRoot = box;
        return box;
    }

    /**
     * Diálogo para crear un calendario VACÍO. Año + CCAA + municipio
     * + nombre. NO siembra festivos — el usuario los carga después con
     * el botón 'Importar desde PDF' que sube el calendario oficial de
     * su CCAA (único camino vinculante: el BOJA/BOPV/DOGC/etc.).
     */
    private void openNewCalendarDialog(Runnable onSuccess) {
        Dialog<javafx.scene.control.ButtonType> dlg = new Dialog<>();
        dlg.setTitle(t("workcal.new_cal.title"));
        dlg.setHeaderText(t("workcal.new_cal.header"));
        TextField year = new TextField(String.valueOf(java.time.LocalDate.now().getYear()));
        ComboBox<String> ccaa = new ComboBox<>(FXCollections.observableArrayList(
                "", "AN", "AR", "AS", "IB", "CN", "CB", "CL", "CM", "CT",
                "VC", "EX", "GA", "MD", "MC", "NC", "PV", "RI", "CE", "ML"));
        ccaa.setValue("");
        ccaa.setMaxWidth(Double.MAX_VALUE);
        TextField muni = new TextField();
        muni.setPromptText(t("workcal.bootstrap.muni_placeholder"));
        TextField name = new TextField();
        name.setPromptText("Calendario " + year.getText());
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(8));
        grid.addRow(0, new Label(t("workcal.bootstrap.year")), year);
        grid.addRow(1, new Label(t("workcal.bootstrap.ccaa")), ccaa);
        grid.addRow(2, new Label(t("workcal.bootstrap.muni")), muni);
        grid.addRow(3, new Label(t("workcal.bootstrap.name")), name);
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);
        dlg.showAndWait().ifPresent(bt -> {
            if (bt == javafx.scene.control.ButtonType.OK) {
                int y;
                try { y = Integer.parseInt(year.getText().trim()); }
                catch (NumberFormatException ex) {
                    showError(t("workcal.error"), t("workcal.new_cal.bad_year"));
                    return;
                }
                Task<com.benjagest.ui.model.WorkCalendarEntry> task = new Task<>() {
                    @Override protected com.benjagest.ui.model.WorkCalendarEntry call() throws Exception {
                        return altaApiClient.createEmptyWorkCalendar(y,
                                ccaa.getValue() == null || ccaa.getValue().isBlank() ? null : ccaa.getValue(),
                                muni.getText() == null || muni.getText().isBlank() ? null : muni.getText().trim(),
                                name.getText() == null || name.getText().isBlank() ? null : name.getText().trim());
                    }
                };
                task.setOnSucceeded(s -> onSuccess.run());
                task.setOnFailed(s -> showError(t("workcal.new_cal.fail.title"),
                        task.getException() == null ? "" : task.getException().getMessage()));
                start(task, "workcal-new");
            }
        });
    }

    /** Diálogo añadir festivo: DatePicker + nombre + scope + notas. */
    private void openAddHolidayDialog(String calendarId, Runnable onSuccess) {
        Dialog<javafx.scene.control.ButtonType> dlg = new Dialog<>();
        dlg.setTitle(t("workcal.add_holiday.title"));
        dlg.setHeaderText(t("workcal.add_holiday.header"));
        DatePicker date = new DatePicker();
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(date);
        date.setMaxWidth(Double.MAX_VALUE);
        TextField name = new TextField();
        name.setPromptText(t("workcal.add_holiday.name_placeholder"));
        ComboBox<String> scope = new ComboBox<>(FXCollections.observableArrayList(
                "NATIONAL", "CCAA", "LOCAL"));
        scope.setValue("LOCAL");
        scope.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) { return s == null ? "" : humanizeScope(s); }
            @Override public String fromString(String s) { return null; }
        });
        scope.setMaxWidth(Double.MAX_VALUE);
        TextField notes = new TextField();
        notes.setPromptText(t("workcal.add_holiday.notes_placeholder"));
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(8));
        grid.addRow(0, new Label(t("workcal.col.date")), date);
        grid.addRow(1, new Label(t("workcal.col.holiday_name")), name);
        grid.addRow(2, new Label(t("workcal.col.scope")), scope);
        grid.addRow(3, new Label(t("workcal.col.notes")), notes);
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);
        dlg.showAndWait().ifPresent(bt -> {
            if (bt == javafx.scene.control.ButtonType.OK) {
                if (date.getValue() == null || name.getText() == null || name.getText().isBlank()) {
                    showError(t("workcal.error"), t("workcal.add_holiday.fail.missing"));
                    return;
                }
                Task<Void> task = new Task<>() {
                    @Override protected Void call() throws Exception {
                        altaApiClient.addHoliday(calendarId, date.getValue(),
                                name.getText().trim(), scope.getValue(), true,
                                notes.getText() == null || notes.getText().isBlank() ? null
                                        : notes.getText().trim());
                        return null;
                    }
                };
                task.setOnSucceeded(s -> onSuccess.run());
                task.setOnFailed(s -> showError(t("workcal.add_holiday.fail.title"),
                        task.getException() == null ? "" : task.getException().getMessage()));
                start(task, "workcal-add-holiday");
            }
        });
    }

    /**
     * CAL-IMPORT-MODAL — diálogo de importación desde PDF (replica
     * CONTENDO {@code calendarioParser.v3.js}). Flujo:
     * <ol>
     *   <li>FileChooser para elegir el PDF descargado del BOE/CCAA/
     *       convenio colectivo.</li>
     *   <li>Llamada al backend para extraer detectados.</li>
     *   <li>Modal con SplitPane:
     *     <ul>
     *       <li>Panel izquierdo: tabla READ-ONLY con detectados +
     *           confianza + línea fuente. Scroll independiente.</li>
     *       <li>Panel derecho: tabla EDITABLE con lo que se va a
     *           importar. Scroll independiente. Botones "← Copiar
     *           todo", "← Copiar selección", "Añadir fila",
     *           "Eliminar fila".</li>
     *     </ul>
     *   </li>
     *   <li>Botón "Volcar al calendario" → replaceHolidays en backend.</li>
     * </ol>
     */
    private void openImportPdfDialog(com.benjagest.ui.model.WorkCalendarEntry calendar,
                                       Runnable onSuccess) {
        // 1. FileChooser
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle(t("workcal.import_pdf.choose_file"));
        fc.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("PDF", "*.pdf"));
        java.io.File picked = fc.showOpenDialog(viewRoot == null || viewRoot.getScene() == null
                ? null : viewRoot.getScene().getWindow());
        if (picked == null) return;

        // 2. Llamada al backend extract-pdf
        Task<com.benjagest.ui.model.HolidayPdfPreview> extractTask = new Task<>() {
            @Override protected com.benjagest.ui.model.HolidayPdfPreview call() throws Exception {
                return altaApiClient.extractHolidaysFromPdf(picked);
            }
        };
        extractTask.setOnFailed(ev -> showError(t("workcal.import_pdf.fail.title"),
                extractTask.getException() == null ? "" : extractTask.getException().getMessage()));
        extractTask.setOnSucceeded(ev -> showImportPreviewModal(
                calendar, picked.getName(), extractTask.getValue(), onSuccess));
        start(extractTask, "workcal-import-extract");
    }

    /** Muestra el modal side-by-side con detectados (izq) e importables (dcha). */
    private void showImportPreviewModal(com.benjagest.ui.model.WorkCalendarEntry calendar,
                                          String fileName,
                                          com.benjagest.ui.model.HolidayPdfPreview preview,
                                          Runnable onSuccess) {
        Stage modal = new Stage();
        modal.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        modal.setTitle(t("workcal.import_pdf.modal_title"));

        // Header: año detectado + nombre archivo + nº detectados.
        Label headerLbl = new Label(t("workcal.import_pdf.modal_header")
                .replace("{file}", fileName)
                .replace("{year}", String.valueOf(preview.year()))
                .replace("{n}", String.valueOf(preview.holidays().size())));
        headerLbl.setWrapText(true);
        headerLbl.getStyleClass().add("settings-hint");

        // -------- IZQUIERDA: tabla detectados read-only --------
        TableView<com.benjagest.ui.model.HolidayPdfPreview.DetectedHoliday> leftTable =
                new TableView<>();
        leftTable.getStyleClass().add("data-table");
        leftTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        leftTable.getSelectionModel().setSelectionMode(
                javafx.scene.control.SelectionMode.MULTIPLE);
        TableColumn<com.benjagest.ui.model.HolidayPdfPreview.DetectedHoliday, String> lDate =
                new TableColumn<>(t("workcal.col.date"));
        lDate.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().date() == null ? "" : c.getValue().date().toString()));
        lDate.setPrefWidth(100);
        TableColumn<com.benjagest.ui.model.HolidayPdfPreview.DetectedHoliday, String> lName =
                new TableColumn<>(t("workcal.col.holiday_name"));
        lName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        TableColumn<com.benjagest.ui.model.HolidayPdfPreview.DetectedHoliday, String> lScope =
                new TableColumn<>(t("workcal.col.scope"));
        lScope.setCellValueFactory(c -> new SimpleStringProperty(humanizeScope(c.getValue().scope())));
        lScope.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.HolidayPdfPreview.DetectedHoliday, String> lConf =
                new TableColumn<>(t("workcal.import_pdf.col_confidence"));
        lConf.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().confidence()));
        lConf.setPrefWidth(90);
        leftTable.getColumns().addAll(List.of(lDate, lName, lScope, lConf));
        leftTable.setItems(FXCollections.observableArrayList(preview.holidays()));
        com.benjagest.ui.support.TableSelectionHelper.install(leftTable);

        Label leftLabel = new Label(t("workcal.import_pdf.section_detected"));
        leftLabel.getStyleClass().add("module-detail-title");
        VBox leftPanel = new VBox(6, leftLabel, leftTable);
        VBox.setVgrow(leftTable, Priority.ALWAYS);
        leftPanel.setPadding(new Insets(8));

        // -------- DERECHA: tabla editable de lo que se importa --------
        // Wrapper editable porque HolidayEntry es record inmutable.
        ObservableList<EditableHolidayRow> rightRows = FXCollections.observableArrayList();

        TableView<EditableHolidayRow> rightTable = new TableView<>();
        rightTable.getStyleClass().add("data-table");
        rightTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        rightTable.setEditable(true);
        rightTable.getSelectionModel().setSelectionMode(
                javafx.scene.control.SelectionMode.MULTIPLE);
        // Atajos uniformes: Escape / click vacío → deselecciona.
        com.benjagest.ui.support.TableSelectionHelper.install(rightTable);

        // Fecha — TextField flexible estilo CONTENDO. Acepta dd/MM/yyyy,
        // dd-MM-yyyy, ISO, etc. SIN popup, SIN DatePicker, SIN VirtualFlow
        // problemas. Bug Benjamin 2026-06-09 ("01/01/2026 aparecía dos
        // veces" al volcar): el DatePicker tiene popup con su propio
        // focus listener; en tabla larga + SplitPane el commit a veces
        // no se disparaba antes del read → fecha por defecto del 1-ene
        // se mantenía. Con TextField simple desaparece la causa raíz.
        TableColumn<EditableHolidayRow, java.time.LocalDate> rDate =
                new TableColumn<>(t("workcal.col.date"));
        rDate.setCellValueFactory(c -> c.getValue().dateValue);
        rDate.setCellFactory(com.benjagest.ui.support.EditableCells.flexibleDateTextField());
        rDate.setOnEditCommit(ev -> ev.getRowValue().dateValue.set(ev.getNewValue()));
        rDate.setPrefWidth(140);

        // Tipo — combo FESTIVO / AJUSTE / CIERRE.
        TableColumn<EditableHolidayRow, String> rType =
                new TableColumn<>(t("workcal.col.type"));
        rType.setCellValueFactory(c -> c.getValue().holidayType);
        rType.setCellFactory(javafx.scene.control.cell.ComboBoxTableCell.forTableColumn(
                "FESTIVO", "AJUSTE", "CIERRE"));
        rType.setOnEditCommit(ev -> ev.getRowValue().holidayType.set(ev.getNewValue()));
        rType.setPrefWidth(100);

        // Nombre — TextField con commit-on-blur (no requiere Enter).
        TableColumn<EditableHolidayRow, String> rName =
                new TableColumn<>(t("workcal.col.holiday_name"));
        rName.setCellValueFactory(c -> c.getValue().name);
        rName.setCellFactory(com.benjagest.ui.support.EditableCells.textFieldCommitOnBlur());
        rName.setOnEditCommit(ev -> ev.getRowValue().name.set(ev.getNewValue()));

        // Scope — combo.
        TableColumn<EditableHolidayRow, String> rScope =
                new TableColumn<>(t("workcal.col.scope"));
        rScope.setCellValueFactory(c -> c.getValue().scope);
        rScope.setCellFactory(javafx.scene.control.cell.ComboBoxTableCell.forTableColumn(
                "NATIONAL", "CCAA", "LOCAL"));
        rScope.setOnEditCommit(ev -> ev.getRowValue().scope.set(ev.getNewValue()));
        rScope.setPrefWidth(110);

        // Notas — TextField commit-on-blur.
        TableColumn<EditableHolidayRow, String> rNotes =
                new TableColumn<>(t("workcal.col.notes"));
        rNotes.setCellValueFactory(c -> c.getValue().notes);
        rNotes.setCellFactory(com.benjagest.ui.support.EditableCells.textFieldCommitOnBlur());
        rNotes.setOnEditCommit(ev -> ev.getRowValue().notes.set(ev.getNewValue()));

        rightTable.getColumns().addAll(List.of(rDate, rType, rName, rScope, rNotes));
        rightTable.setItems(rightRows);

        Label rightLabel = new Label(t("workcal.import_pdf.section_to_import"));
        rightLabel.getStyleClass().add("module-detail-title");

        // Botones panel derecho.
        Button copyAllBtn = new Button(t("workcal.import_pdf.btn.copy_all"));
        copyAllBtn.setGraphic(icon("fas-angle-double-right"));
        Button copySelBtn = new Button(t("workcal.import_pdf.btn.copy_selected"));
        copySelBtn.setGraphic(icon("fas-angle-right"));
        Button addRowBtn = new Button(t("workcal.import_pdf.btn.add_row"));
        addRowBtn.setGraphic(icon("fas-plus"));
        Button delRowBtn = new Button(t("workcal.import_pdf.btn.del_row"));
        delRowBtn.setGraphic(icon("fas-trash"));
        delRowBtn.setDisable(true);
        rightTable.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<EditableHolidayRow>) c ->
                        delRowBtn.setDisable(rightTable.getSelectionModel().getSelectedItems().isEmpty()));
        // actionFlow: si las 4 acciones no caben, envuelven en vez de cortarse.
        javafx.scene.layout.FlowPane rightActions = actionFlow(
                copyAllBtn, copySelBtn, addRowBtn, delRowBtn);

        copyAllBtn.setOnAction(ev -> {
            rightRows.clear();
            for (var det : preview.holidays()) {
                rightRows.add(EditableHolidayRow.from(det));
            }
        });
        copySelBtn.setOnAction(ev -> {
            for (var det : leftTable.getSelectionModel().getSelectedItems()) {
                // Evita duplicar por fecha si ya está.
                java.time.LocalDate dd = det.date();
                boolean already = rightRows.stream().anyMatch(
                        r -> dd != null && dd.equals(r.dateValue.get()));
                if (!already) rightRows.add(EditableHolidayRow.from(det));
            }
        });
        // Default AJUSTE en filas añadidas a mano: por experiencia, lo
        // que el usuario añade después del extract suele ser un ajuste
        // de jornada que el parser no pilló (Benjamin 2026-06-09).
        addRowBtn.setOnAction(ev -> rightRows.add(new EditableHolidayRow(
                java.time.LocalDate.of(preview.year(), 1, 1),
                "AJUSTE", "", "LOCAL", "")));
        delRowBtn.setOnAction(ev -> rightRows.removeAll(
                new java.util.ArrayList<>(rightTable.getSelectionModel().getSelectedItems())));

        // Auto-clasificación por nombre: si la desc contiene "ajuste"
        // / "convenio" / "jornada", forzamos tipo=AJUSTE. Esto cubre
        // tanto filas añadidas a mano como ediciones del usuario.
        rightRows.addListener(
                (javafx.collections.ListChangeListener<EditableHolidayRow>) change -> {
                    while (change.next()) {
                        if (change.wasAdded()) {
                            for (var row : change.getAddedSubList()) {
                                row.name.addListener((obs, oldV, newV) -> {
                                    if (newV == null) return;
                                    String low = newV.toLowerCase(java.util.Locale.ROOT);
                                    if ((low.contains("ajuste") || low.contains("convenio")
                                            || low.contains("jornada"))
                                            && "FESTIVO".equals(row.holidayType.get())) {
                                        row.holidayType.set("AJUSTE");
                                    }
                                });
                            }
                        }
                    }
                });

        VBox rightPanel = new VBox(6, rightLabel, rightTable, rightActions);
        VBox.setVgrow(rightTable, Priority.ALWAYS);
        rightPanel.setPadding(new Insets(8));

        SplitPane split = new SplitPane(leftPanel, rightPanel);
        split.setDividerPositions(0.5);
        VBox.setVgrow(split, Priority.ALWAYS);

        // -------- FOOTER --------
        Button cancelBtn = new Button(t("workcal.import_pdf.btn.cancel"));
        Button dumpBtn = new Button(t("workcal.import_pdf.btn.dump"));
        dumpBtn.setGraphic(icon("fas-check"));
        dumpBtn.getStyleClass().add("primary-button");

        // Contador en vivo: "X festivos · Y ajustes · 14 máx" — se
        // pone en rojo si supera el tope legal. Avisa antes de que
        // el usuario pulse "Volcar" y le evita el error del backend.
        Label counterLbl = new Label();
        counterLbl.getStyleClass().add("settings-hint");
        Runnable updateCounter = () -> {
            long fest = rightRows.stream()
                    .filter(r -> "FESTIVO".equals(r.holidayType.get())).count();
            long aju = rightRows.stream()
                    .filter(r -> "AJUSTE".equals(r.holidayType.get())).count();
            long cie = rightRows.stream()
                    .filter(r -> "CIERRE".equals(r.holidayType.get())).count();
            String txt = t("workcal.import_pdf.counter")
                    .replace("{f}", String.valueOf(fest))
                    .replace("{a}", String.valueOf(aju))
                    .replace("{c}", String.valueOf(cie));
            counterLbl.setText(txt);
            // Color rojo si supera el tope — sin tocar estilos CSS
            // globales, solo inline.
            counterLbl.setStyle(fest > 14
                    ? "-fx-text-fill: #c0392b; -fx-font-weight: bold;"
                    : "");
            dumpBtn.setDisable(fest > 14);
        };
        rightRows.addListener(
                (javafx.collections.ListChangeListener<EditableHolidayRow>) c -> {
                    updateCounter.run();
                    // Re-suscribir cuando holidayType cambia en filas existentes.
                    while (c.next()) {
                        if (c.wasAdded()) {
                            for (var row : c.getAddedSubList()) {
                                row.holidayType.addListener((o,b,n) -> updateCounter.run());
                            }
                        }
                    }
                });
        updateCounter.run();

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, counterLbl, spacer, cancelBtn, dumpBtn);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(8, 16, 8, 16));

        cancelBtn.setOnAction(ev -> modal.close());
        // Acción del volcado, separada para poder llamarla desde
        // Platform.runLater tras forzar commit de cualquier edición
        // pendiente — el usuario puede haber tecleado una fecha en un
        // DatePicker pero no pulsar Enter; al hacer click en "Volcar"
        // forzamos el commit moviendo el foco al propio botón.
        Runnable doDumpAction = () -> {
            if (rightRows.isEmpty()) {
                showError(t("workcal.error"), t("workcal.import_pdf.empty_dump"));
                return;
            }
            // Validar todas las filas: fecha presente + nombre + scope.
            List<com.benjagest.ui.model.HolidayEntry> toSave = new java.util.ArrayList<>();
            java.util.Set<java.time.LocalDate> seenDates = new java.util.HashSet<>();
            long festCount = 0;
            for (var r : rightRows) {
                java.time.LocalDate d = r.dateValue.get();
                if (d == null) {
                    showError(t("workcal.error"),
                            t("workcal.import_pdf.bad_date").replace("{v}", "(vacía)"));
                    return;
                }
                if (!seenDates.add(d)) {
                    showError(t("workcal.error"),
                            t("workcal.import_pdf.dup_date").replace("{v}", d.toString()));
                    return;
                }
                String n = r.name.get();
                if (n == null || n.isBlank()) {
                    showError(t("workcal.error"), t("workcal.import_pdf.empty_name"));
                    return;
                }
                String ht = r.holidayType.get() == null ? "FESTIVO" : r.holidayType.get();
                if ("FESTIVO".equals(ht)) festCount++;
                toSave.add(new com.benjagest.ui.model.HolidayEntry(
                        null, calendar.id(), d, n.trim(), r.scope.get(), true,
                        r.notes.get() == null || r.notes.get().isBlank() ? null
                                : r.notes.get().trim(),
                        ht, null));
            }
            // Aviso anticipado del tope (la UI ya deshabilita el botón
            // pero si el usuario fuerza por teclado, este check rebota
            // con un mensaje legible antes del round-trip al backend).
            if (festCount > 14) {
                showError(t("workcal.error"),
                        t("workcal.import_pdf.too_many_festivos")
                                .replace("{n}", String.valueOf(festCount)));
                return;
            }
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    altaApiClient.replaceHolidaysInCalendar(calendar.id(), toSave);
                    return null;
                }
            };
            task.setOnSucceeded(s -> { modal.close(); onSuccess.run(); });
            task.setOnFailed(s -> showError(t("workcal.import_pdf.dump_fail"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "workcal-import-dump");
        };
        dumpBtn.setOnAction(ev -> {
            // Fuerza commit de cualquier DatePicker abierto en la tabla
            // cuyo editor tenga texto tecleado sin confirmar. Sin esto,
            // las fechas tecleadas en formato 'dd/MM/yyyy' (con año de
            // 4 dígitos) quedaban en el valor por defecto del 'Añadir
            // fila' (1 ene) porque el converter de JavaFX para es_ES
            // espera año de 2 dígitos. Bug Benjamin 2026-06-09.
            com.benjagest.ui.support.EditableCells
                    .commitPendingDatePickerEdits(rightTable);
            // También movemos foco al botón por si hay un TextField
            // (nombre, notas) con texto sin commitear.
            dumpBtn.requestFocus();
            // runLater para dejar que el commit propague antes de leer
            // los valores de las filas en la validación.
            javafx.application.Platform.runLater(doDumpAction);
        });

        VBox root = new VBox(8, headerLbl, split, footer);
        root.setPadding(new Insets(8));
        Scene scene = new Scene(root, 1100, 600);
        modal.setScene(scene);
        modal.showAndWait();
    }

    /** Fila editable mutable para el panel derecho del modal CAL-IMPORT. */
    private static class EditableHolidayRow {
        final javafx.beans.property.SimpleObjectProperty<java.time.LocalDate> dateValue;
        final javafx.beans.property.SimpleStringProperty holidayType;
        final javafx.beans.property.SimpleStringProperty name;
        final javafx.beans.property.SimpleStringProperty scope;
        final javafx.beans.property.SimpleStringProperty notes;

        EditableHolidayRow(java.time.LocalDate date, String holidayType,
                String name, String scope, String notes) {
            this.dateValue = new javafx.beans.property.SimpleObjectProperty<>(date);
            this.holidayType = new javafx.beans.property.SimpleStringProperty(
                    holidayType == null || holidayType.isBlank() ? "FESTIVO" : holidayType);
            this.name = new javafx.beans.property.SimpleStringProperty(name);
            this.scope = new javafx.beans.property.SimpleStringProperty(scope);
            this.notes = new javafx.beans.property.SimpleStringProperty(notes);
        }

        static EditableHolidayRow from(com.benjagest.ui.model.HolidayPdfPreview.DetectedHoliday d) {
            return new EditableHolidayRow(
                    d.date(),
                    d.holidayType() == null ? "FESTIVO" : d.holidayType(),
                    d.name() == null ? "" : d.name(),
                    d.scope() == null ? "LOCAL" : d.scope(),
                    "");
        }
    }
}
