package com.benjagest.ui;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.kordamp.ikonli.javafx.FontIcon;

import com.benjagest.ui.model.CompanyData;
import com.benjagest.ui.model.CompanyModuleEntry;
import com.benjagest.ui.model.DashboardData;
import com.benjagest.ui.model.DashboardItem;
import com.benjagest.ui.model.EmailConfig;
import com.benjagest.ui.model.IssuerCreateRequest;
import com.benjagest.ui.model.IssuerSummary;
import com.benjagest.ui.model.Membership;
import com.benjagest.ui.model.ModuleData;
import com.benjagest.ui.model.ModuleRow;
import com.benjagest.ui.model.SessionInfo;
import com.benjagest.ui.service.AuthApiClient;
import com.benjagest.ui.service.AuthSession;
import com.benjagest.ui.service.IssuerApiClient;
import com.benjagest.ui.service.SettingsApiClient;
import com.benjagest.ui.service.WorkspaceApiClient;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.CheckBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

public class BenjagestUiApplication extends Application {

    private static final List<ModuleLink> ADVISORY_MODULES = List.of(
            new ModuleLink("customers", "Clientes", "fas-users"),
            new ModuleLink("tax", "Fiscal", "fas-percentage"),
            new ModuleLink("labor", "Laboral", "fas-hard-hat"),
            new ModuleLink("billing", "Facturacion", "fas-file-invoice-dollar"),
            new ModuleLink("issuers", "Emisores", "fas-file-signature"),
            new ModuleLink("purchases", "Compras", "fas-receipt"),
            new ModuleLink("reports", "Informes", "fas-chart-line"),
            new ModuleLink("calendar", "Agenda", "fas-calendar-alt"),
            new ModuleLink("settings", "Configuracion", "fas-cog")
    );

    private static final List<ModuleLink> BUSINESS_MODULES = List.of(
            new ModuleLink("customers", "Clientes", "fas-users"),
            new ModuleLink("billing", "Facturacion", "fas-file-invoice-dollar"),
            new ModuleLink("issuers", "Emisores", "fas-file-signature"),
            new ModuleLink("purchases", "Compras", "fas-receipt"),
            new ModuleLink("labor", "Laboral", "fas-hard-hat"),
            new ModuleLink("tax", "Fiscal", "fas-percentage"),
            new ModuleLink("reports", "Informes", "fas-chart-line"),
            new ModuleLink("calendar", "Agenda", "fas-calendar-alt"),
            new ModuleLink("settings", "Configuracion", "fas-cog")
    );

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final NumberFormat CURRENCY_FORMAT = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-ES"));

    private final WorkspaceApiClient apiClient = new WorkspaceApiClient();
    private final IssuerApiClient issuerApiClient = new IssuerApiClient();
    private final AuthApiClient authApiClient = new AuthApiClient();
    private final SettingsApiClient settingsApiClient = new SettingsApiClient();
    private final Map<String, Button> navigationButtons = new LinkedHashMap<>();

    private BorderPane root;
    private SessionInfo session;
    private IssuerSummary activeIssuer;
    private Language language = Language.ES;
    private AppMode appMode = AppMode.ADVISORY;
    private String currentModule = "dashboard";
    // Cache de los modulos activos en el catalogo (lo rellena
    // loadActiveModulesCache desde /api/modules-catalog/active). Cuando
    // esta vacio, activeModules() cae al fallback hardcodeado.
    private List<ModuleLink> activeModulesCache = List.of();
    // Cambios pendientes en la pestana Modulos: slug -> nuevo estado.
    // Vacio = no hay cambios sin guardar. Se vacia al guardar o al
    // entrar otra vez en Configuracion.
    private final java.util.Map<String, Boolean> pendingModuleChanges = new java.util.LinkedHashMap<>();
    // Estado "real" de cada modulo segun backend. Mutable: tras guardar
    // se sincroniza con lo que acabamos de persistir, asi el listener
    // del checkbox compara contra el valor actualizado en lugar de uno
    // capturado al pintar la pestana (que quedaria obsoleto).
    private final java.util.Map<String, Boolean> moduleBaselineState = new java.util.LinkedHashMap<>();
    private Button saveModulesButton;
    private Label modulesDirtyHint;

    // Slugs del catalogo que la UI sabe pintar. Si llega un slug activo
    // que no esta aqui, se ignora en el sidebar (no hay vista para el).
    private static final java.util.Set<String> KNOWN_VIEWS = java.util.Set.of(
            "customers", "billing", "issuers", "purchases", "labor",
            "tax", "reports", "calendar", "settings"
    );

    @Override
    public void start(Stage stage) {
        root = new BorderPane();
        root.getStyleClass().add("app-root");

        Scene scene = new Scene(root, 1180, 760);
        scene.getStylesheets().add(getClass().getResource("/com/benjagest/ui/app.css").toExternalForm());

        stage.setTitle("BENJAGEST");
        stage.getIcons().add(AppBrand.loadWindowIcon());
        stage.setMinWidth(920);
        stage.setMinHeight(640);
        stage.setScene(scene);
        showLogin();
        stage.show();
    }

    private void showLogin() {
        VBox panel = new VBox(14);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(42));
        panel.setMaxWidth(420);
        panel.getStyleClass().add("summary-card");

        Label title = new Label("BENJAGEST");
        title.getStyleClass().add("hero-title");
        Label subtitle = new Label("Inicia sesion con tu email y contrasena");
        subtitle.getStyleClass().add("hero-body");

        TextField emailField = new TextField();
        emailField.setPromptText("email");
        emailField.setMaxWidth(Double.MAX_VALUE);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("contrasena");
        passwordField.setMaxWidth(Double.MAX_VALUE);

        Button loginButton = new Button(t("login"));
        loginButton.setGraphic(icon("fas-sign-in-alt"));
        loginButton.setMaxWidth(Double.MAX_VALUE);
        loginButton.setOnAction(event -> login(emailField.getText(), passwordField.getText()));
        passwordField.setOnAction(event -> login(emailField.getText(), passwordField.getText()));

        Button googleButton = new Button("Iniciar sesion con Google");
        googleButton.setGraphic(icon("fab-google"));
        googleButton.setMaxWidth(Double.MAX_VALUE);
        googleButton.setDisable(true);
        googleButton.setTooltip(new javafx.scene.control.Tooltip("Pendiente de configurar (Slice C2)"));

        Label hint = new Label("Demos: admin@benjagest.local | empresario@benjagest.local");
        hint.getStyleClass().add("status-detail");
        Label hint2 = new Label("Contrasena: Benjamin123456$");
        hint2.getStyleClass().add("status-detail");

        panel.getChildren().addAll(
                AppBrand.createLogoMark(), title, subtitle,
                emailField, passwordField, loginButton,
                new Separator(),
                googleButton,
                hint, hint2
        );

