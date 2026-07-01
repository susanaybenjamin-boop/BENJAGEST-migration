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
 * NOM-1 — Categoría "Parámetros" del módulo Laboral (bloque UIR). Reúne las
 * cuatro tablas legales año-dependientes (patrón no-code) que estaban en el God
 * Object: tipos de cotización SS ({@code buildSsRatesTab}), topes de
 * indemnización por despido ({@code buildSeveranceParamsTab}), bases de
 * cotización por grupo ({@code buildSsGroupBasesTab}) y parámetros IRPF
 * ({@code buildIrpfParamsTab}). Cada pestaña es autocontenida (tabla + editor);
 * sin acoplamiento al resto de Laboral. Movimiento puro: mismo comportamiento,
 * mismas claves i18n. Solo depende de {@link LaborApiClient} y los helpers de
 * {@link ScreenBase}.
 */
public class LaborParamsScreen extends ScreenBase {

    private final LaborApiClient laborApiClient;

    public LaborParamsScreen(LaborApiClient laborApiClient,
                             Function<String, String> tt, Router router) {
        super(tt, router);
        this.laborApiClient = laborApiClient;
    }

    private static String humanizeBackendError(String raw) {
        return com.benjagest.ui.support.BackendErrors.humanize(raw);
    }

    /** Helper genérico para añadir columnas con un getter String. */
    private <T> void addCol(javafx.scene.control.TableView<T> table, String header,
                              java.util.function.Function<T, String> getter, double width) {
        javafx.scene.control.TableColumn<T, String> c = new javafx.scene.control.TableColumn<>(header);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(getter.apply(cd.getValue())));
        table.getColumns().add(c);
    }

    /**
     * VG-FULL-SCAN-2 — Variante de {@link #addCol} con comparador de
     * ordenacion para columnas numericas (importes) o de fecha. Sin
     * esto JavaFX ordena como texto y "10" cae antes de "9".
     */
    private <T> void addColSorted(javafx.scene.control.TableView<T> table, String header,
                                    java.util.function.Function<T, String> getter, double width,
                                    java.util.Comparator<String> comparator) {
        javafx.scene.control.TableColumn<T, String> c = new javafx.scene.control.TableColumn<>(header);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(getter.apply(cd.getValue())));
        c.setComparator(comparator);
        table.getColumns().add(c);
    }

    public Node buildSsRatesTab() {
        VBox content = new VBox(12);
        content.setPadding(new Insets(16));
        Label hint = new Label(t("labor.ssrates.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        TableView<com.benjagest.ui.model.SsRateEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.ssrates.empty")));
        com.benjagest.ui.support.TableSelectionHelper.install(table);

        addColSorted(table, t("labor.ssrates.col.year"), r -> String.valueOf(r.year()), 60, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, "Trab. CC", r -> pctTxt(r.eeCommon()), 80, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, "Trab. Desempleo", r -> pctTxt(r.eeUnemployment()), 110, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, "Trab. Form.", r -> pctTxt(r.eeTraining()), 80, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, "Trab. MEI", r -> pctTxt(r.eeMei()), 75, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, "Emp. CC", r -> pctTxt(r.erCommon()), 80, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, "Emp. Desempleo", r -> pctTxt(r.erUnemployment()), 110, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, "Emp. FOGASA", r -> pctTxt(r.erFogasa()), 90, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, "Emp. Form.", r -> pctTxt(r.erTraining()), 80, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, "Emp. MEI", r -> pctTxt(r.erMei()), 75, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, "AT/EP def.", r -> pctTxt(r.defaultAtEp()), 80, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, t("labor.ssrates.col.basemax"),
                r -> r.baseMaxMonthly() == null ? "" : money(r.baseMaxMonthly()), 100, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, t("labor.ssrates.col.basemin"),
                r -> r.baseMinMonthly() == null ? "" : money(r.baseMinMonthly()), 100, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, t("labor.ssrates.col.ref"),
                r -> r.legalReference() == null ? "" : r.legalReference(), 160, String.CASE_INSENSITIVE_ORDER);

        Runnable reload = () -> {
            Task<java.util.List<com.benjagest.ui.model.SsRateEntry>> tk = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.SsRateEntry> call() throws Exception {
                    return laborApiClient.listSsRates();
                }
            };
            tk.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(tk.getValue())));
            tk.setOnFailed(ev -> showError(t("labor.ssrates.load_failed"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "ssrates-load");
        };

        Button addBtn = new Button(t("labor.ssrates.add"));
        addBtn.getStyleClass().add("button-primary");
        addBtn.setOnAction(e -> {
            // Copia del año más reciente como base para el siguiente.
            var items = table.getItems();
            com.benjagest.ui.model.SsRateEntry base = items.isEmpty() ? null : items.get(0);
            int nextYear = base == null ? java.time.Year.now().getValue() + 1 : base.year() + 1;
            showSsRateEditor(base, nextYear, reload);
        });
        Button editBtn = new Button(t("labor.ssrates.edit"));
        editBtn.getStyleClass().add("button-secondary");
        editBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) showSsRateEditor(sel, sel.year(), reload);
        });
        HBox actions = new HBox(8, addBtn, editBtn);

        reload.run();
        VBox body = new VBox(10, hint, actions, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        content.getChildren().add(body);
        VBox.setVgrow(body, Priority.ALWAYS);
        return content;
    }

    private String pctTxt(java.math.BigDecimal v) {
        return v == null ? "" : v.toPlainString().replace(".", ",") + " %";
    }

    private void showSsRateEditor(com.benjagest.ui.model.SsRateEntry base, int year, Runnable onSaved) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.ssrates.edit") + " — " + year);
        ButtonType save = new ButtonType(t("labor.ssrates.save_btn"), ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        TextField yearF = new TextField(String.valueOf(year));
        TextField eeC = rateField(base == null ? null : base.eeCommon(), "4.70");
        TextField eeU = rateField(base == null ? null : base.eeUnemployment(), "1.55");
        TextField eeT = rateField(base == null ? null : base.eeTraining(), "0.10");
        TextField eeM = rateField(base == null ? null : base.eeMei(), "0.15");
        TextField erC = rateField(base == null ? null : base.erCommon(), "23.60");
        TextField erU = rateField(base == null ? null : base.erUnemployment(), "5.50");
        TextField erF = rateField(base == null ? null : base.erFogasa(), "0.20");
        TextField erT = rateField(base == null ? null : base.erTraining(), "0.60");
        TextField erM = rateField(base == null ? null : base.erMei(), "0.75");
        TextField atEp = rateField(base == null ? null : base.defaultAtEp(), "1.50");
        TextField baseMax = rateField(base == null ? null : base.baseMaxMonthly(), "5101.20");
        TextField baseMin = rateField(base == null ? null : base.baseMinMonthly(), "0");
        TextField ref = new TextField(base == null || base.legalReference() == null ? "" : base.legalReference());

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12));
        int r = 0;
        g.add(new Label(t("labor.ssrates.col.year")), 0, r); g.add(yearF, 1, r++);
        g.add(new Label("Trabajador — Cont. comunes"), 0, r); g.add(eeC, 1, r++);
        g.add(new Label("Trabajador — Desempleo"), 0, r); g.add(eeU, 1, r++);
        g.add(new Label("Trabajador — Formación"), 0, r); g.add(eeT, 1, r++);
        g.add(new Label("Trabajador — MEI"), 0, r); g.add(eeM, 1, r++);
        g.add(new Label("Empresa — Cont. comunes"), 0, r); g.add(erC, 1, r++);
        g.add(new Label("Empresa — Desempleo"), 0, r); g.add(erU, 1, r++);
        g.add(new Label("Empresa — FOGASA"), 0, r); g.add(erF, 1, r++);
        g.add(new Label("Empresa — Formación"), 0, r); g.add(erT, 1, r++);
        g.add(new Label("Empresa — MEI"), 0, r); g.add(erM, 1, r++);
        g.add(new Label("AT/EP por defecto"), 0, r); g.add(atEp, 1, r++);
        g.add(new Label(t("labor.ssrates.col.basemax")), 0, r); g.add(baseMax, 1, r++);
        g.add(new Label(t("labor.ssrates.col.basemin")), 0, r); g.add(baseMin, 1, r++);
        g.add(new Label(t("labor.ssrates.col.ref")), 0, r); g.add(ref, 1, r++);
        installDialog(d, g);

        d.showAndWait().ifPresent(bt -> {
            if (bt != save) return;
            com.benjagest.ui.model.SsRateEntry e = new com.benjagest.ui.model.SsRateEntry(
                    parseIntSafe(yearF.getText()) == null ? year : parseIntSafe(yearF.getText()),
                    parseDecSafe(eeC.getText()), parseDecSafe(eeU.getText()),
                    parseDecSafe(eeT.getText()), parseDecSafe(eeM.getText()),
                    parseDecSafe(erC.getText()), parseDecSafe(erU.getText()), parseDecSafe(erF.getText()),
                    parseDecSafe(erT.getText()), parseDecSafe(erM.getText()), parseDecSafe(atEp.getText()),
                    parseDecSafe(baseMax.getText()), parseDecSafe(baseMin.getText()),
                    blankToNullOrSelf(ref.getText()));
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception { laborApiClient.upsertSsRate(e); return null; }
            };
            tk.setOnSucceeded(ev -> onSaved.run());
            tk.setOnFailed(ev -> showError(t("labor.ssrates.save_failed"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "ssrates-save");
        });
    }

    private TextField rateField(java.math.BigDecimal v, String fallback) {
        return new TextField(v == null ? fallback : v.toPlainString());
    }

    public Node buildSeveranceParamsTab() {
        VBox content = new VBox(12);
        content.setPadding(new Insets(16));
        Label hint = new Label(t("labor.severance.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        TableView<com.benjagest.ui.model.SeveranceParamEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.severance.empty")));
        com.benjagest.ui.support.TableSelectionHelper.install(table);

        addColSorted(table, t("labor.severance.col.year"), r -> String.valueOf(r.yearNumber()), 60, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, t("labor.severance.col.unfair"),
                r -> daysTxt(r.unfairDaysPerYear()), 100, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, t("labor.severance.col.unfair_cap"),
                r -> String.valueOf(r.unfairCapDays()), 90, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, t("labor.severance.col.pre2012"),
                r -> daysTxt(r.unfairPre2012DaysPerYear()), 110, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, t("labor.severance.col.pre2012_cap"),
                r -> String.valueOf(r.unfairPre2012CapDays()), 100, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, t("labor.severance.col.objective"),
                r -> daysTxt(r.objectiveDaysPerYear()), 100, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, t("labor.severance.col.objective_cap"),
                r -> String.valueOf(r.objectiveCapDays()), 90, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, t("labor.severance.col.end_contract"),
                r -> daysTxt(r.endContractDaysPerYear()), 110, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, t("labor.severance.col.exempt"),
                r -> r.irpfExemptCap() == null ? "" : money(r.irpfExemptCap()), 110, NUMERIC_STRING_COMPARATOR);
        addCol(table, t("labor.severance.col.ref"),
                r -> r.legalReference() == null ? "" : r.legalReference(), 200);

        Runnable reload = () -> {
            Task<java.util.List<com.benjagest.ui.model.SeveranceParamEntry>> tk = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.SeveranceParamEntry> call() throws Exception {
                    return laborApiClient.listSeveranceParams();
                }
            };
            tk.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(tk.getValue())));
            tk.setOnFailed(ev -> showError(t("labor.severance.load_failed"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "severance-load");
        };

        Button addBtn = new Button(t("labor.severance.add"));
        addBtn.getStyleClass().add("button-primary");
        addBtn.setOnAction(e -> {
            var items = table.getItems();
            com.benjagest.ui.model.SeveranceParamEntry base = items.isEmpty() ? null : items.get(0);
            int nextYear = base == null ? java.time.Year.now().getValue() : base.yearNumber() + 1;
            showSeveranceParamEditor(base, nextYear, reload);
        });
        Button editBtn = new Button(t("labor.severance.edit"));
        editBtn.getStyleClass().add("button-secondary");
        editBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) showSeveranceParamEditor(sel, sel.yearNumber(), reload);
        });
        HBox actions = new HBox(8, addBtn, editBtn);

        reload.run();
        VBox body = new VBox(10, hint, actions, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        content.getChildren().add(body);
        VBox.setVgrow(body, Priority.ALWAYS);
        return content;
    }

    private String daysTxt(java.math.BigDecimal v) {
        return v == null ? "" : v.toPlainString().replace(".", ",");
    }

    private void showSeveranceParamEditor(com.benjagest.ui.model.SeveranceParamEntry base, int year,
                                          Runnable onSaved) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.severance.edit") + " — " + year);
        ButtonType save = new ButtonType(t("labor.severance.save_btn"), ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        TextField yearF = new TextField(String.valueOf(year));
        TextField unfair = rateField(base == null ? null : base.unfairDaysPerYear(), "33");
        TextField unfairCap = new TextField(base == null ? "720" : String.valueOf(base.unfairCapDays()));
        TextField pre = rateField(base == null ? null : base.unfairPre2012DaysPerYear(), "45");
        TextField preCap = new TextField(base == null ? "1260" : String.valueOf(base.unfairPre2012CapDays()));
        TextField objective = rateField(base == null ? null : base.objectiveDaysPerYear(), "20");
        TextField objectiveCap = new TextField(base == null ? "360" : String.valueOf(base.objectiveCapDays()));
        TextField endContract = rateField(base == null ? null : base.endContractDaysPerYear(), "12");
        TextField exempt = rateField(base == null ? null : base.irpfExemptCap(), "180000");
        TextField ref = new TextField(base == null || base.legalReference() == null ? "" : base.legalReference());

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12));
        int r = 0;
        g.add(new Label(t("labor.severance.col.year")), 0, r); g.add(yearF, 1, r++);
        g.add(new Label(t("labor.severance.col.unfair")), 0, r); g.add(unfair, 1, r++);
        g.add(new Label(t("labor.severance.col.unfair_cap")), 0, r); g.add(unfairCap, 1, r++);
        g.add(new Label(t("labor.severance.col.pre2012")), 0, r); g.add(pre, 1, r++);
        g.add(new Label(t("labor.severance.col.pre2012_cap")), 0, r); g.add(preCap, 1, r++);
        g.add(new Label(t("labor.severance.col.objective")), 0, r); g.add(objective, 1, r++);
        g.add(new Label(t("labor.severance.col.objective_cap")), 0, r); g.add(objectiveCap, 1, r++);
        g.add(new Label(t("labor.severance.col.end_contract")), 0, r); g.add(endContract, 1, r++);
        g.add(new Label(t("labor.severance.col.exempt")), 0, r); g.add(exempt, 1, r++);
        g.add(new Label(t("labor.severance.col.ref")), 0, r); g.add(ref, 1, r++);
        installDialog(d, g);

        d.showAndWait().ifPresent(bt -> {
            if (bt != save) return;
            com.benjagest.ui.model.SeveranceParamEntry e = new com.benjagest.ui.model.SeveranceParamEntry(
                    parseIntSafe(yearF.getText()) == null ? year : parseIntSafe(yearF.getText()),
                    parseDecSafe(unfair.getText()), parseIntSafe(unfairCap.getText()) == null ? 0 : parseIntSafe(unfairCap.getText()),
                    parseDecSafe(pre.getText()), parseIntSafe(preCap.getText()) == null ? 0 : parseIntSafe(preCap.getText()),
                    parseDecSafe(objective.getText()), parseIntSafe(objectiveCap.getText()) == null ? 0 : parseIntSafe(objectiveCap.getText()),
                    parseDecSafe(endContract.getText()), parseDecSafe(exempt.getText()),
                    blankToNullOrSelf(ref.getText()));
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception { laborApiClient.upsertSeveranceParam(e); return null; }
            };
            tk.setOnSucceeded(ev -> onSaved.run());
            tk.setOnFailed(ev -> showError(t("labor.severance.save_failed"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "severance-save");
        });
    }

    public Node buildSsGroupBasesTab() {
        VBox content = new VBox(12);
        content.setPadding(new Insets(16));

        Label warn = new Label(t("labor.ssgroup.validate_warning"));
        warn.setWrapText(true);
        warn.setStyle("-fx-text-fill:#7a4f01; -fx-background-color:#fff7e6; "
                + "-fx-padding:10 14; -fx-background-radius:8; "
                + "-fx-border-color:#ffd591; -fx-border-radius:8;");
        Label hint = new Label(t("labor.ssgroup.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        ComboBox<Integer> yearCombo = new ComboBox<>();

        TableView<com.benjagest.ui.model.SsGroupBaseEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.ssgroup.empty")));
        com.benjagest.ui.support.TableSelectionHelper.install(table);

        addColSorted(table, t("labor.ssgroup.col.group"),
                r -> String.valueOf(r.cotizGroup()), 60, NUMERIC_STRING_COMPARATOR);
        addCol(table, t("labor.ssgroup.col.label"), r -> r.label() == null ? "" : r.label(), 320);
        addColSorted(table, t("labor.ssgroup.col.basemin"),
                r -> r.baseMin() == null ? "" : money(r.baseMin()), 110, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, t("labor.ssgroup.col.basemax"),
                r -> r.baseMax() == null ? "" : money(r.baseMax()), 110, NUMERIC_STRING_COMPARATOR);
        addCol(table, t("labor.ssgroup.col.daily"), r -> r.daily() ? "✓" : "", 70);
        addCol(table, t("labor.ssgroup.col.pending"), r -> r.pendingValidation() ? "⚠" : "✓", 70);

        Runnable reloadTable = () -> {
            Integer y = yearCombo.getValue();
            if (y == null) return;
            Task<java.util.List<com.benjagest.ui.model.SsGroupBaseEntry>> tk = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.SsGroupBaseEntry> call() throws Exception {
                    return laborApiClient.listSsGroupBases(y);
                }
            };
            tk.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(tk.getValue())));
            tk.setOnFailed(ev -> showError(t("labor.ssgroup.load_failed"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "ssgroup-load");
        };
        yearCombo.valueProperty().addListener((o, a, b) -> reloadTable.run());

        Runnable loadYears = () -> {
            Task<java.util.List<Integer>> yt = new Task<>() {
                @Override protected java.util.List<Integer> call() throws Exception {
                    return laborApiClient.listSsGroupBaseYears();
                }
            };
            yt.setOnSucceeded(ev -> {
                var years = yt.getValue();
                Integer keep = yearCombo.getValue();
                yearCombo.getItems().setAll(years);
                if (keep != null && years.contains(keep)) yearCombo.setValue(keep);
                else if (!years.isEmpty()) yearCombo.getSelectionModel().selectFirst();
                reloadTable.run();
            });
            yt.setOnFailed(ev -> showError(t("labor.ssgroup.load_failed"),
                    yt.getException() == null ? "" : yt.getException().getMessage()));
            start(yt, "ssgroup-years");
        };

        Button editBtn = new Button(t("labor.ssgroup.edit"));
        editBtn.getStyleClass().add("button-secondary");
        editBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null && yearCombo.getValue() != null) {
                showSsGroupBaseEditor(sel, yearCombo.getValue(), table, reloadTable);
            }
        });
        Button cloneBtn = new Button(t("labor.ssgroup.clone"));
        cloneBtn.getStyleClass().add("button-secondary");
        cloneBtn.setOnAction(e -> {
            TextInputDialog dlg = new TextInputDialog(
                    String.valueOf(java.time.Year.now().getValue() + 1));
            dlg.setTitle(t("labor.ssgroup.clone"));
            dlg.setHeaderText(t("labor.ssgroup.clone_prompt"));
            dlg.showAndWait().ifPresent(s -> {
                Integer ty = parseIntSafe(s);
                if (ty == null) return;
                Task<Void> tk = new Task<>() {
                    @Override protected Void call() throws Exception {
                        laborApiClient.cloneSsGroupBases(ty); return null;
                    }
                };
                tk.setOnSucceeded(ev -> { yearCombo.setValue(ty); loadYears.run(); });
                tk.setOnFailed(ev -> showError(t("labor.ssgroup.save_failed"),
                        tk.getException() == null ? "" : tk.getException().getMessage()));
                start(tk, "ssgroup-clone");
            });
        });

        HBox actions = new HBox(8, new Label(t("labor.ssgroup.year")), yearCombo, editBtn, cloneBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        loadYears.run();
        VBox body = new VBox(10, warn, hint, actions, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        content.getChildren().add(body);
        VBox.setVgrow(body, Priority.ALWAYS);
        return content;
    }

    private void showSsGroupBaseEditor(com.benjagest.ui.model.SsGroupBaseEntry base, int year,
            TableView<com.benjagest.ui.model.SsGroupBaseEntry> table, Runnable onSaved) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.ssgroup.edit") + " — " + t("labor.ssgroup.col.group") + " " + base.cotizGroup());
        ButtonType save = new ButtonType(t("labor.ssgroup.save_btn"), ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        TextField labelF = new TextField(base.label() == null ? "" : base.label());
        labelF.setPrefColumnCount(30);
        TextField minF = rateField(base.baseMin(), "0");
        TextField maxF = rateField(base.baseMax(), "0");
        CheckBox dailyC = new CheckBox();
        dailyC.setSelected(base.daily());
        CheckBox validC = new CheckBox(t("labor.ssgroup.edit_pending"));
        validC.setSelected(!base.pendingValidation());

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12));
        int r = 0;
        g.add(new Label(t("labor.ssgroup.col.label")), 0, r); g.add(labelF, 1, r++);
        g.add(new Label(t("labor.ssgroup.col.basemin")), 0, r); g.add(minF, 1, r++);
        g.add(new Label(t("labor.ssgroup.col.basemax")), 0, r); g.add(maxF, 1, r++);
        g.add(new Label(t("labor.ssgroup.col.daily")), 0, r); g.add(dailyC, 1, r++);
        g.add(new Label(t("labor.ssgroup.col.pending")), 0, r); g.add(validC, 1, r++);
        installDialog(d, g);

        d.showAndWait().ifPresent(bt -> {
            if (bt != save) return;
            var updated = new com.benjagest.ui.model.SsGroupBaseEntry(
                    year, base.cotizGroup(), labelF.getText(),
                    parseDecSafe(minF.getText()), parseDecSafe(maxF.getText()),
                    dailyC.isSelected(), !validC.isSelected());
            // Persistimos el año completo reemplazando la fila editada.
            var items = new java.util.ArrayList<>(table.getItems());
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).cotizGroup() == base.cotizGroup()) { items.set(i, updated); break; }
            }
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.saveSsGroupBases(year, items); return null;
                }
            };
            tk.setOnSucceeded(ev -> onSaved.run());
            tk.setOnFailed(ev -> showError(t("labor.ssgroup.save_failed"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "ssgroup-save");
        });
    }

    public Node buildIrpfParamsTab() {
        VBox content = new VBox(12);
        content.setPadding(new Insets(16));
        Label hint = new Label(t("labor.irpfparams.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        ComboBox<Integer> yearCombo = new ComboBox<>();
        TableView<double[]> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.irpfparams.empty")));
        TableColumn<double[], String> cFrom = new TableColumn<>(t("labor.irpfparams.from"));
        cFrom.setCellValueFactory(c -> new SimpleStringProperty(money(java.math.BigDecimal.valueOf(c.getValue()[0]))));
        cFrom.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<double[], String> cRate = new TableColumn<>(t("labor.irpfparams.rate"));
        cRate.setCellValueFactory(c -> new SimpleStringProperty(
                java.math.BigDecimal.valueOf(c.getValue()[1]).stripTrailingZeros().toPlainString().replace(".", ",") + " %"));
        cRate.setComparator(NUMERIC_STRING_COMPARATOR);
        table.getColumns().addAll(java.util.List.of(cFrom, cRate));

        Runnable loadBrackets = () -> {
            Integer y = yearCombo.getValue();
            if (y == null) return;
            Task<java.util.List<double[]>> tk = new Task<>() {
                @Override protected java.util.List<double[]> call() throws Exception {
                    return laborApiClient.listIrpfBrackets(y);
                }
            };
            tk.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(tk.getValue())));
            tk.setOnFailed(ev -> showError(t("labor.irpfparams.load_failed"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "irpfparams-brackets");
        };
        yearCombo.valueProperty().addListener((o, ov, nv) -> loadBrackets.run());

        Runnable loadYears = () -> {
            Task<java.util.List<Integer>> tk = new Task<>() {
                @Override protected java.util.List<Integer> call() throws Exception {
                    return laborApiClient.listIrpfYears();
                }
            };
            tk.setOnSucceeded(ev -> {
                Integer prev = yearCombo.getValue();
                yearCombo.getItems().setAll(tk.getValue());
                if (prev != null && tk.getValue().contains(prev)) yearCombo.getSelectionModel().select(prev);
                else if (!tk.getValue().isEmpty()) yearCombo.getSelectionModel().selectFirst();
                loadBrackets.run();
            });
            tk.setOnFailed(ev -> showError(t("labor.irpfparams.load_failed"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "irpfparams-years");
        };

        Button cloneBtn = new Button(t("labor.irpfparams.clone"));
        cloneBtn.getStyleClass().add("button-primary");
        cloneBtn.setOnAction(e -> {
            int next = (yearCombo.getItems().isEmpty() ? java.time.Year.now().getValue()
                    : yearCombo.getItems().get(0)) + 1;
            TextInputDialog ask = new TextInputDialog(String.valueOf(next));
            ask.setTitle(t("labor.irpfparams.clone"));
            ask.setHeaderText(t("labor.irpfparams.clone_prompt"));
            ask.showAndWait().ifPresent(s -> {
                Integer y = parseIntSafe(s);
                if (y == null) return;
                Task<Void> tk = new Task<>() {
                    @Override protected Void call() throws Exception { laborApiClient.cloneIrpfYear(y); return null; }
                };
                tk.setOnSucceeded(ev -> { yearCombo.getSelectionModel().clearSelection(); loadYears.run(); });
                tk.setOnFailed(ev -> showError(t("labor.irpfparams.clone"),
                        humanizeBackendError(tk.getException() == null ? "" : tk.getException().getMessage())));
                start(tk, "irpfparams-clone");
            });
        });
        Button editBtn = new Button(t("labor.irpfparams.edit"));
        editBtn.getStyleClass().add("button-secondary");
        editBtn.setOnAction(e -> {
            Integer y = yearCombo.getValue();
            if (y != null) showIrpfBracketsEditor(y, new java.util.ArrayList<>(table.getItems()), loadBrackets);
        });
        Button paramsBtn = new Button(t("labor.irpfp.edit_btn"));
        paramsBtn.getStyleClass().add("button-secondary");
        paramsBtn.setOnAction(e -> {
            Integer y = yearCombo.getValue();
            if (y != null) showIrpfParamsEditor(y);
        });
        HBox actions = new HBox(8, new Label(t("labor.ssrates.col.year")), yearCombo, editBtn, paramsBtn, cloneBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        loadYears.run();
        VBox body = new VBox(10, hint, actions, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        content.getChildren().add(body);
        VBox.setVgrow(body, Priority.ALWAYS);
        return content;
    }

    private void showIrpfBracketsEditor(int year, java.util.List<double[]> initial, Runnable onSaved) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.irpfparams.edit") + " — " + year);
        ButtonType save = new ButtonType(t("labor.ssrates.save_btn"), ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        VBox rows = new VBox(6);
        java.util.List<TextField[]> fields = new java.util.ArrayList<>();
        java.util.function.BiConsumer<Double, Double> addRow = (lo, ra) -> {
            TextField fLo = new TextField(lo == null ? "" : java.math.BigDecimal.valueOf(lo).toPlainString());
            fLo.setPromptText(t("labor.irpfparams.from")); fLo.setPrefWidth(140);
            TextField fRa = new TextField(ra == null ? "" : java.math.BigDecimal.valueOf(ra).toPlainString());
            fRa.setPromptText(t("labor.irpfparams.rate")); fRa.setPrefWidth(90);
            TextField[] pair = {fLo, fRa};
            fields.add(pair);
            Button del = new Button("✕"); del.getStyleClass().add("button-secondary");
            HBox row = new HBox(8, fLo, fRa, del);
            row.setAlignment(Pos.CENTER_LEFT);
            del.setOnAction(e -> { fields.remove(pair); rows.getChildren().remove(row); });
            rows.getChildren().add(row);
        };
        for (double[] b : initial) addRow.accept(b[0], b[1]);
        Button addBtn = new Button(t("labor.irpfparams.add_row"));
        addBtn.getStyleClass().add("button-secondary");
        addBtn.setOnAction(e -> addRow.accept(0.0, 19.0));

        VBox box = new VBox(10, new Label(t("labor.irpfparams.edit_hint")), rows, addBtn);
        box.setPadding(new Insets(12));
        installDialog(d, box);
        d.showAndWait().ifPresent(bt -> {
            if (bt != save) return;
            java.util.List<double[]> out = new java.util.ArrayList<>();
            for (TextField[] p : fields) {
                java.math.BigDecimal lo = parseDecSafe(p[0].getText());
                java.math.BigDecimal ra = parseDecSafe(p[1].getText());
                if (ra == null) continue;
                out.add(new double[]{lo == null ? 0 : lo.doubleValue(), ra.doubleValue()});
            }
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception { laborApiClient.saveIrpfBrackets(year, out); return null; }
            };
            tk.setOnSucceeded(ev -> onSaved.run());
            tk.setOnFailed(ev -> showError(t("labor.irpfparams.edit"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "irpfparams-save");
        });
    }

    private void showIrpfParamsEditor(int year) {
        Task<java.util.Map<String, java.math.BigDecimal>> load = new Task<>() {
            @Override protected java.util.Map<String, java.math.BigDecimal> call() throws Exception {
                return laborApiClient.getIrpfParams(year);
            }
        };
        load.setOnSucceeded(ev -> buildIrpfParamsForm(year, load.getValue()));
        load.setOnFailed(ev -> showError(t("labor.irpfparams.load_failed"),
                load.getException() == null ? "" : load.getException().getMessage()));
        start(load, "irpf-params-load");
    }

    private void buildIrpfParamsForm(int year, java.util.Map<String, java.math.BigDecimal> values) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.irpfp.title") + " — " + year);
        ButtonType save = new ButtonType(t("labor.ssrates.save_btn"), ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        String[] keys = com.benjagest.ui.service.LaborApiClient.IRPF_PARAM_KEYS;
        java.util.Map<String, TextField> fields = new java.util.LinkedHashMap<>();
        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12));
        int row = 0, col = 0;
        for (String k : keys) {
            Label lbl = new Label(t("labor.irpfp.f." + k));
            lbl.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            java.math.BigDecimal v = values.get(k);
            TextField tf = new TextField(v == null ? "" : v.toPlainString());
            tf.setPrefWidth(110);
            fields.put(k, tf);
            g.add(lbl, col * 2, row);
            g.add(tf, col * 2 + 1, row);
            col++;
            if (col == 2) { col = 0; row++; }
        }
        Label hint = new Label(t("labor.irpfp.hint"));
        hint.getStyleClass().add("settings-hint");
        hint.setWrapText(true);
        VBox box = new VBox(10, hint, g);
        box.setPadding(new Insets(4));
        installDialog(d, box);
        d.showAndWait().ifPresent(bt -> {
            if (bt != save) return;
            java.util.Map<String, java.math.BigDecimal> out = new java.util.LinkedHashMap<>();
            for (String k : keys) out.put(k, parseDecSafe(fields.get(k).getText()));
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception { laborApiClient.saveIrpfParams(year, out); return null; }
            };
            tk.setOnSucceeded(ev -> showInfo(t("labor.irpfp.title"), t("labor.irpfp.saved")));
            tk.setOnFailed(ev -> showError(t("labor.irpfp.title"),
                    humanizeBackendError(tk.getException() == null ? "" : tk.getException().getMessage())));
            start(tk, "irpf-params-save");
        });
    }
}
