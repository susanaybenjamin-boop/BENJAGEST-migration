package com.benjagest.ui.screens;

import com.benjagest.ui.service.AltaApiClient;
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
 * NOM-10 — Contratos (categoría "Personal" del módulo Laboral, bloque UIR). El
 * mayor composite del bloque: pestaña "Contratos" (listado global read-only),
 * diálogo de contratos por empleado, WIZARD de alta en 4 pasos (SEPE/convenio/
 * económicos/cláusulas + plantillas), editor plano (con complementos, ascenso
 * VIG-3), y descarga de documentos (PDF/XML Contrat@). Movimiento puro: mismo
 * comportamiento, mismas claves i18n. Se invoca desde la pestaña Empleados
 * (NOM-11) vía {@link #showEmployeeContracts}. Depende de {@link LaborApiClient}
 * + {@link AltaApiClient}, y de un {@link Host} para el visor PDF interno (que
 * es un Host compartido de facturación/consolidación en el shell) y el refresco
 * del módulo Laboral.
 */
public class ContractsScreen extends ScreenBase {

    /** Capacidades del shell que la pantalla necesita (visor PDF compartido + refresco). */
    public interface Host {
        void showInternalPdfViewer(byte[] bytes, java.nio.file.Path tempPath);
        void showXmlSavedDialog(java.nio.file.Path path);
        void refreshLabor();
    }

    private final LaborApiClient laborApiClient;
    private final AltaApiClient altaApiClient;
    private final Host host;
    private TableView<com.benjagest.ui.model.ContractEntry> contractsTable;

    public ContractsScreen(LaborApiClient laborApiClient, AltaApiClient altaApiClient,
                           Function<String, String> tt, Router router, Host host) {
        super(tt, router);
        this.laborApiClient = laborApiClient;
        this.altaApiClient = altaApiClient;
        this.host = host;
    }

    // ----- Helpers compartidos (copiados del shell) -----

    private String humanizeFromKey(String key, String fallback) {
        if (key == null || key.isBlank()) return fallback == null ? "" : fallback;
        String translated = t(key);
        return key.equals(translated) ? (fallback == null ? "" : fallback) : translated;
    }

    private void highlightMissing(javafx.scene.control.Control field) {
        if (field == null) return;
        if (!field.getStyleClass().contains("field-error")) {
            field.getStyleClass().add("field-error");
        }
        field.requestFocus();
    }

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
                combo.getEditor().setText(nv);
                combo.getEditor().positionCaret(nv == null ? 0 : nv.length());
            } finally {
                guard[0] = false;
            }
        });
    }

    // ===================================================================
    //  Pestaña "Contratos" (listado global, read-only)
    // ===================================================================

    public Node buildContractsGlobalTab(java.util.List<com.benjagest.ui.model.EmployeeEntry> employees,
                                        java.util.List<com.benjagest.ui.model.ContractEntry> contracts) {
        java.util.Map<String, String> empById = new java.util.HashMap<>();
        for (var e : employees) empById.put(e.id(), e.fullName());

        TableView<com.benjagest.ui.model.ContractEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.contracts.placeholder.empty.global")));

        TableColumn<com.benjagest.ui.model.ContractEntry, String> colEmp =
                new TableColumn<>(t("labor.contracts.col.employee"));
        colEmp.setCellValueFactory(c -> new SimpleStringProperty(
                empById.getOrDefault(c.getValue().employeeId(), shortId(c.getValue().employeeId()))));
        colEmp.setPrefWidth(180);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colType =
                new TableColumn<>(t("labor.contracts.col.type"));
        colType.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().contractType()));
        colType.setPrefWidth(140);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colSepe =
                new TableColumn<>(t("labor.contracts.col.sepe"));
        colSepe.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sepeContractCode()));
        colSepe.setPrefWidth(70);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colStart =
                new TableColumn<>(t("labor.contracts.col.start"));
        colStart.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().startDate() == null ? "" : c.getValue().startDate().toString()));
        colStart.setPrefWidth(110);
        colStart.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colEnd =
                new TableColumn<>(t("labor.contracts.col.end"));
        colEnd.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().endDate() == null ? "" : c.getValue().endDate().toString()));
        colEnd.setPrefWidth(110);
        colEnd.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colSalary =
                new TableColumn<>(t("labor.contracts.col.salary"));
        colSalary.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().grossSalary() == null ? "" : c.getValue().grossSalary().toPlainString() + " €"));
        colSalary.setComparator(NUMERIC_STRING_COMPARATOR);  // VG-FULL-SCAN
        colSalary.setPrefWidth(110);
        colSalary.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colStatus =
                new TableColumn<>(t("labor.contracts.col.status"));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(localizedEnum("contract_status", c.getValue().status())));
        colStatus.setPrefWidth(110);
        table.getColumns().addAll(java.util.List.of(colEmp, colType, colSepe, colStart, colEnd, colSalary, colStatus));
        table.setItems(FXCollections.observableArrayList(contracts));

        Label hint = new Label(t("labor.contracts.global.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        VBox body = new VBox(8, hint, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        body.setPadding(new Insets(12));
        return body;
    }

    // ===================================================================
    //  Contratos por empleado (diálogo) — invocado desde Empleados
    // ===================================================================

    public void showEmployeeContracts(com.benjagest.ui.model.EmployeeEntry e) {
        Task<java.util.List<com.benjagest.ui.model.ContractEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.ContractEntry> call() throws Exception {
                return laborApiClient.listContracts(e.id());
            }
        };
        task.setOnSucceeded(ev -> showContractsDialog(e, task.getValue()));
        task.setOnFailed(ev -> showError(t("labor.contracts.load.fail"), t("labor.contracts.load.fail.body")));
        start(task, "labor-contracts-load");
    }

    private void showContractsDialog(com.benjagest.ui.model.EmployeeEntry employee,
                                      java.util.List<com.benjagest.ui.model.ContractEntry> contracts) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("labor.contracts.dialog.title") + " — " + employee.fullName());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        contractsTable = new TableView<>();
        contractsTable.getStyleClass().add("data-table");
        contractsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        contractsTable.setPrefHeight(280);
        contractsTable.setPlaceholder(new Label(t("labor.contracts.placeholder.empty")));
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colType =
                new TableColumn<>(t("labor.contracts.col.type"));
        colType.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().contractType()));
        colType.setPrefWidth(140);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colSepe =
                new TableColumn<>(t("labor.contracts.col.sepe"));
        colSepe.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sepeContractCode()));
        colSepe.setPrefWidth(70);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colStart =
                new TableColumn<>(t("labor.contracts.col.start"));
        colStart.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().startDate() == null ? "" : c.getValue().startDate().toString()));
        colStart.setPrefWidth(110);
        colStart.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colEnd =
                new TableColumn<>(t("labor.contracts.col.end"));
        colEnd.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().endDate() == null ? "" : c.getValue().endDate().toString()));
        colEnd.setPrefWidth(110);
        colEnd.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colSalary =
                new TableColumn<>(t("labor.contracts.col.salary"));
        colSalary.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().grossSalary() == null ? "" : c.getValue().grossSalary().toPlainString() + " €"));
        colSalary.setComparator(NUMERIC_STRING_COMPARATOR);  // VG-FULL-SCAN
        colSalary.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colStatus =
                new TableColumn<>(t("labor.contracts.col.status"));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(localizedEnum("contract_status", c.getValue().status())));
        colStatus.setPrefWidth(100);
        contractsTable.getColumns().addAll(java.util.List.of(colType, colSepe, colStart, colEnd, colSalary, colStatus));
        contractsTable.setItems(FXCollections.observableArrayList(contracts));

        Button newC = new Button(t("labor.contracts.action.new"));
        newC.setGraphic(icon("fas-plus"));
        newC.setOnAction(ev -> {
            // CTR-2: el botón "Nuevo contrato" abre el WIZARD nuevo
            // (4 pasos con catálogos SEPE/convenios/categorías), no el
            // editor plano antiguo. El editor antiguo queda como
            // compatibilidad para los flujos que aún lo usen.
            showContractWizard(employee, null);
            dialog.close();
        });
        Button editC = new Button(t("labor.contracts.action.edit"));
        editC.setGraphic(icon("fas-edit"));
        editC.setDisable(true);
        editC.setOnAction(ev -> {
            var sel = contractsTable.getSelectionModel().getSelectedItem();
            if (sel != null) {
                // Editar usa el editor plano: tiene todos los campos + la
                // sección de complementos salariales (el wizard no la tiene).
                showContractEditor(employee, sel);
                dialog.close();
            }
        });
        // CTR-4: botón "PDF" descarga el contrato firmable del seleccionado.
        Button pdfBtn = new Button(t("labor.contract.docs.pdf"));
        pdfBtn.setGraphic(icon("fas-file-pdf"));
        pdfBtn.setDisable(true);
        pdfBtn.setOnAction(ev -> {
            var sel = contractsTable.getSelectionModel().getSelectedItem();
            if (sel != null) downloadContractDocument(sel.id(), null, "pdf");
        });
        // CTR-5: botón "XML Contrat@" descarga el XML estructurado SEPE.
        Button xmlBtn = new Button(t("labor.contract.docs.xml"));
        xmlBtn.setGraphic(icon("fas-file-code"));
        xmlBtn.setDisable(true);
        xmlBtn.setOnAction(ev -> {
            var sel = contractsTable.getSelectionModel().getSelectedItem();
            if (sel != null) downloadContractDocument(sel.id(), null, "xml");
        });
        // VIG-3 — "Ascender / cambiar condiciones": abre el editor en modo
        // ascenso (fecha de efecto + nueva vigencia, antigüedad intacta).
        Button promoteC = new Button(t("labor.contracts.action.promote"));
        promoteC.setGraphic(icon("fas-arrow-up"));
        promoteC.setDisable(true);
        promoteC.setOnAction(ev -> {
            var sel = contractsTable.getSelectionModel().getSelectedItem();
            if (sel != null) {
                showContractEditor(employee, sel, true);
                dialog.close();
            }
        });
        contractsTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            editC.setDisable(nv == null);
            promoteC.setDisable(nv == null);
            pdfBtn.setDisable(nv == null);
            xmlBtn.setDisable(nv == null);
        });
        HBox actions = new HBox(8, newC, editC, promoteC, pdfBtn, xmlBtn);
        VBox body = new VBox(12, contractsTable, actions);
        body.setPadding(new Insets(10));
        installDialog(dialog, body);
        dialog.setResizable(true);
        dialog.showAndWait();
    }

    // ===================================================================
    //  CTR-2 — Wizard de contrato (4 pasos)
    // ===================================================================

    /**
     * Estado mutable del wizard. Vive durante el diálogo y se va llenando
     * según el usuario navega por los pasos.
     */
    private static final class WizardState {
        com.benjagest.ui.model.ContractCatalog.SepeType sepe;
        com.benjagest.ui.model.ContractCatalog.Agreement agreement;
        com.benjagest.ui.model.ContractCatalog.Category category;
        // Datos económicos (paso 3)
        java.time.LocalDate startDate = java.time.LocalDate.now();
        java.time.LocalDate endDate;
        java.math.BigDecimal weeklyHours;
        java.math.BigDecimal grossSalary;
        Integer annualBonuses = 2;
        Integer vacationDays = 30;
        java.math.BigDecimal irpfPercent;
        java.math.BigDecimal atEpPercent = new java.math.BigDecimal("1.50");
        String workplaceAddress = "";
        Integer probationDays;
        // Paso 4: cláusulas seleccionadas
        final java.util.List<com.benjagest.ui.model.ContractCatalog.ClauseTemplate> selectedClauses
                = new java.util.ArrayList<>();
        // Modelo PDF (paso 4) — decisión Benjamin 2026-06-08
        String pdfModel = "UNIFIED_2022"; // o "BY_CODE"
    }

    /** Bundle de catálogos cargados una vez al abrir el wizard. */
    private record WizardCatalogs(
            java.util.List<com.benjagest.ui.model.ContractCatalog.SepeType> sepe,
            java.util.List<com.benjagest.ui.model.ContractCatalog.Agreement> agreements,
            java.util.List<com.benjagest.ui.model.ContractCatalog.ClauseTemplate> clauses
    ) {}

    /**
     * Punto de entrada del wizard. Carga catálogos del backend de forma
     * async; cuando llegan, monta el diálogo con el primer paso. Si la
     * carga falla, muestra error humano y no abre nada.
     */
    private void showContractWizard(com.benjagest.ui.model.EmployeeEntry employee,
                                      com.benjagest.ui.model.ContractEntry existing) {
        Task<WizardCatalogs> load = new Task<>() {
            @Override protected WizardCatalogs call() throws Exception {
                return new WizardCatalogs(
                        altaApiClient.listSepeContractTypes(),
                        altaApiClient.listCollectiveAgreements(),
                        altaApiClient.listClauseTemplates()
                );
            }
        };
        load.setOnSucceeded(ev -> openContractWizardDialog(employee, existing, load.getValue()));
        load.setOnFailed(ev -> showError(t("labor.contract.wizard.load_fail.title"),
                t("labor.contract.wizard.load_fail.body")));
        start(load, "contract-wizard-load");
    }

    private void openContractWizardDialog(com.benjagest.ui.model.EmployeeEntry employee,
                                            com.benjagest.ui.model.ContractEntry existing,
                                            WizardCatalogs catalogs) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("labor.contract.wizard.title") + " — " + employee.fullName());
        dialog.setResizable(true);

        WizardState state = new WizardState();
        // Pre-rellenar desde contrato existente si lo hay
        if (existing != null) {
            // Buscar SEPE por código
            catalogs.sepe().stream()
                    .filter(s -> s.code().equals(existing.sepeContractCode()))
                    .findFirst().ifPresent(s -> state.sepe = s);
            // Buscar convenio + categoría por nombre (best effort)
            catalogs.agreements().stream()
                    .filter(a -> a.name().equals(existing.collectiveAgreement())
                            || a.code().equals(existing.collectiveAgreement()))
                    .findFirst().ifPresent(a -> {
                        state.agreement = a;
                        a.categories().stream()
                                .filter(c -> c.categoryName().equals(existing.professionalCategory()))
                                .findFirst().ifPresent(c -> state.category = c);
                    });
            state.startDate = existing.startDate();
            state.endDate = existing.endDate();
            state.weeklyHours = existing.weeklyHours();
            state.grossSalary = existing.grossSalary();
            state.annualBonuses = existing.annualBonuses();
            state.vacationDays = existing.vacationDays();
            state.irpfPercent = existing.irpfPercent();
            if (existing.atEpPercent() != null) state.atEpPercent = existing.atEpPercent();
            state.workplaceAddress = existing.workplaceAddress() == null
                    ? "" : existing.workplaceAddress();
        }

        // Indicador de paso (1/4, 2/4, 3/4, 4/4)
        Label stepIndicator = new Label();
        stepIndicator.getStyleClass().add("hero-body");
        // Contenedor del paso actual
        StackPane stepHost = new StackPane();
        stepHost.setMinHeight(360);
        VBox.setVgrow(stepHost, Priority.ALWAYS);

        // Botonera inferior. Cancelar a la izquierda + Anterior, Siguiente/Crear a la derecha.
        Button cancelBtn = new Button(t("labor.contract.wizard.cancel"));
        cancelBtn.setGraphic(icon("fas-times"));
        cancelBtn.setOnAction(ev -> {
            dialog.setResult(ButtonType.CANCEL);
            dialog.close();
        });
        Button prevBtn = new Button(t("labor.contract.wizard.prev"));
        prevBtn.setGraphic(icon("fas-arrow-left"));
        Button nextBtn = new Button(t("labor.contract.wizard.next"));
        nextBtn.setGraphic(icon("fas-arrow-right"));
        Button saveBtn = new Button(existing == null
                ? t("labor.contract.wizard.create") : t("labor.contract.wizard.update"));
        saveBtn.setGraphic(icon("fas-save"));
        saveBtn.getStyleClass().add("primary-button");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, cancelBtn, spacer, prevBtn, nextBtn, saveBtn);
        bar.setPadding(new Insets(10, 0, 0, 0));

        // Registrar ButtonType.CANCEL en el DialogPane para que la X de
        // la ventana y la tecla Escape también cierren el wizard. Sin
        // esto JavaFX no sabe cómo cerrar un diálogo con DialogPane
        // sin button types registrados (Benjamin 2026-06-08: "no me
        // deja cerrar la pagina"). Lo ocultamos visualmente porque
        // ya tenemos el botón cancelBtn en la barra inferior.
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        Node cancelNode = dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancelNode != null) {
            cancelNode.setVisible(false);
            cancelNode.setManaged(false);
        }

        // currentStep mutable via array para que las lambdas lo capturen
        int[] currentStep = {1};
        Runnable refreshStep = () -> {
            stepIndicator.setText(t("labor.contract.wizard.step")
                    + " " + currentStep[0] + " / 4");
            Node node = switch (currentStep[0]) {
                case 1 -> renderWizardStep1(state, catalogs);
                case 2 -> renderWizardStep2(state, catalogs);
                case 3 -> renderWizardStep3(state, catalogs);
                case 4 -> renderWizardStep4(state, catalogs);
                default -> new Label("Paso desconocido");
            };
            stepHost.getChildren().setAll(node);
            prevBtn.setDisable(currentStep[0] == 1);
            nextBtn.setVisible(currentStep[0] < 4);
            saveBtn.setVisible(currentStep[0] == 4);
        };

        prevBtn.setOnAction(ev -> {
            if (currentStep[0] > 1) {
                currentStep[0]--;
                refreshStep.run();
            }
        });
        nextBtn.setOnAction(ev -> {
            // Validación mínima por paso antes de avanzar
            String err = validateStep(currentStep[0], state);
            if (err != null) {
                showError(t("labor.contract.wizard.validation.title"), err);
                return;
            }
            if (currentStep[0] < 4) {
                currentStep[0]++;
                refreshStep.run();
            }
        });
        saveBtn.setOnAction(ev -> saveContractFromWizard(employee, existing, state, dialog));

        VBox root = new VBox(10, stepIndicator, stepHost, new Separator(), bar);
        root.setPadding(new Insets(16));
        root.setPrefWidth(720);
        installDialog(dialog, root);
        refreshStep.run();
        dialog.showAndWait();
    }

    /** Paso 1: Tipo de contrato (combo SEPE filtrable por familia). */
    private Node renderWizardStep1(WizardState state, WizardCatalogs cat) {
        VBox box = new VBox(12);
        Label title = new Label(t("labor.contract.wizard.step1.title"));
        title.getStyleClass().add("hero-title");
        Label hint = new Label(t("labor.contract.wizard.step1.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        ComboBox<String> familyCombo = new ComboBox<>();
        familyCombo.getItems().addAll("", "INDEFINIDO", "TEMPORAL", "FORMATIVO",
                "PRACTICAS", "INSERCION", "FONDOS_EUROPEOS", "DISCAPACIDAD");
        familyCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String c) {
                if (c == null || c.isEmpty()) return t("labor.contract.wizard.step1.all_families");
                return humanizeFromKey("labor.contract.family." + c, c);
            }
            @Override public String fromString(String s) { return null; }
        });
        familyCombo.setValue("");

        ComboBox<com.benjagest.ui.model.ContractCatalog.SepeType> sepeCombo = new ComboBox<>();
        sepeCombo.setMaxWidth(Double.MAX_VALUE);
        sepeCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.ContractCatalog.SepeType s) {
                return s == null ? "" : s.label();
            }
            @Override public com.benjagest.ui.model.ContractCatalog.SepeType fromString(String s) {
                return null;
            }
        });

        Runnable refreshSepe = () -> {
            String family = familyCombo.getValue();
            sepeCombo.getItems().clear();
            for (var s : cat.sepe()) {
                if (family == null || family.isEmpty() || family.equals(s.family())) {
                    sepeCombo.getItems().add(s);
                }
            }
            if (state.sepe != null) sepeCombo.setValue(state.sepe);
        };
        familyCombo.valueProperty().addListener((o, ov, nv) -> refreshSepe.run());
        refreshSepe.run();
        sepeCombo.valueProperty().addListener((o, ov, nv) -> state.sepe = nv);

        Label desc = new Label("");
        desc.setWrapText(true);
        desc.getStyleClass().add("settings-hint");
        sepeCombo.valueProperty().addListener((o, ov, nv) -> {
            if (nv != null && nv.legalBasis() != null) {
                desc.setText("📘 " + nv.legalBasis());
            } else desc.setText("");
        });
        if (state.sepe != null && state.sepe.legalBasis() != null) {
            desc.setText("📘 " + state.sepe.legalBasis());
        }

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.add(new Label(t("labor.contract.wizard.step1.filter")), 0, 0);
        grid.add(familyCombo, 1, 0);
        grid.add(new Label(t("labor.contract.wizard.step1.sepe")), 0, 1);
        grid.add(sepeCombo, 1, 1);
        javafx.scene.layout.ColumnConstraints col0 = new javafx.scene.layout.ColumnConstraints();
        col0.setMinWidth(160);
        javafx.scene.layout.ColumnConstraints col1 = new javafx.scene.layout.ColumnConstraints();
        col1.setHgrow(Priority.ALWAYS); col1.setFillWidth(true);
        grid.getColumnConstraints().addAll(col0, col1);

        // CTR-3: botón "Cargar plantilla" — pre-rellena el state desde una
        // plantilla guardada. Se queda en el paso 1 pero los demás pasos
        // ya saldrán pintados con los valores cargados.
        Button loadTpl = new Button(t("labor.contract.wizard.load_template"));
        loadTpl.setGraphic(icon("fas-clone"));
        loadTpl.setOnAction(ev -> showLoadTemplatePicker(state, sepeCombo));
        HBox loadRow = new HBox(loadTpl);
        loadRow.setAlignment(Pos.CENTER_RIGHT);

        box.getChildren().addAll(title, hint, loadRow, grid, desc);
        return box;
    }

    /** Diálogo para elegir plantilla y aplicarla al WizardState. */
    private void showLoadTemplatePicker(WizardState state, ComboBox<com.benjagest.ui.model.ContractCatalog.SepeType> sepeCombo) {
        Task<java.util.List<com.benjagest.ui.model.ContractTemplate>> task = new Task<>() {
            @Override protected java.util.List<com.benjagest.ui.model.ContractTemplate> call() throws Exception {
                return altaApiClient.listContractTemplates();
            }
        };
        task.setOnSucceeded(ev -> {
            var list = task.getValue();
            if (list == null || list.isEmpty()) {
                showInfo(t("labor.contract.wizard.load_template"), t("labor.templates.empty"));
                return;
            }
            ChoiceDialog<com.benjagest.ui.model.ContractTemplate> dlg = new ChoiceDialog<>(list.get(0), list);
            dlg.setTitle(t("labor.contract.wizard.load_template"));
            dlg.setHeaderText(t("labor.templates.hint"));
            dlg.setContentText(t("labor.templates.col.name") + ":");
            // ChoiceDialog usa toString — los records ContractTemplate no
            // lo sobrescriben, así que añadimos converter al ComboBox interno:
            dlg.showAndWait().ifPresent(tpl -> applyTemplateToState(tpl, state, sepeCombo));
        });
        task.setOnFailed(ev -> showError(t("labor.templates.fail"),
                task.getException() == null ? "" : task.getException().getMessage()));
        start(task, "templates-pick");
    }

    private void applyTemplateToState(com.benjagest.ui.model.ContractTemplate tpl,
                                       WizardState state,
                                       ComboBox<com.benjagest.ui.model.ContractCatalog.SepeType> sepeCombo) {
        if (tpl.weeklyHours() != null) state.weeklyHours = tpl.weeklyHours();
        if (tpl.grossSalary() != null) state.grossSalary = tpl.grossSalary();
        if (tpl.annualBonuses() != null) state.annualBonuses = tpl.annualBonuses();
        if (tpl.vacationDays() != null) state.vacationDays = tpl.vacationDays();
        if (tpl.irpfPercent() != null) state.irpfPercent = tpl.irpfPercent();
        if (tpl.probationDays() != null) state.probationDays = tpl.probationDays();
        if (tpl.workplaceAddress() != null) state.workplaceAddress = tpl.workplaceAddress();
        if (tpl.pdfModel() != null) state.pdfModel = tpl.pdfModel();
        if (tpl.sepeContractCode() != null && sepeCombo != null) {
            for (var it : sepeCombo.getItems()) {
                if (tpl.sepeContractCode().equals(it.code())) {
                    sepeCombo.getSelectionModel().select(it);
                    break;
                }
            }
        }
        showInfo(t("labor.contract.wizard.load_template"), tpl.name());
    }

    /** Paso 2: Convenio + categoría profesional (cascada). */
    private Node renderWizardStep2(WizardState state, WizardCatalogs cat) {
        VBox box = new VBox(12);
        Label title = new Label(t("labor.contract.wizard.step2.title"));
        title.getStyleClass().add("hero-title");
        Label hint = new Label(t("labor.contract.wizard.step2.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        ComboBox<com.benjagest.ui.model.ContractCatalog.Agreement> agreeCombo = new ComboBox<>();
        agreeCombo.setMaxWidth(Double.MAX_VALUE);
        agreeCombo.getItems().addAll(cat.agreements());
        agreeCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.ContractCatalog.Agreement a) {
                return a == null ? "" : a.name();
            }
            @Override public com.benjagest.ui.model.ContractCatalog.Agreement fromString(String s) {
                return null;
            }
        });
        if (state.agreement != null) agreeCombo.setValue(state.agreement);

        ComboBox<com.benjagest.ui.model.ContractCatalog.Category> catCombo = new ComboBox<>();
        catCombo.setMaxWidth(Double.MAX_VALUE);
        catCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.ContractCatalog.Category c) {
                return c == null ? "" : c.label();
            }
            @Override public com.benjagest.ui.model.ContractCatalog.Category fromString(String s) {
                return null;
            }
        });

        Label minSalaryHint = new Label("");
        minSalaryHint.setWrapText(true);
        minSalaryHint.getStyleClass().add("settings-hint");

        Runnable refreshCats = () -> {
            var ag = agreeCombo.getValue();
            catCombo.getItems().clear();
            if (ag != null) catCombo.getItems().addAll(ag.categories());
            if (state.category != null && ag != null
                    && ag.id().equals(state.category.collectiveAgreementId())) {
                catCombo.setValue(state.category);
            } else {
                catCombo.setValue(null);
            }
        };
        agreeCombo.valueProperty().addListener((o, ov, nv) -> {
            state.agreement = nv;
            refreshCats.run();
        });
        catCombo.valueProperty().addListener((o, ov, nv) -> {
            state.category = nv;
            if (nv != null) {
                String text = t("labor.contract.wizard.step2.min_info") + "\n";
                if (nv.minAnnualSalary() != null) text += "• Salario mínimo anual: " + nv.minAnnualSalary() + " €\n";
                if (nv.maxWeeklyHours() != null) text += "• Jornada máxima semanal: " + nv.maxWeeklyHours() + " h\n";
                if (nv.probationDays() != null) text += "• Periodo de prueba estándar: " + nv.probationDays() + " días\n";
                minSalaryHint.setText(text);
            } else minSalaryHint.setText("");
        });
        refreshCats.run();

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.add(new Label(t("labor.contract.wizard.step2.agreement")), 0, 0);
        grid.add(agreeCombo, 1, 0);
        grid.add(new Label(t("labor.contract.wizard.step2.category")), 0, 1);
        grid.add(catCombo, 1, 1);
        javafx.scene.layout.ColumnConstraints col0 = new javafx.scene.layout.ColumnConstraints();
        col0.setMinWidth(160);
        javafx.scene.layout.ColumnConstraints col1 = new javafx.scene.layout.ColumnConstraints();
        col1.setHgrow(Priority.ALWAYS); col1.setFillWidth(true);
        grid.getColumnConstraints().addAll(col0, col1);

        box.getChildren().addAll(title, hint, grid, minSalaryHint);
        return box;
    }

    /** Paso 3: Datos económicos (auto-rellenados desde categoría). */
    private Node renderWizardStep3(WizardState state, WizardCatalogs cat) {
        VBox box = new VBox(12);
        Label title = new Label(t("labor.contract.wizard.step3.title"));
        title.getStyleClass().add("hero-title");
        Label hint = new Label(t("labor.contract.wizard.step3.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        // Pre-rellenar desde categoría si no se ha tocado ya
        if (state.category != null) {
            if (state.weeklyHours == null) state.weeklyHours = state.category.maxWeeklyHours();
            if (state.grossSalary == null) state.grossSalary = state.category.minAnnualSalary();
            if (state.probationDays == null) state.probationDays = state.category.probationDays();
        }

        DatePicker startP = new DatePicker(state.startDate);
        startP.valueProperty().addListener((o, ov, nv) -> state.startDate = nv);
        DatePicker endP = new DatePicker(state.endDate);
        endP.setPromptText(t("labor.contract.wizard.step3.end_optional"));
        endP.valueProperty().addListener((o, ov, nv) -> state.endDate = nv);

        TextField hoursF = new TextField(state.weeklyHours == null ? "" : state.weeklyHours.toPlainString());
        hoursF.textProperty().addListener((o, ov, nv) -> state.weeklyHours = parseDecSafe(nv));
        TextField salaryF = new TextField(state.grossSalary == null ? "" : state.grossSalary.toPlainString());
        salaryF.textProperty().addListener((o, ov, nv) -> state.grossSalary = parseDecSafe(nv));

        Label salaryWarn = new Label("");
        salaryWarn.setWrapText(true);
        salaryWarn.getStyleClass().add("settings-hint");
        salaryF.textProperty().addListener((o, ov, nv) -> {
            java.math.BigDecimal s = parseDecSafe(nv);
            if (s != null && state.category != null && state.category.minAnnualSalary() != null
                    && s.compareTo(state.category.minAnnualSalary()) < 0) {
                salaryWarn.setText("⚠ " + t("labor.contract.wizard.step3.salary_below_minimum")
                        + " " + state.category.minAnnualSalary() + " €");
                salaryWarn.setStyle("-fx-text-fill: #b58900;");
            } else {
                salaryWarn.setText("");
                salaryWarn.setStyle("");
            }
        });

        TextField bonusesF = new TextField(state.annualBonuses == null ? "2" : state.annualBonuses.toString());
        bonusesF.textProperty().addListener((o, ov, nv) -> state.annualBonuses = parseIntSafe(nv));
        TextField vacF = new TextField(state.vacationDays == null ? "30" : state.vacationDays.toString());
        vacF.textProperty().addListener((o, ov, nv) -> state.vacationDays = parseIntSafe(nv));
        TextField irpfF = new TextField(state.irpfPercent == null ? "" : state.irpfPercent.toPlainString());
        irpfF.textProperty().addListener((o, ov, nv) -> state.irpfPercent = parseDecSafe(nv));
        TextField atEpF = new TextField(state.atEpPercent == null ? "" : state.atEpPercent.toPlainString());
        atEpF.textProperty().addListener((o, ov, nv) -> state.atEpPercent = parseDecSafe(nv));
        TextField probF = new TextField(state.probationDays == null ? "" : state.probationDays.toString());
        probF.textProperty().addListener((o, ov, nv) -> state.probationDays = parseIntSafe(nv));
        TextField wpF = new TextField(state.workplaceAddress);
        wpF.textProperty().addListener((o, ov, nv) -> state.workplaceAddress = nv == null ? "" : nv);

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8);
        int r = 0;
        grid.add(new Label(t("labor.contract.editor.start")), 0, r);     grid.add(startP, 1, r);
        grid.add(new Label(t("labor.contract.editor.end")), 2, r);       grid.add(endP, 3, r); r++;
        grid.add(new Label(t("labor.contract.editor.weekly_hours")), 0, r); grid.add(hoursF, 1, r);
        grid.add(new Label(t("labor.contract.editor.salary")), 2, r);    grid.add(salaryF, 3, r); r++;
        grid.add(new Label(t("labor.contract.editor.bonuses")), 0, r);   grid.add(bonusesF, 1, r);
        grid.add(new Label(t("labor.contract.editor.vacation")), 2, r);  grid.add(vacF, 3, r); r++;
        grid.add(new Label(t("labor.contract.editor.irpf")), 0, r);      grid.add(irpfF, 1, r);
        grid.add(new Label(t("labor.contract.wizard.step3.probation_days")), 2, r); grid.add(probF, 3, r); r++;
        grid.add(new Label(t("labor.contract.editor.at_ep")), 0, r);     grid.add(atEpF, 1, r); r++;
        grid.add(new Label(t("labor.contract.editor.workplace")), 0, r); grid.add(wpF, 1, r, 3, 1);

        box.getChildren().addAll(title, hint, grid, salaryWarn);
        return box;
    }

    /** Paso 4: Revisión + anexos + crear. */
    private Node renderWizardStep4(WizardState state, WizardCatalogs cat) {
        VBox box = new VBox(12);
        Label title = new Label(t("labor.contract.wizard.step4.title"));
        title.getStyleClass().add("hero-title");
        Label hint = new Label(t("labor.contract.wizard.step4.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        // Resumen
        VBox summary = new VBox(4);
        summary.getStyleClass().add("settings-section");
        summary.setPadding(new Insets(8));
        if (state.sepe != null) summary.getChildren().add(new Label("• " + t("labor.contract.wizard.summary.sepe") + " " + state.sepe.label()));
        if (state.agreement != null) summary.getChildren().add(new Label("• " + t("labor.contract.wizard.summary.agreement") + " " + state.agreement.name()));
        if (state.category != null) summary.getChildren().add(new Label("• " + t("labor.contract.wizard.summary.category") + " " + state.category.label()));
        if (state.startDate != null) summary.getChildren().add(new Label("• " + t("labor.contract.editor.start") + ": " + state.startDate));
        if (state.endDate != null) summary.getChildren().add(new Label("• " + t("labor.contract.editor.end") + ": " + state.endDate));
        if (state.grossSalary != null) summary.getChildren().add(new Label("• " + t("labor.contract.editor.salary") + ": " + state.grossSalary + " €"));
        if (state.weeklyHours != null) summary.getChildren().add(new Label("• " + t("labor.contract.editor.weekly_hours") + ": " + state.weeklyHours + " h"));

        // Combo modelo PDF (decisión 2026-06-08: el asesor elige al final)
        ComboBox<String> pdfModelCombo = new ComboBox<>();
        pdfModelCombo.getItems().addAll("UNIFIED_2022", "BY_CODE");
        pdfModelCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String c) {
                return c == null ? "" : humanizeFromKey("labor.contract.wizard.step4.pdf_model." + c, c);
            }
            @Override public String fromString(String s) { return null; }
        });
        pdfModelCombo.setValue(state.pdfModel);
        pdfModelCombo.valueProperty().addListener((o, ov, nv) -> state.pdfModel = nv);

        HBox modelRow = new HBox(10,
                new Label(t("labor.contract.wizard.step4.pdf_model_label")), pdfModelCombo);
        modelRow.setAlignment(Pos.CENTER_LEFT);

        // Cláusulas opcionales
        Label clausesTitle = new Label(t("labor.contract.wizard.step4.clauses_title"));
        clausesTitle.getStyleClass().add("settings-section-title");
        Label clausesHint = new Label(t("labor.contract.wizard.step4.clauses_hint"));
        clausesHint.setWrapText(true);
        clausesHint.getStyleClass().add("settings-hint");

        VBox clausesBox = new VBox(4);
        for (var clause : cat.clauses()) {
            CheckBox cb = new CheckBox(clause.title());
            cb.setSelected(state.selectedClauses.stream()
                    .anyMatch(s -> s.id().equals(clause.id())));
            cb.selectedProperty().addListener((o, ov, nv) -> {
                if (nv) {
                    if (state.selectedClauses.stream().noneMatch(s -> s.id().equals(clause.id())))
                        state.selectedClauses.add(clause);
                } else {
                    state.selectedClauses.removeIf(s -> s.id().equals(clause.id()));
                }
            });
            clausesBox.getChildren().add(cb);
        }
        ScrollPane clausesScroll = new ScrollPane(clausesBox);
        clausesScroll.setFitToWidth(true);
        clausesScroll.setPrefViewportHeight(180);

        // CTR-3: botón "Guardar como plantilla" — pregunta nombre y persiste
        // todos los datos económicos + SEPE + pdf_model como ContractTemplate
        // del tenant. Disponible solo si hay datos mínimos cargados.
        Button saveTpl = new Button(t("labor.contract.wizard.save_template"));
        saveTpl.setGraphic(icon("fas-clone"));
        saveTpl.setOnAction(ev -> saveWizardStateAsTemplate(state));
        HBox saveTplRow = new HBox(saveTpl);
        saveTplRow.setAlignment(Pos.CENTER_RIGHT);

        box.getChildren().addAll(title, hint, summary, modelRow, saveTplRow,
                new Separator(), clausesTitle, clausesHint, clausesScroll);
        return box;
    }

    /** CTR-3 — Persiste el WizardState actual como plantilla reutilizable. */
    private void saveWizardStateAsTemplate(WizardState state) {
        TextInputDialog ask = new TextInputDialog();
        ask.setTitle(t("labor.contract.wizard.save_template"));
        ask.setHeaderText(t("labor.contract.wizard.save_template"));
        ask.setContentText(t("labor.contract.wizard.template.name_prompt"));
        ask.showAndWait().ifPresent(name -> {
            if (name == null || name.isBlank()) return;
            com.benjagest.ui.model.ContractTemplate tpl = new com.benjagest.ui.model.ContractTemplate(
                    null, name.trim(), null,
                    state.sepe == null ? null : state.sepe.code(),
                    state.sepe == null ? null : state.sepe.family(),
                    state.agreement == null ? null : state.agreement.id(),
                    state.category == null ? null : state.category.id(),
                    state.category == null ? null : state.category.groupCode(),
                    state.weeklyHours, state.grossSalary,
                    state.annualBonuses, state.vacationDays,
                    state.irpfPercent, state.probationDays,
                    state.workplaceAddress,
                    null, state.pdfModel,
                    false, true);
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    altaApiClient.createContractTemplate(tpl);
                    return null;
                }
            };
            task.setOnSucceeded(ev -> showInfo(t("labor.contract.wizard.save_template"), name));
            task.setOnFailed(ev -> showError(t("labor.templates.fail"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "template-save-wizard");
        });
    }

    /** Validación por paso. Devuelve null si OK, mensaje si error. */
    private String validateStep(int step, WizardState s) {
        return switch (step) {
            case 1 -> s.sepe == null ? t("labor.contract.wizard.validation.sepe_required") : null;
            case 2 -> s.agreement == null || s.category == null
                    ? t("labor.contract.wizard.validation.agreement_required") : null;
            case 3 -> {
                if (s.startDate == null) yield t("labor.contract.wizard.validation.start_required");
                if (s.endDate != null && s.startDate.isAfter(s.endDate))
                    yield t("labor.contract.wizard.validation.dates_invalid");
                if (s.weeklyHours == null) yield t("labor.contract.wizard.validation.hours_required");
                if (s.grossSalary == null) yield t("labor.contract.wizard.validation.salary_required");
                yield null;
            }
            default -> null;
        };
    }

    /** Construye el ContractEntry desde el WizardState y persiste. */
    private void saveContractFromWizard(com.benjagest.ui.model.EmployeeEntry employee,
                                          com.benjagest.ui.model.ContractEntry existing,
                                          WizardState state,
                                          Dialog<ButtonType> dialog) {
        String err = validateStep(3, state);
        if (err != null) {
            showError(t("labor.contract.wizard.validation.title"), err);
            return;
        }
        String contractType = state.sepe == null ? "Indefinido"
                : humanizeFromKey("labor.contract.family." + state.sepe.family(), state.sepe.family());
        com.benjagest.ui.model.ContractEntry payload = new com.benjagest.ui.model.ContractEntry(
                existing == null ? null : existing.id(),
                employee.id(),
                contractType,
                state.sepe == null ? null : state.sepe.code(),
                state.agreement == null ? null : state.agreement.name(),
                state.category == null ? null : state.category.categoryName(),
                state.category == null ? null : state.category.groupCode(),
                state.startDate,
                existing == null ? null : existing.seniorityDate(),
                state.endDate,
                state.weeklyHours,
                state.grossSalary,
                state.annualBonuses,
                existing != null && Boolean.TRUE.equals(existing.extrasProrated()),
                state.vacationDays,
                state.irpfPercent,
                state.atEpPercent,
                // VIG-0: el grupo de cotización se DERIVA de la categoría elegida
                // (el catálogo lo trae); si la categoría no lo define, se conserva
                // el del contrato existente o por defecto el 7 (auxiliar admin).
                state.category != null && state.category.ssContributionGroup() != null
                        ? state.category.ssContributionGroup()
                        : (existing == null || existing.ssContributionGroup() == null
                                ? Integer.valueOf(7) : existing.ssContributionGroup()),
                state.workplaceAddress == null || state.workplaceAddress.isBlank()
                        ? null : state.workplaceAddress,
                existing == null ? "ACTIVE" : existing.status(),
                null,
                state.probationDays,
                state.pdfModel,
                null); // el asistente no toca los complementos (se editan en el editor)
        Task<com.benjagest.ui.model.ContractEntry> task = new Task<>() {
            @Override protected com.benjagest.ui.model.ContractEntry call() throws Exception {
                com.benjagest.ui.model.ContractEntry saved = existing == null
                        ? laborApiClient.createContract(payload)
                        : laborApiClient.updateContract(existing.id(), payload);
                // CTR-7 — vincular las cláusulas/anexos seleccionados al contrato
                // guardado. Si el contrato ya existía y se editan cláusulas,
                // la lógica simple intenta vincular todo: el backend devuelve
                // 409 en duplicados y lo ignoramos.
                if (saved.id() != null) {
                    for (var clause : state.selectedClauses) {
                        try {
                            altaApiClient.linkContractAnnex(saved.id(), clause.id());
                        } catch (Exception linkErr) {
                            // ignore duplicates and continue with the rest
                        }
                    }
                }
                return saved;
            }
        };
        task.setOnSucceeded(ev -> {
            com.benjagest.ui.model.ContractEntry saved = task.getValue();
            dialog.setResult(ButtonType.OK);
            dialog.close();
            // CTR-4/5 — ofrecer descarga inmediata del PDF y XML
            offerContractDocumentDownloads(saved, state.pdfModel);
            host.refreshLabor();
        });
        task.setOnFailed(ev -> {
            Throwable e = task.getException();
            showError(t("labor.contract.editor.fail.title"),
                    e == null || e.getMessage() == null
                            ? t("labor.contract.editor.fail.body") : e.getMessage());
        });
        start(task, "contract-save");
    }

    /**
     * CTR-4 / CTR-5 — Tras guardar el contrato, abre un diálogo informativo
     * con dos botones: "Descargar PDF" y "Descargar XML". El asesor decide.
     * El modelo PDF preferido viene de WizardState (paso 4).
     */
    private void offerContractDocumentDownloads(com.benjagest.ui.model.ContractEntry saved, String pdfModel) {
        if (saved == null || saved.id() == null) {
            showInfo(t("labor.contract.wizard.saved.title"), t("labor.contract.wizard.saved.body"));
            return;
        }
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle(t("labor.contract.wizard.saved.title"));
        a.setHeaderText(t("labor.contract.wizard.saved.body"));
        a.setContentText(t("labor.contract.docs.offer"));
        ButtonType pdfBt = new ButtonType(t("labor.contract.docs.pdf"));
        ButtonType xmlBt = new ButtonType(t("labor.contract.docs.xml"));
        ButtonType laterBt = new ButtonType(t("labor.contract.docs.later"), ButtonBar.ButtonData.CANCEL_CLOSE);
        a.getButtonTypes().setAll(pdfBt, xmlBt, laterBt);
        a.showAndWait().ifPresent(bt -> {
            if (bt == pdfBt) downloadContractDocument(saved.id(), pdfModel, "pdf");
            else if (bt == xmlBt) downloadContractDocument(saved.id(), null, "xml");
        });
    }

    /**
     * Descarga el PDF o XML del contrato. Para PDF abre el visor interno
     * basado en PDFBox (no depende de visor externo del sistema). Para
     * XML guarda en temp y muestra ruta + botón "Copiar ruta" al
     * portapapeles. El visor PDF interno y el diálogo de ruta viven en el
     * shell (Host compartido con facturación/consolidación).
     */
    private void downloadContractDocument(String contractId, String pdfModel, String kind) {
        Task<java.nio.file.Path> task = new Task<>() {
            @Override protected java.nio.file.Path call() throws Exception {
                byte[] bytes = "pdf".equals(kind)
                        ? altaApiClient.downloadContractPdf(contractId, pdfModel)
                        : altaApiClient.downloadContractXml(contractId);
                java.nio.file.Path tmp = java.nio.file.Files.createTempFile(
                        "contract-" + contractId.substring(0, 8) + "-", "." + kind);
                java.nio.file.Files.write(tmp, bytes);
                return tmp;
            }
        };
        task.setOnSucceeded(ev -> {
            java.nio.file.Path path = task.getValue();
            if ("pdf".equals(kind)) {
                try {
                    byte[] bytes = java.nio.file.Files.readAllBytes(path);
                    host.showInternalPdfViewer(bytes, path);
                } catch (Exception e) {
                    host.showXmlSavedDialog(path);
                }
            } else {
                host.showXmlSavedDialog(path);
            }
        });
        task.setOnFailed(ev -> showError(t("labor.contract.docs.fail.title"),
                task.getException() == null ? "" : task.getException().getMessage()));
        start(task, "contract-doc-" + kind);
    }

    /** Importe del salario base de un contrato (concepto SALARY_BASE) o, si
     *  no hay desglose, el bruto anual completo. */
    private java.math.BigDecimal baseSalaryOf(com.benjagest.ui.model.ContractEntry c) {
        if (c == null) return null;
        if (c.salaryItems() != null) {
            for (var it : c.salaryItems()) {
                if ("SALARY_BASE".equals(it.kind())) return it.annualAmount();
            }
        }
        return c.grossSalary();
    }

    /** Complementos (todo lo que no es el salario base) de un contrato. */
    private java.util.List<com.benjagest.ui.model.SalaryItemEntry> complementsOf(
            com.benjagest.ui.model.ContractEntry c) {
        java.util.List<com.benjagest.ui.model.SalaryItemEntry> out = new java.util.ArrayList<>();
        if (c != null && c.salaryItems() != null) {
            for (var it : c.salaryItems()) {
                if (!"SALARY_BASE".equals(it.kind())) out.add(it);
            }
        }
        return out;
    }

    private void showContractEditor(com.benjagest.ui.model.EmployeeEntry employee,
                                     com.benjagest.ui.model.ContractEntry existing) {
        showContractEditor(employee, existing, false);
    }

    /**
     * VIG-3 — Editor de contrato. Con {@code promoteMode=true} actúa como
     * "Ascender / cambiar condiciones": pide fecha de efecto + motivo, deja
     * fijos los datos que el ascenso NO cambia (tipo, SEPE, fechas, antigüedad,
     * estado, centro) y guarda vía /promote (nueva vigencia, antigüedad
     * intacta). Solo válido sobre un contrato existente.
     */
    private void showContractEditor(com.benjagest.ui.model.EmployeeEntry employee,
                                     com.benjagest.ui.model.ContractEntry existing,
                                     boolean promoteMode) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle((promoteMode ? t("labor.contract.promote.title")
                : existing == null ? t("labor.contract.editor.title_new")
                : t("labor.contract.editor.title_edit")) + " — " + employee.fullName());
        ButtonType saveBt = new ButtonType(promoteMode ? t("labor.contract.promote.save")
                : t("labor.contract.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField typeField = new TextField(existing == null ? "Indefinido" : existing.contractType());
        TextField sepeField = new TextField(existing == null ? "100" : existing.sepeContractCode());
        // Convenio y categoría como combos del catálogo, FILTRABLES al teclear
        // (igual que el contrato nuevo). Editables: si el valor guardado no está
        // en el catálogo (contratos antiguos), se conserva como texto libre.
        ComboBox<String> agreementCombo = new ComboBox<>();
        agreementCombo.setEditable(true);
        agreementCombo.setMaxWidth(Double.MAX_VALUE);
        agreementCombo.getEditor().setText(existing == null || existing.collectiveAgreement() == null
                ? "" : existing.collectiveAgreement());
        ComboBox<String> catCombo = new ComboBox<>();
        catCombo.setEditable(true);
        catCombo.setMaxWidth(Double.MAX_VALUE);
        catCombo.getEditor().setText(existing == null || existing.professionalCategory() == null
                ? "" : existing.professionalCategory());
        TextField groupField = new TextField(existing == null ? "" : existing.professionalGroup());
        TextField startField = new TextField(existing == null || existing.startDate() == null
                ? LocalDate.now().toString() : existing.startDate().toString());
        TextField seniorityField = new TextField(existing == null || existing.seniorityDate() == null
                ? "" : existing.seniorityDate().toString());
        seniorityField.setPromptText("AAAA-MM-DD");
        com.benjagest.ui.support.EditableCells.installIsoDateMask(seniorityField);
        TextField endField = new TextField(existing == null || existing.endDate() == null
                ? "" : existing.endDate().toString());
        TextField hoursField = new TextField(existing == null || existing.weeklyHours() == null
                ? "40" : existing.weeklyHours().toPlainString());
        java.math.BigDecimal baseSal = baseSalaryOf(existing);
        TextField salaryField = new TextField(baseSal == null ? "" : baseSal.toPlainString());
        com.benjagest.ui.support.SalaryComplementsEditor compEditor = new com.benjagest.ui.support.SalaryComplementsEditor(this::t, complementsOf(existing));
        TextField bonusesField = new TextField(existing == null || existing.annualBonuses() == null
                ? "2" : existing.annualBonuses().toString());
        CheckBox proratedField = new CheckBox(t("labor.contract.editor.prorated"));
        proratedField.setSelected(existing != null && Boolean.TRUE.equals(existing.extrasProrated()));
        TextField vacationField = new TextField(existing == null || existing.vacationDays() == null
                ? "30" : existing.vacationDays().toString());
        TextField irpfField = new TextField(existing == null || existing.irpfPercent() == null
                ? "" : existing.irpfPercent().toPlainString());
        TextField atEpField = new TextField(existing == null || existing.atEpPercent() == null
                ? "1.50" : existing.atEpPercent().toPlainString());
        ComboBox<Integer> ssGroupCombo = new ComboBox<>();
        for (int gi = 1; gi <= 11; gi++) ssGroupCombo.getItems().add(gi);
        ssGroupCombo.getSelectionModel().select(Integer.valueOf(
                existing == null || existing.ssContributionGroup() == null
                        ? 7 : existing.ssContributionGroup()));
        TextField workplaceField = new TextField(existing == null ? "" : existing.workplaceAddress());
        ComboBox<String> statusCombo = new ComboBox<>();
        statusCombo.getItems().addAll("DRAFT", "ACTIVE", "SUSPENDED", "TERMINATED");
        localizeEnumCombo(statusCombo, "contract_status");
        statusCombo.getSelectionModel().select(existing == null ? "ACTIVE" : existing.status());

        // VIG-3 — Campos del ascenso (fecha de efecto + motivo) y bloqueo de los
        // datos que el ascenso NO cambia: tipo, SEPE, fechas, antigüedad, estado y
        // centro. La antigüedad se conserva (promote no toca start/seniority).
        TextField effectiveField = new TextField(LocalDate.now().toString());
        effectiveField.setPromptText("AAAA-MM-DD");
        com.benjagest.ui.support.EditableCells.installIsoDateMask(effectiveField);
        TextField reasonField = new TextField();
        reasonField.setPromptText(t("labor.contract.promote.reason.prompt"));
        if (promoteMode) {
            for (TextField f : new TextField[]{typeField, sepeField, startField,
                    seniorityField, endField, workplaceField}) {
                f.setDisable(true);
            }
            statusCombo.setDisable(true);
            effectiveField.textProperty().addListener((o, ov, nv) ->
                    effectiveField.getStyleClass().remove("field-error"));
        }

        // IRPF-VOL — Botón "Sugerir": calcula el tipo de retención (mínimo legal,
        // del Modelo 145 del empleado o estimación por tramos) y lo muestra; si el
        // campo está vacío lo rellena, y si el tecleado es MENOR avisa (por ley
        // solo se puede subir, no bajar). El campo sigue siendo el % voluntario.
        Button suggestIrpfBtn = new Button(t("labor.contract.editor.irpf_suggest"));
        suggestIrpfBtn.getStyleClass().add("button-secondary");
        Label irpfSuggestLbl = new Label("");
        irpfSuggestLbl.getStyleClass().add("settings-hint");
        irpfSuggestLbl.setWrapText(true);
        suggestIrpfBtn.setOnAction(ev -> {
            java.math.BigDecimal annualGross = parseDecSafe(salaryField.getText());
            if (annualGross == null) annualGross = java.math.BigDecimal.ZERO;
            for (var it : compEditor.getComplements()) {
                if (it.annualAmount() != null) annualGross = annualGross.add(it.annualAmount());
            }
            final java.math.BigDecimal ag = annualGross;
            java.time.LocalDate sd = parseDateSafe(startField.getText());
            final int yr = sd != null ? sd.getYear() : java.time.LocalDate.now().getYear();
            Task<java.math.BigDecimal> tk = new Task<>() {
                @Override protected java.math.BigDecimal call() throws Exception {
                    return laborApiClient.suggestIrpfRate(employee.id(), ag, yr);
                }
            };
            tk.setOnSucceeded(e2 -> {
                java.math.BigDecimal sug = tk.getValue();
                irpfSuggestLbl.setText(t("labor.contract.editor.irpf_suggested")
                        .replace("{pct}", sug == null ? "?" : sug.toPlainString()));
                java.math.BigDecimal cur = parseDecSafe(irpfField.getText());
                if (cur == null || cur.signum() == 0) {
                    irpfField.setText(sug == null ? "" : sug.toPlainString());
                } else if (sug != null && cur.compareTo(sug) < 0) {
                    toast(dialog.getDialogPane().getScene().getWindow(),
                            t("labor.contract.editor.irpf_below_min"));
                    highlightMissing(irpfField);
                }
            });
            tk.setOnFailed(e2 -> irpfSuggestLbl.setText(""));
            start(tk, "suggest-irpf");
        });
        HBox irpfBox = new HBox(6, irpfField, suggestIrpfBtn);
        irpfBox.setAlignment(Pos.CENTER_LEFT);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(10));
        int row = 0;
        g.add(new Label(t("labor.contract.editor.type")), 0, row); g.add(typeField, 1, row);
        g.add(new Label(t("labor.contract.editor.sepe")), 2, row); g.add(sepeField, 3, row); row++;
        g.add(new Label(t("labor.contract.editor.agreement")), 0, row); g.add(agreementCombo, 1, row, 3, 1); row++;
        g.add(new Label(t("labor.contract.editor.category")), 0, row); g.add(catCombo, 1, row);
        g.add(new Label(t("labor.contract.editor.group")), 2, row); g.add(groupField, 3, row); row++;
        g.add(new Label(t("labor.contract.editor.start")), 0, row); g.add(startField, 1, row);
        g.add(new Label(t("labor.contract.editor.end")), 2, row); g.add(endField, 3, row); row++;
        g.add(new Label(t("labor.contract.editor.seniority")), 0, row); g.add(seniorityField, 1, row, 3, 1); row++;
        g.add(new Label(t("labor.contract.editor.weekly_hours")), 0, row); g.add(hoursField, 1, row);
        g.add(new Label(t("labor.contract.editor.base_salary")), 2, row); g.add(salaryField, 3, row); row++;
        g.add(new Label(t("labor.contract.editor.bonuses")), 0, row); g.add(bonusesField, 1, row);
        g.add(new Label(t("labor.contract.editor.vacation")), 2, row); g.add(vacationField, 3, row); row++;
        g.add(proratedField, 1, row, 3, 1); row++;
        g.add(new Label(t("labor.contract.editor.irpf")), 0, row); g.add(irpfBox, 1, row);
        g.add(new Label(t("labor.contract.editor.status")), 2, row); g.add(statusCombo, 3, row); row++;
        g.add(irpfSuggestLbl, 0, row, 4, 1); row++;
        g.add(new Label(t("labor.contract.editor.at_ep")), 0, row); g.add(atEpField, 1, row);
        g.add(new Label(t("labor.contract.editor.ss_group")), 2, row); g.add(ssGroupCombo, 3, row); row++;
        g.add(new Label(t("labor.contract.editor.workplace")), 0, row); g.add(workplaceField, 1, row, 3, 1);

        Separator sep = new Separator();
        VBox editorBox = new VBox(10);
        if (promoteMode) {
            Label promoteHint = new Label(t("labor.contract.promote.hint"));
            promoteHint.setWrapText(true);
            promoteHint.getStyleClass().add("settings-hint");
            GridPane pg = new GridPane();
            pg.setHgap(10); pg.setVgap(8);
            pg.add(new Label(t("labor.contract.promote.effective_from")), 0, 0); pg.add(effectiveField, 1, 0);
            pg.add(new Label(t("labor.contract.promote.reason")), 0, 1); pg.add(reasonField, 1, 1, 3, 1);
            editorBox.getChildren().addAll(promoteHint, pg, new Separator());
        }
        editorBox.getChildren().addAll(g, sep, compEditor.node);
        installDialog(dialog, editorBox);

        // Catálogo de convenios → combos filtrables. Async: el diálogo se abre ya
        // y los combos se rellenan al llegar. Si falla/está vacío, quedan como
        // texto libre (el editor del combo es la fuente de verdad al guardar).
        final java.util.Map<String, com.benjagest.ui.model.ContractCatalog.Category> categoriesByName =
                new java.util.HashMap<>();
        Task<java.util.List<com.benjagest.ui.model.ContractCatalog.Agreement>> catTask = new Task<>() {
            @Override protected java.util.List<com.benjagest.ui.model.ContractCatalog.Agreement> call() throws Exception {
                return altaApiClient.listCollectiveAgreements();
            }
        };
        catTask.setOnSucceeded(cev -> {
            var agreements = catTask.getValue();
            if (agreements == null || agreements.isEmpty()) return;
            String prevAg = agreementCombo.getEditor().getText();
            String prevCat = catCombo.getEditor().getText();
            java.util.List<String> agNames = new java.util.ArrayList<>();
            java.util.List<String> catNames = new java.util.ArrayList<>();
            for (var ag : agreements) {
                if (ag.name() != null) agNames.add(ag.name());
                if (ag.categories() != null) for (var c : ag.categories()) {
                    if (c.categoryName() == null) continue;
                    if (!categoriesByName.containsKey(c.categoryName())) catNames.add(c.categoryName());
                    categoriesByName.putIfAbsent(c.categoryName(), c);
                }
            }
            installComboFilter(agreementCombo, agNames);
            installComboFilter(catCombo, catNames);
            // installComboFilter resetea el editor; restauramos el valor previo.
            agreementCombo.getEditor().setText(prevAg);
            catCombo.getEditor().setText(prevCat);
            // Al elegir una categoría del catálogo, derivar grupo profesional y
            // grupo de cotización (VIG-0). El usuario puede ajustarlos después.
            catCombo.valueProperty().addListener((o, ov, nv) -> {
                var cat = categoriesByName.get(nv);
                if (cat != null) {
                    if (cat.groupCode() != null) groupField.setText(cat.groupCode());
                    if (cat.ssContributionGroup() != null) ssGroupCombo.getSelectionModel().select(cat.ssContributionGroup());
                }
            });
        });
        start(catTask, "contract-editor-catalog");

        // VIG-3 (menor) — si el contrato YA tiene nóminas, bloquear la edición de
        // fecha de inicio / antigüedad (cambiarlas corrompe antigüedad e
        // indemnización; para cambiar condiciones está "Ascender"). Solo en
        // edición normal (no en alta ni en ascenso, que ya las bloquea).
        if (existing != null && existing.id() != null && !promoteMode) {
            Task<Boolean> payTask = new Task<>() {
                @Override protected Boolean call() throws Exception {
                    return laborApiClient.contractHasPayslips(existing.id());
                }
            };
            payTask.setOnSucceeded(pev -> {
                if (Boolean.TRUE.equals(payTask.getValue())) {
                    startField.setDisable(true);
                    seniorityField.setDisable(true);
                    Label lock = new Label(t("labor.contract.editor.dates_locked"));
                    lock.setWrapText(true);
                    lock.getStyleClass().add("settings-hint");
                    editorBox.getChildren().add(0, lock);
                }
            });
            start(payTask, "contract-has-payslips");
        }

        // VIG-3 — validar la fecha de efecto sin cerrar el diálogo (reusa el
        // patrón toast/consume del BUG-UX-2).
        if (promoteMode) {
            final javafx.scene.Node saveNode = dialog.getDialogPane().lookupButton(saveBt);
            saveNode.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
                if (parseDateSafe(effectiveField.getText()) == null) {
                    ev.consume();
                    toast(dialog.getDialogPane().getScene().getWindow(),
                            t("labor.contract.promote.no_date"));
                    highlightMissing(effectiveField);
                }
            });
        }

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            // Conceptos: salario base (campo) + complementos (editor).
            java.util.List<com.benjagest.ui.model.SalaryItemEntry> items = new java.util.ArrayList<>();
            items.add(new com.benjagest.ui.model.SalaryItemEntry(
                    null, t("labor.contract.salary.base_default"), "SALARY_BASE",
                    parseDecSafe(salaryField.getText()), true, true));
            items.addAll(compEditor.getComplements());
            com.benjagest.ui.model.ContractEntry payload = new com.benjagest.ui.model.ContractEntry(
                    existing == null ? null : existing.id(),
                    employee.id(),
                    typeField.getText().trim(),
                    blankToNullOrSelf(sepeField.getText()),
                    blankToNullOrSelf(agreementCombo.getEditor().getText()),
                    blankToNullOrSelf(catCombo.getEditor().getText()),
                    blankToNullOrSelf(groupField.getText()),
                    parseDateSafe(startField.getText()),
                    parseDateSafe(seniorityField.getText()),
                    parseDateSafe(endField.getText()),
                    parseDecSafe(hoursField.getText()),
                    parseDecSafe(salaryField.getText()),
                    parseIntSafe(bonusesField.getText()),
                    proratedField.isSelected(),
                    parseIntSafe(vacationField.getText()),
                    parseDecSafe(irpfField.getText()),
                    parseDecSafe(atEpField.getText()),
                    ssGroupCombo.getValue() == null ? 7 : ssGroupCombo.getValue(),
                    blankToNullOrSelf(workplaceField.getText()),
                    statusCombo.getValue(),
                    null,
                    existing == null ? null : existing.probationDays(),
                    existing == null ? null : existing.pdfModel(),
                    items);
            final String effFrom = promoteMode ? effectiveField.getText().trim() : null;
            final String promoteReason = promoteMode ? blankToNullOrSelf(reasonField.getText()) : null;
            Task<com.benjagest.ui.model.ContractEntry> task = new Task<>() {
                @Override protected com.benjagest.ui.model.ContractEntry call() throws Exception {
                    if (promoteMode) {
                        return laborApiClient.promoteContract(existing.id(), effFrom, promoteReason, payload);
                    }
                    return existing == null
                            ? laborApiClient.createContract(payload)
                            : laborApiClient.updateContract(existing.id(), payload);
                }
            };
            task.setOnSucceeded(ev -> showEmployeeContracts(employee));
            task.setOnFailed(ev -> showError(
                    promoteMode ? t("labor.contract.promote.fail.title") : t("labor.contract.editor.fail.title"),
                    promoteMode ? t("labor.contract.promote.fail.body") : t("labor.contract.editor.fail.body")));
            start(task, promoteMode ? "labor-contract-promote" : "labor-contract-save");
        });
    }
}
