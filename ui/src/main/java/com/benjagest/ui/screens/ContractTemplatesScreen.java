package com.benjagest.ui.screens;

import com.benjagest.ui.service.AltaApiClient;
import com.benjagest.ui.support.Router;
import java.util.function.Function;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * NOM-9 — Plantillas de contrato + Cláusulas custom (categoría "Personal" del
 * módulo Laboral, bloque UIR; CTR-3/CTR-7). Extraídas del God Object: plantillas
 * de contrato (combos del catálogo SEPE + modelo PDF) y cláusulas/anexos custom
 * (las built-in salen marcadas y no editables). Movimiento puro: mismo
 * comportamiento, mismas claves i18n. Depende de {@link AltaApiClient} y los
 * helpers de {@link ScreenBase}.
 */
public class ContractTemplatesScreen extends ScreenBase {

    private final AltaApiClient altaApiClient;

    public ContractTemplatesScreen(AltaApiClient altaApiClient,
                                   Function<String, String> tt, Router router) {
        super(tt, router);
        this.altaApiClient = altaApiClient;
    }

    private String humanizeFromKey(String key, String fallback) {
        if (key == null || key.isBlank()) return fallback == null ? "" : fallback;
        String translated = t(key);
        return key.equals(translated) ? (fallback == null ? "" : fallback) : translated;
    }

    // ===================================================================
    //  CTR-3 — Plantillas de contrato
    // ===================================================================

