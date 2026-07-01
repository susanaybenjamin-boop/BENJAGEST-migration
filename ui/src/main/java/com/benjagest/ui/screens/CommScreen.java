package com.benjagest.ui.screens;

import com.benjagest.ui.model.AppMode;
import com.benjagest.ui.service.AdvisoryInvitationApiClient;
import com.benjagest.ui.service.AltaApiClient;
import com.benjagest.ui.support.Router;
import java.util.function.Function;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * SM-COMM — Módulo Comunicación (bloque UIR), extraído del God Object como
 * movimiento puro (mismas claves i18n y CSS, mismo comportamiento). Selector
 * de destinatario (asesoría vinculada en BUSINESS, cartera de clientes en
 * ADVISORY) + tabs Mensajes/Documentos compartiendo el destinatario activo.
 * Los dos paneles (buildCommMessagesPane/buildCommDocumentsPane) son públicos
 * porque Configuración → "Mi asesoría" vivía en el mismo rango del monolito;
 * hoy ese tab no los usa (solo el panel de vínculo), pero se dejan públicos
 * por si una futura pantalla necesita reutilizarlos.
 */
public class CommScreen extends ScreenBase {

    private final AltaApiClient altaApiClient;
    private final AdvisoryInvitationApiClient invitationsApi;
    private final AppMode appMode;

    public CommScreen(AltaApiClient altaApiClient, AdvisoryInvitationApiClient invitationsApi,
            AppMode appMode, Function<String, String> tt, Router router) {
        super(tt, router);
        this.altaApiClient = altaApiClient;
        this.invitationsApi = invitationsApi;
        this.appMode = appMode;
    }

    /** Item del selector del módulo Comunicación. id = otherCompanyId. */
    private record CommRecipient(String id, String label) {
        @Override public String toString() { return label == null ? id : label; }
    }

