package com.benjagest.ui.screens;

import com.benjagest.ui.service.BillingApiClient;
import com.benjagest.ui.support.Router;
import java.util.function.Function;
import javafx.concurrent.Task;
import javafx.collections.FXCollections;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * FAC-1 — Diálogos auto-contenidos de facturación, extraídos del God Object:
 * migraciones (baselines) guardadas y declaración del fabricante (VeriFactu).
 * Cero acoplamiento al editor de factura. El shell mantiene
 * {@code showMigrationBaselines()/showManufacturerDeclaration()} como wrappers.
 */
public class BillingDialogsScreen extends ScreenBase {

    /** Callback al visor PDF interno del shell. */
    public interface Host {
        void showInternalPdfViewer(byte[] bytes, java.nio.file.Path tempPath);
    }

    private final BillingApiClient billingApiClient;
    private final Host host;

    public BillingDialogsScreen(BillingApiClient billingApiClient,
                                Function<String, String> tt, Router router, Host host) {
        super(tt, router);
        this.billingApiClient = billingApiClient;
        this.host = host;
    }

    /** MIG-3 — Diálogo con las migraciones (baselines) guardadas + ver su PDF de prueba. */
    public void showMigrationBaselines() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(t("billing.config.migration.view_saved"));
        dialog.setHeaderText(null);
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        TableView<com.benjagest.ui.service.BillingApiClient.BaselineEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("billing.config.migration.saved.empty")));
        table.setPrefSize(680, 300);
        TableColumn<com.benjagest.ui.service.BillingApiClient.BaselineEntry, String> cSerie =
                new TableColumn<>(t("billing.config.migration.field.series"));
        cSerie.setCellValueFactory(c -> new SimpleStringProperty(nzDash(c.getValue().seriesCode())));
        TableColumn<com.benjagest.ui.service.BillingApiClient.BaselineEntry, String> cNum =
                new TableColumn<>(t("billing.config.migration.detected.number"));
        cNum.setCellValueFactory(c -> new SimpleStringProperty(nzDash(c.getValue().fullNumber())));
        TableColumn<com.benjagest.ui.service.BillingApiClient.BaselineEntry, String> cDate =
                new TableColumn<>(t("billing.config.migration.detected.date"));
        cDate.setCellValueFactory(c -> new SimpleStringProperty(nzDash(c.getValue().date())));
        TableColumn<com.benjagest.ui.service.BillingApiClient.BaselineEntry, String> cCust =
                new TableColumn<>(t("billing.config.migration.detected.customer"));
        cCust.setCellValueFactory(c -> new SimpleStringProperty(nzDash(c.getValue().customerName())));
        TableColumn<com.benjagest.ui.service.BillingApiClient.BaselineEntry, String> cTotal =
                new TableColumn<>(t("billing.config.migration.detected.total"));
        cTotal.setCellValueFactory(c -> new SimpleStringProperty(nzDash(c.getValue().total())));
        TableColumn<com.benjagest.ui.service.BillingApiClient.BaselineEntry, String> cWhen =
                new TableColumn<>(t("billing.config.migration.saved.registered"));
        cWhen.setCellValueFactory(c -> new SimpleStringProperty(shortIso(c.getValue().createdAt())));
        table.getColumns().addAll(java.util.List.of(cSerie, cNum, cDate, cCust, cTotal, cWhen));

        Button viewPdf = new Button(t("billing.config.migration.saved.view_pdf"));
        viewPdf.setGraphic(icon("fas-file-pdf"));
        viewPdf.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) ->
                viewPdf.setDisable(b == null || !b.hasEvidence()));
        viewPdf.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Task<byte[]> tk = new Task<>() {
                @Override protected byte[] call() throws Exception { return billingApiClient.getMigrationEvidence(sel.id()); }
            };
            tk.setOnSucceeded(e -> host.showInternalPdfViewer(tk.getValue(),
                    java.nio.file.Paths.get("prueba-migracion.pdf")));
            tk.setOnFailed(e -> showError(t("billing.config.migration.view_saved"),
                    tk.getException() == null ? "" : tk.getException().getMessage()));
            start(tk, "migration-evidence");
        });

        VBox box = new VBox(10, table, new HBox(viewPdf));
        box.setPrefWidth(700);
        dialog.getDialogPane().setContent(box);

        Task<java.util.List<com.benjagest.ui.service.BillingApiClient.BaselineEntry>> load = new Task<>() {
            @Override protected java.util.List<com.benjagest.ui.service.BillingApiClient.BaselineEntry> call() throws Exception {
                return billingApiClient.listMigrationBaselines();
            }
        };
        load.setOnSucceeded(e -> table.setItems(FXCollections.observableArrayList(load.getValue())));
        load.setOnFailed(e -> showError(t("billing.config.migration.view_saved"),
                load.getException() == null ? "" : load.getException().getMessage()));
        start(load, "migration-baselines");

        dialog.showAndWait();
    }

    public void showManufacturerDeclaration() {
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return billingApiClient.fetchManufacturerDeclaration(
                        com.benjagest.ui.service.UpdateService.APP_VERSION);
            }
        };
        task.setOnSucceeded(ev -> {
            String json = task.getValue();
            String formatted = formatManufacturerDeclaration(json);
            javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(formatted);
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount(20);
            area.setPrefColumnCount(70);
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setHeaderText(t("billing.config.manufacturer.dialog.title"));
            info.getDialogPane().setContent(area);
            info.getDialogPane().setPrefWidth(720);
            info.showAndWait();
        });
        task.setOnFailed(ev -> showError(t("billing.config.manufacturer.fail.title"),
                t("billing.config.manufacturer.fail.body")));
        start(task, "billing-config-manufacturer");
    }

    private String formatManufacturerDeclaration(String json) {
        StringBuilder sb = new StringBuilder();
        sb.append(t("billing.config.manufacturer.section.manufacturer")).append("\n");
        sb.append("  ").append(extract(json, "manufacturerName")).append("\n");
        sb.append("  NIF: ").append(extract(json, "manufacturerTaxIdentifier")).append("\n");
        sb.append("  Email: ").append(extract(json, "manufacturerEmail")).append("\n");
        sb.append("  ").append(extract(json, "manufacturerAddress")).append("\n\n");
        sb.append(t("billing.config.manufacturer.section.product")).append("\n");
        sb.append("  ").append(extract(json, "productName"))
                .append(" v").append(extract(json, "productVersion")).append("\n");
        sb.append("  ").append(extract(json, "productType")).append("\n\n");
        sb.append("  ").append(extract(json, "productFunctionalities")).append("\n\n");
        sb.append(t("billing.config.manufacturer.section.date")).append("\n");
        sb.append("  ").append(extract(json, "declarationDate")).append(" — ")
                .append(extract(json, "declarationPlace")).append("\n\n");
        sb.append(t("billing.config.manufacturer.section.commitment")).append("\n");
        sb.append("  ").append(extract(json, "complianceCommitment")).append("\n");
        return sb.toString();
    }

    // Copias locales de helpers compartidos del shell (stateless).
    private static String nzDash(String s) { return s == null || s.isBlank() ? "—" : s; }

    private String extract(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
                .matcher(json);
        if (!m.find()) return "";
        return m.group(1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }
}
