package com.benjagest.ui.screens;

import com.benjagest.ui.model.*;
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
 * AS-2 — Pestaña "Configuración" de la ficha de cliente: datos de gestión
 * (forma jurídica, periodo fiscal, régimen, contacto, notas, políticas
 * contables) + cifras manuales (ingresos/gastos/resultado por año/trimestre,
 * para clientes sin contabilidad completa). Extraída del God Object.
 *
 * <p>El shell conserva {@code buildClientConfigTab()} como wrapper. El único
 * enganche cruzado (refrescar los perfiles RETA al marcar AUTÓNOMO) se inyecta
 * vía {@link Host}. Movido tal cual; reusa data-table/settings-* del CSS.
 */
public class ClientConfigScreen extends ScreenBase {

    /** Enganches cruzados que viven en el shell. */
    public interface Host {
        /** Refresca la tabla de perfiles RETA (tras crear el perfil al marcar AUTÓNOMO). */
        void reloadRetaProfiles();
    }

    private final LaborApiClient laborApiClient;
    private final Host host;

    public ClientConfigScreen(LaborApiClient laborApiClient,
                              Function<String, String> tt, Router router, Host host) {
        super(tt, router);
        this.laborApiClient = laborApiClient;
        this.host = host;
    }

    public Node buildTab() {
        VBox holder = new VBox(14);
        holder.setPadding(new Insets(16));
        Label loading = new Label(t("panorama.loading"));
        loading.getStyleClass().add("settings-hint");
        holder.getChildren().add(loading);

        Task<com.benjagest.ui.model.ClientConfigModels.AdvisoryConfigEntry> cfgTask = new Task<>() {
            @Override protected com.benjagest.ui.model.ClientConfigModels.AdvisoryConfigEntry call() throws Exception {
                return laborApiClient.getClientAdvisoryConfig();
            }
        };
        cfgTask.setOnSucceeded(ev -> holder.getChildren().setAll(
                buildClientConfigContent(cfgTask.getValue())));
        cfgTask.setOnFailed(ev -> holder.getChildren().setAll(
                buildClientConfigContent(new com.benjagest.ui.model.ClientConfigModels.AdvisoryConfigEntry(
                        null, null, null, null, null, null, true, true))));
        start(cfgTask, "client-config-load");
        return holder;
    }

    private VBox buildClientConfigContent(com.benjagest.ui.model.ClientConfigModels.AdvisoryConfigEntry cfg) {
        // --- Sección 1: datos de gestión ---
        Label t1 = new Label(t("clientcfg.section.management"));
        t1.getStyleClass().add("settings-section-title");

        ComboBox<String> legalForm = localizedConfigCombo("clientcfg.legalform.",
                java.util.List.of("AUTONOMO", "SL", "SA", "SLU", "SC", "CB", "COOPERATIVA", "OTRO"),
                cfg.legalForm());
        Label legalFormHint = new Label("");
        legalFormHint.getStyleClass().add("settings-hint");
        legalFormHint.setWrapText(true);
        Runnable updLegalHint = () -> legalFormHint.setText(
                "AUTONOMO".equals(legalForm.getValue()) ? t("clientcfg.legalform.hint_autonomo")
                        : (legalForm.getValue() == null ? "" : t("clientcfg.legalform.hint_society")));
        legalForm.valueProperty().addListener((o, a, b) -> updLegalHint.run());
        updLegalHint.run();

        // V126 — política contable: provisionar pagas extra mensualmente (devengo).
        CheckBox provisionExtra = new CheckBox(t("clientcfg.provision_extra_pay"));
        provisionExtra.setSelected(cfg.provisionExtraPay());
        Label provisionHint = new Label(t("clientcfg.provision_extra_pay.hint"));
        provisionHint.getStyleClass().add("settings-hint");
        provisionHint.setWrapText(true);

        // REFLEJO-6 — interruptor del reflejo automático factura↔gasto.
        CheckBox reflejoAuto = new CheckBox(t("clientcfg.reflejo_auto"));
        reflejoAuto.setSelected(cfg.reflejoAutoEnabled());
        Label reflejoHint = new Label(t("clientcfg.reflejo_auto.hint"));
        reflejoHint.getStyleClass().add("settings-hint");
        reflejoHint.setWrapText(true);

        ComboBox<String> fiscalPeriod = localizedConfigCombo("clientcfg.fiscalperiod.",
                java.util.List.of("MONTHLY", "QUARTERLY"), cfg.fiscalPeriod());
        ComboBox<String> taxRegime = localizedConfigCombo("clientcfg.regime.",
                java.util.List.of("ESTIMACION_DIRECTA", "MODULOS", "SOCIEDADES", "OTHER"), cfg.taxRegime());
        ComboBox<String> contactChannel = localizedConfigCombo("clientcfg.contact.",
                java.util.List.of("EMAIL", "PHONE", "WHATSAPP", "IN_PERSON", "OTHER"), cfg.contactChannel());
        TextField contactValue = new TextField(cfg.contactValue() == null ? "" : cfg.contactValue());
        TextArea internalNotes = new TextArea(cfg.internalNotes() == null ? "" : cfg.internalNotes());
        internalNotes.setPrefRowCount(3); internalNotes.setWrapText(true);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8);
        javafx.scene.layout.ColumnConstraints gl = new javafx.scene.layout.ColumnConstraints();
        gl.setMinWidth(180); gl.setHalignment(javafx.geometry.HPos.LEFT);
        javafx.scene.layout.ColumnConstraints gf = new javafx.scene.layout.ColumnConstraints();
        gf.setHgrow(Priority.ALWAYS); gf.setFillWidth(true);
        g.getColumnConstraints().addAll(gl, gf);
        int r = 0;
        g.add(new Label(t("clientcfg.legal_form")), 0, r); g.add(legalForm, 1, r++);
        g.add(legalFormHint, 1, r++);
        g.add(new Label(t("clientcfg.fiscal_period")), 0, r); g.add(fiscalPeriod, 1, r++);
        g.add(new Label(t("clientcfg.tax_regime")), 0, r); g.add(taxRegime, 1, r++);
        g.add(new Label(t("clientcfg.contact_channel")), 0, r); g.add(contactChannel, 1, r++);
        g.add(new Label(t("clientcfg.contact_value")), 0, r); g.add(contactValue, 1, r++);
        g.add(new Label(t("clientcfg.internal_notes")), 0, r); g.add(internalNotes, 1, r++);
        g.add(provisionExtra, 1, r++);
        g.add(provisionHint, 1, r++);
        g.add(reflejoAuto, 1, r++);
        g.add(reflejoHint, 1, r++);