    /**
     * Módulo Comunicación (slice COMM-MOD 2026-06-11):
     *   - Selector arriba: en BUSINESS la asesoría vinculada (1 item),
     *     en ADVISORY la cartera de clientes.
     *   - Tabs Mensajes / Documentos compartiendo el destinatario activo.
     */
    public void showCommModule() {
        VBox content = content();
        StackPane commIcon = iconBubble("fas-comments", "module-title-icon");
        Label title = label(t("module.comm.title"), "module-detail-title");
        Label hint = new Label(t("module.comm.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");
        VBox commTitleBox = new VBox(2, title, hint);
        HBox commHeader = new HBox(16, commIcon, commTitleBox);
        commHeader.setAlignment(Pos.CENTER_LEFT);
        commHeader.getStyleClass().add("module-detail-header");

        ComboBox<CommRecipient> recipientCombo = new ComboBox<>();
        recipientCombo.setPromptText(t("module.comm.recipient.prompt"));
        recipientCombo.setMinWidth(360);

        SimpleStringProperty otherIdProperty = new SimpleStringProperty(null);
        recipientCombo.valueProperty().addListener((o, ov, nv) ->
                otherIdProperty.set(nv == null ? null : nv.id()));

        Label recipientLabel = new Label(appMode == AppMode.ADVISORY
                ? t("module.comm.recipient.client")
                : t("module.comm.recipient.advisory"));
        HBox selectorRow = new HBox(8, recipientLabel, recipientCombo);
        selectorRow.setAlignment(Pos.CENTER_LEFT);

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("settings-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab tMessages = new Tab(t("module.comm.tab.messages"),
                buildCommMessagesPane(otherIdProperty));
        tMessages.setGraphic(icon("fas-comments"));
        Tab tDocs = new Tab(t("module.comm.tab.documents"),
                buildCommDocumentsPane(otherIdProperty));
        tDocs.setGraphic(icon("fas-folder-open"));
        tabs.getTabs().addAll(tMessages, tDocs);

        // Carga inicial de destinatarios según modo
        Task<java.util.List<CommRecipient>> loadRecipients = new Task<>() {
            @Override protected java.util.List<CommRecipient> call() throws Exception {
                java.util.List<CommRecipient> out = new java.util.ArrayList<>();
                if (appMode == AppMode.ADVISORY) {
                    for (var c : altaApiClient.listAdvisoryPortfolio()) {
                        // Solo clientes con vínculo REAL aceptado: tienen
                        // su propia company y aceptaron la invitación.
                        // Las shadow companies (MANAGED_CLIENT) no tienen
                        // login y nadie podrá leer los mensajes desde el
                        // otro lado — por eso quedan fuera del selector.
                        if (!c.fullyLinked() || c.linkedCompanyId() == null) continue;
                        out.add(new CommRecipient(c.linkedCompanyId(), c.legalName()));
                    }
                } else {
                    var linked = invitationsApi.getLinkedAdvisory();
                    if (linked != null && linked.id() != null) {
                        out.add(new CommRecipient(linked.id(), linked.legalName()));
                    }
                }
                return out;
            }
        };
        loadRecipients.setOnSucceeded(ev -> {
            recipientCombo.getItems().setAll(loadRecipients.getValue());
            if (!loadRecipients.getValue().isEmpty()) {
                recipientCombo.getSelectionModel().selectFirst();
            }
        });
        loadRecipients.setOnFailed(ev -> showError(t("module.comm.recipient.fail.title"),
                loadRecipients.getException() == null ? "" :
                        loadRecipients.getException().getMessage()));
        start(loadRecipients, "comm-load-recipients");

        VBox.setVgrow(tabs, Priority.ALWAYS);
        content.getChildren().addAll(commHeader, selectorRow, tabs);
        setCenterAnimated(scroll(content));
    }

    /**
     * Pestaña "Mensajes" del módulo Comunicación. Recibe el destinatario
     * activo como observable — el selector externo lo controla. Cuando
     * cambia el observable, recarga el timeline.
     */
    public Node buildCommMessagesPane(
            javafx.beans.value.ObservableValue<String> otherIdObs) {
        VBox root = new VBox(12);
        root.setPadding(new Insets(12, 4, 4, 4));

        // Timeline
        ListView<com.benjagest.ui.model.AdvisoryMessageEntry> timeline =
                new ListView<>();
        timeline.getStyleClass().add("comm-timeline");
        Label timelinePlaceholder = new Label(t("module.comm.messages.pick_recipient"));
        timelinePlaceholder.getStyleClass().add("comm-empty");
        timelinePlaceholder.setWrapText(true);
        timeline.setPlaceholder(timelinePlaceholder);
        timeline.setCellFactory(lv -> new ListCell<com.benjagest.ui.model.AdvisoryMessageEntry>() {
            @Override protected void updateItem(
                    com.benjagest.ui.model.AdvisoryMessageEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText((String) null);
                    setGraphic((Node) null);
                    return;
                }
                boolean isA2C = com.benjagest.ui.model.AdvisoryMessageEntry.DIRECTION_A2C
                        .equals(item.direction());
                VBox bubble = new VBox(2);
                Label sender = new Label(isA2C
                        ? t("module.comm.sender.advisory")
                        : t("module.comm.sender.company"));
                sender.getStyleClass().add("comm-sender");
                if (!isA2C) {
                    sender.getStyleClass().add("comm-sender-company");
                }
                Label body = new Label(item.body());
                body.setWrapText(true);
                body.getStyleClass().add("comm-bubble-body");
                Label meta = new Label(item.createdAt() == null ? ""
                        : item.createdAt().length() > 16
                            ? item.createdAt().substring(0, 16) : item.createdAt());
                meta.getStyleClass().add("comm-meta");
                bubble.getChildren().addAll(sender, body, meta);
                bubble.setMaxWidth(520);
                bubble.getStyleClass().addAll("comm-bubble",
                        isA2C ? "comm-bubble-in" : "comm-bubble-out");
                HBox row = new HBox(bubble);
                row.setPadding(new Insets(3, 4, 3, 4));
                row.setAlignment(isA2C ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);
                setGraphic(row);
            }
        });

        // Caja de envío — composer inferior estilo chat.
        TextArea sendBox = new TextArea();
        sendBox.setPromptText(t("advisory.messages.send_prompt"));
        sendBox.setPrefRowCount(2);
        sendBox.setWrapText(true);
        Button sendBtn = new Button(t("advisory.messages.send"));
        sendBtn.setGraphic(icon("fas-paper-plane"));
        sendBtn.getStyleClass().add("button-primary");
        // Desactivado mientras no haya destinatario seleccionado.
        sendBtn.setDisable(otherIdObs.getValue() == null || otherIdObs.getValue().isBlank());
        HBox sendRow = new HBox(8, sendBox, sendBtn);
        sendRow.getStyleClass().add("comm-composer");
        HBox.setHgrow(sendBox, Priority.ALWAYS);
        sendRow.setAlignment(Pos.BOTTOM_RIGHT);

        Runnable reloadTimeline = () -> {
            String other = otherIdObs.getValue();
            if (other == null || other.isBlank()) {
                timelinePlaceholder.setText(t("module.comm.messages.pick_recipient"));
                timeline.setItems(FXCollections.observableArrayList());
                return;
            }
            timelinePlaceholder.setText(t("module.comm.messages.empty"));
            Task<java.util.List<com.benjagest.ui.model.AdvisoryMessageEntry>> task = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.AdvisoryMessageEntry> call() throws Exception {
                    java.util.List<com.benjagest.ui.model.AdvisoryMessageEntry> msgs =
                            altaApiClient.listAdvisoryThread(other);
                    try { altaApiClient.markAdvisoryThreadRead(other); } catch (Exception ignore) {}
                    return msgs;
                }
            };
            task.setOnSucceeded(ev -> {
                timeline.setItems(FXCollections.observableArrayList(task.getValue()));
                if (!task.getValue().isEmpty()) {
                    timeline.scrollTo(task.getValue().size() - 1);
                }
            });
            task.setOnFailed(ev -> { /* puede no haber thread aún — silencio */ });
            start(task, "comm-thread-load");
        };

