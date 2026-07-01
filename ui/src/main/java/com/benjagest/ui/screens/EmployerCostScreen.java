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
 * NOM-8 — Coste de empresa + Cotizaciones SS (categoría "Payroll" del módulo
 * Laboral, bloque UIR). Extraídas del God Object: reporte de coste por empleado
 * (bruto + SS a cargo de la empresa, bloque NOM) y listado de cotizaciones a la
 * Seguridad Social (TC1/RED, solo lectura salvo borrar DRAFT). Movimiento puro:
 * mismo comportamiento, mismas claves i18n. Depende de {@link LaborApiClient}
 * (coste), {@link AltaApiClient} (cotizaciones) y los helpers de
 * {@link ScreenBase}.
 */
public class EmployerCostScreen extends ScreenBase {

    private final LaborApiClient laborApiClient;
    private final AltaApiClient altaApiClient;

    public EmployerCostScreen(LaborApiClient laborApiClient, AltaApiClient altaApiClient,
                              Function<String, String> tt, Router router) {
        super(tt, router);
        this.laborApiClient = laborApiClient;
        this.altaApiClient = altaApiClient;
    }

    /**
     * Reporte de coste de empresa por empleado (bloque NOM). Coste anual
     * = bruto pagado + SS a cargo de la empresa (cuotas TC EMPLOYER_*).
     */
    public Node buildEmployerCostTab() {
        VBox content = new VBox(12);
        content.setPadding(new Insets(16));

        Label hint = new Label(t("labor.cost.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        int currentYear = java.time.Year.now().getValue();
        ComboBox<Integer> yearCombo = new ComboBox<>();
        for (int y = currentYear; y >= currentYear - 5; y--) yearCombo.getItems().add(y);
        yearCombo.setValue(currentYear);
        HBox filters = new HBox(8, new Label(t("labor.cost.filter.year")), yearCombo);
        filters.setAlignment(Pos.CENTER_LEFT);

        TableView<com.benjagest.ui.service.LaborApiClient.EmployerCostEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.cost.placeholder.empty")));
        com.benjagest.ui.support.TableSelectionHelper.install(table);

        TableColumn<com.benjagest.ui.service.LaborApiClient.EmployerCostEntry, String> cEmp =
                new TableColumn<>(t("labor.cost.col.employee"));
        cEmp.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().employeeName()));
        cEmp.setPrefWidth(240);

        TableColumn<com.benjagest.ui.service.LaborApiClient.EmployerCostEntry, String> cGross =
                new TableColumn<>(t("labor.cost.col.gross"));
        cGross.setCellValueFactory(c -> new SimpleStringProperty(money(c.getValue().grossTotal())));
        cGross.setPrefWidth(150);
        cGross.setComparator(NUMERIC_STRING_COMPARATOR);

        TableColumn<com.benjagest.ui.service.LaborApiClient.EmployerCostEntry, String> cSs =
                new TableColumn<>(t("labor.cost.col.employer_ss"));
        cSs.setCellValueFactory(c -> new SimpleStringProperty(money(c.getValue().employerSsTotal())));
        cSs.setPrefWidth(170);
        cSs.setComparator(NUMERIC_STRING_COMPARATOR);

        TableColumn<com.benjagest.ui.service.LaborApiClient.EmployerCostEntry, String> cCost =
                new TableColumn<>(t("labor.cost.col.total"));
        cCost.setCellValueFactory(c -> new SimpleStringProperty(money(c.getValue().costTotal())));
        cCost.setPrefWidth(170);
        cCost.setComparator(NUMERIC_STRING_COMPARATOR);

        table.getColumns().addAll(java.util.List.of(cEmp, cGross, cSs, cCost));

        Label totals = new Label();
        totals.getStyleClass().add("settings-hint");

        Runnable reload = () -> {
            Integer y = yearCombo.getValue();
            Task<java.util.List<com.benjagest.ui.service.LaborApiClient.EmployerCostEntry>> tk = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.service.LaborApiClient.EmployerCostEntry> call()
                        throws Exception {
                    return laborApiClient.employerCost(y == null ? currentYear : y);
                }
            };
            tk.setOnSucceeded(ev -> {
                var rows = tk.getValue();
                table.setItems(FXCollections.observableArrayList(rows));
                java.math.BigDecimal tGross = java.math.BigDecimal.ZERO;
                java.math.BigDecimal tSs = java.math.BigDecimal.ZERO;
                java.math.BigDecimal tCost = java.math.BigDecimal.ZERO;
                for (var rr : rows) {
                    if (rr.grossTotal() != null) tGross = tGross.add(rr.grossTotal());
                    if (rr.employerSsTotal() != null) tSs = tSs.add(rr.employerSsTotal());
                    if (rr.costTotal() != null) tCost = tCost.add(rr.costTotal());
                }
                totals.setText(t("labor.cost.totals")
                        .replace("{gross}", money(tGross))
                        .replace("{ss}", money(tSs))
                        .replace("{total}", money(tCost)));
            });
            tk.setOnFailed(ev -> showError(t("labor.cost.load_failed"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "labor-cost-load");
        };
        yearCombo.valueProperty().addListener((obs, oldV, newV) -> reload.run());
        reload.run();

        VBox body = new VBox(10, hint, filters, table, totals);
        VBox.setVgrow(table, Priority.ALWAYS);
        content.getChildren().add(body);
        VBox.setVgrow(body, Priority.ALWAYS);
        return content;
    }

    public Node buildSsContributionsTab(
            java.util.List<com.benjagest.ui.model.EmployeeEntry> employees) {
        VBox content = new VBox(12);
        content.setPadding(new Insets(16));

        java.util.Map<String, String> empById = new java.util.HashMap<>();
        for (var e : employees) empById.put(e.id(), e.fullName());

        Label hint = new Label(t("labor.ss.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        // Filtros año + mes + empleado.
        int currentYear = java.time.Year.now().getValue();
        ComboBox<Integer> yearCombo = new ComboBox<>();
        for (int y = currentYear; y >= currentYear - 5; y--) yearCombo.getItems().add(y);
        yearCombo.setValue(currentYear);

        ComboBox<Integer> monthCombo = new ComboBox<>();
        monthCombo.getItems().add(null);  // (todos)
        for (int m = 1; m <= 12; m++) monthCombo.getItems().add(m);
        monthCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Integer m) {
                return m == null ? t("labor.ss.filter.month_all") : String.format("%02d", m);
            }
            @Override public Integer fromString(String s) { return null; }
        });
        monthCombo.setValue(null);

        ComboBox<com.benjagest.ui.model.EmployeeEntry> empFilter = new ComboBox<>();
        empFilter.getItems().add(null);  // (todos)
        empFilter.getItems().addAll(employees);
        empFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.EmployeeEntry e) {
                return e == null ? t("labor.ss.filter.employee_all") : e.fullName();
            }
            @Override public com.benjagest.ui.model.EmployeeEntry fromString(String s) {
                return null;
            }
        });
        empFilter.setValue(null);

        HBox filters = new HBox(8,
                new Label(t("labor.ss.filter.year")), yearCombo,
                new Label(t("labor.ss.filter.month")), monthCombo,
                new Label(t("labor.ss.filter.employee")), empFilter);
        filters.setAlignment(Pos.CENTER_LEFT);

        TableView<com.benjagest.ui.model.SocialSecurityContributionEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.ss.placeholder.empty")));
        com.benjagest.ui.support.TableSelectionHelper.install(table);

        TableColumn<com.benjagest.ui.model.SocialSecurityContributionEntry, String> cPeriod =
                new TableColumn<>(t("labor.ss.col.period"));
        cPeriod.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().periodYear() + " · " + String.format("%02d", c.getValue().periodMonth())));
        cPeriod.setPrefWidth(100);

        TableColumn<com.benjagest.ui.model.SocialSecurityContributionEntry, String> cEmp =
                new TableColumn<>(t("labor.ss.col.scope"));
        cEmp.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().employeeId() == null
                        ? t("labor.ss.scope.company")
                        : empById.getOrDefault(c.getValue().employeeId(),
                                shortId(c.getValue().employeeId()))));
        cEmp.setPrefWidth(180);

        TableColumn<com.benjagest.ui.model.SocialSecurityContributionEntry, String> cType =
                new TableColumn<>(t("labor.ss.col.type"));
        cType.setCellValueFactory(c -> new SimpleStringProperty(
                t("labor.ss.type." + c.getValue().contributionType())));
        cType.setPrefWidth(220);

        TableColumn<com.benjagest.ui.model.SocialSecurityContributionEntry, String> cBase =
                new TableColumn<>(t("labor.ss.col.base"));
        cBase.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().baseAmount() == null ? ""
                        : c.getValue().baseAmount().toPlainString() + " €"));
        cBase.setPrefWidth(110);
        cBase.setComparator(NUMERIC_STRING_COMPARATOR);

        TableColumn<com.benjagest.ui.model.SocialSecurityContributionEntry, String> cAmt =
                new TableColumn<>(t("labor.ss.col.amount"));
        cAmt.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().contributionAmount() == null ? ""
                        : c.getValue().contributionAmount().toPlainString() + " €"));
        cAmt.setPrefWidth(110);
        cAmt.setComparator(NUMERIC_STRING_COMPARATOR);

        TableColumn<com.benjagest.ui.model.SocialSecurityContributionEntry, String> cStatus =
                new TableColumn<>(t("labor.ss.col.status"));
        cStatus.setCellValueFactory(c -> new SimpleStringProperty(
                t("labor.ss.status." + c.getValue().status())));
        cStatus.setPrefWidth(100);

        table.getColumns().addAll(java.util.List.of(cPeriod, cEmp, cType, cBase, cAmt, cStatus));

        Runnable reload = () -> {
            Integer y = yearCombo.getValue();
            Integer m = monthCombo.getValue();
            String empId = empFilter.getValue() == null ? null : empFilter.getValue().id();
            Task<java.util.List<com.benjagest.ui.model.SocialSecurityContributionEntry>> tk = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.SocialSecurityContributionEntry> call()
                        throws Exception {
                    return altaApiClient.listSocialSecurityContributions(y, m, empId);
                }
            };
            tk.setOnSucceeded(ev -> table.setItems(
                    FXCollections.observableArrayList(tk.getValue())));
            tk.setOnFailed(ev -> showError(t("labor.ss.load_failed"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "labor-ss-load");
        };
        yearCombo.valueProperty().addListener((obs, oldV, newV) -> reload.run());
        monthCombo.valueProperty().addListener((obs, oldV, newV) -> reload.run());
        empFilter.valueProperty().addListener((obs, oldV, newV) -> reload.run());
        reload.run();

        Button delBtn = new Button(t("labor.ss.btn.delete"));
        delBtn.setGraphic(icon("fas-trash"));
        delBtn.setDisable(true);
        delBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(t("labor.ss.confirm.delete.title"));
            confirm.setHeaderText(t("labor.ss.confirm.delete.body"));
            confirm.showAndWait().ifPresent(rsp -> {
                if (rsp == javafx.scene.control.ButtonType.OK) {
                    Task<Void> del = new Task<>() {
                        @Override protected Void call() throws Exception {
                            altaApiClient.deleteSocialSecurityContribution(sel.id());
                            return null;
                        }
                    };
                    del.setOnSucceeded(s -> reload.run());
                    del.setOnFailed(s -> showError(t("labor.ss.delete_failed"),
                            del.getException() == null ? "" : del.getException().getMessage()));
                    start(del, "labor-ss-delete");
                }
            });
        });

        // Solo permite eliminar DRAFT (backend lo bloquea con 409 si !=DRAFT).
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            delBtn.setDisable(newV == null
                    || !com.benjagest.ui.model.SocialSecurityContributionEntry.STATUS_DRAFT
                            .equals(newV.status()));
        });

        // Totales en footer — útil para verificación rápida contra TC1.
        Label totalsLbl = new Label();
        totalsLbl.getStyleClass().add("settings-hint");
        Runnable recompute = () -> {
            int n = table.getItems().size();
            java.math.BigDecimal sumBase = java.math.BigDecimal.ZERO;
            java.math.BigDecimal sumCuota = java.math.BigDecimal.ZERO;
            for (var row : table.getItems()) {
                if (row.baseAmount() != null) sumBase = sumBase.add(row.baseAmount());
                if (row.contributionAmount() != null) sumCuota = sumCuota.add(row.contributionAmount());
            }
            totalsLbl.setText(t("labor.ss.totals")
                    .replace("{n}", String.valueOf(n))
                    .replace("{base}", sumBase.toPlainString())
                    .replace("{cuota}", sumCuota.toPlainString()));
        };
        table.getItems().addListener(
                (javafx.collections.ListChangeListener<com.benjagest.ui.model.SocialSecurityContributionEntry>) c
                        -> recompute.run());
        recompute.run();

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox actions = new HBox(8, totalsLbl, footerSpacer, delBtn);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow(table, Priority.ALWAYS);
        content.getChildren().addAll(hint, filters, table, actions);
        return content;
    }
}
