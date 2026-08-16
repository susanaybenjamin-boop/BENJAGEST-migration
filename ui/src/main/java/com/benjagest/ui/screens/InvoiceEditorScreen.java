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
import javafx.scene.layout.*;
import javafx.stage.*;

/**
 * Editor de factura — el corazón del bloque FACTURACIÓN. Extraído del God Object
 * en FAC-2. Reutilizable: el shell mantiene {@code showInvoiceEditor(id)} como
 * wrapper (recordNav + new InvoiceEditorScreen(...).show(id)), así los callers no
 * cambian. Los diálogos/paneles propios del shell (volver, prerequisitos, nuevo
 * cliente) se inyectan vía {@link Host}.
 *
 * <p>Reusa form-grid/form-input/data-table del CSS de Pablo (regla: no inventar
 * paletas).
 */
public class InvoiceEditorScreen extends ScreenBase {

    /** Callbacks que viven en el shell y el editor necesita invocar. */
    public interface Host {
        /** "Volver": al cliente si la asesoría actúa-como uno; si no, a Facturación. */
        void onEditorClose();
        /** Abre el diálogo de nuevo cliente; al guardar ejecuta {@code onSaved}. */
        void openNewCustomer(Runnable onSaved);
        /** Abre el editor de un cliente EXISTENTE (prerelleno) para completar sus
         *  datos; al guardar ejecuta {@code onSaved}. Usado por FAC-CLIVAL cuando
         *  faltan datos fiscales para facturar. */
        void openCustomerEditor(String customerId, Runnable onSaved);
        /** Reabre el editor pasando por el wrapper del shell (recordNav incluido). */
        void reopenEditor(String existingInvoiceId);
        /** Panel "blocker" de precondición (solo botón volver). */
        VBox prerequisitePanel(String title, String details);
        /** Panel "blocker" con un botón de acción primario. */
        VBox prerequisitePanel(String title, String details,
                               String actionLabel, String actionIcon, Runnable action);
    }

    private final BillingApiClient billingApiClient;
    private final CustomerApiClient customerApiClient;
    private final AltaApiClient altaApiClient;
    private final Host host;

    private ComboBox<CustomerSummary> editorCustomerCombo;
    private ComboBox<String> editorInvoiceTypeCombo;
    private javafx.scene.control.DatePicker editorInvoiceDate;
    private javafx.scene.control.DatePicker editorDueDate;
    private CheckBox editorNoDueDateChk;
    private javafx.scene.control.TextArea editorNotesArea;
    private TableView<InvoiceLineDraft> editorLinesTable;
    // TIPO-IVA-CLIENTE: IVA/retención por defecto del editor de factura. Arrancan
    // en 21/0 y se ajustan al IVA/retención del cliente cuando se elige uno; las
    // líneas nuevas los usan.
    private java.math.BigDecimal editorDefaultVat = new java.math.BigDecimal("21");
    private java.math.BigDecimal editorDefaultRetention = java.math.BigDecimal.ZERO;
    // TRB — ids de trabajos importados al editor; al guardar/validar se marcan FACTURADOS.
    private final java.util.List<String> editorPendingWorkLogIds = new java.util.ArrayList<>();
    private Label editorSubtotalLabel;
    private Label editorVatLabel;
    private Label editorRetentionLabel;
    private Label editorTotalLabel;

    // Mapas de Label por fila para las columnas calculadas (Subtotal/Total
    // de cada linea). Los rellena la cellFactory cuando bindea, los lee el
    // listener del decimalColumn al teclear. Asi actualizamos los importes
    // visibles de la linea SIN llamar a editorLinesTable.refresh() (que
    // pierde el foco del TextField que estaba editando). IdentityHashMap
    // porque la clave es el objeto fila por identidad, no por equals.
    private final java.util.Map<InvoiceLineDraft, Label> rowSubtotalLabels = new java.util.IdentityHashMap<>();
    private final java.util.Map<InvoiceLineDraft, Label> rowLineTotalLabels = new java.util.IdentityHashMap<>();

    public InvoiceEditorScreen(BillingApiClient billingApiClient,
                               CustomerApiClient customerApiClient,
                               AltaApiClient altaApiClient,
                               Function<String, String> tt,
                               Router router,
                               Host host) {
        super(tt, router);
        this.billingApiClient = billingApiClient;
        this.customerApiClient = customerApiClient;
        this.altaApiClient = altaApiClient;
        this.host = host;
    }

    //  - show(null)  => crear desde cero (DRAFT vacio).
    //  - show(id)    => cargar DRAFT existente y editar.
    public void show(String existingInvoiceId) {
        // TIPO-IVA-CLIENTE: cada apertura del editor parte de 21/0 hasta que se
        // elija cliente (si no, conservaríamos el tipo del cliente anterior).
        editorDefaultVat = new java.math.BigDecimal("21");
        editorDefaultRetention = java.math.BigDecimal.ZERO;
        Task<EditorBundle> task = new Task<>() {
            @Override
            protected EditorBundle call() throws Exception {
                List<CustomerSummary> customers = customerApiClient.list();
                List<SeriesEntry> series = billingApiClient.listSeries();
                SalesInvoiceSummary existing = null;
                List<InvoiceLineDraft> lines = new java.util.ArrayList<>();
                if (existingInvoiceId != null) {
                    existing = billingApiClient.getInvoiceById(existingInvoiceId);
                    lines = billingApiClient.getInvoiceLines(existingInvoiceId);
                }
                return new EditorBundle(customers, series, existing, lines);
            }
        };
        task.setOnSucceeded(event -> {
            EditorBundle bundle = task.getValue();
            // Guardas de precondicion: sin clientes o sin series no tiene
            // sentido abrir el editor. Mensaje accionable en vez de un
            // formulario en blanco que despues falla al guardar.
            if (existingInvoiceId == null && bundle.customers().isEmpty()) {
                // TPB-CLIENT-SETUP F1: en vez de bloquear sin accion,
                // ofrecemos crear el cliente-receptor aqui mismo. Tras
                // crearlo, reabrimos el editor (ya habra >=1 customer).
                setCenterAnimated(scroll(host.prerequisitePanel(
                        t("prereq.no_customers.title"),
                        t("prereq.no_customers.body"),
                        t("prereq.no_customers.create"),
                        "fas-user-plus",
                        () -> host.openNewCustomer(() -> host.reopenEditor(null)))));
                return;
            }
            if (existingInvoiceId == null && bundle.series().isEmpty()) {
                setCenterAnimated(scroll(host.prerequisitePanel(
                        t("prereq.no_series.title"),
                        t("prereq.no_series.body"))));
                return;
            }
            setCenterAnimated(scroll(invoiceEditorView(bundle, existingInvoiceId)));
        });
        task.setOnFailed(event -> setCenterAnimated(scroll(errorPanel(
                existingInvoiceId == null
                        ? t("prereq.editor_failed")
                        : t("prereq.invoice_load_failed")))));
        start(task, "billing-editor-load");
    }

    private record EditorBundle(List<CustomerSummary> customers,
                                List<SeriesEntry> series,
                                SalesInvoiceSummary existing,
                                List<InvoiceLineDraft> existingLines) {
    }

