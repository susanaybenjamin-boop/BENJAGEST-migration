package com.benjagest.ui.screens;

import com.benjagest.ui.model.AccountingModels.BankAccountView;
import com.benjagest.ui.model.AccountingModels.BankMovementRow;
import com.benjagest.ui.model.AccountingModels.FixedAssetRow;
import com.benjagest.ui.model.AccountingModels.InstallmentView;
import com.benjagest.ui.model.AccountingModels.LoanView;
import com.benjagest.ui.service.AccountingApiClient;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Pantalla unificada Bancos / Préstamos / Inmovilizado para el módulo
 * Contabilidad del cliente. Se usa también desde el tab Bancos/Préstamos/
 * Inmovilizado de la vista del cliente en asesoría.
 *
 * <p>Modos del builder:
 * <ul>
 *   <li>{@link #buildBanksTab()} → solo bancos + movimientos enlazables.</li>
 *   <li>{@link #buildLoansTab()} → solo préstamos + cuadro amortización.</li>
 *   <li>{@link #buildAssetsTab()} → inmovilizado con dotación rápida.</li>
 * </ul>
 *
 * <p>Toda la carga es async; no se bloquea el JavaFX thread.
 */
public class ClientFinancialsScreen {

    private final AccountingApiClient api;
    private final Function<String, String> tt;

    public ClientFinancialsScreen(AccountingApiClient api, Function<String, String> tt) {
        this.api = api;
        this.tt = tt == null ? key -> key : tt;
    }

    // ====================================================================
    //  BANCOS
    // ====================================================================

    public Node buildBanksTab() {
        TableView<BankAccountView> accountsTable = new TableView<>();
        accountsTable.getColumns().addAll(List.of(
                col(tt.apply("bank.col.alias"), BankAccountView::alias, 160),
                col(tt.apply("bank.col.iban"), BankAccountView::iban, 200),
                col(tt.apply("bank.col.bank"), BankAccountView::bankName, 160),
                col(tt.apply("bank.col.opening"), v -> v.openingBalance() == null ? "" : v.openingBalance().toString(), 110),
                col(tt.apply("bank.col.active"), v -> v.active() ? "✓" : "✗", 60)
        ));

        TableView<BankMovementRow> movementsTable = new TableView<>();
        movementsTable.getColumns().addAll(List.of(
                col(tt.apply("bank.col.date"), v -> v.operationDate() == null ? "" : v.operationDate().toString(), 100),
                col(tt.apply("bank.col.description"), BankMovementRow::description, 280),
                col(tt.apply("bank.col.counterparty"), BankMovementRow::counterpartyName, 180),
                col(tt.apply("bank.col.nif"), BankMovementRow::counterpartyNif, 100),
                col(tt.apply("bank.col.amount"), v -> v.amount() == null ? "" : v.amount().toString(), 100),
                col(tt.apply("bank.col.balance"), v -> v.balanceAfter() == null ? "" : v.balanceAfter().toString(), 100),
                col(tt.apply("bank.col.status"),
                        v -> v.status() == null ? "" : tt.apply("bank.movement_status." + v.status()), 110),
                col(tt.apply("bank.col.invoice"), v -> v.linkedInvoiceKind() == null
                        ? "" : v.linkedInvoiceKind() + ":" + (v.linkedInvoiceId() == null ? "" : v.linkedInvoiceId().substring(0, 8)), 130)
        ));

        Button refreshAccounts = new Button(tt.apply("accounting.action.refresh"));
        refreshAccounts.setOnAction(e -> loadBankAccounts(accountsTable));

        Button loadMovs = new Button("Ver movimientos");
        loadMovs.setOnAction(e -> {
            BankAccountView sel = accountsTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            async(() -> api.listBankMovements(sel.id(), null, null, null),
                    rows -> movementsTable.setItems(FXCollections.observableArrayList(rows)),
                    err -> showError("No se cargaron movimientos", err));
        });

        accountsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) { movementsTable.getItems().clear(); return; }
            async(() -> api.listBankMovements(newV.id(), null, null, null),
                    rows -> movementsTable.setItems(FXCollections.observableArrayList(rows)),
                    err -> { /* silencioso */ });
        });

        Label accLabel = new Label("Cuentas bancarias");
        accLabel.getStyleClass().add("settings-section-title");
        HBox accActions = new HBox(8, refreshAccounts, loadMovs);
        VBox accountsBox = new VBox(8, accLabel, accActions, accountsTable);
        VBox.setVgrow(accountsTable, Priority.ALWAYS);
        accountsBox.setPadding(new Insets(8));

        Label movLabel = new Label("Movimientos");
        movLabel.getStyleClass().add("settings-section-title");
        Label hint = new Label("Selecciona una cuenta para ver sus movimientos. "
                + "Los enlazados a factura aparecen con etiqueta SALES/PURCHASE.");
        hint.setStyle("-fx-text-fill: #6e6e6e;");
        VBox movBox = new VBox(8, movLabel, hint, movementsTable);
        VBox.setVgrow(movementsTable, Priority.ALWAYS);
        movBox.setPadding(new Insets(8));

        SplitPane split = new SplitPane(accountsBox, movBox);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.4);
        loadBankAccounts(accountsTable);
        return split;
    }

    private void loadBankAccounts(TableView<BankAccountView> table) {
        async(() -> api.listBankAccounts(null),
                rows -> table.setItems(FXCollections.observableArrayList(rows)),
                err -> logSilent("bank-accounts", err));
    }

    // ====================================================================
    //  PRÉSTAMOS
    // ====================================================================

    public Node buildLoansTab() {
        TableView<LoanView> loansTable = new TableView<>();
        loansTable.getColumns().addAll(List.of(
                col(tt.apply("loans.col.code"), LoanView::code, 100),
                col(tt.apply("loans.col.description"), LoanView::description, 240),
                col(tt.apply("loans.col.lender"), LoanView::lenderName, 180),
                col(tt.apply("loans.col.principal"), v -> v.principalAmount() == null ? "" : v.principalAmount().toString(), 110),
                col(tt.apply("loans.col.interest"), v -> v.interestRate() == null ? "" : v.interestRate().toString(), 80),
                col(tt.apply("loans.col.term"), v -> String.valueOf(v.termMonths()), 70),
                col(tt.apply("loans.col.installment"), v -> v.installmentAmount() == null ? "" : v.installmentAmount().toString(), 100),
                col(tt.apply("loans.col.method"), v -> v.method() == null ? "" : tt.apply("loans.method." + v.method()), 100),
                col(tt.apply("loans.col.status"), v -> v.status() == null ? "" : tt.apply("loans.status." + v.status()), 100)
        ));

        TableView<InstallmentView> installmentsTable = new TableView<>();
        installmentsTable.getColumns().addAll(List.of(
                col("#", v -> String.valueOf(v.installmentNumber()), 50),
                col(tt.apply("loans.col.due_date"), v -> v.dueDate() == null ? "" : v.dueDate().toString(), 110),
                col(tt.apply("loans.col.principal"), v -> v.principalAmount() == null ? "" : v.principalAmount().toString(), 110),
                col(tt.apply("loans.col.interest_amount"), v -> v.interestAmount() == null ? "" : v.interestAmount().toString(), 100),
                col(tt.apply("loans.col.installment"), v -> v.totalAmount() == null ? "" : v.totalAmount().toString(), 100),
                col(tt.apply("loans.col.remaining"), v -> v.remainingPrincipal() == null ? "" : v.remainingPrincipal().toString(), 110),
                col(tt.apply("loans.col.status"), v -> v.status() == null ? "" : tt.apply("loans.installment_status." + v.status()), 100)
        ));

        Button refresh = new Button("Refrescar");
        refresh.setOnAction(e -> loadLoans(loansTable));

        Button payNext = new Button("Pagar siguiente cuota");
        payNext.setOnAction(e -> {
            LoanView sel = loansTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            InstallmentView next = null;
            for (InstallmentView i : installmentsTable.getItems()) {
                if ("PENDING".equals(i.status())) { next = i; break; }
            }
            if (next == null) return;
            final InstallmentView fNext = next;
            async(() -> { api.payInstallment(fNext.id(), LocalDate.now()); return null; },
                    v -> {
                        loadInstallments(installmentsTable, sel.id());
                        loadLoans(loansTable);
                        // REFRESH-AUDIT — pagar cuota crea asiento de pago:
                        // avisar a Contabilidad, préstamos y bancos.
                        com.benjagest.ui.support.RefreshBus.emit(
                                com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL,
                                com.benjagest.ui.support.RefreshBus.TOPIC_LOANS,
                                com.benjagest.ui.support.RefreshBus.TOPIC_BANK_ACCOUNTS);
                    },
                    err -> showError("Error al pagar cuota", err));
        });

        loansTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) { installmentsTable.getItems().clear(); return; }
            loadInstallments(installmentsTable, newV.id());
        });

        Label hint = new Label("Selecciona un préstamo para ver su cuadro de amortización. "
                + "El botón 'Pagar siguiente cuota' genera el asiento automático "
                + "Debe 170/662 / Haber 572.");
        hint.setStyle("-fx-text-fill: #6e6e6e;");
        hint.setWrapText(true);

        HBox actions = new HBox(8, refresh, payNext);
        VBox loansBox = new VBox(8, hint, actions, loansTable);
        VBox.setVgrow(loansTable, Priority.ALWAYS);
        loansBox.setPadding(new Insets(8));
        VBox instBox = new VBox(8, new Label("Cuadro de amortización"), installmentsTable);
        VBox.setVgrow(installmentsTable, Priority.ALWAYS);
        instBox.setPadding(new Insets(8));

        SplitPane split = new SplitPane(loansBox, instBox);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.45);
        loadLoans(loansTable);
        return split;
    }

    private void loadLoans(TableView<LoanView> table) {
        async(() -> api.listLoans(null),
                rows -> table.setItems(FXCollections.observableArrayList(rows)),
                err -> logSilent("loans", err));
    }

    private void loadInstallments(TableView<InstallmentView> table, String loanId) {
        async(() -> api.listInstallments(loanId),
                rows -> table.setItems(FXCollections.observableArrayList(rows)),
                err -> logSilent("installments", err));
    }

    // ====================================================================
    //  INMOVILIZADO
    // ====================================================================

    public Node buildAssetsTab() {
        TableView<FixedAssetRow> table = new TableView<>();
        table.getColumns().addAll(List.of(
                col(tt.apply("assets.col.code"), FixedAssetRow::code, 100),
                col(tt.apply("assets.col.name"), FixedAssetRow::name, 240),
                col(tt.apply("assets.col.category"),
                        v -> v.category() == null ? "" : tt.apply("assets.category." + v.category()), 150),
                col(tt.apply("assets.col.acquisition_date"), v -> v.acquisitionDate() == null ? "" : v.acquisitionDate().toString(), 110),
                col(tt.apply("assets.col.cost"), v -> v.acquisitionCost() == null ? "" : v.acquisitionCost().toString(), 110),
                col(tt.apply("assets.col.useful_life"), v -> v.usefulLifeYears() == null ? "" : v.usefulLifeYears().toString(), 90),
                col(tt.apply("assets.col.method"),
                        v -> v.depreciationMethod() == null ? "" : tt.apply("assets.method." + v.depreciationMethod()), 100),
                col(tt.apply("assets.col.active"), v -> v.active() ? "✓" : "✗", 60)
        ));

        Button refresh = new Button("Refrescar");
        refresh.setOnAction(e -> loadAssets(table));

        Button postDepreciation = new Button("Dotar amortización del año");
        postDepreciation.setOnAction(e -> {
            FixedAssetRow sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            int year = LocalDate.now().getYear();
            async(() -> { api.postAssetDepreciationEntry(sel.id(), year, null); return null; },
                    v -> {
                        showInfo("Asiento de amortización creado",
                                "Se ha posteado la dotación del año " + year + " para " + sel.code());
                        loadAssets(table);
                        // REFRESH-AUDIT — la dotación crea asiento: avisar a
                        // Contabilidad y al inventario de inmovilizado.
                        com.benjagest.ui.support.RefreshBus.emit(
                                com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL,
                                com.benjagest.ui.support.RefreshBus.TOPIC_FIXED_ASSETS);
                    },
                    err -> showError("Error al dotar amortización", err));
        });

        Label hint = new Label("El alta de un nuevo activo, la baja por venta y los cambios "
                + "de método se gestionan desde el módulo Inmovilizado completo. "
                + "Desde aquí puedes dotar la amortización anual y ver el inventario.");
        hint.setStyle("-fx-text-fill: #6e6e6e;");
        hint.setWrapText(true);

        HBox actions = new HBox(8, refresh, postDepreciation);
        VBox box = new VBox(8, hint, actions, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPadding(new Insets(8));
        loadAssets(table);
        return box;
    }

    private void loadAssets(TableView<FixedAssetRow> table) {
        async(() -> api.listFixedAssets(),
                rows -> table.setItems(FXCollections.observableArrayList(rows)),
                err -> table.setItems(FXCollections.observableArrayList(new ArrayList<>())));
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private <T> TableColumn<T, String> col(String header, java.util.function.Function<T, String> getter, double width) {
        TableColumn<T, String> c = new TableColumn<>(header);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new SimpleStringProperty(getter.apply(cd.getValue())));
        return c;
    }

    private <T> void async(ApiCall<T> call, Consumer<T> ok, Consumer<Throwable> err) {
        Thread t = new Thread(() -> {
            try {
                T result = call.call();
                Platform.runLater(() -> ok.accept(result));
            } catch (Throwable ex) {
                Platform.runLater(() -> err.accept(ex));
            }
        }, "financials-api");
        t.setDaemon(true);
        t.start();
    }

    private void showError(String title, Throwable err) {
        if (err instanceof com.benjagest.ui.service.SessionExpiredException
                || err.getCause() instanceof com.benjagest.ui.service.SessionExpiredException) {
            Alert a = new Alert(AlertType.WARNING,
                    tt.apply("accounting.error.session_expired_body"));
            a.setHeaderText(tt.apply("accounting.error.session_expired_title"));
            a.showAndWait();
            return;
        }
        Alert a = new Alert(AlertType.ERROR, title + "\n\n"
                + (err.getMessage() == null ? err.toString() : err.getMessage()));
        a.showAndWait();
    }

    private void logSilent(String where, Throwable err) {
        System.err.println("[financials-ui:" + where + "] "
                + (err.getMessage() == null ? err.toString() : err.getMessage()));
    }

    private void showInfo(String title, String body) {
        Alert a = new Alert(AlertType.INFORMATION, body);
        a.setHeaderText(title);
        a.showAndWait();
    }

    @FunctionalInterface
    private interface ApiCall<T> { T call() throws Exception; }
}
