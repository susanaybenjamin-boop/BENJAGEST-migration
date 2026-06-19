package com.benjagest.ui.screens;

import com.benjagest.ui.model.AccountingModels;
import com.benjagest.ui.model.AccountingModels.AccountSummary;
import com.benjagest.ui.model.AccountingModels.DiaryEntry;
import com.benjagest.ui.model.AccountingModels.JournalEntryDetail;
import com.benjagest.ui.model.AccountingModels.JournalLine;
import com.benjagest.ui.model.AccountingModels.LearningRule;
import com.benjagest.ui.model.AccountingModels.RecurringTask;
import com.benjagest.ui.model.FiscalYearCloseEntry;
import com.benjagest.ui.model.RegularizationPreviewEntry;
import com.benjagest.ui.service.AccountingApiClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Pantalla del módulo Contabilidad para asesoría.
 *
 * <p>Tabs:
 * <ul>
 *   <li><b>Por validar</b> — asientos {@code auto_proposed=TRUE AND status='DRAFT'}.
 *       Muestra el chip de confianza, abre el editor para revisar/corregir
 *       y permite validar (POSTED) o reescribir cuentas.</li>
 *   <li><b>Diario</b> — Libro Diario por rango de fechas con filtros de
 *       status y sourceType. Doble-click abre el asiento.</li>
 *   <li><b>Asientos manuales</b> — botón para crear un asiento desde cero
 *       y editor con tabla de líneas + balance en vivo.</li>
 *   <li><b>Reglas aprendidas</b> — listado de reglas con su confianza,
 *       activar/desactivar/borrar.</li>
 *   <li><b>Recurrentes</b> — tareas recurrentes con run-now y desactivar.</li>
 * </ul>
 *
 * <p>Diseñada como clase autocontenida — {@link BenjagestUiApplication}
 * solo necesita un método {@code showAccounting(StackPane)} que crea una
 * instancia y la pega en el viewport. Todas las llamadas API son async
 * en un hilo de fondo; cada {@code Task} actualiza el JavaFX thread via
 * {@code Platform.runLater}.
 */
public class AccountingScreen {

    private final AccountingApiClient api;
    private final Function<String, String> tt;

    public AccountingScreen(AccountingApiClient api) {
        this(api, key -> key);
    }

    public AccountingScreen(AccountingApiClient api, Function<String, String> translator) {
        this.api = api;
        this.tt = translator;
    }

    /** Devuelve el nodo raíz para encajar en el viewport. */
    public Node buildView() {
        TabPane tabs = new TabPane();
        this.accountingTabs = tabs;
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        // Slice 3O — Eliminado el tab "Recurrentes" de Contabilidad.
        // Las plantillas recurrentes viven en su sitio natural:
        //   - Ventas recurrentes → sub-tab de Facturación
        //   - Gastos recurrentes (con factura o sin) → sub-tab de
        //     Compras y Gastos
        // El asesor accede ahí, no desde Contabilidad. Mantener ambas
        // entradas duplicaba la funcionalidad y confundía.
        pendingTab = new Tab(tt.apply("accounting.tab.pending"), buildPendingTab());
        tabs.getTabs().addAll(
                pendingTab,
                new Tab(tt.apply("accounting.tab.diary"), buildDiaryTab()),
                new Tab(tt.apply("accounting.tab.manual"), buildManualTab()),
                // REPORTS-UI — informes contables (backend ya existía; faltaba UI).
                new Tab(tt.apply("accounting.tab.ledger"), buildLedgerTab()),
                new Tab(tt.apply("accounting.tab.trial_balance"), buildTrialBalanceTab()),
                new Tab(tt.apply("accounting.tab.balance_sheet"), buildBalanceSheetTab()),
                new Tab(tt.apply("accounting.tab.pyg"), buildPygTab()),
                // FIN-1 — cuadro de mando financiero del cliente.
                new Tab(tt.apply("accounting.tab.dashboard"), buildFinancialsTab()),
                new Tab(tt.apply("accounting.tab.ecpn"), buildEcpnTab()),
                new Tab(tt.apply("accounting.tab.rules"), buildRulesTab()),
                // ACC-TEMPLATES — plantillas de asiento manual recurrente
                // (backend ya existía; faltaba la UI de gestión + aplicar).
                new Tab(tt.apply("accounting.tab.templates"), buildTemplatesTab()),
                new Tab(tt.apply("accounting.tab.year_close"), buildYearCloseTab()),
                new Tab(tt.apply("accounting.tab.exchange"), buildExportImportTab())
        );
        VBox.setVgrow(tabs, Priority.ALWAYS);
        VBox root = new VBox(tabs);
        root.setPadding(new Insets(8));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        // Auto-refresh de Por validar, Diario y Cuadro de mando cuando alguien
        // emita JOURNAL (validar asiento, aceptar, batch, reclasificar,
        // borrar gasto/venta, aplicar plantilla…). Así el aviso del cuadro de
        // mando ("X por validar") desaparece solo al validar, sin que el
        // usuario tenga que pulsar Refrescar. Auto-baja al desmontar.
        com.benjagest.ui.support.RefreshBus.subscribe(
                com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL,
                () -> { loadPending(); loadDiary(); reloadFinancialsIfReady(); }, root);
        // También refrescar reglas y recurrentes con sus topics.
        com.benjagest.ui.support.RefreshBus.subscribe(
                com.benjagest.ui.support.RefreshBus.TOPIC_RULES,
                this::loadRules, root);
        com.benjagest.ui.support.RefreshBus.subscribe(
                com.benjagest.ui.support.RefreshBus.TOPIC_RECURRING,
                this::loadRecurring, root);
        return root;
    }

    // ====================================================================
    //  Tab: Por validar
    // ====================================================================

    private TableView<DiaryEntry> pendingTable;
    private TabPane accountingTabs;
    private Tab pendingTab;

    /** Selecciona la pestaña "Por validar" y recarga su contenido. */
    private void goToPendingTab() {
        if (accountingTabs != null && pendingTab != null) {
            accountingTabs.getSelectionModel().select(pendingTab);
            loadPending();
        }
    }