        BorderPane wrapper = new BorderPane(panel);
        wrapper.setPadding(new Insets(70));
        BorderPane.setAlignment(panel, Pos.CENTER);
        root.setTop(null);
        root.setLeft(null);
        root.setCenter(wrapper);
        root.setBottom(null);
    }

    private void login(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            showError("Faltan datos", "Introduce email y contrasena para continuar.");
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                authApiClient.login(email.trim(), password);
                try {
                    activeIssuer = issuerApiClient.getDefault();
                } catch (Exception ignored) {
                    activeIssuer = null;
                }
                return null;
            }
        };
        task.setOnSucceeded(event -> handleLoginSuccess());
        task.setOnFailed(event -> showError(t("loginFailed"), t("loginFailedDetail")));
        start(task, "auth-login");
    }

    private void handleLoginSuccess() {
        AuthSession auth = AuthSession.get();
        if (auth.memberships().size() > 1) {
            showCompanyChooser(auth.memberships());
            return;
        }
        enterApp();
    }

    private void enterApp() {
        AuthSession auth = AuthSession.get();
        session = new SessionInfo(
                auth.userId(),
                auth.userDisplayName(),
                auth.activeCompanyId(),
                auth.activeCompanyLegalName() == null || auth.activeCompanyLegalName().isBlank()
                        ? "BENJAGEST"
                        : auth.activeCompanyLegalName(),
                auth.roleInActiveCompany(),
                auth.accessToken(),
                deriveDefaultMode(auth.activeCompanyType())
        );
        appMode = AppMode.from(session.defaultMode());
        // Antes de pintar el shell, intentamos cargar los modulos activos
        // de esta empresa. Si falla (sin red, sin permiso, etc.), el
        // sidebar usa la lista hardcodeada como fallback.
        refreshActiveModulesAndRender();
    }

    private void refreshActiveModulesAndRender() {
        Task<List<CompanyModuleEntry>> task = new Task<>() {
            @Override
            protected List<CompanyModuleEntry> call() throws Exception {
                return settingsApiClient.listActiveCatalog();
            }
        };
        task.setOnSucceeded(event -> {
            activeModulesCache = mapToModuleLinks(task.getValue());
            showShell();
            showDashboard();
        });
        task.setOnFailed(event -> {
            // Fallback silencioso: cache vacio + sidebar hardcodeado.
            activeModulesCache = List.of();
            showShell();
            showDashboard();
        });
        start(task, "modules-active-load");
    }

    /**
     * Filtra los modulos activos por la whitelist KNOWN_VIEWS, los
     * ordena por displayOrder y los convierte a ModuleLink (slug + label
     * + icon) que es lo que consume el sidebar.
     */
    private List<ModuleLink> mapToModuleLinks(List<CompanyModuleEntry> active) {
        return active.stream()
                .filter(m -> KNOWN_VIEWS.contains(m.slug()))
                .sorted(Comparator.comparingInt(CompanyModuleEntry::displayOrder))
                .map(m -> new ModuleLink(
                        m.slug(),
                        m.label(),
                        m.icon() == null || m.icon().isBlank() ? "fas-cube" : m.icon()
                ))
                .toList();
    }

    private String deriveDefaultMode(String companyType) {
        if ("INTERNAL".equalsIgnoreCase(companyType) || "ADVISORY".equalsIgnoreCase(companyType)) {
            return "ADVISORY";
        }
        return "BUSINESS";
    }

    private void showCompanyChooser(List<Membership> memberships) {
        Dialog<Membership> dialog = new Dialog<>();
        dialog.setTitle("BENJAGEST");
        dialog.setHeaderText("Tienes varias empresas - elige cual abrir");

        VBox list = new VBox(10);
        list.setPadding(new Insets(8));
        for (Membership m : memberships) {
            Button card = new Button();
            card.setMaxWidth(Double.MAX_VALUE);
            VBox cardContent = new VBox(2,
                    label(m.companyLegalName(), "form-title"),
                    label(m.roleName() + " | " + m.companyType(), "status-detail")
            );
            card.setGraphic(cardContent);
            card.setOnAction(event -> dialog.setResult(m));
            list.getChildren().add(card);
        }
        dialog.getDialogPane().setContent(list);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        Optional<Membership> chosen = dialog.showAndWait();
        chosen.ifPresentOrElse(m -> switchToCompany(m.companyId()),
                this::enterApp);
    }

    private void switchToCompany(String companyId) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                authApiClient.switchCompany(companyId);
                try {
                    activeIssuer = issuerApiClient.getDefault();
                } catch (Exception ignored) {
                    activeIssuer = null;
                }
                return null;
            }
        };
        task.setOnSucceeded(event -> enterApp());
        task.setOnFailed(event -> showError(t("loginFailed"), "No se pudo cambiar de empresa"));
        start(task, "auth-switch-company");
    }

    private void showShell() {
        root.setTop(header());
        root.setLeft(sidebar());
        root.setBottom(footer());
    }

    private HBox header() {
        // El titulo refleja el modo derivado de company_type, no es
        // elegible por el usuario. ADVISORY -> "Asesoria", BUSINESS ->
        // "Empresario". Se decidio en el registro de la empresa.
        Label title = new Label(t(appMode.labelKey()));
        title.getStyleClass().add("app-title");
        Label subtitle = new Label(session.companyName());
        subtitle.getStyleClass().add("app-subtitle");
        VBox titleBlock = new VBox(2, title, subtitle);
        // Linea persistente con el emisor activo. Asi, trabajes donde
        // trabajes (factura, gasto, etc.) siempre ves con que empresa
        // estas operando.
        if (activeIssuer != null) {
            Label activeIssuerLine = new Label("Facturando como: " + activeIssuer.legalName());
            activeIssuerLine.setGraphic(icon("fas-file-signature"));
            activeIssuerLine.getStyleClass().add("status-detail");
            titleBlock.getChildren().add(activeIssuerLine);
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button(t("refresh"));
        refresh.setGraphic(icon("fas-sync-alt"));
        refresh.setOnAction(event -> showDashboard());

        Button languageButton = new Button(language == Language.ES ? "EN" : "ES");
        languageButton.setGraphic(icon("fas-globe-europe"));
        languageButton.setOnAction(event -> toggleLanguage());

        Button logout = new Button(t("logout"));
        logout.setGraphic(icon("fas-sign-out-alt"));
        logout.setOnAction(event -> {
            session = null;
            activeModulesCache = List.of();
            AuthSession.get().clear();
            showLogin();
        });

        HBox header = new HBox(14, AppBrand.createLogoMark(), titleBlock, spacer, languageButton, refresh, logout);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("app-header");
        return header;
    }

    private VBox sidebar() {
        VBox sidebar = new VBox(8);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(230);
        navigationButtons.clear();

        Label section = new Label(t("modules"));
        section.getStyleClass().add("sidebar-section");
        sidebar.getChildren().add(section);

        Button home = navButton("dashboard", t("home"), "fas-home");
        home.setOnAction(event -> showDashboard());
        sidebar.getChildren().add(home);

        for (ModuleLink link : activeModules()) {
            Button button = navButton(link.id(), moduleTitle(link.id()), link.icon());
            button.setOnAction(event -> showModule(link.id()));
            sidebar.getChildren().add(button);
        }

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        VBox account = new VBox(4,
                label(t("session"), "account-caption"),
                label(session.employeeName(), "account-title"),
                label(session.role(), "account-caption")
        );
        account.getStyleClass().add("sidebar-account");
        sidebar.getChildren().addAll(spacer, account);
        return sidebar;
    }

    private List<ModuleLink> activeModules() {
        // Si el backend nos dio una lista valida la usamos. Si no, caemos
        // al fallback hardcodeado por modo (mantiene la app utilizable
        // sin conexion al endpoint /modules-catalog/active).
        if (activeModulesCache != null && !activeModulesCache.isEmpty()) {
            return activeModulesCache;
        }
        return appMode == AppMode.ADVISORY ? ADVISORY_MODULES : BUSINESS_MODULES;
    }

    private Button navButton(String id, String text, String iconLiteral) {
        Button button = new Button(text);
        button.setGraphic(icon(iconLiteral));
        button.getStyleClass().add("nav-item");
        button.setMaxWidth(Double.MAX_VALUE);
        navigationButtons.put(id, button);
        return button;
    }

    private void select(String id) {
        navigationButtons.values().forEach(button -> button.getStyleClass().remove("nav-item-selected"));
        Button button = navigationButtons.get(id);
        if (button != null) {
            button.getStyleClass().add("nav-item-selected");
        }
    }

    private void showDashboard() {
        currentModule = "dashboard";
        select("dashboard");
        Task<DashboardData> task = new Task<>() {
            @Override
            protected DashboardData call() throws Exception {
                return apiClient.dashboard(appMode.apiValue());
            }
        };
        task.setOnSucceeded(event -> setCenterAnimated(scroll(dashboard(task.getValue()))));
        task.setOnFailed(event -> setCenterAnimated(scroll(errorPanel(t("dashboardLoadFailed")))));
        start(task, "dashboard-load");
    }

    private VBox dashboard(DashboardData data) {
        VBox content = content();

        Label eyebrow = new Label(t(appMode.eyebrowKey()));
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label(session.companyName());
        title.getStyleClass().add("dashboard-title");
        Label body = new Label(t(appMode.descriptionKey()) + " " + t("operationalSummary") + " " + LocalDate.now().format(DISPLAY_DATE));
        body.getStyleClass().add("hero-body");
        Label user = new Label(t("sessionOf") + " " + session.employeeName() + " - " + t(appMode.labelKey()) + " - " + session.role());
        user.getStyleClass().add("dashboard-context");

        TilePane metrics = new TilePane();
        metrics.setHgap(12);
        metrics.setVgap(12);
        metrics.setPrefTileWidth(218);
        metrics.setPrefTileHeight(132);
        metrics.getChildren().addAll(
                metric(t("billed"), money(data.billed()), t("totalIssued"), "fas-euro-sign", "metric-green"),
                metric(t("pendingCollection"), money(data.pending()), t("uncollectedAmount"), "fas-hourglass-half", "metric-amber"),
                metric(t("registeredExpenses"), money(data.expenses()), t("purchasesSuppliers"), "fas-receipt", "metric-blue"),
                metric(t("payrollCost"), money(data.payrollNet()), t("monthlyNet"), "fas-money-check", "metric-rose"),
                metric(t("customers"), data.customers(), t("activeCompanies"), "fas-users", "module-teal"),
                metric(t("invoices"), data.invoices(), t("issuedDocuments"), "fas-file-invoice-dollar", "module-blue"),
                metric(t("employees"), data.employees(), t("operationalTeam"), "fas-hard-hat", "module-violet"),
                metric(t("openAlerts"), data.openAlerts(), t("pendingReview"), "fas-bell", "module-red")
        );

        TilePane launchers = new TilePane();
        launchers.getStyleClass().add("quick-actions");
        launchers.setHgap(12);
        launchers.setVgap(12);
        launchers.setPrefTileWidth(104);
        launchers.setPrefTileHeight(96);
        for (ModuleLink link : activeModules()) {
            launchers.getChildren().add(actionTile(moduleTitle(link.id()), link.icon(), () -> showModule(link.id())));
        }

        VBox heroCopy = new VBox(8, eyebrow, title, body, user);
        heroCopy.getStyleClass().add("hero-copy");
        HBox hero = new HBox(24, heroCopy);
        hero.getStyleClass().add("hero-panel");
        HBox.setHgrow(heroCopy, Priority.ALWAYS);

        TilePane activity = new TilePane();
        activity.getStyleClass().add("activity-row");
        activity.setHgap(14);
        activity.setVgap(14);
        activity.setPrefTileWidth(300);
        activity.getChildren().addAll(
                activityPanel(t("latestInvoices"), "fas-file-invoice-dollar", data.latestInvoices()),
                activityPanel(t("alerts"), "fas-bell", data.alerts()),
                activityPanel(t("agenda"), "fas-calendar-alt", data.calendar())
        );

        content.getChildren().addAll(
                hero,
                label(t("mainIndicators"), "section-title"),
                metrics,
                sectionHeader(t("quickAccess"), t("quickAccessDetail")),
                launchers,
                sectionHeader(t("recentActivity"), t("recentActivityDetail")),
                activity
        );
        return content;
    }

    private void showModule(String module) {
        currentModule = module;
        select(module);
        if ("issuers".equals(module)) {
            // Issuers no pasa por el endpoint genrico /api/modules.
            // Tiene su propia API REST en /api/issuers, llamada via IssuerApiClient.
            showIssuers();
            return;
        }
        if ("settings".equals(module)) {
            // Configuracion tampoco pasa por /api/modules: tiene 3 pestanas
            // (Empresa / Email / Modulos) sobre /api/settings/*.
            showSettings();
            return;
        }
        Task<ModuleData> task = new Task<>() {
            @Override
            protected ModuleData call() throws Exception {
                return apiClient.module(module, appMode.apiValue());
            }
        };
        task.setOnSucceeded(event -> setCenterAnimated(scroll(moduleView(task.getValue()))));
        task.setOnFailed(event -> setCenterAnimated(scroll(errorPanel(t("moduleLoadFailed") + " " + moduleTitle(module)))));
        start(task, "module-load-" + module);
    }

    private void showIssuers() {
        Task<List<IssuerSummary>> task = new Task<>() {
            @Override
            protected List<IssuerSummary> call() throws Exception {
                return issuerApiClient.list();
            }
        };
        task.setOnSucceeded(event -> setCenterAnimated(scroll(issuersView(task.getValue()))));
        task.setOnFailed(event -> setCenterAnimated(scroll(errorPanel(t("moduleLoadFailed") + " " + moduleTitle("issuers")))));
        start(task, "issuers-load");
    }

    private VBox issuersView(List<IssuerSummary> issuers) {
        VBox content = content();

        Label title = new Label(moduleTitle("issuers"));
        title.getStyleClass().add("module-detail-title");
        Label count = new Label(issuers.size() + " " + t("records"));
        count.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, count);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        StackPane moduleIcon = iconBubble("fas-file-signature", "module-title-icon");

        TableView<IssuerSummary> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setItems(FXCollections.observableArrayList(issuers));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        if (issuers.isEmpty()) {
            table.setPlaceholder(new Label(t("noRecords")));
        }

        TableColumn<IssuerSummary, String> colDefault = new TableColumn<>("");
        colDefault.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().isDefault() ? "★" : ""));
        colDefault.setPrefWidth(40);
        TableColumn<IssuerSummary, String> colName = new TableColumn<>("Razon social");
        colName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().legalName()));
        TableColumn<IssuerSummary, String> colTax = new TableColumn<>("NIF/CIF");
        colTax.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().taxIdentifier()));
        TableColumn<IssuerSummary, String> colCity = new TableColumn<>("Ciudad");
        colCity.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().city()));
        TableColumn<IssuerSummary, String> colEmail = new TableColumn<>(t("field.email"));
        colEmail.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().email()));
        TableColumn<IssuerSummary, String> colPhone = new TableColumn<>(t("field.phone"));
        colPhone.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().phone()));
        table.getColumns().addAll(java.util.List.of(colDefault, colName, colTax, colCity, colEmail, colPhone));

        // Doble click se engancha a cada FILA, no a la tabla.
        // Asi, click en zona vacia o en cabecera no dispara nada.
        table.setRowFactory(tv -> {
            javafx.scene.control.TableRow<IssuerSummary> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    showIssuerFormDialog(row.getItem());
                }
            });
            return row;
        });

        VBox.setVgrow(table, Priority.ALWAYS);

        Button create = new Button(t("new"));
        create.setGraphic(icon("fas-plus"));
        create.setOnAction(event -> showIssuerFormDialog(null));

        Button edit = new Button(t("edit"));
        edit.setGraphic(icon("fas-user-edit"));
        edit.setOnAction(event -> {
            IssuerSummary selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError(t("selectRecord"), t("selectRecordDetail"));
                return;
            }
            showIssuerFormDialog(selected);
        });

        Button delete = new Button("Eliminar");
        delete.setGraphic(icon("fas-trash-alt"));
        delete.setOnAction(event -> {
            IssuerSummary selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError(t("selectRecord"), t("selectRecordDetail"));
                return;
            }
            confirmAndDeleteIssuer(selected);
        });

        Button markActive = new Button("Marcar como activo");
        markActive.setGraphic(icon("fas-star"));
        markActive.setOnAction(event -> {
            IssuerSummary selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError(t("selectRecord"), t("selectRecordDetail"));
                return;
            }
            if (selected.isDefault()) {
                Alert info = new Alert(Alert.AlertType.INFORMATION,
                        "Este emisor ya es el activo.", ButtonType.OK);
                info.setHeaderText(null);
                info.showAndWait();
                return;
            }
            markIssuerAsDefault(selected);
        });

        HBox header = new HBox(16, titleBox, moduleIcon, spacer, delete, markActive, edit, create);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        content.getChildren().addAll(header, table);
        return content;
    }

    private void showIssuerFormDialog(IssuerSummary existing) {
        boolean editing = existing != null;
        Dialog<IssuerCreateRequest> dialog = new Dialog<>();
        dialog.setTitle("BENJAGEST");
        dialog.setHeaderText(null);

        Map<String, TextField> fields = new LinkedHashMap<>();
        addIssuerField(fields, "legalName", "Razon social");
        addIssuerField(fields, "taxIdentifier", "NIF/CIF");
        addIssuerField(fields, "addressLine", "Direccion");
        addIssuerField(fields, "city", "Ciudad");
        addIssuerField(fields, "province", "Provincia");
        addIssuerField(fields, "postalCode", "CP");
        addIssuerField(fields, "country", "Pais");
        addIssuerField(fields, "email", "Email");
        addIssuerField(fields, "phone", "Telefono");
        addIssuerField(fields, "iban", "IBAN");
        addIssuerField(fields, "registryInformation", "Datos registrales");
        addIssuerField(fields, "legalTerms", "Condiciones legales");
        addIssuerField(fields, "invoiceFooter", "Pie de factura");

        if (editing) {
            set(fields, "legalName", existing.legalName());
            set(fields, "taxIdentifier", existing.taxIdentifier());
            set(fields, "addressLine", existing.addressLine());
            set(fields, "city", existing.city());
            set(fields, "province", existing.province());
            set(fields, "postalCode", existing.postalCode());
            set(fields, "country", existing.country());
            set(fields, "email", existing.email());
            set(fields, "phone", existing.phone());
            set(fields, "iban", existing.iban());
            set(fields, "registryInformation", existing.registryInformation());
            set(fields, "legalTerms", existing.legalTerms());
            set(fields, "invoiceFooter", existing.invoiceFooter());
        }

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        grid.setHgap(12);
        grid.setVgap(12);
        int row = 0;
        for (Map.Entry<String, TextField> entry : fields.entrySet()) {
            Label fieldLabel = new Label(issuerFieldLabel(entry.getKey()));
            fieldLabel.getStyleClass().add("form-label");
            entry.getValue().getStyleClass().add("form-input");
            grid.addRow(row++, fieldLabel, entry.getValue());
        }

        Label formTitle = label(editing ? t("editRecord") : t("newRecord"), "form-title");
        Label subtitle = label(moduleTitle("issuers") + (editing ? " - " + existing.id().substring(0, 8) : ""), "form-subtitle");
        HBox formHeader = new HBox(12, iconBubble("fas-file-signature", "module-title-icon"), new VBox(3, formTitle, subtitle));
        formHeader.setAlignment(Pos.CENTER_LEFT);
        VBox shell = new VBox(18, formHeader, grid);
        shell.getStyleClass().add("form-shell");
        dialog.getDialogPane().setContent(shell);
        dialog.getDialogPane().getButtonTypes().addAll(
                new ButtonType(editing ? t("update") : t("save"), ButtonBar.ButtonData.OK_DONE),
                ButtonType.CANCEL
        );
        dialog.setResultConverter(button -> button.getButtonData() == ButtonBar.ButtonData.OK_DONE
                ? new IssuerCreateRequest(
                        fields.get("legalName").getText(),
                        fields.get("taxIdentifier").getText(),
                        fields.get("addressLine").getText(),
                        fields.get("city").getText(),
                        fields.get("province").getText(),
                        fields.get("postalCode").getText(),
                        fields.get("country").getText(),
                        fields.get("email").getText(),
                        fields.get("phone").getText(),
                        fields.get("iban").getText(),
                        fields.get("registryInformation").getText(),
                        fields.get("legalTerms").getText(),
                        fields.get("invoiceFooter").getText()
                )
                : null);

        Optional<IssuerCreateRequest> result = dialog.showAndWait();
        result.ifPresent(request -> {
            if (editing) {
                updateIssuer(existing.id(), request);
            } else {
                createIssuer(request);
            }
        });
    }

    private void addIssuerField(Map<String, TextField> fields, String key, String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        fields.put(key, field);
    }

    private String issuerFieldLabel(String key) {
        return switch (key) {
            case "legalName" -> t("field.name");
            case "taxIdentifier" -> t("field.taxId");
            case "addressLine" -> "Direccion";
            case "city" -> "Ciudad";
            case "province" -> "Provincia";
            case "postalCode" -> "CP";
            case "country" -> "Pais";
            case "email" -> t("field.email");
            case "phone" -> t("field.phone");
            case "iban" -> "IBAN";
            case "registryInformation" -> "Datos registrales";
            case "legalTerms" -> "Condiciones legales";
            case "invoiceFooter" -> "Pie de factura";
            default -> key;
        };
    }

    private void createIssuer(IssuerCreateRequest request) {
        Task<IssuerSummary> task = new Task<>() {
            @Override
            protected IssuerSummary call() throws Exception {
                return issuerApiClient.create(request);
            }
        };
        task.setOnSucceeded(event -> showIssuers());
        task.setOnFailed(event -> showError(t("saveFailed"), t("backendCheck")));
        start(task, "issuer-create");
    }

    private void updateIssuer(String id, IssuerCreateRequest request) {
        Task<IssuerSummary> task = new Task<>() {
            @Override
            protected IssuerSummary call() throws Exception {
                return issuerApiClient.update(id, request);
            }
        };
        task.setOnSucceeded(event -> showIssuers());
        task.setOnFailed(event -> showError(t("updateFailed"), t("backendCheck")));
        start(task, "issuer-update-" + id);
    }

    private void markIssuerAsDefault(IssuerSummary issuer) {
        Task<IssuerSummary> task = new Task<>() {
            @Override
            protected IssuerSummary call() throws Exception {
                IssuerSummary updated = issuerApiClient.markAsDefault(issuer.id());
                activeIssuer = issuerApiClient.getDefault();
                return updated;
            }
        };
        task.setOnSucceeded(event -> {
            // showShell repinta header (con la nueva linea "Facturando como...")
            // y sidebar. showIssuers refresca la tabla con la estrella en
            // su nueva posicion.
            showShell();
            showIssuers();
        });
        task.setOnFailed(event -> showError(t("updateFailed"), t("backendCheck")));
        start(task, "issuer-mark-default-" + issuer.id());
    }

    private void confirmAndDeleteIssuer(IssuerSummary issuer) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Vas a eliminar el emisor \"" + issuer.legalName() + "\". Quedara desactivado, no se borra fisicamente.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Eliminar emisor");
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                Task<Void> task = new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        issuerApiClient.delete(issuer.id());
                        return null;
                    }
                };
                task.setOnSucceeded(event -> showIssuers());
                task.setOnFailed(event -> showError(t("deleteFailed"), t("backendCheck")));
                start(task, "issuer-delete-" + issuer.id());
            }
        });
    }

    private VBox moduleView(ModuleData data) {
        if ("calendar".equals(data.module())) {
            return calendarView(data);
        }

        VBox content = content();

        Label title = new Label(moduleTitle(data.module()));
        title.getStyleClass().add("module-detail-title");
        Label count = new Label(data.records().size() + " " + t("records"));
        count.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, count);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        StackPane moduleIcon = iconBubble(moduleIcon(data.module()), "module-title-icon");

        TableView<ModuleRow> table = table(data);
        VBox.setVgrow(table, Priority.ALWAYS);

        Button create = new Button(t("new"));
        create.setGraphic(icon("fas-plus"));
        create.setOnAction(event -> showFormDialog(data.module(), null));

        Button edit = new Button(t("edit"));
        edit.setGraphic(icon("fas-user-edit"));
        edit.setOnAction(event -> editSelected(data.module(), table.getSelectionModel().getSelectedItem()));

        HBox header = new HBox(16, titleBox, moduleIcon, spacer, edit, create);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        content.getChildren().addAll(header, moduleInsights(data), table);
        return content;
    }

    private TableView<ModuleRow> table(ModuleData data) {
        TableView<ModuleRow> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setItems(FXCollections.observableArrayList(data.records()));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        if (data.records().isEmpty()) {
            table.setPlaceholder(new Label(t("noRecords")));
            return table;
        }
        for (String field : data.records().getFirst().fields().keySet()) {
            TableColumn<ModuleRow, String> column = new TableColumn<>(columnTitle(field));
            column.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().fields().getOrDefault(field, "")));
            table.getColumns().add(column);
        }
        table.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                editSelected(data.module(), table.getSelectionModel().getSelectedItem());
            }
        });
        return table;
    }

    private HBox moduleInsights(ModuleData data) {
        HBox insights = new HBox(14);
        insights.getStyleClass().add("module-insights");
        insights.getChildren().addAll(moduleSummaryPanel(data), barPanel(data), piePanel(data));
        return insights;
    }

    private VBox moduleSummaryPanel(ModuleData data) {
        String mainLabel = switch (data.module()) {
            case "customers" -> "clientes activos";
            case "billing" -> "facturas";
            case "purchases" -> "gastos";
            case "labor" -> "partes de trabajo";
            case "tax" -> "modelos fiscales";
            case "reports" -> "avisos";
            case "settings" -> "usuarios/empleados";
            default -> "registros";
        };

        VBox panel = new VBox(12,
                new HBox(10, iconBubble(moduleIcon(data.module()), "panel-icon"), label("Resumen", "card-title")),
                label(String.valueOf(data.records().size()), "module-big-number"),
                label(mainLabel, "metric-detail"),
                new Separator(),
                label(summaryLine(data), "module-summary-line")
        );
        panel.getStyleClass().add("insight-panel");
        HBox.setHgrow(panel, Priority.ALWAYS);
        return panel;
    }

    private VBox barPanel(ModuleData data) {
        BarChart<String, Number> chart = barChart(data);
        VBox panel = new VBox(10, label(barTitle(data.module()), "card-title"), chart);
        panel.getStyleClass().add("insight-panel");
        HBox.setHgrow(panel, Priority.ALWAYS);
        return panel;
    }

    private VBox piePanel(ModuleData data) {
        PieChart chart = pieChart(data);
        VBox panel = new VBox(10, label(pieTitle(data.module()), "card-title"), chart);
        panel.getStyleClass().add("insight-panel");
        HBox.setHgrow(panel, Priority.ALWAYS);
        return panel;
    }

    private BarChart<String, Number> barChart(ModuleData data) {
        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
        chart.setLegendVisible(false);
        chart.setAnimated(true);
        chart.setHorizontalGridLinesVisible(false);
        chart.setVerticalGridLinesVisible(false);
        chart.getStyleClass().add("mini-chart");

        XYChart.Series<String, Number> series = new XYChart.Series<>();
        groupedCounts(data, barField(data.module())).entrySet().stream().limit(6)
                .forEach(entry -> series.getData().add(new XYChart.Data<>(entry.getKey(), entry.getValue())));
        chart.getData().add(series);
        return chart;
    }

    private PieChart pieChart(ModuleData data) {
        PieChart chart = new PieChart();
        chart.setLegendVisible(false);
        chart.setLabelsVisible(false);
        chart.setClockwise(true);
        chart.setAnimated(true);
        chart.getStyleClass().add("mini-pie");
        groupedCounts(data, pieField(data.module())).entrySet().stream().limit(6)
                .forEach(entry -> chart.getData().add(new PieChart.Data(entry.getKey(), entry.getValue())));
        return chart;
    }

    private Map<String, Integer> groupedCounts(ModuleData data, String field) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ModuleRow row : data.records()) {
            String value = row.fields().getOrDefault(field, "").trim();
            if (value.isBlank()) {
                value = "Sin dato";
            }
            counts.merge(value, 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            counts.put("Sin datos", 0);
        }
        return counts;
    }

    private String barField(String module) {
        return switch (module) {
            case "billing", "purchases", "labor", "reports" -> "fecha";
            case "tax" -> "periodo";
            case "settings" -> "tipo";
            default -> "nif";
        };
    }

    private String pieField(String module) {
        return switch (module) {
            case "billing" -> "cobro";
            case "purchases" -> "pago";
            case "labor", "tax", "reports" -> "estado";
            case "settings" -> "acceso";
            default -> "contacto";
        };
    }

    private String barTitle(String module) {
        return switch (module) {
            case "billing", "purchases", "labor", "reports" -> "Actividad por fecha";
            case "tax" -> "Modelos por periodo";
            case "settings" -> "Equipo por tipo";
            default -> "Distribucion principal";
        };
    }

    private String pieTitle(String module) {
        return switch (module) {
            case "billing" -> "Estado de cobro";
            case "purchases" -> "Estado de pago";
            case "labor", "tax", "reports" -> "Estado";
            case "settings" -> "Acceso PIN";
            default -> "Contactos";
        };
    }

    private String summaryLine(ModuleData data) {
        if (data.records().isEmpty()) {
            return "Sin datos cargados para este modulo.";
        }
        ModuleRow first = data.records().getFirst();
        return first.fields().entrySet().stream()
                .limit(2)
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .reduce((left, right) -> left + " · " + right)
                .orElse("Datos listos para revisar.");
    }

    private VBox calendarView(ModuleData data) {
        VBox content = content();
        LocalDate today = LocalDate.now();

        Label title = new Label(data.title());
        title.getStyleClass().add("module-detail-title");
        Label count = new Label(data.records().size() + " eventos");
        count.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, count);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button create = new Button("Nuevo evento");
        create.setGraphic(icon("fas-calendar-plus"));
        create.setOnAction(event -> showFormDialog(data.module(), null));

        HBox header = new HBox(16, titleBox, iconBubble(moduleIcon(data.module()), "module-title-icon"), spacer, create);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        StackPane viewHost = new StackPane();
        viewHost.getStyleClass().add("calendar-view-host");

        List<Button> modeButtons = new ArrayList<>();
        Button dayButton = viewMode("Día", false);
        Button weekButton = viewMode("Semana", false);
        Button monthButton = viewMode("Mes", true);
        Button yearButton = viewMode("Año", false);
        modeButtons.addAll(List.of(dayButton, weekButton, monthButton, yearButton));

        dayButton.setOnAction(event -> showCalendarMode("Día", data, today, modeButtons, viewHost));
        weekButton.setOnAction(event -> showCalendarMode("Semana", data, today, modeButtons, viewHost));
        monthButton.setOnAction(event -> showCalendarMode("Mes", data, today, modeButtons, viewHost));
        yearButton.setOnAction(event -> showCalendarMode("Año", data, today, modeButtons, viewHost));

        HBox modes = new HBox(8, dayButton, weekButton, monthButton, yearButton);
        modes.getStyleClass().add("calendar-modes");

        showCalendarMode("Mes", data, today, modeButtons, viewHost);

        content.getChildren().addAll(header, modes, viewHost);
        return content;
    }

    private void showCalendarMode(String mode, ModuleData data, LocalDate today, List<Button> buttons, StackPane viewHost) {
        buttons.forEach(button -> button.getStyleClass().remove("calendar-mode-selected"));
        buttons.stream()
                .filter(button -> button.getText().equals(mode))
                .findFirst()
                .ifPresent(button -> button.getStyleClass().add("calendar-mode-selected"));

        Node view = switch (mode) {
            case "Día" -> dayCalendarView(data, today);
            case "Semana" -> weekCalendarView(data, today);
            case "Año" -> yearCalendarView(data, today);
            default -> monthCalendarView(data, today);
        };
        viewHost.getChildren().setAll(view);
    }

    private Button viewMode(String text, boolean selected) {
        Button button = new Button(text);
        button.getStyleClass().add("calendar-mode");
        if (selected) {
            button.getStyleClass().add("calendar-mode-selected");
        }
        return button;
    }

    private HBox monthCalendarView(ModuleData data, LocalDate today) {
        HBox calendarBody = new HBox(14, monthCalendar(data, today), dayAgenda(data, today));
        HBox.setHgrow(calendarBody.getChildren().getFirst(), Priority.ALWAYS);
        return calendarBody;
    }

    private HBox dayCalendarView(ModuleData data, LocalDate today) {
        HBox body = new HBox(14, dayFocusPanel(data, today), dayAgenda(data, today));
        HBox.setHgrow(body.getChildren().getFirst(), Priority.ALWAYS);
        return body;
    }

    private VBox dayFocusPanel(ModuleData data, LocalDate date) {
        List<ModuleRow> events = eventsForDate(data, date);
        VBox panel = new VBox(14);
        panel.getStyleClass().add("calendar-panel");
        HBox.setHgrow(panel, Priority.ALWAYS);

        Label eyebrow = label(date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es-ES")), "eyebrow");
        Label title = label(date.format(DISPLAY_DATE), "calendar-month");
        Label count = label(events.size() + " evento" + (events.size() == 1 ? "" : "s") + " programado" + (events.size() == 1 ? "" : "s"), "section-subtitle");
        panel.getChildren().addAll(eyebrow, title, count);

        if (events.isEmpty()) {
            panel.getChildren().add(label("No hay eventos para este dia. Puedes crear uno desde Nuevo evento.", "status-detail"));
            return panel;
        }

        for (ModuleRow event : events) {
            panel.getChildren().add(calendarEventLine(event));
        }
        return panel;
    }

    private VBox weekCalendarView(ModuleData data, LocalDate today) {
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        VBox panel = new VBox(14);
        panel.getStyleClass().add("calendar-panel");

        Label title = label("Semana del " + monday.format(DISPLAY_DATE) + " al " + monday.plusDays(6).format(DISPLAY_DATE), "calendar-month");
        TilePane week = new TilePane();
        week.getStyleClass().add("week-grid");
        week.setHgap(10);
        week.setVgap(10);
        week.setPrefTileWidth(190);
        week.setPrefTileHeight(190);

        for (int index = 0; index < 7; index++) {
            LocalDate date = monday.plusDays(index);
            week.getChildren().add(weekDayPanel(data, date, date.equals(today)));
        }

        panel.getChildren().addAll(title, week);
        return panel;
    }

    private VBox weekDayPanel(ModuleData data, LocalDate date, boolean today) {
        List<ModuleRow> events = eventsForDate(data, date);
        VBox day = new VBox(8);
        day.getStyleClass().add("week-day");
        if (today) {
            day.getStyleClass().add("week-day-today");
        }
        day.getChildren().addAll(
                label(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.forLanguageTag("es-ES")), "calendar-weekday"),
                label(date.getDayOfMonth() + "/" + date.getMonthValue(), "calendar-day-number")
        );
        if (events.isEmpty()) {
            day.getChildren().add(label("Sin eventos", "status-detail"));
            return day;
        }
        events.stream().limit(3).forEach(event -> day.getChildren().add(calendarEventChip(event)));
        if (events.size() > 3) {
            day.getChildren().add(label("+" + (events.size() - 3) + " mas", "calendar-event-badge"));
        }
        return day;
    }

    private VBox yearCalendarView(ModuleData data, LocalDate today) {
        VBox panel = new VBox(14);
        panel.getStyleClass().add("calendar-panel");

        Label title = label("Vista anual " + today.getYear(), "calendar-month");
        TilePane months = new TilePane();
        months.getStyleClass().add("year-grid");
        months.setHgap(12);
        months.setVgap(12);
        months.setPrefTileWidth(210);
        months.setPrefTileHeight(135);

        for (int month = 1; month <= 12; month++) {
            months.getChildren().add(monthCard(data, today.withMonth(month).withDayOfMonth(1)));
        }
        panel.getChildren().addAll(title, months);
        return panel;
    }

    private VBox monthCard(ModuleData data, LocalDate monthDate) {
        List<ModuleRow> events = eventsForMonth(data, monthDate);
        VBox card = new VBox(8);
        card.getStyleClass().add("year-month-card");
        card.getChildren().addAll(
                label(monthDate.getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es-ES")), "activity-title"),
                label(events.size() + " evento" + (events.size() == 1 ? "" : "s"), "module-big-number-small")
        );
        events.stream().limit(2).forEach(event -> card.getChildren().add(calendarEventChip(event)));
        card.setOnMouseClicked(event -> showMonthDialog(monthDate, events));
        return card;
    }

    private HBox calendarEventChip(ModuleRow event) {
        HBox chip = new HBox(6, iconBubble("fas-calendar-check", "tiny-icon"), label(event.fields().getOrDefault("evento", "Evento"), "calendar-chip-text"));
        chip.getStyleClass().add("calendar-chip");
        chip.setAlignment(Pos.CENTER_LEFT);
        return chip;
    }

    private VBox monthCalendar(ModuleData data, LocalDate baseDate) {
        Map<Integer, List<ModuleRow>> eventsByDay = calendarEventsByDay(data, baseDate);
        VBox panel = new VBox(12);
        panel.getStyleClass().add("calendar-panel");
        HBox.setHgrow(panel, Priority.ALWAYS);

        Label month = label(
                baseDate.getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es-ES")) + " " + baseDate.getYear(),
                "calendar-month"
        );

        GridPane grid = new GridPane();
        grid.getStyleClass().add("calendar-grid");
        grid.setHgap(8);
        grid.setVgap(8);

        String[] weekdays = {"L", "M", "X", "J", "V", "S", "D"};
        for (int column = 0; column < weekdays.length; column++) {
            Label dayLabel = label(weekdays[column], "calendar-weekday");
            grid.add(dayLabel, column, 0);
        }

        LocalDate firstDay = baseDate.withDayOfMonth(1);
        int startColumn = firstDay.getDayOfWeek().getValue() - 1;
        int length = firstDay.lengthOfMonth();
        int row = 1;
        int column = startColumn;
        for (int day = 1; day <= length; day++) {
            LocalDate date = baseDate.withDayOfMonth(day);
            grid.add(calendarDay(date, eventsByDay.getOrDefault(day, List.of()), day == baseDate.getDayOfMonth()), column, row);
            column++;
            if (column == 7) {
                column = 0;
                row++;
            }
        }

        panel.getChildren().addAll(month, grid);
        return panel;
    }

    private VBox calendarDay(LocalDate date, List<ModuleRow> events, boolean today) {
        Label number = label(String.valueOf(date.getDayOfMonth()), "calendar-day-number");
        VBox box = new VBox(5, number);
        box.getStyleClass().add("calendar-day");
        if (today) {
            box.getStyleClass().add("calendar-day-today");
        }
        if (!events.isEmpty()) {
            Label badge = label(events.size() + " evento" + (events.size() == 1 ? "" : "s"), "calendar-event-badge");
            box.getChildren().add(badge);
        }
        box.setOnMouseClicked(event -> showDayDialog(date, events));
        return box;
    }

    private VBox dayAgenda(ModuleData data, LocalDate today) {
        VBox panel = new VBox(12);
        panel.getStyleClass().add("day-agenda");
        panel.setPrefWidth(330);

        Label title = label("Agenda del dia", "card-title");
        Label date = label(today.format(DISPLAY_DATE), "section-subtitle");
        panel.getChildren().addAll(new HBox(10, iconBubble("fas-calendar-check", "panel-icon"), new VBox(2, title, date)));

        List<ModuleRow> events = calendarEventsByDay(data, today).getOrDefault(today.getDayOfMonth(), List.of());
        if (events.isEmpty()) {
            panel.getChildren().add(label("No hay eventos para hoy.", "status-detail"));
            return panel;
        }

        for (ModuleRow event : events) {
            panel.getChildren().add(calendarEventLine(event));
        }
        return panel;
    }

    private VBox calendarEventLine(ModuleRow event) {
        Label title = label(event.fields().getOrDefault("evento", "Evento"), "activity-title");
        Label detail = label(event.fields().getOrDefault("detalle", ""), "activity-subtitle");
        Label type = label(event.fields().getOrDefault("tipo", "GENERAL"), "activity-value");
        VBox line = new VBox(4, title, detail, type);
        line.getStyleClass().add("calendar-event-line");
        return line;
    }

    private void showDayDialog(LocalDate date, List<ModuleRow> events) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Agenda");
        dialog.setHeaderText(null);

        VBox eventList = new VBox(10);
        eventList.getStyleClass().add("calendar-dialog-list");
        if (events.isEmpty()) {
            eventList.getChildren().add(emptyDayPanel(date, dialog));
        } else {
            for (ModuleRow event : events) {
                eventList.getChildren().add(dayEventCard(event, dialog));
            }
        }

        ScrollPane eventScroll = new ScrollPane(eventList);
        eventScroll.getStyleClass().add("calendar-dialog-scroll");
        eventScroll.setFitToWidth(true);
        eventScroll.setPrefViewportHeight(280);

        Button create = new Button("Nuevo evento");
        create.setGraphic(icon("fas-calendar-plus"));
        create.getStyleClass().add("calendar-dialog-primary");
        create.setOnAction(action -> {
            dialog.close();
            showFormDialog("calendar", null, Map.of("date", date.toString()));
        });

        VBox copy = new VBox(4,
                label(date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es-ES")), "eyebrow"),
                label(date.format(DISPLAY_DATE), "calendar-dialog-title"),
                label(events.size() + " evento" + (events.size() == 1 ? "" : "s") + " planificado" + (events.size() == 1 ? "" : "s"), "section-subtitle")
        );
        HBox header = new HBox(14, iconBubble("fas-calendar-day", "calendar-dialog-icon"), copy);
        header.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actionBar = new HBox(12, header, spacer, create);
        actionBar.setAlignment(Pos.CENTER_LEFT);

        VBox shell = new VBox(18, actionBar, eventScroll);
        shell.getStyleClass().add("calendar-dialog-shell");
        dialog.getDialogPane().setContent(shell);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().getStyleClass().add("calendar-dialog-pane");
        dialog.showAndWait();
    }

    private VBox emptyDayPanel(LocalDate date, Dialog<?> dialog) {
        Label title = label("Dia libre", "activity-title");
        Label detail = label("No hay nada programado. Puedes crear una cita, vencimiento o recordatorio para este dia.", "activity-subtitle");
        Button create = new Button("Crear evento");
        create.setGraphic(icon("fas-plus"));
        create.getStyleClass().add("calendar-dialog-secondary");
        create.setOnAction(action -> {
            dialog.close();
            showFormDialog("calendar", null, Map.of("date", date.toString()));
        });

        VBox panel = new VBox(12, iconBubble("fas-calendar-plus", "calendar-empty-icon"), title, detail, create);
        panel.getStyleClass().add("calendar-empty-panel");
        panel.setAlignment(Pos.CENTER);
        return panel;
    }

    private HBox dayEventCard(ModuleRow event, Dialog<?> dialog) {
        Label title = label(event.fields().getOrDefault("evento", "Evento"), "calendar-event-card-title");
        Label detail = label(event.fields().getOrDefault("detalle", "Sin detalle"), "calendar-event-card-detail");
        Label type = label(event.fields().getOrDefault("tipo", "GENERAL"), "calendar-event-card-type");
        VBox copy = new VBox(5, title, detail, type);
        HBox.setHgrow(copy, Priority.ALWAYS);

        Button edit = new Button("Editar");
        edit.setGraphic(icon("fas-pen"));
        edit.getStyleClass().add("calendar-dialog-secondary");
        edit.setOnAction(action -> {
            dialog.close();
            showFormDialog("calendar", event);
        });

        Button delete = new Button("Eliminar");
        delete.setGraphic(icon("fas-trash-alt"));
        delete.getStyleClass().add("calendar-dialog-danger");
        delete.setOnAction(action -> {
            dialog.close();
            deleteCalendarEvent(event.id());
        });

        HBox actions = new HBox(8, edit, delete);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox card = new HBox(14, iconBubble("fas-calendar-check", "calendar-event-card-icon"), copy, actions);
        card.getStyleClass().add("calendar-event-card");
        card.setAlignment(Pos.CENTER_LEFT);
        return card;
    }

    private void showMonthDialog(LocalDate monthDate, List<ModuleRow> events) {
        StringBuilder message = new StringBuilder();
        if (events.isEmpty()) {
            message.append("No hay eventos en este mes.");
        } else {
            for (ModuleRow event : events) {
                message.append("- ")
                        .append(event.fields().getOrDefault("fecha", ""))
                        .append(" · ")
                        .append(event.fields().getOrDefault("evento", "Evento"))
                        .append("\n");
            }
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message.toString(), ButtonType.OK);
        alert.setTitle("Agenda");
        alert.setHeaderText(monthDate.getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es-ES")) + " " + monthDate.getYear());
        alert.showAndWait();
    }

    private List<ModuleRow> eventsForDate(ModuleData data, LocalDate date) {
        return data.records().stream()
                .filter(row -> row.fields().getOrDefault("fecha", "").equals(date.toString()))
                .sorted(Comparator.comparing(row -> row.fields().getOrDefault("evento", "")))
                .toList();
    }

    private List<ModuleRow> eventsForMonth(ModuleData data, LocalDate monthDate) {
        return data.records().stream()
                .filter(row -> {
                    String value = row.fields().getOrDefault("fecha", "");
                    if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                        return false;
                    }
                    LocalDate eventDate = LocalDate.parse(value);
                    return eventDate.getYear() == monthDate.getYear() && eventDate.getMonth() == monthDate.getMonth();
                })
                .sorted(Comparator.comparing(row -> row.fields().getOrDefault("fecha", "")))
                .toList();
    }

    private Map<Integer, List<ModuleRow>> calendarEventsByDay(ModuleData data, LocalDate baseDate) {
        Map<Integer, List<ModuleRow>> events = new TreeMap<>();
        for (ModuleRow row : data.records()) {
            String value = row.fields().getOrDefault("fecha", "");
            if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                continue;
            }
            LocalDate eventDate = LocalDate.parse(value);
            if (eventDate.getMonth() == baseDate.getMonth() && eventDate.getYear() == baseDate.getYear()) {
                events.computeIfAbsent(eventDate.getDayOfMonth(), ignored -> new ArrayList<>()).add(row);
            }
        }
        events.values().forEach(list -> list.sort(Comparator.comparing(row -> row.fields().getOrDefault("evento", ""))));
        return events;
    }

    private void editSelected(String module, ModuleRow selected) {
        if (selected == null) {
            showError(t("selectRecord"), t("selectRecordDetail"));
            return;
        }
        showFormDialog(module, selected);
    }

    private void showFormDialog(String module, ModuleRow record) {
        showFormDialog(module, record, Map.of());
    }

    private void showFormDialog(String module, ModuleRow record, Map<String, String> defaults) {
        boolean editing = record != null;
        Dialog<Map<String, String>> dialog = new Dialog<>();
        dialog.setTitle("BENJAGEST");
        dialog.setHeaderText(null);

        Map<String, TextField> fields = formFields(module);
        if (!editing) {
            defaults.forEach((key, value) -> set(fields, key, value));
        } else {
            fillForm(module, fields, record);
        }

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        grid.setHgap(12);
        grid.setVgap(12);
        int row = 0;
        for (Map.Entry<String, TextField> entry : fields.entrySet()) {
            Label fieldLabel = new Label(labelFor(entry.getKey()));
            fieldLabel.getStyleClass().add("form-label");
            entry.getValue().getStyleClass().add("form-input");
            grid.addRow(row++, fieldLabel, entry.getValue());
        }

        Label title = label(editing ? t("editRecord") : t("newRecord"), "form-title");
        Label subtitle = label(moduleTitle(module) + (editing ? " · " + record.id().substring(0, 8) : ""), "form-subtitle");
        HBox header = new HBox(12, iconBubble(moduleIcon(module), "module-title-icon"), new VBox(3, title, subtitle));
        header.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(18, header, grid);
        content.getStyleClass().add("form-shell");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(
                new ButtonType(editing ? t("update") : t("save"), ButtonBar.ButtonData.OK_DONE),
                ButtonType.CANCEL
        );
        dialog.setResultConverter(button -> button.getButtonData() == ButtonBar.ButtonData.OK_DONE ? values(fields) : null);

        Optional<Map<String, String>> result = dialog.showAndWait();
        result.ifPresent(values -> {
            if (editing) {
                update(module, record.id(), values);
            } else {
                create(module, values);
            }
        });
    }

    private Map<String, TextField> formFields(String module) {
        Map<String, TextField> fields = new LinkedHashMap<>();
        switch (module) {
            case "customers" -> add(fields, "legalName", "Nombre fiscal", "taxIdentifier", "CIF/NIF", "contactName", "Contacto", "email", "Email", "phone", "Telefono");
            case "billing" -> add(fields, "title", "Concepto", "description", "Notas", "amount", "Total/Base", "vatPercent", "IVA %", "status", "DRAFT/VALIDATED", "category", "PENDING/PAID", "date", "Fecha YYYY-MM-DD");
            case "purchases" -> add(fields, "description", "Descripcion", "category", "Categoria", "status", "PENDING/PAID", "amount", "Total/Base", "vatPercent", "IVA %", "date", "Fecha YYYY-MM-DD");
            case "labor" -> add(fields, "description", "Trabajo", "minutes", "Minutos", "amount", "Importe", "status", "PENDING/PAID", "eventType", "IN/OUT/BREAK_START/BREAK_END", "date", "Fecha YYYY-MM-DD");
            case "tax" -> add(fields, "title", "Modelo 303/111", "status", "Estado", "amount", "Importe", "date", "Fecha YYYY-MM-DD");
            case "settings" -> add(fields, "legalName", "Empleado", "taxIdentifier", "DNI/NIE", "email", "Email", "phone", "Telefono", "category", "FULL_TIME/PART_TIME", "pin", "PIN", "minutes", "Minutos maximos");
            case "calendar" -> add(fields, "title", "Evento", "description", "Detalle", "category", "Tipo", "date", "Fecha YYYY-MM-DD");
            default -> add(fields, "title", "Titulo", "description", "Detalle", "category", "Prioridad");
        }
        return fields;
    }

    private void fillForm(String module, Map<String, TextField> fields, ModuleRow record) {
        Map<String, String> values = record.fields();
        switch (module) {
            case "customers" -> {
                set(fields, "legalName", values.get("nombre"));
                set(fields, "taxIdentifier", values.get("nif"));
                set(fields, "contactName", values.get("contacto"));
                set(fields, "email", values.get("email"));
                set(fields, "phone", values.get("telefono"));
            }
            case "billing" -> {
                set(fields, "title", values.get("factura"));
                set(fields, "amount", values.get("total"));
                set(fields, "status", values.get("estado"));
                set(fields, "category", values.get("cobro"));
                set(fields, "date", values.get("fecha"));
            }
            case "purchases" -> {
                set(fields, "description", values.get("factura"));
                set(fields, "category", values.get("categoria"));
                set(fields, "status", values.get("pago"));
                set(fields, "amount", values.get("total"));
                set(fields, "date", values.get("fecha"));
            }
            case "labor" -> {
                set(fields, "description", values.get("trabajo"));
                set(fields, "minutes", values.get("minutos"));
                set(fields, "status", values.get("estado"));
                set(fields, "date", values.get("fecha"));
            }
            case "tax" -> {
                set(fields, "title", values.get("modelo"));
                set(fields, "status", values.get("estado"));
                set(fields, "amount", values.get("importe"));
            }
            case "settings" -> {
                set(fields, "legalName", values.get("empleado"));
                set(fields, "email", values.get("email"));
                set(fields, "phone", values.get("telefono"));
                set(fields, "category", values.get("tipo"));
            }
            case "calendar" -> {
                set(fields, "title", values.get("evento"));
                set(fields, "description", values.get("detalle"));
                set(fields, "category", values.get("tipo"));
                set(fields, "date", values.get("fecha"));
            }
            default -> {
                set(fields, "title", values.get("aviso"));
                set(fields, "description", values.get("detalle"));
                set(fields, "category", values.get("prioridad"));
                set(fields, "status", values.get("estado"));
            }
        }
    }

    private void set(Map<String, TextField> fields, String key, String value) {
        TextField field = fields.get(key);
        if (field != null && value != null) {
            field.setText(value);
        }
    }

    private void create(String module, Map<String, String> fields) {
        Task<ModuleRow> task = new Task<>() {
            @Override
            protected ModuleRow call() throws Exception {
                return apiClient.create(module, fields);
            }
        };
        task.setOnSucceeded(event -> showModule(module));
        task.setOnFailed(event -> showError(t("saveFailed"), t("backendCheck")));
        start(task, "module-create-" + module);
    }

    private void update(String module, String id, Map<String, String> fields) {
        Task<ModuleRow> task = new Task<>() {
            @Override
            protected ModuleRow call() throws Exception {
                return apiClient.update(module, id, fields);
            }
        };
        task.setOnSucceeded(event -> showModule(module));
        task.setOnFailed(event -> showError(t("updateFailed"), t("backendCheck")));
        start(task, "module-update-" + module);
    }

    private void deleteCalendarEvent(String id) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                apiClient.delete("calendar", id);
                return null;
            }
        };
        task.setOnSucceeded(event -> showModule("calendar"));
        task.setOnFailed(event -> showError(t("deleteFailed"), t("backendCheck")));
        start(task, "calendar-delete-" + id);
    }

    // ===================================================================
    //  Pantalla Configuracion (Slice C3): TabPane con 3 pestanas
    // ===================================================================

    private void showSettings() {
        // Cargamos los tres recursos en paralelo (3 llamadas REST) y
        // construimos el TabPane cuando todas hayan respondido. Si una
        // falla, mostramos error.
        Task<SettingsBundle> task = new Task<>() {
            @Override
            protected SettingsBundle call() throws Exception {
                CompanyData company = settingsApiClient.getCompany();
                EmailConfig email = settingsApiClient.getEmailConfig();
                List<CompanyModuleEntry> modules = settingsApiClient.listModules();
                return new SettingsBundle(company, email, modules);
            }
        };
        task.setOnSucceeded(event -> setCenterAnimated(settingsView(task.getValue())));
        task.setOnFailed(event -> setCenterAnimated(scroll(errorPanel("No se pudo cargar Configuracion (necesitas rol OWNER o ADMIN)"))));
        start(task, "settings-load");
    }

    private VBox settingsView(SettingsBundle bundle) {
        VBox content = content();

        Label title = new Label("Configuracion");
        title.getStyleClass().add("module-detail-title");
        Label subtitle = new Label(session.companyName());
        subtitle.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, subtitle);
        StackPane moduleIcon = iconBubble("fas-cog", "module-title-icon");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(16, titleBox, moduleIcon, spacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("settings-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab companyTab = new Tab("Empresa", settingsCompanyTab(bundle.company()));
        companyTab.setGraphic(icon("fas-building"));
        Tab emailTab = new Tab("Email SMTP", settingsEmailTab(bundle.email()));
        emailTab.setGraphic(icon("fas-envelope"));
        Tab modulesTab = new Tab("Modulos", settingsModulesTab(bundle.modules()));
        modulesTab.setGraphic(icon("fas-cubes"));
        tabs.getTabs().addAll(companyTab, emailTab, modulesTab);
        // El TabPane crece hasta el final del area central; sin esto, los
        // botones del pie de cada tab podrian quedar fuera de pantalla en
        // portatil.
        VBox.setVgrow(tabs, Priority.ALWAYS);

        content.getChildren().addAll(header, tabs);
        return content;
    }

    private record SettingsBundle(CompanyData company, EmailConfig email, List<CompanyModuleEntry> modules) {
    }

    // ----- Pestana Empresa -----

    private Node settingsCompanyTab(CompanyData company) {
        TextField legalName = textInput(company.legalName(), "Razon social");
        TextField tradeName = textInput(company.tradeName(), "Nombre comercial");
        TextField taxId = textInput(company.taxIdentifier(), "NIF/CIF");
        TextField email = textInput(company.email(), "Email de contacto");
        TextField phone = textInput(company.phone(), "Telefono");
        TextField website = textInput(company.website(), "Web");

        GridPane grid = formGrid();
        addFormRow(grid, 0, "Razon social *", legalName);
        addFormRow(grid, 1, "Nombre comercial", tradeName);
        addFormRow(grid, 2, "NIF/CIF", taxId);
        addFormRow(grid, 3, "Email", email);
        addFormRow(grid, 4, "Telefono", phone);
        addFormRow(grid, 5, "Web", website);

        Label typeNote = new Label("Tipo de empresa: " + company.companyType()
                + " (no editable desde aqui)");
        typeNote.getStyleClass().add("status-detail");

        Button save = new Button("Guardar cambios");
        save.setGraphic(icon("fas-save"));
        save.setOnAction(event -> saveCompany(new CompanyData(
                company.id(),
                legalName.getText(),
                tradeName.getText(),
                taxId.getText(),
                company.companyType(),
                email.getText(),
                phone.getText(),
                website.getText()
        )));

        HBox actions = new HBox(save);
        actions.getStyleClass().add("settings-actions");

        Label sectionTitle = label("Datos generales", "settings-section-title");
        typeNote.getStyleClass().add("settings-hint");

        return tabLayout(sectionTitle, new VBox(16, grid, typeNote), actions);
    }

    private void saveCompany(CompanyData data) {
        if (data.legalName() == null || data.legalName().isBlank()) {
            showError("Falta dato", "La razon social es obligatoria");
            return;
        }
        Task<CompanyData> task = new Task<>() {
            @Override
            protected CompanyData call() throws Exception {
                return settingsApiClient.updateCompany(data);
            }
        };
        task.setOnSucceeded(event -> {
            Alert ok = new Alert(Alert.AlertType.INFORMATION,
                    "Datos de la empresa actualizados.", ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
            showSettings();
        });
        task.setOnFailed(event -> showError("No se pudo guardar", "Comprueba los datos y vuelve a intentarlo."));
        start(task, "settings-company-save");
    }

    // ----- Pestana Email SMTP -----

    private Node settingsEmailTab(EmailConfig config) {
        TextField smtpHost = textInput(config.smtpHost(), "smtp.tu-servidor.com");
        TextField smtpPort = textInput(config.smtpPort() == null ? "" : config.smtpPort().toString(), "587");
        TextField smtpUser = textInput(config.smtpUser(), "usuario@dominio");
        PasswordField smtpPassword = new PasswordField();
        smtpPassword.setPromptText(config.passwordConfigured()
                ? "(password guardada - deja vacio para no cambiar)"
                : "password");
        TextField fromAddress = textInput(config.fromAddress(), "facturas@tu-dominio");
        TextField fromName = textInput(config.fromName(), "Nombre que aparece como remitente");
        TextField replyTo = textInput(config.replyTo(), "respuestas@tu-dominio");
        CheckBox tlsEnabled = new CheckBox("TLS / STARTTLS habilitado");
        tlsEnabled.setSelected(config.tlsEnabled());
        CheckBox authRequired = new CheckBox("El servidor SMTP requiere autenticacion");
        authRequired.setSelected(config.authRequired());

        GridPane grid = formGrid();
        addFormRow(grid, 0, "Servidor SMTP", smtpHost);
        addFormRow(grid, 1, "Puerto", smtpPort);
        addFormRow(grid, 2, "Usuario", smtpUser);
        addFormRow(grid, 3, "Password", smtpPassword);
        addFormRow(grid, 4, "From (remitente)", fromAddress);
        addFormRow(grid, 5, "Nombre del remitente", fromName);
        addFormRow(grid, 6, "Reply-To", replyTo);

        VBox flags = new VBox(8, tlsEnabled, authRequired);

        TextField testRecipient = new TextField();
        testRecipient.setPromptText("destinatario@dominio (para email de prueba)");

        Button save = new Button("Guardar");
        save.setGraphic(icon("fas-save"));
        save.setOnAction(event -> saveEmailConfig(
                smtpHost.getText(),
                parseIntOrNull(smtpPort.getText()),
                smtpUser.getText(),
                smtpPassword.getText(),
                fromAddress.getText(),
                fromName.getText(),
                replyTo.getText(),
                tlsEnabled.isSelected(),
                authRequired.isSelected()
        ));

        Button test = new Button("Enviar email de prueba");
        test.setGraphic(icon("fas-paper-plane"));
        test.setOnAction(event -> sendTestEmail(testRecipient.getText()));

        testRecipient.getStyleClass().add("form-input");

        HBox actions = new HBox(test, save);
        actions.getStyleClass().add("settings-actions");

        VBox center = new VBox(16,
                grid,
                flags,
                new Separator(),
                label("Probar configuracion", "settings-section-title"),
                label("Envia un correo de prueba con la configuracion guardada para verificar que las credenciales funcionan.", "settings-hint"),
                testRecipient
        );
        return tabLayout(label("Servidor SMTP", "settings-section-title"), center, actions);
    }

    private Integer parseIntOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void saveEmailConfig(String host, Integer port, String user, String password,
                                 String fromAddress, String fromName, String replyTo,
                                 boolean tls, boolean auth) {
        Task<EmailConfig> task = new Task<>() {
            @Override
            protected EmailConfig call() throws Exception {
                return settingsApiClient.updateEmailConfig(host, port, user, password,
                        fromAddress, fromName, replyTo, tls, auth);
            }
        };
        task.setOnSucceeded(event -> {
            Alert ok = new Alert(Alert.AlertType.INFORMATION,
                    "Configuracion SMTP guardada.", ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
            showSettings();
        });
        task.setOnFailed(event -> showError("No se pudo guardar", "Revisa los datos del servidor SMTP."));
        start(task, "settings-email-save");
    }

    private void sendTestEmail(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            showError("Falta dato", "Indica un email destinatario para la prueba.");
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                settingsApiClient.sendTestEmail(recipient.trim());
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            Alert ok = new Alert(Alert.AlertType.INFORMATION,
                    "Email de prueba enviado a " + recipient + ".", ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
        });
        task.setOnFailed(event -> showError("Envio fallido",
                "Comprueba host/puerto/usuario/password y vuelve a intentarlo."));
        start(task, "settings-email-test");
    }

    // ----- Pestana Modulos -----

    private Node settingsModulesTab(List<CompanyModuleEntry> modules) {
        pendingModuleChanges.clear();
        moduleBaselineState.clear();

        Label sectionTitle = label("Modulos activos por empresa", "settings-section-title");
        Label hint = new Label("Marca o desmarca cada modulo y pulsa Guardar cambios. "
                + "Cada modulo es todo-o-nada: si activas Facturacion entra el bloque completo "
                + "(series, facturas, cobros, recurrentes); si lo desactivas, sale entero.");
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        VBox list = new VBox(8);
        list.getStyleClass().add("module-list");
        // Solo categorias raiz. Los sub-modulos no son configurables a
        // mano: se mueven en bloque con su categoria padre.
        for (CompanyModuleEntry category : modules.stream().filter(m -> m.parentSlug() == null || m.parentSlug().isBlank()).toList()) {
            CheckBox toggle = new CheckBox(category.label());
            toggle.setSelected(category.active());
            toggle.setDisable("settings".equals(category.slug()));
            moduleBaselineState.put(category.slug(), category.active());
            String slug = category.slug();
            toggle.selectedProperty().addListener((obs, was, now) -> {
                Boolean baseline = moduleBaselineState.get(slug);
                boolean baselineValue = baseline != null && baseline;
                if (now == null || now.booleanValue() == baselineValue) {
                    pendingModuleChanges.remove(slug);
                } else {
                    pendingModuleChanges.put(slug, now);
                }
                refreshSaveModulesButton();
            });
            HBox row = new HBox(toggle);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("module-row");
            list.getChildren().add(row);
        }

        modulesDirtyHint = label("", "settings-hint");
        saveModulesButton = new Button("Guardar cambios");
        saveModulesButton.setGraphic(icon("fas-save"));
        saveModulesButton.setOnAction(event -> saveModuleChanges());

        HBox actions = new HBox(modulesDirtyHint, new Region(), saveModulesButton);
        HBox.setHgrow(actions.getChildren().get(1), Priority.ALWAYS);
        actions.getStyleClass().add("settings-actions");
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox header = new VBox(8, sectionTitle, hint);
        Node body = tabLayout(header, list, actions);
        refreshSaveModulesButton();
        return body;
    }

    /**
     * Patron compartido por los 3 tabs de Configuracion: cabecera arriba,
     * cuerpo desplazable en el centro (scroll vertical si no entra), y
     * acciones ancladas al pie siempre visibles aunque el portatil tenga
     * pantalla pequena.
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

    private void refreshSaveModulesButton() {
        if (saveModulesButton == null) {
            return;
        }
        int count = pendingModuleChanges.size();
        saveModulesButton.setDisable(count == 0);
        if (modulesDirtyHint != null) {
            modulesDirtyHint.setText(count == 0
                    ? "Sin cambios sin guardar."
                    : count == 1 ? "1 cambio sin guardar." : count + " cambios sin guardar.");
        }
    }

    private void saveModuleChanges() {
        if (pendingModuleChanges.isEmpty()) {
            return;
        }
        java.util.Map<String, Boolean> batch = new java.util.LinkedHashMap<>(pendingModuleChanges);
        saveModulesButton.setDisable(true);
        modulesDirtyHint.setText("Guardando " + batch.size() + " cambio" + (batch.size() == 1 ? "" : "s") + "...");

        Task<List<CompanyModuleEntry>> task = new Task<>() {
            @Override
            protected List<CompanyModuleEntry> call() throws Exception {
                for (java.util.Map.Entry<String, Boolean> change : batch.entrySet()) {
                    settingsApiClient.setModuleActive(change.getKey(), change.getValue());
                }
                return settingsApiClient.listActiveCatalog();
            }
        };
        task.setOnSucceeded(event -> {
            // Sincroniza el baseline con lo que acabamos de guardar para
            // que los proximos clicks vuelvan a detectar cambios. Sin
            // esto, reactivar un modulo recien desactivado se descartaba
            // al comparar contra el valor original obsoleto.
            for (java.util.Map.Entry<String, Boolean> change : batch.entrySet()) {
                moduleBaselineState.put(change.getKey(), change.getValue());
            }
            pendingModuleChanges.clear();
            activeModulesCache = mapToModuleLinks(task.getValue());
            // Repintamos el sidebar (asi entra/sale Facturacion en el menu)
            // pero NO reconstruimos la pantalla de Configuracion: el
            // usuario sigue en la pestana Modulos sin parpadeos.
            showShell();
            select("settings");
            refreshSaveModulesButton();
            modulesDirtyHint.setText("Cambios guardados.");
        });
        task.setOnFailed(event -> {
            showError("No se pudieron guardar todos los cambios",
                    "Algunos modulos no se actualizaron. Recarga la pantalla y vuelve a intentarlo.");
            refreshSaveModulesButton();
        });
        start(task, "settings-modules-save-batch");
    }

    // ----- helpers de formularios -----

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

    private VBox errorPanel(String message) {
        VBox panel = content();
        Label title = new Label(message);
        title.getStyleClass().add("section-title");
        Button retry = new Button("Reintentar");
        retry.setGraphic(icon("fas-sync-alt"));
        retry.setOnAction(event -> showDashboard());
        panel.getChildren().addAll(title, retry);
        return panel;
    }

    private VBox content() {
        VBox content = new VBox(22);
        content.getStyleClass().add("content");
        return content;
    }

    private ScrollPane scroll(VBox content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.getStyleClass().add("content-scroll");
        scroll.setFitToWidth(true);
        return scroll;
    }

    private void setCenterAnimated(Node node) {
        node.setOpacity(0);
        node.setTranslateY(12);
        root.setCenter(node);

        FadeTransition fade = new FadeTransition(Duration.millis(180), node);
        fade.setFromValue(0);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), node);
        slide.setFromY(12);
        slide.setToY(0);
        ParallelTransition transition = new ParallelTransition(fade, slide);
        transition.setInterpolator(Interpolator.EASE_OUT);
        transition.play();
    }

    private HBox sectionHeader(String title, String subtitle) {
        Label titleLabel = label(title, "section-title");
        Label subtitleLabel = label(subtitle, "section-subtitle");
        VBox copy = new VBox(2, titleLabel, subtitleLabel);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, copy, spacer);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private VBox activityPanel(String title, String iconLiteral, List<DashboardItem> items) {
        HBox header = new HBox(10, iconBubble(iconLiteral, "panel-icon"), label(title, "card-title"));
        header.setAlignment(Pos.CENTER_LEFT);
        VBox panel = new VBox(12, header);
        panel.getStyleClass().add("activity-panel");
        HBox.setHgrow(panel, Priority.ALWAYS);

        if (items.isEmpty()) {
            panel.getChildren().add(label("Sin movimientos", "status-detail"));
            return panel;
        }

        for (DashboardItem item : items.stream().limit(4).toList()) {
            panel.getChildren().add(activityLine(item));
        }
        return panel;
    }

    private HBox activityLine(DashboardItem item) {
        VBox copy = new VBox(2, label(item.title(), "activity-title"), label(item.subtitle(), "activity-subtitle"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label value = label(displayValue(item.value()), "activity-value");
        HBox line = new HBox(10, copy, spacer, value);
        line.getStyleClass().add("activity-line");
        line.setAlignment(Pos.CENTER_LEFT);
        return line;
    }

    private VBox metric(String title, String value, String detail, String iconLiteral, String colorClass) {
        HBox header = new HBox(10, iconBubble(iconLiteral, "metric-icon", colorClass), label(title, "metric-label"));
        header.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, header, label(value, "metric-value"), label(detail, "metric-detail"));
        box.getStyleClass().add("metric-card");
        return box;
    }

    private VBox actionTile(String text, String iconLiteral, Runnable action) {
        VBox tile = new VBox(8, iconBubble(iconLiteral, "quick-action-icon"), label(text, "action-title"));
        tile.setAlignment(Pos.CENTER);
        tile.getStyleClass().add("action-tile");
        tile.setOnMouseClicked(event -> action.run());
        return tile;
    }

    private StackPane iconBubble(String iconLiteral, String... styleClasses) {
        StackPane bubble = new StackPane(icon(iconLiteral));
        bubble.getStyleClass().addAll(styleClasses);
        return bubble;
    }

    private HBox footer() {
        Label left = label("BENJAGEST", "footer-text");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label right = label("Backend Java + MariaDB", "footer-text");
        HBox footer = new HBox(12, left, spacer, right);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("footer");
        return footer;
    }

    private Node icon(String literal) {
        FontIcon icon = new FontIcon();
        try {
            icon.setIconLiteral(literal);
        } catch (RuntimeException ignored) {
            try {
                icon.setIconLiteral("fas-circle");
            } catch (RuntimeException ignoredAgain) {
                Label fallback = new Label("");
                fallback.getStyleClass().add("font-icon");
                return fallback;
            }
        }
        icon.getStyleClass().add("font-icon");
        return icon;
    }

    private String moduleIcon(String module) {
        return activeModules().stream()
                .filter(link -> link.id().equals(module))
                .map(ModuleLink::icon)
                .findFirst()
                .orElse("fas-briefcase");
    }

    private String moduleTitle(String module) {
        if (appMode == AppMode.ADVISORY) {
            String key = "module.advisory." + module;
            String translated = t(key);
            if (!key.equals(translated)) {
                return translated;
            }
        }
        return t("module." + module);
    }

    private void toggleLanguage() {
        language = language == Language.ES ? Language.EN : Language.ES;
        showShell();
        if ("dashboard".equals(currentModule)) {
            showDashboard();
        } else {
            showModule(currentModule);
        }
    }

    private String columnTitle(String field) {
        return t("column." + field);
    }

    private String t(String key) {
        if (language == Language.EN) {
            return switch (key) {
                case "pinIdentification" -> "Employee PIN identification";
                case "login" -> "Sign in";
                case "demoPin" -> "Demo PIN: 1234, 5678 or 2468";
                case "pinRequired" -> "PIN required";
                case "pinRequiredDetail" -> "Enter the employee PIN.";
                case "loginFailed" -> "Could not sign in";
                case "loginFailedDetail" -> "The PIN does not exist or the backend is unavailable.";
                case "refresh" -> "Refresh";
                case "logout" -> "Exit";
                case "mode.advisory" -> "Advisory";
                case "mode.business" -> "Business";
                case "mode.advisory.eyebrow" -> "Advisory mode";
                case "mode.business.eyebrow" -> "Business owner mode";
                case "mode.advisory.description" -> "Portfolio, tax and labor management for client companies.";
                case "mode.business.description" -> "Own-company operations, billing, purchases and team control.";
                case "modules" -> "Modules";
                case "home" -> "Home";
                case "session" -> "Session";
                case "activeCompany" -> "Active company";
                case "operationalSummary" -> "Operational summary as of";
                case "sessionOf" -> "Session for";
                case "dashboardLoadFailed" -> "Could not load the dashboard";
                case "moduleLoadFailed" -> "Could not load module";
                case "billed" -> "Billed";
                case "totalIssued" -> "Total issued";
                case "pendingCollection" -> "Pending collection";
                case "uncollectedAmount" -> "Uncollected amount";
                case "registeredExpenses" -> "Registered expenses";
                case "purchasesSuppliers" -> "Purchases and suppliers";
                case "payrollCost" -> "Payroll cost";
                case "monthlyNet" -> "Monthly net loaded";
                case "customers" -> "Customers";
                case "activeCompanies" -> "Active companies";
                case "invoices" -> "Invoices";
                case "issuedDocuments" -> "Issued documents";
                case "employees" -> "Employees";
                case "operationalTeam" -> "Operational team";
                case "openAlerts" -> "Open alerts";
                case "pendingReview" -> "Pending review";
                case "latestInvoices" -> "Latest invoices";
                case "alerts" -> "Alerts";
                case "agenda" -> "Calendar";
                case "mainIndicators" -> "Main indicators";
                case "quickAccess" -> "Quick access";
                case "quickAccessDetail" -> "Main modules without going through menus";
                case "recentActivity" -> "Recent activity";
                case "recentActivityDetail" -> "Invoices, alerts and next company milestones";
                case "records" -> "records";
                case "new" -> "New";
                case "edit" -> "Edit";
                case "noRecords" -> "No records yet";
                case "selectRecord" -> "Select a record";
                case "selectRecordDetail" -> "Choose a table row to edit it.";
                case "editRecord" -> "Edit record";
                case "newRecord" -> "New record";
                case "update" -> "Update";
                case "save" -> "Save";
                case "saveFailed" -> "Could not save";
                case "updateFailed" -> "Could not update";
                case "deleteFailed" -> "Could not delete";
                case "backendCheck" -> "Check the data and make sure the backend is available.";
                case "module.customers" -> "Customers";
                case "module.billing" -> "Billing";
                case "module.issuers" -> "Issuers";
                case "module.purchases" -> "Purchases";
                case "module.labor" -> "Labor";
                case "module.tax" -> "Tax";
                case "module.reports" -> "Reports";
                case "module.calendar" -> "Calendar";
                case "module.settings" -> "Users";
                case "module.advisory.customers" -> "Client portfolio";
                case "module.advisory.billing" -> "Client billing";
                case "module.advisory.issuers" -> "Client issuers";
                case "module.advisory.purchases" -> "Reviewed purchases";
                case "module.advisory.labor" -> "Client labor";
                case "module.advisory.tax" -> "Client tax";
                case "module.advisory.reports" -> "Advisory reports";
                case "module.advisory.calendar" -> "Advisory calendar";
                case "module.advisory.settings" -> "Advisory team";
                case "field.name" -> "Name";
                case "field.taxId" -> "Tax ID";
                case "field.contact" -> "Contact";
                case "field.vat" -> "VAT";
                case "field.clockEvent" -> "Clock event";
                case "field.categoryType" -> "Category/Type";
                case "field.status" -> "Status";
                case "field.title" -> "Title";
                case "field.description" -> "Description";
                case "field.amount" -> "Amount";
                case "field.date" -> "Date";
                case "field.email" -> "Email";
                case "field.phone" -> "Phone";
                case "field.pin" -> "PIN";
                case "field.minutes" -> "Minutes";
                case "column.nombre" -> "Name";
                case "column.nif" -> "Tax ID";
                case "column.contacto" -> "Contact";
                case "column.telefono" -> "Phone";
                case "column.factura" -> "Invoice";
                case "column.cliente" -> "Customer";
                case "column.fecha" -> "Date";
                case "column.estado" -> "Status";
                case "column.cobro" -> "Collection";
                case "column.total" -> "Total";
                case "column.proveedor" -> "Supplier";
                case "column.categoria" -> "Category";
                case "column.pago" -> "Payment";
                case "column.empleado" -> "Employee";
                case "column.minutos" -> "Minutes";
                case "column.trabajo" -> "Work";
                case "column.modelo" -> "Model";
                case "column.ejercicio" -> "Year";
                case "column.periodo" -> "Period";
                case "column.importe" -> "Amount";
                case "column.aviso" -> "Alert";
                case "column.detalle" -> "Detail";
                case "column.prioridad" -> "Priority";
                case "column.tipo" -> "Type";
                case "column.acceso" -> "Access";
                case "column.evento" -> "Event";
                default -> key.startsWith("column.") ? key.substring(7) : key;
            };
        }

        return switch (key) {
            case "pinIdentification" -> "Identificacion por PIN de empleado";
            case "login" -> "Entrar";
            case "demoPin" -> "Datos demo: PIN 1234, 5678 o 2468";
            case "pinRequired" -> "PIN requerido";
            case "pinRequiredDetail" -> "Introduce el PIN de empleado.";
            case "loginFailed" -> "No se pudo iniciar sesion";
            case "loginFailedDetail" -> "El PIN no existe o el backend no esta disponible.";
            case "refresh" -> "Actualizar";
            case "logout" -> "Salir";
            case "mode.advisory" -> "Asesoria";
            case "mode.business" -> "Empresario";
            case "mode.advisory.eyebrow" -> "Modo asesoria";
            case "mode.business.eyebrow" -> "Modo empresario";
            case "mode.advisory.description" -> "Cartera, fiscalidad y laboral de empresas cliente.";
            case "mode.business.description" -> "Operacion de empresa propia, facturacion, compras y equipo.";
            case "modules" -> "Modulos";
            case "home" -> "Inicio";
            case "session" -> "Sesion";
            case "activeCompany" -> "Empresa activa";
            case "operationalSummary" -> "Resumen operativo a";
            case "sessionOf" -> "Sesion de";
            case "dashboardLoadFailed" -> "No se pudo cargar el dashboard";
            case "moduleLoadFailed" -> "No se pudo cargar el modulo";
            case "billed" -> "Facturado";
            case "totalIssued" -> "Total emitido";
            case "pendingCollection" -> "Pendiente de cobro";
            case "uncollectedAmount" -> "Importe no cobrado";
            case "registeredExpenses" -> "Gastos registrados";
            case "purchasesSuppliers" -> "Compras y proveedores";
            case "payrollCost" -> "Coste de nominas";
            case "monthlyNet" -> "Neto mensual cargado";
            case "customers" -> "Clientes";
            case "activeCompanies" -> "Empresas activas";
            case "invoices" -> "Facturas";
            case "issuedDocuments" -> "Documentos emitidos";
            case "employees" -> "Empleados";
            case "operationalTeam" -> "Equipo operativo";
            case "openAlerts" -> "Avisos abiertos";
            case "pendingReview" -> "Pendientes de revisar";
            case "latestInvoices" -> "Ultimas facturas";
            case "alerts" -> "Avisos";
            case "agenda" -> "Agenda";
            case "mainIndicators" -> "Indicadores principales";
            case "quickAccess" -> "Accesos rapidos";
            case "quickAccessDetail" -> "Modulos principales para trabajar sin pasar por menus";
            case "recentActivity" -> "Actividad reciente";
            case "recentActivityDetail" -> "Facturas, avisos y proximos hitos de la empresa";
            case "records" -> "registros";
            case "new" -> "Nuevo";
            case "edit" -> "Editar";
            case "noRecords" -> "Sin registros todavia";
            case "selectRecord" -> "Selecciona un registro";
            case "selectRecordDetail" -> "Elige una fila de la tabla para poder editarla.";
            case "editRecord" -> "Editar registro";
            case "newRecord" -> "Nuevo registro";
            case "update" -> "Actualizar";
            case "save" -> "Guardar";
            case "saveFailed" -> "No se pudo guardar";
            case "updateFailed" -> "No se pudo actualizar";
            case "deleteFailed" -> "No se pudo eliminar";
            case "backendCheck" -> "Revisa los datos y que el backend este disponible.";
            case "module.customers" -> "Clientes";
            case "module.billing" -> "Facturacion";
            case "module.issuers" -> "Emisores";
            case "module.purchases" -> "Compras";
            case "module.labor" -> "Laboral";
            case "module.tax" -> "Fiscal";
            case "module.reports" -> "Informes";
            case "module.calendar" -> "Agenda";
            case "module.settings" -> "Usuarios";
            case "module.advisory.customers" -> "Cartera clientes";
            case "module.advisory.billing" -> "Facturacion clientes";
            case "module.advisory.issuers" -> "Emisores clientes";
            case "module.advisory.purchases" -> "Compras revisadas";
            case "module.advisory.labor" -> "Laboral clientes";
            case "module.advisory.tax" -> "Fiscal clientes";
            case "module.advisory.reports" -> "Informes asesoria";
            case "module.advisory.calendar" -> "Agenda asesoria";
            case "module.advisory.settings" -> "Equipo asesoria";
            default -> key.startsWith("column.") ? key.substring(7) : switch (key) {
                case "field.name" -> "Nombre";
                case "field.taxId" -> "NIF/CIF";
                case "field.contact" -> "Contacto";
                case "field.vat" -> "IVA";
                case "field.clockEvent" -> "Fichaje";
                case "field.categoryType" -> "Categoria/Tipo";
                case "field.status" -> "Estado";
                case "field.title" -> "Titulo";
                case "field.description" -> "Descripcion";
                case "field.amount" -> "Importe";
                case "field.date" -> "Fecha";
                case "field.email" -> "Email";
                case "field.phone" -> "Telefono";
            case "field.pin" -> "PIN";
            case "field.minutes" -> "Minutos";
            case "column.nombre" -> "Nombre";
            case "column.nif" -> "NIF/CIF";
            case "column.contacto" -> "Contacto";
            case "column.email" -> "Email";
            case "column.telefono" -> "Telefono";
            case "column.factura" -> "Factura";
            case "column.cliente" -> "Cliente";
            case "column.fecha" -> "Fecha";
            case "column.estado" -> "Estado";
            case "column.cobro" -> "Cobro";
            case "column.total" -> "Total";
            case "column.proveedor" -> "Proveedor";
            case "column.categoria" -> "Categoria";
            case "column.pago" -> "Pago";
            case "column.empleado" -> "Empleado";
            case "column.minutos" -> "Minutos";
            case "column.trabajo" -> "Trabajo";
            case "column.modelo" -> "Modelo";
            case "column.ejercicio" -> "Ejercicio";
            case "column.periodo" -> "Periodo";
            case "column.importe" -> "Importe";
            case "column.aviso" -> "Aviso";
            case "column.detalle" -> "Detalle";
            case "column.prioridad" -> "Prioridad";
            case "column.tipo" -> "Tipo";
            case "column.acceso" -> "Acceso";
            case "column.evento" -> "Evento";
            default -> key;
        };
        };
    }

    private Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private void add(Map<String, TextField> fields, String... namesAndPrompts) {
        for (int index = 0; index < namesAndPrompts.length; index += 2) {
            TextField field = new TextField();
            field.setPromptText(namesAndPrompts[index + 1]);
            fields.put(namesAndPrompts[index], field);
        }
    }

    private Map<String, String> values(Map<String, TextField> fields) {
        Map<String, String> values = new LinkedHashMap<>();
        fields.forEach((key, field) -> values.put(key, field.getText()));
        return values;
    }

    private String labelFor(String key) {
        return switch (key) {
            case "legalName" -> t("field.name");
            case "taxIdentifier" -> t("field.taxId");
            case "contactName" -> t("field.contact");
            case "vatPercent" -> t("field.vat");
            case "eventType" -> t("field.clockEvent");
            case "category" -> t("field.categoryType");
            case "status" -> t("field.status");
            case "title" -> t("field.title");
            case "description" -> t("field.description");
            case "amount" -> t("field.amount");
            case "date" -> t("field.date");
            case "email" -> t("field.email");
            case "phone" -> t("field.phone");
            case "pin" -> t("field.pin");
            case "minutes" -> t("field.minutes");
            default -> key;
        };
    }

    private String money(String value) {
        if (value == null || value.isBlank()) {
            return CURRENCY_FORMAT.format(BigDecimal.ZERO);
        }
        try {
            return CURRENCY_FORMAT.format(new BigDecimal(value.replace(",", "")));
        } catch (NumberFormatException exception) {
            return value + " €";
        }
    }

    private String displayValue(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.endsWith(" EUR")) {
            return money(value.substring(0, value.length() - 4));
        }
        if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return LocalDate.parse(value).format(DISPLAY_DATE);
        }
        return value;
    }

    private void start(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private void showError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
            alert.setTitle("BENJAGEST");
            alert.setHeaderText(title);
            alert.showAndWait();
        });
    }

    public static void main(String[] args) {
        launch(args);
    }

    private record ModuleLink(String id, String title, String icon) {
    }

    private enum AppMode {
        ADVISORY("ADVISORY", "mode.advisory", "mode.advisory.eyebrow", "mode.advisory.description", "fas-briefcase"),
        BUSINESS("BUSINESS", "mode.business", "mode.business.eyebrow", "mode.business.description", "fas-building");

        private final String apiValue;
        private final String labelKey;
        private final String eyebrowKey;
        private final String descriptionKey;
        private final String icon;

        AppMode(String apiValue, String labelKey, String eyebrowKey, String descriptionKey, String icon) {
            this.apiValue = apiValue;
            this.labelKey = labelKey;
            this.eyebrowKey = eyebrowKey;
            this.descriptionKey = descriptionKey;
            this.icon = icon;
        }

        private String apiValue() {
            return apiValue;
        }

        private String labelKey() {
            return labelKey;
        }

        private String eyebrowKey() {
            return eyebrowKey;
        }

        private String descriptionKey() {
            return descriptionKey;
        }

        private String icon() {
            return icon;
        }

        private static AppMode from(String value) {
            if ("BUSINESS".equalsIgnoreCase(value)) {
                return BUSINESS;
            }
            return ADVISORY;
        }
    }

    private enum Language {
        ES,
        EN
    }
}