        Button saveCfg = new Button(t("common.btn.save"));
        saveCfg.getStyleClass().add("button-primary");
        Label cfgSaved = new Label("");
        cfgSaved.getStyleClass().add("settings-hint");
        saveCfg.setOnAction(e -> {
            var payload = new com.benjagest.ui.model.ClientConfigModels.AdvisoryConfigEntry(
                    fiscalPeriod.getValue(), taxRegime.getValue(), contactChannel.getValue(),
                    blankToNullOrSelf(contactValue.getText()), blankToNullOrSelf(internalNotes.getText()),
                    legalForm.getValue(), provisionExtra.isSelected(), reflejoAuto.isSelected());
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception { laborApiClient.saveClientAdvisoryConfig(payload); return null; }
            };
            tk.setOnSucceeded(ev -> {
                cfgSaved.setText(t("clientcfg.saved"));
                // RETA-4: si se marcó AUTONOMO, crear el perfil RETA y refrescar
                // la tabla de perfiles (que pudo construirse antes de marcarlo).
                Task<Void> ensure = new Task<>() {
                    @Override protected Void call() throws Exception { laborApiClient.ensureRetaProfiles(); return null; }
                };
                ensure.setOnSucceeded(e2 -> host.reloadRetaProfiles());
                start(ensure, "client-config-ensure-reta");
            });
            tk.setOnFailed(ev -> showError(t("clientcfg.save_failed"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "client-config-save");
        });
        HBox saveRow = new HBox(8, saveCfg, cfgSaved);
        saveRow.setAlignment(Pos.CENTER_LEFT);

        // --- Sección 2: cifras manuales ---
        Label t2 = new Label(t("clientcfg.section.financials"));
        t2.getStyleClass().add("settings-section-title");
        Label t2hint = new Label(t("clientcfg.financials.hint"));
        t2hint.setWrapText(true); t2hint.getStyleClass().add("settings-hint");

        TableView<com.benjagest.ui.model.ClientConfigModels.ManualFinancialEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("clientcfg.financials.empty")));
        TableColumn<com.benjagest.ui.model.ClientConfigModels.ManualFinancialEntry, String> cYear =
                new TableColumn<>(t("clientcfg.col.year"));
        cYear.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().periodYear())));
        cYear.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.ClientConfigModels.ManualFinancialEntry, String> cPeriod =
                new TableColumn<>(t("clientcfg.col.period"));
        cPeriod.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().periodQuarter() == 0 ? t("clientcfg.period.annual")
                        : "T" + c.getValue().periodQuarter()));
        TableColumn<com.benjagest.ui.model.ClientConfigModels.ManualFinancialEntry, String> cInc =
                new TableColumn<>(t("clientcfg.col.income"));
        cInc.setCellValueFactory(c -> new SimpleStringProperty(money(c.getValue().income())));
        cInc.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.ClientConfigModels.ManualFinancialEntry, String> cExp =
                new TableColumn<>(t("clientcfg.col.expenses"));
        cExp.setCellValueFactory(c -> new SimpleStringProperty(money(c.getValue().expenses())));
        cExp.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.ClientConfigModels.ManualFinancialEntry, String> cRes =
                new TableColumn<>(t("clientcfg.col.result"));
        cRes.setCellValueFactory(c -> new SimpleStringProperty(money(manualResult(c.getValue()))));
        cRes.setComparator(NUMERIC_STRING_COMPARATOR);
        table.getColumns().addAll(java.util.List.of(cYear, cPeriod, cInc, cExp, cRes));

        Runnable reload = () -> {
            Task<java.util.List<com.benjagest.ui.model.ClientConfigModels.ManualFinancialEntry>> tk = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.ClientConfigModels.ManualFinancialEntry> call() throws Exception {
                    return laborApiClient.listClientFinancials(null);
                }
            };
            tk.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(tk.getValue())));
            tk.setOnFailed(ev -> { /* tabla vacía */ });
            start(tk, "client-financials-load");
        };

        Button addBtn = new Button(t("clientcfg.financials.add"));
        addBtn.setGraphic(icon("fas-plus"));
        addBtn.setOnAction(e -> showManualFinancialEditor(null, reload));
        Button editBtn = new Button(t("common.btn.edit"));
        editBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) showManualFinancialEditor(sel, reload);
        });
        Button delBtn = new Button(t("common.btn.delete"));
        delBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception { laborApiClient.deleteClientFinancial(sel.id()); return null; }
            };
            tk.setOnSucceeded(ev -> reload.run());
            tk.setOnFailed(ev -> showError(t("clientcfg.save_failed"), ""));
            start(tk, "client-financial-del");
        });
        HBox finActions = new HBox(8, addBtn, editBtn, delBtn);
        finActions.setAlignment(Pos.CENTER_LEFT);

        reload.run();

        VBox box = new VBox(12, t1, g, saveRow, new Separator(), t2, t2hint, finActions, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    private java.math.BigDecimal manualResult(com.benjagest.ui.model.ClientConfigModels.ManualFinancialEntry f) {
        if (f.netResult() != null) return f.netResult();
        java.math.BigDecimal inc = f.income() == null ? java.math.BigDecimal.ZERO : f.income();
        java.math.BigDecimal exp = f.expenses() == null ? java.math.BigDecimal.ZERO : f.expenses();
        return inc.subtract(exp);
    }

    /** ComboBox con valores fijos localizados via t(prefix + value). */
    private ComboBox<String> localizedConfigCombo(String prefix, java.util.List<String> values, String selected) {
        ComboBox<String> cb = new ComboBox<>();
        cb.getItems().addAll(values);
        cb.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String v) { return v == null ? "" : t(prefix + v); }
            @Override public String fromString(String s) { return s; }
        });
        cb.setMaxWidth(Double.MAX_VALUE);
        if (selected != null && values.contains(selected)) cb.setValue(selected);
        return cb;
    }

    private void showManualFinancialEditor(com.benjagest.ui.model.ClientConfigModels.ManualFinancialEntry existing,
                                            Runnable onSaved) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("clientcfg.financials.add"));
        ButtonType save = new ButtonType(t("common.btn.save"), ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        TextField year = new TextField(existing == null ? String.valueOf(java.time.Year.now().getValue() - 1)
                : String.valueOf(existing.periodYear()));
        ComboBox<Integer> quarter = new ComboBox<>();
        quarter.getItems().addAll(0, 1, 2, 3, 4);
        quarter.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Integer q) { return q == null || q == 0 ? t("clientcfg.period.annual") : "T" + q; }
            @Override public Integer fromString(String s) { return 0; }
        });
        quarter.setValue(existing == null ? 0 : existing.periodQuarter());
        TextField income = new TextField(existing == null || existing.income() == null ? "" : existing.income().toPlainString());
        TextField expenses = new TextField(existing == null || existing.expenses() == null ? "" : existing.expenses().toPlainString());
        TextField netResult = new TextField(existing == null || existing.netResult() == null ? "" : existing.netResult().toPlainString());
        netResult.setPromptText(t("clientcfg.result_auto"));

        GridPane g = new GridPane(); g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12));
        int r = 0;
        g.add(new Label(t("clientcfg.col.year")), 0, r); g.add(year, 1, r);
        g.add(new Label(t("clientcfg.col.period")), 2, r); g.add(quarter, 3, r++);
        g.add(new Label(t("clientcfg.col.income")), 0, r); g.add(income, 1, r);
        g.add(new Label(t("clientcfg.col.expenses")), 2, r); g.add(expenses, 3, r++);
        g.add(new Label(t("clientcfg.col.result")), 0, r); g.add(netResult, 1, r++);
        installDialog(d, g);
        d.showAndWait().ifPresent(bt -> {
            if (bt != save) return;
            Integer y = parseIntSafe(year.getText());
            if (y == null) return;
            var payload = new com.benjagest.ui.model.ClientConfigModels.ManualFinancialEntry(
                    existing == null ? null : existing.id(), y,
                    quarter.getValue() == null ? 0 : quarter.getValue(),
                    parseDecSafe(income.getText()), parseDecSafe(expenses.getText()),
                    parseDecSafe(netResult.getText()), null);
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception { laborApiClient.upsertClientFinancial(payload); return null; }
            };
            tk.setOnSucceeded(ev -> onSaved.run());
            tk.setOnFailed(ev -> showError(t("clientcfg.save_failed"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "client-financial-save");
        });
    }
}
