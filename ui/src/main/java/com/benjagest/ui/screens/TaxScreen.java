package com.benjagest.ui.screens;

import com.benjagest.ui.model.*;
import com.benjagest.ui.service.*;
import com.benjagest.ui.support.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
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

/** Modulos AEAT (Fiscal). Reutilizable en modo standalone (mountStandalone) y
 *  embebido en la ficha de cliente (mountInto). Extraido del God Object en UIR-9. */
public class TaxScreen extends ScreenBase {

    private final AltaApiClient altaApiClient;
    private int year = LocalDate.now().getYear();
    private Runnable onReload = () -> {};
    private TableView<com.benjagest.ui.model.TaxFilingEntry> taxFilingsTable;
    private TableView<com.benjagest.ui.model.TaxDueDateEntry> taxCalendarTable;

    public TaxScreen(AltaApiClient altaApiClient, Function<String, String> tt, Router router) {
        super(tt, router);
        this.altaApiClient = altaApiClient;
    }

    /** Monta el modulo en el centro del shell (modo propio). */
    public void mountStandalone() {
        onReload = this::mountStandalone;
        load(b -> setCenterAnimated(scroll(taxView(b))),
             () -> setCenterAnimated(scroll(errorPanel(t("tax.load_failed")))));
    }

    /** Monta el modulo dentro de un holder (embebido en una ficha). */
    public Node mountInto(VBox holder) {
        onReload = () -> reloadInto(holder);
        reloadInto(holder);
        return holder;
    }

    private void reloadInto(VBox holder) {
        Label loading = new Label(t("panorama.loading"));
        loading.getStyleClass().add("settings-hint");
        loading.setPadding(new Insets(12));
        holder.getChildren().setAll(loading);
        load(b -> holder.getChildren().setAll(scroll(taxView(b))),
             () -> holder.getChildren().setAll(errorPanel(t("tax.load_failed"))));
    }

    private void load(Consumer<TaxBundle> ok, Runnable fail) {
        Task<TaxBundle> task = new Task<>() {
            @Override protected TaxBundle call() throws Exception {
                return new TaxBundle(
                        altaApiClient.listTaxModels(),
                        altaApiClient.listFilings(year, null, null),
                        altaApiClient.calendar(year));
            }
        };
        task.setOnSucceeded(ev -> ok.accept(task.getValue()));
        task.setOnFailed(ev -> fail.run());
        start(task, "tax-models-load");
    }

    private record TaxBundle(
            java.util.List<com.benjagest.ui.model.TaxModelEntry> catalog,
            java.util.List<com.benjagest.ui.model.TaxFilingEntry> filings,
            java.util.List<com.benjagest.ui.model.TaxDueDateEntry> calendar
    ) {}

    private VBox taxView(TaxBundle bundle) {
        VBox content = content();
        Label title = new Label(t("tax.title"));
        title.getStyleClass().add("module-detail-title");
        Label subtitle = new Label(t("tax.subtitle"));
        subtitle.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, subtitle);
        StackPane moduleIcon = iconBubble("fas-percentage", "module-title-icon");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Selector de año
        ComboBox<Integer> yearCombo = new ComboBox<>();
        int currentY = LocalDate.now().getYear();
        for (int y = currentY + 1; y >= currentY - 5; y--) yearCombo.getItems().add(y);
        yearCombo.getSelectionModel().select(Integer.valueOf(year));
        yearCombo.setOnAction(ev -> {
            year = yearCombo.getValue();
            onReload.run();
        });

        Button newBtn = new Button(t("tax.action.new"));
        newBtn.setGraphic(icon("fas-plus"));
        newBtn.setOnAction(ev -> showFilingEditor(null, bundle.catalog()));