        otherIdObs.addListener((o, ov, nv) -> {
            sendBtn.setDisable(nv == null || nv.isBlank());
            reloadTimeline.run();
        });
        sendBtn.setOnAction(ev -> {
            String other = otherIdObs.getValue();
            if (other == null || other.isBlank()) return;
            String body = sendBox.getText();
            if (body == null || body.isBlank()) return;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    altaApiClient.sendAdvisoryMessage(other, body, null);
                    return null;
                }
            };
            task.setOnSucceeded(e -> { sendBox.clear(); reloadTimeline.run(); });
            task.setOnFailed(e -> showError(t("advisory.messages.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "comm-send");
        });

        reloadTimeline.run();

        // Conversación arriba (crece), composer abajo (siempre visible) — patrón chat.
        VBox body = new VBox(10, timeline, sendRow);
        VBox.setVgrow(timeline, Priority.ALWAYS);
        VBox.setVgrow(body, Priority.ALWAYS);
        root.getChildren().add(body);
        return root;
    }

    /**
     * 2026-06-10 noche — Sub-pestaña "Documentos" con upload multipart
     * real + review + download. Backend AdvisoryDocumentUploadController
     * añade endpoints upload/download al service V78.
     */
    public Node buildCommDocumentsPane(
            javafx.beans.value.ObservableValue<String> otherIdObs) {
        VBox root = new VBox(12);
        root.setPadding(new Insets(12, 4, 4, 4));

        TableView<com.benjagest.ui.model.AdvisoryDocumentEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("advisory.documents.empty")));

        TableColumn<com.benjagest.ui.model.AdvisoryDocumentEntry, String> cWhen =
                new TableColumn<>(t("advisory.documents.col.date"));
        cWhen.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().createdAt() == null || c.getValue().createdAt().length() < 16
                        ? c.getValue().createdAt()
                        : c.getValue().createdAt().substring(0, 16)));
        cWhen.setPrefWidth(140);
        cWhen.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.AdvisoryDocumentEntry, String> cDir =
                new TableColumn<>(t("advisory.documents.col.dir"));
        cDir.setCellValueFactory(c -> new SimpleStringProperty(
                "A2C".equals(c.getValue().direction())
                        ? t("advisory.documents.dir.a2c")
                        : t("advisory.documents.dir.c2a")));
        cDir.setPrefWidth(120);
        TableColumn<com.benjagest.ui.model.AdvisoryDocumentEntry, String> cTitle =
                new TableColumn<>(t("advisory.documents.col.title"));
        cTitle.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().title()));
        TableColumn<com.benjagest.ui.model.AdvisoryDocumentEntry, String> cSize =
                new TableColumn<>(t("advisory.documents.col.size"));
        cSize.setCellValueFactory(c -> new SimpleStringProperty(
                humanSize(c.getValue().fileSizeBytes())));
        cSize.setPrefWidth(90);
        TableColumn<com.benjagest.ui.model.AdvisoryDocumentEntry, String> cStatus =
                new TableColumn<>(t("advisory.documents.col.status"));
        cStatus.setCellValueFactory(c -> new SimpleStringProperty(
                humanizeDocStatus(c.getValue().status())));
        cStatus.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.AdvisoryDocumentEntry, String> cNote =
                new TableColumn<>(t("advisory.documents.col.note"));
        cNote.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().note()));

        table.getColumns().addAll(java.util.List.of(
                cWhen, cDir, cTitle, cSize, cStatus, cNote));

        Button uploadBtn = new Button(t("advisory.documents.upload"));
        uploadBtn.setGraphic(icon("fas-upload"));
        uploadBtn.getStyleClass().add("button-primary");
        uploadBtn.setDisable(otherIdObs.getValue() == null || otherIdObs.getValue().isBlank());
        Button downloadBtn = new Button(t("advisory.documents.download"));
        downloadBtn.setGraphic(icon("fas-download"));
        downloadBtn.setDisable(true);
        Button acceptBtn = new Button(t("advisory.documents.accept"));
        acceptBtn.setGraphic(icon("fas-check"));
        acceptBtn.setDisable(true);
        Button rejectBtn = new Button(t("advisory.documents.reject"));
        rejectBtn.setGraphic(icon("fas-times"));
        rejectBtn.setDisable(true);

        HBox actions = new HBox(8, uploadBtn, downloadBtn, acceptBtn, rejectBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        Runnable reloadDocs = () -> {
            String other = otherIdObs.getValue();
            if (other == null || other.isBlank()) { table.getItems().clear(); return; }
            Task<java.util.List<com.benjagest.ui.model.AdvisoryDocumentEntry>> t = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.AdvisoryDocumentEntry> call() throws Exception {
                    return altaApiClient.listAdvisoryDocuments(other);
                }
            };
            t.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(t.getValue())));
            t.setOnFailed(ev -> { /* puede no haber thread aún — silencio */ });
            start(t, "comm-docs-list");
        };
        otherIdObs.addListener((o, ov, nv) -> {
            uploadBtn.setDisable(nv == null || nv.isBlank());
            reloadDocs.run();
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            boolean sel = nv != null;
            downloadBtn.setDisable(!sel);
            boolean reviewable = sel
                    && com.benjagest.ui.model.AdvisoryDocumentEntry.STATUS_UPLOADED.equals(nv.status());
            acceptBtn.setDisable(!reviewable);
            rejectBtn.setDisable(!reviewable);
        });

        uploadBtn.setOnAction(ev -> {
            String other = otherIdObs.getValue();
            if (other == null || other.isBlank()) return;
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle(t("advisory.documents.upload"));
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                    "PDF / PNG / JPG / DOCX / XLSX",
                    "*.pdf", "*.png", "*.jpg", "*.jpeg", "*.docx", "*.xlsx"));
            java.io.File f = fc.showOpenDialog(root.getScene().getWindow());
            if (f == null) return;
            TextInputDialog td = new TextInputDialog(f.getName());
            td.setTitle(t("advisory.documents.upload"));
            td.setHeaderText(t("advisory.documents.title_prompt"));
            String title2 = td.showAndWait().orElse(f.getName());
            Task<Void> up = new Task<>() {
                @Override protected Void call() throws Exception {
                    altaApiClient.uploadAdvisoryDocument(other, f, title2);
                    return null;
                }
            };
            up.setOnSucceeded(s -> reloadDocs.run());
            up.setOnFailed(s -> showError(t("advisory.documents.fail.title"),
                    up.getException() == null ? "" : up.getException().getMessage()));
            start(up, "comm-docs-upload");
        });
        downloadBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle(t("advisory.documents.download"));
            String filename = sel.filePath() == null ? sel.title()
                    : java.nio.file.Paths.get(sel.filePath()).getFileName().toString();
            int dash = filename.indexOf('-');
            if (dash > 0 && dash < 40) filename = filename.substring(dash + 1);
            fc.setInitialFileName(filename);
            java.io.File target = fc.showSaveDialog(root.getScene().getWindow());
            if (target == null) return;
            Task<byte[]> dl = new Task<>() {
                @Override protected byte[] call() throws Exception {
                    return altaApiClient.downloadAdvisoryDocument(sel.id());
                }
            };
            dl.setOnSucceeded(s -> {
                try {
                    java.nio.file.Files.write(target.toPath(), dl.getValue());
                    showInfo(t("advisory.documents.dl.ok.title"),
                            t("advisory.documents.dl.ok.body") + "\n"
                                    + target.getAbsolutePath());
                } catch (java.io.IOException ex) {
                    showError(t("advisory.documents.fail.title"), ex.getMessage());
                }
            });
            dl.setOnFailed(s -> showError(t("advisory.documents.fail.title"),
                    dl.getException() == null ? "" : dl.getException().getMessage()));
            start(dl, "advisory-docs-download");
        });
        acceptBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    altaApiClient.reviewAdvisoryDocument(sel.id(),
                            com.benjagest.ui.model.AdvisoryDocumentEntry.STATUS_ACCEPTED, null);
                    return null;
                }
            };
            task.setOnSucceeded(s -> reloadDocs.run());
            task.setOnFailed(s -> showError(t("advisory.documents.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "advisory-docs-accept");
        });
        rejectBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            TextInputDialog td = new TextInputDialog();
            td.setTitle(t("advisory.documents.reject"));
            td.setHeaderText(t("advisory.documents.reject_prompt"));
            String note = td.showAndWait().orElse("");
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    altaApiClient.reviewAdvisoryDocument(sel.id(),
                            com.benjagest.ui.model.AdvisoryDocumentEntry.STATUS_REJECTED, note);
                    return null;
                }
            };
            task.setOnSucceeded(s -> reloadDocs.run());
            task.setOnFailed(s -> showError(t("advisory.documents.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "comm-docs-reject");
        });

        reloadDocs.run();

        VBox.setVgrow(table, Priority.ALWAYS);
        root.getChildren().addAll(actions, table);
        return root;
    }

    private String humanizeDocStatus(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return switch (raw) {
            case "UPLOADED" -> t("advisory.documents.status.uploaded");
            case "REVIEWED" -> t("advisory.documents.status.reviewed");
            case "ACCEPTED" -> t("advisory.documents.status.accepted");
            case "REJECTED" -> t("advisory.documents.status.rejected");
            default -> raw;
        };
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
