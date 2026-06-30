package com.benjagest.ui.screens;

import com.benjagest.ui.model.*;
import com.benjagest.ui.service.*;
import com.benjagest.ui.support.*;
import java.time.*;
import java.util.*;
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

/** Modulo DEHu (bandeja) con polling. Extraido en UIR-8. El shell guarda una
 *  instancia unica (poller singleton) y expone currentModule() en el Router. */
public class DehuScreen extends ScreenBase {

    private final LaborApiClient laborApiClient;
    private javafx.animation.Timeline dehuPoller;

    public DehuScreen(LaborApiClient laborApiClient, Function<String, String> tt, Router router) {
        super(tt, router);
        this.laborApiClient = laborApiClient;
    }

    private TableView<com.benjagest.ui.model.DehuNotificationEntry> dehuTable;

    public void show() {
        Task<DehuBundle> task = new Task<>() {
            @Override protected DehuBundle call() throws Exception {
                return new DehuBundle(
                        laborApiClient.listDehu(null, 200),
                        laborApiClient.dehuSummary());
            }
        };
        task.setOnSucceeded(ev -> setCenterAnimated(scroll(dehuView(task.getValue()))));
        task.setOnFailed(ev -> setCenterAnimated(scroll(errorPanel(t("dehu.load_failed")))));
        start(task, "dehu-load");
        startDehuPolling();
    }

