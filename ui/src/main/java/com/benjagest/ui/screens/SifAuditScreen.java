package com.benjagest.ui.screens;

import com.benjagest.ui.service.BillingApiClient;
import com.benjagest.ui.support.Router;
import java.util.List;
import java.util.function.Function;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * FAC-4 — Bloque "Auditoría SIF" del config tab: Registro de Eventos del SIF
 * (RD 1007/2023) — listado filtrable, verificación de integridad de la cadena
 * y export PDF/CSV verificable. SOLO LECTURA: no muta el registro (regla SIF
 * inalterable). Construye la sección vía {@link #buildSection()}.
 */
public class SifAuditScreen extends ScreenBase {

    private final BillingApiClient billingApiClient;
    private TableView<com.benjagest.ui.model.SifEventEntry> sifEventsTable;
    private ComboBox<String> sifEventTypeFilter;

    public SifAuditScreen(BillingApiClient billingApiClient,
                          Function<String, String> tt, Router router) {
        super(tt, router);
        this.billingApiClient = billingApiClient;
    }

    /** Traduce el código de tipo de evento SIF (INVOICE_VALIDATED → "Factura validada"…). */
    private String localizedSifEventType(String code) {
        if (code == null || code.isBlank()) return "";
        return t("sif.event_type." + code);
    }

    /** Humaniza el payload JSON del evento SIF a "Etiqueta: valor · …". */
    private String humanizeSifPayload(String payload) {
        if (payload == null || payload.isBlank()) return "";
        java.util.Map<String, String> map = parseDataMap(payload);
        if (map.isEmpty()) return payload;
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : map.entrySet()) {
            String label = t("sif.payload." + e.getKey());
            if (label.equals("sif.payload." + e.getKey())) label = e.getKey();
            String val = e.getValue() == null ? "" : e.getValue();
            if (val.length() > 14) val = val.substring(0, 12) + "…";
            if (sb.length() > 0) sb.append("   ·   ");
            sb.append(label).append(": ").append(val);
        }
        return sb.toString();
    }

    public Node buildSection() {
        Label header = label(t("billing.config.sif.section"), "settings-section-title");
        Label hint = new Label(t("billing.config.sif.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        sifEventTypeFilter = new ComboBox<>();
        sifEventTypeFilter.getItems().addAll(
                t("list.filter.all"),
                "SYSTEM_START", "SYSTEM_STOP",
                "INVOICE_VALIDATED", "INVOICE_VOIDED",
                "ANOMALY_DETECTION_INVOICES_RUN", "ANOMALY_DETECTION_INVOICES_HIT",
                "ANOMALY_DETECTION_EVENTS_RUN", "ANOMALY_DETECTION_EVENTS_HIT",
                "BACKUP_RESTORED",
                "EXPORT_INVOICES", "EXPORT_EVENTS",
                "SUMMARY_6H", "SUMMARY_SHUTDOWN");
        sifEventTypeFilter.getSelectionModel().selectFirst();
        sifEventTypeFilter.getStyleClass().add("form-input");
        sifEventTypeFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) {
                return s == null || s.equals(t("list.filter.all")) ? s : localizedSifEventType(s);
            }
            @Override public String fromString(String s) { return s; }
        });

        sifEventsTable = new TableView<>();
        sifEventsTable.getStyleClass().add("data-table");
        sifEventsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        sifEventsTable.setPlaceholder(new Label(t("billing.config.sif.placeholder.empty")));
        sifEventsTable.setPrefHeight(220);

        TableColumn<com.benjagest.ui.model.SifEventEntry, String> colWhen =
                new TableColumn<>(t("billing.config.sif.col.when"));
        colWhen.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().generatedAtIso()));
        colWhen.setPrefWidth(160);
        colWhen.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.SifEventEntry, String> colType =
                new TableColumn<>(t("billing.config.sif.col.type"));
        colType.setCellValueFactory(c -> new SimpleStringProperty(localizedSifEventType(c.getValue().eventType())));
        colType.setPrefWidth(220);
        TableColumn<com.benjagest.ui.model.SifEventEntry, String> colHash =
                new TableColumn<>(t("billing.config.sif.col.hash"));
        colHash.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().hashCurrent() == null || c.getValue().hashCurrent().length() < 16
                        ? "" : c.getValue().hashCurrent().substring(0, 16) + "…"));
        colHash.setPrefWidth(150);
        TableColumn<com.benjagest.ui.model.SifEventEntry, String> colPayload =
                new TableColumn<>(t("billing.config.sif.col.payload"));
        colPayload.setCellValueFactory(c -> new SimpleStringProperty(
                humanizeSifPayload(c.getValue().payload())));
        sifEventsTable.getColumns().addAll(List.of(colWhen, colType, colHash, colPayload));

        Button refresh = new Button(t("billing.config.sif.refresh"));
        refresh.setGraphic(icon("fas-sync"));
        refresh.setOnAction(event -> refreshSifEvents());

        Button verifySif = new Button(t("billing.config.sif.verify"));
        verifySif.setGraphic(icon("fas-shield-alt"));
        verifySif.setOnAction(event -> verifySifEventChain());

        HBox filterRow = new HBox(8, sifEventTypeFilter, refresh, verifySif);
        filterRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // VF-EVENTS-EXPORT: export PDF/CSV verificable. Defaults: trimestre actual.
        java.time.LocalDate today = java.time.LocalDate.now();
        int quarter = (today.getMonthValue() - 1) / 3;
        java.time.LocalDate quarterStart = java.time.LocalDate.of(today.getYear(), quarter * 3 + 1, 1);
        java.time.LocalDate quarterEnd = quarterStart.plusMonths(3).minusDays(1);
        DatePicker fromPicker = new DatePicker(quarterStart);
        DatePicker toPicker = new DatePicker(quarterEnd);
        Button exportPdfBtn = new Button(t("billing.config.sif.export.pdf"));
        exportPdfBtn.setGraphic(icon("fas-file-pdf"));
        exportPdfBtn.getStyleClass().add("button-primary");
        exportPdfBtn.setOnAction(ev -> downloadSifExport("pdf",
                fromPicker.getValue(), toPicker.getValue(),
                sifEventTypeFilter == null ? null : sifEventTypeFilter.getValue()));
        Button exportCsvBtn = new Button(t("billing.config.sif.export.csv"));
        exportCsvBtn.setGraphic(icon("fas-file-csv"));
        exportCsvBtn.setOnAction(ev -> downloadSifExport("csv",
                fromPicker.getValue(), toPicker.getValue(),
                sifEventTypeFilter == null ? null : sifEventTypeFilter.getValue()));
        Label exportTitle = label(t("billing.config.sif.export.title"), "settings-section-title");
        Label exportHint = new Label(t("billing.config.sif.export.hint"));
        exportHint.setWrapText(true);
        exportHint.getStyleClass().add("settings-hint");
        HBox exportRow = new HBox(8,
                label(t("billing.config.sif.export.from"), "form-label"), fromPicker,
                label(t("billing.config.sif.export.to"), "form-label"), toPicker,
                exportPdfBtn, exportCsvBtn);
        exportRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox exportBlock = new VBox(8, new Separator(), exportTitle, exportHint, exportRow);

        refreshSifEvents();

        return new VBox(8, header, hint, filterRow, sifEventsTable, exportBlock);
    }

    /** Descarga el export del Registro de Eventos del SIF. */
    private void downloadSifExport(String format,
                                    java.time.LocalDate from, java.time.LocalDate to,
                                    String selectedTypeFilter) {
        if (from == null || to == null || from.isAfter(to)) {
            showError(t("settings.audit.export.fail.range.title"),
                    t("settings.audit.export.fail.range.body"));
            return;
        }
        String filter = selectedTypeFilter;
        if (filter != null && ("(todos)".equals(filter) || "(all)".equals(filter)
                || t("list.filter.all").equals(filter))) {
            filter = null;
        }
        String eventType = filter;
        Task<byte[]> task = new Task<>() {
            @Override protected byte[] call() throws Exception {
                return billingApiClient.exportSifEvents(format,
                        from.toString(), to.toString(), eventType);
            }
        };
        task.setOnSucceeded(ev -> {
            byte[] body = task.getValue();
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setInitialFileName("sif-events-" + from + "_" + to + "." + format);
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                    format.toUpperCase(), "*." + format));
            javafx.stage.Window win = sifEventsTable.getScene() == null
                    ? null : sifEventsTable.getScene().getWindow();
            java.io.File target = fc.showSaveDialog(win);
            if (target == null) return;
            try {
                java.nio.file.Files.write(target.toPath(), body);
                showInfo(t("settings.audit.export.ok.title"),
                        t("settings.audit.export.ok.body") + "\n" + target.getAbsolutePath());
                refreshSifEvents();
            } catch (java.io.IOException ex) {
                showError(t("settings.audit.export.fail.write.title"), ex.getMessage());
            }
        });
        task.setOnFailed(ev -> showError(t("settings.audit.export.fail.title"),
                t("settings.audit.export.fail.body")));
        start(task, "sif-export-" + format);
    }

    private void refreshSifEvents() {
        if (sifEventsTable == null) return;
        String selected = sifEventTypeFilter == null ? null : sifEventTypeFilter.getValue();
        String filter = (selected == null
                || t("list.filter.all").equals(selected)
                || "(todos)".equals(selected)
                || "(all)".equals(selected))
                ? null : selected;
        Task<java.util.List<com.benjagest.ui.model.SifEventEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.SifEventEntry> call() throws Exception {
                return billingApiClient.listSifEvents(filter);
            }
        };
        task.setOnSucceeded(event -> sifEventsTable.getItems().setAll(task.getValue()));
        task.setOnFailed(event -> sifEventsTable.getItems().clear());
        start(task, "billing-sif-events-list");
    }

    private void verifySifEventChain() {
        Task<com.benjagest.ui.model.SifEventIntegrityResult> task = new Task<>() {
            @Override
            protected com.benjagest.ui.model.SifEventIntegrityResult call() throws Exception {
                return billingApiClient.verifySifEventChain();
            }
        };
        task.setOnSucceeded(event -> {
            com.benjagest.ui.model.SifEventIntegrityResult result = task.getValue();
            if (result.ok()) {
                Alert ok = new Alert(Alert.AlertType.INFORMATION,
                        t("billing.config.sif.verify.ok.prefix") + result.totalChecked()
                                + t("billing.config.sif.verify.ok.suffix"),
                        ButtonType.OK);
                ok.setHeaderText(null);
                ok.showAndWait();
            } else {
                String broken = result.brokenEventType() == null ? "—" : result.brokenEventType();
                String body = t("billing.config.sif.verify.broken.prefix") + broken + "\n"
                        + (result.reason() == null ? "" : result.reason());
                showError(t("billing.config.sif.verify.broken.title"), body);
            }
        });
        task.setOnFailed(event -> showError(
                t("billing.config.sif.verify.fail.title"),
                t("billing.config.sif.verify.fail.body")));
        start(task, "billing-sif-events-verify");
    }

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
}