    private Node buildPendingTab() {
        pendingTable = createDiaryTable(true);
        // Multiselección: el asesor marca varios DRAFT y los valida en lote.
        pendingTable.getSelectionModel().setSelectionMode(
                javafx.scene.control.SelectionMode.MULTIPLE);

        Button refresh = new Button(tt.apply("accounting.action.refresh"));
        refresh.setOnAction(e -> loadPending());

        Button validate = new Button(tt.apply("accounting.action.validate"));
        validate.setOnAction(e -> {
            DiaryEntry sel = pendingTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            openEntryEditor(sel.id());
        });

        // Multivalidación: cuando hay más de un seleccionado, valida
        // todos los DRAFT seleccionados en una sola llamada al backend.
        Button validateBatch = new Button(tt.apply("accounting.action.validate_batch"));
        validateBatch.setOnAction(e -> validateSelectedBatch());

        Button accept = new Button(tt.apply("accounting.action.accept"));
        accept.setOnAction(e -> {
            DiaryEntry sel = pendingTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            async(() -> {
                api.acceptEntry(sel.id(), List.of());
                api.postEntry(sel.id());
                return null;
            }, ok -> com.benjagest.ui.support.RefreshBus.emit(
                    com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL),
               err -> showError(tt.apply("accounting.error.accept"), err));
        });

        // Botón "Regenerar asientos faltantes" — recorre facturas guardadas
        // sin asiento y las pasa por el service auto-generador. Útil cuando
        // el cliente tenía facturas anteriores al PGC o sin fiscal_year.
        Button backfill = new Button(tt.apply("accounting.action.backfill"));
        backfill.setOnAction(e -> {
            // Confirmación previa para que el asesor entienda qué hace.
            Alert confirm = new Alert(AlertType.CONFIRMATION,
                    tt.apply("accounting.confirm.backfill"),
                    ButtonType.YES, ButtonType.NO);
            confirm.setHeaderText(tt.apply("accounting.action.backfill"));
            confirm.showAndWait().ifPresent(bt -> {
                if (bt != ButtonType.YES) return;
                backfill.setDisable(true);
                async(() -> api.runBackfill(),
                        result -> {
                            backfill.setDisable(false);
                            Alert info = new Alert(AlertType.INFORMATION,
                                    tt.apply("accounting.backfill.result")
                                            .replace("{p}", String.valueOf(result.purchasesPosted()))
                                            .replace("{s}", String.valueOf(result.salesPosted()))
                                            .replace("{t}", String.valueOf(result.totalPosted())));
                            info.setHeaderText(tt.apply("accounting.backfill.done"));
                            info.showAndWait();
                            com.benjagest.ui.support.RefreshBus.emit(
                                    com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL,
                                    com.benjagest.ui.support.RefreshBus.TOPIC_PURCHASES,
                                    com.benjagest.ui.support.RefreshBus.TOPIC_SALES);
                        },
                        err -> {
                            backfill.setDisable(false);
                            showError(tt.apply("accounting.error.backfill"), err);
                        });
            });
        });

        // Botón "Reclasificar asientos" — recorre asientos DRAFT y vuelve a
        // calcular su cuenta 6xx/7xx aplicando histórico+classifier (port
        // de revisarCuentasAsientos de CONTENDO). Solo toca cuentas
        // genéricas (600/700) — si el asesor ya editó la cuenta a algo
        // específico se respeta.
        Button reclassify = new Button(tt.apply("accounting.action.reclassify"));
        reclassify.setOnAction(e -> {
            reclassify.setDisable(true);
            async(() -> api.reclassifyDrafts(),
                    result -> {
                        reclassify.setDisable(false);
                        Alert info = new Alert(AlertType.INFORMATION,
                                tt.apply("accounting.reclassify.result")
                                        .replace("{n}", String.valueOf(result.linesUpdated()))
                                        .replace("{t}", String.valueOf(result.entriesScanned())));
                        info.setHeaderText(tt.apply("accounting.reclassify.done"));
                        info.showAndWait();
                        com.benjagest.ui.support.RefreshBus.emit(
                                com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL);
                    },
                    err -> {
                        reclassify.setDisable(false);
                        showError(tt.apply("accounting.error.reclassify"), err);
                    });
        });

        Label hint = new Label(tt.apply("accounting.pending.hint"));
        hint.setStyle("-fx-text-fill: #6e6e6e;");

        HBox actions = new HBox(8, refresh, validate, accept, validateBatch,
                new javafx.scene.layout.Region(), reclassify, backfill);
        actions.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(actions.getChildren().get(4), Priority.ALWAYS);

        // validate y accept solo cuando hay 1; validateBatch cuando hay >=1
        // DRAFT seleccionado.
        pendingTable.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<DiaryEntry>) ch -> {
            var sel = pendingTable.getSelectionModel().getSelectedItems();
            boolean exactlyOne = sel.size() == 1;
            validate.setDisable(!exactlyOne);
            accept.setDisable(!exactlyOne);
            boolean anyDraft = sel.stream().anyMatch(
                    en -> en != null && "DRAFT".equalsIgnoreCase(en.status()));
            validateBatch.setDisable(!anyDraft);
        });
        validate.setDisable(true);
        accept.setDisable(true);
        validateBatch.setDisable(true);

        VBox box = new VBox(8, hint, actions, pendingTable);
        VBox.setVgrow(pendingTable, Priority.ALWAYS);
        box.setPadding(new Insets(8));
        loadPending();
        return box;
    }

    /**
     * Valida en lote los asientos DRAFT seleccionados en la tabla.
     * Diálogo de confirmación con count → endpoint batch del backend →
     * resumen y refresh. Pieza clave del flujo asesor "auto-propuesta
     * → revisar lote → validar todos los buenos a la vez".
     */
    private void validateSelectedBatch() {
        var sel = pendingTable.getSelectionModel().getSelectedItems();
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (var e : sel) {
            if (e != null && "DRAFT".equalsIgnoreCase(e.status())) ids.add(e.id());
        }
        if (ids.isEmpty()) return;
        Alert confirm = new Alert(AlertType.CONFIRMATION,
                tt.apply("accounting.confirm.validate_batch")
                        .replace("{n}", String.valueOf(ids.size())),
                ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText(tt.apply("accounting.action.validate_batch"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.YES) return;
            async(() -> api.postBatchEntries(ids), result -> {
                Alert info = new Alert(AlertType.INFORMATION,
                        tt.apply("accounting.validate_batch.result")
                                .replace("{p}", String.valueOf(result.posted()))
                                .replace("{s}", String.valueOf(result.skipped()))
                                .replace("{e}", String.valueOf(result.errors())));
                info.setHeaderText(tt.apply("accounting.action.validate_batch"));
                info.showAndWait();
                com.benjagest.ui.support.RefreshBus.emit(
                        com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL);
            }, err -> showError(tt.apply("accounting.error.validate_batch"), err));
        });
    }

    private void loadPending() {
        async(() -> api.diary(null, null, "DRAFT", null, 200), rows -> {
            List<DiaryEntry> auto = new ArrayList<>();
            for (DiaryEntry e : rows) {
                if (e.autoProposed()) auto.add(e);
            }
            pendingTable.setItems(FXCollections.observableArrayList(auto));
        }, err -> logSilent("pending", err));
    }

    // ====================================================================
    //  Tab: Diario
    // ====================================================================

    private TableView<DiaryEntry> diaryTable;
    private DatePicker fromPicker;
    private DatePicker toPicker;
    private ComboBox<String> statusFilter;
    private ComboBox<String> sourceFilter;

    /** Caché del listado completo del Diario para filtrar client-side por texto. */
    private final javafx.collections.ObservableList<DiaryEntry> diaryAll =
            FXCollections.observableArrayList();
    private TextField diarySearch;

    private Node buildDiaryTab() {
        diaryTable = createDiaryTable(false);

        fromPicker = new DatePicker(LocalDate.now().withDayOfYear(1));
        // El Libro Diario se ve por EJERCICIO completo: hasta el 31/12 del año,
        // no hasta "hoy". Si no, los asientos con fecha futura dentro del año (p.
        // ej. el DEVENGO de la nómina del mes, datado a fin de mes) no aparecían
        // aunque estuvieran validados. Bug reportado por Benjamin 2026-06-17.
        toPicker = new DatePicker(LocalDate.now().withMonth(12).withDayOfMonth(31));
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(fromPicker);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(toPicker);
        statusFilter = new ComboBox<>(FXCollections.observableArrayList(
                "", "DRAFT", "POSTED", "VOIDED"));
        statusFilter.setValue("POSTED");
        installStatusCellFactory(statusFilter);
        // Slice 3U — el filtro Origen se simplifica conceptualmente:
        // "Venta" engloba SALES_INVOICE + SALES_PDF_IMPORT (todas las
        // ventas centralizadas — vengan de factura validada o de PDF
        // importado por el asesor). "Compra" engloba PURCHASE_INVOICE
        // (los recurrentes contables RECURRING_ACCOUNTING se filtran
        // aparte para distinguirlos del flujo normal).
        // Slice 3X — Filtro Origen UNIFICADO: las recurrentes ahora
        // se guardan con source_type SALES_INVOICE/PURCHASE_INVOICE
        // (en vez del antiguo RECURRING_ACCOUNTING) y marcan "(recurrente)"
        // en el concepto. Así el asesor solo tiene 2 estados para
        // facturas: Venta y Gasto. La opción RECURRING_TASK queda
        // para distinguir asientos creados explícitamente como
        // plantillas JOURNAL_ENTRY (sin metadata fiscal).
        // Lista COMPLETA de orígenes (todos los source_type que el backend
        // puede escribir en journal_entries y que tienen clave i18n). Si se
        // añade un source_type nuevo, añadirlo también aquí (y su clave ES+EN).
        sourceFilter = new ComboBox<>(FXCollections.observableArrayList(
                "", "MANUAL", "MANUAL_REVERSAL",
                "SALES_INVOICE", "SALES_PDF_IMPORT", "PURCHASE_INVOICE",
                "BANK_MOVEMENT", "DUE_DATE_PAYMENT",
                "PAYSLIP_ACCRUAL", "PAYSLIP_PAYMENT",
                "PAYSLIP_EXTRA_PROVISION", "PAYSLIP_EXTRA_PAYMENT",
                "LOAN_INSTALLMENT",
                "ASSET_ACQUISITION", "ASSET_DEPRECIATION", "ASSET_DISPOSAL",
                "YEAR_CLOSE_REGULARIZATION", "YEAR_CLOSE_CLOSING",
                "RECURRING_TASK", "RECURRING_ACCOUNTING"));
        installSourceCellFactory(sourceFilter);

        // Búsqueda libre — filtra por texto que aparezca en concepto, nº
        // de asiento o (cuando se cargue el detalle) cuenta/tercero.
        diarySearch = new TextField();
        diarySearch.setPromptText(tt.apply("accounting.filter.search_prompt"));
        diarySearch.setPrefColumnCount(20);
        diarySearch.textProperty().addListener((o, a, b) -> applyDiarySearch());

        Button reload = new Button(tt.apply("accounting.action.refresh"));
        reload.setOnAction(e -> loadDiary());

        HBox filters = new HBox(8,
                new Label(tt.apply("accounting.filter.from")), fromPicker,
                new Label(tt.apply("accounting.filter.to")), toPicker,
                new Label(tt.apply("accounting.filter.status")), statusFilter,
                new Label(tt.apply("accounting.filter.source")), sourceFilter,
                new Label(tt.apply("accounting.filter.search")), diarySearch,
                reload);
        filters.setAlignment(Pos.CENTER_LEFT);

        diaryTable.setRowFactory(tv -> {
            javafx.scene.control.TableRow<DiaryEntry> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && row.getItem() != null) {
                    openEntryEditor(row.getItem().id());
                }
            });
            return row;
        });

        VBox box = new VBox(8, filters, diaryTable);
        VBox.setVgrow(diaryTable, Priority.ALWAYS);
        box.setPadding(new Insets(8));
        loadDiary();
        return box;
    }

    private void loadDiary() {
        LocalDate from = fromPicker.getValue();
        LocalDate to = toPicker.getValue();
        String status = empty(statusFilter.getValue()) ? null : statusFilter.getValue();
        // Slice 3U — Expandir filtros virtuales:
        // - "SALES_INVOICE" busca también SALES_PDF_IMPORT.
        //   (Las facturas importadas por PDF son ventas reales del
        //   cliente, solo cambia el origen del documento.)
        String source;
        if (empty(sourceFilter.getValue())) {
            source = null;
        } else if ("SALES_INVOICE".equals(sourceFilter.getValue())) {
            source = "SALES_INVOICE,SALES_PDF_IMPORT";
        } else {
            source = sourceFilter.getValue();
        }
        async(() -> api.diary(from, to, status, source, 500),
                rows -> {
                    diaryAll.setAll(rows);
                    applyDiarySearch();
                },
                err -> logSilent("load", err));
    }

    /** Filtro client-side por texto en concepto/nº/source. */
    private void applyDiarySearch() {
        String q = diarySearch == null || diarySearch.getText() == null
                ? "" : diarySearch.getText().trim().toLowerCase();
        if (q.isEmpty()) {
            diaryTable.setItems(FXCollections.observableArrayList(diaryAll));
            return;
        }
        javafx.collections.ObservableList<DiaryEntry> filtered =
                FXCollections.observableArrayList();
        for (DiaryEntry e : diaryAll) {
            String concept = e.concept() == null ? "" : e.concept().toLowerCase();
            String num = String.valueOf(e.entryNumber());
            String src = e.sourceType() == null ? "" : e.sourceType().toLowerCase();
            String srcLabel = translateSourceType(e.sourceType()).toLowerCase();
            if (concept.contains(q) || num.contains(q)
                    || src.contains(q) || srcLabel.contains(q)) {
                filtered.add(e);
            }
        }
        diaryTable.setItems(filtered);
    }

    /** Traduce un source_type al idioma activo (ej. PURCHASE_INVOICE → "Compra"). */
    private String translateSourceType(String code) {
        if (code == null || code.isBlank()) return "";
        return tt.apply("accounting.source_type." + code);
    }

    /** Aplica un cellFactory al combo de estado para mostrar texto traducido. */
    private void installStatusCellFactory(ComboBox<String> combo) {
        combo.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                setText(item.isEmpty()
                        ? tt.apply("accounting.filter.any")
                        : tt.apply("accounting.status." + item));
            }
        });
        combo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                setText(item.isEmpty()
                        ? tt.apply("accounting.filter.any")
                        : tt.apply("accounting.status." + item));
            }
        });
    }

    /** Aplica un cellFactory al combo de origen para mostrar texto traducido. */
    private void installSourceCellFactory(ComboBox<String> combo) {
        combo.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                setText(item.isEmpty()
                        ? tt.apply("accounting.filter.any")
                        : translateSourceType(item));
            }
        });
        combo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                setText(item.isEmpty()
                        ? tt.apply("accounting.filter.any")
                        : translateSourceType(item));
            }
        });
    }

    // ====================================================================
    //  Tab: Asientos manuales
    // ====================================================================

    private Node buildManualTab() {
        Label hint = new Label(tt.apply("accounting.manual.hint"));
        hint.setStyle("-fx-text-fill: #6e6e6e;");
        Button create = new Button(tt.apply("accounting.action.new_entry"));
        create.setOnAction(e -> openEntryEditor(null));
        VBox box = new VBox(12, hint, create);
        box.setPadding(new Insets(16));
        return box;
    }

    // ====================================================================
    //  Tab: Reglas aprendidas
    // ====================================================================

    private TableView<LearningRule> rulesTable;

    private Node buildRulesTab() {
        rulesTable = new TableView<>();
        rulesTable.getColumns().addAll(List.of(
                col(tt.apply("accounting.col.rule_kind"), r -> tt.apply("accounting.rule_kind." + r.ruleKind()), 180),
                col(tt.apply("accounting.col.nif"), r -> first(r.matchSupplierNif(), r.matchCustomerNif()), 120),
                col(tt.apply("accounting.col.keyword"), LearningRule::matchKeyword, 160),
                col(tt.apply("accounting.col.target_account"), LearningRule::targetAccountCode, 110),
                col(tt.apply("accounting.col.confidence"), r -> r.confidence() == null ? "" : r.confidence() + "%", 90),
                col(tt.apply("accounting.col.applied"), r -> String.valueOf(r.timesApplied()), 70),
                col(tt.apply("accounting.col.overridden"), r -> String.valueOf(r.timesOverridden()), 70),
                col(tt.apply("accounting.col.active"), r -> r.active() ? "✓" : "✗", 60)
        ));

        Button refresh = new Button(tt.apply("accounting.action.refresh"));
        refresh.setOnAction(e -> loadRules());
        Button toggle = new Button(tt.apply("accounting.action.toggle"));
        toggle.setOnAction(e -> {
            LearningRule sel = rulesTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            async(() -> { api.setRuleActive(sel.id(), !sel.active()); return null; },
                    v -> loadRules(),
                    err -> showError(tt.apply("accounting.error.toggle"), err));
        });
        Button delete = new Button(tt.apply("accounting.action.delete"));
        delete.setOnAction(e -> {
            LearningRule sel = rulesTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert confirm = new Alert(AlertType.CONFIRMATION,
                    tt.apply("accounting.confirm.delete_rule"),
                    ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.YES) {
                    async(() -> { api.deleteRule(sel.id()); return null; },
                            v -> loadRules(),
                            err -> showError(tt.apply("accounting.error.delete"), err));
                }
            });
        });

        HBox actions = new HBox(8, refresh, toggle, delete);
        Label hint = new Label(tt.apply("accounting.rules.hint"));
        hint.setStyle("-fx-text-fill: #6e6e6e;");

        // Panel "Plan de tercero": longitud + modo. Aplica al PRÓXIMO
        // tercero creado — los existentes NO se renumeran.
        Node terceroPanel = buildTerceroConfigPanel();

        VBox box = new VBox(10, terceroPanel, new Separator(),
                hint, actions, rulesTable);
        VBox.setVgrow(rulesTable, Priority.ALWAYS);
        box.setPadding(new Insets(8));
        loadRules();
        return box;
    }

    /**
     * Panel de configuración para la sub-cuenta de tercero:
     * <ul>
     *   <li>Longitud total del código (6–12, default 7).</li>
     *   <li>Modo de generación del sufijo:
     *       SEQUENTIAL (1, 2, 3…) o BY_NIF (dígitos del NIF/CIF).</li>
     * </ul>
     * Guarda inmediatamente al cambiar (no necesita botón "Guardar").
     */
    private Node buildTerceroConfigPanel() {
        Label title = new Label(tt.apply("accounting.tercero.title"));
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        // Combo longitud 6..12.
        ComboBox<Integer> lengthCombo = new ComboBox<>();
        for (int i = 6; i <= 12; i++) lengthCombo.getItems().add(i);
        lengthCombo.setPrefWidth(80);

        // Radio modo.
        ToggleGroup modeGroup = new ToggleGroup();
        RadioButton seqRadio = new RadioButton(tt.apply("accounting.tercero.mode.sequential"));
        RadioButton nifRadio = new RadioButton(tt.apply("accounting.tercero.mode.by_nif"));
        seqRadio.setToggleGroup(modeGroup);
        nifRadio.setToggleGroup(modeGroup);
        seqRadio.setUserData("SEQUENTIAL");
        nifRadio.setUserData("BY_NIF");
        seqRadio.setSelected(true);

        Label preview = new Label("");
        preview.setStyle("-fx-text-fill: #6e6e6e; -fx-font-family: 'Consolas','monospace';");

        Runnable updatePreview = () -> {
            Integer len = lengthCombo.getValue();
            String mode = ((RadioButton) modeGroup.getSelectedToggle()).getUserData().toString();
            if (len == null) return;
            int suffix = Math.max(1, len - 4);
            String example = "BY_NIF".equals(mode)
                    ? "4000" + ("12345678".length() >= suffix
                            ? "12345678".substring(0, Math.min(8, suffix))
                            : String.format("%" + suffix + "s", "12345678").replace(' ', '0'))
                    : "4000" + String.format("%0" + suffix + "d", 1);
            preview.setText(tt.apply("accounting.tercero.preview").replace("{x}", example));
        };

        // Cargar config actual.
        async(() -> api.getTerceroConfig(),
                cfg -> {
                    lengthCombo.setValue(cfg.length() >= 6 && cfg.length() <= 12 ? cfg.length() : 7);
                    if ("BY_NIF".equalsIgnoreCase(cfg.mode())) {
                        nifRadio.setSelected(true);
                    } else {
                        seqRadio.setSelected(true);
                    }
                    updatePreview.run();
                },
                err -> {
                    lengthCombo.setValue(7);
                    updatePreview.run();
                });

        // Guardar al cambiar (debounced via simple flag para no spamear).
        Runnable save = () -> {
            Integer len = lengthCombo.getValue();
            if (len == null) return;
            String mode = ((RadioButton) modeGroup.getSelectedToggle()).getUserData().toString();
            updatePreview.run();
            async(() -> api.updateTerceroConfig(len, mode),
                    ok -> {/* silencioso — el cambio ya quedó visible */},
                    err -> showError(tt.apply("accounting.tercero.error_save"), err));
        };
        lengthCombo.valueProperty().addListener((o, a, b) -> save.run());
        modeGroup.selectedToggleProperty().addListener((o, a, b) -> save.run());

        HBox lengthRow = new HBox(8,
                new Label(tt.apply("accounting.tercero.length")), lengthCombo);
        lengthRow.setAlignment(Pos.CENTER_LEFT);
        HBox modeRow = new HBox(8,
                new Label(tt.apply("accounting.tercero.mode")), seqRadio, nifRadio);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        Label warn = new Label(tt.apply("accounting.tercero.warn"));
        warn.setStyle("-fx-text-fill: #6e6e6e; -fx-font-style: italic;");
        warn.setWrapText(true);

        VBox box = new VBox(6, title, lengthRow, modeRow, preview, warn);
        box.setPadding(new Insets(4, 0, 4, 0));
        return box;
    }

    private void loadRules() {
        async(() -> api.listRules(null, null),
                rows -> rulesTable.setItems(FXCollections.observableArrayList(rows)),
                err -> logSilent("load", err));
    }

    // ====================================================================
    //  Tab: Plantillas de asiento (ACC-TEMPLATES)
    // ====================================================================

    private TableView<AccountingModels.EntryTemplate> templatesTable;
    private CheckBox templatesShowArchived;

    /** Categorías de plantilla (coinciden con las del backend). */
    private static final List<String> TEMPLATE_CATEGORIES =
            List.of("PAYROLL", "TAX", "PROVISION", "PERIODIFICATION", "OTHER");
    private static final List<String> LINE_SIDES = List.of("DEBIT", "CREDIT");
    private static final List<String> LINE_KINDS = List.of("FIXED", "VARIABLE", "FORMULA");

    private Node buildTemplatesTab() {
        templatesTable = new TableView<>();
        templatesTable.setPlaceholder(new Label(tt.apply("accounting.templates.empty")));
        templatesTable.getColumns().addAll(List.of(
                col(tt.apply("accounting.col.code"), AccountingModels.EntryTemplate::code, 90),
                col(tt.apply("accounting.col.name"), AccountingModels.EntryTemplate::name, 200),
                col(tt.apply("accounting.col.category"),
                        t -> tt.apply("accounting.tpl_cat." + t.category()), 130),
                col(tt.apply("accounting.col.lines"), t -> String.valueOf(t.lines().size()), 60),
                col(tt.apply("accounting.col.applied"), t -> String.valueOf(t.timesUsed()), 70),
                col(tt.apply("accounting.col.active"), t -> t.active() ? "✓" : "✗", 60)
        ));
        templatesTable.setRowFactory(tv -> {
            javafx.scene.control.TableRow<AccountingModels.EntryTemplate> r = new javafx.scene.control.TableRow<>();
            r.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !r.isEmpty()) openTemplateEditor(r.getItem());
            });
            return r;
        });

        Button create = new Button(tt.apply("accounting.action.new_template"));
        create.setOnAction(e -> openTemplateEditor(null));
        Button edit = new Button(tt.apply("accounting.action.edit"));
        edit.setOnAction(e -> {
            AccountingModels.EntryTemplate sel = templatesTable.getSelectionModel().getSelectedItem();
            if (sel != null) openTemplateEditor(sel);
        });
        Button apply = new Button(tt.apply("accounting.action.apply_template"));
        apply.setOnAction(e -> {
            AccountingModels.EntryTemplate sel = templatesTable.getSelectionModel().getSelectedItem();
            if (sel != null) openTemplateApply(sel);
        });
        Button archive = new Button(tt.apply("accounting.action.archive"));
        archive.setOnAction(e -> {
            AccountingModels.EntryTemplate sel = templatesTable.getSelectionModel().getSelectedItem();
            if (sel == null || !sel.active()) return;
            Alert confirm = new Alert(AlertType.CONFIRMATION,
                    tt.apply("accounting.confirm.archive_template"),
                    ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.YES) {
                    async(() -> { api.archiveTemplate(sel.id()); return null; },
                            v -> loadTemplates(),
                            err -> showError(tt.apply("accounting.error.archive"), err));
                }
            });
        });
        Button refresh = new Button(tt.apply("accounting.action.refresh"));
        refresh.setOnAction(e -> loadTemplates());

        templatesShowArchived = new CheckBox(tt.apply("accounting.templates.show_archived"));
        templatesShowArchived.selectedProperty().addListener((o, a, b) -> loadTemplates());

        Label hint = new Label(tt.apply("accounting.templates.hint"));
        hint.setStyle("-fx-text-fill: #6e6e6e;");
        hint.setWrapText(true);

        HBox actions = new HBox(8, create, edit, apply, archive, refresh,
                new Separator(javafx.geometry.Orientation.VERTICAL), templatesShowArchived);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, hint, actions, templatesTable);
        VBox.setVgrow(templatesTable, Priority.ALWAYS);
        box.setPadding(new Insets(8));
        loadTemplates();
        return box;
    }

    private void loadTemplates() {
        Boolean activeOnly = (templatesShowArchived != null && templatesShowArchived.isSelected())
                ? null : Boolean.TRUE;
        async(() -> api.listTemplates(null, activeOnly),
                rows -> templatesTable.setItems(FXCollections.observableArrayList(rows)),
                err -> logSilent("load-templates", err));
    }

    /** Holder mutable editable de una línea de plantilla (UI). */
    private static class TplLineRow {
        final SimpleStringProperty accountCode, description, side, kind, amount, variable;
        TplLineRow(String ac, String desc, String side, String kind, String amount, String variable) {
            this.accountCode = new SimpleStringProperty(ac == null ? "" : ac);
            this.description = new SimpleStringProperty(desc == null ? "" : desc);
            this.side = new SimpleStringProperty(side == null ? "DEBIT" : side);
            this.kind = new SimpleStringProperty(kind == null ? "FIXED" : kind);
            this.amount = new SimpleStringProperty(amount == null ? "" : amount);
            this.variable = new SimpleStringProperty(variable == null ? "" : variable);
        }
    }

    /**
     * Celda de cuenta para la tabla de líneas de plantilla: ComboBox editable
     * con el Plan General de cuentas (código + nombre), filtrado al teclear, y
     * que persiste solo el CÓDIGO en la línea. Si el asesor teclea un código de
     * tercero (4000xxx/4300xxx) que aún no existe, el backend lo crea al aplicar
     * la plantilla. Misma UX que el editor de asientos manuales.
     */
    private static class TplAccountCell extends javafx.scene.control.TableCell<TplLineRow, String> {
        private final ComboBox<String> combo;
        private final ObservableList<String> allOptions;
        private final List<AccountSummary> accounts;
        private boolean updatingFromFilter = false;

        TplAccountCell(List<AccountSummary> accounts) {
            this.accounts = accounts;
            this.combo = new ComboBox<>();
            this.allOptions = FXCollections.observableArrayList();
            for (AccountSummary a : accounts) allOptions.add(a.code() + "  " + a.name());
            combo.setItems(FXCollections.observableArrayList(allOptions));
            combo.setEditable(true);
            combo.setVisibleRowCount(12);
            combo.setMaxWidth(Double.MAX_VALUE);

            combo.getEditor().textProperty().addListener((obs, oldV, newV) -> {
                if (updatingFromFilter) return;
                String typed = newV == null ? "" : newV.trim();
                if (typed.contains("  ")) return;
                String prefix = typed.toLowerCase();
                ObservableList<String> filtered = FXCollections.observableArrayList();
                for (String opt : allOptions) {
                    if (prefix.isEmpty() || opt.toLowerCase().startsWith(prefix)) filtered.add(opt);
                }
                updatingFromFilter = true;
                try {
                    combo.setItems(filtered);
                    if (!prefix.isEmpty() && !filtered.isEmpty() && !combo.isShowing()) combo.show();
                } finally {
                    updatingFromFilter = false;
                }
            });

            combo.setOnAction(ev -> persist());
            combo.focusedProperty().addListener((obs, had, has) -> { if (had && !has) persist(); });
            combo.getEditor().focusedProperty().addListener((obs, had, has) -> { if (had && !has) persist(); });
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        private void persist() {
            String v = combo.getEditor().getText();
            if (v == null) return;
            String code = v.contains("  ") ? v.substring(0, v.indexOf("  ")).trim() : v.trim();
            if (getTableRow() != null && getTableRow().getItem() != null) {
                TplLineRow row = getTableRow().getItem();
                row.accountCode.set(code);
                if (row.description.get() == null || row.description.get().isBlank()) {
                    for (AccountSummary a : accounts) {
                        if (code.equals(a.code())) { row.description.set(a.name()); break; }
                    }
                }
            }
            setText(code);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) { setGraphic(null); return; }
            String shown = item == null ? "" : item;
            if (item != null && !item.isBlank()) {
                for (AccountSummary a : accounts) {
                    if (item.equals(a.code())) { shown = a.code() + "  " + a.name(); break; }
                }
            }
            updatingFromFilter = true;
            try {
                combo.getEditor().setText(shown);
                combo.setItems(FXCollections.observableArrayList(allOptions));
            } finally {
                updatingFromFilter = false;
            }
            setGraphic(combo);
        }
    }

    /** Editor de plantilla (alta si {@code existing == null}, edición si no). */
    private void openTemplateEditor(AccountingModels.EntryTemplate existing) {
        // Cargar el Plan General de cuentas antes de construir el editor
        // (igual que el editor de asientos manuales) para ofrecer el
        // selector de cuentas + alta de tercero al teclear un 4000/4300.
        async(() -> api.listAccounts(null),
                accounts -> buildTemplateEditor(existing, accounts),
                err -> showError(tt.apply("accounting.error.template_save"), err));
    }

    private void buildTemplateEditor(AccountingModels.EntryTemplate existing,
                                       List<AccountSummary> accounts) {
        boolean isNew = existing == null;
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setResizable(true);
        dlg.setTitle(isNew ? tt.apply("accounting.action.new_template")
                : tt.apply("accounting.action.edit"));
        ButtonType saveBt = new ButtonType(tt.apply("accounting.action.save"), ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField codeField = new TextField(isNew ? "" : existing.code());
        codeField.setDisable(!isNew); // el código no se cambia en edición (backend no lo toca)
        TextField nameField = new TextField(isNew ? "" : existing.name());
        ComboBox<String> categoryCombo = new ComboBox<>(FXCollections.observableArrayList(TEMPLATE_CATEGORIES));
        categoryCombo.setValue(isNew ? "OTHER" : existing.category());
        categoryCombo.setButtonCell(tplCategoryCell());
        categoryCombo.setCellFactory(lv -> tplCategoryCell());
        TextField conceptField = new TextField(isNew ? "" : nz(existing.defaultConcept()));
        TextField descField = new TextField(isNew ? "" : nz(existing.description()));

        GridPane head = new GridPane();
        head.setHgap(8); head.setVgap(6);
        head.addRow(0, new Label(tt.apply("accounting.col.code")), codeField);
        head.addRow(1, new Label(tt.apply("accounting.col.name")), nameField);
        head.addRow(2, new Label(tt.apply("accounting.col.category")), categoryCombo);
        head.addRow(3, new Label(tt.apply("accounting.tpl.default_concept")), conceptField);
        head.addRow(4, new Label(tt.apply("accounting.tpl.description")), descField);
        for (Node n : List.of(nameField, categoryCombo, conceptField, descField)) {
            GridPane.setHgrow(n, Priority.ALWAYS);
            if (n instanceof Region r) r.setMaxWidth(Double.MAX_VALUE);
        }

        // Tabla editable de líneas.
        ObservableList<TplLineRow> lineRows = FXCollections.observableArrayList();
        if (!isNew) {
            for (AccountingModels.EntryTemplateLine l : existing.lines()) {
                String amount = "FIXED".equals(l.amountKind()) && l.fixedAmount() != null
                        ? l.fixedAmount().toPlainString() : "";
                String variable = "VARIABLE".equals(l.amountKind()) ? nz(l.variableName())
                        : "FORMULA".equals(l.amountKind()) ? nz(l.formula()) : "";
                lineRows.add(new TplLineRow(l.accountCode(), l.description(),
                        l.side(), l.amountKind(), amount, variable));
            }
        }
        if (lineRows.isEmpty()) {
            lineRows.add(new TplLineRow("", "", "DEBIT", "FIXED", "", ""));
            lineRows.add(new TplLineRow("", "", "CREDIT", "FIXED", "", ""));
        }

        TableView<TplLineRow> linesTable = new TableView<>(lineRows);
        linesTable.setEditable(true);
        linesTable.setPrefHeight(240);
        // Las columnas se reparten el ancho de la tabla (no se desbordan ni
        // se cortan fuera del diálogo).
        linesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        // Cuenta: selector del Plan General de cuentas (autocompletar por
        // código/nombre) + alta de tercero al teclear un 4000/4300.
        TableColumn<TplLineRow, String> accCol = new TableColumn<>(tt.apply("accounting.col.account"));
        accCol.setPrefWidth(180);
        accCol.setCellValueFactory(cd -> cd.getValue().accountCode);
        accCol.setCellFactory(c -> new TplAccountCell(accounts));

        linesTable.getColumns().addAll(List.of(
                accCol,
                editCol(tt.apply("accounting.col.concept"), r -> r.description, 150),
                comboColLoc(tt.apply("accounting.col.side"), r -> r.side, LINE_SIDES, "accounting.side.", 90),
                comboColLoc(tt.apply("accounting.col.kind"), r -> r.kind, LINE_KINDS, "accounting.kind.", 100),
                editCol(tt.apply("accounting.col.fixed_amount"), r -> r.amount, 110),
                editCol(tt.apply("accounting.col.variable"), r -> r.variable, 130)
        ));

        Button addLine = new Button(tt.apply("accounting.action.add_line"));
        addLine.setOnAction(e -> lineRows.add(new TplLineRow("", "", "DEBIT", "FIXED", "", "")));
        Button removeLine = new Button(tt.apply("accounting.action.remove_line"));
        removeLine.setOnAction(e -> {
            TplLineRow sel = linesTable.getSelectionModel().getSelectedItem();
            if (sel != null) lineRows.remove(sel);
        });
        Label balanceLbl = new Label();
        balanceLbl.setStyle("-fx-text-fill: #6e6e6e;");
        Runnable recalc = () -> balanceLbl.setText(fixedBalanceHint(lineRows));
        lineRows.addListener((javafx.collections.ListChangeListener<TplLineRow>) c -> recalc.run());
        for (TplLineRow r : lineRows) {
            r.amount.addListener((o, a, b) -> recalc.run());
            r.side.addListener((o, a, b) -> recalc.run());
            r.kind.addListener((o, a, b) -> recalc.run());
        }
        recalc.run();

        Label tplHint = new Label(tt.apply("accounting.tpl.lines_hint"));
        tplHint.setStyle("-fx-text-fill: #6e6e6e; -fx-font-style: italic;");
        tplHint.setWrapText(true);

        HBox lineActions = new HBox(8, addLine, removeLine, new Region(), balanceLbl);
        HBox.setHgrow(lineActions.getChildren().get(2), Priority.ALWAYS);
        lineActions.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(10, head, new Separator(),
                new Label(tt.apply("accounting.tpl.lines")), tplHint, linesTable, lineActions);
        content.setPadding(new Insets(4));
        VBox.setVgrow(linesTable, Priority.ALWAYS);
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        dlg.getDialogPane().setContent(sp);
        dlg.getDialogPane().setPrefSize(880, 660);
        dlg.getDialogPane().setMinWidth(720);

        // Validación antes de cerrar con "Guardar".
        Button saveButton = (Button) dlg.getDialogPane().lookupButton(saveBt);
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            String err = validateTemplate(codeField.getText(), nameField.getText(), lineRows);
            if (err != null) {
                ev.consume();
                showError(tt.apply("accounting.error.template_invalid"), err);
            }
        });

        dlg.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            AccountingModels.EntryTemplate payload = new AccountingModels.EntryTemplate(
                    isNew ? null : existing.id(),
                    codeField.getText().trim(), nameField.getText().trim(),
                    categoryCombo.getValue(), blankNull(conceptField.getText()),
                    blankNull(descField.getText()), true, 0, null,
                    toLineRequests(lineRows));
            async(() -> isNew ? api.createTemplate(payload) : api.updateTemplate(existing.id(), payload),
                    saved -> loadTemplates(),
                    err -> showError(tt.apply("accounting.error.template_save"), err));
        });
    }

    /** Diálogo para aplicar la plantilla → genera un asiento. */
    private void openTemplateApply(AccountingModels.EntryTemplate tpl) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setResizable(true);
        dlg.setTitle(tt.apply("accounting.action.apply_template") + " — " + tpl.name());
        ButtonType applyBt = new ButtonType(tt.apply("accounting.action.generate_entry"), ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(applyBt, ButtonType.CANCEL);

        DatePicker datePicker = new DatePicker(LocalDate.now());
        TextField conceptField = new TextField(
                first(tpl.defaultConcept(), tpl.name()));
        CheckBox postNow = new CheckBox(tt.apply("accounting.tpl.post_now"));

        GridPane grid = new GridPane();
        grid.setHgap(8); grid.setVgap(6);
        grid.addRow(0, new Label(tt.apply("accounting.tpl.entry_date")), datePicker);
        grid.addRow(1, new Label(tt.apply("accounting.tpl.concept")), conceptField);
        grid.add(postNow, 1, 2);
        GridPane.setHgrow(conceptField, Priority.ALWAYS);
        conceptField.setMaxWidth(Double.MAX_VALUE);

        // Un campo por cada variable distinta de las líneas VARIABLE/FORMULA.
        java.util.LinkedHashMap<String, TextField> varFields = new java.util.LinkedHashMap<>();
        int rowIdx = 3;
        for (AccountingModels.EntryTemplateLine l : tpl.lines()) {
            String varName = "VARIABLE".equals(l.amountKind()) ? l.variableName()
                    : "FORMULA".equals(l.amountKind()) ? l.formula() : null;
            if (varName == null || varName.isBlank() || varFields.containsKey(varName)) continue;
            TextField vf = new TextField();
            vf.setPromptText("0,00");
            varFields.put(varName, vf);
            grid.addRow(rowIdx++, new Label(varName), vf);
        }
        if (!varFields.isEmpty()) {
            Label vh = new Label(tt.apply("accounting.tpl.variables_hint"));
            vh.setStyle("-fx-text-fill: #6e6e6e; -fx-font-style: italic;");
            grid.add(vh, 0, rowIdx, 2, 1);
        }

        GridPane.setHgrow(conceptField, Priority.ALWAYS);
        VBox content = new VBox(10, grid);
        content.setPadding(new Insets(6));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        dlg.getDialogPane().setContent(sp);
        dlg.getDialogPane().setPrefSize(460, 420);
        dlg.getDialogPane().setMinWidth(420);

        dlg.showAndWait().ifPresent(bt -> {
            if (bt != applyBt) return;
            java.util.Map<String, BigDecimal> vars = new java.util.HashMap<>();
            for (var e : varFields.entrySet()) vars.put(e.getKey(), parse(e.getValue().getText()));
            LocalDate date = datePicker.getValue();
            String concept = conceptField.getText();
            boolean post = postNow.isSelected();
            async(() -> api.applyTemplate(tpl.id(), date, concept, vars, post),
                    entryId -> {
                        loadTemplates();
                        com.benjagest.ui.support.RefreshBus.emit(
                                com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL);
                        showInfo(tt.apply("accounting.tpl.applied_title"),
                                tt.apply("accounting.tpl.applied_body"));
                    },
                    err -> showError(tt.apply("accounting.error.template_apply"), err));
        });
    }

    // ----- helpers ACC-TEMPLATES -----

    private List<AccountingModels.EntryTemplateLine> toLineRequests(List<TplLineRow> rows) {
        List<AccountingModels.EntryTemplateLine> out = new ArrayList<>();
        for (TplLineRow r : rows) {
            String kind = r.kind.get();
            BigDecimal fixed = "FIXED".equals(kind) ? parse(r.amount.get()) : null;
            String variableName = "VARIABLE".equals(kind) ? blankNull(r.variable.get()) : null;
            String formula = "FORMULA".equals(kind) ? blankNull(r.variable.get()) : null;
            out.add(new AccountingModels.EntryTemplateLine(
                    r.accountCode.get().trim(), blankNull(r.description.get()),
                    r.side.get(), kind, fixed, formula, variableName));
        }
        return out;
    }

    private String validateTemplate(String code, String name, List<TplLineRow> rows) {
        if (empty(code)) return tt.apply("accounting.error.code_required");
        if (empty(name)) return tt.apply("accounting.error.name_required");
        if (rows.size() < 2) return tt.apply("accounting.error.min_lines");
        for (TplLineRow r : rows) {
            if (empty(r.accountCode.get())) return tt.apply("accounting.error.account_required");
            if ("VARIABLE".equals(r.kind.get()) && empty(r.variable.get()))
                return tt.apply("accounting.error.variable_required");
            if ("FORMULA".equals(r.kind.get()) && empty(r.variable.get()))
                return tt.apply("accounting.error.formula_required");
        }
        return null;
    }

    /** Pista de cuadre solo sobre líneas FIXED (las VARIABLE no se conocen aún). */
    private String fixedBalanceHint(List<TplLineRow> rows) {
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        boolean hasVariable = false;
        for (TplLineRow r : rows) {
            if (!"FIXED".equals(r.kind.get())) { hasVariable = true; continue; }
            BigDecimal amt = parse(r.amount.get());
            if ("DEBIT".equals(r.side.get())) debit = debit.add(amt);
            else credit = credit.add(amt);
        }
        String base = tt.apply("accounting.tpl.fixed_balance")
                .replace("{d}", debit.toPlainString())
                .replace("{h}", credit.toPlainString());
        if (debit.compareTo(credit) == 0) base += "  ✓";
        if (hasVariable) base += "  " + tt.apply("accounting.tpl.has_variables");
        return base;
    }

    private TableColumn<TplLineRow, String> editCol(String header,
            Function<TplLineRow, SimpleStringProperty> prop, double width) {
        TableColumn<TplLineRow, String> c = new TableColumn<>(header);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> prop.apply(cd.getValue()));
        c.setCellFactory(TextFieldTableCell.forTableColumn());
        c.setOnEditCommit(ev -> prop.apply(ev.getRowValue()).set(ev.getNewValue()));
        return c;
    }

    /**
     * Columna ComboBox cuyo valor interno es un código (DEBIT, FIXED…) pero
     * que SE MUESTRA traducido (Debe, Fijo…) tanto en la celda como en el
     * desplegable, vía {@code i18nPrefix + código}.
     */
    private TableColumn<TplLineRow, String> comboColLoc(String header,
            Function<TplLineRow, SimpleStringProperty> prop, List<String> options,
            String i18nPrefix, double width) {
        TableColumn<TplLineRow, String> c = new TableColumn<>(header);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> prop.apply(cd.getValue()));
        c.setCellFactory(ComboBoxTableCell.forTableColumn(
                codeLabelConverter(i18nPrefix, options),
                FXCollections.observableArrayList(options)));
        c.setOnEditCommit(ev -> prop.apply(ev.getRowValue()).set(ev.getNewValue()));
        return c;
    }

    private javafx.util.StringConverter<String> codeLabelConverter(String i18nPrefix, List<String> codes) {
        return new javafx.util.StringConverter<>() {
            @Override public String toString(String code) {
                return code == null ? "" : tt.apply(i18nPrefix + code);
            }
            @Override public String fromString(String label) {
                for (String code : codes) {
                    if (tt.apply(i18nPrefix + code).equals(label)) return code;
                }
                return label;
            }
        };
    }

    private javafx.scene.control.ListCell<String> tplCategoryCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : tt.apply("accounting.tpl_cat." + item));
            }
        };
    }

    private String nz(String s) { return s == null ? "" : s; }
    private String blankNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    // ====================================================================
    //  Tab: Cuadro de mando financiero (FIN-1)
    // ====================================================================

    private DatePicker finFrom;
    private DatePicker finTo;
    private FlowPane finCards;
    private Label finDraftWarn;
    private HBox finDraftWarnBox;
    private TableView<AccountingModels.MonthPoint> finMonthly;
    private FlowPane finProjection;
    private VBox finRecommendations;

    private static final java.text.NumberFormat MONEY =
            java.text.NumberFormat.getCurrencyInstance(java.util.Locale.forLanguageTag("es-ES"));

    private Node buildFinancialsTab() {
        int y = LocalDate.now().getYear();
        finFrom = new DatePicker(LocalDate.of(y, 1, 1));
        finTo = new DatePicker(LocalDate.now());
        Button refresh = new Button(tt.apply("accounting.action.refresh"));
        refresh.setOnAction(e -> loadFinancials());
        Button exportPdf = new Button(tt.apply("accounting.fin.export_pdf"));
        exportPdf.setOnAction(e -> downloadFinancialsPdf());

        HBox controls = new HBox(8,
                new Label(tt.apply("accounting.fin.from")), finFrom,
                new Label(tt.apply("accounting.fin.to")), finTo, refresh, exportPdf);
        controls.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label(tt.apply("accounting.fin.hint"));
        hint.setStyle("-fx-text-fill: #6e6e6e;");
        hint.setWrapText(true);

        finDraftWarn = new Label();
        finDraftWarn.setStyle("-fx-text-fill: #b8860b;");
        finDraftWarn.setWrapText(true);
        HBox.setHgrow(finDraftWarn, Priority.ALWAYS);
        Button goPending = new Button(tt.apply("accounting.fin.go_pending"));
        goPending.setOnAction(e -> goToPendingTab());
        finDraftWarnBox = new HBox(8, finDraftWarn, goPending);
        finDraftWarnBox.setAlignment(Pos.CENTER_LEFT);
        finDraftWarnBox.setVisible(false);
        finDraftWarnBox.setManaged(false);

        finCards = new FlowPane(14, 14);
        finCards.setPadding(new Insets(6, 0, 0, 0));

        // FIN-2 — evolución mensual del año (del 'Hasta').
        Label evoTitle = new Label(tt.apply("accounting.fin.evolution"));
        evoTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        finMonthly = new TableView<>();
        finMonthly.setPlaceholder(new Label(tt.apply("accounting.fin.no_data")));
        finMonthly.setPrefHeight(360);
        finMonthly.getColumns().addAll(List.of(
                col(tt.apply("accounting.fin.month"), m -> monthName(m.month()), 120),
                col(tt.apply("accounting.fin.income"), m -> money(m.income()), 130),
                col(tt.apply("accounting.fin.expenses"), m -> money(m.expenses()), 130),
                col(tt.apply("accounting.fin.result"), m -> money(m.result()), 130)
        ));

        // FIN-3 — proyección de cierre + IS estimado (del año del 'Hasta').
        Label projTitle = new Label(tt.apply("accounting.fin.projection"));
        projTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        Label projHint = new Label(tt.apply("accounting.fin.projection_hint"));
        projHint.setStyle("-fx-text-fill: #6e6e6e; -fx-font-style: italic;");
        projHint.setWrapText(true);
        finProjection = new FlowPane(14, 14);

        // FIN-4 — recomendaciones (reglas sobre los datos ya cargados).
        Label recTitle = new Label(tt.apply("accounting.fin.recommendations"));
        recTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        Label recHint = new Label(tt.apply("accounting.fin.recommendations_hint"));
        recHint.setStyle("-fx-text-fill: #6e6e6e; -fx-font-style: italic;");
        recHint.setWrapText(true);
        finRecommendations = new VBox(6);

        VBox content = new VBox(12, finCards, new Separator(),
                projTitle, projHint, finProjection, new Separator(),
                recTitle, recHint, finRecommendations, new Separator(),
                evoTitle, finMonthly);
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);

        VBox box = new VBox(10, hint, controls, finDraftWarnBox, sp);
        box.setPadding(new Insets(8));
        VBox.setVgrow(sp, Priority.ALWAYS);
        loadFinancials();
        return box;
    }

    /** Refresca el cuadro de mando si su pestaña ya está construida (auto-refresh). */
    private void reloadFinancialsIfReady() {
        if (finFrom != null && finTo != null) loadFinancials();
    }

    private void loadFinancials() {
        LocalDate from = finFrom.getValue();
        LocalDate to = finTo.getValue();
        if (from == null || to == null) return;
        async(() -> api.clientFinancials(from, to),
                this::renderFinancials,
                err -> logSilent("load-financials", err));
        int year = to.getYear();
        async(() -> api.financialsMonthly(year),
                rows -> finMonthly.setItems(FXCollections.observableArrayList(rows)),
                err -> logSilent("load-financials-monthly", err));
        async(() -> api.financialsProjection(year),
                this::renderProjection,
                err -> logSilent("load-financials-projection", err));
    }

    /** FIN-5 — descarga el informe PDF del cuadro de mando y lo guarda. */
    private void downloadFinancialsPdf() {
        LocalDate from = finFrom.getValue();
        LocalDate to = finTo.getValue();
        if (from == null || to == null) return;
        int year = to.getYear();
        async(() -> api.financialsPdf(from, to, year),
                bytes -> {
                    javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
                    fc.setTitle(tt.apply("accounting.fin.export_pdf"));
                    fc.setInitialFileName("cuadro-mando-" + from + "_" + to + ".pdf");
                    fc.getExtensionFilters().add(
                            new javafx.stage.FileChooser.ExtensionFilter("PDF", "*.pdf"));
                    java.io.File target = fc.showSaveDialog(finCards.getScene() == null
                            ? null : finCards.getScene().getWindow());
                    if (target == null) return;
                    try {
                        java.nio.file.Files.write(target.toPath(), bytes);
                        showInfo(tt.apply("accounting.fin.export_ok_title"),
                                tt.apply("accounting.fin.export_ok_body") + "\n" + target.getAbsolutePath());
                    } catch (Exception ex) {
                        showError(tt.apply("accounting.fin.export_fail"), ex.getMessage());
                    }
                },
                err -> showError(tt.apply("accounting.fin.export_fail"), err));
    }

    /** Descarga genérica de un PDF (informes contables) → FileChooser + guardado. */
    private void savePdf(ApiCall<byte[]> supplier, String suggestedName, Node owner) {
        async(supplier,
                bytes -> {
                    javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
                    fc.setTitle(tt.apply("accounting.fin.export_pdf"));
                    fc.setInitialFileName(suggestedName);
                    fc.getExtensionFilters().add(
                            new javafx.stage.FileChooser.ExtensionFilter("PDF", "*.pdf"));
                    java.io.File target = fc.showSaveDialog(
                            owner.getScene() == null ? null : owner.getScene().getWindow());
                    if (target == null) return;
                    try {
                        java.nio.file.Files.write(target.toPath(), bytes);
                        showInfo(tt.apply("accounting.fin.export_ok_title"),
                                tt.apply("accounting.fin.export_ok_body") + "\n" + target.getAbsolutePath());
                    } catch (Exception ex) {
                        showError(tt.apply("accounting.fin.export_fail"), ex.getMessage());
                    }
                },
                err -> showError(tt.apply("accounting.fin.export_fail"), err));
    }

    private void renderProjection(AccountingModels.ClosingProjection p) {
        finProjection.getChildren().clear();
        finProjection.getChildren().addAll(
                kpiCard(tt.apply("accounting.fin.result_ytd"), money(p.resultToDate()),
                        tt.apply("accounting.fin.months_elapsed").replace("{n}", String.valueOf(p.monthsElapsed())),
                        p.resultToDate().signum() >= 0 ? "#2e7d32" : "#c62828"),
                kpiCard(tt.apply("accounting.fin.projected_result"), money(p.projectedResult()),
                        tt.apply("accounting.fin.year_end"),
                        p.projectedResult().signum() >= 0 ? "#2e7d32" : "#c62828"),
                kpiCard(tt.apply("accounting.fin.estimated_is"), money(p.estimatedCorporateTax()),
                        tt.apply("accounting.fin.is_rate"), "#1565c0"),
                kpiCard(tt.apply("accounting.fin.after_tax"), money(p.projectedAfterTax()), null,
                        p.projectedAfterTax().signum() >= 0 ? "#2e7d32" : "#c62828")
        );
    }

    private String monthName(int m) {
        if (m < 1 || m > 12) return String.valueOf(m);
        return java.time.Month.of(m).getDisplayName(
                java.time.format.TextStyle.FULL,
                java.util.Locale.forLanguageTag("es-ES"));
    }

    private void renderFinancials(AccountingModels.ClientFinancials f) {
        finCards.getChildren().clear();
        finCards.getChildren().addAll(
                kpiCard(tt.apply("accounting.fin.income"), money(f.income()), null, "#2e7d32"),
                kpiCard(tt.apply("accounting.fin.expenses"), money(f.expenses()), null, "#c62828"),
                kpiCard(tt.apply("accounting.fin.result"), money(f.result()),
                        tt.apply("accounting.fin.margin") + " " + pctStr(f.marginPct()),
                        f.result().signum() >= 0 ? "#2e7d32" : "#c62828"),
                kpiCard(tt.apply("accounting.fin.personnel"), money(f.personnelCost()),
                        tt.apply("accounting.fin.over_income") + " " + pctStr(f.personnelRatioPct()), "#1565c0"),
                kpiCard(tt.apply("accounting.fin.expense_ratio"), pctStr(f.expenseRatioPct()),
                        tt.apply("accounting.fin.over_income"), "#6e6e6e"),
                kpiCard(tt.apply("accounting.fin.vat_charged"), money(f.vatCharged()), null, "#6e6e6e"),
                kpiCard(tt.apply("accounting.fin.vat_borne"), money(f.vatBorne()), null, "#6e6e6e"),
                kpiCard(tt.apply("accounting.fin.model303"), money(f.model303Estimated()),
                        tt.apply("accounting.fin.estimated"), "#6e6e6e"),
                kpiCard(tt.apply("accounting.fin.pending_collections"), money(f.pendingCollections()),
                        f.overdueInvoices() > 0
                                ? tt.apply("accounting.fin.overdue").replace("{n}", String.valueOf(f.overdueInvoices()))
                                : null,
                        f.overdueInvoices() > 0 ? "#c62828" : "#1565c0"),
                kpiCard(tt.apply("accounting.fin.pending_payments"), money(f.pendingPayments()),
                        tt.apply("accounting.fin.suppliers"), "#1565c0")
        );
        if (f.draftCount() > 0) {
            finDraftWarn.setText(tt.apply("accounting.fin.draft_warn")
                    .replace("{n}", String.valueOf(f.draftCount())));
            finDraftWarnBox.setVisible(true);
            finDraftWarnBox.setManaged(true);
        } else {
            finDraftWarnBox.setVisible(false);
            finDraftWarnBox.setManaged(false);
        }
        renderRecommendations(f);
    }

    /**
     * FIN-4 — recomendaciones prescriptivas derivadas de las cifras del
     * periodo. Reglas simples y honestas; se presentan como sugerencias a
     * revisar por el asesor (no decisiones automáticas).
     */
    private void renderRecommendations(AccountingModels.ClientFinancials f) {
        finRecommendations.getChildren().clear();
        java.util.List<Node> recs = new ArrayList<>();

        if (f.overdueInvoices() > 0) {
            recs.add(recLine("WARN", tt.apply("accounting.rec.overdue")
                    .replace("{n}", String.valueOf(f.overdueInvoices()))
                    .replace("{x}", money(f.pendingCollections()))));
        }
        if (f.result().signum() < 0) {
            recs.add(recLine("WARN", tt.apply("accounting.rec.loss")
                    .replace("{x}", money(f.result()))));
        }
        if (f.income().signum() > 0 && f.personnelRatioPct().compareTo(new BigDecimal("40")) > 0) {
            recs.add(recLine("WARN", tt.apply("accounting.rec.personnel_high")
                    .replace("{p}", pctStr(f.personnelRatioPct()))));
        }
        if (f.income().signum() > 0 && f.expenseRatioPct().compareTo(new BigDecimal("90")) > 0
                && f.result().signum() >= 0) {
            recs.add(recLine("INFO", tt.apply("accounting.rec.thin_margin")
                    .replace("{p}", pctStr(f.expenseRatioPct()))));
        }
        if (f.model303Estimated().signum() > 0) {
            recs.add(recLine("INFO", tt.apply("accounting.rec.vat_to_pay")
                    .replace("{x}", money(f.model303Estimated()))));
        } else if (f.model303Estimated().signum() < 0) {
            recs.add(recLine("INFO", tt.apply("accounting.rec.vat_to_offset")
                    .replace("{x}", money(f.model303Estimated().abs()))));
        }
        if (f.draftCount() > 0) {
            recs.add(recLine("INFO", tt.apply("accounting.rec.validate_drafts")
                    .replace("{n}", String.valueOf(f.draftCount()))));
        }

        if (recs.isEmpty()) {
            recs.add(recLine("OK", tt.apply("accounting.rec.all_good")));
        }
        finRecommendations.getChildren().addAll(recs);
    }

    private Node recLine(String severity, String text) {
        String color = switch (severity) {
            case "WARN" -> "#c62828";
            case "OK" -> "#2e7d32";
            default -> "#1565c0";
        };
        Label dot = new Label("●");
        dot.setStyle("-fx-text-fill: " + color + ";");
        Label msg = new Label(text);
        msg.setWrapText(true);
        HBox h = new HBox(8, dot, msg);
        HBox.setHgrow(msg, Priority.ALWAYS);
        h.setAlignment(Pos.TOP_LEFT);
        return h;
    }

    /** Tarjeta KPI: título arriba, valor grande con color de acento, subtítulo opcional. */
    private Node kpiCard(String title, String value, String subtitle, String accent) {
        Label t = new Label(title);
        t.setStyle("-fx-text-fill: #6e6e6e; -fx-font-size: 12px;");
        Label v = new Label(value);
        v.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: " + accent + ";");
        VBox card = new VBox(4, t, v);
        if (subtitle != null && !subtitle.isBlank()) {
            Label s = new Label(subtitle);
            s.setStyle("-fx-text-fill: #6e6e6e; -fx-font-size: 11px;");
            card.getChildren().add(s);
        }
        card.setPadding(new Insets(12, 16, 12, 16));
        card.setPrefWidth(210);
        card.setStyle("-fx-background-color: white; -fx-border-color: #e0e0e0;"
                + " -fx-border-radius: 6; -fx-background-radius: 6;");
        return card;
    }

    private String money(BigDecimal v) {
        return MONEY.format(v == null ? BigDecimal.ZERO : v);
    }

    private String pctStr(BigDecimal v) {
        return (v == null ? "0" : v.toPlainString()) + " %";
    }

    // ====================================================================
    //  Tab: Cierre de ejercicio (CONS-CIERRE)
    // ====================================================================

    private TableView<FiscalYearCloseEntry> closesTable;
    private VBox closeDetailBox;

    private Node buildYearCloseTab() {
        int currentYear = LocalDate.now().getYear();

        ComboBox<Integer> yearCombo = new ComboBox<>();
        for (int y = currentYear; y >= currentYear - 6; y--) yearCombo.getItems().add(y);
        yearCombo.setValue(currentYear - 1); // se cierra el ejercicio anterior
        yearCombo.setPrefWidth(110);

        Button precalc = new Button(tt.apply("accounting.yc.precalculate"));
        precalc.getStyleClass().add("button-primary");
        precalc.setOnAction(e -> {
            Integer y = yearCombo.getValue();
            if (y == null) return;
            async(() -> api.precalculateYear(y),
                    entry -> { loadYearCloses(); selectYearInTable(y); },
                    err -> showError(tt.apply("accounting.yc.error_precalculate"), err));
        });

        Button preview = new Button(tt.apply("accounting.yc.preview_reg"));
        preview.setOnAction(e -> {
            Integer y = yearCombo.getValue();
            if (y == null) return;
            async(() -> api.previewRegularization(y),
                    this::showRegularizationPreview,
                    err -> showError(tt.apply("accounting.yc.error_preview"), err));
        });

        Button refresh = new Button(tt.apply("accounting.action.refresh"));
        refresh.setOnAction(e -> loadYearCloses());

        HBox toolbar = new HBox(8,
                new Label(tt.apply("accounting.yc.fiscal_year")), yearCombo,
                precalc, preview, refresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        closesTable = new TableView<>();
        closesTable.setPlaceholder(new Label(tt.apply("accounting.yc.empty")));
        closesTable.getColumns().addAll(List.of(
                col(tt.apply("accounting.yc.col.year"), c -> String.valueOf(c.periodYear()), 80),
                col(tt.apply("accounting.yc.col.status"), c -> tt.apply("accounting.yc.status." + c.status()), 120),
                colMoney(tt.apply("accounting.yc.col.income"), FiscalYearCloseEntry::incomeTotal, 120),
                colMoney(tt.apply("accounting.yc.col.expense"), FiscalYearCloseEntry::expenseTotal, 120),
                colMoney(tt.apply("accounting.yc.col.result"), FiscalYearCloseEntry::resultAmount, 120),
                colMoney(tt.apply("accounting.yc.col.tax"), FiscalYearCloseEntry::taxAmount, 110),
                colMoney(tt.apply("accounting.yc.col.after_tax"), FiscalYearCloseEntry::resultAfterTax, 120),
                col(tt.apply("accounting.yc.col.closed_at"), c -> c.closedAt() == null ? "" : c.closedAt(), 160)
        ));
        closesTable.getSelectionModel().selectedItemProperty().addListener(
                (o, a, b) -> renderCloseDetail(b));

        closeDetailBox = new VBox(8);
        closeDetailBox.setPadding(new Insets(8, 0, 0, 0));

        Label hint = new Label(tt.apply("accounting.yc.hint"));
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #6e6e6e;");

        VBox box = new VBox(10, hint, toolbar, closesTable, new Separator(), closeDetailBox);
        VBox.setVgrow(closesTable, Priority.ALWAYS);
        box.setPadding(new Insets(8));
        loadYearCloses();
        return box;
    }

    private void loadYearCloses() {
        async(api::listYearCloses,
                rows -> {
                    closesTable.setItems(FXCollections.observableArrayList(rows));
                    renderCloseDetail(closesTable.getSelectionModel().getSelectedItem());
                },
                err -> logSilent("year-close-load", err));
    }

    private void selectYearInTable(int year) {
        for (FiscalYearCloseEntry e : closesTable.getItems()) {
            if (e.periodYear() == year) { closesTable.getSelectionModel().select(e); break; }
        }
    }

    /** Pinta el panel de detalle/cierre para el ejercicio seleccionado. */
    private void renderCloseDetail(FiscalYearCloseEntry e) {
        if (closeDetailBox == null) return;
        closeDetailBox.getChildren().clear();
        if (e == null) {
            Label none = new Label(tt.apply("accounting.yc.select_hint"));
            none.setStyle("-fx-text-fill: #6e6e6e; -fx-font-style: italic;");
            closeDetailBox.getChildren().add(none);
            return;
        }

        Label title = new Label(tt.apply("accounting.yc.detail_title").replace("{y}", String.valueOf(e.periodYear())));
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        VBox figures = new VBox(3,
                kv(tt.apply("accounting.yc.col.income"), eur(e.incomeTotal())),
                kv(tt.apply("accounting.yc.col.expense"), eur(e.expenseTotal())),
                kv(tt.apply("accounting.yc.col.result"), eur(e.resultAmount())),
                kv(tt.apply("accounting.yc.col.tax"), eur(e.taxAmount())),
                kv(tt.apply("accounting.yc.col.after_tax"), eur(e.resultAfterTax())));

        if ("CLOSED".equals(e.status())) {
            Label closed = new Label(tt.apply("accounting.yc.is_closed"));
            closed.setStyle("-fx-text-fill: #1e7e34; -fx-font-weight: bold;");
            Button reopen = new Button(tt.apply("accounting.yc.reopen"));
            reopen.setOnAction(ev -> confirmReopen(e));
            closeDetailBox.getChildren().addAll(title, figures, closed, reopen);
            return;
        }

        // PRE_CLOSE / REOPENED → permitir cerrar con aplicación de resultado.
        BigDecimal after = e.resultAfterTax() == null ? BigDecimal.ZERO : e.resultAfterTax();
        boolean profit = after.signum() >= 0;

        TextField reservesField = new TextField(profit ? after.toPlainString() : "0");
        TextField dividendsField = new TextField("0");
        TextField lossesField = new TextField(profit ? "0" : after.toPlainString());
        reservesField.setPrefWidth(140);
        dividendsField.setPrefWidth(140);
        lossesField.setPrefWidth(140);

        Label balanceHint = new Label();
        Runnable updateBalance = () -> {
            BigDecimal sum = parse(reservesField.getText())
                    .add(parse(dividendsField.getText()))
                    .add(parse(lossesField.getText()));
            BigDecimal diff = after.subtract(sum);
            if (diff.abs().compareTo(new BigDecimal("0.01")) <= 0) {
                balanceHint.setText(tt.apply("accounting.yc.balanced"));
                balanceHint.setStyle("-fx-text-fill: #1e7e34;");
            } else {
                balanceHint.setText(tt.apply("accounting.yc.unbalanced").replace("{d}", eur(diff)));
                balanceHint.setStyle("-fx-text-fill: #b00020;");
            }
        };
        reservesField.textProperty().addListener((o, a, b) -> updateBalance.run());
        dividendsField.textProperty().addListener((o, a, b) -> updateBalance.run());
        lossesField.textProperty().addListener((o, a, b) -> updateBalance.run());
        updateBalance.run();

        Label allocTitle = new Label(tt.apply("accounting.yc.allocation_title"));
        allocTitle.setStyle("-fx-font-weight: bold;");

        Button closeBtn = new Button(tt.apply("accounting.yc.close"));
        closeBtn.getStyleClass().add("button-primary");
        closeBtn.setOnAction(ev -> {
            BigDecimal sum = parse(reservesField.getText())
                    .add(parse(dividendsField.getText()))
                    .add(parse(lossesField.getText()));
            if (after.subtract(sum).abs().compareTo(new BigDecimal("0.01")) > 0) {
                showError(tt.apply("accounting.yc.error_close"),
                        tt.apply("accounting.yc.unbalanced").replace("{d}", eur(after.subtract(sum))));
                return;
            }
            Alert confirm = new Alert(AlertType.CONFIRMATION,
                    tt.apply("accounting.yc.confirm_close").replace("{y}", String.valueOf(e.periodYear())),
                    ButtonType.YES, ButtonType.NO);
            confirm.setHeaderText(null);
            confirm.showAndWait().ifPresent(bt -> {
                if (bt != ButtonType.YES) return;
                async(() -> api.closeYear(e.periodYear(),
                                parse(reservesField.getText()),
                                parse(dividendsField.getText()),
                                parse(lossesField.getText()), null),
                        done -> {
                            loadYearCloses();
                            selectYearInTable(e.periodYear());
                            new Alert(AlertType.INFORMATION,
                                    tt.apply("accounting.yc.closed_ok").replace("{y}", String.valueOf(e.periodYear())))
                                    .showAndWait();
                        },
                        err -> showError(tt.apply("accounting.yc.error_close"), err));
            });
        });

        closeDetailBox.getChildren().addAll(title, figures, new Separator(),
                allocTitle,
                row(tt.apply("accounting.yc.reserves"), reservesField),
                row(tt.apply("accounting.yc.dividends"), dividendsField),
                row(tt.apply("accounting.yc.losses"), lossesField),
                balanceHint, closeBtn);
    }

    private void confirmReopen(FiscalYearCloseEntry e) {
        Alert confirm = new Alert(AlertType.WARNING,
                tt.apply("accounting.yc.confirm_reopen").replace("{y}", String.valueOf(e.periodYear())),
                ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.YES) return;
            async(() -> api.reopenYear(e.periodYear(), "Reapertura desde UI"),
                    done -> { loadYearCloses(); selectYearInTable(e.periodYear()); },
                    err -> showError(tt.apply("accounting.yc.error_reopen"), err));
        });
    }

    private void showRegularizationPreview(RegularizationPreviewEntry p) {
        String body = tt.apply("accounting.yc.col.income") + ":  " + eur(p.incomesTotal()) + "\n"
                + tt.apply("accounting.yc.col.expense") + ":  " + eur(p.expensesTotal()) + "\n"
                + tt.apply("accounting.yc.col.result") + ":  " + eur(p.resultAmount());
        Alert a = new Alert(AlertType.INFORMATION, body);
        a.setHeaderText(tt.apply("accounting.yc.preview_title").replace("{y}", String.valueOf(p.periodYear())));
        a.showAndWait();
    }

    private TableColumn<FiscalYearCloseEntry, String> colMoney(
            String header, Function<FiscalYearCloseEntry, BigDecimal> getter, double width) {
        return col(header, e -> eur(getter.apply(e)), width);
    }

    private HBox kv(String k, String v) {
        Label key = new Label(k + ":");
        key.setMinWidth(170);
        key.setStyle("-fx-text-fill: #6e6e6e;");
        Label val = new Label(v);
        val.setStyle("-fx-font-weight: bold;");
        HBox h = new HBox(8, key, val);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** Muestra un ComboBox de códigos con etiqueta traducida (prefijo + código).
     *  El valor seleccionado sigue siendo el código (lo que espera el backend). */
    private void localizeCombo(ComboBox<String> combo, String prefix) {
        combo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String code) { return code == null ? "" : tt.apply(prefix + code); }
            @Override public String fromString(String s) { return s; }
        });
    }

    private String eur(BigDecimal v) {
        if (v == null) return "0,00 €";
        return String.format(java.util.Locale.of("es", "ES"), "%,.2f €", v);
    }

    // ====================================================================
    //  Tab: Recurrentes
    // ====================================================================

    private TableView<RecurringTask> recurringTable;

    private Node buildRecurringTab() {
        recurringTable = new TableView<>();
        recurringTable.getColumns().addAll(List.of(
                col(tt.apply("accounting.col.rec_kind"), r -> tt.apply("accounting.rec_kind." + r.kind()), 150),
                col(tt.apply("accounting.col.name"), RecurringTask::name, 200),
                col(tt.apply("accounting.col.frequency"), r -> tt.apply("accounting.frequency." + r.frequency()), 110),
                col(tt.apply("accounting.col.day"), r -> r.dayOfMonth() == null ? "" : String.valueOf(r.dayOfMonth()), 50),
                col(tt.apply("accounting.col.next_run"), r -> r.nextRunDate() == null ? "" : r.nextRunDate().toString(), 110),
                col(tt.apply("accounting.col.last_run"), r -> r.lastRunDate() == null ? "" : r.lastRunDate().toString(), 110),
                col(tt.apply("accounting.col.status"), r -> r.lastRunStatus() == null ? "" : tt.apply("accounting.run_status." + r.lastRunStatus()), 80),
                col(tt.apply("accounting.col.times_run"), r -> String.valueOf(r.timesRun()), 80),
                col(tt.apply("accounting.col.times_failed"), r -> String.valueOf(r.timesFailed()), 70),
                col(tt.apply("accounting.col.active"), r -> r.active() ? "✓" : "✗", 60)
        ));

        Button refresh = new Button(tt.apply("accounting.action.refresh"));
        refresh.setOnAction(e -> loadRecurring());
        Button runNow = new Button(tt.apply("accounting.action.run_now"));
        runNow.setOnAction(e -> {
            RecurringTask sel = recurringTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            async(() -> { api.runRecurringNow(sel.id(), LocalDate.now()); return null; },
                    v -> loadRecurring(),
                    err -> showError(tt.apply("accounting.error.run_now"), err));
        });
        Button toggle = new Button(tt.apply("accounting.action.toggle"));
        toggle.setOnAction(e -> {
            RecurringTask sel = recurringTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            async(() -> { api.setRecurringActive(sel.id(), !sel.active()); return null; },
                    v -> loadRecurring(),
                    err -> showError(tt.apply("accounting.error.toggle"), err));
        });

        HBox actions = new HBox(8, refresh, runNow, toggle);
        Label hint = new Label(tt.apply("accounting.recurring.hint"));
        hint.setStyle("-fx-text-fill: #6e6e6e;");
        VBox box = new VBox(8, hint, actions, recurringTable);
        VBox.setVgrow(recurringTable, Priority.ALWAYS);
        box.setPadding(new Insets(8));
        loadRecurring();
        return box;
    }

    private void loadRecurring() {
        async(() -> api.listRecurring(null, null),
                rows -> recurringTable.setItems(FXCollections.observableArrayList(rows)),
                err -> logSilent("load", err));
    }

    // ====================================================================
    //  Editor de asiento (DRAFT review + manual)
    // ====================================================================

    private void openEntryEditor(String entryId) {
        async(() -> {
            JournalEntryDetail detail = entryId == null ? null : api.getEntry(entryId);
            List<AccountSummary> accounts = api.listAccounts(null);
            return new Object[]{detail, accounts};
        }, result -> {
            JournalEntryDetail detail = (JournalEntryDetail) result[0];
            @SuppressWarnings("unchecked")
            List<AccountSummary> accounts = (List<AccountSummary>) result[1];
            buildEntryDialog(detail, accounts).show();
        }, err -> showError(tt.apply("accounting.error.load"), err));
    }

    private javafx.stage.Stage buildEntryDialog(JournalEntryDetail detail, List<AccountSummary> accounts) {
        DatePicker datePicker = new DatePicker(
                detail == null ? LocalDate.now() : detail.entryDate());
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(datePicker);
        TextArea conceptArea = new TextArea(detail == null ? "" : detail.concept());
        conceptArea.setPrefRowCount(2);

        TableView<EditableLine> linesTable = new TableView<>();
        linesTable.setEditable(true);
        ObservableList<EditableLine> lines = FXCollections.observableArrayList();
        if (detail != null) {
            for (JournalLine l : detail.lines()) {
                lines.add(EditableLine.from(l));
            }
        }
        if (lines.isEmpty()) {
            lines.add(new EditableLine());
            lines.add(new EditableLine());
        }
        linesTable.setItems(lines);

        // ME-2/ME-3 — paneles de asistencia (facturas pendientes del tercero +
        // cuentas sugeridas). Se rellenan al confirmar una cuenta.
        VBox openInvBox = new VBox(2);
        FlowPane suggestPane = new FlowPane(6, 6);
        Label suggestTitle = new Label(tt.apply("accounting.assist.suggestions"));
        suggestTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        VBox assistBox = new VBox(6, openInvBox, suggestTitle, suggestPane);
        assistBox.setVisible(false);
        assistBox.setManaged(false);

        // Holder para refrescar los totales tras rellenar líneas desde una
        // factura (refreshTotals se define más abajo; aquí aún no existe).
        final Runnable[] refreshHolder = new Runnable[1];

        java.util.function.Consumer<String> loadAssist = code -> {
            if (code == null || code.isBlank()) return;
            java.util.List<String> present = new java.util.ArrayList<>();
            for (EditableLine l : lines) {
                String c = l.accountCodeProp.get();
                if (c != null && !c.isBlank()) present.add(c);
            }
            boolean tercero = code.startsWith("43") || code.startsWith("44")
                    || code.startsWith("40") || code.startsWith("41");
            if (tercero) {
                async(() -> api.openInvoicesForAccount(code),
                        invs -> renderEntryOpenInvoices(openInvBox, invs, lines, code, accounts,
                                () -> { if (refreshHolder[0] != null) refreshHolder[0].run(); }),
                        err -> openInvBox.getChildren().clear());
            } else {
                openInvBox.getChildren().clear();
            }
            async(() -> api.suggestAccounts(code, present),
                    sugg -> renderEntrySuggestions(suggestPane, suggestTitle, sugg, lines),
                    err -> { suggestPane.getChildren().clear(); });
            assistBox.setVisible(true);
            assistBox.setManaged(true);
        };

        TableColumn<EditableLine, String> accCol = new TableColumn<>(tt.apply("accounting.col.account"));
        accCol.setPrefWidth(220);
        accCol.setCellValueFactory(c -> c.getValue().accountCodeProp);
        // Editable via ComboBox de cuentas. ME-2/ME-3: al confirmar, asistencia.
        accCol.setCellFactory(c -> {
            AccountComboCell cell = new AccountComboCell(accounts);
            cell.setOnCommit(loadAssist);
            return cell;
        });

        TableColumn<EditableLine, String> descCol = new TableColumn<>(tt.apply("accounting.col.description"));
        descCol.setPrefWidth(260);
        descCol.setCellValueFactory(c -> c.getValue().descriptionProp);
        descCol.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        descCol.setOnEditCommit(e -> e.getRowValue().descriptionProp.set(e.getNewValue()));

        TableColumn<EditableLine, String> debitCol = new TableColumn<>(tt.apply("accounting.col.debit"));
        debitCol.setPrefWidth(110);
        debitCol.setCellValueFactory(c -> c.getValue().debitProp);
        debitCol.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        debitCol.setOnEditCommit(e -> e.getRowValue().debitProp.set(e.getNewValue()));

        TableColumn<EditableLine, String> creditCol = new TableColumn<>(tt.apply("accounting.col.credit"));
        creditCol.setPrefWidth(110);
        creditCol.setCellValueFactory(c -> c.getValue().creditProp);
        creditCol.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        creditCol.setOnEditCommit(e -> e.getRowValue().creditProp.set(e.getNewValue()));

        linesTable.getColumns().addAll(List.of(accCol, descCol, debitCol, creditCol));

        // ME-1 — Tab recorre la FILA (cuenta → descripción → debe → haber) y,
        // al final, salta a la cuenta de la siguiente línea (creándola si es la
        // última). Shift+Tab va hacia atrás. Sin esto, la traversal por defecto
        // de JavaFX baja a la cuenta de la línea siguiente.
        linesTable.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() != javafx.scene.input.KeyCode.TAB) return;
            ev.consume();
            int nCols = linesTable.getColumns().size();
            int row, col;
            javafx.scene.control.TablePosition<EditableLine, ?> editing = linesTable.getEditingCell();
            if (editing != null) {
                row = editing.getRow();
                col = linesTable.getColumns().indexOf(editing.getTableColumn());
            } else {
                var f = linesTable.getFocusModel().getFocusedCell();
                row = Math.max(0, f.getRow());
                col = f.getTableColumn() == null ? 0 : linesTable.getColumns().indexOf(f.getTableColumn());
                if (col < 0) col = 0;
            }
            int next = col + (ev.isShiftDown() ? -1 : 1);
            int nextRow = row;
            if (next >= nCols) { next = 0; nextRow = row + 1; }
            else if (next < 0) { next = nCols - 1; nextRow = row - 1; }
            if (nextRow < 0) { nextRow = 0; next = 0; }
            if (nextRow >= lines.size()) lines.add(new EditableLine());
            final int fr = nextRow;
            final TableColumn<EditableLine, ?> fc = linesTable.getColumns().get(next);
            javafx.application.Platform.runLater(() -> {
                linesTable.getSelectionModel().clearAndSelect(fr, fc);
                linesTable.getFocusModel().focus(fr, fc);
                linesTable.scrollTo(fr);
                linesTable.edit(fr, fc);
            });
        });

        Label totals = new Label();
        Runnable refreshTotals = () -> {
            BigDecimal sumD = BigDecimal.ZERO;
            BigDecimal sumC = BigDecimal.ZERO;
            for (EditableLine l : lines) {
                sumD = sumD.add(parse(l.debitProp.get()));
                sumC = sumC.add(parse(l.creditProp.get()));
            }
            BigDecimal diff = sumD.subtract(sumC);
            totals.setText("Debe: " + sumD + "   Haber: " + sumC + "   Diferencia: " + diff);
            totals.setStyle(diff.abs().compareTo(new BigDecimal("0.01")) <= 0
                    ? "-fx-text-fill: green;" : "-fx-text-fill: #b00;");
        };
        // Refresh totals al editar.
        lines.addListener((javafx.collections.ListChangeListener<EditableLine>) c -> refreshTotals.run());
        linesTable.setOnKeyReleased(e -> refreshTotals.run());
        linesTable.setOnMouseClicked(e -> refreshTotals.run());
        refreshTotals.run();
        refreshHolder[0] = refreshTotals; // ME-2 fase 2: refrescar totales al rellenar desde factura

        Button addLine = new Button("+ línea");
        addLine.setOnAction(e -> { lines.add(new EditableLine()); refreshTotals.run(); });
        Button removeLine = new Button("- línea");
        removeLine.setOnAction(e -> {
            EditableLine sel = linesTable.getSelectionModel().getSelectedItem();
            if (sel != null) { lines.remove(sel); refreshTotals.run(); }
        });

        Button save = new Button(tt.apply("accounting.action.save_draft"));
        Button post = new Button(tt.apply("accounting.action.validate"));
        Button cancel = new Button(tt.apply("accounting.action.close"));

        javafx.stage.Stage dialog = new javafx.stage.Stage();
        dialog.setTitle(detail == null
                ? tt.apply("accounting.dialog.new_entry")
                : tt.apply("accounting.dialog.review_entry") + " #" + detail.entryNumber());

        save.setOnAction(e -> persistEntry(detail, datePicker.getValue(),
                conceptArea.getText(), lines, accounts, false, dialog));
        post.setOnAction(e -> persistEntry(detail, datePicker.getValue(),
                conceptArea.getText(), lines, accounts, true, dialog));
        cancel.setOnAction(e -> dialog.close());

        HBox actions = new HBox(8, save, post, cancel, addLine, removeLine);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(4,
                row(tt.apply("accounting.field.date"), datePicker),
                row(tt.apply("accounting.field.concept"), conceptArea));

        if (detail != null && detail.autoProposed()) {
            Label propBadge = new Label(tt.apply("accounting.badge.auto_proposed")
                    + (detail.proposedConfidence() == null
                            ? "" : "  (" + detail.proposedConfidence() + "%)"));
            propBadge.setStyle("-fx-background-color: #fff3cd; -fx-padding: 4 8; "
                    + "-fx-background-radius: 4; -fx-text-fill: #856404;");
            header.getChildren().add(0, propBadge);
        }

        BorderPane body = new BorderPane();
        body.setPadding(new Insets(12));
        body.setTop(header);
        body.setCenter(linesTable);
        body.setBottom(new VBox(6, assistBox, totals, actions));

        // Visor PDF embebido si el asiento es DRAFT y tiene PDF asociado
        // (típico en asientos creados por multi-import de gastos/ventas).
        // Hacemos un GET asíncrono al endpoint del PDF; si llega bytes
        // (200), envolvemos el body en SplitPane con visor a la izquierda.
        // Si responde 404 o el asiento no es DRAFT, no pasa nada — el
        // diálogo sale como antes. Una vez validado, el PDF queda
        // archivado pero la UI no lo muestra.
        javafx.scene.Parent rootForScene = body;
        javafx.scene.control.SplitPane split = null;
        com.benjagest.ui.support.PdfViewer viewer = null;
        if (detail != null && "DRAFT".equalsIgnoreCase(detail.status())) {
            viewer = new com.benjagest.ui.support.PdfViewer();
            viewer.setPrefWidth(550);
            viewer.attachAutoDispose();
            split = new javafx.scene.control.SplitPane(viewer, body);
            split.setDividerPositions(0.50);
            rootForScene = split;
        }
        final com.benjagest.ui.support.PdfViewer finalViewer = viewer;
        final javafx.scene.control.SplitPane finalSplit = split;
        if (finalViewer != null) {
            // Background fetch: si llegan bytes, los carga; si no, ocultamos
            // el panel izquierdo del SplitPane para no dejar espacio vacío.
            final String entryIdFetch = detail.id();
            Thread fetch = new Thread(() -> {
                try {
                    byte[] bytes = api.downloadEntrySourcePdf(entryIdFetch);
                    javafx.application.Platform.runLater(() -> {
                        if (bytes != null && bytes.length > 0) {
                            finalViewer.loadFromBytes(bytes);
                        } else if (finalSplit != null) {
                            finalSplit.getItems().remove(finalViewer);
                        }
                    });
                } catch (Exception ex) {
                    // 404 normal cuando el asiento no tiene PDF: ocultar.
                    javafx.application.Platform.runLater(() -> {
                        if (finalSplit != null) {
                            finalSplit.getItems().remove(finalViewer);
                        }
                    });
                }
            }, "entry-source-pdf-fetch");
            fetch.setDaemon(true);
            fetch.start();
        }

        int w = rootForScene == body ? 900 : 1400;
        javafx.scene.Scene scene = new javafx.scene.Scene(rootForScene, w, 600);
        dialog.setScene(scene);
        return dialog;
    }

    /**
     * ME-2 — pinta las facturas pendientes del tercero bajo el asiento. Cada
     * una es CLICABLE (fase 2): al pulsarla, rellena la línea del tercero y la
     * contrapartida de tesorería (572) con el importe en el debe/haber correcto.
     */
    private void renderEntryOpenInvoices(VBox box,
            java.util.List<AccountingModels.OpenInvoice> invs,
            ObservableList<EditableLine> lines, String terceroCode,
            List<AccountSummary> accounts, Runnable onChange) {
        box.getChildren().clear();
        if (invs == null || invs.isEmpty()) return;
        Label title = new Label(tt.apply("accounting.assist.open_invoices"));
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        box.getChildren().add(title);
        int shown = 0;
        for (AccountingModels.OpenInvoice oi : invs) {
            if (shown++ >= 8) {
                Label more = new Label("… +" + (invs.size() - 8));
                more.setStyle("-fx-text-fill:#6e6e6e; -fx-font-size:12px;");
                box.getChildren().add(more);
                break;
            }
            String text = (oi.number() == null ? "" : oi.number()) + "   ·   "
                    + (oi.date() == null ? "" : oi.date().toString()) + "   ·   "
                    + tt.apply("accounting.assist.pending") + " " + eur(oi.pending());
            javafx.scene.control.Hyperlink link = new javafx.scene.control.Hyperlink(text);
            link.setStyle("-fx-font-size:12px;");
            link.setOnAction(e -> applyOpenInvoice(lines, terceroCode, oi, accounts, onChange));
            box.getChildren().add(link);
        }
    }

    /**
     * Rellena el asiento a partir de una factura pendiente del tercero:
     * - Cliente (43x): Haber en la cuenta del cliente + Debe en tesorería (572).
     * - Proveedor (40x/41x): Debe en la cuenta del proveedor + Haber en tesorería.
     * El importe = pendiente de la factura. Tesorería por defecto 572 (banco);
     * el usuario puede cambiarla a 570 (caja) o editar lo que quiera.
     */
    private void applyOpenInvoice(ObservableList<EditableLine> lines, String terceroCode,
            AccountingModels.OpenInvoice oi, List<AccountSummary> accounts, Runnable onChange) {
        boolean cliente = terceroCode.startsWith("43") || terceroCode.startsWith("44");
        BigDecimal amt = oi.pending() == null ? BigDecimal.ZERO : oi.pending();
        String amtStr = amt.toPlainString();
        String concept = (cliente ? "Cobro factura " : "Pago factura ")
                + (oi.number() == null ? "" : oi.number());
        // Cuenta de tesorería REAL (572 banco) — resuelta del plan de la empresa.
        String treasuryCode = resolveCodeByPrefix(accounts, "572");

        // Línea del tercero (la que ya tiene su cuenta) o la primera en blanco.
        EditableLine tline = findLineByCode(lines, terceroCode);
        if (tline == null) { tline = firstBlankOrAdd(lines); tline.accountCodeProp.set(terceroCode); }
        if (cliente) { tline.creditProp.set(amtStr); tline.debitProp.set(""); }
        else { tline.debitProp.set(amtStr); tline.creditProp.set(""); }
        if (isBlank(tline.descriptionProp.get())) tline.descriptionProp.set(concept);

        // Contrapartida de tesorería (572 banco por defecto) en el lado opuesto.
        EditableLine treas = firstBlankOrAdd(lines);
        treas.accountCodeProp.set(treasuryCode);
        if (cliente) { treas.debitProp.set(amtStr); treas.creditProp.set(""); }
        else { treas.creditProp.set(amtStr); treas.debitProp.set(""); }
        if (isBlank(treas.descriptionProp.get())) treas.descriptionProp.set(concept);

        if (onChange != null) onChange.run();
    }

    /** Resuelve un código de cuenta existente por prefijo (exacto o la subcuenta más corta). */
    private String resolveCodeByPrefix(List<AccountSummary> accounts, String prefix) {
        if (accounts != null) {
            for (AccountSummary a : accounts) if (prefix.equals(a.code())) return a.code();
            String best = null;
            for (AccountSummary a : accounts) {
                if (a.code() != null && a.code().startsWith(prefix)
                        && (best == null || a.code().length() < best.length())) best = a.code();
            }
            if (best != null) return best;
        }
        return prefix;
    }

    private EditableLine findLineByCode(ObservableList<EditableLine> lines, String code) {
        for (EditableLine l : lines) if (code.equals(l.accountCodeProp.get())) return l;
        return null;
    }

    private EditableLine firstBlankOrAdd(ObservableList<EditableLine> lines) {
        for (EditableLine l : lines) {
            if (isBlank(l.accountCodeProp.get())) return l;
        }
        EditableLine l = new EditableLine();
        lines.add(l);
        return l;
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** ME-3 — pinta las cuentas sugeridas como botones; un clic rellena una línea. */
    private void renderEntrySuggestions(FlowPane pane, Label title,
            java.util.List<AccountingModels.SuggestedAccount> sugg,
            ObservableList<EditableLine> lines) {
        pane.getChildren().clear();
        boolean any = sugg != null && !sugg.isEmpty();
        title.setVisible(any);
        title.setManaged(any);
        if (!any) return;
        for (AccountingModels.SuggestedAccount s : sugg) {
            Button b = new Button(s.code() + "  " + (s.name() == null ? "" : s.name()));
            b.setStyle("-fx-background-color:#eef2ff; -fx-text-fill:#1e3a8a; -fx-font-size:12px;");
            b.setOnAction(e -> applyEntrySuggestion(lines, s.code(), s.name()));
            pane.getChildren().add(b);
        }
    }

    /** Rellena la primera línea en blanco (o añade una) con la cuenta sugerida. */
    private void applyEntrySuggestion(ObservableList<EditableLine> lines, String code, String name) {
        EditableLine target = null;
        for (EditableLine l : lines) {
            if (l.accountCodeProp.get() == null || l.accountCodeProp.get().isBlank()) { target = l; break; }
        }
        if (target == null) { target = new EditableLine(); lines.add(target); }
        target.accountCodeProp.set(code);
        if ((target.descriptionProp.get() == null || target.descriptionProp.get().isBlank()) && name != null) {
            target.descriptionProp.set(name);
        }
    }

    private void persistEntry(JournalEntryDetail original, LocalDate entryDate,
                                String concept, ObservableList<EditableLine> editable,
                                List<AccountSummary> accounts, boolean post,
                                javafx.stage.Stage dialog) {
        // Mapear líneas editables a JournalLine resolviendo accountId por code.
        Map<String, AccountSummary> byCode = new java.util.HashMap<>();
        for (AccountSummary a : accounts) byCode.put(a.code(), a);

        List<JournalLine> lines = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int idx = 0;
        for (EditableLine el : editable) {
            idx++;
            String code = el.accountCodeProp.get();
            if (code == null || code.isBlank()) {
                if (parse(el.debitProp.get()).signum() == 0
                        && parse(el.creditProp.get()).signum() == 0) continue;
                errors.add("Línea " + idx + ": falta cuenta");
                continue;
            }
            AccountSummary a = byCode.get(code);
            if (a == null) {
                // Si el código no está en el catálogo cargado pero PARECE
                // sub-cuenta de tercero (4000xxx proveedor / 4300xxx
                // cliente), no bloqueamos: el backend la auto-creará
                // tomando el nombre del tercero del concepto del asiento.
                // Esto replica el flujo "crea el tercero" que hace CONTENDO.
                if (looksLikeTerceroCode(code)) {
                    lines.add(new JournalLine(null, null, code, null,
                            el.descriptionProp.get(),
                            parse(el.debitProp.get()), parse(el.creditProp.get())));
                    continue;
                }
                errors.add("Línea " + idx + ": cuenta " + code + " no existe");
                continue;
            }
            lines.add(new JournalLine(null, a.id(), a.code(), a.name(),
                    el.descriptionProp.get(),
                    parse(el.debitProp.get()), parse(el.creditProp.get())));
        }
        if (!errors.isEmpty()) {
            showError("Datos inválidos", String.join("\n", errors));
            return;
        }

        // Detectar correcciones de cuenta vs original (para entrenar el modelo).
        List<Runnable> corrections = new ArrayList<>();
        if (original != null) {
            List<JournalLine> orig = original.lines();
            int min = Math.min(orig.size(), lines.size());
            for (int i = 0; i < min; i++) {
                JournalLine before = orig.get(i);
                JournalLine after = lines.get(i);
                if (before.accountId() != null && after.accountId() != null
                        && !before.accountId().equals(after.accountId())) {
                    final String oldId = before.accountId();
                    final String newId = after.accountId();
                    final String newCode = after.accountCode();
                    corrections.add(() -> {
                        try {
                            api.recordCorrection(original.id(), before.id(),
                                    oldId, newId, newCode,
                                    null, null,
                                    safeKeyword(original.concept()),
                                    null);
                        } catch (Exception ex) {
                            System.err.println("[acc-learn] no se registró corrección: " + ex.getMessage());
                        }
                    });
                }
            }
        }

        async(() -> {
            JournalEntryDetail saved;
            if (original == null) {
                saved = api.createEntry(entryDate, concept, lines, post);
            } else {
                saved = api.updateEntry(original.id(), entryDate, concept, lines, false);
                for (Runnable c : corrections) c.run();
                if (post) saved = api.postEntry(original.id());
            }
            return saved;
        }, saved -> {
            dialog.close();
            com.benjagest.ui.support.RefreshBus.emit(
                    com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL);
        }, err -> showError(tt.apply("accounting.error.save"), err));
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    // ====================================================================
    //  REPORTS-UI — Libro Mayor, Sumas y Saldos, Balance de Situación, PyG
    // ====================================================================

    /**
     * Hace un ComboBox editable FILTRABLE al teclear: con el campo vacío muestra
     * TODAS las opciones; al escribir filtra por subcadena (código o nombre). Es
     * la versión local del helper de la app (AccountingScreen es clase aparte).
     */
    private void installAccountFilter(ComboBox<String> combo, List<String> all) {
        final List<String> master = new ArrayList<>(all);
        combo.getItems().setAll(master);
        final boolean[] guard = {false};
        combo.getEditor().textProperty().addListener((obs, ov, nv) -> {
            if (guard[0]) return;
            guard[0] = true;
            try {
                String q = nv == null ? "" : nv.toLowerCase().trim();
                if (q.isEmpty()) {
                    combo.getItems().setAll(master);
                } else {
                    List<String> f = new ArrayList<>();
                    for (String it : master) if (it.toLowerCase().contains(q)) f.add(it);
                    combo.getItems().setAll(f);
                    if (!combo.isShowing()) combo.show();
                }
                combo.getEditor().setText(nv);
                combo.getEditor().positionCaret(nv == null ? 0 : nv.length());
            } finally {
                guard[0] = false;
            }
        });
    }

    /** Libro Mayor: elige cuenta + rango → movimientos con saldo corriente. */
    private Node buildLedgerTab() {
        // Combo de cuenta EDITABLE y FILTRABLE al teclear: si el campo está vacío
        // se ven todas las cuentas; al escribir se filtra por código o nombre.
        ComboBox<String> accountCombo = new ComboBox<>();
        accountCombo.setEditable(true);
        accountCombo.setPrefWidth(360);
        final java.util.Map<String, AccountSummary> accountsByLabel = new java.util.LinkedHashMap<>();
        async(() -> api.listAccounts(null), accts -> {
            accountsByLabel.clear();
            List<String> labels = new ArrayList<>();
            for (AccountSummary a : accts) {
                String label = a.code() + " — " + a.name();
                accountsByLabel.put(label, a);
                labels.add(label);
            }
            installAccountFilter(accountCombo, labels);
        }, err -> logSilent("ledger-accounts", err));

        DatePicker from = new DatePicker(LocalDate.now().withDayOfYear(1));
        DatePicker to = new DatePicker(LocalDate.now().withMonth(12).withDayOfMonth(31));
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(from);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(to);

        Label opening = new Label("");
        opening.getStyleClass().add("settings-hint");
        Label closing = new Label("");
        closing.setStyle("-fx-font-weight: bold;");

        TableView<AccountingModels.LedgerLineView> table = new TableView<>();
        table.getColumns().addAll(List.of(
                col(tt.apply("accounting.col.date"), m -> m.entryDate() == null ? "" : m.entryDate().toString(), 100),
                col(tt.apply("accounting.col.num"), m -> m.entryNumber() <= 0 ? "—" : String.valueOf(m.entryNumber()), 60),
                // El Mayor muestra el CONCEPTO del asiento (la glosa, igual que el
                // Diario); cae a la descripción de línea solo si el asiento no tiene
                // concepto. Antes priorizaba la descripción de línea, que a menudo es
                // el nombre de la cuenta (redundante en el Mayor de esa misma cuenta).
                col(tt.apply("accounting.col.concept"), m -> first(m.concept(), m.lineDescription()), 280),
                col(tt.apply("accounting.col.status"), m -> m.status() == null ? "" : tt.apply("accounting.status." + m.status()), 90),
                col(tt.apply("accounting.col.debit_total"), m -> eur(m.debit()), 110),
                col(tt.apply("accounting.col.credit_total"), m -> eur(m.credit()), 110),
                col(tt.apply("accounting.col.balance"), m -> eur(m.runningBalance()), 120)));
        VBox.setVgrow(table, Priority.ALWAYS);

        Button view = new Button(tt.apply("accounting.action.view"));
        view.getStyleClass().add("primary-button");
        Runnable run = () -> {
            String label = accountCombo.getEditor().getText();
            AccountSummary sel = accountsByLabel.get(label);
            if (sel == null) sel = accountsByLabel.get(accountCombo.getValue());
            if (sel == null) { showError(tt.apply("accounting.report.fail"), tt.apply("accounting.ledger.pick_account")); return; }
            final String accId = sel.id();
            async(() -> api.ledger(accId, from.getValue(), to.getValue()), lv -> {
                table.setItems(FXCollections.observableArrayList(lv.movements()));
                opening.setText(tt.apply("accounting.ledger.opening") + " " + eur(lv.openingBalance()));
                closing.setText(tt.apply("accounting.ledger.closing") + " " + eur(lv.closingBalance()));
            }, err -> showError(tt.apply("accounting.report.fail"), err));
        };
        view.setOnAction(e -> run.run());

        HBox filters = new HBox(8,
                new Label(tt.apply("accounting.ledger.account")), accountCombo,
                new Label(tt.apply("accounting.filter.from")), from,
                new Label(tt.apply("accounting.filter.to")), to, view);
        filters.setAlignment(Pos.CENTER_LEFT);
        HBox totals = new HBox(16, opening, closing);
        totals.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, filters, table, totals);
        box.setPadding(new Insets(8));
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    /** Balance de Sumas y Saldos: por rango, debe/haber/saldo por cuenta + totales. */
    private Node buildTrialBalanceTab() {
        DatePicker from = new DatePicker(LocalDate.now().withDayOfYear(1));
        DatePicker to = new DatePicker(LocalDate.now().withMonth(12).withDayOfMonth(31));
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(from);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(to);
        TextField prefix = new TextField();
        prefix.setPromptText(tt.apply("accounting.trial.prefix_prompt"));
        prefix.setPrefColumnCount(6);

        TableView<AccountingModels.TrialBalanceRow> table = new TableView<>();
        table.getColumns().addAll(List.of(
                col(tt.apply("accounting.col.account_code"), AccountingModels.TrialBalanceRow::code, 90),
                col(tt.apply("accounting.col.account_name"), AccountingModels.TrialBalanceRow::name, 280),
                col(tt.apply("accounting.col.debit_total"), r -> eur(r.totalDebit()), 110),
                col(tt.apply("accounting.col.credit_total"), r -> eur(r.totalCredit()), 110),
                col(tt.apply("accounting.trial.debtor"), r -> eur(r.saldoDeudor()), 110),
                col(tt.apply("accounting.trial.creditor"), r -> eur(r.saldoAcreedor()), 110)));
        VBox.setVgrow(table, Priority.ALWAYS);

        Label totals = new Label("");
        totals.setStyle("-fx-font-weight: bold;");

        Button view = new Button(tt.apply("accounting.action.view"));
        view.getStyleClass().add("primary-button");
        view.setOnAction(e -> async(() -> api.trialBalance(from.getValue(), to.getValue(),
                prefix.getText() == null ? null : prefix.getText().trim()), rows -> {
            table.setItems(FXCollections.observableArrayList(rows));
            BigDecimal td = BigDecimal.ZERO, tc = BigDecimal.ZERO, sd = BigDecimal.ZERO, sa = BigDecimal.ZERO;
            for (var r : rows) {
                td = td.add(r.totalDebit() == null ? BigDecimal.ZERO : r.totalDebit());
                tc = tc.add(r.totalCredit() == null ? BigDecimal.ZERO : r.totalCredit());
                sd = sd.add(r.saldoDeudor() == null ? BigDecimal.ZERO : r.saldoDeudor());
                sa = sa.add(r.saldoAcreedor() == null ? BigDecimal.ZERO : r.saldoAcreedor());
            }
            totals.setText(tt.apply("accounting.trial.totals")
                    .replace("{debit}", eur(td)).replace("{credit}", eur(tc))
                    .replace("{debtor}", eur(sd)).replace("{creditor}", eur(sa)));
        }, err -> showError(tt.apply("accounting.report.fail"), err)));

        HBox filters = new HBox(8,
                new Label(tt.apply("accounting.filter.from")), from,
                new Label(tt.apply("accounting.filter.to")), to,
                new Label(tt.apply("accounting.trial.prefix")), prefix, view);
        filters.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, filters, table, totals);
        box.setPadding(new Insets(8));
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    /** Balance de Situación a una fecha: Activo vs Pasivo + PN por masas. */
    private Node buildBalanceSheetTab() {
        DatePicker asOf = new DatePicker(LocalDate.now());
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(asOf);
        VBox content = new VBox(12);
        content.setPadding(new Insets(8));
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(content);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button view = new Button(tt.apply("accounting.action.view"));
        view.getStyleClass().add("primary-button");
        view.setOnAction(e -> async(() -> api.balanceSheet(asOf.getValue()), bs -> {
            content.getChildren().setAll(
                    sectionGroup(tt.apply("accounting.balance.activo"), bs.activo(), bs.totalActivo()),
                    new javafx.scene.control.Separator(),
                    sectionGroup(tt.apply("accounting.balance.pasivo"), bs.pasivo(), bs.totalPasivo()));
        }, err -> showError(tt.apply("accounting.report.fail"), err)));

        Button exportPdf = new Button(tt.apply("accounting.fin.export_pdf"));
        exportPdf.setOnAction(e -> savePdf(() -> api.balanceSheetPdf(asOf.getValue()),
                "balance-" + asOf.getValue() + ".pdf", view));

        HBox filters = new HBox(8, new Label(tt.apply("accounting.balance.as_of")), asOf, view, exportPdf);
        filters.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, filters, scroll);
        box.setPadding(new Insets(8));
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    /** ECPN — Estado de Cambios en el Patrimonio Neto: saldo inicial/final + variación. */
    private Node buildEcpnTab() {
        DatePicker from = new DatePicker(LocalDate.now().withDayOfYear(1));
        DatePicker to = new DatePicker(LocalDate.now().withMonth(12).withDayOfMonth(31));
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(from);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(to);
        TableView<AccountingModels.EquityMovementRow> table = new TableView<>();
        table.getColumns().addAll(List.of(
                col(tt.apply("accounting.col.account_code"), AccountingModels.EquityMovementRow::code, 90),
                col(tt.apply("accounting.col.account_name"), AccountingModels.EquityMovementRow::name, 280),
                col(tt.apply("accounting.ecpn.opening"), r -> eur(r.openingBalance()), 120),
                col(tt.apply("accounting.ecpn.closing"), r -> eur(r.closingBalance()), 120),
                col(tt.apply("accounting.ecpn.variation"), r -> eur(r.variation()), 120)));
        VBox.setVgrow(table, Priority.ALWAYS);
        Button view = new Button(tt.apply("accounting.action.view"));
        view.getStyleClass().add("primary-button");
        view.setOnAction(e -> async(() -> api.equityChanges(from.getValue(), to.getValue()),
                rows -> table.setItems(FXCollections.observableArrayList(rows)),
                err -> showError(tt.apply("accounting.report.fail"), err)));
        HBox filters = new HBox(8,
                new Label(tt.apply("accounting.filter.from")), from,
                new Label(tt.apply("accounting.filter.to")), to, view);
        filters.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, filters, table);
        box.setPadding(new Insets(8));
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    /** Cuenta de Pérdidas y Ganancias de un periodo. */
    private Node buildPygTab() {
        DatePicker from = new DatePicker(LocalDate.now().withDayOfYear(1));
        DatePicker to = new DatePicker(LocalDate.now().withMonth(12).withDayOfMonth(31));
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(from);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(to);
        VBox content = new VBox(12);
        content.setPadding(new Insets(8));
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(content);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button view = new Button(tt.apply("accounting.action.view"));
        view.getStyleClass().add("primary-button");
        view.setOnAction(e -> async(() -> api.profitAndLoss(from.getValue(), to.getValue()), pl -> {
            Label result = new Label(tt.apply("accounting.pyg.result") + " " + eur(pl.resultadoExplotacion()));
            result.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
            content.getChildren().setAll(
                    sectionGroup(tt.apply("accounting.pyg.ingresos"), pl.ingresos(), pl.totalIngresos()),
                    new javafx.scene.control.Separator(),
                    sectionGroup(tt.apply("accounting.pyg.gastos"), pl.gastos(), pl.totalGastos()),
                    new javafx.scene.control.Separator(), result);
        }, err -> showError(tt.apply("accounting.report.fail"), err)));

        Button exportPdf = new Button(tt.apply("accounting.fin.export_pdf"));
        exportPdf.setOnAction(e -> savePdf(() -> api.profitAndLossPdf(from.getValue(), to.getValue()),
                "pyg-" + from.getValue() + "_" + to.getValue() + ".pdf", view));

        HBox filters = new HBox(8,
                new Label(tt.apply("accounting.filter.from")), from,
                new Label(tt.apply("accounting.filter.to")), to, view, exportPdf);
        filters.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, filters, scroll);
        box.setPadding(new Insets(8));
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    /**
     * EXPORT-CONTABLE + EXT-IMPORT — exportar a otros programas (CSV/Contasol/
     * JSON) y reimportar. Formatos implementados en backend: CSV, CONTASOL,
     * JSON_BENJAGEST (A3/SAGE = pendientes en backend).
     */
    private Node buildExportImportTab() {
        // ----- Exportar -----
        Label expTitle = new Label(tt.apply("accounting.exchange.export_title"));
        expTitle.getStyleClass().add("settings-section-title");
        ComboBox<String> expFormat = new ComboBox<>(FXCollections.observableArrayList("CSV", "CONTASOL", "JSON_BENJAGEST"));
        localizeCombo(expFormat, "accounting.exchange.fmt.");
        expFormat.setValue("CSV");
        ComboBox<String> expTarget = new ComboBox<>(FXCollections.observableArrayList(
                "JOURNAL_ENTRIES", "ACCOUNTS", "CUSTOMERS", "SUPPLIERS",
                "INVOICES_SALES", "INVOICES_PURCHASE", "FIXED_ASSETS", "LOANS"));
        localizeCombo(expTarget, "accounting.exchange.target.");
        expTarget.setValue("JOURNAL_ENTRIES");
        DatePicker expFrom = new DatePicker(LocalDate.now().withDayOfYear(1));
        DatePicker expTo = new DatePicker(LocalDate.now().withMonth(12).withDayOfMonth(31));
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(expFrom);
        com.benjagest.ui.support.EditableCells.installFlexibleConverter(expTo);
        javafx.scene.control.CheckBox expDrafts = new javafx.scene.control.CheckBox(tt.apply("accounting.exchange.include_drafts"));
        Button expBtn = new Button(tt.apply("accounting.exchange.export_btn"));
        expBtn.getStyleClass().add("primary-button");
        expBtn.setOnAction(e -> async(
                () -> api.exportAccounting(expFormat.getValue(), expTarget.getValue(),
                        expFrom.getValue(), expTo.getValue(), expDrafts.isSelected()),
                content -> {
                    javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
                    fc.setTitle(tt.apply("accounting.exchange.export_btn"));
                    String ext = "JSON_BENJAGEST".equals(expFormat.getValue()) ? ".json"
                            : "CONTASOL".equals(expFormat.getValue()) ? ".txt" : ".csv";
                    fc.setInitialFileName(expTarget.getValue().toLowerCase() + "-"
                            + LocalDate.now() + ext);
                    java.io.File f = fc.showSaveDialog(expBtn.getScene().getWindow());
                    if (f == null) return;
                    try {
                        java.nio.file.Files.writeString(f.toPath(), content == null ? "" : content);
                        showInfo(tt.apply("accounting.exchange.export_done"), f.getName());
                    } catch (java.io.IOException ex) {
                        showInfo(tt.apply("accounting.exchange.export_fail"), ex.getMessage());
                    }
                },
                err -> showError(tt.apply("accounting.exchange.export_fail"), err)));
        GridPane expG = new GridPane();
        expG.setHgap(10); expG.setVgap(8);
        expG.add(new Label(tt.apply("accounting.exchange.format")), 0, 0); expG.add(expFormat, 1, 0);
        expG.add(new Label(tt.apply("accounting.exchange.target")), 2, 0); expG.add(expTarget, 3, 0);
        expG.add(new Label(tt.apply("accounting.filter.from")), 0, 1); expG.add(expFrom, 1, 1);
        expG.add(new Label(tt.apply("accounting.filter.to")), 2, 1); expG.add(expTo, 3, 1);
        expG.add(expDrafts, 1, 2); expG.add(expBtn, 3, 2);

        // ----- Importar -----
        Label impTitle = new Label(tt.apply("accounting.exchange.import_title"));
        impTitle.getStyleClass().add("settings-section-title");
        ComboBox<String> impFormat = new ComboBox<>(FXCollections.observableArrayList("CSV", "CONTASOL", "JSON_BENJAGEST"));
        localizeCombo(impFormat, "accounting.exchange.fmt.");
        impFormat.setValue("CSV");
        ComboBox<String> impTarget = new ComboBox<>(FXCollections.observableArrayList(
                "JOURNAL_ENTRIES", "ACCOUNTS", "CUSTOMERS", "SUPPLIERS"));
        localizeCombo(impTarget, "accounting.exchange.target.");
        impTarget.setValue("JOURNAL_ENTRIES");
        Label impFile = new Label(tt.apply("bank.import.no_file"));
        impFile.setStyle("-fx-text-fill: #6e6e6e;");
        final java.io.File[] impChosen = {null};
        Button impPick = new Button(tt.apply("bank.import.pick_file"));
        impPick.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.getExtensionFilters().addAll(
                    new javafx.stage.FileChooser.ExtensionFilter("CSV / TXT / JSON", "*.csv", "*.txt", "*.json"),
                    new javafx.stage.FileChooser.ExtensionFilter("Todos", "*.*"));
            java.io.File f = fc.showOpenDialog(impPick.getScene().getWindow());
            if (f != null) { impChosen[0] = f; impFile.setText(f.getName()); }
        });
        Button impBtn = new Button(tt.apply("accounting.exchange.import_btn"));
        impBtn.setOnAction(e -> {
            if (impChosen[0] == null) { showInfo(tt.apply("accounting.exchange.import_title"), tt.apply("bank.import.missing")); return; }
            final java.io.File f = impChosen[0];
            async(() -> {
                String content = java.nio.file.Files.readString(f.toPath());
                return api.importExternal(impFormat.getValue(), impTarget.getValue(), f.getName(), content);
            }, res -> {
                showInfo(tt.apply("accounting.exchange.import_done"), tt.apply("accounting.exchange.import_done_body")
                        .replace("{total}", String.valueOf(res.rowsTotal()))
                        .replace("{imported}", String.valueOf(res.rowsImported()))
                        .replace("{skipped}", String.valueOf(res.rowsSkipped()))
                        .replace("{errors}", String.valueOf(res.rowsAutoMatched())));
                com.benjagest.ui.support.RefreshBus.emit(
                        com.benjagest.ui.support.RefreshBus.TOPIC_JOURNAL,
                        com.benjagest.ui.support.RefreshBus.TOPIC_ACCOUNTS_CATALOG,
                        com.benjagest.ui.support.RefreshBus.TOPIC_CUSTOMERS,
                        com.benjagest.ui.support.RefreshBus.TOPIC_SUPPLIERS);
            }, err -> showError(tt.apply("accounting.exchange.import_fail"), err));
        });
        GridPane impG = new GridPane();
        impG.setHgap(10); impG.setVgap(8);
        impG.add(new Label(tt.apply("accounting.exchange.format")), 0, 0); impG.add(impFormat, 1, 0);
        impG.add(new Label(tt.apply("accounting.exchange.target")), 2, 0); impG.add(impTarget, 3, 0);
        impG.add(new Label(tt.apply("accounting.exchange.file")), 0, 1); impG.add(new HBox(8, impPick, impFile), 1, 1, 3, 1);
        impG.add(impBtn, 3, 2);

        Label hint = new Label(tt.apply("accounting.exchange.hint"));
        hint.setWrapText(true); hint.getStyleClass().add("settings-hint");

        VBox box = new VBox(14, expTitle, expG, new javafx.scene.control.Separator(),
                impTitle, impG, new javafx.scene.control.Separator(), hint);
        box.setPadding(new Insets(12));
        return box;
    }

    /** Renderiza un grupo (Activo/Pasivo/Ingresos/Gastos) con sus masas, líneas y total. */
    private VBox sectionGroup(String title, List<AccountingModels.ReportSection> sections, BigDecimal grandTotal) {
        VBox group = new VBox(8);
        Label header = new Label(title + "   " + eur(grandTotal));
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        group.getChildren().add(header);
        if (sections != null) {
            for (var sec : sections) {
                Label secName = new Label(sec.name() + "   " + eur(sec.total()));
                secName.setStyle("-fx-font-weight: bold;");
                VBox secBox = new VBox(2, secName);
                secBox.setPadding(new Insets(0, 0, 0, 12));
                if (sec.items() != null) {
                    for (var it : sec.items()) {
                        Label line = new Label((it.code() == null ? "" : it.code() + "  ")
                                + (it.name() == null ? "" : it.name()) + "   " + eur(it.amount()));
                        line.setPadding(new Insets(0, 0, 0, 16));
                        secBox.getChildren().add(line);
                    }
                }
                group.getChildren().add(secBox);
            }
        }
        return group;
    }

    private TableView<DiaryEntry> createDiaryTable(boolean showConfidence) {
        TableView<DiaryEntry> table = new TableView<>();
        // Columna Nº con ordenación NUMÉRICA (no alfabética). Antes
        // "10" salía antes que "2" porque comparaba como String.
        // entry_number = 0 / NULL → DRAFT sin número aún (se asigna al
        // validar). Mostramos "—" en lugar de "0".
        TableColumn<DiaryEntry, Integer> colNum =
                new TableColumn<>(tt.apply("accounting.col.num"));
        colNum.setCellValueFactory(cd -> new javafx.beans.property.SimpleObjectProperty<>(
                cd.getValue().entryNumber()));
        colNum.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(Integer v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null || v <= 0 ? "—" : String.valueOf(v));
            }
        });
        colNum.setComparator(Integer::compare);
        colNum.setPrefWidth(60);
        table.getColumns().add(colNum);

        List<TableColumn<DiaryEntry, String>> cols = new ArrayList<>();
        cols.add(col(tt.apply("accounting.col.date"), e -> e.entryDate() == null ? "" : e.entryDate().toString(), 100));
        cols.add(col(tt.apply("accounting.col.concept"), DiaryEntry::concept, 280));
        cols.add(col(tt.apply("accounting.col.source"),
                e -> e.sourceType() == null ? tt.apply("accounting.source_type.MANUAL")
                        : tt.apply("accounting.source_type." + e.sourceType()), 160));
        cols.add(col(tt.apply("accounting.col.status"),
                e -> e.status() == null ? "" : tt.apply("accounting.status." + e.status()), 100));
        cols.add(col(tt.apply("accounting.col.debit_total"), e -> e.totalDebit() == null ? "" : e.totalDebit().toString(), 100));
        cols.add(col(tt.apply("accounting.col.credit_total"), e -> e.totalCredit() == null ? "" : e.totalCredit().toString(), 100));
        if (showConfidence) {
            cols.add(col(tt.apply("accounting.col.confidence"),
                    e -> e.proposedConfidence() == null ? "" : e.proposedConfidence() + "%", 90));
        }
        table.getColumns().addAll(cols);
        table.setRowFactory(tv -> {
            javafx.scene.control.TableRow<DiaryEntry> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && row.getItem() != null) {
                    openEntryEditor(row.getItem().id());
                }
            });
            return row;
        });
        return table;
    }

    private <T> TableColumn<T, String> col(String header, Function<T, String> getter, double width) {
        TableColumn<T, String> c = new TableColumn<>(header);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new SimpleStringProperty(getter.apply(cd.getValue())));
        return c;
    }

    private HBox row(String label, Node control) {
        HBox h = new HBox(8, new Label(label), control);
        h.setAlignment(Pos.CENTER_LEFT);
        if (control instanceof Region r) HBox.setHgrow(r, Priority.ALWAYS);
        return h;
    }

    private BigDecimal parse(String v) {
        if (v == null || v.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(v.replace(',', '.').trim()); }
        catch (Exception ex) { return BigDecimal.ZERO; }
    }

    private String first(String... s) {
        for (String x : s) if (x != null && !x.isBlank()) return x;
        return "";
    }

    private boolean empty(String s) { return s == null || s.isBlank(); }

    /**
     * ¿El código tiene pinta de sub-cuenta de tercero (proveedor 4000xxx
     * o cliente 4300xxx)? Si sí, la UI no debe bloquear: el backend la
     * crea con el nombre del tercero extraído del concepto del asiento.
     */
    private boolean looksLikeTerceroCode(String code) {
        if (code == null) return false;
        String c = code.trim();
        if (c.length() < 4) return false;
        return (c.startsWith("4000") || c.startsWith("4300"))
                && c.chars().allMatch(Character::isDigit);
    }

    private String safeKeyword(String concept) {
        if (concept == null) return null;
        String s = concept.toLowerCase().replaceAll("[^a-záéíóúñ ]", "").trim();
        if (s.isEmpty()) return null;
        String[] words = s.split("\\s+");
        Comparator<String> byLen = Comparator.comparingInt(String::length).reversed();
        java.util.Arrays.sort(words, byLen);
        return words[0].length() < 4 ? null : words[0];
    }

    private <T> void async(ApiCall<T> call, Consumer<T> ok, Consumer<Throwable> err) {
        Thread t = new Thread(() -> {
            try {
                T result = call.call();
                Platform.runLater(() -> ok.accept(result));
            } catch (Throwable ex) {
                Platform.runLater(() -> err.accept(ex));
            }
        }, "accounting-api");
        t.setDaemon(true);
        t.start();
    }

    private void showError(String title, Throwable err) {
        // Si la causa es sesión expirada, no abrumes al usuario con el
        // JSON técnico — mensaje claro y sugerencia de relogueo.
        if (err instanceof com.benjagest.ui.service.SessionExpiredException
                || (err.getCause() instanceof com.benjagest.ui.service.SessionExpiredException)) {
            Alert a = new Alert(AlertType.WARNING,
                    tt.apply("accounting.error.session_expired_body"));
            a.setHeaderText(tt.apply("accounting.error.session_expired_title"));
            a.showAndWait();
            return;
        }
        // Si el cuerpo del error contiene un mensaje útil del backend
        // (módulo no activo, rol no permitido), lo mostramos legible
        // en vez del JSON crudo.
        String raw = err.getMessage() == null ? err.toString() : err.getMessage();
        String friendly = extractBackendMessage(raw);
        Alert a = new Alert(AlertType.ERROR,
                title + (friendly == null ? "\n\n" + raw : "\n\n" + friendly));
        a.showAndWait();
    }

    /**
     * Si el JSON de error del backend incluye una propiedad "message"
     * (ej: {@code "message":"El modulo 'accounting' no esta activo..."}),
     * la extrae. Si no, devuelve null y el caller muestra el crudo.
     */
    private String extractBackendMessage(String body) {
        if (body == null) return null;
        int i = body.indexOf("\"message\":\"");
        if (i < 0) return null;
        int start = i + 11;
        int end = body.indexOf('"', start);
        if (end < 0) return null;
        String msg = body.substring(start, end);
        return msg.isBlank() ? null : msg.replace("\\\"", "\"");
    }

    private void showError(String title, String body) {
        Alert a = new Alert(AlertType.ERROR, title + "\n\n" + body);
        a.showAndWait();
    }

    private void showInfo(String title, String body) {
        Alert a = new Alert(AlertType.INFORMATION, body);
        a.setHeaderText(title);
        a.showAndWait();
    }

    /**
     * Loguea sin popup. Para errores de carga inicial: si el endpoint
     * devuelve [] vacío o falla, la tabla queda vacía pero no molestamos
     * al usuario con un Alert. Sólo las acciones explícitas del usuario
     * (botones Validar/Borrar/etc.) deben mostrar Alert al fallar.
     */
    private void logSilent(String where, Throwable err) {
        System.err.println("[accounting-ui:" + where + "] "
                + (err.getMessage() == null ? err.toString() : err.getMessage()));
    }

    @FunctionalInterface
    private interface ApiCall<T> { T call() throws Exception; }

    // ====================================================================
    //  Modelo editable + cell con ComboBox de cuentas
    // ====================================================================

    private static class EditableLine {
        SimpleStringProperty accountCodeProp = new SimpleStringProperty("");
        SimpleStringProperty descriptionProp = new SimpleStringProperty("");
        SimpleStringProperty debitProp = new SimpleStringProperty("");
        SimpleStringProperty creditProp = new SimpleStringProperty("");

        static EditableLine from(JournalLine l) {
            EditableLine e = new EditableLine();
            e.accountCodeProp.set(l.accountCode() == null ? "" : l.accountCode());
            e.descriptionProp.set(l.description() == null ? "" : l.description());
            e.debitProp.set(l.debit() == null || l.debit().signum() == 0
                    ? "" : l.debit().toPlainString());
            e.creditProp.set(l.credit() == null || l.credit().signum() == 0
                    ? "" : l.credit().toPlainString());
            return e;
        }
    }

    /**
     * Celda con ComboBox editable que filtra el listado en vivo al
     * teclear el código de cuenta. Comportamiento:
     *
     * <ul>
     *   <li>Al teclear "70" → muestra dropdown con todas las cuentas
     *       cuyo code empieza por "70" (700, 7000, 7001, 705, ...).</li>
     *   <li>Al teclear "705" → muestra "705 Ventas de prestaciones..."
     *       y filtra al pasar.</li>
     *   <li>Al perder el foco (Tab, click fuera) persiste el valor en
     *       el modelo, no solo al pulsar Enter.</li>
     *   <li>Si el texto NO matchea ninguna cuenta, el modelo guarda lo
     *       tecleado tal cual — la validación al guardar el asiento
     *       reportará "cuenta X no existe en esta empresa" claro.</li>
     * </ul>
     */
    private static class AccountComboCell extends javafx.scene.control.TableCell<EditableLine, String> {
        private final ComboBox<String> combo;
        private final ObservableList<String> allOptions;
        private final List<AccountSummary> accounts;
        private boolean updatingFromFilter = false;
        /** ME-2/ME-3 — callback al confirmar una cuenta (código) para que el
         *  editor cargue facturas pendientes del tercero + sugerencias. */
        private java.util.function.Consumer<String> onCommit;

        void setOnCommit(java.util.function.Consumer<String> c) { this.onCommit = c; }

        AccountComboCell(List<AccountSummary> accounts) {
            this.accounts = accounts;
            this.combo = new ComboBox<>();
            this.allOptions = FXCollections.observableArrayList();
            for (AccountSummary a : accounts) allOptions.add(a.code() + "  " + a.name());
            combo.setItems(FXCollections.observableArrayList(allOptions));
            combo.setEditable(true);
            combo.setVisibleRowCount(12);
            combo.setMaxWidth(Double.MAX_VALUE);

            // Filtrado en vivo al teclear: cada cambio del texto del
            // editor recalcula la lista de items mostrando solo los que
            // empiezan por el código tecleado (case-insensitive).
            combo.getEditor().textProperty().addListener((obs, oldV, newV) -> {
                if (updatingFromFilter) return;
                String typed = newV == null ? "" : newV.trim();
                // Si el usuario seleccionó una opción "705 Ventas...", al
                // pasar de la línea el editor.text contiene "705  Ventas..."
                // — NO filtrar en ese caso (el dropdown ya cerró).
                if (typed.contains("  ")) return;
                String prefix = typed.toLowerCase();
                ObservableList<String> filtered = FXCollections.observableArrayList();
                for (String opt : allOptions) {
                    if (prefix.isEmpty() || opt.toLowerCase().startsWith(prefix)) {
                        filtered.add(opt);
                    }
                }
                updatingFromFilter = true;
                try {
                    combo.setItems(filtered);
                    if (!prefix.isEmpty() && !filtered.isEmpty() && !combo.isShowing()) {
                        combo.show();
                    }
                } finally {
                    updatingFromFilter = false;
                }
            });

            // Persistir al pulsar Enter / seleccionar de la lista.
            combo.setOnAction(ev -> persistCurrentValue());
            // Persistir también al perder foco (Tab / click fuera).
            combo.focusedProperty().addListener((obs, hadFocus, hasFocus) -> {
                if (hadFocus && !hasFocus) persistCurrentValue();
            });
            combo.getEditor().focusedProperty().addListener((obs, hadFocus, hasFocus) -> {
                if (hadFocus && !hasFocus) persistCurrentValue();
            });

            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        private void persistCurrentValue() {
            String v = combo.getEditor().getText();
            if (v == null) return;
            // El editor puede mostrar "705  Ventas de..." cuando el
            // usuario seleccionó del dropdown. Nos quedamos solo con el
            // code (antes del doble espacio) o con el texto literal.
            String code = v.contains("  ") ? v.substring(0, v.indexOf("  ")).trim() : v.trim();
            if (getTableRow() != null && getTableRow().getItem() != null) {
                EditableLine row = getTableRow().getItem();
                row.accountCodeProp.set(code);
                // Si la descripción está vacía, rellenarla con el nombre
                // de la cuenta para que el Diario y el Mayor se lean bien.
                // Si el asesor ya tiene una descripción suya, NO la tocamos
                // — su trabajo de redacción se respeta al cambiar la cuenta.
                String currentDesc = row.descriptionProp.get();
                if (currentDesc == null || currentDesc.isBlank()) {
                    for (AccountSummary a : accounts) {
                        if (code.equals(a.code())) {
                            row.descriptionProp.set(a.name());
                            break;
                        }
                    }
                }
            }
            setText(code);
            if (onCommit != null && !code.isBlank()) onCommit.accept(code);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) { setGraphic(null); return; }
            // Mostrar code + name si encontramos match — más informativo.
            String shown = item == null ? "" : item;
            if (item != null && !item.isBlank()) {
                for (AccountSummary a : accounts) {
                    if (item.equals(a.code())) {
                        shown = a.code() + "  " + a.name();
                        break;
                    }
                }
            }
            updatingFromFilter = true;
            try {
                combo.getEditor().setText(shown);
                combo.setItems(FXCollections.observableArrayList(allOptions));
            } finally {
                updatingFromFilter = false;
            }
            setGraphic(combo);
        }
    }
}
