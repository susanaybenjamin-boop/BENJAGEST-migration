package com.benjagest.ui.screens;

import com.benjagest.ui.model.AccountingModels;
import com.benjagest.ui.model.AccountingModels.AccountSummary;
import com.benjagest.ui.model.AccountingModels.DiaryEntry;
import com.benjagest.ui.model.AccountingModels.JournalEntryDetail;
import com.benjagest.ui.model.AccountingModels.JournalLine;
import com.benjagest.ui.model.AccountingModels.LearningRule;
import com.benjagest.ui.model.AccountingModels.RecurringTask;
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
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
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
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab(tt.apply("accounting.tab.pending"), buildPendingTab()),
                new Tab(tt.apply("accounting.tab.diary"), buildDiaryTab()),
                new Tab(tt.apply("accounting.tab.manual"), buildManualTab()),
                new Tab(tt.apply("accounting.tab.rules"), buildRulesTab()),
                new Tab(tt.apply("accounting.tab.recurring"), buildRecurringTab())
        );
        VBox.setVgrow(tabs, Priority.ALWAYS);
        VBox root = new VBox(tabs);
        root.setPadding(new Insets(8));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return root;
    }

    // ====================================================================
    //  Tab: Por validar
    // ====================================================================

    private TableView<DiaryEntry> pendingTable;

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
            }, ok -> loadPending(),
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
                            loadPending();
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
                        loadPending();
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
                loadPending();
                loadDiary();
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
        toPicker = new DatePicker(LocalDate.now());
        statusFilter = new ComboBox<>(FXCollections.observableArrayList(
                "", "DRAFT", "POSTED", "VOIDED"));
        statusFilter.setValue("POSTED");
        installStatusCellFactory(statusFilter);
        sourceFilter = new ComboBox<>(FXCollections.observableArrayList(
                "", "MANUAL", "SALES_INVOICE", "PURCHASE_INVOICE",
                "BANK_MOVEMENT", "YEAR_CLOSE_REGULARIZATION",
                "YEAR_CLOSE_CLOSING", "LOAN_INSTALLMENT", "ASSET_DEPRECIATION"));
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
        String source = empty(sourceFilter.getValue()) ? null : sourceFilter.getValue();
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

        TableColumn<EditableLine, String> accCol = new TableColumn<>(tt.apply("accounting.col.account"));
        accCol.setPrefWidth(220);
        accCol.setCellValueFactory(c -> c.getValue().accountCodeProp);
        // Editable via ComboBox de cuentas.
        accCol.setCellFactory(c -> new AccountComboCell(accounts));

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
        body.setBottom(new VBox(6, totals, actions));

        javafx.scene.Scene scene = new javafx.scene.Scene(body, 900, 560);
        dialog.setScene(scene);
        return dialog;
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
            loadPending();
            loadDiary();
        }, err -> showError(tt.apply("accounting.error.save"), err));
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private TableView<DiaryEntry> createDiaryTable(boolean showConfidence) {
        TableView<DiaryEntry> table = new TableView<>();
        List<TableColumn<DiaryEntry, String>> cols = new ArrayList<>();
        // entry_number = 0 / NULL → DRAFT sin número aún (se asigna al
        // validar). Mostramos "—" en lugar de "0".
        cols.add(col(tt.apply("accounting.col.num"),
                e -> e.entryNumber() <= 0 ? "—" : String.valueOf(e.entryNumber()), 60));
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
