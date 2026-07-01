package com.benjagest.ui.screens;

import com.benjagest.ui.service.AltaApiClient;
import com.benjagest.ui.service.LaborApiClient;
import com.benjagest.ui.support.Router;
import java.util.function.Consumer;
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
 * NOM-11 — Empleados (categoría "Personal" del módulo Laboral, bloque UIR). El
 * último slice: pestaña Empleados (alta/edición completa con acceso a la app +
 * PIN), baja/despido con finiquito e indemnización (+ documentos), suspensiones/
 * excedencias (CL-4a), atrasos (CL-4b), cese de empresa colectivo (CL-4c),
 * modelo 145 IRPF, e invitación a la PWA. Movimiento puro: mismo comportamiento,
 * mismas claves i18n. Depende de {@link LaborApiClient} + {@link AltaApiClient},
 * callbacks de refresco (refreshLabor / refreshLaborAndJournal — el finiquito y
 * el cese crean asientos) y {@code openEmployeeContracts} (abre ContractsScreen,
 * NOM-10). El owner del FileChooser de documentos era el {@code root} del shell;
 * ahora se resuelve por el nodo montado ({@code viewRoot}).
 */
public class EmployeesScreen extends ScreenBase {

    private final LaborApiClient laborApiClient;
    private final AltaApiClient altaApiClient;
    private final Runnable refreshLabor;
    private final Runnable refreshLaborAndJournalCb;
    private final Consumer<com.benjagest.ui.model.EmployeeEntry> openEmployeeContracts;
    private TableView<com.benjagest.ui.model.EmployeeEntry> employeesTable;
    private Node viewRoot;

    public EmployeesScreen(LaborApiClient laborApiClient, AltaApiClient altaApiClient,
                           Function<String, String> tt, Router router,
                           Runnable refreshLabor, Runnable refreshLaborAndJournal,
                           Consumer<com.benjagest.ui.model.EmployeeEntry> openEmployeeContracts) {
        super(tt, router);
        this.laborApiClient = laborApiClient;
        this.altaApiClient = altaApiClient;
        this.refreshLabor = refreshLabor;
        this.refreshLaborAndJournalCb = refreshLaborAndJournal;
        this.openEmployeeContracts = openEmployeeContracts;
    }

    /** Refresca Labor + Contabilidad (acciones que tocan asientos: finiquito, cese). */
    private void refreshLaborAndJournal() { refreshLaborAndJournalCb.run(); }

    /** Abre los contratos del empleado (delega en ContractsScreen, NOM-10). */
    private void showEmployeeContracts(com.benjagest.ui.model.EmployeeEntry e) { openEmployeeContracts.accept(e); }

    private javafx.stage.Window window() {
        return viewRoot == null || viewRoot.getScene() == null
                ? null : viewRoot.getScene().getWindow();
    }

    // ----- Helpers compartidos (copiados del shell) -----

    private static String humanizeBackendError(String raw) {
        return com.benjagest.ui.support.BackendErrors.humanize(raw);
    }

    private String humanizeFromKey(String key, String fallback) {
        if (key == null || key.isBlank()) return fallback == null ? "" : fallback;
        String translated = t(key);
        return key.equals(translated) ? (fallback == null ? "" : fallback) : translated;
    }

    private String humanizeMemberRole(String code) {
        if (code == null || code.isBlank()) return "";
        String key = "team.member_role." + code;
        String translated = t(key);
        return key.equals(translated) ? code : translated;
    }

    // ===================================================================
    //  Baja / despido (finiquito + indemnización + documentos)
    // ===================================================================

