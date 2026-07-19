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
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/**
 * NOM-7 — Nómina (sub-pestaña "Payroll" del módulo Laboral, bloque UIR). El
 * corazón del bloque: listado de recibos + cálculo de nómina (mensual, extra,
 * bonus, finiquito), generación mensual en lote, replicar a sueldo objetivo,
 * pagas extra, incidencias del periodo, y las acciones por recibo (pagar,
 * entrega/acuse, PDF, email, borrar). Movimiento puro: mismo comportamiento,
 * mismas claves i18n. Depende de {@link LaborApiClient}, un callback
 * {@code refreshLaborAndJournal} (las acciones que crean/revierten asientos
 * refrescan Labor + Contabilidad) y {@code refreshLabor} (entrega, que no toca
 * asientos). El owner del FileChooser del PDF era el {@code root} del shell;
 * ahora se resuelve por el nodo montado ({@code viewRoot}).
 */
public class PayslipsScreen extends ScreenBase {

    private final LaborApiClient laborApiClient;
    private final Runnable refreshLaborAndJournalCb;
    private final Runnable refreshLabor;
    private TableView<com.benjagest.ui.model.PayslipEntry> payslipsTable;
    private Node viewRoot;

    /** Datos del módulo Labor que consume la pantalla de nómina (subconjunto del bundle del shell). */
    public record PayrollData(
            java.util.List<com.benjagest.ui.model.EmployeeEntry> employees,
            java.util.List<com.benjagest.ui.model.ContractEntry> contracts,
            java.util.List<com.benjagest.ui.model.PayslipEntry> payslips,
            int currentYear) {}

    public PayslipsScreen(LaborApiClient laborApiClient, Function<String, String> tt, Router router,
                          Runnable refreshLaborAndJournal, Runnable refreshLabor) {
        super(tt, router);
        this.laborApiClient = laborApiClient;
        this.refreshLaborAndJournalCb = refreshLaborAndJournal;
        this.refreshLabor = refreshLabor;
    }

    /** Refresca Labor + Contabilidad (acciones de nómina que tocan asientos). */
    private void refreshLaborAndJournal() { refreshLaborAndJournalCb.run(); }

    private javafx.stage.Window window() {
        return viewRoot == null || viewRoot.getScene() == null
                ? null : viewRoot.getScene().getWindow();
    }

    // ----- Helpers compartidos (copiados del shell) -----

    private static String humanizeBackendError(String raw) {
        return com.benjagest.ui.support.BackendErrors.humanize(raw);
    }

    private void highlightMissing(javafx.scene.control.Control field) {
        if (field == null) return;
        if (!field.getStyleClass().contains("field-error")) {
            field.getStyleClass().add("field-error");
        }
        field.requestFocus();
    }

    private void clearMissingOnChange(javafx.scene.control.ComboBox<?> combo) {
        if (combo == null) return;
        combo.valueProperty().addListener((o, ov, nv) -> {
            if (nv != null) combo.getStyleClass().remove("field-error");
        });
    }

    /** StringConverter que traduce un código de enum con prefijo i18n ({@code prefix.CODE}). */
    private javafx.util.StringConverter<String> localizedConverter(String prefix) {
        return new javafx.util.StringConverter<>() {
            @Override public String toString(String code) {
                return code == null ? "" : t(prefix + "." + code);
            }
            @Override public String fromString(String s) { return s; }
        };
    }

    /** Etiqueta de la columna "Entrega": firmada > entregada > pendiente. */
    private String payslipDeliveryLabel(com.benjagest.ui.model.PayslipEntry p) {
        if (p.acknowledgedAt() != null && !p.acknowledgedAt().isBlank()) {
            return t("labor.payslips.delivery.signed") + " " + p.acknowledgedAt();
        }
        if (p.deliveredAt() != null && !p.deliveredAt().isBlank()) {
            String m = (p.deliveryMethod() == null || p.deliveryMethod().isBlank())
                    ? "" : " (" + t("labor.payslips.delivery.method." + p.deliveryMethod()) + ")";
            return t("labor.payslips.delivery.delivered") + " " + p.deliveredAt() + m;
        }
        return "—";
    }

    // ===================================================================
    //  Listado de nóminas + acciones
    // ===================================================================

