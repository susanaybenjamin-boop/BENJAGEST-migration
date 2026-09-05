package com.benjagest.ui.screens;

import com.benjagest.ui.model.*;
import com.benjagest.ui.service.BillingApiClient;
import com.benjagest.ui.support.Router;
import java.time.LocalDate;
import java.util.function.Function;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * AS-6 — Pestaña "Facturación" de la ficha de cliente VINCULADO: listado de
 * facturas emitidas (sales_invoices) con filtros, validar borrador, doble-clic a
 * editor, nueva factura, importar PDFs, hacer recurrente. Extraída del God Object.
 *
 * <p>Las acciones compartidas (editor, validar, importar, recurrente, gate AGR-2)
 * viven en el shell y se inyectan vía {@link Host}; el shell conserva
 * {@code buildClientBillingTab()} como wrapper (2 call sites). Movido tal cual.
 */
public class ClientBillingScreen extends ScreenBase {

    /** Acciones compartidas que siguen en el shell. */
    public interface Host {
        void showInvoiceEditor(String invoiceId);
        void validateInvoiceFromList(SalesInvoiceSummary sel);
        void importSalesPdfsMulti();
        void openRecurringEditorFromInvoice(String kind, String partyNif, String partyName,
                                            java.math.BigDecimal total, LocalDate invoiceDate);
        /**
         * COB-5 — cuadro de vencimientos (cobro) de la factura de venta. Mismo
         * dialogo que Facturacion y que Ventas archivadas; lo guarda el shell
         * porque lo comparten las tres pantallas.
         */
        void openDueDatesDialog(String kind, String invoiceId, String partyName,
                                java.math.BigDecimal total);
        void applyBillingGate(boolean sales, javafx.scene.layout.VBox bannerHolder,
                              javafx.scene.control.ButtonBase... toDisable);
    }

    private final BillingApiClient billingApiClient;
    private final Host host;

    public ClientBillingScreen(BillingApiClient billingApiClient,
                               Function<String, String> tt, Router router, Host host) {
        super(tt, router);
        this.billingApiClient = billingApiClient;
        this.host = host;
    }