    private void showTerminationDialog(com.benjagest.ui.model.EmployeeEntry employee) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.term.title") + " — " + employee.fullName());
        ButtonType confirmBt = new ButtonType(t("labor.term.confirm"), ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(confirmBt, ButtonType.CANCEL);

        DatePicker ceseDate = new DatePicker(java.time.LocalDate.now());
        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("VOLUNTARY", "END_OF_CONTRACT", "DISMISSAL_OBJECTIVE",
                "DISMISSAL_UNFAIR", "DISMISSAL_DISCIPLINARY", "RETIREMENT");
        typeCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) { return s == null ? "" : t("labor.term.type." + s); }
            @Override public String fromString(String s) { return null; }
        });
        typeCombo.getSelectionModel().select("DISMISSAL_OBJECTIVE");
        ComboBox<String> accrualCombo = new ComboBox<>();
        accrualCombo.getItems().addAll("SEMIANNUAL", "ANNUAL");
        accrualCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) {
                return "ANNUAL".equals(s) ? t("labor.settlement.accrual.annual") : t("labor.settlement.accrual.semi");
            }
            @Override public String fromString(String s) { return null; }
        });
        accrualCombo.getSelectionModel().select("SEMIANNUAL");
        TextField otherField = new TextField();
        otherField.setPromptText(t("labor.payslips.calc.other_deductions.prompt"));
        TextArea notesArea = new TextArea(); notesArea.setPrefRowCount(2);

        Label summary = new Label(t("labor.term.preview.empty"));
        summary.getStyleClass().add("settings-hint");
        summary.setWrapText(true);
        Button previewBtn = new Button(t("labor.payslips.calc.preview_btn"));
        previewBtn.getStyleClass().add("button-secondary");
        Runnable doPreview = () -> {
            java.time.LocalDate ce = ceseDate.getValue();
            if (ce == null || typeCombo.getValue() == null) return;
            Task<com.benjagest.ui.model.TerminationPreviewEntry> tk = new Task<>() {
                @Override protected com.benjagest.ui.model.TerminationPreviewEntry call() throws Exception {
                    return laborApiClient.previewTermination(employee.id(), ce, typeCombo.getValue(),
                            accrualCombo.getValue(), parseDecSafe(otherField.getText()),
                            blankToNullOrSelf(notesArea.getText()));
                }
            };
            tk.setOnSucceeded(ev -> {
                var p = tk.getValue();
                summary.setText(t("labor.term.preview.text")
                        .replace("{gross}", money(p.settlementGross()))
                        .replace("{ss}", money(p.settlementSs()))
                        .replace("{irpf}", money(p.settlementIrpf()))
                        .replace("{net}", money(p.settlementNet()))
                        .replace("{years}", formatAntiquity(p.antiqYears(), p.antiqMonths(), p.antiqDays()))
                        .replace("{sevdays}", p.sevDays() == null ? "0" : p.sevDays().toPlainString())
                        .replace("{sev}", money(p.sevGross()))
                        .replace("{sevexempt}", money(p.sevExempt()))
                        .replace("{sevtax}", money(p.sevTaxable())));
            });
            tk.setOnFailed(ev -> {
                Throwable ex = tk.getException();
                String dd = ex == null ? null : humanizeBackendError(ex.getMessage());
                summary.setText(dd == null || dd.isBlank() ? t("labor.payslips.calc.fail.body") : dd);
            });
            start(tk, "term-preview");
        };
        previewBtn.setOnAction(e -> doPreview.run());
        ceseDate.valueProperty().addListener((o, ov, nv) -> doPreview.run());
        typeCombo.valueProperty().addListener((o, ov, nv) -> doPreview.run());
        accrualCombo.valueProperty().addListener((o, ov, nv) -> doPreview.run());

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(10));
        int r = 0;
        g.add(new Label(t("labor.settlement.cese_date")), 0, r); g.add(ceseDate, 1, r++);
        g.add(new Label(t("labor.term.reason")), 0, r); g.add(typeCombo, 1, r++);
        g.add(new Label(t("labor.settlement.accrual")), 0, r); g.add(accrualCombo, 1, r++);
        g.add(new Label(t("labor.payslips.calc.other_deductions")), 0, r); g.add(otherField, 1, r++);
        g.add(new Label(t("labor.payslips.calc.notes")), 0, r); g.add(notesArea, 1, r++);

        Label hint = new Label(t("labor.term.hint"));
        hint.getStyleClass().add("settings-hint"); hint.setWrapText(true);
        VBox box = new VBox(10, hint, g, new Separator(), new HBox(8, previewBtn), summary);
        installDialog(d, box);
        javafx.application.Platform.runLater(doPreview);
        d.showAndWait().ifPresent(bt -> {
            if (bt != confirmBt) return;
            java.time.LocalDate ce = ceseDate.getValue();
            if (ce == null || typeCombo.getValue() == null) {
                showError(t("labor.term.title"), t("labor.term.reason")); return;
            }
            Alert c = new Alert(Alert.AlertType.CONFIRMATION,
                    t("labor.term.confirm_body").replace("{name}", employee.fullName())
                            .replace("{date}", ce.toString()),
                    ButtonType.OK, ButtonType.CANCEL);
            c.setHeaderText(t("labor.term.confirm"));
            c.showAndWait().ifPresent(bt2 -> {
                if (bt2 != ButtonType.OK) return;
                Task<Void> tk = new Task<>() {
                    @Override protected Void call() throws Exception {
                        laborApiClient.executeTermination(employee.id(), ce, typeCombo.getValue(),
                                accrualCombo.getValue(), parseDecSafe(otherField.getText()),
                                blankToNullOrSelf(notesArea.getText()));
                        return null;
                    }
                };
                tk.setOnSucceeded(ev -> {
                    // El finiquito crea asiento (SETTLEMENT) y cierra el contrato:
                    // refresca Labor + Contabilidad antes de los documentos de baja.
                    refreshLaborAndJournal();
                    showTerminationDocsDialog(employee, ce, typeCombo.getValue());
                });
                tk.setOnFailed(ev -> showError(t("labor.term.title"),
                        humanizeBackendError(tk.getException() == null ? "" : tk.getException().getMessage())));
                start(tk, "term-execute");
            });
        });
    }

    /** Antigüedad legible: "X años, Y meses y Z días" (omite las partes a 0). */
    private String formatAntiquity(int y, int m, int dd) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (y > 0) parts.add(y + (y == 1 ? " año" : " años"));
        if (m > 0) parts.add(m + (m == 1 ? " mes" : " meses"));
        if (dd > 0) parts.add(dd + (dd == 1 ? " día" : " días"));
        if (parts.isEmpty()) return "0 días";
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " y " + parts.get(parts.size() - 1);
    }

    /** Tras una baja, ofrece descargar la carta de despido y el certificado de
     *  empresa. Al cerrar, recarga el módulo. */
    private void showTerminationDocsDialog(com.benjagest.ui.model.EmployeeEntry employee,
                                            java.time.LocalDate ceseDate, String type) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.term.docs.title"));
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        String safeName = employee.fullName() == null ? employee.id() : employee.fullName().replace(" ", "_");
        // Recibo de finiquito — el documento legal que firma el trabajador.
        Button receipt = new Button(t("labor.term.docs.receipt"));
        receipt.setGraphic(icon("fas-file-signature"));
        receipt.getStyleClass().add("button-primary");
        receipt.setOnAction(e -> downloadTermDoc("settlement-receipt", employee.id(), ceseDate, type,
                "recibo-finiquito-" + safeName + ".pdf"));
        Button letter = new Button(t("labor.term.docs.letter"));
        letter.setGraphic(icon("fas-file-pdf"));
        letter.setOnAction(e -> downloadTermDoc("dismissal-letter", employee.id(), ceseDate, type,
                "carta-despido-" + safeName + ".pdf"));
        Button cert = new Button(t("labor.term.docs.cert"));
        cert.setGraphic(icon("fas-file-pdf"));
        cert.setOnAction(e -> downloadTermDoc("company-certificate", employee.id(), ceseDate, type,
                "certificado-empresa-" + safeName + ".pdf"));
        Label hint = new Label(t("labor.term.docs.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");
        VBox box = new VBox(12, hint, new HBox(8, receipt, letter, cert));
        box.setPadding(new Insets(12));
        installDialog(d, box);
        d.showAndWait();
        refreshLabor.run();
    }

    private void downloadTermDoc(String which, String employeeId, java.time.LocalDate date,
                                  String type, String filename) {
        Task<byte[]> tk = new Task<>() {
            @Override protected byte[] call() throws Exception {
                return laborApiClient.downloadTerminationDoc(which, employeeId, date, type);
            }
        };
        tk.setOnSucceeded(ev -> savePdfBytes(tk.getValue(), filename));
        tk.setOnFailed(ev -> showError(t("labor.term.docs.title"),
                humanizeBackendError(tk.getException() == null ? "" : tk.getException().getMessage())));
        start(tk, "term-doc");
    }

    /** Guarda unos bytes PDF en un fichero elegido por el usuario y lo abre. */
    private void savePdfBytes(byte[] bytes, String filename) {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setInitialFileName(filename);
        fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("PDF", "*.pdf"));
        java.io.File f = fc.showSaveDialog(window());
        if (f == null) return;
        try {
            java.nio.file.Files.write(f.toPath(), bytes);
            if (java.awt.Desktop.isDesktopSupported()) {
                try { java.awt.Desktop.getDesktop().open(f); } catch (Exception ignored) {}
            }
        } catch (java.io.IOException ex) {
            showError(t("labor.payslips.pdf.fail.title"), ex.getMessage());
        }
    }

    // ===================================================================
    //  Modelo 145 (IRPF del empleado)
    // ===================================================================

    /** Editor del modelo 145 (datos IRPF) de un empleado. El motor de
     *  retención usa estos datos para calcular el tipo como A3. */
    private void showIrpf145Dialog(com.benjagest.ui.model.EmployeeEntry employee) {
        Task<com.benjagest.ui.model.Modelo145Entry> load = new Task<>() {
            @Override protected com.benjagest.ui.model.Modelo145Entry call() throws Exception {
                return laborApiClient.getIrpfData(employee.id());
            }
        };
        load.setOnSucceeded(ev -> buildIrpf145Form(employee, load.getValue()));
        load.setOnFailed(ev -> showError(t("labor.irpf.load_failed"),
                load.getException() == null ? "" : load.getException().getMessage()));
        start(load, "irpf-load");
    }

    private void buildIrpf145Form(com.benjagest.ui.model.EmployeeEntry employee,
                                   com.benjagest.ui.model.Modelo145Entry m) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.irpf.title") + " — " + employee.fullName());
        ButtonType save = new ButtonType(t("labor.ssrates.save_btn"), ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        ComboBox<Integer> famSit = new ComboBox<>();
        famSit.getItems().addAll(1, 2, 3);
        famSit.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Integer s) { return s == null ? "" : t("labor.irpf.fam." + s); }
            @Override public Integer fromString(String s) { return null; }
        });
        famSit.getSelectionModel().select(Integer.valueOf(m.familySituation() == 0 ? 3 : m.familySituation()));
        TextField spouseNif = new TextField(m.spouseNif() == null ? "" : m.spouseNif());
        TextField desc = new TextField(String.valueOf(m.descendants()));
        TextField descU3 = new TextField(String.valueOf(m.descendantsUnder3()));
        TextField desc33 = new TextField(String.valueOf(m.descendantsDisability33()));
        TextField desc65 = new TextField(String.valueOf(m.descendantsDisability65()));
        CheckBox exclusive = new CheckBox(t("labor.irpf.exclusive")); exclusive.setSelected(m.exclusiveCustody());
        TextField asc65 = new TextField(String.valueOf(m.ascendantsOver65()));
        TextField asc75 = new TextField(String.valueOf(m.ascendantsOver75()));
        ComboBox<String> ownDis = new ComboBox<>();
        ownDis.getItems().addAll("NONE", "D33", "D65");
        ownDis.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) { return s == null ? "" : t("labor.irpf.dis." + s); }
            @Override public String fromString(String s) { return null; }
        });
        ownDis.getSelectionModel().select(m.ownDisability() == null || m.ownDisability().isBlank() ? "NONE" : m.ownDisability());
        CheckBox mobility = new CheckBox(t("labor.irpf.mobility")); mobility.setSelected(m.ownMobility());
        CheckBox over65 = new CheckBox(t("labor.irpf.over65")); over65.setSelected(m.taxpayerOver65());
        CheckBox over75 = new CheckBox(t("labor.irpf.over75")); over75.setSelected(m.taxpayerOver75());
        CheckBox under1 = new CheckBox(t("labor.irpf.under_year")); under1.setSelected(m.contractUnderYear());
        CheckBox geoMob = new CheckBox(t("labor.irpf.geo_mobility")); geoMob.setSelected(m.geographicMobility());
        CheckBox mortgage = new CheckBox(t("labor.irpf.mortgage")); mortgage.setSelected(m.mortgageBefore2013());

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12));
        // Col 0 (etiquetas): nunca por debajo de su ancho preferido para que no
        // se corten los encabezados al estrechar la ventana. Col 1 (campos) crece.
        javafx.scene.layout.ColumnConstraints labelCol = new javafx.scene.layout.ColumnConstraints();
        labelCol.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.layout.ColumnConstraints fieldCol = new javafx.scene.layout.ColumnConstraints();
        fieldCol.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        fieldCol.setFillWidth(true);
        g.getColumnConstraints().addAll(labelCol, fieldCol);
        int r = 0;
        g.add(new Label(t("labor.irpf.fam_situation")), 0, r); g.add(famSit, 1, r++);
        g.add(new Label(t("labor.irpf.spouse_nif")), 0, r); g.add(spouseNif, 1, r++);
        g.add(new Label(t("labor.irpf.descendants")), 0, r); g.add(desc, 1, r++);
        g.add(new Label(t("labor.irpf.desc_under3")), 0, r); g.add(descU3, 1, r++);
        g.add(new Label(t("labor.irpf.desc_dis33")), 0, r); g.add(desc33, 1, r++);
        g.add(new Label(t("labor.irpf.desc_dis65")), 0, r); g.add(desc65, 1, r++);
        g.add(exclusive, 1, r++);
        g.add(new Label(t("labor.irpf.asc65")), 0, r); g.add(asc65, 1, r++);
        g.add(new Label(t("labor.irpf.asc75")), 0, r); g.add(asc75, 1, r++);
        g.add(new Label(t("labor.irpf.own_dis")), 0, r); g.add(ownDis, 1, r++);
        g.add(mobility, 1, r++);
        g.add(over65, 1, r++);
        g.add(over75, 1, r++);
        g.add(under1, 1, r++);
        g.add(geoMob, 1, r++);
        g.add(mortgage, 1, r++);
        installDialog(d, g);

        d.showAndWait().ifPresent(bt -> {
            if (bt != save) return;
            com.benjagest.ui.model.Modelo145Entry out = new com.benjagest.ui.model.Modelo145Entry(
                    famSit.getValue() == null ? 3 : famSit.getValue(),
                    blankToNullOrSelf(spouseNif.getText()),
                    intOr0(desc.getText()), intOr0(descU3.getText()),
                    intOr0(desc33.getText()), intOr0(desc65.getText()), exclusive.isSelected(),
                    intOr0(asc65.getText()), intOr0(asc75.getText()),
                    ownDis.getValue(), mobility.isSelected(),
                    over65.isSelected(), over75.isSelected(),
                    under1.isSelected(), geoMob.isSelected(), mortgage.isSelected());
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.saveIrpfData(employee.id(), out); return null;
                }
            };
            tk.setOnSucceeded(ev -> showInfo(t("labor.irpf.title"), t("labor.irpf.saved")));
            tk.setOnFailed(ev -> showError(t("labor.irpf.save_failed"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "irpf-save");
        });
    }

    private int intOr0(String s) {
        Integer v = parseIntSafe(s);
        return v == null ? 0 : v;
    }

    // ===================================================================
    //  Pestaña Empleados
    // ===================================================================

    public Node buildEmployeesTab(java.util.List<com.benjagest.ui.model.EmployeeEntry> employees) {
        Button newEmployee = new Button(t("labor.action.new_employee"));
        newEmployee.setGraphic(icon("fas-plus"));
        newEmployee.setOnAction(ev -> showEmployeeEditor(null));

        employeesTable = new TableView<>();
        employeesTable.getStyleClass().add("data-table");
        employeesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        employeesTable.setPlaceholder(new Label(t("labor.employees.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.EmployeeEntry, String> colName =
                new TableColumn<>(t("labor.employees.col.name"));
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().fullName()));
        TableColumn<com.benjagest.ui.model.EmployeeEntry, String> colNif =
                new TableColumn<>(t("labor.employees.col.nif"));
        colNif.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().taxIdentifier()));
        colNif.setPrefWidth(120);
        TableColumn<com.benjagest.ui.model.EmployeeEntry, String> colNuss =
                new TableColumn<>(t("labor.employees.col.nuss"));
        colNuss.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().socialSecurityNumber()));
        colNuss.setPrefWidth(140);
        TableColumn<com.benjagest.ui.model.EmployeeEntry, String> colHire =
                new TableColumn<>(t("labor.employees.col.hire_date"));
        colHire.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().hireDate() == null ? "" : c.getValue().hireDate().toString()));
        colHire.setPrefWidth(110);
        colHire.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.EmployeeEntry, String> colSs =
                new TableColumn<>(t("labor.employees.col.ss"));
        colSs.setCellValueFactory(c -> new SimpleStringProperty(localizedEnum("ss_regime", c.getValue().ssRegime())));
        colSs.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.EmployeeEntry, String> colGeo =
                new TableColumn<>(t("labor.employees.col.geo"));
        colGeo.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().geolocationEnabled() ? "📍" : ""));
        colGeo.setPrefWidth(60);
        TableColumn<com.benjagest.ui.model.EmployeeEntry, String> colFlags =
                new TableColumn<>(t("labor.employees.col.flags"));
        colFlags.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().active() ? "" : t("labor.employees.inactive")));
        colFlags.setPrefWidth(90);
        employeesTable.getColumns().addAll(java.util.List.of(colName, colNif, colNuss, colHire, colSs, colGeo, colFlags));
        employeesTable.setItems(FXCollections.observableArrayList(employees));
        employeesTable.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                var sel = employeesTable.getSelectionModel().getSelectedItem();
                if (sel != null) showEmployeeEditor(sel);
            }
        });

        Button editBtn = new Button(t("labor.employees.action.edit"));
        editBtn.setGraphic(icon("fas-edit"));
        editBtn.setDisable(true);
        editBtn.setOnAction(ev -> {
            var sel = employeesTable.getSelectionModel().getSelectedItem();
            if (sel != null) showEmployeeEditor(sel);
        });
        Button contractsBtn = new Button(t("labor.employees.action.contracts"));
        contractsBtn.setGraphic(icon("fas-file-contract"));
        contractsBtn.setDisable(true);
        contractsBtn.setOnAction(ev -> {
            var sel = employeesTable.getSelectionModel().getSelectedItem();
            if (sel != null) showEmployeeContracts(sel);
        });
        Button irpfBtn = new Button(t("labor.employees.action.irpf"));
        irpfBtn.setGraphic(icon("fas-percentage"));
        irpfBtn.setDisable(true);
        irpfBtn.setOnAction(ev -> {
            var sel = employeesTable.getSelectionModel().getSelectedItem();
            if (sel != null) showIrpf145Dialog(sel);
        });
        Button terminateBtn = new Button(t("labor.employees.action.terminate"));
        terminateBtn.setGraphic(icon("fas-handshake"));
        terminateBtn.setDisable(true);
        terminateBtn.setOnAction(ev -> {
            var sel = employeesTable.getSelectionModel().getSelectedItem();
            if (sel != null) showTerminationDialog(sel);
        });
        Button suspendBtn = new Button(t("labor.employees.action.suspend"));
        suspendBtn.setGraphic(icon("fas-user-clock"));
        suspendBtn.setDisable(true);
        suspendBtn.setOnAction(ev -> {
            var sel = employeesTable.getSelectionModel().getSelectedItem();
            if (sel != null) showSuspensionsDialog(sel);
        });
        Button backPayBtn = new Button(t("labor.employees.action.backpay"));
        backPayBtn.setGraphic(icon("fas-coins"));
        backPayBtn.setDisable(true);
        backPayBtn.setOnAction(ev -> {
            var sel = employeesTable.getSelectionModel().getSelectedItem();
            if (sel != null) showBackPayDialog(sel);
        });
        Button deleteBtn = new Button(t("labor.employees.action.delete"));
        deleteBtn.setGraphic(icon("fas-user-slash"));
        deleteBtn.setDisable(true);
        deleteBtn.setOnAction(ev -> {
            var sel = employeesTable.getSelectionModel().getSelectedItem();
            if (sel != null) deleteEmployee(sel);
        });
        // Cese de empresa — nivel empresa (no requiere empleado seleccionado).
        Button closureBtn = new Button(t("labor.employees.action.closure"));
        closureBtn.setGraphic(icon("fas-times-circle"));
        closureBtn.getStyleClass().add("button-danger-outline");
        closureBtn.setOnAction(ev -> showCompanyClosureDialog());

        employeesTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            editBtn.setDisable(nv == null);
            contractsBtn.setDisable(nv == null);
            irpfBtn.setDisable(nv == null);
            terminateBtn.setDisable(nv == null || !nv.active());
            suspendBtn.setDisable(nv == null || !nv.active());
            backPayBtn.setDisable(nv == null || !nv.active());
            deleteBtn.setDisable(nv == null || !nv.active());
        });

        HBox actions = new HBox(8, newEmployee, editBtn, contractsBtn, irpfBtn, terminateBtn,
                suspendBtn, backPayBtn, deleteBtn, closureBtn);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(0, 0, 8, 0));

        VBox body = new VBox(12, actions, employeesTable);
        VBox.setVgrow(employeesTable, Priority.ALWAYS);
        body.setPadding(new Insets(12));
        this.viewRoot = body;
        return screenScroll(body);
    }

    // ===================================================================
    //  CL-4a — Suspensión / excedencia
    // ===================================================================

    /**
     * CL-1/CL-4 — Suspensiones/excedencias del contrato (art. 45 ET). Lista las
     * del empleado y permite registrar una nueva o cerrar/borrar las existentes.
     * Durante una suspensión sin sueldo la nómina no genera recibo (guarda backend).
     */
    private void showSuspensionsDialog(com.benjagest.ui.model.EmployeeEntry employee) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.susp.title") + " — " + employee.fullName());
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        TableView<LaborApiClient.SuspensionEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.susp.empty")));
        TableColumn<LaborApiClient.SuspensionEntry, String> cType = new TableColumn<>(t("labor.susp.col.type"));
        cType.setCellValueFactory(c -> new SimpleStringProperty(t("labor.susp.type." + c.getValue().type())));
        TableColumn<LaborApiClient.SuspensionEntry, String> cFrom = new TableColumn<>(t("labor.susp.col.from"));
        cFrom.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().startDate() == null ? "" : c.getValue().startDate().toString()));
        TableColumn<LaborApiClient.SuspensionEntry, String> cTo = new TableColumn<>(t("labor.susp.col.to"));
        cTo.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().endDate() == null ? t("labor.susp.open") : c.getValue().endDate().toString()));
        TableColumn<LaborApiClient.SuspensionEntry, String> cRes = new TableColumn<>(t("labor.susp.col.reserva"));
        cRes.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().reservaPuesto() ? t("common.yes") : t("common.no")));
        table.getColumns().addAll(java.util.List.of(cType, cFrom, cTo, cRes));

        Runnable reload = () -> {
            Task<java.util.List<LaborApiClient.SuspensionEntry>> tk = new Task<>() {
                @Override protected java.util.List<LaborApiClient.SuspensionEntry> call() throws Exception {
                    return laborApiClient.listSuspensions(employee.id());
                }
            };
            tk.setOnSucceeded(ev -> table.getItems().setAll(tk.getValue()));
            tk.setOnFailed(ev -> showError(t("labor.susp.title"),
                    humanizeBackendError(tk.getException() == null ? "" : tk.getException().getMessage())));
            start(tk, "susp-list");
        };

        // Formulario de alta.
        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("EXCEDENCIA_VOLUNTARIA", "EXCEDENCIA_FORZOSA", "EXCEDENCIA_CUIDADO",
                "SUSPENSION_EMPLEO_SUELDO", "OTRA");
        typeCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) { return s == null ? "" : t("labor.susp.type." + s); }
            @Override public String fromString(String s) { return null; }
        });
        typeCombo.getSelectionModel().select("EXCEDENCIA_VOLUNTARIA");
        DatePicker fromDate = new DatePicker(java.time.LocalDate.now());
        DatePicker toDate = new DatePicker();
        toDate.setPromptText(t("labor.susp.open"));
        javafx.scene.control.CheckBox reserva = new javafx.scene.control.CheckBox(t("labor.susp.col.reserva"));
        TextField reason = new TextField();
        reason.setPromptText(t("labor.susp.reason.prompt"));
        Button addBtn = new Button(t("labor.susp.add"));
        addBtn.getStyleClass().add("button-primary");
        addBtn.setOnAction(e -> {
            if (fromDate.getValue() == null || typeCombo.getValue() == null) {
                showError(t("labor.susp.title"), t("labor.susp.need_dates")); return;
            }
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.registerSuspension(employee.id(), typeCombo.getValue(),
                            fromDate.getValue(), toDate.getValue(), reserva.isSelected(),
                            blankToNullOrSelf(reason.getText()));
                    return null;
                }
            };
            tk.setOnSucceeded(ev -> { reason.clear(); reload.run(); });
            tk.setOnFailed(ev -> showError(t("labor.susp.title"),
                    humanizeBackendError(tk.getException() == null ? "" : tk.getException().getMessage())));
            start(tk, "susp-add");
        });

        Button closeBtn = new Button(t("labor.susp.close_btn"));
        closeBtn.setDisable(true);
        closeBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            DatePicker dp = new DatePicker(java.time.LocalDate.now());
            Dialog<ButtonType> cd = new Dialog<>();
            cd.setTitle(t("labor.susp.close_btn"));
            cd.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            installDialog(cd, new VBox(8, new Label(t("labor.susp.reentry_date")), dp));
            cd.showAndWait().ifPresent(bt -> {
                if (bt != ButtonType.OK || dp.getValue() == null) return;
                Task<Void> tk = new Task<>() {
                    @Override protected Void call() throws Exception {
                        laborApiClient.closeSuspension(sel.id(), dp.getValue());
                        return null;
                    }
                };
                tk.setOnSucceeded(ev -> reload.run());
                tk.setOnFailed(ev -> showError(t("labor.susp.title"),
                        humanizeBackendError(tk.getException() == null ? "" : tk.getException().getMessage())));
                start(tk, "susp-close");
            });
        });
        Button delBtn = new Button(t("labor.susp.delete_btn"));
        delBtn.setDisable(true);
        delBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception { laborApiClient.deleteSuspension(sel.id()); return null; }
            };
            tk.setOnSucceeded(ev -> reload.run());
            tk.setOnFailed(ev -> showError(t("labor.susp.title"),
                    humanizeBackendError(tk.getException() == null ? "" : tk.getException().getMessage())));
            start(tk, "susp-del");
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            closeBtn.setDisable(nv == null || nv.endDate() != null);
            delBtn.setDisable(nv == null);
        });

        Label hint = new Label(t("labor.susp.hint"));
        hint.getStyleClass().add("settings-hint"); hint.setWrapText(true);
        GridPane g = new GridPane(); g.setHgap(10); g.setVgap(8);
        int r = 0;
        g.add(new Label(t("labor.susp.col.type")), 0, r); g.add(typeCombo, 1, r++);
        g.add(new Label(t("labor.susp.col.from")), 0, r); g.add(fromDate, 1, r++);
        g.add(new Label(t("labor.susp.col.to")), 0, r); g.add(toDate, 1, r++);
        g.add(reserva, 1, r++);
        g.add(new Label(t("labor.susp.reason")), 0, r); g.add(reason, 1, r++);
        HBox tableActions = new HBox(8, closeBtn, delBtn);
        VBox box = new VBox(10, hint, new HBox(8, addBtn), g, new Separator(),
                tableActions, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPrefSize(620, 540);
        installDialog(d, box);
        javafx.application.Platform.runLater(reload);
        d.showAndWait();
    }

    // ===================================================================
    //  CL-4b — Atrasos
    // ===================================================================

    /** CL-2/CL-4 — Calcula los atrasos del empleado (subida con efecto pasado). */
    private void showBackPayDialog(com.benjagest.ui.model.EmployeeEntry employee) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.backpay.title") + " — " + employee.fullName());
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        DatePicker through = new DatePicker(java.time.LocalDate.now());
        Label out = new Label(t("labor.backpay.empty"));
        out.getStyleClass().add("settings-hint"); out.setWrapText(true);
        Button calc = new Button(t("labor.backpay.calc"));
        calc.getStyleClass().add("button-primary");
        Runnable doCalc = () -> {
            java.time.LocalDate th = through.getValue() == null ? java.time.LocalDate.now() : through.getValue();
            Task<LaborApiClient.BackPayEntry> tk = new Task<>() {
                @Override protected LaborApiClient.BackPayEntry call() throws Exception {
                    return laborApiClient.previewBackPay(employee.id(), th.getYear(), th.getMonthValue());
                }
            };
            tk.setOnSucceeded(ev -> {
                var p = tk.getValue();
                if (p == null || !p.hasBackPay()) {
                    out.setText(p == null || p.message() == null ? t("labor.backpay.none") : p.message());
                    return;
                }
                out.setText(t("labor.backpay.result")
                        .replace("{old}", money(p.oldAnnual()))
                        .replace("{new}", money(p.newAnnual()))
                        .replace("{from}", p.effectiveFrom() == null ? "" : p.effectiveFrom().toString())
                        .replace("{months}", String.valueOf(p.totalMonths()))
                        .replace("{mdiff}", money(p.monthlyDiff()))
                        .replace("{gprior}", money(p.grossPriorYears()))
                        .replace("{pmonths}", String.valueOf(p.priorYearMonths()))
                        .replace("{gcurr}", money(p.grossCurrentYear()))
                        .replace("{cmonths}", String.valueOf(p.currentYearMonths()))
                        .replace("{gtotal}", money(p.grossTotal()))
                        .replace("{irpfprior}", money(p.irpfPriorYears()))
                        .replace("{irpfcurr}", money(p.irpfCurrentYear()))
                        .replace("{irpf}", money(p.irpfTotal()))
                        .replace("{ss}", money(p.employeeSs()))
                        .replace("{net}", money(p.net())));
            });
            tk.setOnFailed(ev -> out.setText(humanizeBackendError(
                    tk.getException() == null ? "" : tk.getException().getMessage())));
            start(tk, "backpay");
        };
        calc.setOnAction(e -> doCalc.run());
        through.valueProperty().addListener((o, ov, nv) -> doCalc.run());

        Label hint = new Label(t("labor.backpay.hint"));
        hint.getStyleClass().add("settings-hint"); hint.setWrapText(true);
        GridPane g = new GridPane(); g.setHgap(10); g.setVgap(8);
        g.add(new Label(t("labor.backpay.through")), 0, 0); g.add(through, 1, 0);
        VBox box = new VBox(10, hint, g, new HBox(8, calc), new Separator(), out);
        box.setPrefSize(560, 420);
        installDialog(d, box);
        javafx.application.Platform.runLater(doCalc);
        d.showAndWait();
    }

    // ===================================================================
    //  CL-4c — Cese de empresa (extinción colectiva)
    // ===================================================================

    /**
     * CL-3/CL-4 — Cese de empresa: extingue TODOS los contratos activos a una
     * fecha. Acción IRREVERSIBLE → preview obligatorio + DOBLE confirmación.
     */
    private void showCompanyClosureDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.closure.title"));
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        DatePicker ceseDate = new DatePicker(java.time.LocalDate.now());
        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("DISMISSAL_OBJECTIVE", "DISMISSAL_UNFAIR");
        typeCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) { return s == null ? "" : t("labor.term.type." + s); }
            @Override public String fromString(String s) { return null; }
        });
        typeCombo.getSelectionModel().select("DISMISSAL_OBJECTIVE");

        TableView<LaborApiClient.ClosureLineEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.closure.empty")));
        TableColumn<LaborApiClient.ClosureLineEntry, String> cName = new TableColumn<>(t("labor.closure.col.employee"));
        cName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().employeeName()));
        TableColumn<LaborApiClient.ClosureLineEntry, String> cSev = new TableColumn<>(t("labor.closure.col.severance"));
        cSev.setCellValueFactory(c -> new SimpleStringProperty(money(c.getValue().severanceGross())));
        table.getColumns().addAll(java.util.List.of(cName, cSev));

        Label summary = new Label(t("labor.closure.preview.empty"));
        summary.getStyleClass().add("settings-hint"); summary.setWrapText(true);
        Button previewBtn = new Button(t("labor.payslips.calc.preview_btn"));
        previewBtn.getStyleClass().add("button-secondary");
        final LaborApiClient.ClosureEntry[] lastPreview = {null};
        Runnable doPreview = () -> {
            java.time.LocalDate ce = ceseDate.getValue();
            if (ce == null) return;
            Task<LaborApiClient.ClosureEntry> tk = new Task<>() {
                @Override protected LaborApiClient.ClosureEntry call() throws Exception {
                    return laborApiClient.previewCompanyClosure(ce, typeCombo.getValue());
                }
            };
            tk.setOnSucceeded(ev -> {
                var p = tk.getValue();
                lastPreview[0] = p;
                table.getItems().setAll(p.lines());
                summary.setText(t("labor.closure.preview.text")
                        .replace("{n}", String.valueOf(p.total()))
                        .replace("{total}", money(p.totalSeverance())));
            });
            tk.setOnFailed(ev -> summary.setText(humanizeBackendError(
                    tk.getException() == null ? "" : tk.getException().getMessage())));
            start(tk, "closure-preview");
        };
        previewBtn.setOnAction(e -> doPreview.run());
        ceseDate.valueProperty().addListener((o, ov, nv) -> doPreview.run());
        typeCombo.valueProperty().addListener((o, ov, nv) -> doPreview.run());

        Button executeBtn = new Button(t("labor.closure.execute"));
        executeBtn.getStyleClass().add("button-danger-outline");
        executeBtn.setOnAction(e -> {
            java.time.LocalDate ce = ceseDate.getValue();
            if (ce == null || lastPreview[0] == null || lastPreview[0].total() == 0) {
                showError(t("labor.closure.title"), t("labor.closure.need_preview")); return;
            }
            int n = lastPreview[0].total();
            // DOBLE confirmación — acción irreversible.
            Alert c1 = new Alert(Alert.AlertType.WARNING,
                    t("labor.closure.confirm1").replace("{n}", String.valueOf(n))
                            .replace("{date}", ce.toString()).replace("{total}", money(lastPreview[0].totalSeverance())),
                    ButtonType.OK, ButtonType.CANCEL);
            c1.setHeaderText(t("labor.closure.execute"));
            c1.showAndWait().ifPresent(b1 -> {
                if (b1 != ButtonType.OK) return;
                Alert c2 = new Alert(Alert.AlertType.WARNING,
                        t("labor.closure.confirm2").replace("{n}", String.valueOf(n)),
                        ButtonType.OK, ButtonType.CANCEL);
                c2.setHeaderText(t("labor.closure.confirm2_header"));
                c2.showAndWait().ifPresent(b2 -> {
                    if (b2 != ButtonType.OK) return;
                    Task<LaborApiClient.ClosureEntry> tk = new Task<>() {
                        @Override protected LaborApiClient.ClosureEntry call() throws Exception {
                            return laborApiClient.executeCompanyClosure(ce, typeCombo.getValue(), null);
                        }
                    };
                    tk.setOnSucceeded(ev -> {
                        refreshLaborAndJournal();
                        showInfo(t("labor.closure.title"), t("labor.closure.done")
                                .replace("{n}", String.valueOf(tk.getValue().ok()))
                                .replace("{total}", money(tk.getValue().totalSeverance())));
                        d.setResult(ButtonType.CLOSE); d.close();
                    });
                    tk.setOnFailed(ev -> showError(t("labor.closure.title"),
                            humanizeBackendError(tk.getException() == null ? "" : tk.getException().getMessage())));
                    start(tk, "closure-execute");
                });
            });
        });

        Label hint = new Label(t("labor.closure.hint"));
        hint.getStyleClass().add("settings-hint"); hint.setWrapText(true);
        GridPane g = new GridPane(); g.setHgap(10); g.setVgap(8);
        g.add(new Label(t("labor.settlement.cese_date")), 0, 0); g.add(ceseDate, 1, 0);
        g.add(new Label(t("labor.term.reason")), 0, 1); g.add(typeCombo, 1, 1);
        VBox box = new VBox(10, hint, g, new HBox(8, previewBtn, executeBtn), summary, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPrefSize(640, 560);
        installDialog(d, box);
        d.showAndWait();
    }

    // ===================================================================
    //  Invitación a la app (PWA) + editor de empleado
    // ===================================================================

    private void showEmployeeAppInvite(com.benjagest.ui.model.EmployeeEntry emp) {
        Task<com.benjagest.ui.model.AppInvitationResult> task = new Task<>() {
            @Override protected com.benjagest.ui.model.AppInvitationResult call() throws Exception {
                return laborApiClient.generateAppInvitation(emp.id());
            }
        };
        task.setOnSucceeded(ev -> {
            var inv = task.getValue();
            Dialog<Void> d = new Dialog<>();
            d.setTitle(t("labor.employee.app_invite.title"));
            d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            d.getDialogPane().setPrefWidth(520);

            Label hint = new Label(t("labor.employee.app_invite.dialog.hint"));
            hint.setWrapText(true); hint.getStyleClass().add("settings-hint");

            TextField linkField = new TextField(inv.url());
            linkField.setEditable(false);
            Button copyLink = new Button(t("labor.employee.app_invite.copy"));
            copyLink.setOnAction(e -> copyToClipboard(inv.url()));
            HBox linkRow = new HBox(8, linkField, copyLink);
            HBox.setHgrow(linkField, Priority.ALWAYS);

            TextField codeField = new TextField(inv.token());
            codeField.setEditable(false);
            Button copyCode = new Button(t("labor.employee.app_invite.copy"));
            copyCode.setOnAction(e -> copyToClipboard(inv.token()));
            HBox codeRow = new HBox(8, codeField, copyCode);
            HBox.setHgrow(codeField, Priority.ALWAYS);

            Label expires = new Label(t("labor.employee.app_invite.expires")
                    .replace("{h}", String.valueOf(inv.expiresInHours())));
            expires.getStyleClass().add("settings-hint");

            VBox box = new VBox(8, hint,
                    label(t("labor.employee.app_invite.link"), "settings-section-title"), linkRow,
                    label(t("labor.employee.app_invite.code"), "settings-section-title"), codeRow,
                    expires);
            box.setPadding(new Insets(12));
            d.getDialogPane().setContent(box);
            d.showAndWait();
        });
        task.setOnFailed(ev -> showError(t("labor.employee.app_invite.fail"),
                task.getException() == null ? "" : task.getException().getMessage()));
        start(task, "emp-app-invite");
    }

    private void copyToClipboard(String text) {
        javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
        cc.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
        toast(t("labor.employee.app_invite.copied"));
    }

    private void showEmployeeEditor(com.benjagest.ui.model.EmployeeEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? t("labor.employee.editor.title_new") : t("labor.employee.editor.title_edit"));
        ButtonType saveBt = new ButtonType(t("labor.employee.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField nameField = new TextField(existing == null ? "" : existing.fullName());
        TextField nifField = new TextField(existing == null ? "" : existing.taxIdentifier());
        TextField nussField = new TextField(existing == null ? "" : existing.socialSecurityNumber());
        TextField emailField = new TextField(existing == null ? "" : existing.email());
        TextField phoneField = new TextField(existing == null ? "" : existing.phone());
        TextField birthField = new TextField(existing == null || existing.birthDate() == null
                ? "" : existing.birthDate().toString());
        birthField.setPromptText("AAAA-MM-DD");
        com.benjagest.ui.support.EditableCells.installIsoDateMask(birthField);
        // Combos con StringConverter para mostrar texto traducido al
        // idioma actual; los valores internos (MALE/FEMALE/SINGLE/…) se
        // mantienen tal cual viajan al backend, así no hay que tocar BD.
        ComboBox<String> genderCombo = new ComboBox<>();
        genderCombo.getItems().addAll("", "MALE", "FEMALE", "OTHER");
        genderCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String code) {
                if (code == null || code.isEmpty()) return "";
                return humanizeFromKey("labor.employee.gender." + code, code);
            }
            @Override public String fromString(String s) { return null; }
        });
        genderCombo.getSelectionModel().select(existing == null || existing.gender() == null ? "" : existing.gender());

        ComboBox<String> maritalCombo = new ComboBox<>();
        maritalCombo.getItems().addAll("", "SINGLE", "MARRIED", "DIVORCED", "WIDOWED", "DOMESTIC_PARTNER");
        maritalCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String code) {
                if (code == null || code.isEmpty()) return "";
                return humanizeFromKey("labor.employee.marital." + code, code);
            }
            @Override public String fromString(String s) { return null; }
        });
        maritalCombo.getSelectionModel().select(existing == null || existing.maritalStatus() == null ? "" : existing.maritalStatus());
        TextField childrenField = new TextField(existing == null || existing.dependentChildren() == null
                ? "" : existing.dependentChildren().toString());
        TextField disabledField = new TextField(existing == null || existing.dependentDisabled() == null
                ? "" : existing.dependentDisabled().toString());

        TextField addressField = new TextField(existing == null ? "" : existing.addressLine());
        TextField cityField = new TextField(existing == null ? "" : existing.city());
        TextField provinceField = new TextField(existing == null ? "" : existing.province());
        TextField postalField = new TextField(existing == null ? "" : existing.postalCode());
        TextField countryField = new TextField(existing == null || existing.country() == null || existing.country().isBlank()
                ? "Espana" : existing.country());
        TextField ibanField = new TextField(existing == null ? "" : existing.iban());
        ComboBox<String> workTypeCombo = new ComboBox<>();
        workTypeCombo.getItems().addAll("FULL_TIME", "PART_TIME");
        workTypeCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String v) {
                if (v == null) return "";
                return switch (v) {
                    case "FULL_TIME" -> t("labor.worktype.full");
                    case "PART_TIME" -> t("labor.worktype.part");
                    default -> v;
                };
            }
            @Override public String fromString(String s) { return null; }
        });
        String wt0 = existing == null ? "FULL_TIME" : existing.workType();
        if (wt0 != null && !wt0.isBlank() && !workTypeCombo.getItems().contains(wt0)) {
            workTypeCombo.getItems().add(wt0);
        }
        workTypeCombo.getSelectionModel().select(wt0 == null || wt0.isBlank() ? "FULL_TIME" : wt0);
        ComboBox<String> ssCombo = new ComboBox<>();
        ssCombo.getItems().addAll("", "GENERAL", "RETA", "AUTONOMO_SOCIETARIO", "ARTISTAS", "MAR", "AGRARIO", "OTHER");
        ssCombo.getSelectionModel().select(existing == null || existing.ssRegime() == null ? "" : existing.ssRegime());

        // CAL-FIX 4 (2026-06-09): combo para asignar calendario laboral.
        // Carga sincronizada por simplicidad — la lista es pequeña (1-3
        // entries típicas por empresa). El item "—" representa "sin
        // calendario asignado" (null en BD). Al fichar/calcular nómina,
        // si workCalendarId es null, el sistema usa el calendario activo
        // del año de la empresa (decisión Benjamin 2026-06-09).
        ComboBox<com.benjagest.ui.model.WorkCalendarEntry> calCombo = new ComboBox<>();
        calCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.WorkCalendarEntry e) {
                if (e == null || e.id() == null) return t("labor.employee.editor.work_calendar.none");
                StringBuilder sb = new StringBuilder();
                sb.append(e.year()).append(" — ").append(e.name() == null ? "" : e.name());
                if (e.regionCcaa() != null && !e.regionCcaa().isBlank()) {
                    sb.append(" (").append(e.regionCcaa()).append(")");
                }
                return sb.toString();
            }
            @Override public com.benjagest.ui.model.WorkCalendarEntry fromString(String s) { return null; }
        });
        // Item sentinel "ninguno" (id=null) para poder limpiar la asignación.
        com.benjagest.ui.model.WorkCalendarEntry sinCal =
                new com.benjagest.ui.model.WorkCalendarEntry(
                        null, null, 0, "", "", "", false,
                        null, null, java.util.List.of());
        calCombo.getItems().add(sinCal);
        try {
            for (var c : altaApiClient.listWorkCalendars()) {
                calCombo.getItems().add(c);
            }
        } catch (Exception ignored) {
            // Si falla, dejamos solo "sin calendario" — no bloqueamos
            // el alta de empleado por un problema en /work-calendars.
        }
        // Selección actual: por id si existe, sino "sin calendario".
        if (existing != null && existing.workCalendarId() != null) {
            calCombo.getItems().stream()
                    .filter(x -> existing.workCalendarId().equals(x.id()))
                    .findFirst()
                    .ifPresentOrElse(calCombo.getSelectionModel()::select,
                            () -> calCombo.getSelectionModel().select(sinCal));
        } else {
            calCombo.getSelectionModel().select(sinCal);
        }
        TextField hireField = new TextField(existing == null || existing.hireDate() == null
                ? "" : existing.hireDate().toString());
        hireField.setPromptText("AAAA-MM-DD");
        com.benjagest.ui.support.EditableCells.installIsoDateMask(hireField);
        TextField termField = new TextField(existing == null || existing.terminationDate() == null
                ? "" : existing.terminationDate().toString());
        termField.setPromptText("AAAA-MM-DD");
        com.benjagest.ui.support.EditableCells.installIsoDateMask(termField);
        TextField termReasonField = new TextField(existing == null ? "" : existing.terminationReason());
        CheckBox geoCb = new CheckBox(t("labor.employee.editor.geolocation"));
        geoCb.setSelected(existing != null && existing.geolocationEnabled());
        CheckBox activeCb = new CheckBox(t("labor.employee.editor.active"));
        activeCb.setSelected(existing == null || existing.active());

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(6); g.setPadding(new Insets(10));
        int row = 0;
        g.add(label(t("labor.employee.section.identity"), "settings-section-title"), 0, row++, 4, 1);
        g.add(new Label(t("labor.employee.editor.name")), 0, row); g.add(nameField, 1, row);
        g.add(new Label(t("labor.employee.editor.nif")), 2, row); g.add(nifField, 3, row); row++;
        g.add(new Label(t("labor.employee.editor.nuss")), 0, row); g.add(nussField, 1, row);
        g.add(new Label(t("labor.employee.editor.birth")), 2, row); g.add(birthField, 3, row); row++;
        g.add(new Label(t("labor.employee.editor.gender")), 0, row); g.add(genderCombo, 1, row);
        g.add(new Label(t("labor.employee.editor.marital")), 2, row); g.add(maritalCombo, 3, row); row++;
        g.add(new Label(t("labor.employee.editor.children")), 0, row); g.add(childrenField, 1, row);
        g.add(new Label(t("labor.employee.editor.disabled")), 2, row); g.add(disabledField, 3, row); row++;

        g.add(new Separator(), 0, row++, 4, 1);
        g.add(label(t("labor.employee.section.contact"), "settings-section-title"), 0, row++, 4, 1);
        g.add(new Label(t("labor.employee.editor.email")), 0, row); g.add(emailField, 1, row);
        g.add(new Label(t("labor.employee.editor.phone")), 2, row); g.add(phoneField, 3, row); row++;
        g.add(new Label(t("labor.employee.editor.address")), 0, row); g.add(addressField, 1, row, 3, 1); row++;
        g.add(new Label(t("labor.employee.editor.city")), 0, row); g.add(cityField, 1, row);
        g.add(new Label(t("labor.employee.editor.province")), 2, row); g.add(provinceField, 3, row); row++;
        g.add(new Label(t("labor.employee.editor.postal")), 0, row); g.add(postalField, 1, row);
        g.add(new Label(t("labor.employee.editor.country")), 2, row); g.add(countryField, 3, row); row++;

        g.add(new Separator(), 0, row++, 4, 1);
        g.add(label(t("labor.employee.section.work"), "settings-section-title"), 0, row++, 4, 1);
        g.add(new Label(t("labor.employee.editor.iban")), 0, row); g.add(ibanField, 1, row, 3, 1); row++;
        g.add(new Label(t("labor.employee.editor.work_type")), 0, row); g.add(workTypeCombo, 1, row);
        g.add(new Label(t("labor.employee.editor.ss_regime")), 2, row); g.add(ssCombo, 3, row); row++;
        g.add(new Label(t("labor.employee.editor.work_calendar")), 0, row); g.add(calCombo, 1, row, 3, 1); row++;
        g.add(new Label(t("labor.employee.editor.hire")), 0, row); g.add(hireField, 1, row);
        g.add(new Label(t("labor.employee.editor.termination")), 2, row); g.add(termField, 3, row); row++;
        g.add(new Label(t("labor.employee.editor.term_reason")), 0, row); g.add(termReasonField, 1, row, 3, 1); row++;
        g.add(geoCb, 1, row, 3, 1); row++;
        Label geoHint = new Label(t("labor.employee.editor.geolocation.hint"));
        geoHint.setWrapText(true);
        geoHint.getStyleClass().add("settings-hint");
        g.add(geoHint, 1, row, 3, 1); row++;
        g.add(activeCb, 1, row); row++;

        // L4-4: sección "Acceso a la app" — sólo visible para el OWNER
        // (lo enforce el backend; aquí mostramos siempre pero el guardar
        // dará 403 si quien edita no tiene rol suficiente).
        g.add(new Separator(), 0, row++, 4, 1);
        g.add(label(t("labor.employee.section.app_access"), "settings-section-title"), 0, row++, 4, 1);

        CheckBox appAccessCb = new CheckBox(t("labor.employee.editor.app_access"));
        appAccessCb.setSelected(existing != null && existing.appAccess());
        g.add(appAccessCb, 1, row, 3, 1); row++;

        Label appAccessHint = new Label(t("labor.employee.editor.app_access.hint"));
        appAccessHint.setWrapText(true);
        appAccessHint.getStyleClass().add("settings-hint");
        g.add(appAccessHint, 1, row, 3, 1); row++;

        ComboBox<String> roleCombo = new ComboBox<>();
        roleCombo.getItems().addAll("EMPLOYEE", "ACCOUNTANT", "ADVISOR", "ADMIN");
        roleCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String code) {
                return code == null ? "" : humanizeMemberRole(code);
            }
            @Override public String fromString(String s) { return null; }
        });
        roleCombo.setValue("EMPLOYEE");
        g.add(new Label(t("labor.employee.editor.app_access.role")), 0, row);
        g.add(roleCombo, 1, row); row++;

        PasswordField pinField = new PasswordField();
        pinField.setPromptText(existing != null && existing.hasPin()
                ? t("labor.employee.editor.app_access.pin_keep")
                : t("labor.employee.editor.app_access.pin_prompt"));
        g.add(new Label(t("labor.employee.editor.app_access.pin")), 0, row);
        g.add(pinField, 1, row);

        Label pinHint = new Label(existing != null && existing.hasPin()
                ? t("labor.employee.editor.app_access.pin_change_hint")
                : t("labor.employee.editor.app_access.pin_new_hint"));
        pinHint.setWrapText(true);
        pinHint.getStyleClass().add("settings-hint");
        g.add(pinHint, 2, row, 2, 1); row++;

        // MEMP-1c — Invitar al móvil (PWA). Solo si el empleado ya tiene
        // acceso a la app + PIN (lo exige el backend).
        if (existing != null && existing.appAccess() && existing.hasPin()) {
            Button inviteMobileBtn = new Button(t("labor.employee.app_invite.btn"));
            inviteMobileBtn.setGraphic(icon("fas-mobile-alt"));
            final com.benjagest.ui.model.EmployeeEntry inviteEmp = existing;
            inviteMobileBtn.setOnAction(ev -> showEmployeeAppInvite(inviteEmp));
            Label inviteHint = new Label(t("labor.employee.app_invite.hint"));
            inviteHint.setWrapText(true);
            inviteHint.getStyleClass().add("settings-hint");
            g.add(inviteMobileBtn, 1, row); g.add(inviteHint, 2, row, 2, 1); row++;
        }

        // Habilita/deshabilita los inputs de PIN y rol según el toggle.
        Runnable refreshAppAccessInputs = () -> {
            boolean on = appAccessCb.isSelected();
            roleCombo.setDisable(!on);
            pinField.setDisable(!on);
            // Si el empleado ya tenía rol asignado y desmarcamos, lo
            // dejamos visible pero deshabilitado (informativo).
        };
        appAccessCb.selectedProperty().addListener((o, ov, nv) -> refreshAppAccessInputs.run());
        refreshAppAccessInputs.run();

        ScrollPane sp = new ScrollPane(g);
        sp.setFitToWidth(true);
        sp.setPrefViewportHeight(560);
        dialog.getDialogPane().setContent(sp);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            // L4-4: el record EmployeeEntry lleva los 3 campos "read-only"
            // del backend (appAccess, userId, hasPin) — siempre los enviamos
            // tal cual vienen del existing para no sobrescribirlos por
            // error. El TOGGLE intencional de app_access va al método
            // createEmployee/updateEmployee con la sobrecarga (Boolean,
            // String pin, String role).
            boolean appAccessNow = appAccessCb.isSelected();
            String pinPlain = pinField.getText() == null
                    ? "" : pinField.getText().trim();
            String roleSelected = roleCombo.getValue();

            com.benjagest.ui.model.EmployeeEntry payload = new com.benjagest.ui.model.EmployeeEntry(
                    existing == null ? null : existing.id(),
                    nameField.getText().trim(),
                    blankToNullOrSelf(nifField.getText()),
                    blankToNullOrSelf(nussField.getText()),
                    blankToNullOrSelf(emailField.getText()),
                    blankToNullOrSelf(phoneField.getText()),
                    parseDateSafe(birthField.getText()),
                    blankToNullOrSelf(genderCombo.getValue()),
                    blankToNullOrSelf(maritalCombo.getValue()),
                    parseIntSafe(childrenField.getText()),
                    parseIntSafe(disabledField.getText()),
                    blankToNullOrSelf(addressField.getText()),
                    blankToNullOrSelf(cityField.getText()),
                    blankToNullOrSelf(provinceField.getText()),
                    blankToNullOrSelf(postalField.getText()),
                    blankToNullOrSelf(countryField.getText()),
                    blankToNullOrSelf(ibanField.getText()),
                    workTypeCombo.getValue(),
                    blankToNullOrSelf(ssCombo.getValue()),
                    // CAL-FIX 4: id del calendario laboral (null si "—").
                    calCombo.getValue() == null ? null : calCombo.getValue().id(),
                    parseDateSafe(hireField.getText()),
                    parseDateSafe(termField.getText()),
                    blankToNullOrSelf(termReasonField.getText()),
                    geoCb.isSelected(),
                    activeCb.isSelected(),
                    // Los campos read-only se devuelven igual: el backend
                    // ignora estos en UpsertRequest, los toma de la BD.
                    existing != null && existing.appAccess(),
                    existing == null ? null : existing.userId(),
                    existing != null && existing.hasPin());
            Task<com.benjagest.ui.model.EmployeeEntry> task = new Task<>() {
                @Override
                protected com.benjagest.ui.model.EmployeeEntry call() throws Exception {
                    // El backend usa la sobrecarga con 4 args para tomar
                    // appAccess + pin + role como decisiones explícitas.
                    return existing == null
                            ? laborApiClient.createEmployee(payload, appAccessNow,
                                    pinPlain.isEmpty() ? null : pinPlain, roleSelected)
                            : laborApiClient.updateEmployee(existing.id(), payload,
                                    appAccessNow, pinPlain.isEmpty() ? null : pinPlain,
                                    roleSelected);
                }
            };
            task.setOnSucceeded(ev -> refreshLabor.run());
            task.setOnFailed(ev -> {
                Throwable err = task.getException();
                String msg = err != null && err.getMessage() != null
                        ? err.getMessage() : t("labor.employee.editor.fail.body");
                showError(t("labor.employee.editor.fail.title"), msg);
            });
            start(task, "labor-employee-save");
        });
    }

    private void deleteEmployee(com.benjagest.ui.model.EmployeeEntry e) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("labor.employee.delete.body") + " " + e.fullName(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("labor.employee.delete.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.deleteEmployee(e.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> refreshLabor.run());
            task.setOnFailed(ev -> showError(t("labor.employee.editor.fail.title"),
                    t("labor.employee.editor.fail.body")));
            start(task, "labor-employee-delete");
        });
    }
}