    /**
     * Arranca el polling de la bandeja DEHú la primera vez que el
     * usuario entra al módulo. El Timeline persiste durante la
     * sesión; cada tick comprueba {@code currentModule} y solo
     * recarga si seguimos viendo DEHú (evita peticiones inútiles
     * cuando el usuario navega a otra pantalla).
     */
    private void startDehuPolling() {
        if (dehuPoller != null) return;
        // 15s — las notificaciones DEHú no son tan urgentes como una
        // invitación de asesoría, pero sí deben aparecer sin tener
        // que refrescar a mano.
        dehuPoller = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(
                        javafx.util.Duration.seconds(15),
                        ev -> pollDehu()));
        dehuPoller.setCycleCount(javafx.animation.Animation.INDEFINITE);
        dehuPoller.play();
    }

    public void stopDehuPolling() {
        if (dehuPoller != null) {
            dehuPoller.stop();
            dehuPoller = null;
        }
    }

    /**
     * Tick del poller DEHú. Solo refresca si la pantalla activa es
     * DEHú; en caso contrario, no hace petición. Silencioso (sin
     * animación) para no marear con parpadeo cada 15s.
     */
    private void pollDehu() {
        if (!"dehu".equals(router.currentModule())) return;
        if (!com.benjagest.ui.service.AuthSession.get().isAuthenticated()) return;
        Task<DehuBundle> task = new Task<>() {
            @Override protected DehuBundle call() throws Exception {
                return new DehuBundle(
                        laborApiClient.listDehu(null, 200),
                        laborApiClient.dehuSummary());
            }
        };
        task.setOnSucceeded(ev -> {
            // Reemplazo silencioso del contenido — no usamos
            // setCenterAnimated para evitar la animación cada 15s.
            if ("dehu".equals(router.currentModule())) {
                setCenterSilent(scroll(dehuView(task.getValue())));
            }
        });
        task.setOnFailed(ev -> { /* silencio: el siguiente tick lo intenta otra vez */ });
        start(task, "dehu-poll");
    }

    private record DehuBundle(
            java.util.List<com.benjagest.ui.model.DehuNotificationEntry> notifications,
            com.benjagest.ui.model.DehuSummary summary
    ) {}

    private VBox dehuView(DehuBundle bundle) {
        VBox content = content();
        Label title = new Label(t("dehu.title"));
        title.getStyleClass().add("module-detail-title");
        Label subtitle = new Label(t("dehu.subtitle"));
        subtitle.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, subtitle);
        StackPane moduleIcon = iconBubble("fas-bell", "module-title-icon");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button newBtn = new Button(t("dehu.action.new"));
        newBtn.setGraphic(icon("fas-plus"));
        newBtn.setOnAction(ev -> showDehuEditor());

        HBox header = new HBox(16, titleBox, moduleIcon, spacer, newBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        // Resumen: 3 chips coloreados según pendientes / a caducar / caducadas
        var s = bundle.summary();
        Label chipPending = new Label(t("dehu.summary.pending") + ": " + s.pending());
        chipPending.getStyleClass().add(s.pending() > 0 ? "settings-section-title" : "settings-hint");
        Label chipSoon = new Label(t("dehu.summary.soon") + ": " + s.expiringSoon());
        chipSoon.getStyleClass().add(s.expiringSoon() > 0 ? "settings-section-title" : "settings-hint");
        Label chipExpired = new Label(t("dehu.summary.expired") + ": " + s.expired());
        chipExpired.getStyleClass().add("settings-hint");
        HBox summaryRow = new HBox(20, chipPending, chipSoon, chipExpired);
        summaryRow.setPadding(new Insets(8));

        dehuTable = new TableView<>();
        dehuTable.getStyleClass().add("data-table");
        dehuTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        dehuTable.setPlaceholder(new Label(t("dehu.placeholder.empty")));
        TableColumn<com.benjagest.ui.model.DehuNotificationEntry, String> cIssued =
                new TableColumn<>(t("dehu.col.issued"));
        cIssued.setCellValueFactory(c -> new SimpleStringProperty(shortIso(c.getValue().issuedAt())));
        cIssued.setPrefWidth(150);
        cIssued.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.DehuNotificationEntry, String> cExpires =
                new TableColumn<>(t("dehu.col.expires"));
        cExpires.setCellValueFactory(c -> new SimpleStringProperty(shortIso(c.getValue().expiresAt())));
        cExpires.setPrefWidth(150);
        cExpires.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.DehuNotificationEntry, String> cOrgan =
                new TableColumn<>(t("dehu.col.organism"));
        cOrgan.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().organismName()));
        cOrgan.setPrefWidth(200);
        TableColumn<com.benjagest.ui.model.DehuNotificationEntry, String> cSubject =
                new TableColumn<>(t("dehu.col.subject"));
        cSubject.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().subject()));
        TableColumn<com.benjagest.ui.model.DehuNotificationEntry, String> cStatus =
                new TableColumn<>(t("dehu.col.status"));
        cStatus.setCellValueFactory(c -> new SimpleStringProperty(t("dehu.status." + c.getValue().status())));
        cStatus.setPrefWidth(110);
        dehuTable.getColumns().addAll(java.util.List.of(cIssued, cExpires, cOrgan, cSubject, cStatus));
        dehuTable.setItems(FXCollections.observableArrayList(bundle.notifications()));

        Button readBtn = new Button(t("dehu.action.read"));
        readBtn.setGraphic(icon("fas-envelope-open"));
        readBtn.setDisable(true);
        readBtn.setOnAction(ev -> {
            var sel = dehuTable.getSelectionModel().getSelectedItem();
            if (sel != null) markDehuRead(sel);
        });
        Button dismissBtn = new Button(t("dehu.action.dismiss"));
        dismissBtn.setGraphic(icon("fas-archive"));
        dismissBtn.setDisable(true);
        dismissBtn.setOnAction(ev -> {
            var sel = dehuTable.getSelectionModel().getSelectedItem();
            if (sel != null) dismissDehu(sel);
        });
        dehuTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            readBtn.setDisable(nv == null || "READ".equals(nv == null ? "" : nv.status()));
            dismissBtn.setDisable(nv == null || "DISMISSED".equals(nv == null ? "" : nv.status()));
        });
        HBox actions = new HBox(8, readBtn, dismissBtn);
        actions.getStyleClass().add("settings-actions");

        VBox body = new VBox(12, summaryRow, dehuTable);
        VBox.setVgrow(dehuTable, Priority.ALWAYS);
        content.getChildren().addAll(header, body, actions);
        return content;
    }

    private void showDehuEditor() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("dehu.editor.title"));
        ButtonType saveBt = new ButtonType(t("dehu.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField dehuIdField = new TextField();
        TextField nifField = new TextField();
        TextField organField = new TextField();
        TextField organCodeField = new TextField();
        TextField procField = new TextField();
        TextField procCodeField = new TextField();
        TextField subjectField = new TextField();
        TextField issuedField = new TextField(LocalDate.now().toString() + "T00:00:00Z");
        TextField expiresField = new TextField(LocalDate.now().plusDays(10).toString() + "T00:00:00Z");
        TextField csvField = new TextField();
        TextField urlField = new TextField();
        TextArea notes = new TextArea(); notes.setPrefRowCount(2);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(6); g.setPadding(new Insets(10));
        int row = 0;
        g.add(new Label(t("dehu.editor.dehu_id")), 0, row); g.add(dehuIdField, 1, row, 3, 1); row++;
        g.add(new Label(t("dehu.editor.nif")), 0, row); g.add(nifField, 1, row);
        g.add(new Label(t("dehu.editor.subject")), 2, row); g.add(subjectField, 3, row); row++;
        g.add(new Label(t("dehu.editor.organism")), 0, row); g.add(organField, 1, row);
        g.add(new Label(t("dehu.editor.organism_code")), 2, row); g.add(organCodeField, 3, row); row++;
        g.add(new Label(t("dehu.editor.procedure")), 0, row); g.add(procField, 1, row);
        g.add(new Label(t("dehu.editor.procedure_code")), 2, row); g.add(procCodeField, 3, row); row++;
        g.add(new Label(t("dehu.editor.issued")), 0, row); g.add(issuedField, 1, row);
        g.add(new Label(t("dehu.editor.expires")), 2, row); g.add(expiresField, 3, row); row++;
        g.add(new Label(t("dehu.editor.csv")), 0, row); g.add(csvField, 1, row);
        g.add(new Label(t("dehu.editor.url")), 2, row); g.add(urlField, 3, row); row++;
        g.add(new Label(t("dehu.editor.notes")), 0, row); g.add(notes, 1, row, 3, 1);

        installDialog(dialog, g);
        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            com.benjagest.ui.model.DehuNotificationEntry payload = new com.benjagest.ui.model.DehuNotificationEntry(
                    null,
                    blankToNullOrSelf(dehuIdField.getText()),
                    blankToNullOrSelf(nifField.getText()),
                    organField.getText().trim(),
                    blankToNullOrSelf(organCodeField.getText()),
                    blankToNullOrSelf(procField.getText()),
                    blankToNullOrSelf(procCodeField.getText()),
                    subjectField.getText().trim(),
                    blankToNullOrSelf(issuedField.getText()),
                    blankToNullOrSelf(expiresField.getText()),
                    null, null, "PENDING",
                    blankToNullOrSelf(csvField.getText()),
                    blankToNullOrSelf(urlField.getText()),
                    null,
                    blankToNullOrSelf(notes.getText())
            );
            Task<com.benjagest.ui.model.DehuNotificationEntry> task = new Task<>() {
                @Override protected com.benjagest.ui.model.DehuNotificationEntry call() throws Exception {
                    return laborApiClient.createDehu(payload);
                }
            };
            task.setOnSucceeded(ev -> show());
            task.setOnFailed(ev -> showError(t("dehu.editor.fail.title"), t("dehu.editor.fail.body")));
            start(task, "dehu-create");
        });
    }

    private void markDehuRead(com.benjagest.ui.model.DehuNotificationEntry n) {
        Task<com.benjagest.ui.model.DehuNotificationEntry> task = new Task<>() {
            @Override protected com.benjagest.ui.model.DehuNotificationEntry call() throws Exception {
                return laborApiClient.markDehuRead(n.id());
            }
        };
        task.setOnSucceeded(ev -> show());
        task.setOnFailed(ev -> showError(t("dehu.editor.fail.title"), t("dehu.editor.fail.body")));
        start(task, "dehu-read");
    }

    private void dismissDehu(com.benjagest.ui.model.DehuNotificationEntry n) {
        Task<com.benjagest.ui.model.DehuNotificationEntry> task = new Task<>() {
            @Override protected com.benjagest.ui.model.DehuNotificationEntry call() throws Exception {
                return laborApiClient.dismissDehu(n.id());
            }
        };
        task.setOnSucceeded(ev -> show());
        task.setOnFailed(ev -> showError(t("dehu.editor.fail.title"), t("dehu.editor.fail.body")));
        start(task, "dehu-dismiss");
    }

    // ===================================================================
}
