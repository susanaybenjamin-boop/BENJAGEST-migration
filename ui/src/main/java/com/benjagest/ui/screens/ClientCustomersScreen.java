package com.benjagest.ui.screens;

import com.benjagest.ui.model.CustomerSummary;
import com.benjagest.ui.service.CustomerApiClient;
import com.benjagest.ui.support.Router;
import java.util.List;
import java.util.function.Function;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * AS-1 — Pestaña "Clientes" (receptores de factura) del titular, dentro de la
 * ficha de asesoría. Listado + alta/edición. Extraída del God Object con el
 * patrón Host: los diálogos de cliente (alta/detalle) viven en el shell porque
 * se comparten con otras pantallas, y se inyectan vía {@link Host}.
 *
 * <p>El shell conserva {@code buildClientCustomersTab()} como wrapper (3 call
 * sites: composite de la ficha, activación TPB, y la sub-pestaña reutilizada en
 * config de facturación). Movido tal cual; reusa data-table/settings-* del CSS.
 */
public class ClientCustomersScreen extends ScreenBase {

    /** Diálogos de cliente que viven en el shell (compartidos con otras pantallas). */
    public interface Host {
        /** Abre el alta de cliente; al guardar ejecuta {@code onSaved}. */
        void openNewCustomerDialog(Runnable onSaved);
        /** Abre el detalle/edición de un cliente; al guardar ejecuta {@code onSaved}. */
        void showCustomerDetailDialog(String customerId, Runnable onSaved);
    }

    private final CustomerApiClient customerApiClient;
    private final Host host;

    public ClientCustomersScreen(CustomerApiClient customerApiClient,
                                 Function<String, String> tt, Router router, Host host) {
        super(tt, router);
        this.customerApiClient = customerApiClient;
        this.host = host;
    }

    public Node buildTab() {
        javafx.scene.control.TableView<CustomerSummary> table =
                new javafx.scene.control.TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("client.customers.empty")));
        addCol(table, t("cli.field.legalName"), v -> v.legalName() == null ? "" : v.legalName(), 240);
        addCol(table, t("cli.field.taxId"), v -> v.taxIdentifier() == null ? "" : v.taxIdentifier(), 120);
        addCol(table, t("cli.col.vat"), v -> pctOrDash(v.defaultVatPercent()), 80);
        addCol(table, t("cli.col.retention"), v -> pctOrDash(v.defaultRetentionPercent()), 90);
        addCol(table, t("cli.field.city"), v -> v.city() == null ? "" : v.city(), 140);
        addCol(table, t("cli.field.email"), v -> v.email() == null ? "" : v.email(), 220);
        addCol(table, t("cli.field.phone"), v -> v.phone() == null ? "" : v.phone(), 120);
        VBox.setVgrow(table, Priority.ALWAYS);

        Runnable reload = () -> {
            Task<List<CustomerSummary>> task = new Task<>() {
                @Override
                protected List<CustomerSummary> call() throws Exception {
                    return customerApiClient.list();
                }
            };
            task.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(task.getValue())));
            task.setOnFailed(ev -> showError(t("client.customers.load.fail"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "client-customers-load");
        };

        Button newBtn = new Button(t("client.customers.new"));
        newBtn.setGraphic(icon("fas-user-plus"));
        newBtn.getStyleClass().add("primary-button");
        newBtn.setOnAction(e -> host.openNewCustomerDialog(reload));

        Button editBtn = new Button(t("client.customers.edit"));
        editBtn.setGraphic(icon("fas-pen"));
        editBtn.setDisable(true);
        editBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) host.showCustomerDetailDialog(sel.id(), reload);
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) ->
                editBtn.setDisable(nv == null));
        table.setRowFactory(tv -> {
            javafx.scene.control.TableRow<CustomerSummary> row =
                    new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    host.showCustomerDetailDialog(row.getItem().id(), reload);
                }
            });
            return row;
        });

        Button refresh = new Button(t("accounting.action.refresh"));
        refresh.setGraphic(icon("fas-sync-alt"));
        refresh.setOnAction(e -> reload.run());

        Label hint = new Label(t("client.customers.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        HBox actions = new HBox(8, newBtn, editBtn, refresh);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox box = new VBox(10, hint, actions, table);
        box.setPadding(new Insets(12));
        reload.run();
        return box;
    }

    // ----- helpers locales (copias stateless del shell) -----

    private <T> void addCol(javafx.scene.control.TableView<T> table, String header,
                              java.util.function.Function<T, String> getter, double width) {
        javafx.scene.control.TableColumn<T, String> c = new javafx.scene.control.TableColumn<>(header);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(getter.apply(cd.getValue())));
        table.getColumns().add(c);
    }

    private static String pctOrDash(java.math.BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString() + "%";
    }
}
