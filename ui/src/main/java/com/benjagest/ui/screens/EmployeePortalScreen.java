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

/** Portal del empleado (read-only): Calendario / Nominas / Notificaciones /
 *  Trabajos. Extraido del God Object en UIR-7. */
public class EmployeePortalScreen extends ScreenBase {

    private final AltaApiClient altaApiClient;

    public EmployeePortalScreen(AltaApiClient altaApiClient, Function<String, String> tt, Router router) {
        super(tt, router);
        this.altaApiClient = altaApiClient;
    }

    public Node buildView() {

        // Cabecera + TabPane vacio. Cada tab se rellena cuando se
        // selecciona por primera vez (lazy load).
        VBox root = new VBox(16);
        root.getStyleClass().add("content");

        Label header = label(t("portal.title"), "settings-section-title");
        Label hint = new Label(t("portal.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("settings-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab tCal = new Tab(t("portal.tab.calendar"));
        Tab tPay = new Tab(t("portal.tab.payslips"));
        Tab tNot = new Tab(t("portal.tab.notifications"));
        Tab tJob = new Tab(t("portal.tab.jobs"));

        tCal.setContent(buildPortalCalendarTab());
        tPay.setContent(buildPortalPayslipsTab());
        tNot.setContent(buildPortalNotificationsTab());
        tJob.setContent(buildPortalJobsTab());

        tabs.getTabs().addAll(tCal, tPay, tNot, tJob);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        root.getChildren().addAll(header, hint, tabs);
        return scroll(root);
    }

    /** Tab 1 — Mi calendario laboral. */
    private Node buildPortalCalendarTab() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));

        Label hint = new Label(t("portal.calendar.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        LocalDate now = LocalDate.now();
        DatePicker fromPick = new DatePicker(now.withDayOfMonth(1));
        DatePicker toPick = new DatePicker(now.withDayOfMonth(1).plusMonths(2).minusDays(1));
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(fromPick);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(toPick);
        Button reloadBtn = new Button(t("portal.calendar.reload"));
        reloadBtn.setGraphic(icon("fas-sync-alt"));
        reloadBtn.getStyleClass().add("button-primary");
        HBox filters = new HBox(8,
                new Label(t("portal.calendar.from")), fromPick,
                new Label(t("portal.calendar.to")), toPick,
                reloadBtn);
        filters.setAlignment(Pos.CENTER_LEFT);

        TableView<com.benjagest.ui.model.PortalEvent> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("portal.calendar.empty")));

