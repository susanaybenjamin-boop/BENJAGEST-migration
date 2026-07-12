package com.benjagest.ui.screens;

import com.benjagest.ui.model.AccountingModels.BankAccountView;
import com.benjagest.ui.model.AccountingModels.BankMovementRow;
import com.benjagest.ui.model.AccountingModels.BankReconcileRow;
import com.benjagest.ui.model.AccountingModels.CompensationInvoice;
import com.benjagest.ui.model.AccountingModels.CompensationProposal;
import com.benjagest.ui.model.AccountingModels.CompensationRow;
import com.benjagest.ui.model.AccountingModels.ExistingPayment;
import com.benjagest.ui.model.AccountingModels.FixedAssetRow;
import com.benjagest.ui.model.AccountingModels.InstallmentView;
import com.benjagest.ui.model.AccountingModels.LoanView;
import com.benjagest.ui.service.AccountingApiClient;
import com.benjagest.ui.support.RefreshBus;
import java.math.BigDecimal;
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
import javafx.scene.control.ButtonType;
import javafx.scene.control.SelectionMode;
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

        Button newAccount = new Button(tt.apply("bank.account.new"));
        newAccount.getStyleClass().add("primary-button");
        newAccount.setOnAction(e -> showBankAccountDialog(accountsTable));

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

        // BANK-IMPORT — importar extracto bancario (autodetecta Excel/CSV/N43).
        Button importBtn = new Button(tt.apply("bank.import.btn"));
        importBtn.setOnAction(e -> showBankImportDialog(accountsTable, movementsTable));

        // F1-BANCO-REVIEW — reabrir la revisión de conciliación en cualquier
        // momento (no solo justo tras importar).
        Button reconcileBtn = new Button(tt.apply("bank.reconcile.open"));
        reconcileBtn.setOnAction(e -> {
            BankAccountView sel = accountsTable.getSelectionModel().getSelectedItem();
            if (sel == null) sel = accountsTable.getItems().isEmpty() ? null : accountsTable.getItems().get(0);
            if (sel == null) { showInfo(tt.apply("bank.reconcile.open"), tt.apply("bank.import.no_accounts")); return; }
            openReconcileReview(sel.id(), accountsTable, movementsTable);
        });

        // F1-BANCO-IGNORE — sacar de pendientes un movimiento sin factura
        // (comisión, etc.) para que no quede colgado.
        Button ignoreBtn = new Button(tt.apply("bank.ignore.btn"));
        ignoreBtn.setOnAction(e -> ignoreSelectedMovement(accountsTable, movementsTable));

        accountsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) { movementsTable.getItems().clear(); return; }
            async(() -> api.listBankMovements(newV.id(), null, null, null),
                    rows -> movementsTable.setItems(FXCollections.observableArrayList(rows)),
                    err -> { /* silencioso */ });
        });

        Label accLabel = new Label("Cuentas bancarias");
        accLabel.getStyleClass().add("settings-section-title");
        HBox accActions = new HBox(8, newAccount, refreshAccounts, loadMovs, importBtn, reconcileBtn);
        VBox accountsBox = new VBox(8, accLabel, accActions, accountsTable);
        VBox.setVgrow(accountsTable, Priority.ALWAYS);
        accountsBox.setPadding(new Insets(8));

        Label movLabel = new Label("Movimientos");
        movLabel.getStyleClass().add("settings-section-title");
        Label hint = new Label("Selecciona una cuenta para ver sus movimientos. "
                + "Los enlazados a factura aparecen con etiqueta SALES/PURCHASE.");
        hint.setStyle("-fx-text-fill: #6e6e6e;");
        HBox movActions = new HBox(8, ignoreBtn);
        VBox movBox = new VBox(8, movLabel, hint, movActions, movementsTable);
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

    /**
     * F1-BANCO-CUENTA — alta de cuenta bancaria (alias + IBAN + banco). Es el
     * requisito previo para importar un extracto. El 572 lo resuelve el backend.
     */
    private void showBankAccountDialog(TableView<BankAccountView> accountsTable) {
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> d = new javafx.scene.control.Dialog<>();
        d.setTitle(tt.apply("bank.account.new.title"));
        javafx.scene.control.ButtonType ok = new javafx.scene.control.ButtonType(
                tt.apply("bank.account.save"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ok, javafx.scene.control.ButtonType.CANCEL);

        javafx.scene.control.TextField alias = new javafx.scene.control.TextField();
        alias.setPromptText(tt.apply("bank.account.alias.hint"));
        javafx.scene.control.TextField iban = new javafx.scene.control.TextField();
        iban.setPromptText("ES00 0000 0000 0000 0000 0000");
        javafx.scene.control.TextField bank = new javafx.scene.control.TextField();
        bank.setPromptText("BBVA, CaixaBank…");

        javafx.scene.layout.GridPane g = new javafx.scene.layout.GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12));
        g.add(new Label(tt.apply("bank.account.alias")), 0, 0); g.add(alias, 1, 0);
        g.add(new Label(tt.apply("bank.account.iban")), 0, 1); g.add(iban, 1, 1);
        g.add(new Label(tt.apply("bank.account.bank")), 0, 2); g.add(bank, 1, 2);
        Label hint = new Label(tt.apply("bank.account.hint"));
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #6e6e6e;");
        g.add(hint, 0, 3, 2, 1);
        d.getDialogPane().setContent(g);
        d.getDialogPane().setPrefSize(460, 240);
        d.setResizable(true);

        d.showAndWait().ifPresent(bt -> {
            if (bt != ok) return;
            if (alias.getText() == null || alias.getText().isBlank()) {
                showInfo(tt.apply("bank.account.new.title"), tt.apply("bank.account.alias_required"));
                return;
            }
            async(() -> api.createBankAccount(alias.getText().trim(),
                            iban.getText() == null ? null : iban.getText().trim(),
                            bank.getText() == null ? null : bank.getText().trim()),
                    v -> {
                        loadBankAccounts(accountsTable);
                        showInfo(tt.apply("bank.account.new.title"), tt.apply("bank.account.saved"));
                        com.benjagest.ui.support.RefreshBus.emit(
                                com.benjagest.ui.support.RefreshBus.TOPIC_BANK_ACCOUNTS);
                    },
                    err -> showError(tt.apply("bank.account.fail"), err));
        });
    }

    /** BANK-IMPORT — diálogo: cuenta + formato (N43/CSV) + fichero → importar. */
    private void showBankImportDialog(TableView<BankAccountView> accountsTable,
                                      TableView<BankMovementRow> movementsTable) {
        if (accountsTable.getItems().isEmpty()) {
            showInfo(tt.apply("bank.import.btn"), tt.apply("bank.import.no_accounts"));
            return;
        }
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> d = new javafx.scene.control.Dialog<>();
        d.setTitle(tt.apply("bank.import.title"));
        javafx.scene.control.ButtonType ok = new javafx.scene.control.ButtonType(
                tt.apply("bank.import.do"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ok, javafx.scene.control.ButtonType.CANCEL);

        javafx.scene.control.ComboBox<BankAccountView> accCombo = new javafx.scene.control.ComboBox<>(
                FXCollections.observableArrayList(accountsTable.getItems()));
        accCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(BankAccountView a) {
                return a == null ? "" : a.alias() + " — " + (a.iban() == null ? "" : a.iban());
            }
            @Override public BankAccountView fromString(String s) { return null; }
        });
        BankAccountView selAcc = accountsTable.getSelectionModel().getSelectedItem();
        accCombo.setValue(selAcc != null ? selAcc : accountsTable.getItems().get(0));

        Label fileLabel = new Label(tt.apply("bank.import.no_file"));
        fileLabel.setStyle("-fx-text-fill: #6e6e6e;");
        final java.io.File[] chosen = {null};
        Button pick = new Button(tt.apply("bank.import.pick_file"));
        pick.setOnAction(ev -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle(tt.apply("bank.import.pick_file"));
            fc.getExtensionFilters().addAll(
                    new javafx.stage.FileChooser.ExtensionFilter(
                            tt.apply("bank.import.filter_extract"),
                            "*.xlsx", "*.csv", "*.n43", "*.txt"),
                    new javafx.stage.FileChooser.ExtensionFilter(tt.apply("bank.import.filter_all"), "*.*"));
            java.io.File f = fc.showOpenDialog(accountsTable.getScene().getWindow());
            if (f != null) {
                chosen[0] = f;
                fileLabel.setText(f.getName());
            }
        });

        javafx.scene.layout.GridPane g = new javafx.scene.layout.GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12));
        g.add(new Label(tt.apply("bank.import.account")), 0, 0); g.add(accCombo, 1, 0);
        g.add(new Label(tt.apply("bank.import.file")), 0, 1); g.add(new HBox(8, pick, fileLabel), 1, 1);
        Label fmtHint = new Label(tt.apply("bank.import.auto_hint"));
        fmtHint.setWrapText(true);
        fmtHint.setStyle("-fx-text-fill: #6e6e6e;");
        g.add(fmtHint, 0, 2, 2, 1);
        d.getDialogPane().setContent(g);

        d.showAndWait().ifPresent(bt -> {
            if (bt != ok) return;
            BankAccountView acc = accCombo.getValue();
            if (acc == null || chosen[0] == null) {
                showInfo(tt.apply("bank.import.title"), tt.apply("bank.import.missing"));
                return;
            }
            final java.io.File file = chosen[0];
            async(() -> {
                // F1-BANCO-AUTO: el fichero SIEMPRE viaja en Base64 (vale para el
                // .xlsx binario y para csv/n43 de texto); el backend detecta el
                // formato REAL por el contenido, no por la extensión.
                String content = java.util.Base64.getEncoder().encodeToString(
                        java.nio.file.Files.readAllBytes(file.toPath()));
                return api.importBankExtract(acc.id(), "AUTO", file.getName(), content);
            }, res -> {
                reloadMovements(acc.id(), movementsTable);
                showInfo(tt.apply("bank.import.done_title"), tt.apply("bank.import.done_body")
                        .replace("{total}", String.valueOf(res.rowsTotal()))
                        .replace("{imported}", String.valueOf(res.rowsImported()))
                        .replace("{skipped}", String.valueOf(res.rowsSkipped())));
                // F1-BANCO-REVIEW: tras importar, abrir la revisión de
                // conciliación (checkbox + estado + pago existente) en vez de
                // auto-postear a ciegas.
                openReconcileReview(acc.id(), accountsTable, movementsTable);
                com.benjagest.ui.support.RefreshBus.emit(
                        com.benjagest.ui.support.RefreshBus.TOPIC_BANK_ACCOUNTS,
                        com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL);
            }, err -> showError(tt.apply("bank.import.fail"), err));
        });
    }

    private void reloadMovements(String bankAccountId, TableView<BankMovementRow> movementsTable) {
        async(() -> api.listBankMovements(bankAccountId, null, null, null),
                rows -> movementsTable.setItems(FXCollections.observableArrayList(rows)),
                err -> { /* silencioso */ });
    }

    /**
     * F1-BANCO-IGNORE — marca IGNORED el movimiento seleccionado (comisión u
     * otro cargo sin factura) para que salga de "pendientes de conciliar".
     */
    private void ignoreSelectedMovement(TableView<BankAccountView> accountsTable,
                                        TableView<BankMovementRow> movementsTable) {
        BankMovementRow m = movementsTable.getSelectionModel().getSelectedItem();
        if (m == null) { showInfo(tt.apply("bank.ignore.btn"), tt.apply("bank.ignore.pick")); return; }
        if (!"UNRECONCILED".equals(m.status())) {
            showInfo(tt.apply("bank.ignore.btn"), tt.apply("bank.ignore.only_pending"));
            return;
        }
        javafx.scene.control.TextInputDialog dlg = new javafx.scene.control.TextInputDialog();
        dlg.setTitle(tt.apply("bank.ignore.btn"));
        dlg.setHeaderText(tt.apply("bank.ignore.header"));
        dlg.setContentText(tt.apply("bank.ignore.reason"));
        dlg.showAndWait().ifPresent(reason -> async(
                () -> { api.ignoreMovement(m.id(), reason); return null; },
                v -> {
                    reloadMovements(m.bankAccountId(), movementsTable);
                    com.benjagest.ui.support.RefreshBus.emit(
                            com.benjagest.ui.support.RefreshBus.TOPIC_BANK_ACCOUNTS);
                },
                err -> showError(tt.apply("bank.ignore.fail"), err)));
    }

    /**
     * F1-BANCO-REVIEW — carga las filas de conciliación de la cuenta y abre la
     * pantalla de revisión. Si no hay nada pendiente, avisa y no molesta.
     */
    private void openReconcileReview(String bankAccountId,
                                     TableView<BankAccountView> accountsTable,
                                     TableView<BankMovementRow> movementsTable) {
        async(() -> api.reconcileReview(bankAccountId),
                rows -> {
                    if (rows.isEmpty()) {
                        showInfo(tt.apply("bank.reconcile.none.title"), tt.apply("bank.reconcile.none"));
                        return;
                    }
                    showReconcileDialog(bankAccountId, rows, movementsTable);
                },
                err -> showError(tt.apply("bank.reconcile.load_fail"), err));
    }

    /**
     * Árbol de revisión: cada movimiento es una fila con checkbox; los que ya
     * están cobrados/pagados vienen desmarcados y con su pago existente colgando
     * DEBAJO (para asegurarse de que está, sin abrir otra ventana). Solo se
     * concilian los marcados.
     */
    private void showReconcileDialog(String bankAccountId, List<BankReconcileRow> rows,
                                     TableView<BankMovementRow> movementsTable) {
        javafx.scene.control.TreeItem<ReconNode> root = new javafx.scene.control.TreeItem<>();
        root.setExpanded(true);
        List<ReconNode> movementNodes = new ArrayList<>();
        for (BankReconcileRow r : rows) {
            boolean selectable = "PENDING_SALES".equals(r.state()) || "PENDING_PURCHASE".equals(r.state());
            ReconNode mn = new ReconNode(r, selectable);
            movementNodes.add(mn);
            javafx.scene.control.TreeItem<ReconNode> ti = new javafx.scene.control.TreeItem<>(mn);
            if (r.existingPayments() != null && !r.existingPayments().isEmpty()) {
                for (ExistingPayment p : r.existingPayments()) {
                    ti.getChildren().add(new javafx.scene.control.TreeItem<>(new ReconNode(p)));
                }
                ti.setExpanded(true);
            }
            root.getChildren().add(ti);
        }

        javafx.scene.control.TreeTableColumn<ReconNode, Boolean> selCol =
                new javafx.scene.control.TreeTableColumn<>(tt.apply("bank.reconcile.col.sel"));
        selCol.setPrefWidth(90);
        selCol.setSortable(false);
        selCol.setCellFactory(c -> new javafx.scene.control.TreeTableCell<>() {
            private final javafx.scene.control.CheckBox cb = new javafx.scene.control.CheckBox();
            @Override protected void updateItem(Boolean v, boolean empty) {
                super.updateItem(v, empty);
                ReconNode node = (getTableRow() == null || getTableRow().getTreeItem() == null)
                        ? null : getTableRow().getTreeItem().getValue();
                if (empty || node == null || !node.selectable) { setGraphic(null); return; }
                cb.setOnAction(null);
                cb.setSelected(node.selected.get());
                cb.setOnAction(e -> node.selected.set(cb.isSelected()));
                setGraphic(cb);
            }
        });

        javafx.scene.control.TreeTableView<ReconNode> tree = new javafx.scene.control.TreeTableView<>(root);
        tree.setShowRoot(false);
        tree.getColumns().add(selCol);
        tree.getColumns().add(treeCol(tt.apply("bank.col.date"), this::nodeDate, 100));
        tree.getColumns().add(treeCol(tt.apply("bank.col.description"), this::nodeDescription, 300));
        tree.getColumns().add(treeCol(tt.apply("bank.col.amount"), this::nodeAmount, 110));
        tree.getColumns().add(treeCol(tt.apply("bank.reconcile.col.candidate"), this::nodeCandidate, 220));
        tree.getColumns().add(treeCol(tt.apply("bank.reconcile.col.state"), this::nodeState, 150));
        tree.setColumnResizePolicy(javafx.scene.control.TreeTableView.CONSTRAINED_RESIZE_POLICY);

        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> d = new javafx.scene.control.Dialog<>();
        d.setTitle(tt.apply("bank.reconcile.title"));
        javafx.scene.control.ButtonType ok = new javafx.scene.control.ButtonType(
                tt.apply("bank.reconcile.do"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ok, javafx.scene.control.ButtonType.CANCEL);
        Label intro = new Label(tt.apply("bank.reconcile.intro"));
        intro.setWrapText(true);
        intro.setStyle("-fx-text-fill: #6e6e6e;");
        VBox content = new VBox(8, intro, tree);
        VBox.setVgrow(tree, Priority.ALWAYS);
        content.setPadding(new Insets(10));
        d.getDialogPane().setContent(content);
        d.getDialogPane().setPrefSize(940, 540);
        d.setResizable(true);

        d.showAndWait().ifPresent(bt -> {
            if (bt != ok) { reloadMovements(bankAccountId, movementsTable); return; }
            List<BankReconcileRow> chosen = new ArrayList<>();
            for (ReconNode mn : movementNodes) {
                if (mn.selectable && mn.selected.get()) chosen.add(mn.row);
            }
            if (chosen.isEmpty()) { reloadMovements(bankAccountId, movementsTable); return; }
            async(() -> api.reconcileSelected(chosen),
                    res -> {
                        showInfo(tt.apply("bank.reconcile.done.title"),
                                tt.apply("bank.reconcile.done")
                                        .replace("{ok}", String.valueOf(res.reconciled()))
                                        .replace("{fail}", String.valueOf(res.failed())));
                        reloadMovements(bankAccountId, movementsTable);
                        // La conciliación crea asientos de cobro/pago: avisar.
                        com.benjagest.ui.support.RefreshBus.emit(
                                com.benjagest.ui.support.RefreshBus.TOPIC_BANK_ACCOUNTS,
                                com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL,
                                com.benjagest.ui.support.RefreshBus.TOPIC_SALES,
                                com.benjagest.ui.support.RefreshBus.TOPIC_PURCHASES);
                    },
                    err -> showError(tt.apply("bank.reconcile.fail"), err));
        });
    }

    private javafx.scene.control.TreeTableColumn<ReconNode, String> treeCol(
            String header, java.util.function.Function<ReconNode, String> getter, double width) {
        javafx.scene.control.TreeTableColumn<ReconNode, String> c =
                new javafx.scene.control.TreeTableColumn<>(header);
        c.setPrefWidth(width);
        c.setSortable(false);
        c.setCellValueFactory(cd -> new SimpleStringProperty(getter.apply(cd.getValue().getValue())));
        return c;
    }

    private String nodeDate(ReconNode n) {
        if (n.isPayment()) return n.payment.payDate() == null ? "" : n.payment.payDate().toString();
        return n.row.operationDate() == null ? "" : n.row.operationDate().toString();
    }

    private String nodeDescription(ReconNode n) {
        if (n.isPayment()) {
            ExistingPayment p = n.payment;
            StringBuilder b = new StringBuilder("    ↳ ")
                    .append(tt.apply("bank.reconcile.payment")).append(": ")
                    .append(p.paySource() == null ? "" : p.paySource());
            if (p.payEntryNumber() != null) b.append(" · nº ").append(p.payEntryNumber());
            if (p.payReference() != null && !p.payReference().isBlank()) b.append(" · ").append(p.payReference());
            if (p.payMethod() != null && !p.payMethod().isBlank()) b.append(" · ").append(p.payMethod());
            return b.toString();
        }
        String d = n.row.description() == null ? "" : n.row.description();
        if (n.row.counterpartyName() != null && !n.row.counterpartyName().isBlank()) {
            d = d + " · " + n.row.counterpartyName();
        }
        return d;
    }

    private String nodeAmount(ReconNode n) {
        java.math.BigDecimal a = n.isPayment() ? n.payment.payAmount() : n.row.amount();
        return a == null ? "" : a.toPlainString();
    }

    private String nodeCandidate(ReconNode n) {
        if (n.isPayment()) return "";
        if (n.row.invoiceNumber() == null && n.row.invoiceCounterparty() == null) return "";
        StringBuilder b = new StringBuilder();
        if (n.row.invoiceNumber() != null) b.append(n.row.invoiceNumber());
        if (n.row.invoiceCounterparty() != null && !n.row.invoiceCounterparty().isBlank()) {
            if (b.length() > 0) b.append(" · ");
            b.append(n.row.invoiceCounterparty());
        }
        return b.toString();
    }

    private String nodeState(ReconNode n) {
        if (n.isPayment() || n.row.state() == null) return "";
        return tt.apply("bank.reconcile.state." + n.row.state());
    }

    /** Nodo del árbol de conciliación: o un movimiento (con checkbox) o un pago existente. */
    private static final class ReconNode {
        final BankReconcileRow row;
        final ExistingPayment payment;
        final boolean selectable;
        final javafx.beans.property.BooleanProperty selected =
                new javafx.beans.property.SimpleBooleanProperty(false);

        ReconNode(BankReconcileRow row, boolean selectable) {
            this.row = row;
            this.payment = null;
            this.selectable = selectable;
            this.selected.set(selectable && row.suggested());
        }

        ReconNode(ExistingPayment payment) {
            this.row = null;
            this.payment = payment;
            this.selectable = false;
        }

        boolean isPayment() { return payment != null; }
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

    // ====================================================================
    //  COMP- Compensación (netting) de facturas
    // ====================================================================

    /**
     * Pestaña de compensación: propuestas por tercero (ventas 430 vs compras
     * 400 del mismo NIF), selección de facturas (multi-selección), ejecución
     * (asiento 400/430) y listado de compensaciones con justificante PDF y
     * reversión. Controles/datos arriba (patrón Slice 3V), auto-refresh por
     * RefreshBus.
     */
    public Node buildCompensationsTab() {
        TableView<CompensationProposal> proposals = new TableView<>();
        proposals.setPrefHeight(170);
        proposals.getColumns().addAll(List.of(
                col(tt.apply("comp.col.counterparty"),
                        p -> p.counterpartyName() == null || p.counterpartyName().isBlank()
                                ? p.nif() : p.counterpartyName(), 220),
                col(tt.apply("comp.col.nif"), CompensationProposal::nif, 110),
                col(tt.apply("comp.col.sales_pending"), p -> compMoney(p.salesPending()), 150),
                col(tt.apply("comp.col.purchase_pending"), p -> compMoney(p.purchasePending()), 150),
                col(tt.apply("comp.col.compensable"), p -> compMoney(p.compensable()), 130)));

        TableView<CompensationInvoice> sales = invoiceTable();
        TableView<CompensationInvoice> purchases = invoiceTable();

        Label compensableLbl = new Label();
        compensableLbl.getStyleClass().add("settings-section-title");

        Button compensar = new Button(tt.apply("comp.action.execute"));
        compensar.getStyleClass().add("primary-button");
        compensar.setDisable(true);

        TableView<CompensationRow> executed = new TableView<>();
        executed.setPrefHeight(150);
        executed.getColumns().addAll(List.of(
                col(tt.apply("comp.col.date"), r -> r.date() == null ? "" : r.date().toString(), 100),
                col(tt.apply("comp.col.counterparty"),
                        r -> r.counterpartyName() == null ? r.nif() : r.counterpartyName(), 200),
                col(tt.apply("comp.col.amount"), r -> compMoney(r.amount()), 120),
                col(tt.apply("comp.col.entry"), r -> r.entryNumber() > 0 ? "#" + r.entryNumber() : "", 80),
                col(tt.apply("comp.col.status"),
                        r -> tt.apply("comp.status." + (r.status() == null ? "ACTIVE" : r.status())), 110)));

        Runnable recompute = () -> compensableLbl.setText(
                tt.apply("comp.label.compensable") + " " + compMoney(minSelected(sales, purchases)) + " €");

        proposals.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            sales.getItems().clear();
            purchases.getItems().clear();
            compensar.setDisable(nv == null);
            if (nv == null) { recompute.run(); return; }
            async(() -> api.listCompensationInvoices(nv.nif()), lines -> {
                for (CompensationInvoice l : lines) {
                    if ("SALES".equals(l.invoiceKind())) sales.getItems().add(l);
                    else purchases.getItems().add(l);
                }
                sales.getSelectionModel().selectAll();
                purchases.getSelectionModel().selectAll();
                recompute.run();
            }, err -> showError(tt.apply("comp.error.load"), err));
        });
        sales.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<CompensationInvoice>) c -> recompute.run());
        purchases.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<CompensationInvoice>) c -> recompute.run());

        compensar.setOnAction(e -> {
            List<String> sIds = new ArrayList<>();
            for (CompensationInvoice i : sales.getSelectionModel().getSelectedItems()) sIds.add(i.invoiceId());
            List<String> pIds = new ArrayList<>();
            for (CompensationInvoice i : purchases.getSelectionModel().getSelectedItems()) pIds.add(i.invoiceId());
            if (sIds.isEmpty() || pIds.isEmpty()) {
                showInfo(tt.apply("comp.action.execute"), tt.apply("comp.msg.pick_both"));
                return;
            }
            Alert confirm = new Alert(AlertType.CONFIRMATION,
                    tt.apply("comp.confirm.body") + " " + compMoney(minSelected(sales, purchases)) + " €");
            confirm.setHeaderText(tt.apply("comp.confirm.title"));
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            async(() -> api.executeCompensation(sIds, pIds), res -> {
                RefreshBus.emit(RefreshBus.TOPIC_JOURNAL, RefreshBus.TOPIC_SALES, RefreshBus.TOPIC_PURCHASES);
                showInfo(tt.apply("comp.done.title"),
                        tt.apply("comp.done.body") + " " + compMoney(res.compensated()) + " €"
                                + (res.entryNumber() > 0 ? " (#" + res.entryNumber() + ")" : ""));
                loadProposals(proposals);
                loadExecuted(executed);
                sales.getItems().clear();
                purchases.getItems().clear();
                recompute.run();
            }, err -> showError(tt.apply("comp.error.execute"), err));
        });

        Button pdfBtn = new Button(tt.apply("comp.action.pdf"));
        pdfBtn.setOnAction(e -> {
            CompensationRow sel = executed.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            async(() -> api.compensationPdf(sel.id()),
                    bytes -> openPdfViewer(bytes, tt.apply("comp.pdf.title")),
                    err -> showError(tt.apply("comp.action.pdf"), err));
        });
        Button reverseBtn = new Button(tt.apply("comp.action.reverse"));
        reverseBtn.setOnAction(e -> {
            CompensationRow sel = executed.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            if (!"ACTIVE".equals(sel.status())) {
                showInfo(tt.apply("comp.action.reverse"), tt.apply("comp.msg.already_reversed"));
                return;
            }
            Alert confirm = new Alert(AlertType.CONFIRMATION, tt.apply("comp.confirm.reverse_body"));
            confirm.setHeaderText(tt.apply("comp.action.reverse"));
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            async(() -> { api.reverseCompensation(sel.id()); return Boolean.TRUE; }, ok -> {
                RefreshBus.emit(RefreshBus.TOPIC_JOURNAL, RefreshBus.TOPIC_SALES, RefreshBus.TOPIC_PURCHASES);
                loadProposals(proposals);
                loadExecuted(executed);
            }, err -> showError(tt.apply("comp.action.reverse"), err));
        });

        Button refresh = new Button(tt.apply("accounting.action.refresh"));
        refresh.setOnAction(e -> { loadProposals(proposals); loadExecuted(executed); });

        Label propTitle = sectionTitle("comp.section.proposals");
        Label salesTitle = sectionTitle("comp.section.sales");
        Label purchTitle = sectionTitle("comp.section.purchases");
        Label execTitle = sectionTitle("comp.section.executed");

        VBox salesBox = new VBox(4, salesTitle, sales);
        VBox purchBox = new VBox(4, purchTitle, purchases);
        VBox.setVgrow(sales, Priority.ALWAYS);
        VBox.setVgrow(purchases, Priority.ALWAYS);
        SplitPane split = new SplitPane(salesBox, purchBox);

        HBox actions = new HBox(10, compensar, compensableLbl);
        actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Label hint = new Label(tt.apply("comp.hint.multiselect"));
        hint.getStyleClass().add("settings-hint");

        HBox execActions = new HBox(8, pdfBtn, reverseBtn, refresh);

        VBox root = new VBox(10, propTitle, proposals, hint, split, actions,
                execTitle, executed, execActions);
        root.setPadding(new Insets(12));
        VBox.setVgrow(split, Priority.ALWAYS);

        RefreshBus.subscribe(RefreshBus.TOPIC_JOURNAL, () -> {
            loadProposals(proposals);
            loadExecuted(executed);
        }, root);

        loadProposals(proposals);
        loadExecuted(executed);
        return root;
    }

    private TableView<CompensationInvoice> invoiceTable() {
        TableView<CompensationInvoice> t = new TableView<>();
        t.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        t.getColumns().addAll(List.of(
                col(tt.apply("comp.col.invoice"), i -> i.invoiceNumber() == null ? "—" : i.invoiceNumber(), 140),
                col(tt.apply("comp.col.date"), i -> i.invoiceDate() == null ? "" : i.invoiceDate().toString(), 100),
                col(tt.apply("comp.col.total"), i -> compMoney(i.total()), 110),
                col(tt.apply("comp.col.pending"), i -> compMoney(i.pending()), 110),
                col(tt.apply("comp.col.due"), i -> i.due() ? "✓" : "", 60)));
        return t;
    }

    private BigDecimal minSelected(TableView<CompensationInvoice> sales,
                                   TableView<CompensationInvoice> purchases) {
        BigDecimal s = BigDecimal.ZERO;
        for (CompensationInvoice i : sales.getSelectionModel().getSelectedItems()) {
            if (i.pending() != null) s = s.add(i.pending());
        }
        BigDecimal p = BigDecimal.ZERO;
        for (CompensationInvoice i : purchases.getSelectionModel().getSelectedItems()) {
            if (i.pending() != null) p = p.add(i.pending());
        }
        return s.min(p);
    }

    private void loadProposals(TableView<CompensationProposal> table) {
        async(() -> api.listCompensationSuggestions(),
                rows -> table.setItems(FXCollections.observableArrayList(rows)),
                err -> logSilent("comp-proposals", err));
    }

    private void loadExecuted(TableView<CompensationRow> table) {
        async(() -> api.listCompensations(),
                rows -> table.setItems(FXCollections.observableArrayList(rows)),
                err -> logSilent("comp-executed", err));
    }

    private void openPdfViewer(byte[] bytes, String title) {
        try {
            com.benjagest.ui.support.PdfViewer viewer = new com.benjagest.ui.support.PdfViewer();
            viewer.loadFromBytes(bytes);
            javafx.stage.Stage st = new javafx.stage.Stage();
            st.setTitle(title);
            Button close = new Button(tt.apply("comp.pdf.close"));
            close.setOnAction(e -> st.close());
            HBox bar = new HBox(8, close);
            bar.setPadding(new Insets(8));
            VBox box = new VBox(bar, viewer);
            VBox.setVgrow(viewer, Priority.ALWAYS);
            javafx.scene.Scene sc = new javafx.scene.Scene(box, 900, 720);
            try {
                sc.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
            } catch (Exception ignore) {}
            st.setScene(sc);
            st.show();
        } catch (Exception ex) {
            showError(title, ex);
        }
    }

    private Label sectionTitle(String key) {
        Label l = new Label(tt.apply(key));
        l.getStyleClass().add("settings-section-title");
        return l;
    }

    private static String compMoney(BigDecimal v) {
        return v == null ? "0,00" : String.format(java.util.Locale.GERMANY, "%,.2f", v);
    }

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
        Alert a = new Alert(AlertType.ERROR, title + "\n\n" + humanize(err));
        a.showAndWait();
    }

    /**
     * Convierte el error técnico del backend en algo legible. El cliente lanza
     * "HTTP {code}: {cuerpo JSON}"; aquí se saca el mensaje del servidor y, para
     * un 5xx, se muestra un texto genérico en vez de filtrar SQL/stacktrace.
     */
    private String humanize(Throwable err) {
        String raw = err.getMessage() == null ? err.toString() : err.getMessage();
        if (raw == null) return "";
        java.util.regex.Matcher st = java.util.regex.Pattern
                .compile("\"status\"\\s*:\\s*(\\d{3})").matcher(raw);
        if (st.find() && st.group(1).startsWith("5")) {
            return tt.apply("accounting.error.server");
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"message\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(raw);
        if (m.find()) {
            return m.group(1).replace("\\\"", "\"").replace("\\n", " ").replace("\\r", "").trim();
        }
        return raw;
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