    private VBox invoiceEditorView(EditorBundle bundle, String existingId) {
        VBox content = content();
        editorPendingWorkLogIds.clear();

        // ----- Header -----
        // NAV-CLIENT-BACK: "Volver" vuelve al cliente si la asesoria
        // esta actuando-como uno; si no, a la facturacion normal.
        Button back = new Button(t("editor.back"));
        back.setGraphic(icon("fas-arrow-left"));
        back.setOnAction(event -> host.onEditorClose());

        Label title = new Label(existingId == null ? t("editor.title.new") : t("editor.title.edit"));
        title.getStyleClass().add("module-detail-title");
        Label subtitle = new Label(existingId == null
                ? t("editor.subtitle.new")
                : t("editor.subtitle.edit"));
        subtitle.getStyleClass().add("module-detail-description");

        // Pill grande con el numero que se asignara al validar. Se mantiene
        // igual aunque guardes varios borradores: solo "Validar y emitir"
        // consume el correlativo. Cuando cambias de serie en el combo se
        // recalcula en vivo.
        Label nextNumberBadgeLabel = label(t("editor.next_number.caption"), "invoice-next-number-caption");
        Label nextNumberBadgeValue = new Label("—");
        nextNumberBadgeValue.getStyleClass().add("invoice-next-number-value");
        VBox nextNumberBadge = new VBox(2, nextNumberBadgeLabel, nextNumberBadgeValue);
        nextNumberBadge.getStyleClass().add("invoice-next-number-badge");

        VBox titleBox = new VBox(4, title, subtitle);
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        StackPane moduleIcon = iconBubble("fas-file-invoice", "module-title-icon");
        HBox header = new HBox(16, back, titleBox, headerSpacer, nextNumberBadge, moduleIcon);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        // ----- Setup widgets (mismos nombres de campo: persistDraft sigue
        //  funcionando sin tocarlo) -----
        editorCustomerCombo = new ComboBox<>();
        editorCustomerCombo.getItems().addAll(bundle.customers());
        editorCustomerCombo.getStyleClass().add("invoice-input");
        editorCustomerCombo.setMaxWidth(Double.MAX_VALUE);
        configureCustomerCombo(editorCustomerCombo);
        if (existingId != null && bundle.existing() != null) {
            for (CustomerSummary c : bundle.customers()) {
                if (c.legalName() != null && c.legalName().equals(bundle.existing().customerLegalName())) {
                    editorCustomerCombo.getSelectionModel().select(c);
                    break;
                }
            }
        } else if (!bundle.customers().isEmpty()) {
            editorCustomerCombo.getSelectionModel().selectFirst();
        }

        // Ya no exponemos un combo "Serie" al usuario. La serie se elige
        // en el servidor segun el invoice_type (NORMAL → STANDARD). El
        // editor solo crea facturas normales — proformas/rectificativas
        // tienen flujo aparte (proximamente). De este modo cumplimos
        // RD 1619/2012 Art.13 por construccion: el usuario no puede
        // mezclar series por error.
        //
        // TPB-2: si la sesion esta actuando-como cliente y existe una
        // serie con expedited_by_company_id = asesoria real, esa es la
        // serie que el backend usara al validar. La mostramos en el
        // badge "PROXIMO Nº AL VALIDAR" para que el numero del banner
        // coincida con el que se asignara.
        String myRealAdvisoryId = com.benjagest.ui.service.AuthSession.get().activeCompanyId();
        boolean actingAsClient = com.benjagest.ui.service.AuthSession.get().isActingForClient();
        SeriesEntry tpbSeries = actingAsClient && myRealAdvisoryId != null
                ? bundle.series().stream()
                        .filter(s -> "STANDARD".equals(s.invoiceKind()))
                        .filter(s -> myRealAdvisoryId.equals(s.expeditedByCompanyId()))
                        .findFirst()
                        .orElse(null)
                : null;
        SeriesEntry standardSeries = tpbSeries != null
                ? tpbSeries
                : bundle.series().stream()
                        .filter(s -> "STANDARD".equals(s.invoiceKind()))
                        .filter(s -> s.expeditedByCompanyId() == null
                                || s.expeditedByCompanyId().isBlank())
                        .findFirst()
                        .orElse(null);

        editorInvoiceDate = new javafx.scene.control.DatePicker(
                bundle.existing() == null || bundle.existing().invoiceDate() == null || bundle.existing().invoiceDate().isBlank()
                        ? LocalDate.now()
                        : LocalDate.parse(bundle.existing().invoiceDate()));
        editorInvoiceDate.getStyleClass().add("invoice-input");
        editorInvoiceDate.setMaxWidth(Double.MAX_VALUE);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(editorInvoiceDate);

        editorDueDate = new javafx.scene.control.DatePicker(
                bundle.existing() == null || bundle.existing().dueDate() == null || bundle.existing().dueDate().isBlank()
                        ? LocalDate.now().plusDays(30)
                        : LocalDate.parse(bundle.existing().dueDate()));
        editorDueDate.getStyleClass().add("invoice-input");
        editorDueDate.setMaxWidth(Double.MAX_VALUE);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(editorDueDate);

        editorNotesArea = new javafx.scene.control.TextArea();
        editorNotesArea.setPromptText(t("editor.notes.prompt"));
        editorNotesArea.setPrefRowCount(5);
        editorNotesArea.setWrapText(true);
        editorNotesArea.getStyleClass().add("invoice-input");

        // ----- Card 1: Cabecera -----
        // Detalle del cliente seleccionado (NIF + email + telefono).
        VBox clientDetail = new VBox(3);
        clientDetail.getStyleClass().add("invoice-client-detail");
        Runnable refreshClientDetail = () -> {
            clientDetail.getChildren().clear();
            CustomerSummary c = editorCustomerCombo.getValue();
            if (c == null) {
                clientDetail.setVisible(false);
                clientDetail.setManaged(false);
                return;
            }
            clientDetail.setVisible(true);
            clientDetail.setManaged(true);
            Label datos = label(t("editor.client.detail_title"), "invoice-detail-title");
            Label nif = new Label(t("editor.client.tax_id_prefix")
                    + (c.taxIdentifier() == null || c.taxIdentifier().isBlank() ? "—" : c.taxIdentifier()));
            nif.getStyleClass().add("invoice-detail-line");
            clientDetail.getChildren().addAll(datos, nif);
            // Tarde 2026-06-10: pintar también dirección postal si está
            // rellena. Antes el editor solo mostraba NIF/email/teléfono
            // aunque el cliente ya tuviera dirección en BD → el bloque
            // del PDF salía completo pero el "preview" en el editor no.
            StringBuilder addrBuf = new StringBuilder();
            for (String part : new String[]{c.address(), c.postalCode(),
                                             c.city(), c.province()}) {
                if (part != null && !part.isBlank()) {
                    if (addrBuf.length() > 0) addrBuf.append(", ");
                    addrBuf.append(part);
                }
            }
            String fullAddress = addrBuf.toString();
            if (!fullAddress.isBlank()) {
                Label addr = new Label(fullAddress);
                addr.getStyleClass().add("invoice-detail-line");
                addr.setWrapText(true);
                clientDetail.getChildren().add(addr);
            }
            if (c.country() != null && !c.country().isBlank()
                    && !"España".equalsIgnoreCase(c.country().trim())) {
                Label cou = new Label(c.country());
                cou.getStyleClass().add("invoice-detail-line");
                clientDetail.getChildren().add(cou);
            }
            if (c.email() != null && !c.email().isBlank()) {
                Label em = new Label(t("editor.client.email_prefix") + c.email());
                em.getStyleClass().add("invoice-detail-line");
                clientDetail.getChildren().add(em);
            }
            if (c.phone() != null && !c.phone().isBlank()) {
                Label tel = new Label(t("editor.client.phone_prefix") + c.phone());
                tel.getStyleClass().add("invoice-detail-line");
                clientDetail.getChildren().add(tel);
            }
        };
        editorCustomerCombo.valueProperty().addListener((obs, oldV, newV) -> {
            // TIPO-IVA-CLIENTE: al elegir cliente, las líneas adoptan su IVA/retención.
            // Solo se "voltean" las líneas no tocadas y solo en factura NUEVA (editar
            // una factura existente no debe alterar sus tipos ya guardados).
            applyCustomerVatDefaults(newV, existingId == null);
            refreshClientDetail.run();
        });
        refreshClientDetail.run();

        // Tipo de factura. Si es RECTIFYING (borrador creado via "Anular"),
        // mostramos pill informativa (no editable — el tipo es consecuencia
        // del acto legal y no se puede cambiar). Si es NORMAL/PROFORMA,
        // mostramos un ComboBox para que el usuario elija. La serie la
        // resuelve el server por invoice_type; el usuario no elige serie.
        boolean isRectifying = bundle.existing() != null
                && "RECTIFYING".equals(bundle.existing().invoiceType());

        Node kindControl;
        if (isRectifying) {
            kindControl = label(
                    t("editor.rectifying.pill_prefix") + shortId(bundle.existing().originalInvoiceId()),
                    "invoice-pill");
            editorInvoiceTypeCombo = null;
        } else {
            editorInvoiceTypeCombo = new ComboBox<>();
            // RECT-F2: SIMPLIFIED = factura simplificada (sin identificar
            // cliente, tope legal 400 EUR — lo valida el backend).
            editorInvoiceTypeCombo.getItems().addAll("NORMAL", "SIMPLIFIED", "PROFORMA");
            // Texto traducido en items y selected; valor interno = código.
            editorInvoiceTypeCombo.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
                @Override protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? "" : localizedInvoiceTypeLabel(item));
                }
            });
            editorInvoiceTypeCombo.setButtonCell(new javafx.scene.control.ListCell<>() {
                @Override protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? "" : localizedInvoiceTypeLabel(item));
                }
            });
            String currentType = bundle.existing() == null
                    ? "NORMAL" : (bundle.existing().invoiceType() == null
                            ? "NORMAL" : bundle.existing().invoiceType());
            if (!editorInvoiceTypeCombo.getItems().contains(currentType)) {
                currentType = "NORMAL";
            }
            editorInvoiceTypeCombo.getSelectionModel().select(currentType);
            editorInvoiceTypeCombo.getStyleClass().add("invoice-input");
            // Al cambiar el tipo, actualizamos el badge del header con
            // el próximo número de la serie correspondiente — feedback
            // inmediato de qué pasaría al validar.
            final var finalSeries = bundle.series();
            editorInvoiceTypeCombo.valueProperty().addListener((obs, oldV, newV) -> {
                if (newV == null) return;
                SeriesEntry seriesForType = pickSeriesForInvoiceType(newV, finalSeries, standardSeries);
                nextNumberBadgeValue.setText(seriesForType == null ? "—" : previewNextNumber(seriesForType));
                // Re-preguntar al backend la serie real para este kind
                // (mantiene el badge sincronizado con la logica TPB del
                // server cuando el usuario cambia NORMAL/PROFORMA).
                Task<SeriesEntry> retask = new Task<>() {
                    @Override
                    protected SeriesEntry call() throws Exception {
                        return billingApiClient.previewNextSeries(newV);
                    }
                };
                retask.setOnSucceeded(e -> {
                    SeriesEntry s2 = retask.getValue();
                    if (s2 != null) nextNumberBadgeValue.setText(previewNextNumber(s2));
                });
                start(retask, "editor-preview-next-type");
            });
            kindControl = editorInvoiceTypeCombo;
        }

        // Badge del header con el próximo número de la serie que tocará
        // al validar: STANDARD para NORMAL, PROFORMA para Proforma, RECT
        // para rectificativas.
        SeriesEntry badgeSeries = isRectifying
                ? bundle.series().stream()
                        .filter(s -> "RECTIFYING".equals(s.invoiceKind()))
                        .findFirst()
                        .orElse(standardSeries)
                : pickSeriesForInvoiceType(
                        editorInvoiceTypeCombo == null ? "NORMAL" : editorInvoiceTypeCombo.getValue(),
                        bundle.series(), standardSeries);
        if (badgeSeries != null) {
            nextNumberBadgeValue.setText(previewNextNumber(badgeSeries));
        } else {
            nextNumberBadgeValue.setText("—");
        }
        // TPB-2: en lugar de adivinar la serie del bundle, preguntamos
        // al backend la serie real que usaria al validar (aplica logica
        // TPB cuando la asesoria actua-como cliente). Asi el badge no
        // depende de filtros en el frontend que pueden no detectar la
        // serie TPB. Asincrono — al volver, machaca el badge.
        Task<SeriesEntry> previewTask = new Task<>() {
            @Override
            protected SeriesEntry call() throws Exception {
                String kind = isRectifying ? "RECTIFYING"
                        : (editorInvoiceTypeCombo == null
                            ? "NORMAL" : editorInvoiceTypeCombo.getValue());
                return billingApiClient.previewNextSeries(kind);
            }
        };
        previewTask.setOnSucceeded(ev -> {
            SeriesEntry s = previewTask.getValue();
            if (s != null) {
                nextNumberBadgeValue.setText(previewNextNumber(s));
            }
        });
        previewTask.setOnFailed(ev -> { /* fallback al badge calculado arriba */ });
        start(previewTask, "editor-preview-next");

        VBox colCliente = new VBox(8,
                label(t("editor.field.customer"), "invoice-field-label"),
                editorCustomerCombo,
                clientDetail
        );
        // Checkbox "Sin vencimiento" — para facturas que se cobran al
        // contado (sin fecha de pago aplazado). Al marcarse, deshabilita
        // el DatePicker y al guardar enviamos dueDate=null al backend.
        CheckBox noDueDateChk = new CheckBox(t("editor.field.due_date.none"));
        // Por defecto, las facturas NUEVAS nacen "sin vencimiento" (cobradas al
        // contado): así no arrastran el texto legal de vencimiento en el PDF
        // salvo que el usuario marque expresamente una fecha. Al editar una
        // existente, se respeta lo guardado (marcado si no tenía vencimiento).
        noDueDateChk.setSelected(bundle.existing() == null
                || bundle.existing().dueDate() == null
                || bundle.existing().dueDate().isBlank());
        editorDueDate.setDisable(noDueDateChk.isSelected());
        noDueDateChk.selectedProperty().addListener((obs, oldV, newV) -> {
            editorDueDate.setDisable(Boolean.TRUE.equals(newV));
            if (Boolean.TRUE.equals(newV)) editorDueDate.setValue(null);
        });
        // Lo guardamos como campo para que el flujo de "save" lo lea.
        editorNoDueDateChk = noDueDateChk;

        VBox colFechas = new VBox(8,
                label(t("editor.field.invoice_date"), "invoice-field-label"),
                editorInvoiceDate,
                label(t("editor.field.due_date"), "invoice-field-label"),
                editorDueDate,
                noDueDateChk
        );
        VBox colTipo = new VBox(8,
                label(t("editor.field.kind"), "invoice-field-label"),
                kindControl
        );
        HBox.setHgrow(colCliente, Priority.ALWAYS);
        HBox.setHgrow(colFechas, Priority.ALWAYS);
        HBox.setHgrow(colTipo, Priority.ALWAYS);
        colCliente.setMinWidth(0);
        colFechas.setMinWidth(0);
        colTipo.setMinWidth(0);
        HBox cabeceraGrid = new HBox(20, colCliente, colFechas, colTipo);
        Node cabeceraCard = invoiceCard(t("editor.card.header"), "fas-info-circle", cabeceraGrid);

        // ----- Card 2: Lineas -----
        editorLinesTable = buildEditorLinesTable(bundle.existingLines());
        // Para una factura nueva, arrancamos con una linea vacia: evita la
        // sensacion de "pantalla rota" sin filas y replica el patron de
        // CONTENDO al abrir Nueva Factura.
        if (existingId == null && editorLinesTable.getItems().isEmpty()) {
            // TIPO-IVA-CLIENTE: la línea inicial nace con el IVA/retención del
            // cliente preseleccionado (selectFirst de arriba, anterior al listener).
            CustomerSummary preCust = editorCustomerCombo.getValue();
            if (preCust != null) {
                editorDefaultVat = preCust.defaultVatPercent() != null
                        ? preCust.defaultVatPercent() : new java.math.BigDecimal("21");
                editorDefaultRetention = preCust.defaultRetentionPercent() != null
                        ? preCust.defaultRetentionPercent() : java.math.BigDecimal.ZERO;
            }
            editorLinesTable.getItems().add(newEditorLine());
        }

        Button addLine = new Button(t("editor.line.add"));
        addLine.setGraphic(icon("fas-plus"));
        addLine.getStyleClass().add("invoice-primary-action");
        addLine.setOnAction(event -> {
            editorLinesTable.getItems().add(newEditorLine());
            recomputeEditorTotals();
        });

        Button removeLine = new Button(t("editor.line.remove"));
        removeLine.setGraphic(icon("fas-trash-alt"));
        removeLine.setOnAction(event -> {
            InvoiceLineDraft sel = editorLinesTable.getSelectionModel().getSelectedItem();
            if (sel != null) {
                editorLinesTable.getItems().remove(sel);
                recomputeEditorTotals();
            }
        });

        // TRB — Importar trabajos pendientes del cliente seleccionado como líneas.
        Button importWorks = new Button(t("editor.line.import_works"));
        importWorks.setGraphic(icon("fas-briefcase"));
        importWorks.getStyleClass().add("button-secondary");
        importWorks.setOnAction(event -> {
            CustomerSummary cust = editorCustomerCombo.getValue();
            if (cust == null) { showError(t("editor.error.no_customer.title"), t("editor.error.no_customer.body")); return; }
            showImportWorkLogsToInvoice(cust);
        });

        // CONC-2 — Elegir un concepto ya usado en facturas anteriores o guardado
        // en el catálogo, en vez de reescribirlo a mano cada vez.
        Button pickConcept = new Button(t("editor.line.concepts"));
        pickConcept.setGraphic(icon("fas-list-ul"));
        pickConcept.getStyleClass().add("button-secondary");
        pickConcept.setOnAction(event -> showConceptPicker());

        // CONC-2 — El camino inverso: la línea que acabo de escribir la guardo
        // como concepto para reutilizarla tal cual en las próximas facturas.
        Button saveConcept = new Button(t("editor.line.save_concept"));
        saveConcept.setGraphic(icon("fas-bookmark"));
        saveConcept.getStyleClass().add("button-secondary");
        saveConcept.setOnAction(event -> saveSelectedLineAsConcept());

        // FlowPane en vez de HBox: con 5 botones, en una ventana estrecha un HBox
        // los encogería y truncaría el texto con "..." (el bug de "botones
        // cortados" del 17-jul). NO se usa la clase .settings-actions a
        // propósito: encogería los botones que ya había.
        //
        // OJO con prefWrapLength (fallo cazado en el smoke de la UI): con
        // Double.MAX_VALUE el ancho PREFERIDO del FlowPane se vuelve enorme, la
        // HBox de la cabecera no puede dárselo y lo encoge hasta su MÍNIMO (el
        // botón más ancho) => los 5 botones salían apilados en vertical y el
        // título de la tarjeta truncado. Con un ancho holgado y finito caben en
        // UNA fila cuando hay sitio, y solo envuelven cuando de verdad no cabe.
        javafx.scene.layout.FlowPane lineasActions = new javafx.scene.layout.FlowPane(
                8, 8, importWorks, pickConcept, saveConcept, addLine, removeLine);
        lineasActions.setPrefWrapLength(1200);
        for (Node b : lineasActions.getChildren()) {
            if (b instanceof Region r) r.setMinWidth(Region.USE_PREF_SIZE);
        }
        lineasActions.setAlignment(Pos.CENTER_RIGHT);
        Node lineasCard = invoiceCardWithActions(t("editor.card.lines"), "fas-calculator",
                lineasActions, editorLinesTable);

        // ----- Card 3: Totales y observaciones -----
        editorSubtotalLabel = new Label("0,00 €");
        editorVatLabel = new Label("0,00 €");
        editorRetentionLabel = new Label("0,00 €");
        editorTotalLabel = new Label("0,00 €");
        editorSubtotalLabel.getStyleClass().add("invoice-totals-value");
        editorVatLabel.getStyleClass().add("invoice-totals-value");
        editorRetentionLabel.getStyleClass().add("invoice-totals-retention");
        editorTotalLabel.getStyleClass().add("invoice-total-big");

        VBox totalsRows = new VBox(2,
                invoiceTotalsRow(t("editor.total.subtotal"), editorSubtotalLabel),
                invoiceTotalsRow(t("editor.total.vat"), editorVatLabel),
                invoiceTotalsRow(t("editor.total.retention"), editorRetentionLabel)
        );

        Label totalLabel = label(t("editor.total.total"), "invoice-total-label");
        Region totalSpacer = new Region();
        HBox.setHgrow(totalSpacer, Priority.ALWAYS);
        HBox totalBigBox = new HBox(12, totalLabel, totalSpacer, editorTotalLabel);
        totalBigBox.setAlignment(Pos.CENTER_LEFT);
        totalBigBox.getStyleClass().add("invoice-total-card");

        VBox rightTotals = new VBox(14, totalsRows, totalBigBox);
        rightTotals.setMinWidth(320);
        rightTotals.setMaxWidth(420);

        VBox notesCol = new VBox(8,
                label(t("editor.notes.label"), "invoice-field-label"),
                editorNotesArea
        );
        HBox.setHgrow(notesCol, Priority.ALWAYS);
        notesCol.setMinWidth(0);
        VBox.setVgrow(editorNotesArea, Priority.ALWAYS);

        HBox totalesGrid = new HBox(24, notesCol, rightTotals);
        Node totalesCard = invoiceCard(t("editor.card.totals"), "fas-euro-sign", totalesGrid);

        recomputeEditorTotals();

        // ----- Footer bar -----
        Button cancel = new Button(t("editor.action.cancel"));
        cancel.setOnAction(event -> host.onEditorClose());

        Button saveDraft = new Button(existingId == null ? t("editor.action.save_draft") : t("editor.action.save_changes"));
        saveDraft.setGraphic(icon("fas-save"));
        saveDraft.getStyleClass().add("invoice-primary-action");
        saveDraft.setOnAction(event -> persistDraft(existingId, false));

        Button validate = new Button(t("editor.action.validate"));
        validate.setGraphic(icon("fas-check"));
        validate.getStyleClass().add("invoice-validate-action");
        validate.setOnAction(event -> persistDraft(existingId, true));

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footerBar = new HBox(10, cancel, footerSpacer, saveDraft, validate);
        footerBar.setAlignment(Pos.CENTER_LEFT);
        footerBar.getStyleClass().add("invoice-footer-bar");

        content.getChildren().addAll(header, cabeceraCard, lineasCard, totalesCard, footerBar);
        return content;
    }

    // ----- helpers del editor de facturas -----

    /**
     * Card blanco con cabecera (icono + titulo) y cuerpo. Se usa para las
     * tres secciones del editor de facturas (Cabecera / Lineas / Totales).
     */
    private VBox invoiceCard(String titleText, String iconLiteral, Node body) {
        return invoiceCardWithActions(titleText, iconLiteral, null, body);
    }

    /**
     * Variante del card con una zona de acciones a la derecha del titulo
     * (p.ej. boton "Anadir linea" en la card de Lineas).
     */
    private VBox invoiceCardWithActions(String titleText, String iconLiteral, Node actions, Node body) {
        StackPane iconBox = iconBubble(iconLiteral, "invoice-card-icon");
        Label titleLabel = label(titleText, "invoice-card-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox titleBar = new HBox(10, iconBox, titleLabel, spacer);
        if (actions != null) {
            titleBar.getChildren().add(actions);
        }
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.getStyleClass().add("invoice-card-header");

        VBox bodyBox = new VBox(body);
        bodyBox.getStyleClass().add("invoice-card-body");

        VBox card = new VBox(titleBar, bodyBox);
        card.getStyleClass().add("invoice-card");
        return card;
    }

    private HBox invoiceTotalsRow(String labelText, Label valueLabel) {
        Label l = label(labelText, "invoice-totals-row-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, l, spacer, valueLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("invoice-totals-row");
        return row;
    }

    private void configureCustomerCombo(ComboBox<CustomerSummary> combo) {
        combo.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(CustomerSummary item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.legalName() + " — " + item.taxIdentifier());
            }
        });
        combo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(CustomerSummary item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.legalName());
            }
        });
    }

    /** Etiqueta humana de un invoice_type (NORMAL/PROFORMA) traducida. */
    private String localizedInvoiceTypeLabel(String code) {
        if (code == null) return "";
        return switch (code) {
            case "NORMAL" -> t("editor.kind.normal");
            case "SIMPLIFIED" -> t("editor.kind.simplified");
            case "PROFORMA" -> t("editor.kind.proforma");
            case "RECTIFYING" -> t("editor.kind.rectifying");
            default -> code;
        };
    }

    /**
     * Resuelve qué serie del catálogo aplica para un invoiceType dado.
     * NORMAL → STANDARD, PROFORMA → PROFORMA (sub-módulo del catálogo
     * sembrado en V16), RECTIFYING → RECTIFYING. Si no encuentra la
     * específica, cae a la STANDARD para que el badge nunca se quede
     * mudo (el server al validar fallaría limpiamente igualmente).
     */
    private SeriesEntry pickSeriesForInvoiceType(String invoiceType,
                                                  List<SeriesEntry> all,
                                                  SeriesEntry fallback) {
        if (invoiceType == null || all == null) return fallback;
        String targetKind = switch (invoiceType) {
            case "PROFORMA" -> "PROFORMA";
            case "RECTIFYING" -> "RECTIFYING";
            default -> "STANDARD";
        };
        return all.stream()
                .filter(s -> targetKind.equals(s.invoiceKind()))
                .findFirst()
                .orElse(fallback);
    }

    private String previewNextNumber(SeriesEntry s) {
        if (s == null) {
            return "—";
        }
        String tpl = s.formatTemplate();
        if (tpl == null || tpl.isBlank()) {
            tpl = "{CODE}-{YYYY}-{0000}";
        }
        String result = tpl
                .replace("{CODE}", s.code() == null ? "" : s.code())
                .replace("{YYYY}", String.valueOf(LocalDate.now().getYear()));
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\{(0+)\\}").matcher(result);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            int width = matcher.group(1).length();
            String padded = String.format("%0" + width + "d", s.nextNumber());
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(padded));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private TableView<InvoiceLineDraft> buildEditorLinesTable(List<InvoiceLineDraft> initial) {
        TableView<InvoiceLineDraft> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Sin lineas. Pulsa 'Anadir linea' para empezar."));

        TableColumn<InvoiceLineDraft, String> colDesc = liveTextColumn(t("editor.lines.col.description"),
                InvoiceLineDraft::getDescription, InvoiceLineDraft::setDescription);
        // CONC-3: la descripción es lo que más se lee de la línea; se le da más
        // ancho para que quepa el concepto sin depender del tooltip.
        colDesc.setPrefWidth(360);

        TableColumn<InvoiceLineDraft, String> colQty = decimalColumn(t("editor.lines.col.qty"), InvoiceLineDraft::getQuantity, InvoiceLineDraft::setQuantity);
        TableColumn<InvoiceLineDraft, String> colPrice = decimalColumn(t("editor.lines.col.price"), InvoiceLineDraft::getUnitPrice, InvoiceLineDraft::setUnitPrice);
        // FAC-IVA (2026-07-10): el IVA de la línea deja de ser texto libre —
        // combo con los tipos CONFIGURADOS (Configuración → Facturación).
        // Con dos tipos al mismo % (dos 0% con conceptos legales distintos),
        // la línea guarda QUÉ tipo se eligió (vatRateId) y el PDF imprime el
        // texto legal de ESE tipo, no el genérico.
        TableColumn<InvoiceLineDraft, String> colVat = vatComboColumn(t("editor.lines.col.vat"));
        // FAC-IVA: la retención también con combo del catálogo (kind
        // WITHHOLDING). Solo fija el %, no necesita identidad (la retención
        // no imprime texto legal por tipo en el PDF).
        TableColumn<InvoiceLineDraft, String> colRet = retentionComboColumn(t("editor.lines.col.retention"));

        // Limpiamos los mapas al construir la tabla — la instancia anterior
        // ya no existe y sus labels son basura.
        rowSubtotalLabels.clear();
        rowLineTotalLabels.clear();

        TableColumn<InvoiceLineDraft, String> colSubtotal = computedColumn(t("editor.lines.col.subtotal"),
                line -> money(lineSubtotal(line).toPlainString()),
                rowSubtotalLabels);
        TableColumn<InvoiceLineDraft, String> colLineTotal = computedColumn(t("editor.lines.col.total"),
                line -> money(lineTotal(line).toPlainString()),
                rowLineTotalLabels);

        table.getColumns().addAll(java.util.List.of(colDesc, colQty, colPrice, colVat, colRet, colSubtotal, colLineTotal));
        table.setItems(FXCollections.observableArrayList(initial));
        table.setPrefHeight(280);
        // FAC-IVA: cargar el catálogo de tipos de IVA (kind VAT, activos)
        // para el combo de la columna. Un refresh() re-pinta los combos
        // cuando llega; si falla, la columna queda sin opciones y el % de
        // las líneas existentes se conserva igualmente.
        new Thread(() -> {
            try {
                var all = billingApiClient.listVatRates(false);
                var vats = all.stream().filter(r -> "VAT".equals(r.kind())).toList();
                var rets = all.stream().filter(r -> "WITHHOLDING".equals(r.kind())).toList();
                Platform.runLater(() -> {
                    editorVatRates = vats;
                    editorRetentionRates = rets;
                    table.refresh();
                });
            } catch (Exception ignored) { /* combo vacío, sin romper el editor */ }
        }, "invoice-editor-vat-rates").start();
        return table;
    }

    /**
     * Columna calculada (no editable) cuya celda lleva un Label cuyo
     * texto se actualiza desde fuera: cada vez que la celda se bindea a
     * una fila, registramos el Label en el mapa con la fila como clave.
     * El listener de las celdas editables (decimalColumn) lee el mapa y
     * llama directamente a label.setText sin pasar por refresh() — asi
     * no perdemos el foco del TextField que esta editando.
     */
    private TableColumn<InvoiceLineDraft, String> computedColumn(String header,
                                                                  java.util.function.Function<InvoiceLineDraft, String> compute,
                                                                  java.util.Map<InvoiceLineDraft, Label> registry) {
        TableColumn<InvoiceLineDraft, String> col = new TableColumn<>(header);
        col.setCellValueFactory(c -> new SimpleStringProperty(compute.apply(c.getValue())));
        col.setEditable(false);
        col.setCellFactory(cv -> new javafx.scene.control.TableCell<InvoiceLineDraft, String>() {
            private final Label label = new Label();
            private InvoiceLineDraft boundRow;
            {
                label.getStyleClass().add("invoice-line-computed");
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    setText(null);
                    if (boundRow != null) {
                        registry.remove(boundRow, label);
                        boundRow = null;
                    }
                    return;
                }
                InvoiceLineDraft row = getTableRow().getItem();
                if (row != boundRow) {
                    if (boundRow != null) registry.remove(boundRow, label);
                    boundRow = row;
                    registry.put(row, label);
                }
                // Siempre recalculamos al pintar (cuando el rebind ocurre o
                // cuando la fila aparece por primera vez). Las actualizaciones
                // posteriores las hace el listener via el mapa.
                label.setText(compute.apply(row));
                setGraphic(label);
            }
        });
        return col;
    }

    /** FAC-IVA: catálogo de tipos de IVA activos (se carga al abrir el editor). */
    private java.util.List<com.benjagest.ui.model.VatRateEntry> editorVatRates = java.util.List.of();
    /** FAC-IVA: catálogo de retenciones (kind WITHHOLDING) activas. */
    private java.util.List<com.benjagest.ui.model.VatRateEntry> editorRetentionRates = java.util.List.of();

    /**
     * Opción sintética "0% · Sin retención": el catálogo seed solo trae
     * 15%/7% — sin esta entrada no se podría volver a 0 desde el combo.
     */
    private com.benjagest.ui.model.VatRateEntry noRetentionOption() {
        return new com.benjagest.ui.model.VatRateEntry(
                "", "WITHHOLDING", "NONE", t("editor.lines.retention.none"),
                java.math.BigDecimal.ZERO, false, true, null);
    }

    /**
     * FAC-IVA — columna de IVA con COMBO de los tipos configurados en
     * Configuración → Facturación (kind VAT, activos). Al elegir un tipo la
     * línea guarda su % Y su identidad (vatRateId) para que el PDF imprima
     * el texto legal del tipo elegido. Las líneas históricas sin tipo se
     * preseleccionan por % (primer tipo que coincida) sin mutar el modelo.
     */
    private TableColumn<InvoiceLineDraft, String> vatComboColumn(String header) {
        TableColumn<InvoiceLineDraft, String> col = new TableColumn<>(header);
        col.setCellValueFactory(c -> new SimpleStringProperty(
                formatDecimalForCell(c.getValue().getVatPercent())));
        col.setCellFactory(cv -> new javafx.scene.control.TableCell<InvoiceLineDraft, String>() {
            private final ComboBox<com.benjagest.ui.model.VatRateEntry> combo = new ComboBox<>();
            private boolean syncing = false;
            {
                combo.setMaxWidth(Double.MAX_VALUE);
                combo.setConverter(new javafx.util.StringConverter<>() {
                    @Override public String toString(com.benjagest.ui.model.VatRateEntry e) {
                        if (e == null) return "";
                        String pct = formatDecimalForCell(e.percent());
                        return e.label() == null || e.label().isBlank()
                                ? pct + "%" : pct + "% · " + e.label();
                    }
                    @Override public com.benjagest.ui.model.VatRateEntry fromString(String s) { return null; }
                });
                combo.setOnAction(ev -> {
                    if (syncing) return;
                    if (getTableRow() == null) return;
                    InvoiceLineDraft row = getTableRow().getItem();
                    com.benjagest.ui.model.VatRateEntry sel = combo.getValue();
                    if (row == null || sel == null) return;
                    row.setVatPercent(sel.percent());
                    row.setVatRateId(sel.id());
                    recomputeEditorTotals();
                    Label subLabel = rowSubtotalLabels.get(row);
                    if (subLabel != null) subLabel.setText(money(lineSubtotal(row).toPlainString()));
                    Label totLabel = rowLineTotalLabels.get(row);
                    if (totLabel != null) totLabel.setText(money(lineTotal(row).toPlainString()));
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                InvoiceLineDraft row = getTableRow().getItem();
                syncing = true;
                try {
                    combo.getItems().setAll(editorVatRates);
                    com.benjagest.ui.model.VatRateEntry match = null;
                    for (var e : editorVatRates) {
                        if (row.getVatRateId() != null && row.getVatRateId().equals(e.id())) {
                            match = e;
                            break;
                        }
                    }
                    if (match == null && row.getVatPercent() != null) {
                        for (var e : editorVatRates) {
                            if (e.percent() != null
                                    && e.percent().compareTo(row.getVatPercent()) == 0) {
                                match = e;
                                break;
                            }
                        }
                    }
                    combo.setValue(match);
                } finally {
                    syncing = false;
                }
                setGraphic(combo);
            }
        });
        return col;
    }

    /**
     * FAC-IVA — columna de RETENCIÓN con combo del catálogo (WITHHOLDING).
     * Fija solo el % de la línea; preselección por % coincidente.
     */
    private TableColumn<InvoiceLineDraft, String> retentionComboColumn(String header) {
        TableColumn<InvoiceLineDraft, String> col = new TableColumn<>(header);
        col.setCellValueFactory(c -> new SimpleStringProperty(
                formatDecimalForCell(c.getValue().getRetentionPercent())));
        col.setCellFactory(cv -> new javafx.scene.control.TableCell<InvoiceLineDraft, String>() {
            private final ComboBox<com.benjagest.ui.model.VatRateEntry> combo = new ComboBox<>();
            private boolean syncing = false;
            {
                combo.setMaxWidth(Double.MAX_VALUE);
                combo.setConverter(new javafx.util.StringConverter<>() {
                    @Override public String toString(com.benjagest.ui.model.VatRateEntry e) {
                        if (e == null) return "";
                        String pct = formatDecimalForCell(e.percent());
                        return e.label() == null || e.label().isBlank()
                                ? pct + "%" : pct + "% · " + e.label();
                    }
                    @Override public com.benjagest.ui.model.VatRateEntry fromString(String s) { return null; }
                });
                combo.setOnAction(ev -> {
                    if (syncing) return;
                    if (getTableRow() == null) return;
                    InvoiceLineDraft row = getTableRow().getItem();
                    com.benjagest.ui.model.VatRateEntry sel = combo.getValue();
                    if (row == null || sel == null) return;
                    row.setRetentionPercent(sel.percent());
                    recomputeEditorTotals();
                    Label subLabel = rowSubtotalLabels.get(row);
                    if (subLabel != null) subLabel.setText(money(lineSubtotal(row).toPlainString()));
                    Label totLabel = rowLineTotalLabels.get(row);
                    if (totLabel != null) totLabel.setText(money(lineTotal(row).toPlainString()));
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                InvoiceLineDraft row = getTableRow().getItem();
                syncing = true;
                try {
                    java.util.List<com.benjagest.ui.model.VatRateEntry> options =
                            new java.util.ArrayList<>();
                    options.add(noRetentionOption());
                    options.addAll(editorRetentionRates);
                    combo.getItems().setAll(options);
                    com.benjagest.ui.model.VatRateEntry match = null;
                    if (row.getRetentionPercent() != null) {
                        for (var e : options) {
                            if (e.percent() != null
                                    && e.percent().compareTo(row.getRetentionPercent()) == 0) {
                                match = e;
                                break;
                            }
                        }
                    }
                    combo.setValue(match);
                } finally {
                    syncing = false;
                }
                setGraphic(combo);
            }
        });
        return col;
    }

    private TableColumn<InvoiceLineDraft, String> decimalColumn(String header,
                                                                java.util.function.Function<InvoiceLineDraft, java.math.BigDecimal> getter,
                                                                java.util.function.BiConsumer<InvoiceLineDraft, java.math.BigDecimal> setter) {
        TableColumn<InvoiceLineDraft, String> col = new TableColumn<>(header);
        // Mostramos sin ceros sobrantes: BigDecimal("1.0000") → "1",
        // BigDecimal("1.50") → "1.5". El modelo sigue siendo BigDecimal
        // (precision intacta), pero la celda se ve limpia. Solo afecta al
        // rebind inicial: mientras el usuario teclea, boundRow protege su
        // texto literal (si escribe "1.50" se queda "1.50" durante la
        // edicion, solo se "limpia" si abandona la fila y vuelve).
        col.setCellValueFactory(c -> new SimpleStringProperty(formatDecimalForCell(getter.apply(c.getValue()))));
        // Celda siempre en modo edicion (TextField visible) con escucha en
        // textProperty: a cada pulsacion parseamos, actualizamos el modelo
        // y recomputeEditorTotals(). Asi los totales y las columnas
        // calculadas (Subtotal, Total) reaccionan en vivo, igual que en
        // CONTENDO. boundRow protege el texto que esta escribiendo el
        // usuario frente a los refresh() de filas hermanas.
        col.setCellFactory(cv -> new javafx.scene.control.TableCell<InvoiceLineDraft, String>() {
            private final TextField field = new TextField();
            private boolean syncing = false;
            private InvoiceLineDraft boundRow;
            {
                field.setMaxWidth(Double.MAX_VALUE);
                field.getStyleClass().add("invoice-line-input");
                field.focusedProperty().addListener((obs, was, isNow) -> {
                    if (isNow) {
                        // Seleccionamos todo al ganar foco para que la
                        // primera tecla reemplace el "0" inicial sin que
                        // el usuario tenga que borrarlo.
                        Platform.runLater(field::selectAll);
                    }
                });
                field.textProperty().addListener((obs, oldV, newV) -> {
                    if (syncing) return;
                    if (getTableRow() == null) return;
                    InvoiceLineDraft row = getTableRow().getItem();
                    if (row == null) return;
                    java.math.BigDecimal value;
                    try {
                        value = newV == null || newV.isBlank()
                                ? java.math.BigDecimal.ZERO
                                : new java.math.BigDecimal(newV.replace(',', '.'));
                    } catch (NumberFormatException ignored) {
                        // Numero invalido mientras teclea (p.ej. "1.")
                        // → no tocamos el modelo, dejamos el texto tal cual.
                        return;
                    }
                    setter.accept(row, value);
                    recomputeEditorTotals();
                    // Actualizamos los Subtotal/Total de ESTA fila via las
                    // referencias guardadas por computedColumn. Asi se ven
                    // en vivo sin tocar editorLinesTable.refresh() (que
                    // pierde el foco del TextField — sintoma "tecleas 1,
                    // el campo se sale"). Si la fila no tiene labels
                    // registrados todavia (cell aun no pintada), la celda
                    // pintara con el valor correcto cuando aparezca.
                    Label subLabel = rowSubtotalLabels.get(row);
                    if (subLabel != null) {
                        subLabel.setText(money(lineSubtotal(row).toPlainString()));
                    }
                    Label totLabel = rowLineTotalLabels.get(row);
                    if (totLabel != null) {
                        totLabel.setText(money(lineTotal(row).toPlainString()));
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    boundRow = null;
                } else {
                    InvoiceLineDraft row = getTableRow().getItem();
                    // Solo sincronizamos el texto cuando la celda se
                    // engancha a una fila distinta (carga inicial o
                    // anadir/quitar lineas). Mientras el usuario tipea
                    // en esta misma fila, NO sobreescribimos su texto
                    // — eso es lo que rompia la calc en tiempo real.
                    if (row != boundRow) {
                        syncing = true;
                        field.setText(item == null ? "" : item);
                        syncing = false;
                        boundRow = row;
                    }
                    setGraphic(field);
                }
            }
        });
        col.setPrefWidth(80);
        col.setEditable(false);
        return col;
    }

    /**
     * Devuelve el BigDecimal sin ceros sobrantes a la derecha y nunca en
     * notacion cientifica. stripTrailingZeros de "100" deja "1E+2" — el
     * setScale(0, UNNECESSARY) o toPlainString lo arregla.
     */
    private String formatDecimalForCell(java.math.BigDecimal val) {
        if (val == null) return "";
        java.math.BigDecimal stripped = val.stripTrailingZeros();
        // Si el resultado tiene exponente positivo (p.ej. 100 → 1E+2),
        // toPlainString sigue dando "100" — lo que queremos.
        // Si scale<0 (p.ej. 100 con scale=-2), toPlainString tambien
        // devuelve "100". Asi que toPlainString es seguro aqui.
        return stripped.scale() < 0
                ? stripped.setScale(0, java.math.RoundingMode.UNNECESSARY).toPlainString()
                : stripped.toPlainString();
    }

    /**
     * Hermano de decimalColumn pero para texto (Descripcion). El cell
     * tambien lleva un TextField siempre visible y commitea en cada
     * pulsacion via textProperty. Antes la descripcion usaba el
     * TextFieldTableCell por defecto, que solo commitea con Enter/Tab —
     * si el usuario pulsaba "Validar y emitir" directamente despues de
     * teclear, la descripcion no llegaba al modelo y el guardado abortaba
     * con "Hay una linea sin descripcion" (que parecia un "faltan campos"
     * espurio).
     */
    private TableColumn<InvoiceLineDraft, String> liveTextColumn(String header,
                                                                  java.util.function.Function<InvoiceLineDraft, String> getter,
                                                                  java.util.function.BiConsumer<InvoiceLineDraft, String> setter) {
        TableColumn<InvoiceLineDraft, String> col = new TableColumn<>(header);
        col.setCellValueFactory(c -> {
            String value = getter.apply(c.getValue());
            return new SimpleStringProperty(value == null ? "" : value);
        });
        col.setCellFactory(cv -> new javafx.scene.control.TableCell<InvoiceLineDraft, String>() {
            /**
             * CONC-3 — TextArea (no TextField) para que un concepto largo se
             * LEA entero, envuelto en varias líneas, en vez de tener que mover
             * el cursor adelante y atrás dentro de una sola línea.
             *
             * <p>Sigue siendo texto de UNA línea a efectos de dato: Enter no
             * inserta salto y el pegado los convierte en espacios (ver
             * {@link #pasteAsSingleLine}). Los saltos dentro de la descripción
             * no aportan nada al PDF y sí dan problemas.
             */
            private final javafx.scene.control.TextArea field = new javafx.scene.control.TextArea() {
                @Override
                public void paste() {
                    pasteAsSingleLine(this);
                }
            };
            private final javafx.scene.control.Tooltip fullText = new javafx.scene.control.Tooltip();
            private boolean syncing = false;
            private InvoiceLineDraft boundRow;
            {
                field.setMaxWidth(Double.MAX_VALUE);
                field.setWrapText(true);
                field.setPrefRowCount(2);
                field.getStyleClass().add("invoice-line-input");
                // Enter en una celda de tabla no debe partir la descripción.
                field.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                    if (e.getCode() == javafx.scene.input.KeyCode.ENTER) e.consume();
                });
                fullText.setWrapText(true);
                fullText.setMaxWidth(520);
                field.textProperty().addListener((obs, oldV, newV) -> {
                    // Aunque el texto sea largo, el tooltip lo enseña completo.
                    fullText.setText(newV == null ? "" : newV);
                    javafx.scene.control.Tooltip.install(field,
                            newV == null || newV.isBlank() ? null : fullText);
                    if (syncing) return;
                    if (getTableRow() == null) return;
                    InvoiceLineDraft row = getTableRow().getItem();
                    if (row == null) return;
                    setter.accept(row, newV);
                });
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    boundRow = null;
                } else {
                    InvoiceLineDraft row = getTableRow().getItem();
                    if (row != boundRow) {
                        syncing = true;
                        field.setText(item == null ? "" : item);
                        syncing = false;
                        boundRow = row;
                    }
                    setGraphic(field);
                }
            }
        });
        col.setEditable(false);
        return col;
    }

    /**
     * CONC-3 — Pega el portapapeles como UNA sola línea.
     *
     * <p>El bug (Benjamin 2026-08-15): copió de un PDF un concepto que ocupaba
     * tres líneas y al pegarlo la última palabra de cada línea quedó pegada a
     * la primera de la siguiente ("…mantenimientoy revisión…"). La causa es de
     * JavaFX: un control de una línea DESCARTA los saltos al pegar, sin poner
     * nada en su lugar. Aquí se sustituyen por un espacio ANTES de insertar.
     */
    private void pasteAsSingleLine(javafx.scene.control.TextInputControl target) {
        javafx.scene.input.Clipboard cb = javafx.scene.input.Clipboard.getSystemClipboard();
        if (!cb.hasString() || cb.getString() == null) {
            return;
        }
        target.replaceSelection(singleLine(cb.getString()));
    }

    /**
     * CONC-3 — Texto de varias líneas a una sola, sin pegar palabras.
     *
     * <p>Un guion al final de línea es una palabra PARTIDA por el ancho de la
     * página ("manteni-\nmiento"): ahí no se mete espacio, o quedaría
     * "manteni- miento". No se le quita el guion: quitarlo acertaría con las
     * palabras partidas pero rompería las que llevan guion de verdad
     * ("pre-\nventa"), y este código no puede distinguirlas.
     */
    static String singleLine(String raw) {
        if (raw == null) return "";
        String joined = raw
                // "palabra-\n" + "resto"  ->  "palabra-resto"
                .replaceAll("-[ \\t]*\\R[ \\t]*", "-")
                // cualquier otro salto (con lo que lo rodee) -> UN espacio
                .replaceAll("[ \\t]*\\R[ \\t]*", " ");
        // Espacios repetidos (típicos al copiar de un PDF) -> uno solo.
        return joined.replaceAll("[ \\t]{2,}", " ").trim();
    }

    private java.math.BigDecimal lineSubtotal(InvoiceLineDraft line) {
        return line.getQuantity().multiply(line.getUnitPrice())
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private java.math.BigDecimal lineVat(InvoiceLineDraft line) {
        return lineSubtotal(line).multiply(line.getVatPercent())
                .divide(java.math.BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
    }

    private java.math.BigDecimal lineRetention(InvoiceLineDraft line) {
        return lineSubtotal(line).multiply(line.getRetentionPercent())
                .divide(java.math.BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
    }

    private java.math.BigDecimal lineTotal(InvoiceLineDraft line) {
        return lineSubtotal(line).add(lineVat(line)).subtract(lineRetention(line));
    }

    /** Línea nueva del editor con el IVA/retención por defecto actual (del cliente). */
    private InvoiceLineDraft newEditorLine() {
        InvoiceLineDraft line = new InvoiceLineDraft();
        line.setVatPercent(editorDefaultVat);
        line.setRetentionPercent(editorDefaultRetention);
        return line;
    }

    /**
     * TIPO-IVA-CLIENTE: ajusta el IVA/retención por defecto del editor al del
     * cliente elegido (si no tiene, 21/0). Si {@code flipUntouchedLines}, las
     * líneas que aún tenían el default anterior (no tocadas a mano) adoptan el
     * nuevo tipo; las que el usuario cambió se respetan.
     */
    private void applyCustomerVatDefaults(CustomerSummary cust, boolean flipUntouchedLines) {
        java.math.BigDecimal oldVat = editorDefaultVat;
        java.math.BigDecimal oldRet = editorDefaultRetention;
        editorDefaultVat = (cust != null && cust.defaultVatPercent() != null)
                ? cust.defaultVatPercent() : new java.math.BigDecimal("21");
        editorDefaultRetention = (cust != null && cust.defaultRetentionPercent() != null)
                ? cust.defaultRetentionPercent() : java.math.BigDecimal.ZERO;
        if (!flipUntouchedLines || editorLinesTable == null) {
            return;
        }
        for (InvoiceLineDraft line : editorLinesTable.getItems()) {
            if (line.getVatPercent().compareTo(oldVat) == 0) {
                line.setVatPercent(editorDefaultVat);
            }
            if (line.getRetentionPercent().compareTo(oldRet) == 0) {
                line.setRetentionPercent(editorDefaultRetention);
            }
        }
        editorLinesTable.refresh();
        recomputeEditorTotals();
    }

    private void recomputeEditorTotals() {
        java.math.BigDecimal subtotal = java.math.BigDecimal.ZERO;
        java.math.BigDecimal vat = java.math.BigDecimal.ZERO;
        java.math.BigDecimal retention = java.math.BigDecimal.ZERO;
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        if (editorLinesTable != null) {
            for (InvoiceLineDraft line : editorLinesTable.getItems()) {
                subtotal = subtotal.add(lineSubtotal(line));
                vat = vat.add(lineVat(line));
                retention = retention.add(lineRetention(line));
                total = total.add(lineTotal(line));
            }
        }
        if (editorSubtotalLabel != null) editorSubtotalLabel.setText(money(subtotal.toPlainString()));
        if (editorVatLabel != null) editorVatLabel.setText(money(vat.toPlainString()));
        if (editorRetentionLabel != null) editorRetentionLabel.setText(money(retention.toPlainString()));
        if (editorTotalLabel != null) editorTotalLabel.setText(money(total.toPlainString()));
    }

    /** FAC-CLIVAL — campos fiscales que le faltan al cliente para emitir una
     *  factura completa (NIF, nombre, dirección postal). Lista vacía = completo. */
    private java.util.List<String> missingInvoiceFields(CustomerSummary c) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (c.taxIdentifier() == null || c.taxIdentifier().isBlank()) missing.add(t("editor.error.customer_incomplete.field.nif"));
        if (c.legalName() == null || c.legalName().isBlank())         missing.add(t("editor.error.customer_incomplete.field.name"));
        if (c.address() == null || c.address().isBlank())             missing.add(t("editor.error.customer_incomplete.field.address"));
        if (c.city() == null || c.city().isBlank())                   missing.add(t("editor.error.customer_incomplete.field.city"));
        if (c.province() == null || c.province().isBlank())           missing.add(t("editor.error.customer_incomplete.field.province"));
        if (c.postalCode() == null || c.postalCode().isBlank())       missing.add(t("editor.error.customer_incomplete.field.postal"));
        return missing;
    }

    /** Aviso "faltan datos del cliente" con opción de completarlos (modal) y
     *  reintentar la emisión automáticamente al guardar. */
    private void promptCompleteCustomer(CustomerSummary customer, String existingId,
                                         boolean validateAfter, java.util.List<String> missing) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(t("editor.error.customer_incomplete.title"));
        alert.setHeaderText(t("editor.error.customer_incomplete.header"));
        alert.setContentText(t("editor.error.customer_incomplete.body")
                + "\n\n• " + String.join("\n• ", missing));
        ButtonType complete = new ButtonType(t("editor.error.customer_incomplete.complete"),
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(t("editor.error.customer_incomplete.cancel"),
                javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(complete, cancel);
        java.util.Optional<ButtonType> res = alert.showAndWait();
        if (res.isPresent() && res.get() == complete) {
            String customerId = customer.id();
            host.openCustomerEditor(customerId,
                    () -> reloadCustomerAndRetry(customerId, existingId, validateAfter));
        }
    }

    /** Tras completar los datos: recarga la lista de clientes, re-selecciona el
     *  mismo cliente y reintenta la emisión (ahora con los datos completos). */
    private void reloadCustomerAndRetry(String customerId, String existingId, boolean validateAfter) {
        Task<List<CustomerSummary>> reload = new Task<>() {
            @Override protected List<CustomerSummary> call() throws Exception {
                return customerApiClient.list();
            }
        };
        reload.setOnSucceeded(e -> {
            List<CustomerSummary> fresh = reload.getValue();
            editorCustomerCombo.getItems().setAll(fresh);
            for (CustomerSummary c : fresh) {
                if (c.id() != null && c.id().equals(customerId)) {
                    editorCustomerCombo.getSelectionModel().select(c);
                    break;
                }
            }
            persistDraft(existingId, validateAfter);
        });
        new Thread(reload, "reload-customer-retry").start();
    }

    private void persistDraft(String existingId, boolean validateAfter) {
        CustomerSummary customer = editorCustomerCombo.getValue();
        // RECT-F2: la simplificada no identifica al destinatario — el
        // cliente es OPCIONAL solo en ese tipo (el backend lo re-valida).
        boolean simplifiedSelected = editorInvoiceTypeCombo != null
                && "SIMPLIFIED".equals(editorInvoiceTypeCombo.getValue());
        if (customer == null && !simplifiedSelected) {
            showError(t("editor.error.no_customer.title"), t("editor.error.no_customer.body"));
            return;
        }
        // FAC-CLIVAL: una factura completa (no simplificada) exige NIF + nombre +
        // dirección del destinatario (art. 6 RD 1619/2012). Si faltan, NO se emite:
        // se avisa y se ofrece completar los datos del cliente en el modal, y al
        // guardar se reintenta la emisión con el cliente ya completo.
        if (customer != null && !simplifiedSelected) {
            java.util.List<String> missing = missingInvoiceFields(customer);
            if (!missing.isEmpty()) {
                promptCompleteCustomer(customer, existingId, validateAfter, missing);
                return;
            }
        }
        if (editorLinesTable.getItems().isEmpty()) {
            showError(t("editor.error.no_lines.title"), t("editor.error.no_lines.body"));
            return;
        }
        for (InvoiceLineDraft line : editorLinesTable.getItems()) {
            if (line.getDescription() == null || line.getDescription().isBlank()) {
                showError(t("editor.error.line_incomplete.title"), t("editor.error.line_incomplete.body"));
                return;
            }
        }

        String invoiceDateIso = editorInvoiceDate.getValue() == null ? null : editorInvoiceDate.getValue().toString();
        // Si el usuario marcó "Sin vencimiento" (factura al contado),
        // mandamos null al backend independientemente de lo que tenga
        // el DatePicker (debería estar disabled, pero defensivo).
        String dueDateIso;
        if (editorNoDueDateChk != null && editorNoDueDateChk.isSelected()) {
            dueDateIso = null;
        } else {
            dueDateIso = editorDueDate.getValue() == null
                    ? null : editorDueDate.getValue().toString();
        }
        String notes = editorNotesArea.getText();
        List<InvoiceLineDraft> lines = new java.util.ArrayList<>(editorLinesTable.getItems());

        // No mandamos seriesId: el server lo resuelve por invoice_type.
        // Mantenemos la firma del cliente API (seriesId nullable) por
        // si en algun flujo futuro queremos forzarlo explicitamente.
        // Tipo elegido por el usuario en el ComboBox. Si está editando
        // una RECTIFYING (combo null por diseño), se preserva el tipo
        // existente — el backend updateDraft también lo preserva como
        // defensa adicional.
        String selectedInvoiceType = editorInvoiceTypeCombo == null
                ? (existingId == null ? "NORMAL" : "RECTIFYING")
                : (editorInvoiceTypeCombo.getValue() == null ? "NORMAL" : editorInvoiceTypeCombo.getValue());
        Task<SalesInvoiceSummary> task = new Task<>() {
            @Override
            protected SalesInvoiceSummary call() throws Exception {
                SalesInvoiceSummary saved;
                String customerIdOrNull = customer == null ? null : customer.id();
                if (existingId == null) {
                    saved = billingApiClient.createInvoice(customerIdOrNull, null, selectedInvoiceType,
                            invoiceDateIso, dueDateIso, notes, lines);
                } else {
                    saved = billingApiClient.updateInvoice(existingId, customerIdOrNull, null, selectedInvoiceType,
                            invoiceDateIso, dueDateIso, notes, lines);
                }
                if (validateAfter) {
                    saved = billingApiClient.validateInvoice(saved.id());
                }
                return saved;
            }
        };
        task.setOnSucceeded(event -> {
            SalesInvoiceSummary result = task.getValue();
            String msg = validateAfter
                    ? t("editor.saved.validated_prefix") + result.invoiceNumber()
                    : t("editor.saved.draft");
            Alert ok = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
            // TRB — si se importaron trabajos pendientes, marcarlos FACTURADOS
            // enlazados a esta factura (best-effort, off-thread).
            if (!editorPendingWorkLogIds.isEmpty()) {
                java.util.List<String> wids = new java.util.ArrayList<>(editorPendingWorkLogIds);
                editorPendingWorkLogIds.clear();
                new Thread(() -> {
                    try { altaApiClient.markWorksBilled(wids, result.id()); }
                    catch (Exception ignored) { /* la factura ya se guardó */ }
                }, "mark-works-billed").start();
            }
            host.onEditorClose();
            // Crear/editar borrador o validar: emite SALES + JOURNAL si
            // se validó (con asiento). El listado de facturación se
            // refresca aunque no estuviera abierto antes.
            com.benjagest.ui.support.RefreshBus.emit(
                    com.benjagest.ui.support.RefreshBus.TOPIC_SALES,
                    com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL);
        });
        task.setOnFailed(event -> showError(
                validateAfter ? t("editor.error.validate_failed.title") : t("editor.error.save_failed.title"),
                t("editor.error.save_failed.body")));
        start(task, "billing-invoice-save" + (validateAfter ? "-validate" : ""));
    }

    // ===================================================================
    //  TRB — Importar trabajos pendientes del cliente como líneas de la
    //  factura abierta. Diálogo interno del editor.
    // ===================================================================
    private void showImportWorkLogsToInvoice(CustomerSummary customer) {
        Stage dlg = new Stage();
        dlg.setTitle(t("editor.import_works.title"));
        dlg.initModality(javafx.stage.Modality.APPLICATION_MODAL);

        TableView<com.benjagest.ui.model.WorkLogEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        table.setPlaceholder(new Label(t("editor.import_works.empty")));
        VBox.setVgrow(table, Priority.ALWAYS);
        TableColumn<com.benjagest.ui.model.WorkLogEntry, String> cD = new TableColumn<>(t("trabajos.col.date"));
        cD.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().logDate()));
        cD.setComparator(ISO_DATE_COMPARATOR); cD.setPrefWidth(100);
        TableColumn<com.benjagest.ui.model.WorkLogEntry, String> cDe = new TableColumn<>(t("trabajos.col.description"));
        cDe.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().description()));
        TableColumn<com.benjagest.ui.model.WorkLogEntry, String> cV = new TableColumn<>(t("trabajos.col.valuation"));
        cV.setCellValueFactory(c -> new SimpleStringProperty(workLogValuationLabel(c.getValue())));
        cV.setPrefWidth(160);
        TableColumn<com.benjagest.ui.model.WorkLogEntry, String> cA = new TableColumn<>(t("trabajos.col.amount"));
        cA.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().billableAmount() == null ? "" : money(c.getValue().billableAmount().toPlainString())));
        cA.setComparator(NUMERIC_STRING_COMPARATOR); cA.setPrefWidth(100);
        table.getColumns().addAll(java.util.List.of(cD, cDe, cV, cA));

        Task<java.util.List<com.benjagest.ui.model.WorkLogEntry>> load = new Task<>() {
            @Override protected java.util.List<com.benjagest.ui.model.WorkLogEntry> call() throws Exception {
                return altaApiClient.listWorkLogs(java.time.LocalDate.now().minusYears(2),
                        java.time.LocalDate.now(), customer.id(), null, true);
            }
        };
        // FAC-MERGE: los trabajos ya importados a esta factura (aún sin guardar) no se
        // vuelven a ofrecer — reabrir el diálogo los duplicaba como líneas nuevas.
        load.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(
                load.getValue().stream().filter(w -> !editorPendingWorkLogIds.contains(w.id())).toList())));
        load.setOnFailed(ev -> showError(t("trabajos.fail.title"),
                load.getException() == null ? "" : com.benjagest.ui.support.BackendErrors.humanize(load.getException().getMessage())));
        start(load, "import-works-load");

        Label hint = new Label(t("editor.import_works.hint").replace("{customer}",
                customer.legalName() == null ? "" : customer.legalName()));
        hint.setWrapText(true); hint.getStyleClass().add("settings-hint");
        javafx.scene.control.ToggleGroup mode = new javafx.scene.control.ToggleGroup();
        javafx.scene.control.RadioButton perLine = new javafx.scene.control.RadioButton(t("trabajos.bill.per_job"));
        perLine.setToggleGroup(mode); perLine.setSelected(true);
        javafx.scene.control.RadioButton merge = new javafx.scene.control.RadioButton(t("trabajos.bill.merge"));
        merge.setToggleGroup(mode);
        TextField mergedConcept = new TextField(t("trabajos.bill.merged_default"));
        mergedConcept.disableProperty().bind(merge.selectedProperty().not());

        Button add = new Button(t("editor.import_works.add"));
        add.setGraphic(icon("fas-plus")); add.getStyleClass().add("button-primary");
        add.setOnAction(e -> {
            var sel = new java.util.ArrayList<>(table.getSelectionModel().getSelectedItems());
            if (sel.isEmpty()) { showError(t("trabajos.fail.title"), t("editor.import_works.none")); return; }
            // FAC-MERGE: la línea vacía con la que arranca una factura nueva se retira al
            // importar (bloqueaba el guardado con "línea sin descripción" y empujaba a
            // reimportar los mismos trabajos).
            editorLinesTable.getItems().removeIf(l ->
                    (l.getDescription() == null || l.getDescription().isBlank())
                            && l.getUnitPrice().signum() == 0);
            if (merge.isSelected()) {
                java.math.BigDecimal sum = sel.stream()
                        .map(w -> w.billableAmount() == null ? java.math.BigDecimal.ZERO : w.billableAmount())
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                String concept = mergedConcept.getText() == null || mergedConcept.getText().isBlank()
                        ? t("trabajos.bill.merged_default") : mergedConcept.getText().trim();
                editorLinesTable.getItems().add(new InvoiceLineDraft(
                        concept, java.math.BigDecimal.ONE, sum, editorDefaultVat, editorDefaultRetention));
            } else {
                for (var w : sel) editorLinesTable.getItems().add(lineFromWork(w));
            }
            for (var w : sel) {
                if (!editorPendingWorkLogIds.contains(w.id())) editorPendingWorkLogIds.add(w.id());
            }
            recomputeEditorTotals();
            dlg.close();
        });
        Button cancel = new Button(t("dialog.cancel"));
        cancel.setOnAction(e -> dlg.close());
        HBox btns = new HBox(10, cancel, add);
        btns.setAlignment(Pos.CENTER_RIGHT);

        // FAC-MERGE: el modo (una línea por trabajo / agrupar) va ARRIBA de la tabla
        // (patrón Slice 3V) — debajo pasaba desapercibido y se importaba con el modo
        // equivocado.
        VBox root = new VBox(12, hint, perLine, merge, mergedConcept, new Separator(), table, btns);
        root.setPadding(new javafx.geometry.Insets(16));
        root.setPrefSize(640, 520);
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/com/benjagest/ui/app.css").toExternalForm());
        dlg.setScene(scene);
        dlg.showAndWait();
    }

    /** Línea de factura a partir de un trabajo (preserva cantidad×precio; cerrado = 1×importe). */
    private InvoiceLineDraft lineFromWork(com.benjagest.ui.model.WorkLogEntry w) {
        String d = (w.description() == null || w.description().isBlank())
                ? t("trabajos.title") : singleLine(w.description()); // CONC-3
        java.math.BigDecimal vat = editorDefaultVat;
        if (w.quantity() != null && w.unitPrice() != null && !"FIXED".equals(w.billingUnit())) {
            return new InvoiceLineDraft(d, w.quantity(), w.unitPrice(), vat, editorDefaultRetention);
        }
        return new InvoiceLineDraft(d, java.math.BigDecimal.ONE,
                w.billableAmount() == null ? java.math.BigDecimal.ZERO : w.billableAmount(),
                vat, editorDefaultRetention);
    }

    // ==================== CONC-2 — conceptos reutilizables ====================

    /**
     * Selector de conceptos: mezcla los GUARDADOS en el catálogo con los ya
     * USADOS en facturas anteriores (el backend los devuelve juntos, marcados
     * con {@code source}). Elegir uno o varios los añade como líneas.
     */
    private void showConceptPicker() {
        Stage dlg = new Stage();
        dlg.setTitle(t("concepts.title"));
        dlg.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        com.benjagest.ui.support.Dialogs.ownAndCenter(dlg);

        TableView<ConceptEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        table.setPlaceholder(new Label(t("concepts.empty")));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<ConceptEntry, String> cName = new TableColumn<>(t("concepts.col.concept"));
        cName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        cName.setPrefWidth(300);
        TableColumn<ConceptEntry, String> cPrice = new TableColumn<>(t("concepts.col.price"));
        cPrice.setCellValueFactory(c -> new SimpleStringProperty(money(c.getValue().unitPrice().toPlainString())));
        cPrice.setComparator(NUMERIC_STRING_COMPARATOR); cPrice.setPrefWidth(100);
        TableColumn<ConceptEntry, String> cVat = new TableColumn<>(t("concepts.col.vat"));
        cVat.setCellValueFactory(c -> new SimpleStringProperty(
                formatDecimalForCell(c.getValue().vatPercent()) + " %"));
        cVat.setPrefWidth(70);
        TableColumn<ConceptEntry, String> cRet = new TableColumn<>(t("concepts.col.retention"));
        cRet.setCellValueFactory(c -> new SimpleStringProperty(
                formatDecimalForCell(c.getValue().retentionPercent()) + " %"));
        cRet.setPrefWidth(70);
        TableColumn<ConceptEntry, String> cUses = new TableColumn<>(t("concepts.col.uses"));
        cUses.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().usageCount())));
        cUses.setComparator(NUMERIC_STRING_COMPARATOR); cUses.setPrefWidth(70);
        TableColumn<ConceptEntry, String> cLast = new TableColumn<>(t("concepts.col.last_used"));
        cLast.setCellValueFactory(c -> new SimpleStringProperty(shortIso(c.getValue().lastUsedAt())));
        cLast.setComparator(ISO_DATE_COMPARATOR); cLast.setPrefWidth(110);
        TableColumn<ConceptEntry, String> cSource = new TableColumn<>(t("concepts.col.source"));
        cSource.setCellValueFactory(c -> new SimpleStringProperty(
                t("concepts.source." + c.getValue().source())));
        cSource.setPrefWidth(100);
        table.getColumns().addAll(java.util.List.of(cName, cPrice, cVat, cRet, cUses, cLast, cSource));

        Label hint = new Label(t("concepts.hint"));
        hint.setWrapText(true); hint.getStyleClass().add("settings-hint");
        // Sin minHeight propio, la VBox del diálogo lo encoge a UNA línea y el
        // texto sale cortado con "..." (visto en el smoke de la UI).
        hint.setMinHeight(Region.USE_PREF_SIZE);
        TextField search = new TextField();
        search.setPromptText(t("concepts.search"));

        java.util.List<ConceptEntry> loaded = new java.util.ArrayList<>();
        Runnable applyFilter = () -> {
            String q = stripDiacritics(search.getText() == null ? "" : search.getText().trim().toLowerCase());
            table.setItems(FXCollections.observableArrayList(loaded.stream()
                    .filter(c -> q.isEmpty() || conceptText(c).contains(q))
                    .toList()));
        };
        search.textProperty().addListener((obs, old, val) -> applyFilter.run());

        Runnable reload = () -> {
            Task<java.util.List<ConceptEntry>> load = new Task<>() {
                @Override protected java.util.List<ConceptEntry> call() throws Exception {
                    return billingApiClient.listConcepts();
                }
            };
            load.setOnSucceeded(ev -> {
                loaded.clear();
                loaded.addAll(load.getValue());
                applyFilter.run();
            });
            load.setOnFailed(ev -> showError(t("concepts.fail.title"),
                    load.getException() == null ? "" : load.getException().getMessage()));
            start(load, "concepts-load");
        };
        reload.run();

        Button newConcept = new Button(t("concepts.action.new"));
        newConcept.setGraphic(icon("fas-plus"));
        newConcept.setOnAction(e -> showConceptForm(dlg, null, reload));

        // Un concepto del HISTÓRICO no tiene fila que editar: se GUARDA (POST).
        // Uno del catálogo ya existe: se EDITA (PUT) o se quita.
        Button saveToCatalog = new Button(t("concepts.action.save_to_catalog"));
        saveToCatalog.setGraphic(icon("fas-bookmark"));
        Button editConcept = new Button(t("concepts.action.edit"));
        editConcept.setGraphic(icon("fas-edit"));
        Button removeConcept = new Button(t("concepts.action.remove"));
        removeConcept.setGraphic(icon("fas-trash-alt"));

        Runnable syncButtons = () -> {
            ConceptEntry sel = table.getSelectionModel().getSelectedItem();
            boolean one = sel != null && table.getSelectionModel().getSelectedItems().size() == 1;
            saveToCatalog.setDisable(!(one && !sel.saved()));
            editConcept.setDisable(!(one && sel.saved()));
            removeConcept.setDisable(!(one && sel.saved()));
        };
        table.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<ConceptEntry>) change -> syncButtons.run());
        syncButtons.run();

        saveToCatalog.setOnAction(e -> showConceptForm(dlg, table.getSelectionModel().getSelectedItem(), reload));
        editConcept.setOnAction(e -> showConceptForm(dlg, table.getSelectionModel().getSelectedItem(), reload));
        removeConcept.setOnAction(e -> {
            ConceptEntry sel = table.getSelectionModel().getSelectedItem();
            if (sel == null || !sel.saved()) return;
            javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.CONFIRMATION,
                    t("concepts.remove.body").replace("{name}", sel.name()),
                    javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);
            confirm.setHeaderText(t("concepts.remove.title"));
            confirm.initOwner(dlg);
            if (confirm.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL)
                    != javafx.scene.control.ButtonType.OK) {
                return;
            }
            Task<Void> del = new Task<>() {
                @Override protected Void call() throws Exception {
                    billingApiClient.deleteConcept(sel.id());
                    return null;
                }
            };
            del.setOnSucceeded(ev -> { toast(dlg, t("concepts.removed")); reload.run(); });
            del.setOnFailed(ev -> showError(t("concepts.fail.title"),
                    del.getException() == null ? "" : del.getException().getMessage()));
            start(del, "concept-delete");
        });

        Button add = new Button(t("concepts.action.add"));
        add.setGraphic(icon("fas-plus")); add.getStyleClass().add("button-primary");
        add.setOnAction(e -> {
            var sel = new java.util.ArrayList<>(table.getSelectionModel().getSelectedItems());
            if (sel.isEmpty()) { showError(t("concepts.fail.title"), t("concepts.none_selected")); return; }
            // Igual que al importar trabajos: la línea vacía con la que arranca
            // una factura nueva se retira (si no, bloquea el guardado con
            // "línea sin descripción").
            editorLinesTable.getItems().removeIf(l ->
                    (l.getDescription() == null || l.getDescription().isBlank())
                            && l.getUnitPrice().signum() == 0);
            for (ConceptEntry c : sel) {
                editorLinesTable.getItems().add(lineFromConcept(c));
            }
            recomputeEditorTotals();
            dlg.close();
        });
        Button cancel = new Button(t("dialog.cancel"));
        cancel.setOnAction(e -> dlg.close());

        Region btnSpacer = new Region();
        HBox.setHgrow(btnSpacer, Priority.ALWAYS);
        HBox btns = new HBox(10, newConcept, saveToCatalog, editConcept, removeConcept,
                btnSpacer, cancel, add);
        btns.setAlignment(Pos.CENTER_LEFT);

        // Controles ARRIBA del listado (patrón Slice 3V): buscador y acciones
        // visibles sin hacer scroll.
        VBox root = new VBox(12, hint, search, new Separator(), table, btns);
        root.setPadding(new javafx.geometry.Insets(16));
        root.setPrefSize(860, 560);
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/com/benjagest/ui/app.css").toExternalForm());
        dlg.setScene(scene);
        dlg.setResizable(true);
        dlg.showAndWait();
    }

    /** Texto normalizado del concepto para el buscador (sin tildes, minúsculas). */
    private String conceptText(ConceptEntry c) {
        String raw = (c.name() == null ? "" : c.name()) + " "
                + (c.description() == null ? "" : c.description());
        return stripDiacritics(raw.toLowerCase());
    }

    /** Línea de factura a partir de un concepto elegido. */
    private InvoiceLineDraft lineFromConcept(ConceptEntry c) {
        // Si el concepto no trae IVA/retención propios (0 por defecto en un
        // concepto recién creado a mano), se usan los del cliente de la
        // factura, que es lo que espera el usuario.
        java.math.BigDecimal vat = c.vatPercent().signum() == 0 ? editorDefaultVat : c.vatPercent();
        java.math.BigDecimal ret = c.retentionPercent().signum() == 0
                ? editorDefaultRetention : c.retentionPercent();
        // CONC-3: un concepto guardado puede traer saltos de línea; en la
        // línea de factura van como UN párrafo, sin pegar palabras.
        InvoiceLineDraft line = new InvoiceLineDraft(
                singleLine(c.invoiceText()), java.math.BigDecimal.ONE, c.unitPrice(), vat, ret);
        if (c.vatRateId() != null && !c.vatRateId().isBlank()) {
            line.setVatRateId(c.vatRateId());
        }
        return line;
    }

    /** Guarda como concepto la línea seleccionada en la factura. */
    private void saveSelectedLineAsConcept() {
        InvoiceLineDraft sel = editorLinesTable.getSelectionModel().getSelectedItem();
        if (sel == null || sel.getDescription() == null || sel.getDescription().isBlank()) {
            showError(t("concepts.fail.title"), t("concepts.no_line_selected"));
            return;
        }
        ConceptEntry prefill = new ConceptEntry(
                null, ConceptEntry.SOURCE_HISTORY, sel.getDescription(), sel.getDescription(),
                sel.getUnitPrice(), sel.getVatPercent(), sel.getRetentionPercent(),
                sel.getVatRateId(), 0, "");
        showConceptForm(null, prefill, null);
    }

    /**
     * Formulario de un concepto. Con {@code existing} nulo o sin id, GUARDA uno
     * nuevo (POST); con id, actualiza el guardado (PUT).
     */
    private void showConceptForm(Stage owner, ConceptEntry existing, Runnable onSaved) {
        boolean editing = existing != null && existing.saved();
        Stage dlg = new Stage();
        dlg.setTitle(editing ? t("concepts.form.title.edit") : t("concepts.form.title.new"));
        dlg.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        if (owner != null) {
            dlg.initOwner(owner);
        } else {
            com.benjagest.ui.support.Dialogs.ownAndCenter(dlg);
        }

        // CONC-3: pegar aquí un texto de varias líneas tampoco debe pegar
        // palabras (mismo motivo que en la línea de factura).
        TextField name = new TextField(existing == null ? "" : existing.name()) {
            @Override public void paste() { pasteAsSingleLine(this); }
        };
        name.setPromptText(t("concepts.form.name"));
        javafx.scene.control.TextArea description = new javafx.scene.control.TextArea(
                existing == null ? "" : existing.invoiceText());
        description.setPromptText(t("concepts.form.description"));
        description.setPrefRowCount(3);
        TextField price = new TextField(existing == null ? "" : formatDecimalForCell(existing.unitPrice()));
        TextField vat = new TextField(existing == null ? "" : formatDecimalForCell(existing.vatPercent()));
        TextField retention = new TextField(existing == null ? "" : formatDecimalForCell(existing.retentionPercent()));

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.getStyleClass().add("form-grid");
        grid.setHgap(12); grid.setVgap(10);
        // Sin estas restricciones la columna de las etiquetas se encoge para dar
        // sitio a los campos y salen cortadas ("No...", "Tex..."): visto en el
        // smoke de la UI.
        javafx.scene.layout.ColumnConstraints labelCol = new javafx.scene.layout.ColumnConstraints();
        labelCol.setMinWidth(Region.USE_PREF_SIZE);
        labelCol.setHalignment(javafx.geometry.HPos.LEFT);
        javafx.scene.layout.ColumnConstraints fieldCol = new javafx.scene.layout.ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        fieldCol.setFillWidth(true);
        grid.getColumnConstraints().addAll(labelCol, fieldCol);
        grid.addRow(0, label(t("concepts.form.name"), "invoice-field-label"), name);
        grid.addRow(1, label(t("concepts.form.description"), "invoice-field-label"), description);
        grid.addRow(2, label(t("concepts.form.price"), "invoice-field-label"), price);
        grid.addRow(3, label(t("concepts.form.vat"), "invoice-field-label"), vat);
        grid.addRow(4, label(t("concepts.form.retention"), "invoice-field-label"), retention);
        for (Node n : java.util.List.of(name, description, price, vat, retention)) {
            n.getStyleClass().add("form-input");
            javafx.scene.layout.GridPane.setHgrow(n, Priority.ALWAYS);
        }

        Label help = new Label(t("concepts.form.hint"));
        help.setWrapText(true); help.getStyleClass().add("settings-hint");
        help.setMinHeight(Region.USE_PREF_SIZE);

        Button save = new Button(t("dialog.save"));
        save.setGraphic(icon("fas-save")); save.getStyleClass().add("button-primary");
        save.setOnAction(e -> {
            String theName = name.getText() == null ? "" : name.getText().trim();
            if (theName.isBlank()) {
                showError(t("concepts.fail.title"), t("concepts.form.name_required"));
                return;
            }
            java.math.BigDecimal p = parseDecSafe(price.getText());
            java.math.BigDecimal v = parseDecSafe(vat.getText());
            java.math.BigDecimal r = parseDecSafe(retention.getText());
            String body = description.getText() == null ? "" : description.getText().trim();
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    if (editing) {
                        billingApiClient.updateConcept(existing.id(), theName, body, p, v, r,
                                existing.vatRateId());
                    } else {
                        billingApiClient.saveConcept(theName, body, p, v, r,
                                existing == null ? null : existing.vatRateId());
                    }
                    return null;
                }
            };
            task.setOnSucceeded(ev -> {
                dlg.close();
                toast(t("concepts.saved"));
                if (onSaved != null) onSaved.run();
            });
            task.setOnFailed(ev -> showError(t("concepts.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "concept-save");
        });
        Button cancel = new Button(t("dialog.cancel"));
        cancel.setOnAction(e -> dlg.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox btns = new HBox(10, spacer, cancel, save);
        btns.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, help, grid, btns);
        root.setPadding(new javafx.geometry.Insets(16));
        root.setPrefSize(620, 440);
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/com/benjagest/ui/app.css").toExternalForm());
        dlg.setScene(scene);
        dlg.setResizable(true);
        dlg.showAndWait();
    }

    /** Valoración legible de un trabajo (copia compartida con la tabla de Trabajos del shell). */
    private String workLogValuationLabel(com.benjagest.ui.model.WorkLogEntry e) {
        if (!e.billable()) return "—";
        if ("FIXED".equals(e.billingUnit())) return t("trabajos.unit.FIXED");
        if (e.quantity() != null && e.unitPrice() != null) {
            return e.quantity().toPlainString() + " " + t("trabajos.unit." + e.billingUnit())
                    + " × " + money(e.unitPrice().toPlainString());
        }
        return "";
    }
}