    public Node buildPayslipsTab(PayrollData bundle) {
        java.util.Map<String, String> empById = new java.util.HashMap<>();
        for (var e : bundle.employees()) empById.put(e.id(), e.fullName());

        payslipsTable = new TableView<>();
        payslipsTable.getStyleClass().add("data-table");
        payslipsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        payslipsTable.setPlaceholder(new Label(t("labor.payslips.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cPeriod =
                new TableColumn<>(t("labor.payslips.col.period"));
        cPeriod.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().periodYear() + " · " + String.format("%02d", c.getValue().periodMonth())));
        cPeriod.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cEmp =
                new TableColumn<>(t("labor.payslips.col.employee"));
        cEmp.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().employeeName() == null || c.getValue().employeeName().isBlank()
                        ? empById.getOrDefault(c.getValue().employeeId(), shortId(c.getValue().employeeId()))
                        : c.getValue().employeeName()));
        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cType =
                new TableColumn<>(t("labor.payslips.col.type"));
        cType.setCellValueFactory(c -> new SimpleStringProperty(t("labor.payslips.type." + c.getValue().payslipType())));
        cType.setPrefWidth(120);
        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cGross =
                new TableColumn<>(t("labor.payslips.col.gross"));
        cGross.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().grossAmount() == null ? "" : c.getValue().grossAmount().toPlainString() + " €"));
        cGross.setPrefWidth(110);
        cGross.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cSs =
                new TableColumn<>(t("labor.payslips.col.ss"));
        cSs.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().ssEmployeeAmount() == null ? "" : c.getValue().ssEmployeeAmount().toPlainString() + " €"));
        cSs.setPrefWidth(100);
        cSs.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cIrpf =
                new TableColumn<>(t("labor.payslips.col.irpf"));
        cIrpf.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().irpfAmount() == null ? "" : c.getValue().irpfAmount().toPlainString() + " €"));
        cIrpf.setPrefWidth(100);
        cIrpf.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cNet =
                new TableColumn<>(t("labor.payslips.col.net"));
        cNet.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().netAmount() == null ? "" : c.getValue().netAmount().toPlainString() + " €"));
        cNet.setPrefWidth(110);
        cNet.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cStatus =
                new TableColumn<>(t("labor.payslips.col.status"));
        cStatus.setCellValueFactory(c -> new SimpleStringProperty(t("labor.payslips.status." + c.getValue().status())));
        cStatus.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cDelivery =
                new TableColumn<>(t("labor.payslips.col.delivery"));
        cDelivery.setCellValueFactory(c -> new SimpleStringProperty(payslipDeliveryLabel(c.getValue())));
        cDelivery.setPrefWidth(150);
        payslipsTable.getColumns().addAll(java.util.List.of(cPeriod, cEmp, cType, cGross, cSs, cIrpf, cNet, cStatus, cDelivery));
        payslipsTable.setItems(FXCollections.observableArrayList(bundle.payslips()));

        Button calcBtn = new Button(t("labor.payslips.action.calculate"));
        calcBtn.setGraphic(icon("fas-calculator"));
        calcBtn.setOnAction(ev -> {
            // Solo se puede nominar a empleados con contrato. Esto excluye
            // filas sintéticas como la del OWNER (creada solo para login PIN),
            // que no tienen contrato y no son nominables.
            java.util.Set<String> withContract = bundle.contracts().stream()
                    .map(com.benjagest.ui.model.ContractEntry::employeeId)
                    .collect(java.util.stream.Collectors.toSet());
            var payrollEmployees = bundle.employees().stream()
                    .filter(e -> withContract.contains(e.id())).toList();
            showCalculatePayslipDialog(payrollEmployees, bundle);
        });

        Button genMonthBtn = new Button(t("labor.payslips.action.gen_month"));
        genMonthBtn.setGraphic(icon("fas-calendar-check"));
        genMonthBtn.setOnAction(ev -> showGenerateMonthDialog());

        Button batchBtn = new Button(t("labor.payslips.action.batch"));
        batchBtn.setGraphic(icon("fas-layer-group"));
        batchBtn.setOnAction(ev -> showBatchTargetDialog(bundle));

        Button extraBtn = new Button(t("labor.payslips.action.extra"));
        extraBtn.setGraphic(icon("fas-gift"));
        extraBtn.setOnAction(ev -> showExtraPagaDialog(bundle));

        Button settlementBtn = new Button(t("labor.payslips.action.settlement"));
        settlementBtn.setGraphic(icon("fas-handshake"));
        settlementBtn.setOnAction(ev -> showSettlementDialog(bundle));

        Button ssBtn = new Button(t("labor.payslips.action.ss"));
        ssBtn.setGraphic(icon("fas-building-columns"));
        ssBtn.setOnAction(ev -> showSocialSecurityDialog(bundle.currentYear()));

        Button payBtn = new Button(t("labor.payslips.action.pay"));
        payBtn.setGraphic(icon("fas-money-check-alt"));
        payBtn.setDisable(true);
        payBtn.setOnAction(ev -> {
            var sel = payslipsTable.getSelectionModel().getSelectedItem();
            if (sel != null) markPayslipPaid(sel);
        });

        Button deliverBtn = new Button(t("labor.payslips.action.deliver"));
        deliverBtn.setGraphic(icon("fas-hand-holding"));
        deliverBtn.setDisable(true);
        deliverBtn.setOnAction(ev -> {
            var sel = payslipsTable.getSelectionModel().getSelectedItem();
            if (sel != null) showPayslipDeliveryDialog(sel);
        });

        Button pdfBtn = new Button(t("labor.payslips.action.pdf"));
        pdfBtn.setGraphic(icon("fas-file-pdf"));
        pdfBtn.setDisable(true);
        pdfBtn.setOnAction(ev -> {
            var sel = payslipsTable.getSelectionModel().getSelectedItem();
            if (sel != null) downloadPayslipPdf(sel);
        });

        Button emailBtn = new Button(t("labor.payslips.action.email"));
        emailBtn.setGraphic(icon("fas-envelope"));
        emailBtn.setDisable(true);
        emailBtn.setOnAction(ev -> {
            var sel = payslipsTable.getSelectionModel().getSelectedItem();
            if (sel != null) emailPayslip(sel);
        });

        Button delBtn = new Button(t("labor.payslips.action.delete"));
        delBtn.setGraphic(icon("fas-trash"));
        delBtn.setDisable(true);
        delBtn.setOnAction(ev -> {
            var sel = payslipsTable.getSelectionModel().getSelectedItem();
            if (sel != null) deletePayslip(sel);
        });

        payslipsTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            boolean none = nv == null;
            payBtn.setDisable(none || "PAID".equals(nv == null ? "" : nv.status()));
            deliverBtn.setDisable(none);
            pdfBtn.setDisable(none);
            emailBtn.setDisable(none);
            delBtn.setDisable(none || "PAID".equals(nv == null ? "" : nv.status()));
        });

        // 10 botones: en un HBox se encogían y cortaban el texto ("...") en
        // pantallas estrechas. actionFlow (FlowPane) los envuelve a la línea
        // siguiente y mantiene el texto entero.
        javafx.scene.layout.FlowPane actions = actionFlow(
                calcBtn, genMonthBtn, batchBtn, extraBtn, settlementBtn, ssBtn,
                payBtn, deliverBtn, pdfBtn, emailBtn, delBtn);

        Label hint = new Label(t("labor.payslips.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        VBox body = new VBox(10, hint, actions, payslipsTable);
        VBox.setVgrow(payslipsTable, Priority.ALWAYS);
        body.setPadding(new Insets(12));
        this.viewRoot = body;

        // NOTIF-RT — refresco en vivo cuando el empleado firma el recibí (o cambia
        // la entrega) desde su app: recarga la tabla para ver el estado actualizado.
        com.benjagest.ui.support.RefreshBus.subscribe(
                com.benjagest.ui.support.RefreshBus.TOPIC_PAYSLIPS,
                () -> {
                    Task<java.util.List<com.benjagest.ui.model.PayslipEntry>> rt = new Task<>() {
                        @Override protected java.util.List<com.benjagest.ui.model.PayslipEntry> call() throws Exception {
                            return laborApiClient.listPayslips(bundle.currentYear(), null, null);
                        }
                    };
                    rt.setOnSucceeded(e -> payslipsTable.setItems(
                            FXCollections.observableArrayList(rt.getValue())));
                    start(rt, "payslips-rt-reload");
                },
                payslipsTable);
        return screenScroll(body);
    }

    /**
     * LIQ-SS-UI — Liquidación mensual de la Seguridad Social. Lista los meses del
     * año con cuota (empresa + trabajador) y su estado, y permite pagarlos: cada
     * pago genera el asiento {@code 476 → 572} que salda la cuota a la TGSS.
     * "Pagar todos los pendientes" liquida de una vez los meses sin asiento.
     * Refresca Labor + Contabilidad tras pagar (el usuario no pulsa "Refrescar").
     */
    private void showSocialSecurityDialog(int currentYear) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.ss.title"));
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        ComboBox<Integer> yearCombo = new ComboBox<>();
        int yNow = java.time.LocalDate.now().getYear();
        for (int y = yNow + 1; y >= yNow - 5; y--) yearCombo.getItems().add(y);
        yearCombo.getSelectionModel().select(Integer.valueOf(currentYear));

        TableView<com.benjagest.ui.model.SsMonthEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.ss.empty")));

        TableColumn<com.benjagest.ui.model.SsMonthEntry, String> cMonth =
                new TableColumn<>(t("labor.ss.col.month"));
        cMonth.setCellValueFactory(c -> new SimpleStringProperty(
                String.format("%02d/%d", c.getValue().month(), c.getValue().year())));
        TableColumn<com.benjagest.ui.model.SsMonthEntry, String> cAmount =
                new TableColumn<>(t("labor.ss.col.amount"));
        cAmount.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().amount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " €"));
        TableColumn<com.benjagest.ui.model.SsMonthEntry, String> cStatus =
                new TableColumn<>(t("labor.ss.col.status"));
        cStatus.setCellValueFactory(c -> {
            var e = c.getValue();
            String s = e.alreadyPaid() ? t("labor.ss.status.paid")
                    : (e.motivo() != null ? e.motivo() : t("labor.ss.status.pending"));
            return new SimpleStringProperty(s);
        });
        table.getColumns().addAll(java.util.List.of(cMonth, cAmount, cStatus));

        Runnable reload = () -> {
            int y = yearCombo.getValue();
            Task<java.util.List<com.benjagest.ui.model.SsMonthEntry>> tk = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.SsMonthEntry> call() throws Exception {
                    return laborApiClient.ssPending(y);
                }
            };
            tk.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(tk.getValue())));
            tk.setOnFailed(ev -> showError(t("labor.ss.title"), humanizeBackendError(
                    tk.getException() == null ? "" : tk.getException().getMessage())));
            start(tk, "ss-pending");
        };
        yearCombo.valueProperty().addListener((o, ov, nv) -> reload.run());

        Button payBtn = new Button(t("labor.ss.pay"));
        payBtn.setGraphic(icon("fas-money-check-alt"));
        payBtn.setDisable(true);
        Button payAllBtn = new Button(t("labor.ss.pay_all"));
        payAllBtn.setGraphic(icon("fas-check-double"));

        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) ->
                payBtn.setDisable(nv == null || nv.alreadyPaid() || nv.motivo() != null));

        payBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Task<Boolean> tk = new Task<>() {
                @Override protected Boolean call() throws Exception {
                    return laborApiClient.ssPay(sel.year(), sel.month());
                }
            };
            tk.setOnSucceeded(ev2 -> { refreshLaborAndJournal(); reload.run(); });
            tk.setOnFailed(ev2 -> showError(t("labor.ss.title"), humanizeBackendError(
                    tk.getException() == null ? "" : tk.getException().getMessage())));
            start(tk, "ss-pay");
        });
        payAllBtn.setOnAction(ev -> {
            int y = yearCombo.getValue();
            Task<Integer> tk = new Task<>() {
                @Override protected Integer call() throws Exception {
                    return laborApiClient.ssPayAll(y);
                }
            };
            tk.setOnSucceeded(ev2 -> {
                showInfo(t("labor.ss.title"),
                        t("labor.ss.paid_n").replace("{n}", String.valueOf(tk.getValue())));
                refreshLaborAndJournal();
                reload.run();
            });
            tk.setOnFailed(ev2 -> showError(t("labor.ss.title"), humanizeBackendError(
                    tk.getException() == null ? "" : tk.getException().getMessage())));
            start(tk, "ss-pay-all");
        });

        Label hint = new Label(t("labor.ss.pay_hint"));
        hint.setWrapText(true); hint.getStyleClass().add("settings-hint");
        HBox top = new HBox(8, new Label(t("labor.payslips.calc.year")), yearCombo, payBtn, payAllBtn);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, hint, top, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPrefSize(560, 420);
        installDialog(d, box);
        d.setResizable(true);
        reload.run();
        d.showAndWait();
    }

    /**
     * PAY-RECURRENT — Genera de una vez la nómina mensual de todos los empleados
     * activos del mes (salta las ya hechas). Recurrente: un clic al mes.
     */
    private void showGenerateMonthDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.genmonth.title"));
        ButtonType gen = new ButtonType(t("labor.genmonth.run"), ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(gen, ButtonType.CANCEL);
        int yNow = java.time.LocalDate.now().getYear();
        ComboBox<Integer> yearCombo = new ComboBox<>();
        for (int y = yNow + 1; y >= yNow - 5; y--) yearCombo.getItems().add(y);
        yearCombo.getSelectionModel().select(Integer.valueOf(yNow));
        ComboBox<Integer> monthCombo = new ComboBox<>();
        for (int m = 1; m <= 12; m++) monthCombo.getItems().add(m);
        monthCombo.getSelectionModel().select(Integer.valueOf(java.time.LocalDate.now().getMonthValue()));
        Label hint = new Label(t("labor.genmonth.hint"));
        hint.setWrapText(true); hint.getStyleClass().add("settings-hint");
        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12));
        g.add(new Label(t("labor.payslips.calc.year")), 0, 0); g.add(yearCombo, 1, 0);
        g.add(new Label(t("labor.payslips.calc.month")), 0, 1); g.add(monthCombo, 1, 1);
        installDialog(d, new VBox(10, hint, g));
        d.showAndWait().ifPresent(bt -> {
            if (bt != gen) return;
            int y = yearCombo.getValue(), m = monthCombo.getValue();
            Task<com.benjagest.ui.model.MonthlyRunEntry> tk = new Task<>() {
                @Override protected com.benjagest.ui.model.MonthlyRunEntry call() throws Exception {
                    return laborApiClient.generateMonthPayslips(y, m);
                }
            };
            tk.setOnSucceeded(ev -> {
                var res = tk.getValue();
                String msg = t("labor.genmonth.done")
                        .replace("{gen}", String.valueOf(res.generated()))
                        .replace("{skip}", String.valueOf(res.skipped()));
                if (!res.errors().isEmpty()) msg += "\n\n" + String.join("\n", res.errors());
                showInfo(t("labor.genmonth.title"), msg);
                refreshLaborAndJournal();
            });
            tk.setOnFailed(ev -> showError(t("labor.genmonth.title"),
                    humanizeBackendError(tk.getException() == null ? "" : tk.getException().getMessage())));
            start(tk, "gen-month");
        });
    }

    /**
     * CV-1 Finiquito / liquidación. Calcula los conceptos (salario de los días
     * trabajados + vacaciones no disfrutadas + prorrata de pagas extra) y los
     * muestra editables; al validar genera una nómina tipo SETTLEMENT.
     */
    private void showSettlementDialog(PayrollData bundle) {
        java.util.Set<String> withContract = bundle.contracts().stream()
                .map(com.benjagest.ui.model.ContractEntry::employeeId)
                .collect(java.util.stream.Collectors.toSet());
        var employees = bundle.employees().stream()
                .filter(e -> e.active() && withContract.contains(e.id())).toList();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("labor.settlement.title"));
        ButtonType saveBt = new ButtonType(t("labor.settlement.generate"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        ComboBox<com.benjagest.ui.model.EmployeeEntry> empCombo = new ComboBox<>();
        empCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.EmployeeEntry e) { return e == null ? "" : e.fullName(); }
            @Override public com.benjagest.ui.model.EmployeeEntry fromString(String s) { return null; }
        });
        empCombo.getItems().addAll(employees);
        empCombo.setPromptText(t("labor.payslips.calc.employee.prompt"));

        DatePicker ceseDate = new DatePicker(java.time.LocalDate.now());
        // Vacío por defecto → el backend calcula las vacaciones no disfrutadas
        // (devengadas proporcional − disfrutadas). Un valor manual (incl. 0) lo
        // respeta. Antes venía "0" y suprimía el auto-cálculo (bug reportado).
        TextField vacationField = new TextField();
        vacationField.setPromptText(t("labor.settlement.vacation_days.auto"));
        vacationField.setPrefWidth(120);
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

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(10));
        int r = 0;
        g.add(new Label(t("labor.payslips.calc.employee")), 0, r); g.add(empCombo, 1, r++);
        g.add(new Label(t("labor.settlement.cese_date")), 0, r); g.add(ceseDate, 1, r++);
        g.add(new Label(t("labor.settlement.vacation_days")), 0, r); g.add(vacationField, 1, r++);
        g.add(new Label(t("labor.settlement.accrual")), 0, r); g.add(accrualCombo, 1, r++);
        g.add(new Label(t("labor.payslips.calc.other_deductions")), 0, r); g.add(otherField, 1, r++);
        g.add(new Label(t("labor.payslips.calc.notes")), 0, r); g.add(notesArea, 1, r++);

        com.benjagest.ui.support.SalaryComplementsEditor concepts = new com.benjagest.ui.support.SalaryComplementsEditor(
                this::t, new java.util.ArrayList<>(), "labor.payslips.calc.complement.amount",
                "labor.settlement.concepts.title", "labor.settlement.concepts.hint");

        Label summary = new Label(t("labor.payslips.calc.preview.empty"));
        summary.getStyleClass().add("settings-hint");
        summary.setWrapText(true);

        Button calcBtn = new Button(t("labor.settlement.calc_btn"));
        calcBtn.getStyleClass().add("button-secondary");
        Button previewBtn = new Button(t("labor.payslips.calc.preview_btn"));
        previewBtn.getStyleClass().add("button-secondary");

        Runnable doPreview = () -> {
            var emp = empCombo.getValue();
            if (emp == null) { showError(t("labor.settlement.title"), t("labor.payslips.calc.fail.no_employee")); return; }
            java.time.LocalDate d = ceseDate.getValue();
            Task<com.benjagest.ui.model.PayslipPreview> tk = new Task<>() {
                @Override protected com.benjagest.ui.model.PayslipPreview call() throws Exception {
                    return laborApiClient.previewPayslip(emp.id(), d.getYear(), d.getMonthValue(),
                            "SETTLEMENT", false, parseDecSafe(otherField.getText()), concepts.getComplements());
                }
            };
            tk.setOnSucceeded(ev -> {
                var p = tk.getValue();
                summary.setText(t("labor.payslips.calc.preview.text")
                        .replace("{gross}", money(p.gross())).replace("{ss}", money(p.ssEmployee()))
                        .replace("{irpf}", money(p.irpf())).replace("{net}", money(p.net()))
                        .replace("{er}", money(p.employerTotal())).replace("{ercost}", money(p.employerCost())));
            });
            tk.setOnFailed(ev -> {
                Throwable ex = tk.getException();
                String dd = ex == null ? null : humanizeBackendError(ex.getMessage());
                summary.setText(dd == null || dd.isBlank() ? t("labor.payslips.calc.fail.body") : dd);
            });
            start(tk, "settlement-preview");
        };
        previewBtn.setOnAction(e -> doPreview.run());

        calcBtn.setOnAction(e -> {
            var emp = empCombo.getValue();
            if (emp == null) { showError(t("labor.settlement.title"), t("labor.payslips.calc.fail.no_employee")); return; }
            java.time.LocalDate d = ceseDate.getValue();
            if (d == null) { showError(t("labor.settlement.title"), t("labor.settlement.cese_date")); return; }
            Task<java.util.List<com.benjagest.ui.model.SalaryItemEntry>> tk = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.SalaryItemEntry> call() throws Exception {
                    return laborApiClient.settlementConcepts(emp.id(), d.getYear(), d.getMonthValue(),
                            d.getDayOfMonth(), parseDecSafe(vacationField.getText()), accrualCombo.getValue());
                }
            };
            tk.setOnSucceeded(ev -> {
                concepts.clear();
                for (var c : tk.getValue()) concepts.addComplement(c);
                doPreview.run();
            });
            tk.setOnFailed(ev -> showError(t("labor.settlement.title"),
                    humanizeBackendError(tk.getException() == null ? "" : tk.getException().getMessage())));
            start(tk, "settlement-concepts");
        });

        VBox box = new VBox(10, g, new Separator(), new HBox(8, calcBtn), concepts.node,
                new Separator(), new HBox(8, previewBtn), summary);
        installDialog(dialog, box);
        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            var emp = empCombo.getValue();
            java.time.LocalDate d = ceseDate.getValue();
            if (emp == null || d == null) {
                showError(t("labor.settlement.title"), t("labor.payslips.calc.fail.no_employee"));
                return;
            }
            var lines = concepts.getComplements();
            Task<com.benjagest.ui.model.PayslipEntry> task = new Task<>() {
                @Override protected com.benjagest.ui.model.PayslipEntry call() throws Exception {
                    return laborApiClient.calculatePayslip(emp.id(), d.getYear(), d.getMonthValue(),
                            "SETTLEMENT", false, parseDecSafe(otherField.getText()),
                            blankToNullOrSelf(notesArea.getText()), lines);
                }
            };
            task.setOnSucceeded(ev -> refreshLaborAndJournal());
            task.setOnFailed(ev -> showError(t("labor.settlement.title"),
                    humanizeBackendError(task.getException() == null ? "" : task.getException().getMessage())));
            start(task, "settlement-generate");
        });
    }

    /**
     * Pagas extras — genera la nómina de paga extra (verano / navidad) para
     * los empleados seleccionados. Importe = una mensualidad (anual/(12+pagas)),
     * sin cotización propia (su prorrata ya cotiza en las mensuales), solo IRPF.
     */
    private void showExtraPagaDialog(PayrollData bundle) {
        java.util.Set<String> withContract = bundle.contracts().stream()
                .map(com.benjagest.ui.model.ContractEntry::employeeId)
                .collect(java.util.stream.Collectors.toSet());
        var emps = bundle.employees().stream()
                .filter(e -> e.active() && withContract.contains(e.id())).toList();

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.payslips.extra.title"));
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        int yNow = java.time.LocalDate.now().getYear();
        ComboBox<Integer> yearCombo = new ComboBox<>();
        for (int y = yNow + 1; y >= yNow - 5; y--) yearCombo.getItems().add(y);
        yearCombo.getSelectionModel().select(Integer.valueOf(yNow));
        ComboBox<Integer> monthCombo = new ComboBox<>();
        for (int m = 1; m <= 12; m++) monthCombo.getItems().add(m);
        monthCombo.getSelectionModel().select(Integer.valueOf(java.time.LocalDate.now().getMonthValue()));
        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("EXTRA_SUMMER", "EXTRA_CHRISTMAS");
        localizeEnumCombo(typeCombo, "payslip_type");
        typeCombo.getSelectionModel().select("EXTRA_SUMMER");

        VBox empBox = new VBox(4);
        java.util.Map<String, CheckBox> checks = new java.util.HashMap<>();
        for (var e : emps) {
            CheckBox cb = new CheckBox(e.fullName());
            checks.put(e.id(), cb);
            empBox.getChildren().add(cb);
        }
        ScrollPane empScroll = new ScrollPane(empBox);
        empScroll.setFitToWidth(true);
        empScroll.setPrefHeight(160);

        Label status = new Label(t("labor.payslips.extra.hint"));
        status.getStyleClass().add("settings-hint");
        status.setWrapText(true);
        Button genBtn = new Button(t("labor.payslips.extra.generate"));
        genBtn.getStyleClass().add("button-primary");
        genBtn.setOnAction(ev -> {
            var targets = emps.stream().filter(e -> checks.get(e.id()).isSelected()).toList();
            if (targets.isEmpty()) { status.setText(t("labor.payslips.batch.none_selected")); return; }
            int yr = yearCombo.getValue(), mo = monthCombo.getValue();
            String type = typeCombo.getValue();
            Task<String> tk = new Task<>() {
                @Override protected String call() throws Exception {
                    int ok = 0;
                    StringBuilder errs = new StringBuilder();
                    for (var e : targets) {
                        try {
                            laborApiClient.calculatePayslip(e.id(), yr, mo, type, false, null, null,
                                    java.util.List.of());
                            ok++;
                        } catch (Exception ex) {
                            errs.append("• ").append(e.fullName()).append(": ")
                                .append(humanizeBackendError(ex.getMessage())).append("\n");
                        }
                    }
                    return ok + "||" + errs;
                }
            };
            tk.setOnSucceeded(ev2 -> {
                String[] parts = tk.getValue().split("\\|\\|", 2);
                String msg = t("labor.payslips.batch.done").replace("{n}", parts[0]);
                if (parts.length > 1 && !parts[1].isBlank()) msg += "\n" + parts[1];
                status.setText(msg);
            });
            tk.setOnFailed(ev2 -> status.setText(tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "payslip-extra");
        });

        GridPane top = new GridPane();
        top.setHgap(10); top.setVgap(8);
        int r = 0;
        top.add(new Label(t("labor.payslips.calc.year")), 0, r); top.add(yearCombo, 1, r);
        top.add(new Label(t("labor.payslips.calc.month")), 2, r); top.add(monthCombo, 3, r); r++;
        top.add(new Label(t("labor.payslips.calc.type")), 0, r); top.add(typeCombo, 1, r); r++;

        VBox content = new VBox(10, top, new Label(t("labor.payslips.batch.employees")),
                empScroll, genBtn, status);
        content.setPadding(new Insets(12));
        installDialog(d, content);
        d.showAndWait();
        refreshLaborAndJournal();
    }

    /**
     * REPLICAR — generar nóminas en lote para varios empleados a un mismo
     * sueldo objetivo (bruto o neto). Para cada empleado calcula el "plus"
     * (mejora voluntaria) con solve-target y genera la nómina. Útil cuando
     * el empresario quiere que varios de la misma categoría cobren igual.
     */
    private void showBatchTargetDialog(PayrollData bundle) {
        java.util.Set<String> withContract = bundle.contracts().stream()
                .map(com.benjagest.ui.model.ContractEntry::employeeId)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Map<String, String> catByEmp = new java.util.HashMap<>();
        for (var c : bundle.contracts()) catByEmp.putIfAbsent(c.employeeId(), c.professionalCategory());
        var emps = bundle.employees().stream()
                .filter(e -> e.active() && withContract.contains(e.id())).toList();

        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.payslips.batch.title"));
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        int yNow = java.time.LocalDate.now().getYear();
        ComboBox<Integer> yearCombo = new ComboBox<>();
        for (int y = yNow + 1; y >= yNow - 5; y--) yearCombo.getItems().add(y);
        yearCombo.getSelectionModel().select(Integer.valueOf(yNow));
        ComboBox<Integer> monthCombo = new ComboBox<>();
        for (int m = 1; m <= 12; m++) monthCombo.getItems().add(m);
        monthCombo.getSelectionModel().select(Integer.valueOf(java.time.LocalDate.now().getMonthValue()));

        ComboBox<String> mode = new ComboBox<>();
        mode.getItems().addAll("GROSS", "NET");
        mode.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String m) {
                return "GROSS".equals(m) ? t("labor.payslips.calc.target.gross")
                        : t("labor.payslips.calc.target.net");
            }
            @Override public String fromString(String s) { return null; }
        });
        mode.getSelectionModel().select("NET");
        TextField targetField = new TextField();
        targetField.setPromptText(t("labor.payslips.calc.target.prompt"));
        targetField.setPrefWidth(120);

        VBox empBox = new VBox(4);
        java.util.Map<String, CheckBox> checks = new java.util.HashMap<>();
        for (var e : emps) {
            String cat = catByEmp.getOrDefault(e.id(), "");
            CheckBox cb = new CheckBox(e.fullName()
                    + (cat == null || cat.isBlank() ? "" : "   (" + cat + ")"));
            checks.put(e.id(), cb);
            empBox.getChildren().add(cb);
        }
        ScrollPane empScroll = new ScrollPane(empBox);
        empScroll.setFitToWidth(true);
        empScroll.setPrefHeight(160);

        Label status = new Label(t("labor.payslips.batch.hint"));
        status.getStyleClass().add("settings-hint");
        status.setWrapText(true);
        Button genBtn = new Button(t("labor.payslips.batch.generate"));
        genBtn.getStyleClass().add("button-primary");
        genBtn.setOnAction(ev -> {
            var targets = emps.stream().filter(e -> checks.get(e.id()).isSelected()).toList();
            if (targets.isEmpty()) { status.setText(t("labor.payslips.batch.none_selected")); return; }
            java.math.BigDecimal target = parseDecSafe(targetField.getText());
            if (target == null) { status.setText(t("labor.payslips.calc.target.prompt")); return; }
            int yr = yearCombo.getValue(), mo = monthCombo.getValue();
            String modeV = mode.getValue();
            Task<String> tk = new Task<>() {
                @Override protected String call() throws Exception {
                    int ok = 0;
                    StringBuilder errs = new StringBuilder();
                    for (var e : targets) {
                        try {
                            java.math.BigDecimal plus = laborApiClient.solveTargetPlus(
                                    e.id(), yr, mo, "MONTHLY", false, modeV, target, java.util.List.of());
                            // La mejora es recurrente: se guarda en el contrato
                            // (anualiza en SS e IRPF) y se calcula la nómina normal.
                            laborApiClient.upsertRecurringComplement(
                                    e.id(), t("labor.payslips.calc.target.concept"), plus);
                            laborApiClient.calculatePayslip(e.id(), yr, mo, "MONTHLY", false, null, null,
                                    java.util.List.of());
                            ok++;
                        } catch (Exception ex) {
                            errs.append("• ").append(e.fullName()).append(": ")
                                .append(humanizeBackendError(ex.getMessage())).append("\n");
                        }
                    }
                    return ok + "||" + errs;
                }
            };
            tk.setOnSucceeded(ev2 -> {
                String[] parts = tk.getValue().split("\\|\\|", 2);
                String msg = t("labor.payslips.batch.done").replace("{n}", parts[0]);
                if (parts.length > 1 && !parts[1].isBlank()) msg += "\n" + parts[1];
                status.setText(msg);
            });
            tk.setOnFailed(ev2 -> status.setText(tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "payslip-batch");
        });

        GridPane top = new GridPane();
        top.setHgap(10); top.setVgap(8);
        int r = 0;
        top.add(new Label(t("labor.payslips.calc.year")), 0, r); top.add(yearCombo, 1, r);
        top.add(new Label(t("labor.payslips.calc.month")), 2, r); top.add(monthCombo, 3, r); r++;
        top.add(new Label(t("labor.payslips.calc.target.label")), 0, r);
        top.add(new HBox(8, mode, targetField), 1, r, 3, 1); r++;

        VBox content = new VBox(10, top, new Label(t("labor.payslips.batch.employees")),
                empScroll, genBtn, status);
        content.setPadding(new Insets(12));
        installDialog(d, content);
        d.showAndWait();
        refreshLaborAndJournal();
    }

    private void showCalculatePayslipDialog(java.util.List<com.benjagest.ui.model.EmployeeEntry> employees,
                                             PayrollData bundle) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("labor.payslips.calc.title"));
        ButtonType saveBt = new ButtonType(t("labor.payslips.calc.validate"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        // Complementos del CONTRATO por empleado (se aplican automáticamente;
        // aquí se muestran solo lectura para no duplicarlos en el editor).
        java.util.Map<String, java.util.List<com.benjagest.ui.model.SalaryItemEntry>> contractComps =
                new java.util.HashMap<>();
        // Prorrateo de pagas extra por empleado (del contrato): la casilla del
        // cálculo aparecerá marcada/desmarcada según el contrato del empleado.
        java.util.Map<String, Boolean> proratedByEmp = new java.util.HashMap<>();
        for (var c : bundle.contracts()) {
            proratedByEmp.putIfAbsent(c.employeeId(),
                    c.extrasProrated() != null && c.extrasProrated());
            if (c.salaryItems() == null) continue;
            var comps = c.salaryItems().stream()
                    .filter(it -> !"SALARY_BASE".equals(it.kind())).toList();
            contractComps.putIfAbsent(c.employeeId(), new java.util.ArrayList<>(comps));
        }

        ComboBox<com.benjagest.ui.model.EmployeeEntry> empCombo = new ComboBox<>();
        empCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.EmployeeEntry e) {
                return e == null ? "" : e.fullName();
            }
            @Override public com.benjagest.ui.model.EmployeeEntry fromString(String s) { return null; }
        });
        empCombo.getItems().addAll(employees.stream().filter(com.benjagest.ui.model.EmployeeEntry::active).toList());
        empCombo.setPromptText(t("labor.payslips.calc.employee.prompt"));
        // No autoseleccionar: forzar elección consciente para no nominar
        // por error al empleado equivocado (p. ej. el primero por orden).
        // BUG-UX-2 — quita el resaltado rojo en cuanto se elige empleado.
        clearMissingOnChange(empCombo);

        // Listado (solo lectura) de los complementos del contrato del empleado.
        Label contractCompsInfo = new Label(t("labor.payslips.calc.contract_comps.none"));
        contractCompsInfo.getStyleClass().add("settings-hint");
        contractCompsInfo.setWrapText(true);
        Runnable refreshContractComps = () -> {
            var nv = empCombo.getValue();
            if (nv == null) { contractCompsInfo.setText(t("labor.payslips.calc.contract_comps.none")); return; }
            var comps = contractComps.getOrDefault(nv.id(), java.util.List.of());
            if (comps.isEmpty()) { contractCompsInfo.setText(t("labor.payslips.calc.contract_comps.empty")); return; }
            StringBuilder sb = new StringBuilder(t("labor.payslips.calc.contract_comps.title")).append("\n");
            for (var it : comps) {
                // Importe anual del contrato mostrado como mensual (/12).
                java.math.BigDecimal monthly = it.annualAmount() == null ? java.math.BigDecimal.ZERO
                        : it.annualAmount().divide(java.math.BigDecimal.valueOf(12), 2, java.math.RoundingMode.HALF_UP);
                sb.append("• ").append(it.conceptName()).append(": ")
                  .append(monthly.toPlainString()).append(" €/mes")
                  .append("  [").append(it.cotizes() ? "SS" : "no SS").append(", ")
                  .append(it.taxable() ? "IRPF" : "no IRPF").append("]\n");
            }
            contractCompsInfo.setText(sb.toString().trim());
        };
        empCombo.valueProperty().addListener((o, ov, nv) -> refreshContractComps.run());

        ComboBox<Integer> yearCombo = new ComboBox<>();
        int year = java.time.LocalDate.now().getYear();
        for (int y = year + 1; y >= year - 5; y--) yearCombo.getItems().add(y);
        yearCombo.getSelectionModel().select(Integer.valueOf(year));

        ComboBox<Integer> monthCombo = new ComboBox<>();
        for (int m = 1; m <= 12; m++) monthCombo.getItems().add(m);
        monthCombo.getSelectionModel().select(Integer.valueOf(java.time.LocalDate.now().getMonthValue()));

        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("MONTHLY", "EXTRA_SUMMER", "EXTRA_CHRISTMAS", "BONUS", "SETTLEMENT");
        localizeEnumCombo(typeCombo, "payslip_type");
        typeCombo.getSelectionModel().select("MONTHLY");

        CheckBox extraProrated = new CheckBox(t("labor.payslips.calc.extra_prorated"));
        // Default legal: 14 pagas (sin prorratear) salvo convenio. Art. 31 ET.
        extraProrated.setSelected(false);
        // Refleja el prorrateo del contrato del empleado seleccionado.
        empCombo.valueProperty().addListener((o, ov, nv) ->
                extraProrated.setSelected(nv != null
                        && Boolean.TRUE.equals(proratedByEmp.get(nv.id()))));

        TextField otherField = new TextField();
        otherField.setPromptText(t("labor.payslips.calc.other_deductions.prompt"));

        TextArea notesArea = new TextArea(); notesArea.setPrefRowCount(2);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(10));
        g.add(new Label(t("labor.payslips.calc.employee")), 0, 0); g.add(empCombo, 1, 0);
        g.add(new Label(t("labor.payslips.calc.year")), 0, 1); g.add(yearCombo, 1, 1);
        g.add(new Label(t("labor.payslips.calc.month")), 0, 2); g.add(monthCombo, 1, 2);
        g.add(new Label(t("labor.payslips.calc.type")), 0, 3); g.add(typeCombo, 1, 3);
        g.add(extraProrated, 1, 4);
        g.add(new Label(t("labor.payslips.calc.other_deductions")), 0, 5); g.add(otherField, 1, 5);
        g.add(new Label(t("labor.payslips.calc.notes")), 0, 6); g.add(notesArea, 1, 6);

        // Complementos de esta nómina (dietas, kilometraje, asistencia…).
        com.benjagest.ui.support.SalaryComplementsEditor extras = new com.benjagest.ui.support.SalaryComplementsEditor(
                this::t, new java.util.ArrayList<>(), "labor.payslips.calc.complement.amount",
                "labor.payslips.calc.complement.title", "labor.payslips.calc.complement.hint");

        // OBJETIVO — proponer un plus para llegar a un sueldo bruto o neto.
        ComboBox<String> targetMode = new ComboBox<>();
        targetMode.getItems().addAll("GROSS", "NET");
        targetMode.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String m) {
                return "GROSS".equals(m) ? t("labor.payslips.calc.target.gross")
                        : t("labor.payslips.calc.target.net");
            }
            @Override public String fromString(String s) { return null; }
        });
        targetMode.getSelectionModel().select("NET");
        TextField targetField = new TextField();
        targetField.setPromptText(t("labor.payslips.calc.target.prompt"));
        targetField.setPrefWidth(110);
        Button proposeBtn = new Button(t("labor.payslips.calc.target.propose"));
        proposeBtn.getStyleClass().add("button-secondary");
        HBox targetBox = new HBox(8, new Label(t("labor.payslips.calc.target.label")),
                targetMode, targetField, proposeBtn);
        targetBox.setAlignment(Pos.CENTER_LEFT);

        // PREVIEW — resumen en vivo (bruto / SS / IRPF / neto / coste empresa).
        Label summary = new Label(t("labor.payslips.calc.preview.empty"));
        summary.getStyleClass().add("settings-hint");
        summary.setWrapText(true);
        Button previewBtn = new Button(t("labor.payslips.calc.preview_btn"));
        previewBtn.getStyleClass().add("button-secondary");

        Runnable doPreview = () -> {
            var emp = empCombo.getValue();
            if (emp == null) { showError(t("labor.payslips.calc.fail.title"),
                    t("labor.payslips.calc.fail.no_employee")); return; }
            Task<com.benjagest.ui.model.PayslipPreview> tk = new Task<>() {
                @Override protected com.benjagest.ui.model.PayslipPreview call() throws Exception {
                    return laborApiClient.previewPayslip(emp.id(), yearCombo.getValue(), monthCombo.getValue(),
                            typeCombo.getValue(), extraProrated.isSelected(),
                            parseDecSafe(otherField.getText()), extras.getComplements());
                }
            };
            tk.setOnSucceeded(ev -> {
                var p = tk.getValue();
                summary.setText(t("labor.payslips.calc.preview.text")
                        .replace("{gross}", money(p.gross()))
                        .replace("{ss}", money(p.ssEmployee()))
                        .replace("{irpf}", money(p.irpf()))
                        .replace("{net}", money(p.net()))
                        .replace("{er}", money(p.employerTotal()))
                        .replace("{ercost}", money(p.employerCost())));
            });
            tk.setOnFailed(ev -> {
                Throwable ex = tk.getException();
                String d = ex == null ? null : humanizeBackendError(ex.getMessage());
                summary.setText(d == null || d.isBlank() ? t("labor.payslips.calc.fail.body") : d);
            });
            start(tk, "payslip-preview");
        };
        previewBtn.setOnAction(e -> doPreview.run());

        proposeBtn.setOnAction(e -> {
            var emp = empCombo.getValue();
            if (emp == null) { showError(t("labor.payslips.calc.fail.title"),
                    t("labor.payslips.calc.fail.no_employee")); return; }
            java.math.BigDecimal target = parseDecSafe(targetField.getText());
            if (target == null) { showError(t("labor.payslips.calc.target.label"),
                    t("labor.payslips.calc.target.prompt")); return; }
            Task<java.math.BigDecimal> tk = new Task<>() {
                @Override protected java.math.BigDecimal call() throws Exception {
                    return laborApiClient.solveTargetPlus(emp.id(), yearCombo.getValue(), monthCombo.getValue(),
                            typeCombo.getValue(), extraProrated.isSelected(), targetMode.getValue(),
                            target, extras.getComplements());
                }
            };
            tk.setOnSucceeded(ev -> {
                java.math.BigDecimal x = tk.getValue();
                String concept = t("labor.payslips.calc.target.concept");
                // La mejora es recurrente: se guarda como complemento mensual
                // del contrato (anualiza en base SS e IRPF). Confirmamos antes.
                Alert c = new Alert(Alert.AlertType.CONFIRMATION,
                        t("labor.payslips.calc.target.confirm")
                                .replace("{amount}", money(x))
                                .replace("{name}", emp.fullName()),
                        ButtonType.OK, ButtonType.CANCEL);
                c.setHeaderText(t("labor.payslips.calc.target.propose"));
                c.showAndWait().ifPresent(bt -> {
                    if (bt != ButtonType.OK) return;
                    Task<Void> wr = new Task<>() {
                        @Override protected Void call() throws Exception {
                            laborApiClient.upsertRecurringComplement(emp.id(), concept, x);
                            return null;
                        }
                    };
                    wr.setOnSucceeded(e2 -> {
                        // Refleja la mejora en el listado local del contrato.
                        var list = contractComps.computeIfAbsent(emp.id(),
                                k -> new java.util.ArrayList<>());
                        list.removeIf(it -> concept.equals(it.conceptName()));
                        list.add(new com.benjagest.ui.model.SalaryItemEntry(
                                null, concept, "COMPLEMENT",
                                x.multiply(java.math.BigDecimal.valueOf(12)), true, true));
                        refreshContractComps.run();
                        doPreview.run();
                    });
                    wr.setOnFailed(e2 -> {
                        Throwable ex = wr.getException();
                        String d = ex == null ? null : humanizeBackendError(ex.getMessage());
                        showError(t("labor.payslips.calc.target.label"),
                                d == null || d.isBlank() ? t("labor.payslips.calc.fail.body") : d);
                    });
                    start(wr, "mejora-contract");
                });
            });
            tk.setOnFailed(ev -> {
                Throwable ex = tk.getException();
                String d = ex == null ? null : humanizeBackendError(ex.getMessage());
                showError(t("labor.payslips.calc.target.label"),
                        d == null || d.isBlank() ? t("labor.payslips.calc.fail.body") : d);
            });
            start(tk, "payslip-target");
        });

        // INC-1 — Incidencias del periodo (horas extra, complementos variables,
        // ausencias, deducciones) PERSISTIDAS por empleado+mes. Se gestionan aquí y
        // el motor las aplica al calcular. Botón con contador de cuántas hay.
        Button incidenciasBtn = new Button(t("inc.btn.open"));
        incidenciasBtn.setGraphic(icon("fas-list-check"));
        incidenciasBtn.getStyleClass().add("button-secondary");
        Runnable refreshIncCount = () -> {
            var emp = empCombo.getValue();
            if (emp == null) { incidenciasBtn.setText(t("inc.btn.open")); return; }
            Task<Integer> tk = new Task<>() {
                @Override protected Integer call() throws Exception {
                    return laborApiClient.listIncidencias(emp.id(),
                            yearCombo.getValue(), monthCombo.getValue()).size();
                }
            };
            tk.setOnSucceeded(ev -> incidenciasBtn.setText(
                    t("inc.btn.open") + (tk.getValue() > 0 ? " (" + tk.getValue() + ")" : "")));
            tk.setOnFailed(ev -> incidenciasBtn.setText(t("inc.btn.open")));
            start(tk, "inc-count");
        };
        incidenciasBtn.setOnAction(e -> {
            var emp = empCombo.getValue();
            if (emp == null) { showError(t("labor.payslips.calc.fail.title"),
                    t("labor.payslips.calc.fail.no_employee")); return; }
            showIncidenciasDialog(emp.id(), emp.fullName(), yearCombo.getValue(), monthCombo.getValue(),
                    () -> { refreshIncCount.run(); doPreview.run(); });
        });
        empCombo.valueProperty().addListener((o, ov, nv) -> refreshIncCount.run());
        yearCombo.valueProperty().addListener((o, ov, nv) -> refreshIncCount.run());
        monthCombo.valueProperty().addListener((o, ov, nv) -> refreshIncCount.run());
        refreshIncCount.run();
        HBox incBox = new HBox(8, incidenciasBtn);
        incBox.setAlignment(Pos.CENTER_LEFT);

        VBox previewBox = new VBox(8, new Separator(), targetBox, previewBtn, summary);
        installDialog(dialog, new VBox(10, g, new Separator(), contractCompsInfo,
                extras.node, incBox, previewBox));

        // BUG-UX-2 — Validar sin empleado NO debe cerrar el diálogo ni
        // sacar un Alert: no es un error, es un campo que falta. El filtro
        // de ACTION consume el evento (evita el cierre) y avisa con un
        // toast no modal + resalta el combo.
        final javafx.scene.Node saveNode = dialog.getDialogPane().lookupButton(saveBt);
        saveNode.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            if (empCombo.getValue() == null) {
                ev.consume();
                toast(dialog.getDialogPane().getScene().getWindow(),
                        t("labor.payslips.calc.fail.no_employee"));
                highlightMissing(empCombo);
            }
        });

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            var emp = empCombo.getValue();
            // El filtro de ACTION ya impide llegar aquí sin empleado; este
            // guard queda como red de seguridad (no muestra error).
            if (emp == null) return;
            java.math.BigDecimal other = parseDecSafe(otherField.getText());
            java.util.List<com.benjagest.ui.model.SalaryItemEntry> extraConcepts = extras.getComplements();
            Task<com.benjagest.ui.model.PayslipEntry> task = new Task<>() {
                @Override
                protected com.benjagest.ui.model.PayslipEntry call() throws Exception {
                    return laborApiClient.calculatePayslip(emp.id(),
                            yearCombo.getValue(), monthCombo.getValue(),
                            typeCombo.getValue(), extraProrated.isSelected(),
                            other, blankToNullOrSelf(notesArea.getText()), extraConcepts);
                }
            };
            task.setOnSucceeded(ev -> refreshLaborAndJournal());
            task.setOnFailed(ev -> {
                Throwable ex = task.getException();
                String detail = ex == null ? null : humanizeBackendError(ex.getMessage());
                showError(t("labor.payslips.calc.fail.title"),
                        (detail == null || detail.isBlank())
                                ? t("labor.payslips.calc.fail.body")
                                : detail);
            });
            start(task, "payslip-calculate");
        });
    }

    /**
     * INC-1 — Gestiona las incidencias de nómina de un (empleado, periodo): horas
     * extra, complementos variables, ausencias y deducciones. Persisten en
     * nomina_incidencias (V136). {@code onChanged} refresca el preview del diálogo
     * de calcular. Hoy el motor aplica COMPLEMENT y DEDUCTION; las horas extra
     * (cotización adicional legal) y ausencias (reducción de base) llegan en INC-2/3.
     */
    private void showIncidenciasDialog(String employeeId, String employeeName,
                                       int year, int month, Runnable onChanged) {
        Stage dlg = new Stage();
        dlg.setTitle(t("inc.title") + " — " + employeeName
                + "  " + String.format("%02d/%d", month, year));
        dlg.initModality(javafx.stage.Modality.APPLICATION_MODAL);

        Label hint = new Label(t("inc.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        TableView<com.benjagest.ui.model.IncidenciaEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("inc.empty")));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<com.benjagest.ui.model.IncidenciaEntry, String> cKind = new TableColumn<>(t("inc.col.kind"));
        cKind.setCellValueFactory(c -> new SimpleStringProperty(incKindLabel(c.getValue())));
        cKind.setPrefWidth(170);
        TableColumn<com.benjagest.ui.model.IncidenciaEntry, String> cConcept = new TableColumn<>(t("inc.col.concept"));
        cConcept.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().concept()));
        cConcept.setPrefWidth(220);
        TableColumn<com.benjagest.ui.model.IncidenciaEntry, String> cValue = new TableColumn<>(t("inc.col.value"));
        cValue.setCellValueFactory(c -> new SimpleStringProperty(incValueLabel(c.getValue())));
        cValue.setPrefWidth(180);
        TableColumn<com.benjagest.ui.model.IncidenciaEntry, String> cFlags = new TableColumn<>(t("inc.col.flags"));
        cFlags.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().cotizes() ? t("inc.flag.cotiza") : "") +
                (c.getValue().taxable() ? (c.getValue().cotizes() ? " · " : "") + t("inc.flag.tributa") : "")));
        cFlags.setPrefWidth(150);
        table.getColumns().addAll(java.util.List.of(cKind, cConcept, cValue, cFlags));

        Runnable reload = () -> {
            Task<java.util.List<com.benjagest.ui.model.IncidenciaEntry>> tk = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.IncidenciaEntry> call() throws Exception {
                    return laborApiClient.listIncidencias(employeeId, year, month);
                }
            };
            tk.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(tk.getValue())));
            tk.setOnFailed(ev -> showError(t("inc.fail.title"),
                    tk.getException() == null ? "" : humanizeBackendError(tk.getException().getMessage())));
            start(tk, "inc-list");
        };

        Button addBtn = new Button(t("inc.action.add"));
        addBtn.setGraphic(icon("fas-plus"));
        addBtn.getStyleClass().add("button-primary");
        addBtn.setOnAction(e -> showIncidenciaForm(employeeId, year, month, null,
                () -> { reload.run(); onChanged.run(); }));
        Button editBtn = new Button(t("inc.action.edit"));
        editBtn.setGraphic(icon("fas-edit"));
        editBtn.setDisable(true);
        editBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) showIncidenciaForm(employeeId, year, month, sel,
                    () -> { reload.run(); onChanged.run(); });
        });
        Button delBtn = new Button(t("inc.action.delete"));
        delBtn.setGraphic(icon("fas-trash"));
        delBtn.setDisable(true);
        delBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert c = new Alert(Alert.AlertType.CONFIRMATION,
                    t("inc.delete.confirm") + " " + sel.concept(), ButtonType.OK, ButtonType.CANCEL);
            c.setHeaderText(null);
            c.showAndWait().ifPresent(bt -> {
                if (bt != ButtonType.OK) return;
                Task<Void> tk = new Task<>() {
                    @Override protected Void call() throws Exception {
                        laborApiClient.deleteIncidencia(sel.id()); return null;
                    }
                };
                tk.setOnSucceeded(ev -> { reload.run(); onChanged.run(); });
                tk.setOnFailed(ev -> showError(t("inc.fail.title"),
                        tk.getException() == null ? "" : humanizeBackendError(tk.getException().getMessage())));
                start(tk, "inc-delete");
            });
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            editBtn.setDisable(nv == null);
            delBtn.setDisable(nv == null);
        });
        Button closeBtn = new Button(t("dialog.close"));
        closeBtn.setOnAction(e -> dlg.close());

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox actions = new HBox(8, addBtn, editBtn, delBtn, sp, closeBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, hint, actions, table);
        root.setPadding(new javafx.geometry.Insets(16));
        root.setPrefSize(760, 460);
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/com/benjagest/ui/app.css").toExternalForm());
        dlg.setScene(scene);
        reload.run();
        dlg.showAndWait();
    }

    /** Etiqueta de tipo (+ subtipo) de una incidencia para la tabla. */
    private String incKindLabel(com.benjagest.ui.model.IncidenciaEntry e) {
        String k = t("inc.kind." + e.kind());
        if (e.subtype() != null && !e.subtype().isBlank()) k += " · " + t("inc.subtype." + e.subtype());
        return k;
    }

    /** Etiqueta de valor: importe + detalle (horas×precio o días). */
    private String incValueLabel(com.benjagest.ui.model.IncidenciaEntry e) {
        String v = e.amount() == null ? "—" : money(e.amount().toPlainString());
        if (e.hours() != null && e.unitPrice() != null) {
            v += "  (" + e.hours().toPlainString() + "h × " + money(e.unitPrice().toPlainString()) + ")";
        } else if (e.days() != null) {
            v += "  (" + e.days().toPlainString() + " " + t("inc.unit.days") + ")";
        }
        return v;
    }

    private void showIncidenciaForm(String employeeId, int year, int month,
                                    com.benjagest.ui.model.IncidenciaEntry existing, Runnable onSaved) {
        Stage dlg = new Stage();
        dlg.setTitle(existing == null ? t("inc.form.add") : t("inc.form.edit"));
        dlg.initModality(javafx.stage.Modality.APPLICATION_MODAL);

        ComboBox<String> kind = new ComboBox<>(FXCollections.observableArrayList(
                "OVERTIME", "COMPLEMENT", "ABSENCE", "DEDUCTION", "OTHER"));
        kind.setConverter(localizedConverter("inc.kind"));
        ComboBox<String> subtype = new ComboBox<>();
        subtype.setConverter(localizedConverter("inc.subtype"));
        Label subtypeLbl = new Label(t("inc.field.subtype"));

        TextField concept = new TextField();
        TextField hours = new TextField(); hours.setPromptText("0");
        TextField unitPrice = new TextField(); unitPrice.setPromptText("0,00");
        TextField days = new TextField(); days.setPromptText("0");
        TextField amount = new TextField(); amount.setPromptText("0,00");
        CheckBox cotizes = new CheckBox(t("inc.field.cotizes"));
        CheckBox taxable = new CheckBox(t("inc.field.taxable"));
        TextArea notes = new TextArea(); notes.setPrefRowCount(2);

        // Subtipos por tipo; defaults de cotiza/tributa.
        Runnable onKind = () -> {
            String k = kind.getValue();
            if ("OVERTIME".equals(k)) {
                subtype.setItems(FXCollections.observableArrayList("STRUCTURAL", "NORMAL"));
                subtype.setVisible(true); subtypeLbl.setVisible(true);
            } else if ("ABSENCE".equals(k)) {
                subtype.setItems(FXCollections.observableArrayList(
                        "JUSTIFIED_PAID", "JUSTIFIED_UNPAID", "UNJUSTIFIED"));
                subtype.setVisible(true); subtypeLbl.setVisible(true);
            } else {
                subtype.getItems().clear(); subtype.setValue(null);
                subtype.setVisible(false); subtypeLbl.setVisible(false);
            }
            boolean ded = "DEDUCTION".equals(k);
            if (existing == null) { cotizes.setSelected(!ded); taxable.setSelected(!ded); }
        };
        kind.valueProperty().addListener((o, ov, nv) -> onKind.run());

        if (existing != null) {
            kind.setValue(existing.kind());
            onKind.run();
            subtype.setValue(existing.subtype());
            concept.setText(existing.concept());
            if (existing.hours() != null) hours.setText(existing.hours().toPlainString());
            if (existing.unitPrice() != null) unitPrice.setText(existing.unitPrice().toPlainString());
            if (existing.days() != null) days.setText(existing.days().toPlainString());
            if (existing.amount() != null) amount.setText(existing.amount().toPlainString());
            cotizes.setSelected(existing.cotizes());
            taxable.setSelected(existing.taxable());
            notes.setText(existing.notes() == null ? "" : existing.notes());
        } else {
            kind.setValue("OVERTIME");
            onKind.run();
        }

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new javafx.geometry.Insets(12));
        // La columna de etiquetas debe conservar su ancho preferido (si no, los
        // Label se truncan a "..."); la columna de campos crece con el diálogo.
        javafx.scene.layout.ColumnConstraints labelCol = new javafx.scene.layout.ColumnConstraints();
        labelCol.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.layout.ColumnConstraints fieldCol = new javafx.scene.layout.ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        fieldCol.setFillWidth(true);
        g.getColumnConstraints().addAll(labelCol, fieldCol);
        kind.setMaxWidth(Double.MAX_VALUE);
        subtype.setMaxWidth(Double.MAX_VALUE);
        int r = 0;
        g.add(new Label(t("inc.field.kind")), 0, r); g.add(kind, 1, r++);
        g.add(subtypeLbl, 0, r); g.add(subtype, 1, r++);
        g.add(new Label(t("inc.field.concept")), 0, r); g.add(concept, 1, r++);
        g.add(new Label(t("inc.field.hours")), 0, r); g.add(hours, 1, r++);
        g.add(new Label(t("inc.field.unit_price")), 0, r); g.add(unitPrice, 1, r++);
        g.add(new Label(t("inc.field.days")), 0, r); g.add(days, 1, r++);
        g.add(new Label(t("inc.field.amount")), 0, r); g.add(amount, 1, r++);
        g.add(cotizes, 1, r++);
        g.add(taxable, 1, r++);
        g.add(new Label(t("inc.field.notes")), 0, r); g.add(notes, 1, r++);
        Label formHint = new Label(t("inc.form.hint"));
        formHint.setWrapText(true); formHint.getStyleClass().add("settings-hint");

        Button save = new Button(t("dialog.save"));
        save.setGraphic(icon("fas-check"));
        save.getStyleClass().add("button-primary");
        save.setOnAction(e -> {
            if (concept.getText() == null || concept.getText().isBlank()) {
                showError(t("inc.fail.title"), t("inc.fail.concept")); return;
            }
            com.benjagest.ui.model.IncidenciaEntry payload = new com.benjagest.ui.model.IncidenciaEntry(
                    existing == null ? null : existing.id(), employeeId, year, month,
                    kind.getValue(), subtype.getValue(), concept.getText().trim(),
                    parseDecSafe(hours.getText()), parseDecSafe(unitPrice.getText()),
                    parseDecSafe(days.getText()), parseDecSafe(amount.getText()),
                    cotizes.isSelected(), taxable.isSelected(),
                    blankToNullOrSelf(notes.getText()), "MANUAL");
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception {
                    if (existing == null) laborApiClient.createIncidencia(payload);
                    else laborApiClient.updateIncidencia(existing.id(), payload);
                    return null;
                }
            };
            tk.setOnSucceeded(ev -> { dlg.close(); onSaved.run(); });
            tk.setOnFailed(ev -> showError(t("inc.fail.title"),
                    tk.getException() == null ? "" : humanizeBackendError(tk.getException().getMessage())));
            start(tk, "inc-save");
        });
        Button cancel = new Button(t("dialog.cancel"));
        cancel.setOnAction(e -> dlg.close());
        HBox btns = new HBox(10, cancel, save);
        btns.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10, g, formHint, btns);
        root.setPadding(new javafx.geometry.Insets(8));
        root.setPrefWidth(460);
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/com/benjagest/ui/app.css").toExternalForm());
        com.benjagest.ui.support.EditableCells.enableDateMaskOnFocus(scene);
        dlg.setScene(scene);
        dlg.showAndWait();
    }

    // ===================================================================
    //  Acciones por recibo
    // ===================================================================

    /**
     * PAY-DELIVERY — Registra la entrega del recibo de salarios al trabajador
     * (fecha + vía) y opcionalmente el acuse de recibo (firma). ET art. 29 /
     * Orden ESS/2098/2014.
     */
    private void showPayslipDeliveryDialog(com.benjagest.ui.model.PayslipEntry p) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(t("labor.payslips.delivery.title"));
        d.setHeaderText(p.employeeName() + " — " + p.periodMonth() + "/" + p.periodYear());
        ButtonType save = new ButtonType(t("save"), ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        DatePicker deliveredPicker = new DatePicker(
                (p.deliveredAt() != null && !p.deliveredAt().isBlank())
                        ? java.time.LocalDate.parse(p.deliveredAt()) : java.time.LocalDate.now());
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(deliveredPicker);

        ComboBox<String> methodCombo = new ComboBox<>();
        methodCombo.getItems().addAll("HAND", "EMAIL", "PORTAL", "POSTAL");
        methodCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String code) {
                return code == null ? "" : t("labor.payslips.delivery.method." + code);
            }
            @Override public String fromString(String s) { return s; }
        });
        methodCombo.setValue(p.deliveryMethod() == null || p.deliveryMethod().isBlank()
                ? "HAND" : p.deliveryMethod());

        CheckBox ackCheck = new CheckBox(t("labor.payslips.delivery.ack_check"));
        DatePicker ackPicker = new DatePicker(
                (p.acknowledgedAt() != null && !p.acknowledgedAt().isBlank())
                        ? java.time.LocalDate.parse(p.acknowledgedAt()) : java.time.LocalDate.now());
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(ackPicker);
        ackCheck.setSelected(p.acknowledgedAt() != null && !p.acknowledgedAt().isBlank());
        ackPicker.disableProperty().bind(ackCheck.selectedProperty().not());

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(10); g.setPadding(new Insets(12));
        g.add(new Label(t("labor.payslips.delivery.date")), 0, 0); g.add(deliveredPicker, 1, 0);
        g.add(new Label(t("labor.payslips.delivery.method")), 0, 1); g.add(methodCombo, 1, 1);
        g.add(ackCheck, 0, 2, 2, 1);
        g.add(new Label(t("labor.payslips.delivery.ack_date")), 0, 3); g.add(ackPicker, 1, 3);
        d.getDialogPane().setContent(g);

        d.showAndWait().ifPresent(bt -> {
            if (bt != save) return;
            java.time.LocalDate delivered = deliveredPicker.getValue();
            String method = methodCombo.getValue();
            boolean ack = ackCheck.isSelected();
            java.time.LocalDate ackDate = ackPicker.getValue();
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.markPayslipDelivered(p.id(), delivered, method);
                    if (ack) laborApiClient.markPayslipAcknowledged(p.id(), ackDate);
                    return null;
                }
            };
            task.setOnSucceeded(ev -> refreshLabor.run());
            task.setOnFailed(ev -> showError(t("labor.payslips.calc.fail.title"), t("labor.payslips.calc.fail.body")));
            start(task, "payslip-deliver");
        });
    }

    private void markPayslipPaid(com.benjagest.ui.model.PayslipEntry p) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("labor.payslips.pay.body") + " " + p.employeeName() + " (" + p.periodMonth() + "/" + p.periodYear() + ")",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("labor.payslips.pay.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.markPayslipPaid(p.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> refreshLaborAndJournal());
            task.setOnFailed(ev -> showError(t("labor.payslips.calc.fail.title"), t("labor.payslips.calc.fail.body")));
            start(task, "payslip-pay");
        });
    }

    private void downloadPayslipPdf(com.benjagest.ui.model.PayslipEntry p) {
        Task<byte[]> task = new Task<>() {
            @Override protected byte[] call() throws Exception {
                return laborApiClient.downloadPayslipPdf(p.id());
            }
        };
        task.setOnSucceeded(ev -> {
            byte[] bytes = task.getValue();
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle(t("labor.payslips.pdf.save_as"));
            String typeSeg = switch (p.payslipType() == null ? "MONTHLY" : p.payslipType()) {
                case "EXTRA_SUMMER" -> "extra-verano-";
                case "EXTRA_CHRISTMAS" -> "extra-navidad-";
                case "BONUS" -> "bonus-";
                case "SETTLEMENT" -> "finiquito-";
                default -> "";
            };
            fc.setInitialFileName("nomina-" + typeSeg + p.periodYear() + "-"
                    + String.format("%02d", p.periodMonth()) + "-"
                    + (p.employeeName() == null ? p.employeeId() : p.employeeName().replace(" ", "_")) + ".pdf");
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
        });
        task.setOnFailed(ev -> showError(t("labor.payslips.pdf.fail.title"),
                t("labor.payslips.pdf.fail.body")));
        start(task, "payslip-pdf");
    }

    private void emailPayslip(com.benjagest.ui.model.PayslipEntry p) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("labor.payslips.email.body") + " " + p.employeeName(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("labor.payslips.email.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.emailPayslipToEmployee(p.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> showInfo(t("labor.payslips.email.ok.title"),
                    t("labor.payslips.email.ok.body")));
            task.setOnFailed(ev -> showError(t("labor.payslips.email.fail.title"),
                    t("labor.payslips.email.fail.body")));
            start(task, "payslip-email");
        });
    }

    private void deletePayslip(com.benjagest.ui.model.PayslipEntry p) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("labor.payslips.delete.body") + " " + p.periodMonth() + "/" + p.periodYear(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("labor.payslips.delete.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.deletePayslip(p.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> refreshLaborAndJournal());
            task.setOnFailed(ev -> showError(t("labor.payslips.calc.fail.title"),
                    t("labor.payslips.calc.fail.body")));
            start(task, "payslip-delete");
        });
    }
}