        HBox header = new HBox(16, titleBox, moduleIcon, spacer,
                new Label(t("tax.year") + ":"), yearCombo, newBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("settings-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab filingsTab = new Tab(t("tax.tab.filings"), buildFilingsTab(bundle));
        filingsTab.setGraphic(icon("fas-file-alt"));
        Tab calendarTab = new Tab(t("tax.tab.calendar"), buildCalendarTab(bundle));
        calendarTab.setGraphic(icon("fas-calendar-alt"));
        tabs.getTabs().addAll(filingsTab, calendarTab);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        content.getChildren().addAll(header, tabs);
        return content;
    }

    private Node buildFilingsTab(TaxBundle bundle) {
        taxFilingsTable = new TableView<>();
        taxFilingsTable.getStyleClass().add("data-table");
        taxFilingsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        taxFilingsTable.setPlaceholder(new Label(t("tax.filings.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.TaxFilingEntry, String> colModel =
                new TableColumn<>(t("tax.filings.col.model"));
        colModel.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().taxModelCode()));
        colModel.setPrefWidth(70);
        TableColumn<com.benjagest.ui.model.TaxFilingEntry, String> colPeriod =
                new TableColumn<>(t("tax.filings.col.period"));
        colPeriod.setCellValueFactory(c -> new SimpleStringProperty(formatPeriod(c.getValue())));
        colPeriod.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.TaxFilingEntry, String> colStatus =
                new TableColumn<>(t("tax.filings.col.status"));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(t("tax.status." + c.getValue().status())));
        colStatus.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.TaxFilingEntry, String> colAmount =
                new TableColumn<>(t("tax.filings.col.amount"));
        colAmount.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().totalAmount() == null ? "" : c.getValue().totalAmount().toPlainString() + " €"));
        colAmount.setPrefWidth(110);
        colAmount.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TaxFilingEntry, String> colDeadline =
                new TableColumn<>(t("tax.filings.col.deadline"));
        colDeadline.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().deadlineAt()));
        colDeadline.setPrefWidth(110);
        colDeadline.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TaxFilingEntry, String> colCsv =
                new TableColumn<>(t("tax.filings.col.csv"));
        colCsv.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().csvAeat()));
        taxFilingsTable.getColumns().addAll(java.util.List.of(colModel, colPeriod, colStatus, colAmount, colDeadline, colCsv));
        taxFilingsTable.setItems(FXCollections.observableArrayList(bundle.filings()));
        taxFilingsTable.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                var sel = taxFilingsTable.getSelectionModel().getSelectedItem();
                if (sel != null) showFilingEditor(sel, bundle.catalog());
            }
        });

        Button editBtn = new Button(t("tax.filings.action.edit"));
        editBtn.setGraphic(icon("fas-edit"));
        editBtn.setDisable(true);
        editBtn.setOnAction(ev -> {
            var sel = taxFilingsTable.getSelectionModel().getSelectedItem();
            if (sel != null) showFilingEditor(sel, bundle.catalog());
        });

        Button deleteBtn = new Button(t("tax.filings.action.delete"));
        deleteBtn.setGraphic(icon("fas-trash"));
        deleteBtn.setDisable(true);
        deleteBtn.setOnAction(ev -> {
            var sel = taxFilingsTable.getSelectionModel().getSelectedItem();
            if (sel != null) deleteFiling(sel);
        });

        taxFilingsTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            editBtn.setDisable(nv == null);
            deleteBtn.setDisable(nv == null
                    || !("DRAFT".equals(nv.status()) || "CANCELLED".equals(nv.status())));
        });

        HBox actions = new HBox(8, editBtn, deleteBtn);
        actions.getStyleClass().add("settings-actions");

        VBox body = new VBox(12, taxFilingsTable);
        VBox.setVgrow(taxFilingsTable, Priority.ALWAYS);
        return screenScroll(new VBox(8, body, actions));
    }

    private Node buildCalendarTab(TaxBundle bundle) {
        Label hint = new Label(t("tax.calendar.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        taxCalendarTable = new TableView<>();
        taxCalendarTable.getStyleClass().add("data-table");
        taxCalendarTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        taxCalendarTable.setPlaceholder(new Label(t("tax.calendar.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.TaxDueDateEntry, String> colDeadline =
                new TableColumn<>(t("tax.calendar.col.deadline"));
        colDeadline.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().deadlineAt()));
        colDeadline.setPrefWidth(110);
        colDeadline.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TaxDueDateEntry, String> colModel =
                new TableColumn<>(t("tax.calendar.col.model"));
        colModel.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().taxModelCode()));
        colModel.setPrefWidth(70);
        TableColumn<com.benjagest.ui.model.TaxDueDateEntry, String> colName =
                new TableColumn<>(t("tax.calendar.col.name"));
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().taxModelName()));
        TableColumn<com.benjagest.ui.model.TaxDueDateEntry, String> colPeriod =
                new TableColumn<>(t("tax.calendar.col.period"));
        colPeriod.setCellValueFactory(c -> new SimpleStringProperty(formatPeriod(
                c.getValue().periodYear(), c.getValue().periodQuarter(), c.getValue().periodMonth())));
        colPeriod.setPrefWidth(120);
        TableColumn<com.benjagest.ui.model.TaxDueDateEntry, String> colState =
                new TableColumn<>(t("tax.calendar.col.state"));
        colState.setCellValueFactory(c -> new SimpleStringProperty(calendarFilingState(c.getValue(), bundle.filings())));
        colState.setPrefWidth(120);
        taxCalendarTable.getColumns().addAll(java.util.List.of(colDeadline, colModel, colName, colPeriod, colState));

        // Ordenar por fecha ascendente para que arriba salgan los más próximos
        var sorted = new java.util.ArrayList<>(bundle.calendar());
        sorted.sort(java.util.Comparator.comparing(com.benjagest.ui.model.TaxDueDateEntry::deadlineAt));
        taxCalendarTable.setItems(FXCollections.observableArrayList(sorted));

        VBox.setVgrow(taxCalendarTable, Priority.ALWAYS);
        return screenScroll(new VBox(8, hint, taxCalendarTable));
    }

    private String calendarFilingState(com.benjagest.ui.model.TaxDueDateEntry due,
                                        java.util.List<com.benjagest.ui.model.TaxFilingEntry> filings) {
        for (var f : filings) {
            if (!due.taxModelCode().equals(f.taxModelCode())) continue;
            if (f.periodYear() != due.periodYear()) continue;
            if (!java.util.Objects.equals(f.periodQuarter(), due.periodQuarter())) continue;
            if (!java.util.Objects.equals(f.periodMonth(), due.periodMonth())) continue;
            return t("tax.status." + f.status());
        }
        return t("tax.calendar.state.pending");
    }

    private String formatPeriod(com.benjagest.ui.model.TaxFilingEntry f) {
        return formatPeriod(f.periodYear(), f.periodQuarter(), f.periodMonth());
    }

    private String formatPeriod(int year, Integer quarter, Integer month) {
        if (quarter != null) return year + " T" + quarter;
        if (month != null) return year + " M" + String.format("%02d", month);
        return String.valueOf(year);
    }

    private void deleteFiling(com.benjagest.ui.model.TaxFilingEntry entry) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("tax.filings.delete.body") + " " + entry.taxModelCode() + " " + formatPeriod(entry),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("tax.filings.delete.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    altaApiClient.deleteFiling(entry.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> onReload.run());
            task.setOnFailed(ev -> showError(t("tax.filings.delete.fail.title"),
                    t("tax.filings.delete.fail.body")));
            start(task, "tax-filing-delete");
        });
    }

    /**
     * Editor de declaracion. Si el modelo es 303 o 130 se abren los
     * editores especificos (con sus casillas). Para el resto, un editor
     * generico con JSON crudo en TextArea.
     */
    private void showFilingEditor(com.benjagest.ui.model.TaxFilingEntry existing,
                                   java.util.List<com.benjagest.ui.model.TaxModelEntry> catalog) {
        String modelCode = existing == null ? null : existing.taxModelCode();
        if (existing == null) {
            // Para nueva declaracion, primero el usuario elige modelo y periodo.
            showNewFilingDialog(catalog);
            return;
        }
        if ("303".equals(modelCode)) {
            show303Editor(existing);
        } else if ("130".equals(modelCode)) {
            show130Editor(existing);
        } else if ("347".equals(modelCode)) {
            show347Editor(existing);
        } else if ("390".equals(modelCode)) {
            show390Editor(existing);
        } else if ("190".equals(modelCode)) {
            show190Editor(existing);
        } else {
            showGenericFilingEditor(existing, catalog);
        }
    }

    private void showNewFilingDialog(java.util.List<com.benjagest.ui.model.TaxModelEntry> catalog) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("tax.new.title"));
        ButtonType nextBt = new ButtonType(t("tax.new.next"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(nextBt, ButtonType.CANCEL);

        ComboBox<com.benjagest.ui.model.TaxModelEntry> modelCombo = new ComboBox<>();
        modelCombo.getItems().addAll(catalog);
        modelCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.TaxModelEntry m) {
                return m == null ? "" : (m.code() + " · " + m.name());
            }
            @Override public com.benjagest.ui.model.TaxModelEntry fromString(String s) { return null; }
        });
        if (!catalog.isEmpty()) modelCombo.getSelectionModel().selectFirst();

        ComboBox<Integer> yearCombo = new ComboBox<>();
        int currentY = LocalDate.now().getYear();
        for (int y = currentY + 1; y >= currentY - 5; y--) yearCombo.getItems().add(y);
        yearCombo.getSelectionModel().select(Integer.valueOf(year));

        ComboBox<String> periodCombo = new ComboBox<>();
        periodCombo.getItems().addAll("T1", "T2", "T3", "T4", "M01", "M02", "M03", "M04", "M05", "M06",
                "M07", "M08", "M09", "M10", "M11", "M12", "ANUAL");
        periodCombo.getSelectionModel().select("T1");

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.add(new Label(t("tax.new.model")), 0, 0); grid.add(modelCombo, 1, 0);
        grid.add(new Label(t("tax.new.year")), 0, 1); grid.add(yearCombo, 1, 1);
        grid.add(new Label(t("tax.new.period")), 0, 2); grid.add(periodCombo, 1, 2);
        installDialog(dialog, grid);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != nextBt) return;
            var model = modelCombo.getValue();
            if (model == null) return;
            Integer quarter = null, month = null;
            String pv = periodCombo.getValue();
            if (pv != null && pv.startsWith("T")) quarter = Integer.parseInt(pv.substring(1));
            else if (pv != null && pv.startsWith("M")) month = Integer.parseInt(pv.substring(1));
            // Crear stub vacio y abrir editor especifico
            com.benjagest.ui.model.TaxFilingEntry stub = new com.benjagest.ui.model.TaxFilingEntry(
                    null, model.code(), yearCombo.getValue(), quarter, month,
                    "DRAFT", null, null, null, null, null, "{}");
            Task<com.benjagest.ui.model.TaxFilingEntry> task = new Task<>() {
                @Override
                protected com.benjagest.ui.model.TaxFilingEntry call() throws Exception {
                    return altaApiClient.createFiling(model.code(), yearCombo.getValue(), stub.periodQuarter(),
                            stub.periodMonth(), "DRAFT", "{}", null, null, null);
                }
            };
            task.setOnSucceeded(ev -> {
                var created = task.getValue();
                showFilingEditor(created, catalog);
            });
            task.setOnFailed(ev -> showError(t("tax.new.fail.title"), t("tax.new.fail.body")));
            start(task, "tax-filing-create");
        });
    }

    /** Editor genérico: JSON crudo en TextArea + estado + total + CSV + notas. */
    private void showGenericFilingEditor(com.benjagest.ui.model.TaxFilingEntry existing,
                                          java.util.List<com.benjagest.ui.model.TaxModelEntry> catalog) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("tax.editor.generic.title") + " — " + existing.taxModelCode() + " " + formatPeriod(existing));
        ButtonType saveBt = new ButtonType(t("tax.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        ComboBox<String> statusCombo = new ComboBox<>();
        statusCombo.getItems().addAll("DRAFT", "READY", "PRESENTED", "PAID", "REJECTED", "CANCELLED");
        localizeEnumCombo(statusCombo, "tax_status");
        statusCombo.getSelectionModel().select(existing.status());
        TextField amountField = new TextField(existing.totalAmount() == null
                ? "" : existing.totalAmount().toPlainString());
        TextField csvField = new TextField(existing.csvAeat());
        TextArea dataArea = new TextArea(existing.dataJson() == null || existing.dataJson().isBlank()
                ? "{}" : existing.dataJson());
        dataArea.setPrefRowCount(6);
        TextArea notesArea = new TextArea(existing.notes());
        notesArea.setPrefRowCount(2);

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.add(new Label(t("tax.editor.status")), 0, 0); grid.add(statusCombo, 1, 0);
        grid.add(new Label(t("tax.editor.total")), 0, 1); grid.add(amountField, 1, 1);
        grid.add(new Label(t("tax.editor.csv")), 0, 2); grid.add(csvField, 1, 2);
        grid.add(new Label(t("tax.editor.data")), 0, 3); grid.add(dataArea, 1, 3);
        grid.add(new Label(t("tax.editor.notes")), 0, 4); grid.add(notesArea, 1, 4);
        installDialog(dialog, grid);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            saveFiling(existing, statusCombo.getValue(), dataArea.getText(),
                    parseDec(amountField.getText()), csvField.getText(), notesArea.getText(), catalog);
        });
    }

    /** Editor 303 — IVA autoliquidación trimestral. Casillas básicas. */
    private void show303Editor(com.benjagest.ui.model.TaxFilingEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Modelo 303 — " + formatPeriod(existing));
        ButtonType saveBt = new ButtonType(t("tax.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        java.util.Map<String, String> parsed = parseDataMap(existing.dataJson());

        // IVA repercutido (devengado)
        TextField b21 = new TextField(parsed.getOrDefault("base_21", ""));
        TextField c21 = new TextField(parsed.getOrDefault("cuota_21", ""));
        TextField b10 = new TextField(parsed.getOrDefault("base_10", ""));
        TextField c10 = new TextField(parsed.getOrDefault("cuota_10", ""));
        TextField b4 = new TextField(parsed.getOrDefault("base_4", ""));
        TextField c4 = new TextField(parsed.getOrDefault("cuota_4", ""));
        // IVA soportado (deducible)
        TextField bs = new TextField(parsed.getOrDefault("base_soportado", ""));
        TextField cs = new TextField(parsed.getOrDefault("cuota_soportada", ""));
        // IVA-COMP: cuotas a compensar de periodos anteriores (casilla 110).
        TextField comp110 = new TextField(parsed.getOrDefault("compensar_anteriores", ""));

        ComboBox<String> statusCombo = new ComboBox<>();
        statusCombo.getItems().addAll("DRAFT", "READY", "PRESENTED", "PAID", "REJECTED");
        applyFilingStatusLabels(statusCombo);
        statusCombo.getSelectionModel().select(existing.status());
        TextField csvField = new TextField(existing.csvAeat());

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(6); grid.setPadding(new Insets(12));
        grid.add(label("IVA repercutido (devengado)", "settings-section-title"), 0, 0, 4, 1);
        grid.add(new Label("Base 21 %"), 0, 1); grid.add(b21, 1, 1);
        grid.add(new Label("Cuota 21 %"), 2, 1); grid.add(c21, 3, 1);
        grid.add(new Label("Base 10 %"), 0, 2); grid.add(b10, 1, 2);
        grid.add(new Label("Cuota 10 %"), 2, 2); grid.add(c10, 3, 2);
        grid.add(new Label("Base 4 %"), 0, 3); grid.add(b4, 1, 3);
        grid.add(new Label("Cuota 4 %"), 2, 3); grid.add(c4, 3, 3);

        grid.add(new Separator(), 0, 4, 4, 1);
        grid.add(label("IVA soportado (deducible)", "settings-section-title"), 0, 5, 4, 1);
        grid.add(new Label("Base soportada"), 0, 6); grid.add(bs, 1, 6);
        grid.add(new Label("Cuota soportada"), 2, 6); grid.add(cs, 3, 6);

        grid.add(new Separator(), 0, 7, 4, 1);
        // IVA-COMP: casilla 110 (arrastrada) + botón para el saldo inicial.
        grid.add(new Label("Cuotas a compensar anteriores (110)"), 0, 8); grid.add(comp110, 1, 8);
        Button baselineBtn = new Button("Saldo inicial…");
        baselineBtn.setGraphic(icon("fas-sliders-h"));
        baselineBtn.setOnAction(e -> showVatCompensationBaselineDialog(existing.periodYear()));
        grid.add(baselineBtn, 2, 8, 2, 1);

        grid.add(new Separator(), 0, 9, 4, 1);
        Label resultLabel = new Label();
        resultLabel.getStyleClass().add("settings-section-title");
        grid.add(resultLabel, 0, 10, 4, 1);

        Runnable recompute = () -> {
            java.math.BigDecimal repercutido = sum(c21.getText(), c10.getText(), c4.getText());
            java.math.BigDecimal soportado = parseDec(cs.getText());
            if (soportado == null) soportado = java.math.BigDecimal.ZERO;
            java.math.BigDecimal regimen = repercutido.subtract(soportado);
            // IVA-COMP: aplicar las cuotas a compensar de periodos anteriores.
            java.math.BigDecimal previa = parseDec(comp110.getText());
            if (previa == null || previa.signum() < 0) previa = java.math.BigDecimal.ZERO;
            java.math.BigDecimal aplicada = regimen.signum() > 0 ? previa.min(regimen) : java.math.BigDecimal.ZERO;
            java.math.BigDecimal result = regimen.subtract(aplicada).setScale(2, java.math.RoundingMode.HALF_UP);
            java.math.BigDecimal remanente = previa.subtract(aplicada);
            if (regimen.signum() < 0) remanente = previa.add(regimen.abs());
            resultLabel.setText(
                    "Resultado régimen (46): " + regimen.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                    + " €   ·   A compensar aplicado (78): " + aplicada.toPlainString() + " €\n"
                    + "Resultado (casilla 71): " + result.toPlainString() + " €"
                    + "   ·   Remanente futuro (87): " + remanente.toPlainString() + " €");
        };
        for (TextField f : new TextField[]{c21, c10, c4, cs, comp110}) {
            f.textProperty().addListener((o, ov, nv) -> recompute.run());
        }
        recompute.run();

        // MOD-PREFILL: rellenar las casillas desde las facturas VALIDADAS del
        // trimestre. Las cuotas repercutidas se derivan (base × tipo).
        java.util.function.Consumer<com.benjagest.ui.model.Aeat303Data> apply303 = d -> {
            b4.setText(d.base4); b10.setText(d.base10); b21.setText(d.base21);
            c4.setText(mulStr(d.base4, "0.04"));
            c10.setText(mulStr(d.base10, "0.10"));
            c21.setText(mulStr(d.base21, "0.21"));
            bs.setText(d.baseSoportada); cs.setText(d.cuotaSoportada);
            comp110.setText(d.compensacionPrevia);
            recompute.run();
        };
        Runnable recalc303 = () -> {
            Integer q = existing.periodQuarter();
            if (q == null) return;
            Task<com.benjagest.ui.model.Aeat303Data> tk = new Task<>() {
                @Override protected com.benjagest.ui.model.Aeat303Data call() throws Exception {
                    return altaApiClient.preview303(existing.periodYear(), q);
                }
            };
            tk.setOnSucceeded(ev -> apply303.accept(tk.getValue()));
            tk.setOnFailed(ev -> showError(t("tax.editor.fail.title"), t("tax.editor.fail.body")));
            start(tk, "aeat303-recalc");
        };
        Button recalc303Btn = new Button(t("aeat347.recalc"));
        recalc303Btn.setGraphic(icon("fas-sync"));
        recalc303Btn.setOnAction(e -> recalc303.run());
        // Automático al abrir si el filing está vacío (no pisa ajustes guardados).
        if (parsed.isEmpty()) recalc303.run();

        grid.add(recalc303Btn, 0, 11, 4, 1);
        grid.add(new Label(t("tax.editor.status")), 0, 12); grid.add(statusCombo, 1, 12);
        grid.add(new Label(t("tax.editor.csv")), 2, 12); grid.add(csvField, 3, 12);

        installDialog(dialog, grid);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            java.util.Map<String, String> data = new java.util.LinkedHashMap<>();
            data.put("base_21", b21.getText().trim());
            data.put("cuota_21", c21.getText().trim());
            data.put("base_10", b10.getText().trim());
            data.put("cuota_10", c10.getText().trim());
            data.put("base_4", b4.getText().trim());
            data.put("cuota_4", c4.getText().trim());
            data.put("base_soportado", bs.getText().trim());
            data.put("cuota_soportada", cs.getText().trim());
            data.put("compensar_anteriores", comp110.getText().trim());
            // IVA-COMP: total = resultado con la compensacion aplicada (casilla 71).
            java.math.BigDecimal regimen = sum(c21.getText(), c10.getText(), c4.getText())
                    .subtract(parseDec(cs.getText()) == null ? java.math.BigDecimal.ZERO : parseDec(cs.getText()));
            java.math.BigDecimal previa = parseDec(comp110.getText());
            if (previa == null || previa.signum() < 0) previa = java.math.BigDecimal.ZERO;
            java.math.BigDecimal aplicada = regimen.signum() > 0 ? previa.min(regimen) : java.math.BigDecimal.ZERO;
            java.math.BigDecimal total = regimen.subtract(aplicada).setScale(2, java.math.RoundingMode.HALF_UP);
            saveFiling(existing, statusCombo.getValue(), encodeDataMap(data), total,
                    csvField.getText(), existing.notes(), java.util.List.of());
        });
    }

    /**
     * IVA-COMP — diálogo del saldo INICIAL de cuotas de IVA a compensar
     * (casilla 110 de partida). La asesoría lo teclea una vez al migrar;
     * de ahí en adelante el 303 lo arrastra solo. Al guardar, refresca el
     * editor para que el prefill recoja el saldo nuevo.
     */
    private void showVatCompensationBaselineDialog(int defaultYear) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Saldo inicial de IVA a compensar");
        ButtonType saveBt = new ButtonType(t("tax.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField balance = new TextField();
        TextField yearF = new TextField(String.valueOf(defaultYear));
        ComboBox<String> quarter = new ComboBox<>();
        quarter.getItems().addAll("1", "2", "3", "4");
        quarter.getSelectionModel().selectFirst();

        Task<String[]> load = new Task<>() {
            @Override protected String[] call() throws Exception {
                return altaApiClient.getVatCompensationBaseline();
            }
        };
        load.setOnSucceeded(ev -> {
            String[] b = load.getValue();
            if (b != null) {
                balance.setText(b[0]); yearF.setText(b[1]);
                quarter.getSelectionModel().select(b[2] == null ? "1" : b[2].replace(".0", ""));
            }
        });
        start(load, "vat-baseline-load");

        Label hint = new Label("El IVA a compensar pendiente de trimestres anteriores a usar "
                + "BENJAGEST, y desde qué trimestre empieza el arrastre. Se teclea una vez.");
        hint.setWrapText(true); hint.getStyleClass().add("settings-hint");
        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12));
        g.add(hint, 0, 0, 2, 1);
        g.add(new Label("Saldo a compensar (€)"), 0, 1); g.add(balance, 1, 1);
        g.add(new Label("Desde el año"), 0, 2); g.add(yearF, 1, 2);
        g.add(new Label("Desde el trimestre"), 0, 3); g.add(quarter, 1, 3);
        installDialog(dialog, g);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            int y; try { y = Integer.parseInt(yearF.getText().trim()); }
            catch (Exception ex) { showError("Saldo inicial", "Año inválido."); return; }
            int q = Integer.parseInt(quarter.getValue());
            Task<Void> save = new Task<>() {
                @Override protected Void call() throws Exception {
                    altaApiClient.setVatCompensationBaseline(balance.getText().trim(), y, q);
                    return null;
                }
            };
            save.setOnFailed(ev -> showError("Saldo inicial",
                    save.getException() == null ? "" : save.getException().getMessage()));
            start(save, "vat-baseline-save");
        });
    }

    /**
     * MOD-130-FIX (2026-07-08) — cálculo del modelo 130 en el cliente,
     * espejo de {@code AeatExtraModelsService.compute130}: 5% de gastos de
     * difícil justificación (tope 2.000 € anuales) + cuota 20% + resultado
     * a cero si es negativo. Validado contra las declaraciones reales de
     * Benjamin (1T 827,04 / 2T 522,84).
     */
    private record Model130Local(java.math.BigDecimal gastosDificil,
                                  java.math.BigDecimal rendimientoNeto,
                                  java.math.BigDecimal cuota,
                                  java.math.BigDecimal pago) {}

    private Model130Local computeModel130(java.math.BigDecimal ingresos, java.math.BigDecimal gastos,
                                          java.math.BigDecimal retenciones, java.math.BigDecimal pagosPrevios) {
        java.math.RoundingMode HU = java.math.RoundingMode.HALF_UP;
        java.math.BigDecimal ing = ingresos == null ? java.math.BigDecimal.ZERO : ingresos;
        java.math.BigDecimal gas = gastos == null ? java.math.BigDecimal.ZERO : gastos;
        java.math.BigDecimal ret = retenciones == null ? java.math.BigDecimal.ZERO : retenciones;
        java.math.BigDecimal prev = pagosPrevios == null ? java.math.BigDecimal.ZERO : pagosPrevios;
        java.math.BigDecimal rendimientoPrevio = ing.subtract(gas);
        java.math.BigDecimal gastosDificil = java.math.BigDecimal.ZERO;
        if (rendimientoPrevio.signum() > 0) {
            gastosDificil = rendimientoPrevio.multiply(new java.math.BigDecimal("0.05")).setScale(2, HU);
            java.math.BigDecimal tope = new java.math.BigDecimal("2000");
            if (gastosDificil.compareTo(tope) > 0) gastosDificil = tope;
        }
        java.math.BigDecimal rendimientoNeto = rendimientoPrevio.subtract(gastosDificil);
        java.math.BigDecimal cuota = rendimientoNeto.signum() > 0
                ? rendimientoNeto.multiply(new java.math.BigDecimal("0.20")).setScale(2, HU)
                : java.math.BigDecimal.ZERO.setScale(2);
        java.math.BigDecimal pago = cuota.subtract(ret).subtract(prev).setScale(2, HU);
        if (pago.signum() < 0) pago = java.math.BigDecimal.ZERO.setScale(2);
        return new Model130Local(gastosDificil, rendimientoNeto, cuota, pago);
    }

    /** Editor 130 — IRPF pago fraccionado estimación directa. */
    private void show130Editor(com.benjagest.ui.model.TaxFilingEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Modelo 130 — " + formatPeriod(existing));
        ButtonType saveBt = new ButtonType(t("tax.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        java.util.Map<String, String> parsed = parseDataMap(existing.dataJson());

        TextField ingresos = new TextField(parsed.getOrDefault("ingresos", ""));
        TextField gastos = new TextField(parsed.getOrDefault("gastos", ""));
        TextField retencionesPrev = new TextField(parsed.getOrDefault("retenciones", ""));
        TextField pagosPrev = new TextField(parsed.getOrDefault("pagos_previos", ""));

        ComboBox<String> statusCombo = new ComboBox<>();
        statusCombo.getItems().addAll("DRAFT", "READY", "PRESENTED", "PAID", "REJECTED");
        applyFilingStatusLabels(statusCombo);
        statusCombo.getSelectionModel().select(existing.status());
        TextField csvField = new TextField(existing.csvAeat());

        Label resultLabel = new Label();
        resultLabel.getStyleClass().add("settings-section-title");

        Runnable recompute = () -> {
            java.math.BigDecimal ing = parseDec(ingresos.getText());
            java.math.BigDecimal gas = parseDec(gastos.getText());
            java.math.BigDecimal ret = parseDec(retencionesPrev.getText());
            java.math.BigDecimal pag = parseDec(pagosPrev.getText());
            // MOD-130-FIX (2026-07-08): mismo calculo que el backend
            // (incluye el 5% de gastos de dificil justificacion). Muestra
            // el rendimiento neto y la cuota para casar con el modelo AEAT.
            Model130Local c = computeModel130(ing, gas, ret, pag);
            resultLabel.setText(
                    "Rendimiento neto: " + c.rendimientoNeto.toPlainString() + " €   ·   "
                    + "Cuota (20%): " + c.cuota.toPlainString() + " €\n"
                    + "Pago fraccionado a ingresar: " + c.pago.toPlainString() + " €");
        };
        for (TextField f : new TextField[]{ingresos, gastos, retencionesPrev, pagosPrev}) {
            f.textProperty().addListener((o, ov, nv) -> recompute.run());
        }
        recompute.run();

        // MOD-PREFILL: ingresos/gastos/retenciones (acumulado del año) y pagos
        // previos (de los 130 anteriores) desde las facturas del cliente.
        Runnable recalc130 = () -> {
            Integer q = existing.periodQuarter();
            if (q == null) return;
            Task<com.benjagest.ui.model.Aeat130Data> tk = new Task<>() {
                @Override protected com.benjagest.ui.model.Aeat130Data call() throws Exception {
                    return altaApiClient.preview130(existing.periodYear(), q);
                }
            };
            tk.setOnSucceeded(ev -> {
                var d = tk.getValue();
                ingresos.setText(d.ingresos); gastos.setText(d.gastos);
                retencionesPrev.setText(d.retenciones); pagosPrev.setText(d.pagosPrevios);
                recompute.run();
            });
            tk.setOnFailed(ev -> showError(t("tax.editor.fail.title"), t("tax.editor.fail.body")));
            start(tk, "aeat130-recalc");
        };
        Button recalc130Btn = new Button(t("aeat347.recalc"));
        recalc130Btn.setGraphic(icon("fas-sync"));
        recalc130Btn.setOnAction(e -> recalc130.run());
        if (parsed.isEmpty()) recalc130.run();

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(12));
        grid.add(new Label("Ingresos acumulados"), 0, 0); grid.add(ingresos, 1, 0);
        grid.add(new Label("Gastos acumulados"), 0, 1); grid.add(gastos, 1, 1);
        grid.add(new Label("Retenciones soportadas"), 0, 2); grid.add(retencionesPrev, 1, 2);
        grid.add(new Label("Pagos fraccionados previos"), 0, 3); grid.add(pagosPrev, 1, 3);
        grid.add(new Separator(), 0, 4, 2, 1);
        grid.add(resultLabel, 0, 5, 2, 1);
        grid.add(new Separator(), 0, 6, 2, 1);
        grid.add(new Label(t("tax.editor.status")), 0, 7); grid.add(statusCombo, 1, 7);
        grid.add(new Label(t("tax.editor.csv")), 0, 8); grid.add(csvField, 1, 8);
        grid.add(recalc130Btn, 0, 9, 2, 1);
        installDialog(dialog, grid);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            java.util.Map<String, String> data = new java.util.LinkedHashMap<>();
            data.put("ingresos", ingresos.getText().trim());
            data.put("gastos", gastos.getText().trim());
            data.put("retenciones", retencionesPrev.getText().trim());
            data.put("pagos_previos", pagosPrev.getText().trim());
            // MOD-130-FIX: guardar el pago con el 5% de gastos de dificil
            // justificacion aplicado (mismo calculo que backend y display).
            java.math.BigDecimal total = computeModel130(
                    parseDec(ingresos.getText()), parseDec(gastos.getText()),
                    parseDec(retencionesPrev.getText()), parseDec(pagosPrev.getText())).pago;
            saveFiling(existing, statusCombo.getValue(), encodeDataMap(data), total,
                    csvField.getText(), existing.notes(), java.util.List.of());
        });
    }

    /**
     * AEAT-ED-1 — Editor específico del modelo 347 (operaciones con terceros).
     * Tabla editable de terceros (clave A compras / B ventas, NIF, nombre, T1-T4,
     * total calculado) + recalcular desde facturas + guardar. Fiel a CONTENDO.
     */
    private void show347Editor(com.benjagest.ui.model.TaxFilingEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Modelo 347 — " + formatPeriod(existing));
        ButtonType saveBt = new ButtonType(t("tax.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        javafx.collections.ObservableList<com.benjagest.ui.model.Aeat347Row> rows =
                FXCollections.observableArrayList(altaApiClient.parse347(existing.dataJson()));

        TableView<com.benjagest.ui.model.Aeat347Row> table = new TableView<>(rows);
        table.setEditable(true);
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("aeat347.placeholder")));
        table.setPrefHeight(320);

        Label totalsLabel = new Label();
        totalsLabel.getStyleClass().add("settings-section-title");
        Runnable recompute = () -> {
            java.math.BigDecimal a = java.math.BigDecimal.ZERO, b = java.math.BigDecimal.ZERO;
            for (var r : rows) {
                java.math.BigDecimal tot = row347Total(r);
                if ("A".equalsIgnoreCase(r.clave)) a = a.add(tot); else b = b.add(tot);
            }
            totalsLabel.setText(t("aeat347.totals")
                    .replace("{a}", a.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
                    .replace("{b}", b.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
                    .replace("{n}", String.valueOf(rows.size())));
        };

        // Clave A/B (combo en celda).
        TableColumn<com.benjagest.ui.model.Aeat347Row, String> cClave = new TableColumn<>(t("aeat347.col.key"));
        cClave.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().clave));
        cClave.setCellFactory(javafx.scene.control.cell.ChoiceBoxTableCell.forTableColumn("A", "B"));
        cClave.setOnEditCommit(e -> { e.getRowValue().clave = e.getNewValue(); recompute.run(); });
        cClave.setPrefWidth(70);
        TableColumn<com.benjagest.ui.model.Aeat347Row, String> cNif = new TableColumn<>(t("aeat347.col.nif"));
        cNif.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nif));
        cNif.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        cNif.setOnEditCommit(e -> e.getRowValue().nif = e.getNewValue());
        cNif.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.Aeat347Row, String> cName = new TableColumn<>(t("aeat347.col.name"));
        cName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name));
        cName.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        cName.setOnEditCommit(e -> e.getRowValue().name = e.getNewValue());
        cName.setPrefWidth(200);
        TableColumn<com.benjagest.ui.model.Aeat347Row, String> cT1 = quarterCol347(t("aeat347.col.t1"), 1, recompute);
        TableColumn<com.benjagest.ui.model.Aeat347Row, String> cT2 = quarterCol347(t("aeat347.col.t2"), 2, recompute);
        TableColumn<com.benjagest.ui.model.Aeat347Row, String> cT3 = quarterCol347(t("aeat347.col.t3"), 3, recompute);
        TableColumn<com.benjagest.ui.model.Aeat347Row, String> cT4 = quarterCol347(t("aeat347.col.t4"), 4, recompute);
        TableColumn<com.benjagest.ui.model.Aeat347Row, String> cTot = new TableColumn<>(t("aeat347.col.total"));
        cTot.setCellValueFactory(c -> new SimpleStringProperty(
                row347Total(c.getValue()).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()));
        cTot.setEditable(false);
        cTot.setPrefWidth(100);
        table.getColumns().addAll(java.util.List.of(cClave, cNif, cName, cT1, cT2, cT3, cT4, cTot));

        Button addBtn = new Button(t("aeat347.add"));
        addBtn.setOnAction(e -> { rows.add(new com.benjagest.ui.model.Aeat347Row()); recompute.run(); });
        Button delBtn = new Button(t("aeat347.remove"));
        delBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) { rows.remove(sel); recompute.run(); }
        });
        Button recalcBtn = new Button(t("aeat347.recalc"));
        recalcBtn.setGraphic(icon("fas-sync"));
        recalcBtn.setOnAction(e -> {
            Task<java.util.List<com.benjagest.ui.model.Aeat347Row>> tk = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.Aeat347Row> call() throws Exception {
                    return altaApiClient.preview347(existing.periodYear());
                }
            };
            tk.setOnSucceeded(ev -> { rows.setAll(tk.getValue()); recompute.run(); });
            tk.setOnFailed(ev -> showError(t("tax.editor.fail.title"), t("tax.editor.fail.body")));
            start(tk, "aeat347-recalc");
        });
        // MOD-PREFILL: auto-rellenar al abrir si el filing está vacío.
        if (existing.dataJson() == null || existing.dataJson().isBlank()) recalcBtn.fire();

        ComboBox<String> statusCombo = new ComboBox<>();
        statusCombo.getItems().addAll("DRAFT", "READY", "PRESENTED", "PAID", "REJECTED");
        applyFilingStatusLabels(statusCombo);
        statusCombo.getSelectionModel().select(existing.status());
        TextField csvField = new TextField(existing.csvAeat());

        recompute.run();
        Label hint = new Label(t("aeat347.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");
        HBox tools = new HBox(8, recalcBtn, addBtn, delBtn);
        tools.setAlignment(Pos.CENTER_LEFT);
        HBox foot = new HBox(8, new Label(t("tax.editor.status")), statusCombo,
                new Label(t("tax.editor.csv")), csvField);
        foot.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, hint, tools, totalsLabel, table, new Separator(), foot);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPrefWidth(760);
        installDialog(dialog, box);
        dialog.setResizable(true);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            java.math.BigDecimal totA = java.math.BigDecimal.ZERO, totB = java.math.BigDecimal.ZERO;
            StringBuilder rowsJson = new StringBuilder("[");
            for (int i = 0; i < rows.size(); i++) {
                var r = rows.get(i);
                java.math.BigDecimal tot = row347Total(r);
                if ("A".equalsIgnoreCase(r.clave)) totA = totA.add(tot); else totB = totB.add(tot);
                if (i > 0) rowsJson.append(",");
                rowsJson.append("{\"operationType\":\"").append("A".equalsIgnoreCase(r.clave) ? "A" : "B")
                        .append("\",\"nif\":\"").append(jsonEsc(r.nif))
                        .append("\",\"name\":\"").append(jsonEsc(r.name))
                        .append("\",\"q1\":").append(dec347(r.q1))
                        .append(",\"q2\":").append(dec347(r.q2))
                        .append(",\"q3\":").append(dec347(r.q3))
                        .append(",\"q4\":").append(dec347(r.q4))
                        .append(",\"yearTotal\":").append(tot.toPlainString())
                        .append("}");
            }
            rowsJson.append("]");
            String json = "{\"year\":" + existing.periodYear()
                    + ",\"rowsCount\":" + rows.size()
                    + ",\"totalAdquisiciones\":" + totA.toPlainString()
                    + ",\"totalEntregas\":" + totB.toPlainString()
                    + ",\"rows\":" + rowsJson + "}";
            saveFiling(existing, statusCombo.getValue(), json, totA.add(totB),
                    csvField.getText(), existing.notes(), java.util.List.of());
        });
    }

    /** Columna editable de un trimestre del 347 (escribe en el POJO + recalcula). */
    private TableColumn<com.benjagest.ui.model.Aeat347Row, String> quarterCol347(
            String header, int q, Runnable recompute) {
        TableColumn<com.benjagest.ui.model.Aeat347Row, String> col = new TableColumn<>(header);
        col.setCellValueFactory(c -> new SimpleStringProperty(switch (q) {
            case 1 -> c.getValue().q1; case 2 -> c.getValue().q2;
            case 3 -> c.getValue().q3; default -> c.getValue().q4; }));
        col.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        col.setOnEditCommit(e -> {
            String v = e.getNewValue() == null ? "0" : e.getNewValue().trim();
            switch (q) {
                case 1 -> e.getRowValue().q1 = v; case 2 -> e.getRowValue().q2 = v;
                case 3 -> e.getRowValue().q3 = v; default -> e.getRowValue().q4 = v;
            }
            recompute.run();
            e.getTableView().refresh(); // actualiza la columna Total
        });
        col.setPrefWidth(75);
        return col;
    }

    private java.math.BigDecimal row347Total(com.benjagest.ui.model.Aeat347Row r) {
        return dec347(r.q1).add(dec347(r.q2)).add(dec347(r.q3)).add(dec347(r.q4));
    }

    private java.math.BigDecimal dec347(String s) {
        java.math.BigDecimal d = parseDec(s);
        return d == null ? java.math.BigDecimal.ZERO : d;
    }

    private String jsonEsc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** Etiquetas traducidas para un combo de estado de declaración (DRAFT→Borrador…). */
    private void applyFilingStatusLabels(ComboBox<String> combo) {
        combo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) { return s == null ? "" : t("tax.filing_status." + s); }
            @Override public String fromString(String s) { return s; }
        });
    }

    /**
     * AEAT-ED-2 — Editor específico y editable del modelo 390 (resumen anual IVA).
     * Bases IVA devengado/deducible al 4/10/21 (editables) → cuotas (base×tipo),
     * totales, resultado, volumen y resultado final calculados. Fiel a CONTENDO.
     */
    private void show390Editor(com.benjagest.ui.model.TaxFilingEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Modelo 390 — " + formatPeriod(existing));
        ButtonType saveBt = new ButtonType(t("tax.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        com.benjagest.ui.model.Aeat390Data d = altaApiClient.parse390(existing.dataJson());

        TextField bd4 = new TextField(d.baseDev4), bd10 = new TextField(d.baseDev10), bd21 = new TextField(d.baseDev21);
        TextField bs4 = new TextField(d.baseDed4), bs10 = new TextField(d.baseDed10), bs21 = new TextField(d.baseDed21);
        TextField exentas = new TextField(d.exentas), intracom = new TextField(d.intracom);
        TextField compensaciones = new TextField(d.compensaciones);

        Label cuotaDev = new Label(), cuotaDed = new Label(), resultado = new Label(),
                volumen = new Label(), resultadoFinal = new Label();
        resultado.getStyleClass().add("settings-section-title");
        resultadoFinal.getStyleClass().add("settings-section-title");

        Runnable recompute = () -> {
            java.math.BigDecimal cd = pct390(bd4, 4).add(pct390(bd10, 10)).add(pct390(bd21, 21));
            java.math.BigDecimal cs = pct390(bs4, 4).add(pct390(bs10, 10)).add(pct390(bs21, 21));
            java.math.BigDecimal res = cd.subtract(cs);
            java.math.BigDecimal vol = dec347(bd4.getText()).add(dec347(bd10.getText())).add(dec347(bd21.getText()))
                    .add(dec347(exentas.getText())).add(dec347(intracom.getText()));
            java.math.BigDecimal resFinal = res.subtract(dec347(compensaciones.getText()));
            cuotaDev.setText(eur390(cd));
            cuotaDed.setText(eur390(cs));
            resultado.setText(t("aeat390.result") + " " + eur390(res));
            volumen.setText(t("aeat390.volume") + " " + eur390(vol));
            resultadoFinal.setText(t("aeat390.result_final") + " " + eur390(resFinal));
        };
        for (TextField f : new TextField[]{bd4, bd10, bd21, bs4, bs10, bs21, exentas, intracom, compensaciones}) {
            f.textProperty().addListener((o, ov, nv) -> recompute.run());
        }

        ComboBox<String> statusCombo = new ComboBox<>();
        statusCombo.getItems().addAll("DRAFT", "READY", "PRESENTED", "PAID", "REJECTED");
        applyFilingStatusLabels(statusCombo);
        statusCombo.getSelectionModel().select(existing.status());
        TextField csvField = new TextField(existing.csvAeat());

        Button recalcBtn = new Button(t("aeat347.recalc"));
        recalcBtn.setGraphic(icon("fas-sync"));
        recalcBtn.setOnAction(e -> {
            Task<com.benjagest.ui.model.Aeat390Data> tk = new Task<>() {
                @Override protected com.benjagest.ui.model.Aeat390Data call() throws Exception {
                    return altaApiClient.preview390(existing.periodYear());
                }
            };
            tk.setOnSucceeded(ev -> {
                var nd = tk.getValue();
                bd4.setText(nd.baseDev4); bd10.setText(nd.baseDev10); bd21.setText(nd.baseDev21);
                bs4.setText(nd.baseDed4); bs10.setText(nd.baseDed10); bs21.setText(nd.baseDed21);
                recompute.run();
            });
            tk.setOnFailed(ev -> showError(t("tax.editor.fail.title"), t("tax.editor.fail.body")));
            start(tk, "aeat390-recalc");
        });
        // MOD-PREFILL: auto-rellenar al abrir si el filing está vacío.
        if (existing.dataJson() == null || existing.dataJson().isBlank()) recalcBtn.fire();

        GridPane g = new GridPane();
        g.setHgap(12); g.setVgap(8); g.setPadding(new Insets(12));
        int r = 0;
        g.add(label(t("aeat390.devengado"), "settings-section-title"), 0, r++, 3, 1);
        g.add(new Label("4%"), 0, r); g.add(bd4, 1, r++);
        g.add(new Label("10%"), 0, r); g.add(bd10, 1, r++);
        g.add(new Label("21%"), 0, r); g.add(bd21, 1, r++);
        g.add(new Label(t("aeat390.cuota_dev")), 0, r); g.add(cuotaDev, 1, r++);
        g.add(label(t("aeat390.deducible"), "settings-section-title"), 0, r++, 3, 1);
        g.add(new Label("4%"), 0, r); g.add(bs4, 1, r++);
        g.add(new Label("10%"), 0, r); g.add(bs10, 1, r++);
        g.add(new Label("21%"), 0, r); g.add(bs21, 1, r++);
        g.add(new Label(t("aeat390.cuota_ded")), 0, r); g.add(cuotaDed, 1, r++);
        g.add(label(t("aeat390.other"), "settings-section-title"), 0, r++, 3, 1);
        g.add(new Label(t("aeat390.exentas")), 0, r); g.add(exentas, 1, r++);
        g.add(new Label(t("aeat390.intracom")), 0, r); g.add(intracom, 1, r++);
        g.add(new Label(t("aeat390.compensaciones")), 0, r); g.add(compensaciones, 1, r++);
        g.add(new Separator(), 0, r++, 3, 1);
        g.add(volumen, 0, r++, 3, 1);
        g.add(resultado, 0, r++, 3, 1);
        g.add(resultadoFinal, 0, r++, 3, 1);
        g.add(new Separator(), 0, r++, 3, 1);
        g.add(new Label(t("tax.editor.status")), 0, r); g.add(statusCombo, 1, r++);
        g.add(new Label(t("tax.editor.csv")), 0, r); g.add(csvField, 1, r++);

        Label hint = new Label(t("aeat390.hint"));
        hint.setWrapText(true); hint.getStyleClass().add("settings-hint");
        HBox tools = new HBox(8, recalcBtn);
        VBox box = new VBox(10, hint, tools, g);
        box.setPrefWidth(560);
        recompute.run();
        installDialog(dialog, scroll(box));
        dialog.setResizable(true);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            java.math.BigDecimal cd = pct390(bd4, 4).add(pct390(bd10, 10)).add(pct390(bd21, 21));
            java.math.BigDecimal cs = pct390(bs4, 4).add(pct390(bs10, 10)).add(pct390(bs21, 21));
            java.math.BigDecimal res = cd.subtract(cs);
            java.math.BigDecimal vol = dec347(bd4.getText()).add(dec347(bd10.getText())).add(dec347(bd21.getText()))
                    .add(dec347(exentas.getText())).add(dec347(intracom.getText()));
            java.math.BigDecimal resFinal = res.subtract(dec347(compensaciones.getText()));
            String json = "{\"year\":" + existing.periodYear()
                    + ",\"baseDev4\":" + dec347(bd4.getText()).toPlainString()
                    + ",\"baseDev10\":" + dec347(bd10.getText()).toPlainString()
                    + ",\"baseDev21\":" + dec347(bd21.getText()).toPlainString()
                    + ",\"baseDed4\":" + dec347(bs4.getText()).toPlainString()
                    + ",\"baseDed10\":" + dec347(bs10.getText()).toPlainString()
                    + ",\"baseDed21\":" + dec347(bs21.getText()).toPlainString()
                    + ",\"exentas\":" + dec347(exentas.getText()).toPlainString()
                    + ",\"intracom\":" + dec347(intracom.getText()).toPlainString()
                    + ",\"compensaciones\":" + dec347(compensaciones.getText()).toPlainString()
                    + ",\"cuotaDevengada\":" + cd.toPlainString()
                    + ",\"cuotaDeducible\":" + cs.toPlainString()
                    + ",\"resultado\":" + res.toPlainString()
                    + ",\"volumen\":" + vol.toPlainString()
                    + ",\"resultadoFinal\":" + resFinal.toPlainString() + "}";
            saveFiling(existing, statusCombo.getValue(), json,
                    resFinal.setScale(2, java.math.RoundingMode.HALF_UP),
                    csvField.getText(), existing.notes(), java.util.List.of());
        });
    }

    /**
     * AEAT-ED-3 — Editor específico y editable del modelo 190 (retenciones IRPF).
     * Tabla editable de perceptores (clave A trabajo / G profesionales, NIF, nombre,
     * retribuciones, retención) + totales + recalcular desde facturas/nóminas.
     */
    private void show190Editor(com.benjagest.ui.model.TaxFilingEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Modelo 190 — " + formatPeriod(existing));
        ButtonType saveBt = new ButtonType(t("tax.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        javafx.collections.ObservableList<com.benjagest.ui.model.Aeat190Row> rows =
                FXCollections.observableArrayList(altaApiClient.parse190(existing.dataJson()));

        TableView<com.benjagest.ui.model.Aeat190Row> table = new TableView<>(rows);
        table.setEditable(true);
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("aeat190.placeholder")));
        table.setPrefHeight(320);

        Label totalsLabel = new Label();
        totalsLabel.getStyleClass().add("settings-section-title");
        Runnable recompute = () -> {
            java.math.BigDecimal b = java.math.BigDecimal.ZERO, ret = java.math.BigDecimal.ZERO;
            for (var r : rows) { b = b.add(dec347(r.base)); ret = ret.add(dec347(r.retencion)); }
            totalsLabel.setText(t("aeat190.totals")
                    .replace("{base}", b.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
                    .replace("{ret}", ret.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
                    .replace("{n}", String.valueOf(rows.size())));
        };

        TableColumn<com.benjagest.ui.model.Aeat190Row, String> cClave = new TableColumn<>(t("aeat190.col.key"));
        cClave.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().clave));
        cClave.setCellFactory(javafx.scene.control.cell.ChoiceBoxTableCell.forTableColumn("A", "G"));
        cClave.setOnEditCommit(e -> e.getRowValue().clave = e.getNewValue());
        cClave.setPrefWidth(70);
        TableColumn<com.benjagest.ui.model.Aeat190Row, String> cNif = new TableColumn<>(t("aeat190.col.nif"));
        cNif.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().nif));
        cNif.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        cNif.setOnEditCommit(e -> e.getRowValue().nif = e.getNewValue());
        cNif.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.Aeat190Row, String> cName = new TableColumn<>(t("aeat190.col.name"));
        cName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name));
        cName.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        cName.setOnEditCommit(e -> e.getRowValue().name = e.getNewValue());
        cName.setPrefWidth(200);
        TableColumn<com.benjagest.ui.model.Aeat190Row, String> cBase = new TableColumn<>(t("aeat190.col.base"));
        cBase.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().base));
        cBase.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        cBase.setOnEditCommit(e -> { e.getRowValue().base = e.getNewValue() == null ? "0" : e.getNewValue().trim(); recompute.run(); });
        cBase.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.Aeat190Row, String> cRet = new TableColumn<>(t("aeat190.col.ret"));
        cRet.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().retencion));
        cRet.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        cRet.setOnEditCommit(e -> { e.getRowValue().retencion = e.getNewValue() == null ? "0" : e.getNewValue().trim(); recompute.run(); });
        cRet.setPrefWidth(110);
        table.getColumns().addAll(java.util.List.of(cClave, cNif, cName, cBase, cRet));

        Button addBtn = new Button(t("aeat347.add"));
        addBtn.setOnAction(e -> { rows.add(new com.benjagest.ui.model.Aeat190Row()); recompute.run(); });
        Button delBtn = new Button(t("aeat347.remove"));
        delBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) { rows.remove(sel); recompute.run(); }
        });
        Button recalcBtn = new Button(t("aeat347.recalc"));
        recalcBtn.setGraphic(icon("fas-sync"));
        recalcBtn.setOnAction(e -> {
            Task<java.util.List<com.benjagest.ui.model.Aeat190Row>> tk = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.Aeat190Row> call() throws Exception {
                    return altaApiClient.preview190(existing.periodYear());
                }
            };
            tk.setOnSucceeded(ev -> { rows.setAll(tk.getValue()); recompute.run(); });
            tk.setOnFailed(ev -> showError(t("tax.editor.fail.title"), t("tax.editor.fail.body")));
            start(tk, "aeat190-recalc");
        });
        // MOD-PREFILL: auto-rellenar al abrir si el filing está vacío.
        if (existing.dataJson() == null || existing.dataJson().isBlank()) recalcBtn.fire();

        ComboBox<String> statusCombo = new ComboBox<>();
        statusCombo.getItems().addAll("DRAFT", "READY", "PRESENTED", "PAID", "REJECTED");
        applyFilingStatusLabels(statusCombo);
        statusCombo.getSelectionModel().select(existing.status());
        TextField csvField = new TextField(existing.csvAeat());

        recompute.run();
        Label hint = new Label(t("aeat190.hint"));
        hint.setWrapText(true); hint.getStyleClass().add("settings-hint");
        HBox tools = new HBox(8, recalcBtn, addBtn, delBtn);
        tools.setAlignment(Pos.CENTER_LEFT);
        HBox foot = new HBox(8, new Label(t("tax.editor.status")), statusCombo,
                new Label(t("tax.editor.csv")), csvField);
        foot.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, hint, tools, totalsLabel, table, new Separator(), foot);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPrefWidth(720);
        installDialog(dialog, box);
        dialog.setResizable(true);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            java.math.BigDecimal totBase = java.math.BigDecimal.ZERO, totRet = java.math.BigDecimal.ZERO;
            StringBuilder rowsJson = new StringBuilder("[");
            for (int i = 0; i < rows.size(); i++) {
                var r = rows.get(i);
                totBase = totBase.add(dec347(r.base));
                totRet = totRet.add(dec347(r.retencion));
                if (i > 0) rowsJson.append(",");
                rowsJson.append("{\"nif\":\"").append(jsonEsc(r.nif))
                        .append("\",\"name\":\"").append(jsonEsc(r.name))
                        .append("\",\"subclave\":\"").append("G".equalsIgnoreCase(r.clave) ? "G" : "A")
                        .append("\",\"clave\":\"").append("G".equalsIgnoreCase(r.clave) ? "G" : "A")
                        .append("\",\"base\":").append(dec347(r.base).toPlainString())
                        .append(",\"retencion\":").append(dec347(r.retencion).toPlainString())
                        .append("}");
            }
            rowsJson.append("]");
            String json = "{\"year\":" + existing.periodYear()
                    + ",\"perceptoresCount\":" + rows.size()
                    + ",\"totalBase\":" + totBase.toPlainString()
                    + ",\"totalRetenciones\":" + totRet.toPlainString()
                    + ",\"rows\":" + rowsJson + "}";
            saveFiling(existing, statusCombo.getValue(), json,
                    totRet.setScale(2, java.math.RoundingMode.HALF_UP),
                    csvField.getText(), existing.notes(), java.util.List.of());
        });
    }

    /** Cuota IVA = base × tipo% (para el 390). */
    private java.math.BigDecimal pct390(TextField baseField, int tipo) {
        return dec347(baseField.getText())
                .multiply(new java.math.BigDecimal(tipo)).divide(new java.math.BigDecimal(100));
    }

    private String eur390(java.math.BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " €";
    }

    private void saveFiling(com.benjagest.ui.model.TaxFilingEntry existing, String status,
                             String dataJson, java.math.BigDecimal total, String csv, String notes,
                             java.util.List<com.benjagest.ui.model.TaxModelEntry> catalog) {
        Task<com.benjagest.ui.model.TaxFilingEntry> task = new Task<>() {
            @Override
            protected com.benjagest.ui.model.TaxFilingEntry call() throws Exception {
                return altaApiClient.updateFiling(existing.id(), existing.taxModelCode(),
                        existing.periodYear(), existing.periodQuarter(), existing.periodMonth(),
                        status, dataJson, total,
                        blankToNullOrSelf(csv),
                        blankToNullOrSelf(notes));
            }
        };
        task.setOnSucceeded(ev -> onReload.run());
        task.setOnFailed(ev -> showError(t("tax.editor.fail.title"), t("tax.editor.fail.body")));
        start(task, "tax-filing-save");
    }

    private java.math.BigDecimal parseDec(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new java.math.BigDecimal(s.trim().replace(",", ".")); }
        catch (NumberFormatException ex) { return null; }
    }

    private java.math.BigDecimal sum(String... values) {
        java.math.BigDecimal acc = java.math.BigDecimal.ZERO;
        for (String v : values) {
            java.math.BigDecimal d = parseDec(v);
            if (d != null) acc = acc.add(d);
        }
        return acc;
    }

    /** base × tipo, redondeado a 2 decimales (cuota de IVA). "0" si la base no parsea. */
    private String mulStr(String base, String rate) {
        java.math.BigDecimal b = parseDec(base);
        if (b == null) return "0";
        return b.multiply(new java.math.BigDecimal(rate))
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Mini-parser: convierte el JSON crudo de `data` en un Map<String,String>
     * para poblar las casillas del editor. No es un parser real — asume
     * un objeto plano con claves y valores string-o-number.
     */
    private java.util.Map<String, String> parseDataMap(String json) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        var p = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*(?:\"([^\"]*)\"|(-?\\d+(?:\\.\\d+)?))");
        var m = p.matcher(json);
        while (m.find()) {
            String key = m.group(1);
            String val = m.group(2) != null ? m.group(2) : m.group(3);
            out.put(key, val);
        }
        return out;
    }

    private String encodeDataMap(java.util.Map<String, String> data) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : data.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":\"")
                    .append(e.getValue() == null ? "" : e.getValue().replace("\"", "\\\""))
                    .append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    // ================================================================
    //  L3-4 — Tab CALENDARIO LABORAL (festivos nacionales + autonómicos)
    // ================================================================

    /**
     * Construye la pestaña Calendario laboral del módulo Labor. Layout:
     * <ul>
     *   <li>Botón "Crear calendario 2026" arriba (bootstrap rápido).</li>
     *   <li>Tabla de calendarios existentes (años, CCAA, activo).</li>
     *   <li>Tabla de festivos del calendario seleccionado abajo.</li>
     *   <li>Botones añadir/eliminar festivo, eliminar calendario.</li>
     * </ul>
     */
}
