package com.benjagest.ui.screens;

import com.benjagest.ui.model.*;
import com.benjagest.ui.service.*;
import com.benjagest.ui.support.Router;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javafx.concurrent.Task;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * FAC-4a — Pestaña de Configuración de facturación, extraída del God Object.
 * Reúne la modalidad VeriFactu (RD 1007/2023), las series de numeración
 * (RD 1619/2012), la migración desde otro programa (baselines), los textos
 * legales y el régimen de IVA. Incrusta {@link VatRatesScreen} y
 * {@link SifAuditScreen} (ya extraídas).
 *
 * <p>Zona caliente legal: el contenido se MOVIÓ tal cual del shell (sin
 * reescritura). El shell mantiene {@code billingConfigTab(...)} como wrapper
 * que delega en {@link #buildTab}. Los enganches que viven en el shell
 * (refresco de la pantalla de Facturación, diálogos de BillingDialogsScreen) se
 * inyectan vía {@link Host}.
 *
 * <p>Reusa form-grid/form-input/data-table/settings-* del CSS de Pablo.
 */
public class BillingConfigScreen extends ScreenBase {

    /** Callbacks que viven en el shell y la pantalla de config necesita invocar. */
    public interface Host {
        /**
         * Reconstruye la pantalla de Facturación aterrizando en la pestaña
         * Configuración (tras CRUD de series o aplicar una migración). En el
         * shell: {@code pendingBillingTab="config"; billingRefresh.run();}.
         */
        void refreshBillingConfig();
        /** Diálogo con las migraciones (baselines) guardadas — BillingDialogsScreen vía shell. */
        void showMigrationBaselines();
        /** Declaración responsable del fabricante (RD 1007/2023) — BillingDialogsScreen vía shell. */
        void showManufacturerDeclaration();
    }

    private final BillingApiClient billingApiClient;
    private final Host host;

    private ComboBox<String> verifactuModalityCombo;
    private ComboBox<String> verifactuModeCombo;
    private ComboBox<CertificateOption> verifactuCertCombo;
    private TextField verifactuStorageRootField;
    private ComboBox<SeriesEntry> migrationSeriesCombo;
    private TextField migrationNextNumberField;
    private TextField migrationYearField;
    private CheckBox migrationAcknowledgeCheck;
    private javafx.scene.layout.FlowPane migrationTokenBox;
    private java.util.List<String> migrationSegTexts;
    private java.util.List<Boolean> migrationSegIsToken;
    private java.util.List<ComboBox<String>> migrationRoleCombos;
    private String migrationFormatTemplate;
    private byte[] migrationImportedPdf;
    private com.benjagest.ui.service.BillingApiClient.MigrationExtracted migrationExtracted;
    private Label migrationDetectedLabel;
    private javafx.scene.control.TextArea textPieArea;
    private javafx.scene.control.TextArea textExemptArea;
    private javafx.scene.control.TextArea textReverseChargeArea;
    private javafx.scene.control.TextArea textReducedVatArea;
    private javafx.scene.control.TextArea textRectifyingArea;
    private javafx.scene.control.TextArea textLegalTermsArea;
    private CheckBox showIbanCheck;

    public BillingConfigScreen(BillingApiClient billingApiClient,
                               Function<String, String> tt, Router router, Host host) {
        super(tt, router);
        this.billingApiClient = billingApiClient;
        this.host = host;
    }

    public Node buildTab(VerifactuConfig config, List<SeriesEntry> series, List<CertificateOption> certificates, InvoiceTexts texts) {
        Label section = label(t("billing.config.verifactu.section"), "settings-section-title");
        Label hint = new Label(t("billing.config.verifactu.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        // Modalidad legal (RD 1007/2023): VERIFACTU o NO_VERIFACTU. Es
        // el concepto principal — define si se envía a AEAT y si hay
        // registro de eventos del SIF obligatorio.
        verifactuModalityCombo = new ComboBox<>();
        verifactuModalityCombo.getItems().addAll("VERIFACTU", "NO_VERIFACTU");
        verifactuModalityCombo.getSelectionModel().select(
                config.modality() == null ? "NO_VERIFACTU" : config.modality());
        verifactuModalityCombo.getStyleClass().add("form-input");
        // Mostramos texto traducido pero conservamos el código interno.
        verifactuModalityCombo.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : localizedModality(item));
            }
        });
        verifactuModalityCombo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : localizedModality(item));
            }
        });

        // Entorno técnico del cliente AEAT (TEST/PROD). Solo aplica si
        // la modalidad es VERIFACTU; si no, se conserva pero se
        // deshabilita visualmente.
        verifactuModeCombo = new ComboBox<>();
        verifactuModeCombo.getItems().addAll("TEST", "PROD");
        localizeEnumCombo(verifactuModeCombo, "verifactu_mode");
        verifactuModeCombo.getSelectionModel().select(config.mode() == null ? "TEST" : config.mode());
        verifactuModeCombo.getStyleClass().add("form-input");
        verifactuModeCombo.setDisable(!"VERIFACTU".equals(verifactuModalityCombo.getValue()));
        // Reactivar/desactivar al cambiar la modalidad — el environment
        // solo tiene sentido cuando se envía a AEAT.
        verifactuModalityCombo.valueProperty().addListener((obs, oldV, newV) ->
                verifactuModeCombo.setDisable(!"VERIFACTU".equals(newV)));

        verifactuCertCombo = new ComboBox<>();
        verifactuCertCombo.getItems().add(new CertificateOption(null, t("billing.config.verifactu.cert.none"), ""));
        verifactuCertCombo.getItems().addAll(certificates);
        verifactuCertCombo.getSelectionModel().selectFirst();
        if (config.certificateId() != null && !config.certificateId().isBlank()) {
            for (CertificateOption opt : verifactuCertCombo.getItems()) {
                if (config.certificateId().equals(opt.id())) {
                    verifactuCertCombo.getSelectionModel().select(opt);
                    break;
                }
            }
        }
        verifactuCertCombo.getStyleClass().add("form-input");
        verifactuCertCombo.setDisable(certificates.isEmpty());

        // El "pie de factura" se gestiona UNA sola vez en la sección "Textos
        // legales" (campo Pie general → companies.invoice_footer_template, que es
        // lo que pinta el PDF). El campo duplicado que había aquí se quitó.

        // Slice F-STORAGE: ruta local donde se almacenan los PDFs al
        // validar. Vacio = usar el default del backend
        // ($HOME/benjagest-facturas o lo que diga
        // benjagest.invoices.storage-root). El backend monta la
        // estructura {root}/{companyId}/{YYYY}/T{q}/{nº}.pdf.
        verifactuStorageRootField = textInput(config.invoiceStorageRoot(),
                t("billing.config.field.storage_root.prompt"));
        verifactuStorageRootField.setPrefColumnCount(50);

        // Botón "Examinar…" que abre el DirectoryChooser del sistema
        // operativo (en Windows = Explorador; macOS = Finder; Linux =
        // GTK/QT según escritorio). El DirectoryChooser permite navegar
        // Y crear carpetas nuevas con el botón estándar del SO
        // ("Nueva carpeta" / "New folder") — no hace falta UI extra.
        Button browseStorageBtn = new Button(t("billing.config.field.storage_root.browse"));
        browseStorageBtn.setGraphic(icon("fas-folder-open"));
        browseStorageBtn.setOnAction(ev -> chooseInvoiceStorageDir());
        HBox storageRow = new HBox(8, verifactuStorageRootField, browseStorageBtn);
        storageRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(verifactuStorageRootField, Priority.ALWAYS);

        GridPane grid = formGrid();
        addFormRow(grid, 0, t("billing.config.field.modality"), verifactuModalityCombo);
        addFormRow(grid, 1, t("billing.config.field.mode"), verifactuModeCombo);
        addFormRow(grid, 2, t("billing.config.field.cert"), verifactuCertCombo);
        addFormRow(grid, 3, t("billing.config.field.storage_root"), storageRow);

        Label certHint = new Label(certificates.isEmpty()
                ? t("billing.config.cert.hint.empty")
                : certificates.size() + t("billing.config.cert.hint.count_prefix"));
        certHint.getStyleClass().add("settings-hint");

        Label seriesHeader = label(t("billing.config.series.section"), "settings-section-title");
        Label seriesHint = new Label(t("billing.config.series.hint"));
        seriesHint.setWrapText(true);
        seriesHint.getStyleClass().add("settings-hint");

        TableView<SeriesEntry> seriesTable = new TableView<>();
        seriesTable.getStyleClass().add("data-table");
        seriesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        seriesTable.setPlaceholder(new Label(t("billing.config.series.placeholder.empty")));
        TableColumn<SeriesEntry, String> sCode = new TableColumn<>(t("billing.config.series.col.code"));
        sCode.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().code()));
        sCode.setPrefWidth(120);
        TableColumn<SeriesEntry, String> sKind = new TableColumn<>(t("billing.config.series.col.kind"));
        sKind.setCellValueFactory(c -> new SimpleStringProperty(
                "STANDARD".equals(c.getValue().invoiceKind())
                        ? t("billing.config.series.kind.standard.label")
                        : c.getValue().invoiceKind() + t("billing.config.series.kind.system_suffix")));
        sKind.setPrefWidth(160);
        TableColumn<SeriesEntry, String> sFormat = new TableColumn<>(t("billing.config.series.col.format"));
        sFormat.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().formatTemplate()));
        sFormat.setPrefWidth(180);
        TableColumn<SeriesEntry, String> sNext = new TableColumn<>(t("billing.config.series.col.next"));
        sNext.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().nextNumber())));
        sNext.setComparator(NUMERIC_STRING_COMPARATOR);
        sNext.setPrefWidth(140);
        TableColumn<SeriesEntry, String> sYear = new TableColumn<>(t("billing.config.series.col.year"));
        sYear.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().currentYear() == null ? "—" : String.valueOf(c.getValue().currentYear())));
        sYear.setComparator(NUMERIC_STRING_COMPARATOR);
        sYear.setPrefWidth(70);
        seriesTable.getColumns().addAll(List.of(sCode, sKind, sFormat, sNext, sYear));
        seriesTable.setItems(FXCollections.observableArrayList(series));
        seriesTable.setPrefHeight(200);

        boolean hasStandard = series.stream().anyMatch(s -> "STANDARD".equals(s.invoiceKind()));

        // Doble click solo abre editor para STANDARD; sobre reservadas
        // informamos de que son del sistema.
        seriesTable.setRowFactory(tv -> {
            javafx.scene.control.TableRow<SeriesEntry> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    SeriesEntry sel = row.getItem();
                    if ("STANDARD".equals(sel.invoiceKind())) {
                        showSeriesEditor(sel);
                    } else {
                        Alert info = new Alert(Alert.AlertType.INFORMATION,
                                t("billing.config.series.reserved.body"),
                                ButtonType.OK);
                        info.setHeaderText(t("billing.config.series.reserved.header_prefix") + sel.invoiceKind());
                        info.showAndWait();
                    }
                }
            });
            return row;
        });

        Button newSeriesBtn = new Button(hasStandard ? t("billing.config.series.btn.edit") : t("billing.config.series.btn.define"));
        newSeriesBtn.setGraphic(icon(hasStandard ? "fas-edit" : "fas-plus"));
        newSeriesBtn.setOnAction(event -> {
            if (hasStandard) {
                series.stream()
                        .filter(s -> "STANDARD".equals(s.invoiceKind()))
                        .findFirst()
                        .ifPresent(this::showSeriesEditor);
            } else {
                showSeriesEditor(null);
            }
        });

        HBox seriesActions = new HBox(8, newSeriesBtn);

        // ---- Migracion desde otro programa ----
        Label migrationHeader = label(t("billing.config.migration.section"), "settings-section-title");
        Label migrationHint = new Label(t("billing.config.migration.hint"));
        migrationHint.setWrapText(true);
        migrationHint.getStyleClass().add("settings-hint");

        migrationSeriesCombo = new ComboBox<>();
        migrationSeriesCombo.getItems().addAll(series);
        if (!series.isEmpty()) {
            migrationSeriesCombo.getSelectionModel().selectFirst();
        }
        migrationSeriesCombo.getStyleClass().add("form-input");
        migrationSeriesCombo.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(SeriesEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.code() + t("billing.config.migration.combo.suffix_prefix") + item.nextNumber());
            }
        });
        migrationSeriesCombo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(SeriesEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.code() + t("billing.config.migration.combo.suffix_prefix") + item.nextNumber());
            }
        });
        // MIG-2b — Serie EDITABLE: el usuario elige una existente o escribe el
        // código (p. ej. "FRA"). Si no existe, se crea al aplicar. No adivinamos
        // el formato de numeración: la serie la confirma quien la conoce.
        migrationSeriesCombo.setEditable(true);
        migrationSeriesCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(SeriesEntry s) { return s == null ? "" : s.code(); }
            @Override public SeriesEntry fromString(String code) { return resolveMigrationSeries(code); }
        });

        migrationNextNumberField = new TextField();
        migrationNextNumberField.setPromptText(t("billing.config.migration.next.prompt"));
        migrationNextNumberField.getStyleClass().add("form-input");

        // MIG-2c — Año a confirmar (numeración por año): se autorrellena de la
        // fecha detectada y fija el current_year de la serie.
        migrationYearField = new TextField();
        migrationYearField.setPromptText(t("billing.config.migration.year.prompt"));
        migrationYearField.getStyleClass().add("form-input");

        migrationAcknowledgeCheck = new CheckBox(t("billing.config.migration.ack"));
        migrationAcknowledgeCheck.setWrapText(true);

        // MIG-2 — reset del estado de PDF importado al (re)construir la pestaña.
        migrationImportedPdf = null;
        migrationExtracted = null;
        migrationDetectedLabel = new Label();
        migrationDetectedLabel.getStyleClass().add("settings-hint");
        migrationDetectedLabel.setWrapText(true);
        migrationDetectedLabel.setVisible(false);
        migrationDetectedLabel.setManaged(false);

        Label migrationTokenHint = new Label(t("billing.config.migration.tokens.hint"));
        migrationTokenHint.getStyleClass().add("settings-hint");
        migrationTokenHint.setWrapText(true);
        migrationTokenBox = new javafx.scene.layout.FlowPane(8, 8);
        VBox migrationTokenSection = new VBox(6, migrationTokenHint, migrationTokenBox);
        migrationTokenSection.visibleProperty().bind(migrationTokenBox.visibleProperty());
        migrationTokenSection.managedProperty().bind(migrationTokenBox.managedProperty());
        migrationTokenBox.setVisible(false);
        migrationTokenBox.setManaged(false);

        Button importPdfBtn = new Button(t("billing.config.migration.import_pdf"));
        importPdfBtn.setGraphic(icon("fas-file-pdf"));
        importPdfBtn.setOnAction(event -> importMigrationPdf());

        Button applyMigration = new Button(t("billing.config.migration.apply"));
        applyMigration.setGraphic(icon("fas-file-import"));
        applyMigration.setOnAction(event -> applyMigration());

        GridPane migrationGrid = formGrid();
        addFormRow(migrationGrid, 0, t("billing.config.migration.field.series"), migrationSeriesCombo);
        addFormRow(migrationGrid, 1, t("billing.config.migration.field.next"), migrationNextNumberField);
        addFormRow(migrationGrid, 2, t("billing.config.migration.field.year"), migrationYearField);

        Button viewBaselinesBtn = new Button(t("billing.config.migration.view_saved"));
        viewBaselinesBtn.setGraphic(icon("fas-clock-rotate-left"));
        viewBaselinesBtn.setOnAction(event -> host.showMigrationBaselines());

        VBox migrationBlock = new VBox(8,
                migrationHeader,
                migrationHint,
                new HBox(8, importPdfBtn, viewBaselinesBtn),
                migrationDetectedLabel,
                migrationTokenSection,
                migrationGrid,
                migrationAcknowledgeCheck,
                new HBox(applyMigration)
        );

        // ---- Textos legales de factura ----
        Label textsHeader = label(t("billing.config.texts.section"), "settings-section-title");
        Label textsHint = new Label(t("billing.config.texts.hint"));
        textsHint.setWrapText(true);
        textsHint.getStyleClass().add("settings-hint");

        textPieArea = textArea(texts == null ? null : texts.pie(), t("billing.config.texts.prompt.pie"));
        textExemptArea = textArea(texts == null ? null : texts.exempt(), t("billing.config.texts.prompt.exempt"));
        textReverseChargeArea = textArea(texts == null ? null : texts.reverseCharge(), t("billing.config.texts.prompt.reverse"));
        textReducedVatArea = textArea(texts == null ? null : texts.reducedVat(), t("billing.config.texts.prompt.reduced"));
        textRectifyingArea = textArea(texts == null ? null : texts.rectifying(), t("billing.config.texts.prompt.rectifying"));
        textLegalTermsArea = textArea(texts == null ? null : texts.legalTerms(), t("billing.config.texts.prompt.legal_terms"));

        showIbanCheck = new CheckBox(t("billing.config.texts.show_iban"));
        showIbanCheck.setSelected(texts == null || texts.showIban());

        GridPane textsGrid = formGrid();
        addFormRow(textsGrid, 0, t("billing.config.texts.field.pie"), textPieArea);
        addFormRow(textsGrid, 1, t("billing.config.texts.field.exempt"), textExemptArea);
        addFormRow(textsGrid, 2, t("billing.config.texts.field.reverse"), textReverseChargeArea);
        addFormRow(textsGrid, 3, t("billing.config.texts.field.reduced"), textReducedVatArea);
        addFormRow(textsGrid, 4, t("billing.config.texts.field.rectifying"), textRectifyingArea);
        addFormRow(textsGrid, 5, t("billing.config.texts.field.legal_terms"), textLegalTermsArea);

        Button loadStdTexts = new Button(t("billing.config.texts.load_standard"));
        loadStdTexts.setGraphic(icon("fas-shield-alt"));
        loadStdTexts.setOnAction(event -> loadStandardLegalTexts());

        Button saveTexts = new Button(t("billing.config.texts.save"));
        saveTexts.setGraphic(icon("fas-save"));
        saveTexts.setOnAction(event -> saveInvoiceTexts());

        Label loadStdHint = new Label(t("billing.config.texts.load_standard.hint"));
        loadStdHint.getStyleClass().add("settings-hint");
        loadStdHint.setWrapText(true);

        VBox textsBlock = new VBox(8,
                textsHeader, textsHint,
                textsGrid, showIbanCheck,
                loadStdHint,
                new HBox(8, loadStdTexts, saveTexts)
        );

        Button save = new Button(t("billing.config.verifactu.save"));
        save.setGraphic(icon("fas-save"));
        save.setOnAction(event -> saveVerifactuConfig());

        // VF2: comprobación de integridad de la cadena hash. El backend
        // recorre todas las facturas validadas y verifica que la huella
        // SHA-256 cuadra con el input canónico de cada una. Es lo único
        // que da validez fiscal real al sistema; el botón pega tirando
        // del modo TEST (que es donde normalmente operará BENJAGEST hasta
        // que VF3 active el envío real a AEAT).
        Button verifyChain = new Button(t("billing.config.verifactu.verify"));
        verifyChain.setGraphic(icon("fas-shield-alt"));
        verifyChain.setOnAction(event -> verifyVerifactuChain());

        // C2: declaración responsable del fabricante (RD 1007/2023 art.
        // 15). Información pública del producto exigida por ley en el
        // SIF mismo — no es una compra ni un upgrade, es un dato que
        // el operador debe poder ver bajo demanda.
        Button manufacturerBtn = new Button(t("billing.config.manufacturer.btn"));
        manufacturerBtn.setGraphic(icon("fas-info-circle"));
        manufacturerBtn.setOnAction(event -> host.showManufacturerDeclaration());

        // VF-EVENTS-B: bloque de Registro de Eventos del SIF — solo es
        // legalmente obligatorio en NO VeriFactu, pero lo mostramos en
        // ambas modalidades porque (a) las facturas anteriores al
        // cambio de modalidad pueden tener eventos, (b) la pestaña es
        // de solo lectura y sirve de evidencia auditable.
        Node sifEventsBlock = new SifAuditScreen(
                billingApiClient, this::t, router).buildSection();

        HBox actions = new HBox(8, save, verifyChain, manufacturerBtn);
        actions.getStyleClass().add("settings-actions");

        Node vatRatesBlock = new VatRatesScreen(
                billingApiClient, this::t, router).buildSection();
        Node vatRegimeBlock = vatRegimeBlock();

        VBox body = new VBox(16,
                section, hint, grid, certHint,
                new Separator(),
                seriesHeader, seriesHint, seriesTable, seriesActions,
                new Separator(),
                migrationBlock,
                new Separator(),
                textsBlock,
                new Separator(),
                vatRegimeBlock,
                new Separator(),
                vatRatesBlock,
                new Separator(),
                sifEventsBlock
        );
        return tabLayout(label(t("billing.config.tab_title"), "settings-section-title"), body, actions);
    }

    /** VAT-REGIME — bloque del régimen de IVA (General / Prorrata / Criterio de caja). */
    private Node vatRegimeBlock() {
        Label header = label(t("billing.config.regime.section"), "settings-section-title");
        Label hint = new Label(t("billing.config.regime.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(
                "GENERAL", "PRORRATA", "CASH_CRITERION"));
        combo.getStyleClass().add("form-input");
        combo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String c) {
                if (c == null) return "";
                return switch (c) {
                    case "GENERAL" -> t("billing.config.regime.general");
                    case "PRORRATA" -> t("billing.config.regime.prorrata");
                    case "CASH_CRITERION" -> t("billing.config.regime.cash");
                    default -> c;
                };
            }
            @Override public String fromString(String s) { return s; }
        });
        combo.setValue("GENERAL");

        TextField prorrata = new TextField();
        prorrata.setPromptText("%");
        prorrata.setMaxWidth(120);
        HBox prorrataRow = new HBox(8, new Label(t("billing.config.regime.prorrata_pct")), prorrata);
        prorrataRow.setAlignment(Pos.CENTER_LEFT);
        Runnable toggleProrrata = () -> {
            boolean show = "PRORRATA".equals(combo.getValue());
            prorrataRow.setVisible(show);
            prorrataRow.setManaged(show);
        };
        combo.valueProperty().addListener((o, a, b) -> toggleProrrata.run());
        toggleProrrata.run();

        Button save = new Button(t("billing.config.regime.save"));
        save.setGraphic(icon("fas-save"));
        save.setOnAction(e -> {
            save.setDisable(true);
            String regime = combo.getValue();
            String pct = "PRORRATA".equals(regime) ? prorrata.getText() : null;
            Task<com.benjagest.ui.service.BillingApiClient.VatRegime> tk = new Task<>() {
                @Override protected com.benjagest.ui.service.BillingApiClient.VatRegime call() throws Exception {
                    return billingApiClient.saveVatRegime(regime, pct);
                }
            };
            tk.setOnSucceeded(ev -> { save.setDisable(false);
                    showInfo(t("billing.config.regime.section"), t("billing.config.regime.saved")); });
            tk.setOnFailed(ev -> { save.setDisable(false);
                    showError(t("billing.config.regime.section"),
                            tk.getException() == null ? "" : tk.getException().getMessage()); });
            start(tk, "vat-regime-save");
        });

        Task<com.benjagest.ui.service.BillingApiClient.VatRegime> load = new Task<>() {
            @Override protected com.benjagest.ui.service.BillingApiClient.VatRegime call() throws Exception {
                return billingApiClient.getVatRegime();
            }
        };
        load.setOnSucceeded(ev -> {
            var v = load.getValue();
            if (v.regime() != null && !v.regime().isBlank()) combo.setValue(v.regime());
            if (v.prorrataPercent() != null) prorrata.setText(v.prorrataPercent());
            toggleProrrata.run();
        });
        javafx.application.Platform.runLater(() -> start(load, "vat-regime-load"));

        GridPane g = formGrid();
        addFormRow(g, 0, t("billing.config.regime.field"), combo);
        return new VBox(8, header, hint, g, prorrataRow, new HBox(save));
    }

    private javafx.scene.control.TextArea textArea(String value, String prompt) {
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(value == null ? "" : value);
        area.setPromptText(prompt);
        area.setPrefRowCount(2);
        area.setWrapText(true);
        area.getStyleClass().add("form-input");
        return area;
    }

    private void applyMigration() {
        // Resolver la serie del combo EDITABLE: el valor elegido de la lista o
        // el código tecleado (que puede no existir aún → se creará).
        SeriesEntry serie = migrationSeriesCombo.getValue();
        String typed = migrationSeriesCombo.getEditor() == null ? null
                : migrationSeriesCombo.getEditor().getText();
        if (typed != null && !typed.isBlank()
                && (serie == null || !typed.trim().equalsIgnoreCase(serie.code()))) {
            serie = resolveMigrationSeries(typed);
        }
        if (serie == null || serie.code() == null || serie.code().isBlank()) {
            showError(t("billing.config.migration.error.no_series.title"),
                    t("billing.config.migration.error.no_series.body"));
            return;
        }
        if (!migrationAcknowledgeCheck.isSelected()) {
            showError(t("billing.config.migration.error.no_ack.title"),
                    t("billing.config.migration.error.no_ack.body"));
            return;
        }
        Integer next;
        try {
            next = Integer.parseInt(migrationNextNumberField.getText().trim());
        } catch (NumberFormatException ex) {
            showError(t("billing.config.migration.error.bad_number.title"),
                    t("billing.config.migration.error.bad_number.body"));
            return;
        }
        if (next < 1) {
            showError(t("billing.config.migration.error.bad_number.title"),
                    t("billing.config.migration.error.bad_number.body_low"));
            return;
        }
        Integer yr = null;
        try {
            String y = migrationYearField == null ? null : migrationYearField.getText();
            if (y != null && !y.isBlank()) yr = Integer.parseInt(y.trim());
        } catch (NumberFormatException ignored) { /* año vacío/erróneo → null */ }

        final SeriesEntry serieF = serie;
        final int nextNumber = next;
        final Integer declaredYear = yr;
        final String fmt = migrationFormatTemplate == null || migrationFormatTemplate.isBlank()
                ? null : migrationFormatTemplate;
        final byte[] pdf = migrationImportedPdf;
        final com.benjagest.ui.service.BillingApiClient.MigrationExtracted ext = migrationExtracted;
        final String declarationText = t("billing.config.migration.ack");

        Runnable onOk = () -> {
            Alert ok = new Alert(Alert.AlertType.INFORMATION,
                    t("billing.config.migration.success_prefix") + serieF.code()
                            + t("billing.config.migration.success.middle") + nextNumber + ".",
                    ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
            migrationImportedPdf = null;
            migrationExtracted = null;
            host.refreshBillingConfig();
        };

        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                // Si la serie NO existe (id null), se crea ahora con el próximo
                // número ya fijado. Si existe, se ajusta su número.
                String seriesId = serieF.id();
                boolean created = false;
                if (seriesId == null) {
                    SeriesEntry s = billingApiClient.createSeries(
                            serieF.code(), "STANDARD", "BY_YEAR", fmt, nextNumber, false);
                    seriesId = s.id();
                    created = true;
                }
                if (pdf != null) {
                    // Guarda PDF de prueba + declaración firmada (fijar el número
                    // de nuevo es inocuo: mismo valor).
                    billingApiClient.confirmMigrationBaseline(
                            seriesId, serieF.code(),
                            ext == null ? null : ext.invoiceNumber(),
                            nextNumber - 1,
                            ext == null ? null : ext.invoiceDateIso(),
                            declaredYear,
                            ext == null ? null : ext.emitterNif(),
                            ext == null ? null : ext.customerNif(),
                            ext == null ? null : ext.customerName(),
                            ext == null ? null : ext.totalAmount(),
                            ext == null ? null : ext.confidence(),
                            true, declarationText, pdf);
                } else if (!created) {
                    // Serie existente, sin PDF: solo ajustar el próximo número.
                    billingApiClient.migrateSeries(seriesId, nextNumber, true);
                }
                return null;
            }
        };
        task.setOnSucceeded(event -> onOk.run());
        task.setOnFailed(event -> showError(t("billing.config.migration.fail.title"),
                task.getException() == null ? t("billing.config.migration.fail.body")
                        : task.getException().getMessage()));
        start(task, "migration-apply");
    }

    /** MIG-2 — Importa el PDF de la última factura y autorellena por OCR. */
    private void importMigrationPdf() {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle(t("billing.config.migration.import_pdf"));
        fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("PDF", "*.pdf"));
        java.io.File file = fc.showOpenDialog(window());
        if (file == null) return;
        final byte[] bytes;
        try {
            bytes = java.nio.file.Files.readAllBytes(file.toPath());
        } catch (Exception ex) {
            showError(t("billing.config.migration.import_pdf"), ex.getMessage());
            return;
        }
        Task<com.benjagest.ui.service.BillingApiClient.MigrationExtracted> task = new Task<>() {
            @Override protected com.benjagest.ui.service.BillingApiClient.MigrationExtracted call() throws Exception {
                return billingApiClient.extractMigrationBaseline(bytes);
            }
        };
        task.setOnSucceeded(ev -> {
            var ext = task.getValue();
            migrationImportedPdf = bytes;
            migrationExtracted = ext;
            // Troceador: el usuario etiqueta cada parte del número. De ahí salen
            // serie, año, próximo número y la plantilla de formato.
            String yearHint = ext.invoiceDateIso() != null && ext.invoiceDateIso().length() >= 4
                    ? ext.invoiceDateIso().substring(0, 4) : null;
            buildMigrationTokenTagger(ext.invoiceNumber(), yearHint);
            String sb = t("billing.config.migration.detected.header")
                    + "\n• " + t("billing.config.migration.detected.number") + ": " + nzDash(ext.invoiceNumber())
                    + "\n• " + t("billing.config.migration.detected.series") + ": " + nzDash(ext.seriesCodeGuess())
                    + "\n• " + t("billing.config.migration.detected.date") + ": " + nzDash(ext.invoiceDateIso())
                    + "\n• " + t("billing.config.migration.detected.customer") + ": "
                            + nzDash(ext.customerName()) + "  " + nzDash(ext.customerNif())
                    + "\n• " + t("billing.config.migration.detected.total") + ": " + nzDash(ext.totalAmount())
                    + (ext.confidence() == null ? ""
                            : "   (" + t("billing.config.migration.detected.confidence") + ": " + ext.confidence() + ")");
            migrationDetectedLabel.setText(sb);
            migrationDetectedLabel.setVisible(true);
            migrationDetectedLabel.setManaged(true);
        });
        task.setOnFailed(ev -> showError(t("billing.config.migration.import_pdf"),
                task.getException() == null ? "" : task.getException().getMessage()));
        start(task, "migration-extract");
    }

    private static String nzDash(String s) { return s == null || s.isBlank() ? "—" : s; }

    /**
     * Resuelve el código de serie tecleado/elegido en la migración: si coincide
     * (sin distinguir mayúsculas) con una serie existente, la devuelve; si no,
     * devuelve una serie sintética con id=null (señal de "crear al aplicar").
     */
    private SeriesEntry resolveMigrationSeries(String code) {
        if (code == null || code.isBlank()) return null;
        String c = code.trim();
        if (migrationSeriesCombo != null) {
            for (SeriesEntry s : migrationSeriesCombo.getItems()) {
                if (s != null && c.equalsIgnoreCase(s.code())) return s;
            }
        }
        return new SeriesEntry(null, c, "STANDARD", "BY_YEAR", null, 0, null, false, true);
    }

    /**
     * MIG-2c — Trocea el número detectado (p. ej. "FRA-2026-0007") en sus
     * partes y deja que el usuario etiquete cada una: Serie / Año / Número /
     * Texto fijo. De ahí salen el código, el año, el próximo número y la
     * plantilla de formato ({CODE}-{YYYY}-{0000}) para que las próximas
     * facturas salgan idénticas. Auto-sugiere roles, pero manda el usuario.
     */
    private void buildMigrationTokenTagger(String fullNumber, String yearHint) {
        migrationTokenBox.getChildren().clear();
        migrationSegTexts = new java.util.ArrayList<>();
        migrationSegIsToken = new java.util.ArrayList<>();
        migrationRoleCombos = new java.util.ArrayList<>();
        migrationFormatTemplate = null;
        if (fullNumber == null || fullNumber.isBlank()) {
            migrationTokenBox.setVisible(false);
            migrationTokenBox.setManaged(false);
            return;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z0-9]+").matcher(fullNumber);
        int idx = 0;
        while (m.find()) {
            if (m.start() > idx) {
                migrationSegTexts.add(fullNumber.substring(idx, m.start()));
                migrationSegIsToken.add(false);
            }
            migrationSegTexts.add(m.group());
            migrationSegIsToken.add(true);
            idx = m.end();
        }
        if (idx < fullNumber.length()) {
            migrationSegTexts.add(fullNumber.substring(idx));
            migrationSegIsToken.add(false);
        }
        int lastTokenSeg = -1;
        for (int i = 0; i < migrationSegIsToken.size(); i++) {
            if (migrationSegIsToken.get(i)) lastTokenSeg = i;
        }
        String roleSerie = t("billing.config.migration.role.series");
        String roleYear = t("billing.config.migration.role.year");
        String roleNumber = t("billing.config.migration.role.number");
        String roleFixed = t("billing.config.migration.role.fixed");
        for (int i = 0; i < migrationSegTexts.size(); i++) {
            String text = migrationSegTexts.get(i);
            if (!migrationSegIsToken.get(i)) {
                migrationRoleCombos.add(null);
                Label sep = new Label(text);
                sep.setStyle("-fx-font-size: 18px; -fx-padding: 20 2 0 2;");
                migrationTokenBox.getChildren().add(sep);
                continue;
            }
            ComboBox<String> role = new ComboBox<>();
            role.getItems().addAll(roleSerie, roleYear, roleNumber, roleFixed);
            String suggested;
            if (i == lastTokenSeg && text.matches("\\d+")) suggested = roleNumber;
            else if (text.matches("\\d{4}") && (yearHint == null || text.equals(yearHint))) suggested = roleYear;
            else if (text.matches(".*[A-Za-z].*")) suggested = roleSerie;
            else suggested = roleFixed;
            role.setValue(suggested);
            role.valueProperty().addListener((o, a, b) -> recomputeMigrationFromTokens());
            migrationRoleCombos.add(role);
            Label tokLbl = new Label(text);
            tokLbl.setStyle("-fx-font-weight: 700;");
            VBox cell = new VBox(2, tokLbl, role);
            cell.setAlignment(Pos.CENTER_LEFT);
            migrationTokenBox.getChildren().add(cell);
        }
        migrationTokenBox.setVisible(true);
        migrationTokenBox.setManaged(true);
        recomputeMigrationFromTokens();
    }

    /** Recalcula código/año/nº/plantilla a partir de las etiquetas del troceador. */
    private void recomputeMigrationFromTokens() {
        if (migrationSegTexts == null) return;
        String roleSerie = t("billing.config.migration.role.series");
        String roleYear = t("billing.config.migration.role.year");
        String roleNumber = t("billing.config.migration.role.number");
        StringBuilder tpl = new StringBuilder();
        String code = null;
        String year = null;
        Integer number = null;
        boolean codeUsed = false;
        for (int i = 0; i < migrationSegTexts.size(); i++) {
            String text = migrationSegTexts.get(i);
            if (!migrationSegIsToken.get(i)) { tpl.append(text); continue; }
            ComboBox<String> role = migrationRoleCombos.get(i);
            String r = role == null ? null : role.getValue();
            if (roleSerie.equals(r)) {
                if (!codeUsed) { tpl.append("{CODE}"); code = text; codeUsed = true; }
                else tpl.append(text);
            } else if (roleYear.equals(r)) {
                tpl.append("{YYYY}");
                year = text;
            } else if (roleNumber.equals(r)) {
                tpl.append("{").append("0".repeat(Math.max(1, text.length()))).append("}");
                try { number = Integer.parseInt(text); } catch (NumberFormatException ignored) { }
            } else {
                tpl.append(text);
            }
        }
        migrationFormatTemplate = tpl.toString();
        if (code != null && migrationSeriesCombo.getEditor() != null) {
            migrationSeriesCombo.getEditor().setText(code);
        }
        if (year != null) migrationYearField.setText(year);
        if (number != null) migrationNextNumberField.setText(String.valueOf(number + 1));
    }

    // ----- Editor de series (crear/editar) -----

    /**
     * Dialogo modal de creacion/edicion de una serie. El proximo numero
     * solo es editable en CREATE (en UPDATE el backend lo rechaza para
     * no abrir agujero legal — si necesitas mover el correlativo usa
     * Migracion desde otro programa). El backend tambien bloquea cambios
     * de code/format/kind cuando la serie ya emitio facturas validadas
     * este ano.
     */
    private void showSeriesEditor(SeriesEntry existing) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? t("billing.series.editor.title.create") : t("billing.series.editor.title.edit"));
        dialog.setHeaderText(null);

        TextField codeField = new TextField(existing == null ? "" : existing.code());
        codeField.setPromptText(t("billing.series.editor.code.prompt"));
        codeField.getStyleClass().add("form-input");

        // Tipo factura: fijo a STANDARD (el usuario solo define su serie
        // de facturas normales). Las PROF/RECT las gestiona el sistema.
        Label kindFixedLabel = new Label(t("billing.series.editor.kind.fixed"));
        kindFixedLabel.getStyleClass().add("invoice-pill");

        ComboBox<String> numberingCombo = new ComboBox<>();
        numberingCombo.getItems().addAll("STANDARD", "BY_YEAR", "PREFIXED");
        localizeEnumCombo(numberingCombo, "numbering");
        numberingCombo.getSelectionModel().select(existing == null ? "BY_YEAR" : existing.numberingType());
        numberingCombo.getStyleClass().add("form-input");

        TextField formatField = new TextField(existing == null
                ? "{CODE}-{YYYY}-{0000}"
                : (existing.formatTemplate() == null ? "" : existing.formatTemplate()));
        formatField.setPromptText(t("billing.series.editor.format.prompt"));
        formatField.getStyleClass().add("form-input");

        TextField nextNumberField = new TextField(existing == null ? "1" : String.valueOf(existing.nextNumber()));
        nextNumberField.getStyleClass().add("form-input");
        nextNumberField.setDisable(existing != null);

        Label nextNumberHint = new Label(existing == null
                ? t("billing.series.editor.next.hint.create")
                : t("billing.series.editor.next.hint.edit"));
        nextNumberHint.setWrapText(true);
        nextNumberHint.getStyleClass().add("settings-hint");

        Label autoLockHint = new Label(t("billing.series.editor.autolock.hint"));
        autoLockHint.setWrapText(true);
        autoLockHint.getStyleClass().add("settings-hint");

        GridPane grid = formGrid();
        addFormRow(grid, 0, t("billing.series.editor.field.code"), codeField);
        addFormRow(grid, 1, t("billing.series.editor.field.kind"), kindFixedLabel);
        addFormRow(grid, 2, t("billing.series.editor.field.numbering"), numberingCombo);
        addFormRow(grid, 3, t("billing.series.editor.field.format"), formatField);
        addFormRow(grid, 4, t("billing.series.editor.field.next"), nextNumberField);

        VBox dialogBody = new VBox(12, grid, nextNumberHint, autoLockHint);
        dialogBody.setPadding(new Insets(8));
        installDialog(dialog, dialogBody);

        ButtonType saveBtn = new ButtonType(existing == null ? t("billing.series.editor.btn.create") : t("billing.series.editor.btn.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        // Validacion local antes de cerrar el dialogo: codigo no vacio
        // y, si es CREATE, proximo numero entero >= 1.
        Node saveButton = dialog.getDialogPane().lookupButton(saveBtn);
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            if (codeField.getText().trim().isBlank()) {
                ev.consume();
                showError(t("billing.series.editor.error.no_code.title"), t("billing.series.editor.error.no_code.body"));
                return;
            }
            if (existing == null) {
                try {
                    int n = Integer.parseInt(nextNumberField.getText().trim());
                    if (n < 1) throw new NumberFormatException();
                } catch (NumberFormatException ex) {
                    ev.consume();
                    showError(t("billing.series.editor.error.bad_number.title"), t("billing.series.editor.error.bad_number.body"));
                }
            }
        });

        dialog.setResultConverter(bt -> bt == saveBtn);
        Optional<Boolean> result = dialog.showAndWait();
        if (result.isEmpty() || !result.get()) {
            return;
        }

        String code = codeField.getText().trim();
        String kind = "STANDARD"; // El usuario solo define su STANDARD.
        String numbering = numberingCombo.getValue();
        String format = formatField.getText();
        // El locked desaparece del editor: se infiere de las emisiones
        // (countValidatedInYear > 0 → no editable). Asi por convencion.
        Integer initialNext = null;
        if (existing == null) {
            try {
                initialNext = Integer.parseInt(nextNumberField.getText().trim());
            } catch (NumberFormatException ignored) {
                return;
            }
        }
        final Integer finalInitialNext = initialNext;

        Task<SeriesEntry> task = new Task<>() {
            @Override
            protected SeriesEntry call() throws Exception {
                if (existing == null) {
                    return billingApiClient.createSeries(code, kind, numbering, format, finalInitialNext, false);
                }
                return billingApiClient.updateSeries(existing.id(), code, kind, numbering, format, false);
            }
        };
        task.setOnSucceeded(ev -> {
            // Refrescamos toda la pantalla de Facturacion para que tanto el
            // listado de series como el combo del editor de facturas vean
            // la serie nueva/actualizada inmediatamente. Aterrizamos en la
            // pestaña Configuracion para que el usuario vea su cambio
            // reflejado sin tener que cambiar de tab.
            host.refreshBillingConfig();
        });
        task.setOnFailed(ev -> showError(
                existing == null ? t("billing.series.editor.fail.create.title") : t("billing.series.editor.fail.save.title"),
                t("billing.series.editor.fail.body")));
        start(task, "billing-series-save");
    }

    /**
     * Rellena los textos legales VACÍOS con redacciones estándar que citan la
     * ley aplicable (editables después). No machaca lo que el usuario ya haya
     * escrito. Contenido en español (la factura española es en español).
     */
    private void loadStandardLegalTexts() {
        fillIfBlank(textExemptArea,
                "Operación exenta del IVA conforme al artículo 20 de la Ley 37/1992, de 28 de "
                + "diciembre, del Impuesto sobre el Valor Añadido.");
        fillIfBlank(textReverseChargeArea,
                "Operación con inversión del sujeto pasivo (artículo 84.Uno.2º de la Ley 37/1992 "
                + "del IVA). Esta factura no incluye IVA; su autoliquidación corresponde al destinatario.");
        fillIfBlank(textReducedVatArea,
                "Tipo impositivo reducido aplicado conforme al artículo 91 de la Ley 37/1992 del IVA.");
        fillIfBlank(textRectifyingArea,
                "Factura rectificativa expedida conforme al artículo 15 del Real Decreto 1619/2012, "
                + "por el que se aprueba el Reglamento que regula las obligaciones de facturación.");
        fillIfBlank(textLegalTermsArea,
                "El impago a su vencimiento devengará intereses de demora conforme a la Ley 3/2004, "
                + "de 29 de diciembre, de lucha contra la morosidad en las operaciones comerciales.");
        fillIfBlank(textPieArea,
                "En cumplimiento del Reglamento (UE) 2016/679 (RGPD) y la Ley Orgánica 3/2018 "
                + "(LOPDGDD), los datos personales se tratan para la gestión de la relación comercial. "
                + "Puede ejercer sus derechos dirigiéndose a la dirección de la empresa.");
        toast(window(), t("billing.config.texts.load_standard.done"));
    }

    private void fillIfBlank(javafx.scene.control.TextArea area, String standard) {
        if (area != null && (area.getText() == null || area.getText().isBlank())) {
            area.setText(standard);
        }
    }

    private void saveInvoiceTexts() {
        InvoiceTexts payload = new InvoiceTexts(
                textPieArea.getText(),
                textExemptArea.getText(),
                textReverseChargeArea.getText(),
                textReducedVatArea.getText(),
                textRectifyingArea.getText(),
                textLegalTermsArea.getText(),
                showIbanCheck.isSelected()
        );
        Task<InvoiceTexts> task = new Task<>() {
            @Override
            protected InvoiceTexts call() throws Exception {
                return billingApiClient.updateInvoiceTexts(payload);
            }
        };
        task.setOnSucceeded(event -> {
            Alert ok = new Alert(Alert.AlertType.INFORMATION,
                    t("billing.texts.save.success"), ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
        });
        task.setOnFailed(event -> showError(t("billing.texts.save.fail.title"),
                t("billing.texts.save.fail.body")));
        start(task, "billing-texts-save");
    }

    private void saveVerifactuConfig() {
        saveVerifactuConfig(true);
    }

    /**
     * @param showSuccessAlert true desde el botón "Guardar VeriFactu"
     *        (feedback explícito); false desde acciones que ya tienen
     *        su propio feedback (p.ej. tras elegir carpeta en el
     *        DirectoryChooser — molestaría un alert genérico encima
     *        del propio acto de elegir).
     */
    private void saveVerifactuConfig(boolean showSuccessAlert) {
        String modality = verifactuModalityCombo.getValue();
        String mode = verifactuModeCombo.getValue();
        CertificateOption cert = verifactuCertCombo.getValue();
        String certId = cert == null ? null : cert.id();
        // El pie ahora vive solo en "Textos legales" (textPieArea). Al guardar
        // VeriFactu reenviamos ese mismo valor para no machacar la columna
        // (ambos guardan companies.invoice_footer_template).
        String footer = textPieArea == null ? null : textPieArea.getText();
        String storageRoot = verifactuStorageRootField == null ? null : verifactuStorageRootField.getText();

        Task<VerifactuConfig> task = new Task<>() {
            @Override
            protected VerifactuConfig call() throws Exception {
                return billingApiClient.updateVerifactuConfig(modality, mode, certId, footer, storageRoot);
            }
        };
        task.setOnSucceeded(event -> {
            if (!showSuccessAlert) return;
            String detail = "VERIFACTU".equals(modality)
                    ? localizedModality(modality) + " (" + mode + ")"
                    : localizedModality(modality);
            Alert ok = new Alert(Alert.AlertType.INFORMATION,
                    t("billing.verifactu.save.success_prefix") + detail + t("billing.verifactu.save.success_suffix"),
                    ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
        });
        task.setOnFailed(event -> showError(t("billing.verifactu.save.fail.title"),
                t("billing.verifactu.save.fail.body")));
        start(task, "billing-config-save");
    }

    /**
     * Abre un DirectoryChooser del sistema (Explorador en Windows /
     * Finder en macOS / dialog GTK en Linux) para que el usuario
     * elija una carpeta donde almacenar las facturas. El dialog del
     * SO permite navegar Y crear carpetas nuevas con el botón estándar,
     * así no necesitamos UI propia para eso.
     *
     * - Si el TextField ya tiene una ruta válida, abre directamente en
     *   esa carpeta (UX: la navegación arranca donde el usuario estaba).
     * - Si está vacío o la ruta no existe, abre en el home del usuario.
     * - Cancelar el dialog deja el TextField como estaba.
     * - Al confirmar, el path absoluto se vuelca al TextField. El
     *   backend usará esa raíz para `{root}/{companyId}/{YYYY}/T{q}/{nº}.pdf`.
     */
    private void chooseInvoiceStorageDir() {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle(t("billing.config.field.storage_root.dialog_title"));
        String current = verifactuStorageRootField == null ? null : verifactuStorageRootField.getText();
        if (current != null && !current.isBlank()) {
            java.io.File initial = new java.io.File(current.trim());
            if (initial.exists() && initial.isDirectory()) {
                chooser.setInitialDirectory(initial);
            }
        }
        if (chooser.getInitialDirectory() == null) {
            java.io.File home = new java.io.File(System.getProperty("user.home"));
            if (home.isDirectory()) chooser.setInitialDirectory(home);
        }
        java.io.File selected = chooser.showDialog(window());
        if (selected != null) {
            verifactuStorageRootField.setText(selected.getAbsolutePath());
            // Persistencia automática: el usuario eligió una carpeta,
            // espera que quede grabada. Si solo dejásemos el texto en
            // el campo, al salir de la pantalla y volver se perdería.
            // Sin alert porque el propio acto de elegir ya es feedback
            // visual suficiente.
            saveVerifactuConfig(false);
        }
    }

    /**
     * Etiqueta traducida para la modalidad VeriFactu. Internamente el
     * combo guarda el código técnico (VERIFACTU / NO_VERIFACTU) que el
     * backend espera; aquí solo se traduce para mostrarlo al usuario.
     */
    private String localizedModality(String code) {
        if (code == null) return "";
        return switch (code) {
            case "VERIFACTU" -> t("billing.config.modality.verifactu");
            case "NO_VERIFACTU" -> t("billing.config.modality.no_verifactu");
            default -> code;
        };
    }

    /**
     * Dispara verificación del hash encadenado VeriFactu contra el
     * modo seleccionado en el combo. Si la cadena es íntegra → mensaje
     * de éxito con el total de facturas comprobadas. Si está rota →
     * mensaje de error con el número de la primera factura sospechosa
     * y la razón devuelta por el backend.
     *
     * Si el modo es OFF, ni siquiera se intenta — sin VeriFactu activo
     * no hay cadena que verificar. Mostramos un mensaje claro.
     */
    private void verifyVerifactuChain() {
        // Tras VF-OFF-DEPRECATE el hash existe en ambas modalidades, así
        // que el verify siempre se puede lanzar. Solo necesitamos el
        // environment (TEST/PROD) para saber qué cadena del registro
        // recorre el backend.
        String mode = verifactuModeCombo.getValue();
        if (mode == null) {
            mode = "TEST";
        }
        final String chainMode = mode;
        Task<com.benjagest.ui.model.VerifactuIntegrityResult> task = new Task<>() {
            @Override
            protected com.benjagest.ui.model.VerifactuIntegrityResult call() throws Exception {
                return billingApiClient.verifyVerifactuChain(chainMode);
            }
        };
        task.setOnSucceeded(event -> {
            com.benjagest.ui.model.VerifactuIntegrityResult result = task.getValue();
            if (result.ok()) {
                String body = t("billing.config.verifactu.verify.ok.prefix")
                        + result.totalChecked()
                        + t("billing.config.verifactu.verify.ok.suffix");
                Alert ok = new Alert(Alert.AlertType.INFORMATION, body, ButtonType.OK);
                ok.setHeaderText(null);
                ok.showAndWait();
            } else {
                String broken = result.brokenInvoiceNumber() == null
                        ? "—" : result.brokenInvoiceNumber();
                String body = t("billing.config.verifactu.verify.broken.prefix")
                        + broken + "\n"
                        + (result.reason() == null ? "" : result.reason());
                showError(t("billing.config.verifactu.verify.broken.title"), body);
            }
        });
        task.setOnFailed(event -> showError(
                t("billing.config.verifactu.verify.fail.title"),
                t("billing.config.verifactu.verify.fail.body")));
        start(task, "billing-config-verify");
    }

    // ----- helpers locales (copias stateless del shell) -----

    private javafx.stage.Window window() {
        return migrationTokenBox == null || migrationTokenBox.getScene() == null
                ? null : migrationTokenBox.getScene().getWindow();
    }

    private TextField textInput(String value, String prompt) {
        TextField field = new TextField(value == null ? "" : value);
        field.setPromptText(prompt);
        return field;
    }

    private GridPane formGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.getStyleClass().add("form-grid");
        return grid;
    }

    private void addFormRow(GridPane grid, int row, String labelText, javafx.scene.control.Control input) {
        Label fieldLabel = new Label(labelText);
        fieldLabel.getStyleClass().add("form-label");
        input.getStyleClass().add("form-input");
        grid.add(fieldLabel, 0, row);
        grid.add(input, 1, row);
        GridPane.setHgrow(input, Priority.ALWAYS);
    }

    /**
     * Overload para celdas compuestas (TextField + botón en HBox, p.ej.
     * el selector de ruta de almacenamiento). No fuerza el style class
     * "form-input" sobre el Node — cada hijo se estiló desde fuera.
     */
    private void addFormRow(GridPane grid, int row, String labelText, javafx.scene.Node input) {
        Label fieldLabel = new Label(labelText);
        fieldLabel.getStyleClass().add("form-label");
        grid.add(fieldLabel, 0, row);
        grid.add(input, 1, row);
        GridPane.setHgrow(input, Priority.ALWAYS);
    }

    /**
     * Patron compartido por los tabs de FORMULARIO: cabecera arriba, cuerpo
     * DESPLAZABLE en el centro (scroll vertical si no entra) y acciones ancladas
     * al pie siempre visibles aunque el portatil tenga pantalla pequena.
     */
    private Node tabLayout(Node header, Node body, Node footerActions) {
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("settings-inner-scroll");

        VBox bottom = new VBox(12, new Separator(), footerActions);
        BorderPane layout = new BorderPane();
        layout.setTop(header);
        layout.setCenter(scroll);
        layout.setBottom(bottom);
        layout.getStyleClass().add("settings-tab-body");
        BorderPane.setMargin(scroll, new Insets(12, 0, 12, 0));
        return layout;
    }
}
