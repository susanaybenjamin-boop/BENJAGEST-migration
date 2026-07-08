package com.benjagest.ui.screens;

import com.benjagest.ui.model.*;
import com.benjagest.ui.service.*;
import com.benjagest.ui.support.Router;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * FAC-4b — Pestaña de Facturas (listado + filtros + acciones de fila), extraída
 * del God Object. El listado y sus acciones exclusivas (validar/anular/borrar
 * borrador/email/convertir proforma/PDF) viven aquí; las acciones COMPARTIDAS
 * con Compras / la ficha de cliente, o los diálogos grandes (multi-cobro,
 * conciliación bancaria, vencimientos, editor recurrente, editor de factura, el
 * gate AGR-2, el banner de candidatos a recurrencia, importar PDFs) se inyectan
 * vía {@link Host} y siguen viviendo en el shell.
 *
 * <p>El shell conserva {@code billingInvoicesTab(list)} como wrapper. Movido tal
 * cual (mover, no reescribir); reusa data-table/form-input/settings-* del CSS.
 */
public class BillingInvoicesScreen extends ScreenBase {

    /** Callbacks que viven en el shell (acciones compartidas o diálogos grandes). */
    public interface Host {
        /** Abre el editor de factura ({@code null} = nueva). */
        void showInvoiceEditor(String invoiceId);
        /** Validar un borrador desde el listado (AGR-2 + asiento + prompt recurrente). Compartido con la ficha de cliente. */
        void validateInvoiceFromList(SalesInvoiceSummary sel);
        /** Gate AGR-2: ¿hay acuerdo de facturación para operar (ventas/compras)? */
        boolean ensureBillingAllowed(boolean sales);
        /** Diálogo de pago multi-factura (cobro repartido). */
        void showMultiAllocationDialog();
        /** Diálogo de conciliación bancaria (match movimientos ↔ facturas). */
        void showBankReconciliationDialog();
        /** Diálogo de vencimientos (cobro por plazos). Compartido con Compras. */
        void openDueDatesDialog(String kind, String invoiceId, String partyName, java.math.BigDecimal total);
        /** Editor de plantilla recurrente desde una factura. Compartido. */
        void openRecurringEditorFromInvoice(String kind, String partyNif, String partyName,
                                            java.math.BigDecimal total, LocalDate invoiceDate);
        /** Banner de candidatos a recurrencia (facturas repetidas). Compartido con Compras. */
        Node buildRecurringCandidatesBanner(String kind);
        /** Importar uno o varios PDFs de venta (asesoría actuando-como cliente). Compartido. */
        void importSalesPdfsMulti();
        /** ¿Mostrar el botón "Importar PDFs"? (solo asesoría actuando-como cliente). */
        boolean showImportSalesButton();
        /** OPTYPE-3 — clasificar el tipo de operación de una factura (intracom → modelo 349). */
        void showSalesClassificationDialog(String invoiceId);
    }

    private final BillingApiClient billingApiClient;
    private final Host host;

    private ComboBox<String> billingStatusFilter;
    private ComboBox<String> billingPaymentFilter;
    private ComboBox<String> billingTypeFilter;
    private TableView<SalesInvoiceSummary> billingTable;

    public BillingInvoicesScreen(BillingApiClient billingApiClient,
                                 Function<String, String> tt, Router router, Host host) {
        super(tt, router);
        this.billingApiClient = billingApiClient;
        this.host = host;
    }