    public Node buildTab() {
        javafx.scene.control.TableView<com.benjagest.ui.model.SalesInvoiceSummary> table =
                new javafx.scene.control.TableView<>();
        addCol(table, t("billing.col.number"), v -> v.invoiceNumber() == null ? "" : v.invoiceNumber(), 130);
        addColSorted(table, t("billing.col.date"), v -> v.invoiceDate() == null ? "" : v.invoiceDate(), 100, ISO_DATE_COMPARATOR);
        addCol(table, t("billing.col.customer"), v -> v.customerLegalName() == null ? "" : v.customerLegalName(), 240);
        addCol(table, t("billing.col.type"), v -> localizedEnum("invoice_type", v.invoiceType()), 90);
        addColSorted(table, t("billing.col.total"), v -> v.total() == null ? "" : v.total().toString(), 110, NUMERIC_STRING_COMPARATOR);
        addColSorted(table, t("billing.col.paid"), v -> v.paidAmount() == null ? "" : v.paidAmount().toString(), 100, NUMERIC_STRING_COMPARATOR);
        addCol(table, t("billing.col.status"),
                v -> v.status() == null ? "" : t("accounting.status." + v.status()), 110);
        addCol(table, t("billing.col.payment_status"),
                v -> v.collectionNotApplicable() ? "—"
                        : (v.paymentStatus() == null ? "" : t("billing.payment_status." + v.paymentStatus())), 100);

        // Cache cliente-side para filtrar sin ir al backend.
        final java.util.List<com.benjagest.ui.model.SalesInvoiceSummary> cache =
                new java.util.ArrayList<>();

        // Filtros internos (Slice 2B aplicado al cliente VINCULADO):
        //   - Buscar: nº, cliente, concepto.
        //   - Estado: TODOS | DRAFT | POSTED | VOIDED.
        //   - Tipo: TODOS | NORMAL | RECTIFICATIVA (invoiceType=RECT
        //     o total < 0).
        TextField search = new TextField();
        search.setPromptText(t("client.filter.search_prompt"));
        search.setPrefColumnCount(20);

        // Estados de FACTURA (sales_invoices) — distintos a asientos.
        // Confirma V2: CHECK status IN ('DRAFT', 'VALIDATED',
        // 'CANCELLED', 'VOIDED'). Antes mi combo tenía POSTED por
        // copy-paste del listado de asientos, y filtrar por POSTED
        // dejaba la tabla vacía porque las facturas validadas tienen
        // estado VALIDATED.
        ComboBox<String> statusFilter = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                "", "DRAFT", "PENDING_CLIENT_APPROVAL", "VALIDATED", "CANCELLED", "VOIDED"));
        statusFilter.setValue("");
        statusFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) {
                return s == null || s.isBlank()
                        ? t("client.filter.status.all")
                        : t("accounting.status." + s);
            }
            @Override public String fromString(String s) { return s; }
        });

        ComboBox<String> typeFilter = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                "", "NORMAL", "RECT"));
        typeFilter.setValue("");
        typeFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) {
                return s == null || s.isBlank() ? t("client.filter.type.all")
                        : "RECT".equals(s) ? t("client.filter.type.rectifying")
                        : t("client.filter.type.normal");
            }
            @Override public String fromString(String s) { return s; }
        });

        Runnable applyFilters = () -> {
            String q = search.getText() == null ? ""
                    : search.getText().trim().toLowerCase();
            String st = statusFilter.getValue();
            String tp = typeFilter.getValue();
            javafx.collections.ObservableList<com.benjagest.ui.model.SalesInvoiceSummary> filtered =
                    javafx.collections.FXCollections.observableArrayList();
            for (var inv : cache) {
                if (!q.isEmpty()) {
                    String hay = (inv.invoiceNumber() == null ? "" : inv.invoiceNumber().toLowerCase())
                            + " " + (inv.customerLegalName() == null ? "" : inv.customerLegalName().toLowerCase());
                    if (!hay.contains(q)) continue;
                }
                if (st != null && !st.isBlank()
                        && !st.equalsIgnoreCase(inv.status())) continue;
                if (tp != null && !tp.isBlank()) {
                    boolean isRect = isSalesInvoiceRectifying(inv);
                    if ("RECT".equals(tp) && !isRect) continue;
                    if ("NORMAL".equals(tp) && isRect) continue;
                }
                filtered.add(inv);
            }
            table.setItems(filtered);
        };
        search.textProperty().addListener((o, a, b) -> applyFilters.run());
        statusFilter.valueProperty().addListener((o, a, b) -> applyFilters.run());
        typeFilter.valueProperty().addListener((o, a, b) -> applyFilters.run());

        Button refresh = new Button(t("accounting.action.refresh"));
        refresh.setOnAction(e -> loadClientBilling(table, cache, applyFilters));

        // TPB — Boton "Nueva factura" en el tab Facturacion del cliente
        // visto desde la asesoria. El backend valida via TPB ACTIVE
        // cubriendo ventas en el momento de validar la factura, asi que
        // el boton se habilita siempre; si falta cobertura legal, el
        // POST /validate devuelve 409 con mensaje explicito.
        Button newInvoiceBtn = new Button(t("client.billing.action.new_invoice"));
        newInvoiceBtn.setGraphic(icon("fas-plus"));
        newInvoiceBtn.getStyleClass().add("button-primary");
        newInvoiceBtn.setOnAction(e -> host.showInvoiceEditor(null));

        // Fix Benjamin 2026-06-13: el listado del cliente no permitia
        // ni validar un borrador ni abrirlo para corregir. Anadimos:
        //  - Doble clic en DRAFT / PENDING_CLIENT_APPROVAL -> editor.
        //  - Boton "Validar" para el DRAFT seleccionado.
        Button validateBtn = new Button(t("editor.action.validate"));
        validateBtn.setGraphic(icon("fas-check"));
        validateBtn.setDisable(true);
        validateBtn.setOnAction(e -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) host.validateInvoiceFromList(sel);
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) ->
                validateBtn.setDisable(nv == null || !"DRAFT".equals(nv.status())));
        table.setRowFactory(tv -> {
            javafx.scene.control.TableRow<com.benjagest.ui.model.SalesInvoiceSummary> row =
                    new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    var inv = row.getItem();
                    boolean editable = "DRAFT".equals(inv.status())
                            || "PENDING_CLIENT_APPROVAL".equals(inv.status())
                            || "PROFORMA".equals(inv.invoiceType());
                    if (editable) {
                        host.showInvoiceEditor(inv.id());
                    } else {
                        Alert info = new Alert(Alert.AlertType.INFORMATION,
                                t("list.dialog.validated_no_edit"), ButtonType.OK);
                        info.setHeaderText(t("list.dialog.validated_no_edit.header"));
                        info.showAndWait();
                    }
                }
            });
            return row;
        });

        Button importSalesPdfsBtn = new Button(t("sales.action.import_pdfs"));
        importSalesPdfsBtn.setGraphic(icon("fas-file-import"));
        importSalesPdfsBtn.getStyleClass().add("button-primary");
        importSalesPdfsBtn.setOnAction(e -> host.importSalesPdfsMulti());

        // Slice 3T — Botón "Hacer recurrente" para facturas validadas
        // del cliente. En cliente VINCULADO abre el editor SALES_INVOICE
        // (con serie + VeriFactu del cliente). En cliente NO VINCULADO
        // (que reusa este mismo tab en otro sitio) redirige al editor
        // contable vía openRecurringEditorFromInvoice (Slice 3T).
        Button makeRecurringBtnBilling = new Button(t("list.action.make_recurring"));
        makeRecurringBtnBilling.setGraphic(icon("fas-arrows-rotate"));
        makeRecurringBtnBilling.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            boolean onePosted = nv != null
                    && ("POSTED".equalsIgnoreCase(nv.status())
                        || "VALIDATED".equalsIgnoreCase(nv.status()));
            makeRecurringBtnBilling.setDisable(!onePosted);
        });
        makeRecurringBtnBilling.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            LocalDate invDate = null;
            try {
                if (sel.invoiceDate() != null && !sel.invoiceDate().isBlank()) {
                    invDate = LocalDate.parse(sel.invoiceDate());
                }
            } catch (Exception ignored) {}
            host.openRecurringEditorFromInvoice(
                    "SALES_INVOICE",
                    sel.customerTaxId(), // NIF del cliente (JOIN customers.tax_identifier)
                    sel.customerLegalName(),
                    sel.total(),
                    invDate);
        });

        // COB-5 — Cobrar la factura del cliente VINCULADO. COB-2 solo llego al
        // listado del NO vinculado (Ventas archivadas); aqui, que es donde acaba
        // la factura de un cliente vinculado, seguia sin poder cobrarse sin irse
        // a Facturacion. Aviso de Benjamin: "lo que no esta apareciendo es el
        // cobro de la factura en los clientes vinculados".
        //
        // Aqui las filas YA son facturas (no asientos), asi que basta con su id:
        // no hace falta el rodeo por source_id que si necesitaba COB-2.
        Button dueDatesBtn = new Button(t("duedates.action.open_sales"));
        dueDatesBtn.setGraphic(icon("fas-calendar-check"));
        dueDatesBtn.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            // Un borrador no tiene nada que cobrar. Se acepta POSTED ademas de
            // VALIDATED por el mismo criterio defensivo que "Hacer recurrente".
            boolean cobrable = nv != null
                    && ("VALIDATED".equalsIgnoreCase(nv.status())
                        || "POSTED".equalsIgnoreCase(nv.status()));
            dueDatesBtn.setDisable(!cobrable);
        });
        dueDatesBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            host.openDueDatesDialog("SALES", sel.id(),
                    sel.customerLegalName(), sel.total());
        });

        // Fila muy cargada (3 filtros etiqueta+control + 5 botones): en un HBox
        // los botones se encogían y cortaban el texto. actionFlow envuelve; cada
        // filtro va en su propio grupo para que la etiqueta no se separe del control.
        HBox searchGroup = new HBox(6, new Label(t("client.filter.search")), search);
        searchGroup.setAlignment(Pos.CENTER_LEFT);
        HBox statusGroup = new HBox(6, new Label(t("client.filter.status")), statusFilter);
        statusGroup.setAlignment(Pos.CENTER_LEFT);
        HBox typeGroup = new HBox(6, new Label(t("client.filter.type")), typeFilter);
        typeGroup.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.layout.FlowPane actions = actionFlow(
                searchGroup, statusGroup, typeGroup,
                validateBtn, dueDatesBtn, makeRecurringBtnBilling, refresh,
                importSalesPdfsBtn, newInvoiceBtn);

        com.benjagest.ui.support.RefreshBus.subscribe(
                com.benjagest.ui.support.RefreshBus.TOPIC_SALES,
                () -> loadClientBilling(table, cache, applyFilters), table);
        com.benjagest.ui.support.RefreshBus.subscribe(
                com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL,
                () -> loadClientBilling(table, cache, applyFilters), table);

        // AGR-2: banner + deshabilitar "Nueva factura" si no hay acuerdo (ventas).
        VBox gateBanner = new VBox();
        gateBanner.setVisible(false);
        gateBanner.setManaged(false);
        host.applyBillingGate(true, gateBanner, newInvoiceBtn);

        VBox box = new VBox(8, gateBanner, actions, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPadding(new Insets(12));
        loadClientBilling(table, cache, applyFilters);
        return box;
    }

    /**
     * Decide si una factura emitida es rectificativa:
     *   - invoiceType contiene "RECT"
     *   - total negativo
     */
    private boolean isSalesInvoiceRectifying(com.benjagest.ui.model.SalesInvoiceSummary inv) {
        if (inv == null) return false;
        String type = inv.invoiceType() == null ? "" : inv.invoiceType().toUpperCase();
        if (type.contains("RECT")) return true;
        if (inv.total() != null && inv.total().signum() < 0) return true;
        return false;
    }

    private void loadClientBilling(
            javafx.scene.control.TableView<com.benjagest.ui.model.SalesInvoiceSummary> table,
            java.util.List<com.benjagest.ui.model.SalesInvoiceSummary> cache,
            Runnable applyFilters) {
        Task<java.util.List<com.benjagest.ui.model.SalesInvoiceSummary>> task = new Task<>() {
            @Override protected java.util.List<com.benjagest.ui.model.SalesInvoiceSummary> call() throws Exception {
                return billingApiClient.listInvoices(null, null, null, 500);
            }
        };
        task.setOnSucceeded(ev -> {
            cache.clear();
            cache.addAll(task.getValue());
            applyFilters.run();
        });
        task.setOnFailed(ev -> System.err.println("[client-billing] "
                + (task.getException() == null ? "?" : task.getException().getMessage())));
        start(task, "client-billing");
    }

    // ----- helpers locales (copias stateless del shell) -----

    private <T> void addCol(javafx.scene.control.TableView<T> table, String header,
                              java.util.function.Function<T, String> getter, double width) {
        javafx.scene.control.TableColumn<T, String> c = new javafx.scene.control.TableColumn<>(header);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(getter.apply(cd.getValue())));
        table.getColumns().add(c);
    }

    private <T> void addColSorted(javafx.scene.control.TableView<T> table, String header,
                                    java.util.function.Function<T, String> getter, double width,
                                    java.util.Comparator<String> comparator) {
        javafx.scene.control.TableColumn<T, String> c = new javafx.scene.control.TableColumn<>(header);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(getter.apply(cd.getValue())));
        c.setComparator(comparator);
        table.getColumns().add(c);
    }
}