        TableColumn<com.benjagest.ui.model.PortalEvent, String> cDate =
                new TableColumn<>(t("portal.calendar.col.date"));
        cDate.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().date()));
        cDate.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.PortalEvent, String> cTitle =
                new TableColumn<>(t("portal.calendar.col.title"));
        cTitle.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().title()));
        TableColumn<com.benjagest.ui.model.PortalEvent, String> cType =
                new TableColumn<>(t("portal.calendar.col.type"));
        cType.setCellValueFactory(c -> new SimpleStringProperty(
                humanizeCalendarEventType(c.getValue().eventType())));
        cType.setPrefWidth(150);
        TableColumn<com.benjagest.ui.model.PortalEvent, String> cKind =
                new TableColumn<>(t("portal.calendar.col.source"));
        cKind.setCellValueFactory(c -> new SimpleStringProperty(
                "LEAVE".equals(c.getValue().kind())
                        ? t("portal.calendar.kind.leave")
                        : t("portal.calendar.kind.calendar")));
        cKind.setPrefWidth(120);
        TableColumn<com.benjagest.ui.model.PortalEvent, String> cDetail =
                new TableColumn<>(t("portal.calendar.col.detail"));
        cDetail.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().detail()));

        table.getColumns().addAll(java.util.List.of(cDate, cTitle, cType, cKind, cDetail));

        Runnable reload = () -> {
            LocalDate from = fromPick.getValue();
            LocalDate to = toPick.getValue();
            if (from == null || to == null) return;
            Task<List<com.benjagest.ui.model.PortalEvent>> task = new Task<>() {
                @Override protected List<com.benjagest.ui.model.PortalEvent> call() throws Exception {
                    return altaApiClient.listPortalCalendar(from, to);
                }
            };
            task.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(task.getValue())));
            task.setOnFailed(ev -> showError(t("portal.calendar.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "portal-calendar-load");
        };
        reloadBtn.setOnAction(ev -> reload.run());
        reload.run();

        VBox.setVgrow(table, Priority.ALWAYS);
        box.getChildren().addAll(hint, filters, table);
        return box;
    }

    /** Tab 2 — Mis nominas. */
    private Node buildPortalPayslipsTab() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));

        Label hint = new Label(t("portal.payslips.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        TableView<com.benjagest.ui.model.PortalPayslip> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("portal.payslips.empty")));

        TableColumn<com.benjagest.ui.model.PortalPayslip, String> cPeriod =
                new TableColumn<>(t("portal.payslips.col.period"));
        cPeriod.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().year() + "-" + String.format("%02d", c.getValue().month())));
        cPeriod.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.PortalPayslip, String> cGross =
                new TableColumn<>(t("portal.payslips.col.gross"));
        cGross.setCellValueFactory(c -> new SimpleStringProperty(money(
                c.getValue().grossAmount() == null ? "0"
                        : c.getValue().grossAmount().toPlainString())));
        cGross.setPrefWidth(120);
        cGross.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.PortalPayslip, String> cNet =
                new TableColumn<>(t("portal.payslips.col.net"));
        cNet.setCellValueFactory(c -> new SimpleStringProperty(money(
                c.getValue().netAmount() == null ? "0"
                        : c.getValue().netAmount().toPlainString())));
        cNet.setPrefWidth(120);
        cNet.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.PortalPayslip, String> cStatus =
                new TableColumn<>(t("portal.payslips.col.status"));
        cStatus.setCellValueFactory(c -> new SimpleStringProperty(localizedEnum("payslip_status", c.getValue().status())));
        cStatus.setPrefWidth(120);

        table.getColumns().addAll(java.util.List.of(cPeriod, cGross, cNet, cStatus));

        Task<List<com.benjagest.ui.model.PortalPayslip>> task = new Task<>() {
            @Override protected List<com.benjagest.ui.model.PortalPayslip> call() throws Exception {
                return altaApiClient.listPortalPayslips();
            }
        };
        task.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(task.getValue())));
        task.setOnFailed(ev -> showError(t("portal.payslips.fail.title"),
                task.getException() == null ? "" : task.getException().getMessage()));
        start(task, "portal-payslips-load");

        VBox.setVgrow(table, Priority.ALWAYS);
        box.getChildren().addAll(hint, table);
        return box;
    }

    /** Tab 3 — Mis notificaciones. */
    private Node buildPortalNotificationsTab() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));

        Label hint = new Label(t("portal.notifications.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        TableView<com.benjagest.ui.model.PortalNotification> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("portal.notifications.empty")));

        TableColumn<com.benjagest.ui.model.PortalNotification, String> cWhen =
                new TableColumn<>(t("portal.notifications.col.when"));
        cWhen.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().createdAt() == null || c.getValue().createdAt().length() < 16
                        ? c.getValue().createdAt()
                        : c.getValue().createdAt().substring(0, 16)));
        cWhen.setPrefWidth(140);
        TableColumn<com.benjagest.ui.model.PortalNotification, String> cSev =
                new TableColumn<>(t("portal.notifications.col.severity"));
        cSev.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().severity()));
        cSev.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.PortalNotification, String> cTitle =
                new TableColumn<>(t("portal.notifications.col.title"));
        cTitle.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().title()));
        cTitle.setPrefWidth(220);
        TableColumn<com.benjagest.ui.model.PortalNotification, String> cBody =
                new TableColumn<>(t("portal.notifications.col.body"));
        cBody.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().body()));

        table.getColumns().addAll(java.util.List.of(cWhen, cSev, cTitle, cBody));

        Task<List<com.benjagest.ui.model.PortalNotification>> task = new Task<>() {
            @Override protected List<com.benjagest.ui.model.PortalNotification> call() throws Exception {
                return altaApiClient.listPortalNotifications();
            }
        };
        task.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(task.getValue())));
        task.setOnFailed(ev -> showError(t("portal.notifications.fail.title"),
                task.getException() == null ? "" : task.getException().getMessage()));
        start(task, "portal-notifications-load");

        VBox.setVgrow(table, Priority.ALWAYS);
        box.getChildren().addAll(hint, table);
        return box;
    }

    /** Tab 4 — Mis trabajos. Placeholder hasta que se decida PORT-2. */
    private Node buildPortalJobsTab() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));

        Label hint = new Label(t("portal.jobs.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        TableView<com.benjagest.ui.model.PortalJob> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("portal.jobs.empty")));

        TableColumn<com.benjagest.ui.model.PortalJob, String> cDate =
                new TableColumn<>(t("portal.jobs.col.date"));
        cDate.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().date()));
        cDate.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.PortalJob, String> cTitle =
                new TableColumn<>(t("portal.jobs.col.title"));
        cTitle.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().title()));
        TableColumn<com.benjagest.ui.model.PortalJob, String> cStatus =
                new TableColumn<>(t("portal.jobs.col.status"));
        cStatus.setCellValueFactory(c -> new SimpleStringProperty(localizedEnum("worklog_status", c.getValue().status())));
        cStatus.setPrefWidth(120);

        table.getColumns().addAll(java.util.List.of(cDate, cTitle, cStatus));

        Task<List<com.benjagest.ui.model.PortalJob>> task = new Task<>() {
            @Override protected List<com.benjagest.ui.model.PortalJob> call() throws Exception {
                return altaApiClient.listPortalJobs();
            }
        };
        task.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(task.getValue())));
        task.setOnFailed(ev -> { /* Si endpoint no existe aun, ignorar silenciosamente */ });
        start(task, "portal-jobs-load");

        VBox.setVgrow(table, Priority.ALWAYS);
        box.getChildren().addAll(hint, table);
        return box;
    }

    // ============================================================
    //  PORT-4 CLI — Editor extendido de cliente
    //  ----------------------------------------------------------
    //  Modal con TabPane (Datos / Direccion postal / Facturacion).
    //  Se abre al hacer doble-click o pulsar "Editar" sobre un cliente
    //  del modulo customers. CONTENDO equivalente: el formulario rico
    //  de app/admin/clientes/[id]/page.tsx.
    // ============================================================
}