    public Node buildTab(List<SalesInvoiceSummary> initialList) {
        billingStatusFilter = new ComboBox<>();
        // Los items siguen siendo los códigos técnicos (DRAFT/VALIDATED/...)
        // para que mapAllOrValue() los mande al backend tal cual. La
        // visualización pasa por localizedInvoiceStatus en cellFactory+
        // buttonCell, así el filtro se ve traducido pero internamente
        // sigue hablando el idioma del API.
        billingStatusFilter.getItems().addAll(t("list.filter.all"), "DRAFT", "PENDING_CLIENT_APPROVAL", "VALIDATED", "CANCELLED", "VOIDED");
        billingStatusFilter.getSelectionModel().selectFirst();
        billingStatusFilter.getStyleClass().add("form-input");
        billingStatusFilter.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : localizedInvoiceStatus(item));
            }
        });
        billingStatusFilter.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : localizedInvoiceStatus(item));
            }
        });

        billingPaymentFilter = new ComboBox<>();
        billingPaymentFilter.getItems().addAll(t("list.filter.all"), "PENDING", "PARTIAL", "PAID", "OVERDUE");
        billingPaymentFilter.getSelectionModel().selectFirst();
        billingPaymentFilter.getStyleClass().add("form-input");
        billingPaymentFilter.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : localizedPaymentStatus(item));
            }
        });
        billingPaymentFilter.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : localizedPaymentStatus(item));
            }
        });

        // Filtro por tipo de factura: NORMAL / PROFORMA / RECTIFYING / HISTORICAL.
        // Misma técnica que los demás filtros: items=códigos técnicos,
        // visualización vía localizedInvoiceTypeLabel en cell/buttonCell.
        billingTypeFilter = new ComboBox<>();
        billingTypeFilter.getItems().addAll(
                t("list.filter.all"), "NORMAL", "SIMPLIFIED", "PROFORMA", "RECTIFYING", "HISTORICAL");
        billingTypeFilter.getSelectionModel().selectFirst();
        billingTypeFilter.getStyleClass().add("form-input");
        billingTypeFilter.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : localizedInvoiceTypeLabel(item));
            }
        });
        billingTypeFilter.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : localizedInvoiceTypeLabel(item));
            }
        });

        Button apply = new Button(t("list.filter.apply"));
        apply.setGraphic(icon("fas-filter"));
        apply.setOnAction(event -> reloadInvoices());

        Button reset = new Button(t("list.filter.reset"));
        reset.setGraphic(icon("fas-sync-alt"));
        reset.setOnAction(event -> {
            billingStatusFilter.getSelectionModel().selectFirst();
            billingPaymentFilter.getSelectionModel().selectFirst();
            billingTypeFilter.getSelectionModel().selectFirst();
            reloadInvoices();
        });

        // Botón "Importar PDFs" en la fila de filtros (derecha) — un solo
        // botón que acepta uno o varios PDFs y crea asientos directos.
        // Para clientes no vinculados es el flujo natural para cerrar
        // trimestre con los PDFs que el cliente entrega.
        //
        // Tarde 2026-06-10 (feedback Benjamin): el empresario NO importa
        // SUS propias facturas — las crea con "Nueva factura". Solo tiene
        // sentido cuando es la asesoría actuando como cliente (vinculado
        // o no). Visible solo si appMode==ADVISORY && actingForClient.
        Button importSalesPdfsBtn = new Button(t("sales.action.import_pdfs"));
        importSalesPdfsBtn.setGraphic(icon("fas-file-import"));
        importSalesPdfsBtn.getStyleClass().add("button-primary");
        importSalesPdfsBtn.setOnAction(ev -> host.importSalesPdfsMulti());
        boolean showImportSales = host.showImportSalesButton();
        importSalesPdfsBtn.setVisible(showImportSales);
        importSalesPdfsBtn.setManaged(showImportSales);

        // DOBLE PANTALLA — fila de filtros que ENVUELVE (FlowPane): cada etiqueta va
        // pegada a su control en un grupo, y los grupos pasan de línea si no caben en
        // el ancho de la ventana (portátil), en vez de cortarse a la derecha sin scroll.
        javafx.scene.layout.FlowPane filters = actionFlow(
                filterGroup(t("list.filter.label.status"), billingStatusFilter),
                filterGroup(t("list.filter.label.collection"), billingPaymentFilter),
                filterGroup(t("list.filter.label.type"), billingTypeFilter),
                apply, reset, importSalesPdfsBtn);

        billingTable = new TableView<>();
        billingTable.getStyleClass().add("data-table");
        billingTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        billingTable.setPlaceholder(new Label(t("list.placeholder.empty")));

        TableColumn<SalesInvoiceSummary, String> colNumber = new TableColumn<>(t("list.column.number"));
        colNumber.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().invoiceNumber() == null || c.getValue().invoiceNumber().isBlank()
                        ? t("list.draft_label")
                        : c.getValue().invoiceNumber()
        ));
        colNumber.setPrefWidth(160);

        TableColumn<SalesInvoiceSummary, String> colCustomer = new TableColumn<>(t("list.column.customer"));
        colCustomer.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().customerLegalName()));
        colCustomer.setPrefWidth(220);

        TableColumn<SalesInvoiceSummary, String> colDate = new TableColumn<>(t("list.column.date"));
        colDate.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().invoiceDate()));
        colDate.setPrefWidth(110);
        colDate.setComparator(ISO_DATE_COMPARATOR);

        TableColumn<SalesInvoiceSummary, String> colDue = new TableColumn<>(t("list.column.due_date"));
        colDue.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().dueDate()));
        colDue.setPrefWidth(120);
        colDue.setComparator(ISO_DATE_COMPARATOR);

        TableColumn<SalesInvoiceSummary, String> colStatus = new TableColumn<>(t("list.column.status"));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(localizedInvoiceStatus(c.getValue().status())));
        colStatus.setPrefWidth(110);

        TableColumn<SalesInvoiceSummary, String> colPayment = new TableColumn<>(t("list.column.collection"));
        colPayment.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().collectionNotApplicable()
                        ? "—" : localizedPaymentStatus(c.getValue().paymentStatus())));
        colPayment.setPrefWidth(100);

        TableColumn<SalesInvoiceSummary, String> colTotal = new TableColumn<>(t("list.column.total"));
        colTotal.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().total() == null ? "" : money(c.getValue().total().toPlainString())));
        colTotal.setPrefWidth(110);
        // Sin esto "1.234,56 €" ordenaría alfabéticamente — "9" caería
        // después de "10" porque "9" > "1". El comparator extrae el
        // número y compara como BigDecimal.
        colTotal.setComparator(NUMERIC_STRING_COMPARATOR);

        // REFLEJO — columna que marca "↪ reflejada como gasto en {cliente}". El
        // mapa se rellena async tras cargar el listado (no hay vista de detalle).
        final java.util.Map<String, String> reflMap = new java.util.HashMap<>();
        TableColumn<SalesInvoiceSummary, String> colReflejo = new TableColumn<>(t("list.column.reflected"));
        colReflejo.setCellValueFactory(c -> {
            String name = c.getValue().id() == null ? null : reflMap.get(c.getValue().id());
            return new SimpleStringProperty(name == null || name.isBlank() ? "" : "↪ " + name);
        });
        colReflejo.setPrefWidth(170);

        billingTable.getColumns().addAll(List.of(colNumber, colCustomer, colDate, colDue, colStatus, colPayment, colTotal, colReflejo));
        billingTable.setItems(FXCollections.observableArrayList(initialList));
        // Cargar las facturas reflejadas y refrescar la columna.
        Task<java.util.List<java.util.Map<String, String>>> reflTask = new Task<>() {
            @Override protected java.util.List<java.util.Map<String, String>> call() throws Exception {
                return billingApiClient.listInvoiceReflections();
            }
        };
        reflTask.setOnSucceeded(ev -> {
            reflMap.clear();
            for (var m : reflTask.getValue()) {
                String sid = m.get("salesInvoiceId");
                if (sid != null && !sid.isBlank()) reflMap.put(sid, m.get("companyName"));
            }
            billingTable.refresh();
        });
        start(reflTask, "billing-reflections");
        // Doble click sobre fila editable -> abrir editor. Editable =
        // cualquier DRAFT, o una PROFORMA aunque ya esté VALIDATED
        // (las proformas no son documentos fiscales y siguen siendo
        // borradores comerciales hasta que se convierten a factura).
        billingTable.setRowFactory(tv -> {
            javafx.scene.control.TableRow<SalesInvoiceSummary> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    SalesInvoiceSummary inv = row.getItem();
                    // TPB-3: PENDING_CLIENT_APPROVAL tambien abre el editor
                    // — del lado de la asesoria para corregir si el cliente
                    // la ha rechazado, del lado del cliente para revisarla
                    // antes de aprobar. El backend ya impide cambios
                    // ilegales (numeracion ya consumida, status check).
                    boolean editable = "DRAFT".equals(inv.status())
                            || "PENDING_CLIENT_APPROVAL".equals(inv.status())
                            || "PROFORMA".equals(inv.invoiceType());
                    if (editable) {
                        host.showInvoiceEditor(inv.id());
                    } else {
                        Alert info = new Alert(Alert.AlertType.INFORMATION,
                                t("list.dialog.validated_no_edit"),
                                ButtonType.OK);
                        info.setHeaderText(t("list.dialog.validated_no_edit.header"));
                        info.showAndWait();
                    }
                }
            });
            return row;
        });

        Label header = label(t("list.header"), "settings-section-title");
        Label hint = new Label(t("list.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        VBox topBlock = new VBox(8, header, hint, filters);

        // Barra de acciones contextual: validar borrador, eliminar
        // borrador, generar PDF. Cada boton se autohabilita segun el
        // estado de la fila seleccionada (no tiene sentido validar una
        // VALIDATED ni borrar fisicamente algo que ya tiene numero
        // legal).
        Button validateRowBtn = new Button(t("editor.action.validate"));
        validateRowBtn.setGraphic(icon("fas-check"));
        validateRowBtn.getStyleClass().add("invoice-validate-action");
        validateRowBtn.setDisable(true);
        validateRowBtn.setOnAction(ev -> {
            SalesInvoiceSummary sel = billingTable.getSelectionModel().getSelectedItem();
            if (sel != null) host.validateInvoiceFromList(sel);
        });

        Button deleteDraftBtn = new Button(t("list.action.delete_draft"));
        deleteDraftBtn.setGraphic(icon("fas-trash-alt"));
        deleteDraftBtn.setDisable(true);
        deleteDraftBtn.setOnAction(ev -> {
            SalesInvoiceSummary sel = billingTable.getSelectionModel().getSelectedItem();
            if (sel != null) deleteDraftFromList(sel);
        });

        Button voidBtn = new Button(t("list.action.void"));
        voidBtn.setGraphic(icon("fas-ban"));
        voidBtn.setDisable(true);
        voidBtn.setOnAction(ev -> {
            SalesInvoiceSummary sel = billingTable.getSelectionModel().getSelectedItem();
            if (sel != null) voidInvoiceFromList(sel);
        });

        // RECT — rectificativa PARCIAL: corrige importes sin anular. Crea
        // el borrador con las líneas en negativo y abre el editor para
        // que el usuario deje solo la diferencia y valide.
        Button rectifyBtn = new Button(t("list.action.rectify"));
        rectifyBtn.setGraphic(icon("fas-edit"));
        rectifyBtn.setDisable(true);
        rectifyBtn.setOnAction(ev -> {
            SalesInvoiceSummary sel = billingTable.getSelectionModel().getSelectedItem();
            if (sel != null) rectifyInvoiceFromList(sel);
        });

        // Botón "mutante" según si el PDF ya está guardado en la ruta
        // configurada (F-STORAGE) o no:
        //   - pdfStored=true  → "Abrir PDF" (lo lanza con el visor del SO).
        //   - pdfStored=false → "Guardar PDF" (genera y escribe en la ruta,
        //                       creando companyId/YYYY/T{q}/ si no existen).
        // El listener de selección actualiza texto + handler.
        Button pdfBtn = new Button(t("list.action.save_pdf"));
        pdfBtn.setGraphic(icon("fas-file-pdf"));
        pdfBtn.setDisable(true);
        pdfBtn.setUserData(Boolean.FALSE);  // estado pdfStored asociado al botón

        // F-EMAIL: enviar factura por email al cliente. Solo VALIDATED
        // — borradores no son documentos legales, anuladas tampoco se
        // envian por defecto (si llega caso de uso real, se abre slice
        // aparte).
        Button emailBtn = new Button(t("list.action.send_email"));
        emailBtn.setGraphic(icon("fas-paper-plane"));
        emailBtn.setDisable(true);
        emailBtn.setOnAction(ev -> {
            SalesInvoiceSummary sel = billingTable.getSelectionModel().getSelectedItem();
            if (sel != null) sendInvoiceByEmail(sel);
        });

        // Acción de conversión proforma → factura standard. Aparece
        // activa para cualquier PROFORMA (DRAFT o VALIDATED — porque
        // una proforma "validada" sigue siendo borrador comercial
        // mientras no se convierte). Al pulsar, el server reemite el
        // siguiente número de la serie STANDARD (FRA-XXXX), cambia el
        // invoice_type y registra en VeriFactu.
        //
        // No hay botón "A borrador" para proformas: como las proformas
        // no son fiscales, no necesitan paso por borrador NORMAL. El
        // usuario edita la proforma, y cuando el cliente la acepta,
        // pulsa "Convertir y validar".
        Button toValidatedBtn = new Button(t("list.action.proforma_to_validated"));
        toValidatedBtn.setGraphic(icon("fas-check-double"));
        toValidatedBtn.setDisable(true);
        toValidatedBtn.setOnAction(ev -> {
            SalesInvoiceSummary sel = billingTable.getSelectionModel().getSelectedItem();
            if (sel != null) convertProforma(sel, true);
        });

        // Wire up de habilitacion segun la fila seleccionada.
        // - Validar / Eliminar borrador: solo en DRAFT.
        // - Anular: solo en STANDARD VALIDATED. Una rectificativa YA es el
        //   acto legal de anular; anularla a su vez no tiene sentido (y
        //   el backend lo rechazaría con 409).
        // - PDF: cualquier estado EXCEPTO DRAFT. Un borrador no es
        //   documento legal (no tiene número emitido), pero VALIDATED /
        //   VOIDED / CANCELLED sí — debe poder regenerarse el PDF por
        //   archivo o si el usuario lo perdió.
        // - Email: solo VALIDATED. Borrador no procede; anulada se
        //   comunica por otros medios (telefono, presencial) por
        //   defecto.
        billingTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            boolean isDraft = newV != null && "DRAFT".equals(newV.status());
            boolean isValidated = newV != null && "VALIDATED".equals(newV.status());
            boolean isRectifying = newV != null && "RECTIFYING".equals(newV.invoiceType());
            boolean isProforma = newV != null && "PROFORMA".equals(newV.invoiceType());
            // IMP-H: una factura historica importada (CONTENDO) es de solo
            // lectura. No se anula (no esta en VeriFactu), no genera PDF
            // oficial y no se envia por email desde aqui.
            boolean isHistorical = newV != null && "HISTORICAL".equals(newV.invoiceType());
            // Validar y eliminar borrador: solo DRAFT que NO sea proforma
            // (la proforma se valida en su flujo propio: "Convertir y
            // validar", y se elimina con su propio botón aunque esté
            // VALIDATED porque no tiene valor fiscal).
            validateRowBtn.setDisable(!isDraft || isProforma);
            // Eliminar: DRAFT normales O cualquier proforma (no fiscal).
            deleteDraftBtn.setDisable(!(isDraft && !isProforma) && !isProforma);
            // Anular: solo facturas legales (NORMAL VALIDATED, no
            // rectificativas ni proformas). Una proforma no tiene valor
            // fiscal: no hay nada legal que anular.
            voidBtn.setDisable(!isValidated || isRectifying || isProforma || isHistorical);
            // Rectificar (parcial): mismas condiciones que Anular — solo
            // documentos fiscales validados (NORMAL o SIMPLIFIED).
            rectifyBtn.setDisable(!isValidated || isRectifying || isProforma || isHistorical);
            pdfBtn.setDisable(newV == null || isDraft || isHistorical);
            // "Convertir y validar": cualquier proforma en DRAFT o
            // VALIDATED. Editarla sigue siendo posible hasta que se
            // convierte (entra al editor con doble click).
            toValidatedBtn.setDisable(!isProforma || (!isDraft && !isValidated));
            // Mutación del botón PDF: si la factura ya tiene PDF
            // almacenado en la ruta configurada, ofrecemos "Abrir";
            // si no, "Guardar". El estado se guarda en userData del
            // botón para que el handler pueda discriminar sin volver
            // a leer la fila seleccionada.
            boolean stored = newV != null && Boolean.TRUE.equals(newV.pdfStored());
            pdfBtn.setUserData(stored);
            pdfBtn.setText(stored ? t("list.action.open_pdf") : t("list.action.save_pdf"));
            pdfBtn.setGraphic(icon(stored ? "fas-file-pdf" : "fas-save"));
            emailBtn.setDisable(!isValidated || isHistorical);
        });

        pdfBtn.setOnAction(ev -> {
            SalesInvoiceSummary sel = billingTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            if (Boolean.TRUE.equals(pdfBtn.getUserData())) {
                openInvoicePdf(sel);
            } else {
                storeInvoicePdf(sel);
            }
        });

        // Slice 3E-1 — Botón "Hacer recurrente" para convertir
        // una factura VALIDATED NORMAL en plantilla recurrente
        // directamente, sin tener que esperar al banner de detección.
        // Solo se habilita para facturas legales (VALIDATED + NORMAL):
        // proformas y rectificativas no aplican como plantillas.
        Button makeRecurringBtn = new Button(t("list.action.make_recurring"));
        makeRecurringBtn.setGraphic(icon("fas-arrows-rotate"));
        makeRecurringBtn.setDisable(true);
        makeRecurringBtn.setOnAction(ev -> {
            SalesInvoiceSummary sel = billingTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            LocalDate invDate = null;
            try {
                if (sel.invoiceDate() != null && !sel.invoiceDate().isBlank()) {
                    invDate = LocalDate.parse(sel.invoiceDate());
                }
            } catch (Exception ignored) { /* fecha en formato inesperado */ }
            host.openRecurringEditorFromInvoice(
                    "SALES_INVOICE", sel.customerTaxId(), sel.customerLegalName(),
                    sel.total(), invDate);
        });

        // Hook al listener existente: activar el botón cuando la
        // selección sea una NORMAL VALIDATED.
        billingTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            boolean isValidated = newV != null && "VALIDATED".equals(newV.status());
            boolean isNormal = newV != null
                    && ("NORMAL".equals(newV.invoiceType()) || newV.invoiceType() == null);
            makeRecurringBtn.setDisable(!(isValidated && isNormal));
        });

        // MULTI-ALLOCATION (#67) — boton para registrar un pago real
        // que se reparte entre varias facturas VALIDATED pendientes del
        // mismo cliente. Siempre habilitado.
        Button multiAllocBtn = new Button(t("list.action.multi_allocation"));
        multiAllocBtn.setGraphic(icon("fas-coins"));
        multiAllocBtn.setOnAction(ev -> host.showMultiAllocationDialog());

        // REC-BANCARIA (#68) — abre dialogo con sugerencias de match
        // entre movimientos bancarios no conciliados y facturas
        // pendientes. El asesor confirma cada match.
        Button bankReconcileBtn = new Button(t("list.action.bank_reconcile"));
        bankReconcileBtn.setGraphic(icon("fas-link"));
        bankReconcileBtn.setOnAction(ev -> host.showBankReconciliationDialog());

        // PV-7 — cobro por plazos: vencimientos de la factura de venta.
        // Solo para facturas VALIDATED (un borrador no tiene cobro).
        Button dueDatesSalesBtn = new Button(t("duedates.action.open_sales"));
        dueDatesSalesBtn.setGraphic(icon("fas-calendar-check"));
        dueDatesSalesBtn.setDisable(true);
        dueDatesSalesBtn.setOnAction(ev -> {
            SalesInvoiceSummary sel = billingTable.getSelectionModel().getSelectedItem();
            if (sel != null) host.openDueDatesDialog("SALES", sel.id(),
                    sel.customerLegalName(), sel.total());
        });
        billingTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) ->
                dueDatesSalesBtn.setDisable(newV == null || !"VALIDATED".equals(newV.status())));

        // OPTYPE-3 — clasificación fiscal del tipo de operación (para marcar una
        // entrega intracomunitaria → clave E del modelo 349). Solo tiene sentido
        // en facturas VALIDATED (las que declara el 349).
        Button classifySalesBtn = new Button(t("list.action.classify"));
        classifySalesBtn.setGraphic(icon("fas-tags"));
        classifySalesBtn.setDisable(true);
        classifySalesBtn.setOnAction(ev -> {
            SalesInvoiceSummary sel = billingTable.getSelectionModel().getSelectedItem();
            if (sel != null) host.showSalesClassificationDialog(sel.id());
        });
        billingTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) ->
                classifySalesBtn.setDisable(newV == null || !"VALIDATED".equals(newV.status())));

        // DOBLE PANTALLA — barra de acciones en FlowPane: si no cabe en el ancho de
        // la ventana (portátil), los botones ENVUELVEN a la línea siguiente y se ven
        // todos. Antes era un HBox con overflow horizontal SIN scroll -> botones
        // atrapados fuera. La función `actionFlow` es reutilizable para otras barras.
        javafx.scene.layout.FlowPane rowActions = actionFlow(
                validateRowBtn, deleteDraftBtn, voidBtn, rectifyBtn, toValidatedBtn, makeRecurringBtn,
                multiAllocBtn, bankReconcileBtn, dueDatesSalesBtn, emailBtn, pdfBtn, classifySalesBtn);

        // Slice 3V — Acciones SOBRE la tabla, no debajo. Antes vivían
        // bajo el listado y el asesor tenía que hacer scroll para
        // verlos cuando había muchas facturas. Ahora van encima.
        //
        // Slice 3D-2 — banner de candidatos a recurrencia (facturas
        // repetidas con mismo cliente + importe). Si no hay
        // candidatos el banner se oculta solo.
        Node candidatesBanner = host.buildRecurringCandidatesBanner("SALES_INVOICE");
        VBox bottomBlock = new VBox(12, rowActions, candidatesBanner, billingTable);
        // LAYOUT-FILL: la tabla crece para llenar el alto disponible y scrollea
        // por dentro (un solo scroll), en vez de quedar fija en pantalla pequena.
        VBox.setVgrow(billingTable, Priority.ALWAYS);

        // Auto-refresh cuando alguien emite SALES (crear/validar/anular/
        // borrar/cobrar factura). Auto-baja al desmontar la pantalla.
        com.benjagest.ui.support.RefreshBus.subscribe(
                com.benjagest.ui.support.RefreshBus.TOPIC_SALES,
                this::reloadInvoices, billingTable);

        return tabLayoutFill(topBlock, bottomBlock, new HBox());
    }

    /**
     * Abre el PDF de la factura ya almacenada (pdfStored=true). Descarga
     * la copia que vive en la ruta configurada por el backend a un
     * archivo temporal y lanza el visor con {@code Desktop.open()}.
     *
     * Por qué temp y no abrir directamente el path del backend: la UI
     * podría correr en una máquina distinta de la del backend en el
     * futuro; bajar por HTTP funciona siempre. El TMP se sobrescribe
     * en cada apertura, sin acumular ficheros.
     */
    private void openInvoicePdf(SalesInvoiceSummary sel) {
        String filename = (sel.invoiceNumber() == null || sel.invoiceNumber().isBlank())
                ? "borrador-" + shortId(sel.id()) + ".pdf"
                : sel.invoiceNumber().replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf";

        Task<byte[]> task = new Task<>() {
            @Override
            protected byte[] call() throws Exception {
                return billingApiClient.downloadInvoicePdf(sel.id());
            }
        };
        task.setOnSucceeded(ev -> {
            try {
                java.io.File tempFile = new java.io.File(
                        System.getProperty("java.io.tmpdir"), filename);
                java.nio.file.Files.write(tempFile.toPath(), task.getValue());
                try {
                    java.awt.Desktop.getDesktop().open(tempFile);
                } catch (Exception openEx) {
                    showError(t("list.dialog.pdf.open_failed.title"),
                            t("list.dialog.pdf.open_failed.body"));
                }
            } catch (Exception writeEx) {
                showError(t("list.dialog.pdf.save_failed.title"),
                        t("list.dialog.pdf.save_failed.body"));
            }
        });
        task.setOnFailed(ev -> showError(t("list.dialog.pdf.download_failed.title"),
                t("list.dialog.pdf.download_failed.body")));
        start(task, "billing-invoice-pdf");
    }

    /**
     * Genera y guarda el PDF en la ruta configurada
     * ({@code {root}/{companyId}/{YYYY}/T{q}/{nº}.pdf}). El backend
     * crea las subcarpetas año y trimestre si no existen. Tras éxito,
     * recarga la lista para que el botón mute a "Abrir PDF".
     *
     * Útil para facturas legacy (validadas antes de F-STORAGE) o
     * cuando el archivo se borró manualmente del disco.
     */
    private void storeInvoicePdf(SalesInvoiceSummary sel) {
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return billingApiClient.storeInvoicePdf(sel.id());
            }
        };
        task.setOnSucceeded(ev -> {
            Alert ok = new Alert(Alert.AlertType.INFORMATION,
                    t("list.dialog.pdf.stored_prefix") + task.getValue()
                            + t("list.dialog.pdf.stored_suffix"),
                    ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
            // Refresca el listado para que el SUMMARY traiga
            // pdfStored=true y el botón mute a "Abrir PDF".
            reloadInvoices();
        });
        task.setOnFailed(ev -> showError(t("list.dialog.pdf.store_failed.title"),
                t("list.dialog.pdf.store_failed.body")));
        start(task, "billing-invoice-store-pdf");
    }

    /**
     * Envía la factura validada al email del cliente. F-EMAIL.
     *
     * Diálogo de confirmación con TextField pre-rellenado (vacío si el
     * cliente no tiene contacto principal) — así el usuario puede
     * sobreescribir el destinatario o introducir uno si falta. Si lo
     * deja vacío se manda al primary_contact por defecto.
     */
    private void sendInvoiceByEmail(SalesInvoiceSummary sel) {
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog();
        dialog.setHeaderText(t("list.dialog.email.title") + "\n" + sel.invoiceNumber());
        dialog.setContentText(t("list.dialog.email.recipient_label"));
        dialog.setTitle(t("list.dialog.email.window_title"));
        Optional<String> ans = dialog.showAndWait();
        if (ans.isEmpty()) return;
        String override = ans.get().trim();
        // Nota: vacio = usar el email registrado del cliente. El
        // backend rechaza con 400 si no hay ni override ni primary.

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return billingApiClient.sendInvoiceByEmail(sel.id(), override.isEmpty() ? null : override);
            }
        };
        task.setOnSucceeded(ev -> {
            String recipient = task.getValue();
            String body = t("list.dialog.email.success_prefix")
                    + (recipient == null ? "" : recipient)
                    + t("list.dialog.email.success_suffix");
            Alert ok = new Alert(Alert.AlertType.INFORMATION, body, ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
        });
        task.setOnFailed(ev -> {
            // Mostrar la causa REAL del backend (humanizada) en vez del genérico
            // de "comprueba SMTP", que ocultaba el motivo real: cliente sin email,
            // fallo de envío Gmail, SMTP mal configurado, etc. (mismo patrón que
            // validateInvoiceFromList). showError ya humaniza; el guard evita
            // mostrar el "HTTP 400: {json}" crudo si no se pudo extraer el message.
            String raw = task.getException() == null ? null : task.getException().getMessage();
            String real = com.benjagest.ui.support.BackendErrors.humanize(raw);
            String body = (real == null || real.isBlank() || real.equals(raw))
                    ? t("list.dialog.email.fail.body")
                    : real;
            showError(t("list.dialog.email.fail.title"), body);
        });
        start(task, "billing-invoice-email");
    }

    /**
     * Convierte una proforma (DRAFT o VALIDATED) en factura NORMAL
     * validada en la misma transacción: cambia el invoice_type, emite
     * el siguiente número STANDARD (FRA-XXXX), calcula hash VeriFactu,
     * genera PDF con QR oficial AEAT y lo almacena.
     *
     * Confirmación obligatoria — emitir un número STANDARD es
     * irreversible aunque luego se anule (la posición queda quemada).
     *
     * @param validate siempre TRUE en el flujo actual (parámetro
     *                 conservado por compat con la API del backend
     *                 que sigue aceptando validate=false para futuros
     *                 casos de uso).
     */
    private void convertProforma(SalesInvoiceSummary sel, boolean validate) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("list.dialog.proforma.convert_validate.body"),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("list.dialog.proforma.convert_validate.title"));
        Optional<ButtonType> ans = confirm.showAndWait();
        if (ans.isEmpty() || ans.get() != ButtonType.OK) return;

        Task<SalesInvoiceSummary> task = new Task<>() {
            @Override
            protected SalesInvoiceSummary call() throws Exception {
                return billingApiClient.convertProformaToStandard(sel.id(), validate);
            }
        };
        task.setOnSucceeded(ev -> {
            SalesInvoiceSummary result = task.getValue();
            Alert ok = new Alert(Alert.AlertType.INFORMATION,
                    t("list.dialog.proforma.success_prefix")
                            + (result.invoiceNumber() == null ? "—" : result.invoiceNumber())
                            + t("list.dialog.proforma.success_suffix"),
                    ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
            reloadInvoices();
            // REFRESH-AUDIT — al validar la conversión se crea asiento de ventas:
            // avisar a Contabilidad (y al listado de ventas embebido en la ficha).
            com.benjagest.ui.support.RefreshBus.emit(
                    com.benjagest.ui.support.RefreshBus.TOPIC_SALES,
                    com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL);
        });
        task.setOnFailed(ev -> showError(t("list.dialog.proforma.fail.title"),
                t("list.dialog.proforma.fail.body")));
        start(task, "billing-invoice-convert-proforma");
    }

    /**
     * RECT — selector de causa legal R1-R4 compartido por Anular y
     * Rectificar. Si la factura es SIMPLIFIED no hay elección: la ley
     * fija R5 (el backend la aplica solo) y se muestra un aviso fijo.
     * Devuelve la causa elegida, o null si el usuario cancela.
     */
    private String askRectificationCause(SalesInvoiceSummary sel, String titleKey, String bodyKey) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t(titleKey));
        dialog.setHeaderText(t(titleKey));
        Label body = new Label(t(bodyKey));
        body.setWrapText(true);
        VBox content = new VBox(10, body);
        content.setPrefWidth(560);
        ComboBox<String> causeCombo = new ComboBox<>();
        boolean simplified = "SIMPLIFIED".equals(sel.invoiceType());
        if (simplified) {
            Label r5 = new Label(t("rect.cause.r5_auto"));
            r5.setWrapText(true);
            content.getChildren().add(r5);
        } else {
            causeCombo.getItems().addAll("R1", "R2", "R3", "R4");
            causeCombo.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(String c) {
                    return c == null ? "" : c + " — " + t("rect.cause." + c);
                }
                @Override public String fromString(String s) { return s; }
            });
            causeCombo.getSelectionModel().select("R4");
            causeCombo.setMaxWidth(Double.MAX_VALUE);
            content.getChildren().addAll(new Label(t("rect.cause.label")), causeCombo);
        }
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Optional<ButtonType> ans = dialog.showAndWait();
        if (ans.isEmpty() || ans.get() != ButtonType.OK) return null;
        return simplified ? "R5" : causeCombo.getValue();
    }

    private void voidInvoiceFromList(SalesInvoiceSummary sel) {
        if (!host.ensureBillingAllowed(true)) return; // AGR-2
        String cause = askRectificationCause(sel, "list.dialog.void.title", "list.dialog.void.body");
        if (cause == null) return;

        Task<SalesInvoiceSummary> task = new Task<>() {
            @Override
            protected SalesInvoiceSummary call() throws Exception {
                return billingApiClient.voidInvoice(sel.id(), cause);
            }
        };
        task.setOnSucceeded(ev -> {
            SalesInvoiceSummary rect = task.getValue();
            // El server ya devuelve la rectificativa VALIDATED, así que
            // invoiceNumber viene relleno (ej. RECT-2026-0001).
            String rectLabel = rect.invoiceNumber() == null || rect.invoiceNumber().isBlank()
                    ? shortId(rect.id())
                    : rect.invoiceNumber();
            Alert ok = new Alert(Alert.AlertType.INFORMATION,
                    t("list.dialog.void.success_prefix") + rectLabel
                            + t("list.dialog.void.success_suffix"),
                    ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
            // Anular emite la rectificativa: cambian ambas facturas + el
            // asiento de cada una.
            com.benjagest.ui.support.RefreshBus.emit(
                    com.benjagest.ui.support.RefreshBus.TOPIC_SALES,
                    com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL);
        });
        task.setOnFailed(ev -> showError(t("list.dialog.void.failure.title"),
                t("list.dialog.void.failure.body")));
        start(task, "billing-invoice-void");
    }

    /**
     * RECT — rectificativa PARCIAL. El backend crea el borrador con las
     * líneas de la original en NEGATIVO; abrimos el editor sobre él para
     * que el usuario deje solo la corrección y valide. La original no
     * se anula (sigue VALIDATED, con vínculo).
     */
    private void rectifyInvoiceFromList(SalesInvoiceSummary sel) {
        if (!host.ensureBillingAllowed(true)) return; // AGR-2
        String cause = askRectificationCause(sel, "list.dialog.rectify.title", "list.dialog.rectify.body");
        if (cause == null) return;

        Task<SalesInvoiceSummary> task = new Task<>() {
            @Override
            protected SalesInvoiceSummary call() throws Exception {
                return billingApiClient.rectifyInvoice(sel.id(), cause);
            }
        };
        task.setOnSucceeded(ev -> {
            SalesInvoiceSummary draft = task.getValue();
            com.benjagest.ui.support.RefreshBus.emit(
                    com.benjagest.ui.support.RefreshBus.TOPIC_SALES);
            host.showInvoiceEditor(draft.id());
        });
        // showError ya humaniza el mensaje del backend (BackendErrors.humanize).
        task.setOnFailed(ev -> showError(t("list.dialog.rectify.failure.title"),
                task.getException() == null ? "" : task.getException().getMessage()));
        start(task, "billing-invoice-rectify");
    }

    private void deleteDraftFromList(SalesInvoiceSummary sel) {
        if (!host.ensureBillingAllowed(true)) return; // AGR-2
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("list.dialog.delete.body"),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("list.dialog.delete.title"));
        Optional<ButtonType> ans = confirm.showAndWait();
        if (ans.isEmpty() || ans.get() != ButtonType.OK) return;

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                billingApiClient.deleteInvoice(sel.id());
                return null;
            }
        };
        task.setOnSucceeded(ev -> com.benjagest.ui.support.RefreshBus.emit(
                com.benjagest.ui.support.RefreshBus.TOPIC_SALES,
                com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL));
        task.setOnFailed(ev -> showError(t("list.dialog.delete.failure_title"),
                t("list.dialog.delete.failure_body")));
        start(task, "billing-invoice-delete-from-list");
    }

    private void reloadInvoices() {
        String status = mapAllOrValue(billingStatusFilter.getValue());
        String payment = mapAllOrValue(billingPaymentFilter.getValue());
        String type = billingTypeFilter == null ? null : mapAllOrValue(billingTypeFilter.getValue());
        Task<List<SalesInvoiceSummary>> task = new Task<>() {
            @Override
            protected List<SalesInvoiceSummary> call() throws Exception {
                return billingApiClient.listInvoices(status, payment, type, 200);
            }
        };
        task.setOnSucceeded(event -> billingTable.setItems(FXCollections.observableArrayList(task.getValue())));
        task.setOnFailed(event -> showError(t("list.dialog.reload_failed.title"), t("list.dialog.reload_failed.body")));
        start(task, "billing-invoices-reload");
    }

    /**
     * Traduce el código técnico del estado de una factura
     * (DRAFT/VALIDATED/CANCELLED/VOIDED) a la versión visible del idioma
     * activo. Si el código no se reconoce, lo devuelve tal cual — útil
     * para el "(todos)" del filtro u otros placeholders.
     */
    private String localizedInvoiceStatus(String code) {
        if (code == null) return "";
        return switch (code) {
            case "DRAFT" -> t("status.invoice.draft");
            case "PENDING_CLIENT_APPROVAL" -> t("status.invoice.pending_client_approval");
            case "VALIDATED" -> t("status.invoice.validated");
            case "CANCELLED" -> t("status.invoice.cancelled");
            case "VOIDED" -> t("status.invoice.voided");
            default -> code;
        };
    }

    /**
     * Traduce el código de payment_status
     * (PENDING/PARTIAL/PAID/OVERDUE) al idioma activo.
     */
    private String localizedPaymentStatus(String code) {
        if (code == null) return "";
        return switch (code) {
            case "PENDING" -> t("status.payment.pending");
            case "PARTIAL" -> t("status.payment.partial");
            case "PAID" -> t("status.payment.paid");
            case "OVERDUE" -> t("status.payment.overdue");
            default -> code;
        };
    }

    /** Etiqueta humana de un invoice_type (NORMAL/PROFORMA) traducida. */
    private String localizedInvoiceTypeLabel(String code) {
        if (code == null) return "";
        return switch (code) {
            case "NORMAL" -> t("editor.kind.normal");
            case "SIMPLIFIED" -> t("editor.kind.simplified");
            case "PROFORMA" -> t("editor.kind.proforma");
            case "RECTIFYING" -> t("editor.kind.rectifying");
            case "HISTORICAL" -> t("editor.kind.historical");
            default -> code;
        };
    }

    private String mapAllOrValue(String selection) {
        // Comparamos contra ambas variantes (ES y EN) del "todos" porque el
        // ComboBox se rellena con t() y el usuario puede haber cambiado de
        // idioma entre selecciones.
        return selection == null
                || t("list.filter.all").equals(selection)
                || "(todos)".equals(selection)
                || "(all)".equals(selection)
                ? null : selection;
    }

    // ----- helpers locales (copias stateless del shell) -----

    /**
     * DOBLE PANTALLA — barra de acciones que ENVUELVE (FlowPane) en vez de hacer
     * overflow horizontal sin scroll. Si los botones no caben en el ancho de la
     * ventana (portátil), pasan a la línea siguiente y se ven todos. Mantiene el
     * minWidth de cada botón (texto entero, sin "..."). Reutilizable en cualquier
     * pantalla con muchos botones de acción.
     */
    private javafx.scene.layout.FlowPane actionFlow(javafx.scene.Node... children) {
        javafx.scene.layout.FlowPane fp = new javafx.scene.layout.FlowPane(6, 6, children);
        fp.getStyleClass().add("settings-actions");
        for (javafx.scene.Node n : children) {
            if (n instanceof Region r) r.setMinWidth(Region.USE_PREF_SIZE);
        }
        return fp;
    }

    /** Grupo etiqueta+control que se mantiene junto al envolver un {@link #actionFlow}. */
    private HBox filterGroup(String labelText, javafx.scene.Node control) {
        HBox g = new HBox(6, label(labelText, "form-label"), control);
        g.setAlignment(Pos.CENTER_LEFT);
        return g;
    }

    /**
     * LAYOUT-FILL — variante de tabLayout para tabs cuyo cuerpo es una
     * TABLA/listado. El cuerpo va DIRECTO al centro del BorderPane (sin
     * ScrollPane), de modo que el centro lo redimensiona al alto disponible y la
     * tabla (con {@code VBox.setVgrow(tabla, ALWAYS)}) se ADAPTA a ese alto y
     * scrollea por DENTRO (un solo scroll).
     */
    private Node tabLayoutFill(Node header, Node body, Node footerActions) {
        VBox bottom = new VBox(12, new Separator(), footerActions);
        BorderPane layout = new BorderPane();
        layout.setTop(header);
        layout.setCenter(body);
        layout.setBottom(bottom);
        layout.getStyleClass().add("settings-tab-body");
        BorderPane.setMargin(body, new Insets(12, 0, 12, 0));
        return layout;
    }
}