    public Node buildContractTemplatesTab() {
        TableView<com.benjagest.ui.model.ContractTemplate> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.templates.empty")));

        TableColumn<com.benjagest.ui.model.ContractTemplate, String> colName =
                new TableColumn<>(t("labor.templates.col.name"));
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        colName.setPrefWidth(200);
        TableColumn<com.benjagest.ui.model.ContractTemplate, String> colSepe =
                new TableColumn<>(t("labor.templates.col.sepe"));
        colSepe.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().sepeContractCode() == null ? "—" : c.getValue().sepeContractCode()));
        colSepe.setPrefWidth(80);
        TableColumn<com.benjagest.ui.model.ContractTemplate, String> colSalary =
                new TableColumn<>(t("labor.templates.col.salary"));
        colSalary.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().grossSalary() == null ? "" : c.getValue().grossSalary().toPlainString() + " €"));
        colSalary.setComparator(NUMERIC_STRING_COMPARATOR);  // VG-FULL-SCAN
        colSalary.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.ContractTemplate, String> colDescr =
                new TableColumn<>(t("labor.templates.col.descr"));
        colDescr.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().description() == null ? "" : c.getValue().description()));
        table.getColumns().addAll(java.util.List.of(colName, colSepe, colSalary, colDescr));

        Runnable refresh = () -> {
            Task<java.util.List<com.benjagest.ui.model.ContractTemplate>> task = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.ContractTemplate> call() throws Exception {
                    return altaApiClient.listContractTemplates();
                }
            };
            task.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(task.getValue())));
            task.setOnFailed(ev -> showError(t("labor.templates.fail"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "templates-load");
        };
        refresh.run();

        Button newBtn = new Button(t("labor.templates.action.new"));
        newBtn.setGraphic(icon("fas-plus"));
        newBtn.setOnAction(ev -> showContractTemplateEditor(null, refresh));

        Button editBtn = new Button(t("labor.templates.action.edit"));
        editBtn.setGraphic(icon("fas-edit"));
        editBtn.setDisable(true);
        editBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) showContractTemplateEditor(sel, refresh);
        });

        Button delBtn = new Button(t("labor.templates.action.delete"));
        delBtn.setGraphic(icon("fas-trash"));
        delBtn.setDisable(true);
        delBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    t("labor.templates.confirm.delete"), ButtonType.OK, ButtonType.CANCEL);
            confirm.showAndWait().ifPresent(bt -> {
                if (bt != ButtonType.OK) return;
                Task<Void> task = new Task<>() {
                    @Override protected Void call() throws Exception {
                        altaApiClient.deleteContractTemplate(sel.id());
                        return null;
                    }
                };
                task.setOnSucceeded(ev2 -> refresh.run());
                task.setOnFailed(ev2 -> showError(t("labor.templates.fail"),
                        task.getException() == null ? "" : task.getException().getMessage()));
                start(task, "template-delete");
            });
        });

        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            editBtn.setDisable(nv == null);
            delBtn.setDisable(nv == null);
        });

        Label hint = new Label(t("labor.templates.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        HBox actions = new HBox(8, newBtn, editBtn, delBtn);
        VBox body = new VBox(10, hint, actions, table); // acciones arriba (no debajo)
        VBox.setVgrow(table, Priority.ALWAYS);
        body.setPadding(new Insets(12));
        return body;
    }

    /**
     * Editor de plantilla — combos cargados del catálogo (igual que el wizard),
     * no TextField libres. El campo "SEPE" es ComboBox editable: puedes elegir
     * de la lista oficial o escribir un código a mano si la asesoría usa uno
     * legacy/raro. El "Modelo PDF" se humaniza ES/EN en el desplegable.
     */
    private void showContractTemplateEditor(com.benjagest.ui.model.ContractTemplate existing, Runnable onSaved) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? t("labor.templates.title.new") : t("labor.templates.title.edit"));
        ButtonType saveBt = new ButtonType(
                existing == null ? t("labor.templates.action.new") : t("labor.editor.save"),
                ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField nameF = new TextField(existing == null ? "" : existing.name());
        TextField descrF = new TextField(existing == null ? "" : existing.description());

        // Combo SEPE editable — carga el catálogo en background y permite
        // también escribir libre por si la asesoría tiene un código legacy
        // no listado. El converter pinta "100 — Indefinido…" y devuelve el
        // código limpio.
        ComboBox<com.benjagest.ui.model.ContractCatalog.SepeType> sepeCombo = new ComboBox<>();
        sepeCombo.setEditable(true);
        sepeCombo.setMaxWidth(Double.MAX_VALUE);
        sepeCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.ContractCatalog.SepeType s) {
                return s == null ? "" : s.label();
            }
            @Override public com.benjagest.ui.model.ContractCatalog.SepeType fromString(String s) {
                if (s == null || s.isBlank()) return null;
                String code = s.contains(" ") ? s.substring(0, s.indexOf(' ')).trim() : s.trim();
                return sepeCombo.getItems().stream()
                        .filter(it -> code.equals(it.code()))
                        .findFirst().orElse(null);
            }
        });
        Task<java.util.List<com.benjagest.ui.model.ContractCatalog.SepeType>> sepeTask = new Task<>() {
            @Override protected java.util.List<com.benjagest.ui.model.ContractCatalog.SepeType> call() throws Exception {
                return altaApiClient.listSepeContractTypes();
            }
        };
        sepeTask.setOnSucceeded(ev -> {
            sepeCombo.getItems().setAll(sepeTask.getValue());
            if (existing != null && existing.sepeContractCode() != null) {
                sepeCombo.getItems().stream()
                        .filter(it -> existing.sepeContractCode().equals(it.code()))
                        .findFirst().ifPresent(sepeCombo.getSelectionModel()::select);
            }
        });
        start(sepeTask, "tpl-sepe-load");

        TextField hoursF = new TextField(existing == null || existing.weeklyHours() == null
                ? "40" : existing.weeklyHours().toPlainString());
        TextField salaryF = new TextField(existing == null || existing.grossSalary() == null
                ? "" : existing.grossSalary().toPlainString());
        TextField bonusF = new TextField(existing == null || existing.annualBonuses() == null
                ? "2" : existing.annualBonuses().toString());
        TextField vacF = new TextField(existing == null || existing.vacationDays() == null
                ? "30" : existing.vacationDays().toString());
        TextField probF = new TextField(existing == null || existing.probationDays() == null
                ? "" : existing.probationDays().toString());

        // Combo modelo PDF humanizado (no códigos crudos)
        ComboBox<String> pdfM = new ComboBox<>();
        pdfM.getItems().addAll("UNIFIED_2022", "BY_CODE");
        pdfM.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String c) {
                return c == null ? "" : humanizeFromKey("labor.contract.wizard.step4.pdf_model." + c, c);
            }
            @Override public String fromString(String s) { return null; }
        });
        pdfM.setValue(existing == null ? "UNIFIED_2022" :
                (existing.pdfModel() == null ? "UNIFIED_2022" : existing.pdfModel()));

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(10));
        int row = 0;
        g.add(new Label(t("labor.templates.field.name")), 0, row); g.add(nameF, 1, row, 3, 1); row++;
        g.add(new Label(t("labor.templates.field.descr")), 0, row); g.add(descrF, 1, row, 3, 1); row++;
        g.add(new Label(t("labor.templates.field.sepe")), 0, row); g.add(sepeCombo, 1, row, 3, 1); row++;
        g.add(new Label(t("labor.templates.field.pdf")), 0, row); g.add(pdfM, 1, row, 3, 1); row++;
        g.add(new Label(t("labor.templates.field.hours")), 0, row); g.add(hoursF, 1, row);
        g.add(new Label(t("labor.templates.field.salary")), 2, row); g.add(salaryF, 3, row); row++;
        g.add(new Label(t("labor.templates.field.bonus")), 0, row); g.add(bonusF, 1, row);
        g.add(new Label(t("labor.templates.field.vac")), 2, row); g.add(vacF, 3, row); row++;
        g.add(new Label(t("labor.templates.field.probation")), 0, row); g.add(probF, 1, row);

        installDialog(dialog, g);
        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            if (nameF.getText() == null || nameF.getText().isBlank()) {
                showError(t("labor.templates.fail"), t("labor.templates.fail.name"));
                return;
            }
            // Si el SEPE viene del combo, sacamos su .code(); si el usuario
            // escribió a mano, lo tomamos del editor del combo.
            String sepeCode = null;
            var selSepe = sepeCombo.getSelectionModel().getSelectedItem();
            if (selSepe != null) sepeCode = selSepe.code();
            else if (sepeCombo.getEditor().getText() != null && !sepeCombo.getEditor().getText().isBlank()) {
                String txt = sepeCombo.getEditor().getText().trim();
                sepeCode = txt.contains(" ") ? txt.substring(0, txt.indexOf(' ')) : txt;
            }
            com.benjagest.ui.model.ContractTemplate tpl = new com.benjagest.ui.model.ContractTemplate(
                    existing == null ? null : existing.id(),
                    nameF.getText().trim(),
                    blankToNullOrSelf(descrF.getText()),
                    sepeCode,
                    null, null, null, null,
                    parseDecSafe(hoursF.getText()),
                    parseDecSafe(salaryF.getText()),
                    parseIntSafe(bonusF.getText()),
                    parseIntSafe(vacF.getText()),
                    null,
                    parseIntSafe(probF.getText()),
                    null, null,
                    pdfM.getValue(),
                    false, true);
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    if (existing == null) altaApiClient.createContractTemplate(tpl);
                    // Update no implementado en altaApiClient v1 — borrar+crear
                    // sería violento (cambia id). Backend tiene endpoint
                    // PUT pero el wrapper UI no lo expone. Sub-slice si hace
                    // falta; por ahora editar = crear nuevo.
                    else { altaApiClient.deleteContractTemplate(existing.id()); altaApiClient.createContractTemplate(tpl); }
                    return null;
                }
            };
            task.setOnSucceeded(ev -> onSaved.run());
            task.setOnFailed(ev -> showError(t("labor.templates.fail"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "template-save");
        });
    }

    // ===================================================================
    //  CTR-7 — Cláusulas custom
    // ===================================================================

    /**
     * Listado de cláusulas/anexos custom de la asesoría. Las built-in
     * salen pero marcadas y no son editables. Cuando llamamos al
     * endpoint /clauses, el backend ya filtra built-in + custom del
     * tenant; aquí solo distinguimos visualmente.
     */
    public Node buildCustomClausesTab() {
        TableView<com.benjagest.ui.model.ContractCatalog.ClauseTemplate> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.clauses.empty")));

        TableColumn<com.benjagest.ui.model.ContractCatalog.ClauseTemplate, String> colTitle =
                new TableColumn<>(t("labor.clauses.col.title"));
        colTitle.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().title()));
        colTitle.setPrefWidth(280);
        TableColumn<com.benjagest.ui.model.ContractCatalog.ClauseTemplate, String> colCat =
                new TableColumn<>(t("labor.clauses.col.category"));
        colCat.setCellValueFactory(c -> new SimpleStringProperty(
                humanizeFromKey("labor.clauses.cat." + c.getValue().category(), c.getValue().category())));
        colCat.setPrefWidth(160);
        TableColumn<com.benjagest.ui.model.ContractCatalog.ClauseTemplate, String> colOrigin =
                new TableColumn<>(t("labor.clauses.col.origin"));
        colOrigin.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().isBuiltIn() ? t("labor.clauses.origin.builtin")
                                          : t("labor.clauses.origin.custom")));
        colOrigin.setPrefWidth(110);
        table.getColumns().addAll(java.util.List.of(colTitle, colCat, colOrigin));

        Runnable refresh = () -> {
            Task<java.util.List<com.benjagest.ui.model.ContractCatalog.ClauseTemplate>> task = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.ContractCatalog.ClauseTemplate> call() throws Exception {
                    return altaApiClient.listClauseTemplates();
                }
            };
            task.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(task.getValue())));
            task.setOnFailed(ev -> showError(t("labor.clauses.fail"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "clauses-load");
        };
        refresh.run();

        Button newBtn = new Button(t("labor.clauses.action.new"));
        newBtn.setGraphic(icon("fas-plus"));
        newBtn.setOnAction(ev -> showCustomClauseEditor(null, refresh));

        Button editBtn = new Button(t("labor.clauses.action.edit"));
        editBtn.setGraphic(icon("fas-edit"));
        editBtn.setDisable(true);
        editBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            if (sel.isBuiltIn()) {
                showError(t("labor.clauses.fail"), t("labor.clauses.fail.builtin"));
                return;
            }
            showCustomClauseEditor(sel, refresh);
        });

        Button viewBtn = new Button(t("labor.clauses.action.view"));
        viewBtn.setGraphic(icon("fas-eye"));
        viewBtn.setDisable(true);
        viewBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setTitle(sel.title());
            info.setHeaderText(sel.title()
                    + (sel.legalBasis() == null ? "" : "\n" + sel.legalBasis()));
            TextArea ta = new TextArea(sel.body());
            ta.setWrapText(true);
            ta.setEditable(false);
            ta.setPrefRowCount(20); ta.setPrefColumnCount(80);
            info.getDialogPane().setContent(ta);
            info.setResizable(true);
            info.showAndWait();
        });

        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            editBtn.setDisable(nv == null);
            viewBtn.setDisable(nv == null);
        });

        Label hint = new Label(t("labor.clauses.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        HBox actions = new HBox(8, newBtn, editBtn, viewBtn);
        VBox body = new VBox(10, hint, actions, table); // acciones arriba (no debajo)
        VBox.setVgrow(table, Priority.ALWAYS);
        body.setPadding(new Insets(12));
        return body;
    }

    /** Editor de cláusula custom (POST/PUT /api/contracts/catalog/clauses). */
    private void showCustomClauseEditor(com.benjagest.ui.model.ContractCatalog.ClauseTemplate existing,
                                         Runnable onSaved) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? t("labor.clauses.title.new") : t("labor.clauses.title.edit"));
        ButtonType saveBt = new ButtonType(t("labor.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField codeF = new TextField(existing == null ? "" : existing.code());
        TextField titleF = new TextField(existing == null ? "" : existing.title());
        ComboBox<String> catC = new ComboBox<>();
        catC.getItems().addAll("CONFIDENTIALITY", "NON_COMPETE", "EXCLUSIVITY",
                "RETENTION_TRAINING", "GEOLOCATION_GDPR", "TELEWORK",
                "INTELLECTUAL_PROPERTY", "OBJECTIVES_BONUS", "COMPANY_CAR",
                "COMPANY_PHONE", "EXPENSE_ALLOWANCE", "WORKING_HOURS_FLEX",
                "CUSTOM", "OTHER");
        catC.setValue(existing == null ? "CUSTOM" : existing.category());
        TextField legalF = new TextField(existing == null ? "" : existing.legalBasis());
        TextArea bodyF = new TextArea(existing == null ? "" : existing.body());
        bodyF.setWrapText(true);
        bodyF.setPrefRowCount(15);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(10));
        int row = 0;
        g.add(new Label(t("labor.clauses.field.code")), 0, row); g.add(codeF, 1, row);
        g.add(new Label(t("labor.clauses.field.category")), 2, row); g.add(catC, 3, row); row++;
        g.add(new Label(t("labor.clauses.field.title")), 0, row); g.add(titleF, 1, row, 3, 1); row++;
        g.add(new Label(t("labor.clauses.field.legal")), 0, row); g.add(legalF, 1, row, 3, 1); row++;
        g.add(new Label(t("labor.clauses.field.body")), 0, row); g.add(bodyF, 1, row, 3, 1);
        GridPane.setVgrow(bodyF, Priority.ALWAYS);

        installDialog(dialog, g);
        dialog.setResizable(true);
        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    // Backend expone POST + PUT en ContractCatalogController.
                    // El wrapper de UI no tiene helpers todavía — usamos
                    // HttpClient inline aquí para no inflar AltaApiClient
                    // antes de tiempo.
                    String json = "{\"code\":\"" + js(codeF.getText())
                            + "\",\"title\":\"" + js(titleF.getText())
                            + "\",\"category\":\"" + js(catC.getValue())
                            + "\",\"legalBasis\":\"" + js(legalF.getText())
                            + "\",\"body\":\"" + js(bodyF.getText()) + "\"}";
                    altaApiClient.upsertClauseTemplate(existing == null ? null : existing.id(), json);
                    return null;
                }
            };
            task.setOnSucceeded(ev -> onSaved.run());
            task.setOnFailed(ev -> showError(t("labor.clauses.fail"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "clause-save");
        });
    }

    private static String js(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}
