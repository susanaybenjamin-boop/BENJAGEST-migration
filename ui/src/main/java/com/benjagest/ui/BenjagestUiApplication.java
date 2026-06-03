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

import com.benjagest.ui.model.AuditEvent;
import com.benjagest.ui.model.CertificateOption;
import com.benjagest.ui.model.CompanyData;
import com.benjagest.ui.model.CompanyModuleEntry;
import com.benjagest.ui.model.CustomerSummary;
import com.benjagest.ui.model.DashboardData;
import com.benjagest.ui.model.DashboardItem;
import com.benjagest.ui.model.EmailConfig;
import com.benjagest.ui.model.InvoiceLineDraft;
import com.benjagest.ui.model.InvoiceTexts;
import com.benjagest.ui.model.Membership;
import com.benjagest.ui.model.ModuleData;
import com.benjagest.ui.model.ModuleRow;
import com.benjagest.ui.model.SalesInvoiceSummary;
import com.benjagest.ui.model.SeriesEntry;
import com.benjagest.ui.model.SessionInfo;
import com.benjagest.ui.model.VerifactuConfig;
import com.benjagest.ui.service.AuthApiClient;
import com.benjagest.ui.service.AuthSession;
import com.benjagest.ui.service.BillingApiClient;
import com.benjagest.ui.service.CustomerApiClient;
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
import javafx.scene.control.ComboBox;
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
            new ModuleLink("purchases", "Compras", "fas-receipt"),
            new ModuleLink("reports", "Informes", "fas-chart-line"),
            new ModuleLink("calendar", "Agenda", "fas-calendar-alt"),
            new ModuleLink("settings", "Configuracion", "fas-cog")
    );

    private static final List<ModuleLink> BUSINESS_MODULES = List.of(
            new ModuleLink("customers", "Clientes", "fas-users"),
            new ModuleLink("billing", "Facturacion", "fas-file-invoice-dollar"),
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
    private final AuthApiClient authApiClient = new AuthApiClient();
    private final SettingsApiClient settingsApiClient = new SettingsApiClient();
    private final BillingApiClient billingApiClient = new BillingApiClient();
    private final CustomerApiClient customerApiClient = new CustomerApiClient();
    private final Map<String, Button> navigationButtons = new LinkedHashMap<>();

    private BorderPane root;
    private SessionInfo session;
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

    // ----- Historial de navegacion (mouse BACK/FORWARD) -----
    // Cada pantalla "principal" registra el Runnable que la repinta. Asi
    // BACK/FORWARD pueden repetirlo sin tocar el resto del flujo. Limite
    // de 20 entradas por mano (si lo dejamos crecer, se acumulan
    // Runnables-clausura con referencias indirectas a Bundle/Tabs que
    // chuparian RAM).
    private final java.util.Deque<Runnable> navBack = new java.util.ArrayDeque<>();
    private final java.util.Deque<Runnable> navForward = new java.util.ArrayDeque<>();
    private Runnable navCurrent;
    private boolean navReplaying = false;

    // Slugs del catalogo que la UI sabe pintar. Si llega un slug activo
    // que no esta aqui, se ignora en el sidebar (no hay vista para el).
    private static final java.util.Set<String> KNOWN_VIEWS = java.util.Set.of(
            "customers", "billing", "purchases", "labor",
            "tax", "reports", "calendar", "settings"
    );

    @Override
    public void start(Stage stage) {
        root = new BorderPane();
        root.getStyleClass().add("app-root");

        Scene scene = new Scene(root, 1180, 760);
        scene.getStylesheets().add(getClass().getResource("/com/benjagest/ui/app.css").toExternalForm());
        setupGlobalShortcuts(scene);

        stage.setTitle("BENJAGEST");
        stage.getIcons().add(AppBrand.loadWindowIcon());
        stage.setMinWidth(920);
        stage.setMinHeight(640);
        stage.setScene(scene);
        showLogin();
        stage.show();
    }

    // ===================================================================
    //  Atajos globales (Scene-level event filter)
    //  ----------------------------------------------------------------
    //  Tab/Shift+Tab → navegacion entre campos (default JavaFX, sin
    //                   tocar nada — las TextField estan en orden de
    //                   declaracion).
    //  Ctrl+K        → command palette (buscador rapido de acciones).
    //  Ctrl+N        → nueva factura (si hay sesion).
    //  Ctrl+F        → ir a Facturacion.
    //  Ctrl+H        → ir a Inicio / dashboard.
    //  F5            → recargar dashboard.
    //
    //  Se ignoran si el foco esta dentro de un TextField/TextArea para
    //  no pisar el texto que el usuario esta escribiendo (Ctrl+N en un
    //  textarea seria una sorpresa horrible). Ctrl+K es la unica
    //  excepcion: siempre captura.
    // ===================================================================

    /**
     * Registra la pantalla actual en el historial. Si se llama desde un
     * replay (navigateBack/navigateForward) no apila — esto evita que
     * BACK quede atascado en bucle entre dos pantallas.
     */
    private void recordNav(Runnable showAgain) {
        if (navReplaying) return;
        if (navCurrent != null) {
            navBack.push(navCurrent);
            while (navBack.size() > 20) navBack.pollLast();
        }
        navForward.clear();
        navCurrent = showAgain;
    }

    private void navigateBack() {
        if (navBack.isEmpty()) return;
        Runnable previous = navBack.pop();
        if (navCurrent != null) navForward.push(navCurrent);
        navCurrent = previous;
        navReplaying = true;
        try { previous.run(); } finally { navReplaying = false; }
    }

    private void navigateForward() {
        if (navForward.isEmpty()) return;
        Runnable next = navForward.pop();
        if (navCurrent != null) navBack.push(navCurrent);
        navCurrent = next;
        navReplaying = true;
        try { next.run(); } finally { navReplaying = false; }
    }

    private void setupGlobalShortcuts(Scene scene) {
        // Botones laterales del raton: 4 = BACK, 5 = FORWARD.
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, ev -> {
            if (ev.getButton() == javafx.scene.input.MouseButton.BACK) {
                navigateBack();
                ev.consume();
            } else if (ev.getButton() == javafx.scene.input.MouseButton.FORWARD) {
                navigateForward();
                ev.consume();
            }
        });

        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
            // Ctrl+K abre el command palette siempre (incluso desde un
            // TextField — es el "alfred" del app).
            if (ev.isControlDown() && ev.getCode() == javafx.scene.input.KeyCode.K) {
                if (session != null) {
                    showCommandPalette();
                }
                ev.consume();
                return;
            }

            // El resto de atajos se ignoran si el foco esta en un campo
            // de texto, para no comerse pulsaciones del usuario.
            javafx.scene.Node focused = scene.getFocusOwner();
            boolean inTextInput = focused instanceof javafx.scene.control.TextInputControl;
            if (inTextInput) {
                return;
            }

            if (session == null) {
                return;
            }

            if (ev.isControlDown() && ev.getCode() == javafx.scene.input.KeyCode.N) {
                showInvoiceEditor(null);
                ev.consume();
            } else if (ev.isControlDown() && ev.getCode() == javafx.scene.input.KeyCode.F) {
                showModule("billing");
                ev.consume();
            } else if (ev.isControlDown() && ev.getCode() == javafx.scene.input.KeyCode.H) {
                showDashboard();
                ev.consume();
            } else if (ev.getCode() == javafx.scene.input.KeyCode.F5) {
                showDashboard();
                ev.consume();
            }
        });
    }

    /**
     * Lista de acciones del command palette. Slug + label + runnable.
     * Tab fija un orden estable y lo mantiene a la hora de filtrar por
     * texto (substring case-insensitive).
     */
    private record PaletteAction(String label, String icon, Runnable action) {
    }

    private List<PaletteAction> commandPaletteActions() {
        List<PaletteAction> all = new java.util.ArrayList<>();
        all.add(new PaletteAction(t("palette.action.home"), "fas-home", this::showDashboard));
        all.add(new PaletteAction(t("palette.action.customers"), "fas-users", () -> showModule("customers")));
        all.add(new PaletteAction(t("palette.action.billing"), "fas-file-invoice-dollar", () -> showModule("billing")));
        all.add(new PaletteAction(t("palette.action.new_invoice"), "fas-plus", () -> showInvoiceEditor(null)));
        all.add(new PaletteAction(t("palette.action.settings"), "fas-cog", () -> showModule("settings")));
        all.add(new PaletteAction(t("palette.action.calendar"), "fas-calendar-alt", () -> showModule("calendar")));
        all.add(new PaletteAction(t("palette.action.purchases"), "fas-receipt", () -> showModule("purchases")));
        all.add(new PaletteAction(t("palette.action.tax"), "fas-percentage", () -> showModule("tax")));
        all.add(new PaletteAction(t("palette.action.labor"), "fas-hard-hat", () -> showModule("labor")));
        all.add(new PaletteAction(t("palette.action.reports"), "fas-chart-line", () -> showModule("reports")));
        return all;
    }

    private void showCommandPalette() {
        Dialog<PaletteAction> dialog = new Dialog<>();
        dialog.setTitle(t("palette.title"));
        dialog.setHeaderText(null);
        dialog.initStyle(javafx.stage.StageStyle.UTILITY);

        TextField search = new TextField();
        search.setPromptText(t("palette.search.prompt"));
        search.getStyleClass().add("form-input");
        search.setMaxWidth(Double.MAX_VALUE);

        javafx.scene.control.ListView<PaletteAction> listView = new javafx.scene.control.ListView<>();
        listView.setPrefHeight(280);
        listView.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(PaletteAction item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.label());
                    setGraphic(icon(item.icon()));
                }
            }
        });

        List<PaletteAction> all = commandPaletteActions();
        javafx.collections.ObservableList<PaletteAction> items = FXCollections.observableArrayList(all);
        listView.setItems(items);
        if (!items.isEmpty()) {
            listView.getSelectionModel().selectFirst();
        }

        search.textProperty().addListener((obs, oldV, newV) -> {
            String q = newV == null ? "" : newV.trim().toLowerCase(Locale.ROOT);
            if (q.isBlank()) {
                items.setAll(all);
            } else {
                items.setAll(all.stream()
                        .filter(a -> a.label().toLowerCase(Locale.ROOT).contains(q))
                        .toList());
            }
            if (!items.isEmpty()) {
                listView.getSelectionModel().selectFirst();
            }
        });

        // Flechas mueven la seleccion en la lista aunque el foco este en
        // el TextField; Enter elige; Esc cierra.
        search.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == javafx.scene.input.KeyCode.DOWN) {
                listView.getSelectionModel().selectNext();
                ev.consume();
            } else if (ev.getCode() == javafx.scene.input.KeyCode.UP) {
                listView.getSelectionModel().selectPrevious();
                ev.consume();
            } else if (ev.getCode() == javafx.scene.input.KeyCode.ENTER) {
                PaletteAction sel = listView.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    dialog.setResult(sel);
                    dialog.close();
                }
                ev.consume();
            } else if (ev.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                dialog.setResult(null);
                dialog.close();
                ev.consume();
            }
        });
        listView.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                PaletteAction sel = listView.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    dialog.setResult(sel);
                    dialog.close();
                }
            }
        });

        VBox box = new VBox(10, search, listView);
        box.setPadding(new Insets(12));
        box.setPrefWidth(420);
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        // Esto fuerza el foco al search cuando se abre.
        Platform.runLater(search::requestFocus);

        Optional<PaletteAction> result = dialog.showAndWait();
        result.ifPresent(action -> action.action().run());
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
     *
     * El modulo "settings" siempre queda al final del sidebar, aunque su
     * displayOrder en BD sea bajo (es sub-modulo de "core" con orden 2).
     * Es una opcion de mantenimiento, no de uso diario.
     */
    private List<ModuleLink> mapToModuleLinks(List<CompanyModuleEntry> active) {
        return active.stream()
                .filter(m -> KNOWN_VIEWS.contains(m.slug()))
                .sorted(Comparator
                        .comparingInt((CompanyModuleEntry m) -> "settings".equals(m.slug()) ? 1 : 0)
                        .thenComparingInt(CompanyModuleEntry::displayOrder))
                .map(m -> new ModuleLink(
                        m.slug(),
                        moduleTitle(m.slug()),
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
        // Tras la unificacion (decision 2026-06-01), la empresa es su propio
        // emisor: no hace falta una linea "Facturando como:" porque siempre
        // facturas como tu empresa. Si cambia el legal_name desde la
        // pantalla de Configuracion, el subtitle se refresca solo.
        VBox titleBlock = new VBox(2, title, subtitle);

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
            // Revocamos el refresh token en backend antes de limpiar el
            // estado local. Asi, si alguien recupera ese refresh ya no
            // sirve para generar mas accesses.
            authApiClient.logout();
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
        recordNav(this::showDashboard);
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
        recordNav(() -> showModule(module));
        currentModule = module;
        select(module);
        if ("billing".equals(module)) {
            showBilling();
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


    private VBox moduleView(ModuleData data) {
        if ("calendar".equals(data.module())) {
            return calendarView(data);
        }

        VBox content = content();

        Label title = new Label(moduleTitle(data.module()));
        title.getStyleClass().add("module-detail-title");
        Label count = new Label(data.records().size() + t("module.records_count_suffix"));
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
            case "customers" -> t("module.unit.active_customers");
            case "billing" -> t("module.unit.invoices");
            case "purchases" -> t("module.unit.expenses");
            case "labor" -> t("module.unit.work_logs");
            case "tax" -> t("module.unit.tax_models");
            case "reports" -> t("module.unit.alerts");
            case "settings" -> t("module.unit.users_employees");
            default -> t("module.unit.records");
        };

        VBox panel = new VBox(12,
                new HBox(10, iconBubble(moduleIcon(data.module()), "panel-icon"), label(t("module.section.summary"), "card-title")),
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
                value = t("module.summary.no_field");
            }
            counts.merge(value, 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            counts.put(t("module.empty.no_data"), 0);
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
            case "billing", "purchases", "labor", "reports" -> t("module.section.activity_by_date");
            case "tax" -> t("module.section.models_by_period");
            case "settings" -> t("module.section.team_by_type");
            default -> t("module.section.main_distribution");
        };
    }

    private String pieTitle(String module) {
        return switch (module) {
            case "billing" -> t("module.section.collection_status");
            case "purchases" -> t("module.section.payment_status");
            case "labor", "tax", "reports" -> t("module.section.status");
            case "settings" -> t("module.section.pin_access");
            default -> t("module.section.contacts");
        };
    }

    private String summaryLine(ModuleData data) {
        if (data.records().isEmpty()) {
            return t("module.empty.no_data_loaded");
        }
        ModuleRow first = data.records().getFirst();
        return first.fields().entrySet().stream()
                .limit(2)
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .reduce((left, right) -> left + " · " + right)
                .orElse(t("module.summary.ready_to_review"));
    }

    private VBox calendarView(ModuleData data) {
        VBox content = content();
        LocalDate today = LocalDate.now();

        Label title = new Label(data.title());
        title.getStyleClass().add("module-detail-title");
        Label count = new Label(pluralEvents(data.records().size()));
        count.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, count);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button create = new Button(t("calendar.btn.new_event"));
        create.setGraphic(icon("fas-calendar-plus"));
        create.setOnAction(event -> showFormDialog(data.module(), null));

        HBox header = new HBox(16, titleBox, iconBubble(moduleIcon(data.module()), "module-title-icon"), spacer, create);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        StackPane viewHost = new StackPane();
        viewHost.getStyleClass().add("calendar-view-host");

        // Las claves "day"/"week"/"month"/"year" son estables; el texto
        // visible se traduce y se guarda en userData para identificar
        // botones sin depender del idioma activo.
        List<Button> modeButtons = new ArrayList<>();
        Button dayButton = viewMode("day", false);
        Button weekButton = viewMode("week", false);
        Button monthButton = viewMode("month", true);
        Button yearButton = viewMode("year", false);
        modeButtons.addAll(List.of(dayButton, weekButton, monthButton, yearButton));

        dayButton.setOnAction(event -> showCalendarMode("day", data, today, modeButtons, viewHost));
        weekButton.setOnAction(event -> showCalendarMode("week", data, today, modeButtons, viewHost));
        monthButton.setOnAction(event -> showCalendarMode("month", data, today, modeButtons, viewHost));
        yearButton.setOnAction(event -> showCalendarMode("year", data, today, modeButtons, viewHost));

        HBox modes = new HBox(8, dayButton, weekButton, monthButton, yearButton);
        modes.getStyleClass().add("calendar-modes");

        showCalendarMode("month", data, today, modeButtons, viewHost);

        content.getChildren().addAll(header, modes, viewHost);
        return content;
    }

    private void showCalendarMode(String modeKey, ModuleData data, LocalDate today, List<Button> buttons, StackPane viewHost) {
        buttons.forEach(button -> button.getStyleClass().remove("calendar-mode-selected"));
        buttons.stream()
                .filter(button -> modeKey.equals(button.getUserData()))
                .findFirst()
                .ifPresent(button -> button.getStyleClass().add("calendar-mode-selected"));

        Node view = switch (modeKey) {
            case "day" -> dayCalendarView(data, today);
            case "week" -> weekCalendarView(data, today);
            case "year" -> yearCalendarView(data, today);
            default -> monthCalendarView(data, today);
        };
        viewHost.getChildren().setAll(view);
    }

    private Button viewMode(String modeKey, boolean selected) {
        Button button = new Button(t("calendar.mode." + modeKey));
        button.setUserData(modeKey);
        button.getStyleClass().add("calendar-mode");
        if (selected) {
            button.getStyleClass().add("calendar-mode-selected");
        }
        return button;
    }

    /** Pluraliza "X eventos" según el idioma activo (sin gramática
     *  compleja: cero/uno/muchos). */
    private String pluralEvents(int count) {
        if (count == 0) return t("calendar.events_count_zero");
        if (count == 1) return t("calendar.events_count_one");
        return count + t("calendar.events_count_many");
    }

    /** Letras de los 7 días de la semana en el idioma activo (L→D / M→S). */
    private String[] localizedWeekdayLetters() {
        return new String[] {
                t("calendar.weekday.mon"), t("calendar.weekday.tue"), t("calendar.weekday.wed"),
                t("calendar.weekday.thu"), t("calendar.weekday.fri"), t("calendar.weekday.sat"),
                t("calendar.weekday.sun")
        };
    }

    /** Locale activo para los nombres largos/cortos de mes/día. */
    private Locale activeLocale() {
        return language == Language.EN ? Locale.ENGLISH : Locale.forLanguageTag("es-ES");
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

        Label eyebrow = label(date.getDayOfWeek().getDisplayName(TextStyle.FULL, activeLocale()), "eyebrow");
        Label title = label(date.format(DISPLAY_DATE), "calendar-month");
        Label count = label(events.size() == 1
                ? t("calendar.day.scheduled_one")
                : events.size() + t("calendar.day.scheduled_many_suffix"), "section-subtitle");
        panel.getChildren().addAll(eyebrow, title, count);

        if (events.isEmpty()) {
            panel.getChildren().add(label(t("calendar.day.empty"), "status-detail"));
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

        Label title = label(t("calendar.week.range_prefix") + monday.format(DISPLAY_DATE)
                + t("calendar.week.range_middle") + monday.plusDays(6).format(DISPLAY_DATE), "calendar-month");
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
                label(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, activeLocale()), "calendar-weekday"),
                label(date.getDayOfMonth() + "/" + date.getMonthValue(), "calendar-day-number")
        );
        if (events.isEmpty()) {
            day.getChildren().add(label(t("calendar.week.no_events"), "status-detail"));
            return day;
        }
        events.stream().limit(3).forEach(event -> day.getChildren().add(calendarEventChip(event)));
        if (events.size() > 3) {
            day.getChildren().add(label(t("calendar.week.more_prefix") + (events.size() - 3) + t("calendar.week.more_suffix"), "calendar-event-badge"));
        }
        return day;
    }

    private VBox yearCalendarView(ModuleData data, LocalDate today) {
        VBox panel = new VBox(14);
        panel.getStyleClass().add("calendar-panel");

        Label title = label(t("calendar.year.title_prefix") + today.getYear(), "calendar-month");
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
                label(monthDate.getMonth().getDisplayName(TextStyle.FULL, activeLocale()), "activity-title"),
                label(pluralEvents(events.size()), "module-big-number-small")
        );
        events.stream().limit(2).forEach(event -> card.getChildren().add(calendarEventChip(event)));
        card.setOnMouseClicked(event -> showMonthDialog(monthDate, events));
        return card;
    }

    private HBox calendarEventChip(ModuleRow event) {
        HBox chip = new HBox(6, iconBubble("fas-calendar-check", "tiny-icon"), label(event.fields().getOrDefault("evento", t("calendar.event.default_title")), "calendar-chip-text"));
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
                baseDate.getMonth().getDisplayName(TextStyle.FULL, activeLocale()) + " " + baseDate.getYear(),
                "calendar-month"
        );

        GridPane grid = new GridPane();
        grid.getStyleClass().add("calendar-grid");
        grid.setHgap(8);
        grid.setVgap(8);

        String[] weekdays = localizedWeekdayLetters();
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
            Label badge = label(pluralEvents(events.size()), "calendar-event-badge");
            box.getChildren().add(badge);
        }
        box.setOnMouseClicked(event -> showDayDialog(date, events));
        return box;
    }

    private VBox dayAgenda(ModuleData data, LocalDate today) {
        VBox panel = new VBox(12);
        panel.getStyleClass().add("day-agenda");
        panel.setPrefWidth(330);

        Label title = label(t("calendar.day_agenda.title"), "card-title");
        Label date = label(today.format(DISPLAY_DATE), "section-subtitle");
        panel.getChildren().addAll(new HBox(10, iconBubble("fas-calendar-check", "panel-icon"), new VBox(2, title, date)));

        List<ModuleRow> events = calendarEventsByDay(data, today).getOrDefault(today.getDayOfMonth(), List.of());
        if (events.isEmpty()) {
            panel.getChildren().add(label(t("calendar.day_agenda.no_events"), "status-detail"));
            return panel;
        }

        for (ModuleRow event : events) {
            panel.getChildren().add(calendarEventLine(event));
        }
        return panel;
    }

    private VBox calendarEventLine(ModuleRow event) {
        Label title = label(event.fields().getOrDefault("evento", t("calendar.event.default_title")), "activity-title");
        Label detail = label(event.fields().getOrDefault("detalle", ""), "activity-subtitle");
        Label type = label(event.fields().getOrDefault("tipo", t("calendar.event.default_type")), "activity-value");
        VBox line = new VBox(4, title, detail, type);
        line.getStyleClass().add("calendar-event-line");
        return line;
    }

    private void showDayDialog(LocalDate date, List<ModuleRow> events) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(t("calendar.dialog.title"));
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

        Button create = new Button(t("calendar.btn.new_event"));
        create.setGraphic(icon("fas-calendar-plus"));
        create.getStyleClass().add("calendar-dialog-primary");
        create.setOnAction(action -> {
            dialog.close();
            showFormDialog("calendar", null, Map.of("date", date.toString()));
        });

        VBox copy = new VBox(4,
                label(date.getDayOfWeek().getDisplayName(TextStyle.FULL, activeLocale()), "eyebrow"),
                label(date.format(DISPLAY_DATE), "calendar-dialog-title"),
                label(events.size() == 1
                        ? t("calendar.dialog.planned_one")
                        : events.size() + t("calendar.dialog.planned_many_suffix"), "section-subtitle")
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
        Label title = label(t("calendar.dialog.empty.title"), "activity-title");
        Label detail = label(t("calendar.dialog.empty.body"), "activity-subtitle");
        Button create = new Button(t("calendar.dialog.empty.btn"));
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
        Label title = label(event.fields().getOrDefault("evento", t("calendar.event.default_title")), "calendar-event-card-title");
        Label detail = label(event.fields().getOrDefault("detalle", t("calendar.event.no_detail")), "calendar-event-card-detail");
        Label type = label(event.fields().getOrDefault("tipo", t("calendar.event.default_type")), "calendar-event-card-type");
        VBox copy = new VBox(5, title, detail, type);
        HBox.setHgrow(copy, Priority.ALWAYS);

        Button edit = new Button(t("common.btn.edit"));
        edit.setGraphic(icon("fas-pen"));
        edit.getStyleClass().add("calendar-dialog-secondary");
        edit.setOnAction(action -> {
            dialog.close();
            showFormDialog("calendar", event);
        });

        Button delete = new Button(t("common.btn.delete"));
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
            message.append(t("calendar.dialog.month.no_events"));
        } else {
            for (ModuleRow event : events) {
                message.append("- ")
                        .append(event.fields().getOrDefault("fecha", ""))
                        .append(" · ")
                        .append(event.fields().getOrDefault("evento", t("calendar.event.default_title")))
                        .append("\n");
            }
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message.toString(), ButtonType.OK);
        alert.setTitle(t("calendar.dialog.title"));
        alert.setHeaderText(monthDate.getMonth().getDisplayName(TextStyle.FULL, activeLocale()) + " " + monthDate.getYear());
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
        task.setOnFailed(event -> setCenterAnimated(scroll(errorPanel(t("settings.load_failed")))));
        start(task, "settings-load");
    }

    private VBox settingsView(SettingsBundle bundle) {
        VBox content = content();

        Label title = new Label(t("settings.shell.title"));
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
        Tab companyTab = new Tab(t("settings.tab.company"), settingsCompanyTab(bundle.company()));
        companyTab.setGraphic(icon("fas-building"));
        Tab emailTab = new Tab(t("settings.tab.email"), settingsEmailTab(bundle.email()));
        emailTab.setGraphic(icon("fas-envelope"));
        Tab modulesTab = new Tab(t("settings.tab.modules"), settingsModulesTab(bundle.modules()));
        modulesTab.setGraphic(icon("fas-cubes"));
        Tab auditTab = new Tab(t("settings.tab.audit"), settingsAuditTab());
        auditTab.setGraphic(icon("fas-shield-alt"));
        tabs.getTabs().addAll(companyTab, emailTab, modulesTab, auditTab);
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
        TextField legalName = textInput(company.legalName(), t("settings.company.prompt.legal_name"));
        TextField tradeName = textInput(company.tradeName(), t("settings.company.prompt.trade_name"));
        TextField taxId = textInput(company.taxIdentifier(), t("settings.company.prompt.tax_id"));
        TextField email = textInput(company.email(), t("settings.company.prompt.email"));
        TextField phone = textInput(company.phone(), t("settings.company.prompt.phone"));
        TextField website = textInput(company.website(), t("settings.company.prompt.website"));

        TextField addressLine = textInput(company.addressLine(), t("settings.company.prompt.address_line"));
        TextField city = textInput(company.city(), t("settings.company.prompt.city"));
        TextField province = textInput(company.province(), t("settings.company.prompt.province"));
        TextField postalCode = textInput(company.postalCode(), t("settings.company.prompt.postal_code"));
        TextField country = textInput(company.country() == null || company.country().isBlank() ? t("settings.company.country.default") : company.country(), t("settings.company.prompt.country"));

        TextField iban = textInput(company.iban(), t("settings.company.prompt.iban"));
        TextField registry = textInput(company.registryInformation(), t("settings.company.prompt.registry"));
        TextField terms = textInput(company.legalTerms(), t("settings.company.prompt.terms"));
        TextField footer = textInput(company.invoiceFooter(), t("settings.company.prompt.footer"));

        GridPane generalGrid = formGrid();
        addFormRow(generalGrid, 0, t("settings.company.field.legal_name"), legalName);
        addFormRow(generalGrid, 1, t("settings.company.field.trade_name"), tradeName);
        addFormRow(generalGrid, 2, t("settings.company.field.tax_id"), taxId);
        addFormRow(generalGrid, 3, t("settings.company.field.email"), email);
        addFormRow(generalGrid, 4, t("settings.company.field.phone"), phone);
        addFormRow(generalGrid, 5, t("settings.company.field.website"), website);

        GridPane addressGrid = formGrid();
        addFormRow(addressGrid, 0, t("settings.company.field.address"), addressLine);
        addFormRow(addressGrid, 1, t("settings.company.field.city"), city);
        addFormRow(addressGrid, 2, t("settings.company.field.province"), province);
        addFormRow(addressGrid, 3, t("settings.company.field.postal_code"), postalCode);
        addFormRow(addressGrid, 4, t("settings.company.field.country"), country);

        GridPane billingGrid = formGrid();
        addFormRow(billingGrid, 0, t("settings.company.field.iban"), iban);
        addFormRow(billingGrid, 1, t("settings.company.field.registry"), registry);
        addFormRow(billingGrid, 2, t("settings.company.field.terms"), terms);
        addFormRow(billingGrid, 3, t("settings.company.field.footer"), footer);

        Label typeNote = new Label(t("settings.company.type_note_prefix") + company.companyType()
                + t("settings.company.type_note_suffix"));
        typeNote.getStyleClass().add("settings-hint");

        Button save = new Button(t("settings.company.save"));
        save.setGraphic(icon("fas-save"));
        save.setOnAction(event -> saveCompany(new CompanyData(
                company.id(),
                legalName.getText(),
                tradeName.getText(),
                taxId.getText(),
                company.companyType(),
                email.getText(),
                phone.getText(),
                website.getText(),
                addressLine.getText(),
                city.getText(),
                province.getText(),
                postalCode.getText(),
                country.getText(),
                iban.getText(),
                registry.getText(),
                terms.getText(),
                footer.getText()
        )));

        HBox actions = new HBox(save);
        actions.getStyleClass().add("settings-actions");

        VBox body = new VBox(16,
                label(t("settings.company.section.general"), "settings-section-title"),
                generalGrid,
                typeNote,
                new Separator(),
                label(t("settings.company.section.address"), "settings-section-title"),
                addressGrid,
                new Separator(),
                label(t("settings.company.section.billing"), "settings-section-title"),
                label(t("settings.company.section.billing.hint"), "settings-hint"),
                billingGrid
        );

        Label sectionTitle = label(t("settings.company.section_label"), "settings-section-title");
        return tabLayout(sectionTitle, body, actions);
    }

    private void saveCompany(CompanyData data) {
        if (data.legalName() == null || data.legalName().isBlank()) {
            showError(t("settings.company.error.missing_legal_name.title"), t("settings.company.error.missing_legal_name.body"));
            return;
        }
        Task<CompanyData> task = new Task<>() {
            @Override
            protected CompanyData call() throws Exception {
                return settingsApiClient.updateCompany(data);
            }
        };
        task.setOnSucceeded(event -> {
            // Refresh silencioso: actualizamos AuthSession con la nueva
            // razon social y repintamos el shell (header + sidebar).
            // No reconstruimos la pantalla de Configuracion, asi te
            // quedas en el tab Empresa con los datos guardados visibles.
            CompanyData saved = task.getValue();
            AuthSession.get().updateActiveCompanyLegalName(saved.legalName());
            session = session.withCompanyName(saved.legalName());
            showShell();
            select("settings");
        });
        task.setOnFailed(event -> showError(t("settings.company.save.fail.title"), t("settings.company.save.fail.body")));
        start(task, "settings-company-save");
    }

    // ----- Pestana Email SMTP -----

    private Node settingsEmailTab(EmailConfig config) {
        TextField smtpHost = textInput(config.smtpHost(), t("settings.email.prompt.host"));
        TextField smtpPort = textInput(config.smtpPort() == null ? "" : config.smtpPort().toString(), t("settings.email.prompt.port"));
        TextField smtpUser = textInput(config.smtpUser(), t("settings.email.prompt.user"));
        PasswordField smtpPassword = new PasswordField();
        smtpPassword.setPromptText(config.passwordConfigured()
                ? t("settings.email.prompt.password.saved")
                : t("settings.email.prompt.password"));
        TextField fromAddress = textInput(config.fromAddress(), t("settings.email.prompt.from_address"));
        TextField fromName = textInput(config.fromName(), t("settings.email.prompt.from_name"));
        TextField replyTo = textInput(config.replyTo(), t("settings.email.prompt.reply_to"));
        CheckBox tlsEnabled = new CheckBox(t("settings.email.flag.tls"));
        tlsEnabled.setSelected(config.tlsEnabled());
        CheckBox authRequired = new CheckBox(t("settings.email.flag.auth"));
        authRequired.setSelected(config.authRequired());

        GridPane grid = formGrid();
        addFormRow(grid, 0, t("settings.email.field.host"), smtpHost);
        addFormRow(grid, 1, t("settings.email.field.port"), smtpPort);
        addFormRow(grid, 2, t("settings.email.field.user"), smtpUser);
        addFormRow(grid, 3, t("settings.email.field.password"), smtpPassword);
        addFormRow(grid, 4, t("settings.email.field.from_address"), fromAddress);
        addFormRow(grid, 5, t("settings.email.field.from_name"), fromName);
        addFormRow(grid, 6, t("settings.email.field.reply_to"), replyTo);

        VBox flags = new VBox(8, tlsEnabled, authRequired);

        TextField testRecipient = new TextField();
        testRecipient.setPromptText(t("settings.email.test.prompt"));

        Button save = new Button(t("settings.email.btn.save"));
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

        Button test = new Button(t("settings.email.btn.test"));
        test.setGraphic(icon("fas-paper-plane"));
        test.setOnAction(event -> sendTestEmail(testRecipient.getText()));

        testRecipient.getStyleClass().add("form-input");

        HBox actions = new HBox(test, save);
        actions.getStyleClass().add("settings-actions");

        VBox center = new VBox(16,
                grid,
                flags,
                new Separator(),
                label(t("settings.email.section.test"), "settings-section-title"),
                label(t("settings.email.section.test.hint"), "settings-hint"),
                testRecipient
        );
        return tabLayout(label(t("settings.email.section"), "settings-section-title"), center, actions);
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
                    t("settings.email.save.success"), ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
            showSettings();
        });
        task.setOnFailed(event -> showError(t("settings.email.save.fail.title"), t("settings.email.save.fail.body")));
        start(task, "settings-email-save");
    }

    private void sendTestEmail(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            showError(t("settings.email.test.missing.title"), t("settings.email.test.missing.body"));
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
                    t("settings.email.test.success_prefix") + recipient + t("settings.email.test.success_suffix"),
                    ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
        });
        task.setOnFailed(event -> showError(t("settings.email.test.fail.title"),
                t("settings.email.test.fail.body")));
        start(task, "settings-email-test");
    }

    // ----- Pestana Modulos -----

    private Node settingsModulesTab(List<CompanyModuleEntry> modules) {
        pendingModuleChanges.clear();
        moduleBaselineState.clear();

        Label sectionTitle = label(t("settings.modules.section"), "settings-section-title");
        Label hint = new Label(t("settings.modules.hint"));
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
        saveModulesButton = new Button(t("settings.company.save"));
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

    // ----- Pestana Auditoria -----

    private static final List<String> AUDIT_EVENT_TYPES = List.of(
            "(todos)",
            "LOGIN_OK",
            "LOGIN_FAIL",
            "COMPANY_SWITCHED",
            "MODULE_ENABLED",
            "MODULE_DISABLED",
            "COMPANY_DATA_UPDATED"
    );

    private Node settingsAuditTab() {
        Label sectionTitle = label(t("settings.audit.section"), "settings-section-title");
        Label hint = new Label(t("settings.audit.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        javafx.scene.control.ComboBox<String> typeFilter = new javafx.scene.control.ComboBox<>();
        // El primer item es el "(todos)/(all)" — lo traducimos via t(); el
        // resto son codigos tecnicos que no se traducen.
        typeFilter.getItems().add(t("list.filter.all"));
        typeFilter.getItems().addAll(AUDIT_EVENT_TYPES.subList(1, AUDIT_EVENT_TYPES.size()));
        typeFilter.getSelectionModel().selectFirst();
        typeFilter.getStyleClass().add("form-input");

        TableView<AuditEvent> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("settings.audit.placeholder.empty")));

        TableColumn<AuditEvent, String> colWhen = new TableColumn<>(t("settings.audit.col.when"));
        colWhen.setCellValueFactory(c -> new SimpleStringProperty(shortIso(c.getValue().createdAt())));
        colWhen.setPrefWidth(160);
        TableColumn<AuditEvent, String> colType = new TableColumn<>(t("settings.audit.col.type"));
        colType.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().eventType()));
        colType.setPrefWidth(150);
        TableColumn<AuditEvent, String> colResult = new TableColumn<>(t("settings.audit.col.result"));
        colResult.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().result()));
        colResult.setPrefWidth(80);
        TableColumn<AuditEvent, String> colUser = new TableColumn<>(t("settings.audit.col.user"));
        colUser.setCellValueFactory(c -> new SimpleStringProperty(shortId(c.getValue().userId())));
        colUser.setPrefWidth(120);
        TableColumn<AuditEvent, String> colEntity = new TableColumn<>(t("settings.audit.col.entity"));
        colEntity.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().entityType() == null ? "" : c.getValue().entityType() + ":" + shortId(c.getValue().entityId())
        ));
        colEntity.setPrefWidth(160);
        TableColumn<AuditEvent, String> colIp = new TableColumn<>(t("settings.audit.col.ip"));
        colIp.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().ipAddress()));
        colIp.setPrefWidth(120);
        TableColumn<AuditEvent, String> colDetails = new TableColumn<>(t("settings.audit.col.details"));
        colDetails.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().details()));
        table.getColumns().addAll(List.of(colWhen, colType, colResult, colUser, colEntity, colIp, colDetails));

        Button refresh = new Button(t("settings.audit.btn.refresh"));
        refresh.setGraphic(icon("fas-sync-alt"));
        refresh.setOnAction(event -> loadAuditEvents(table, typeFilter.getValue()));
        typeFilter.setOnAction(event -> loadAuditEvents(table, typeFilter.getValue()));

        loadAuditEvents(table, typeFilter.getValue());

        HBox filterRow = new HBox(10, label(t("settings.audit.filter.label"), "form-label"), typeFilter);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        HBox actions = new HBox(refresh);
        actions.getStyleClass().add("settings-actions");

        VBox header = new VBox(8, sectionTitle, hint, filterRow);
        return tabLayout(header, table, actions);
    }

    private void loadAuditEvents(TableView<AuditEvent> table, String selectedType) {
        // Comparamos contra ambos idiomas — el "(todos)/(all)" del filtro
        // depende del idioma activo en el momento de pintar el ComboBox.
        String filter = selectedType == null
                || "(todos)".equals(selectedType)
                || "(all)".equals(selectedType)
                || t("list.filter.all").equals(selectedType)
                ? null : selectedType;
        Task<List<AuditEvent>> task = new Task<>() {
            @Override
            protected List<AuditEvent> call() throws Exception {
                return settingsApiClient.listAuditEvents(filter, null, 200);
            }
        };
        task.setOnSucceeded(event -> table.setItems(FXCollections.observableArrayList(task.getValue())));
        task.setOnFailed(event -> table.setPlaceholder(new Label(t("settings.audit.load.fail"))));
        start(task, "settings-audit-load");
    }

    private String shortIso(String iso) {
        if (iso == null || iso.length() < 19) {
            return iso == null ? "" : iso;
        }
        // "2026-06-01T19:30:00..." -> "2026-06-01 19:30:00"
        return iso.substring(0, 10) + " " + iso.substring(11, 19);
    }

    private String shortId(String id) {
        if (id == null || id.length() < 8) {
            return id == null ? "" : id;
        }
        return id.substring(0, 8);
    }

    // ===================================================================
    //  Pantalla Facturacion (Slice F2/F3/F5): sub-tabs Dashboard / Facturas
    //  / Configuracion. Mismo patron CSS que la pantalla de Configuracion
    //  (settings-tabs + settings-tab-body), siguiendo la regla guardada:
    //  no se inventan paletas; se reutilizan las clases de Pablo.
    // ===================================================================

    private void showBilling() {
        Task<BillingBundle> task = new Task<>() {
            @Override
            protected BillingBundle call() throws Exception {
                List<SalesInvoiceSummary> invoices = billingApiClient.listInvoices(null, null, null, 200);
                List<SeriesEntry> series = billingApiClient.listSeries();
                VerifactuConfig vfConfig = billingApiClient.getVerifactuConfig();
                InvoiceTexts texts = billingApiClient.getInvoiceTexts();
                List<CertificateOption> certificates;
                try {
                    certificates = billingApiClient.listCertificateOptions();
                } catch (Exception ignored) {
                    // El modulo "documents" puede no estar activo; en ese caso
                    // la lista queda vacia y el ComboBox aparece deshabilitado.
                    certificates = List.of();
                }
                return new BillingBundle(invoices, series, vfConfig, texts, certificates);
            }
        };
        task.setOnSucceeded(event -> setCenterAnimated(billingView(task.getValue())));
        task.setOnFailed(event -> setCenterAnimated(scroll(errorPanel(
                t("billing.shell.load_failed")))));
        start(task, "billing-load");
    }

    private record BillingBundle(List<SalesInvoiceSummary> invoices,
                                 List<SeriesEntry> series,
                                 VerifactuConfig verifactuConfig,
                                 InvoiceTexts invoiceTexts,
                                 List<CertificateOption> certificates) {
    }

    private VBox billingView(BillingBundle bundle) {
        VBox content = content();

        Label title = new Label(t("billing.shell.title"));
        title.getStyleClass().add("module-detail-title");
        Label subtitle = new Label(t("billing.shell.subtitle"));
        subtitle.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, subtitle);

        StackPane moduleIcon = iconBubble("fas-file-invoice-dollar", "module-title-icon");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button newInvoice = new Button(t("billing.shell.new_invoice"));
        newInvoice.setGraphic(icon("fas-plus"));
        newInvoice.setOnAction(event -> showInvoiceEditor(null));

        HBox header = new HBox(16, titleBox, moduleIcon, spacer, newInvoice);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("settings-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab dashboardTab = new Tab(t("billing.tab.dashboard"), billingDashboardTab(bundle));
        dashboardTab.setGraphic(icon("fas-chart-bar"));

        Tab invoicesTab = new Tab(t("billing.tab.invoices"), billingInvoicesTab(bundle.invoices()));
        invoicesTab.setGraphic(icon("fas-file-invoice"));

        Tab configTab = new Tab(t("billing.tab.config"), billingConfigTab(bundle.verifactuConfig(), bundle.series(), bundle.certificates(), bundle.invoiceTexts()));
        configTab.setGraphic(icon("fas-cog"));

        tabs.getTabs().addAll(dashboardTab, invoicesTab, configTab);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        // Aterrizamos en la pestaña que indique el intent (lo fija quien
        // navega aqui — p.ej. tras CRUD de series queremos volver a
        // Configuracion, no a Facturas). Por defecto: Facturas.
        Tab landingTab = switch (pendingBillingTab == null ? "" : pendingBillingTab) {
            case "config" -> configTab;
            case "dashboard" -> dashboardTab;
            default -> invoicesTab;
        };
        tabs.getSelectionModel().select(landingTab);
        pendingBillingTab = null;

        content.getChildren().addAll(header, tabs);
        return content;
    }

    // ----- Sub-tab Dashboard (placeholder F6) -----

    private Node billingDashboardTab(BillingBundle bundle) {
        Label section = label(t("billing.dash.section"), "settings-section-title");
        Label hint = new Label(t("billing.dash.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        long total = bundle.invoices().size();
        long drafts = bundle.invoices().stream().filter(i -> "DRAFT".equals(i.status())).count();
        long validated = bundle.invoices().stream().filter(i -> "VALIDATED".equals(i.status())).count();
        long pending = bundle.invoices().stream().filter(i -> "PENDING".equals(i.paymentStatus())).count();

        TilePane metrics = new TilePane();
        metrics.setHgap(12);
        metrics.setVgap(12);
        metrics.setPrefTileWidth(218);
        metrics.setPrefTileHeight(132);
        metrics.getChildren().addAll(
                metric(t("billing.dash.metric.total"), String.valueOf(total), t("billing.dash.metric.total.detail"), "fas-file-invoice", "module-blue"),
                metric(t("billing.dash.metric.drafts"), String.valueOf(drafts), t("billing.dash.metric.drafts.detail"), "fas-edit", "metric-amber"),
                metric(t("billing.dash.metric.validated"), String.valueOf(validated), t("billing.dash.metric.validated.detail"), "fas-check", "metric-green"),
                metric(t("billing.dash.metric.pending"), String.valueOf(pending), t("billing.dash.metric.pending.detail"), "fas-hourglass-half", "metric-rose")
        );

        VBox body = new VBox(16, section, hint, metrics);
        return tabLayout(label(t("billing.dash.tab_title"), "settings-section-title"), body, new HBox());
    }

    // ----- Sub-tab Facturas (listado con filtros) -----

    /**
     * Tab a seleccionar la proxima vez que se pinte billingView. Lo fijan
     * los flujos que vuelven a Facturacion despues de tocar algo de una
     * pestaña concreta (p.ej. crear una serie debe dejarte en
     * Configuracion). Se consume una sola vez.
     */
    private String pendingBillingTab;

    private ComboBox<String> billingStatusFilter;
    private ComboBox<String> billingPaymentFilter;
    private TableView<SalesInvoiceSummary> billingTable;

    private Node billingInvoicesTab(List<SalesInvoiceSummary> initialList) {
        billingStatusFilter = new ComboBox<>();
        billingStatusFilter.getItems().addAll(t("list.filter.all"), "DRAFT", "VALIDATED", "CANCELLED", "VOIDED");
        billingStatusFilter.getSelectionModel().selectFirst();
        billingStatusFilter.getStyleClass().add("form-input");

        billingPaymentFilter = new ComboBox<>();
        billingPaymentFilter.getItems().addAll(t("list.filter.all"), "PENDING", "PARTIAL", "PAID", "OVERDUE");
        billingPaymentFilter.getSelectionModel().selectFirst();
        billingPaymentFilter.getStyleClass().add("form-input");

        Button apply = new Button(t("list.filter.apply"));
        apply.setGraphic(icon("fas-filter"));
        apply.setOnAction(event -> reloadInvoices());

        Button reset = new Button(t("list.filter.reset"));
        reset.setGraphic(icon("fas-sync-alt"));
        reset.setOnAction(event -> {
            billingStatusFilter.getSelectionModel().selectFirst();
            billingPaymentFilter.getSelectionModel().selectFirst();
            reloadInvoices();
        });

        HBox filters = new HBox(10,
                label(t("list.filter.label.status"), "form-label"), billingStatusFilter,
                label(t("list.filter.label.collection"), "form-label"), billingPaymentFilter,
                apply, reset);
        filters.setAlignment(Pos.CENTER_LEFT);

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

        TableColumn<SalesInvoiceSummary, String> colDue = new TableColumn<>(t("list.column.due_date"));
        colDue.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().dueDate()));
        colDue.setPrefWidth(120);

        TableColumn<SalesInvoiceSummary, String> colStatus = new TableColumn<>(t("list.column.status"));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status()));
        colStatus.setPrefWidth(110);

        TableColumn<SalesInvoiceSummary, String> colPayment = new TableColumn<>(t("list.column.collection"));
        colPayment.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().paymentStatus()));
        colPayment.setPrefWidth(100);

        TableColumn<SalesInvoiceSummary, String> colTotal = new TableColumn<>(t("list.column.total"));
        colTotal.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().total() == null ? "" : money(c.getValue().total().toPlainString())));
        colTotal.setPrefWidth(110);

        billingTable.getColumns().addAll(List.of(colNumber, colCustomer, colDate, colDue, colStatus, colPayment, colTotal));
        billingTable.setItems(FXCollections.observableArrayList(initialList));
        // Doble click sobre fila DRAFT -> abrir editor. Sobre VALIDATED
        // tambien podriamos abrir un view-only; por ahora solo DRAFT.
        billingTable.setRowFactory(tv -> {
            javafx.scene.control.TableRow<SalesInvoiceSummary> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    SalesInvoiceSummary inv = row.getItem();
                    if ("DRAFT".equals(inv.status())) {
                        showInvoiceEditor(inv.id());
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
            if (sel != null) validateInvoiceFromList(sel);
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

        Button pdfBtn = new Button(t("list.action.generate_pdf"));
        pdfBtn.setGraphic(icon("fas-file-pdf"));
        pdfBtn.setDisable(true);
        pdfBtn.setOnAction(ev -> {
            Alert info = new Alert(Alert.AlertType.INFORMATION,
                    t("list.dialog.pdf.body"),
                    ButtonType.OK);
            info.setHeaderText(t("list.dialog.pdf.title"));
            info.showAndWait();
        });

        // Wire up de habilitacion segun la fila seleccionada.
        billingTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            boolean isDraft = newV != null && "DRAFT".equals(newV.status());
            boolean isValidated = newV != null && "VALIDATED".equals(newV.status());
            validateRowBtn.setDisable(!isDraft);
            deleteDraftBtn.setDisable(!isDraft);
            voidBtn.setDisable(!isValidated);
            pdfBtn.setDisable(!isValidated);
        });

        Region rowActionsSpacer = new Region();
        HBox.setHgrow(rowActionsSpacer, Priority.ALWAYS);
        HBox rowActions = new HBox(10, validateRowBtn, deleteDraftBtn, voidBtn, rowActionsSpacer, pdfBtn);
        rowActions.getStyleClass().add("settings-actions");

        VBox bottomBlock = new VBox(12, billingTable, rowActions);

        return tabLayout(topBlock, bottomBlock, new HBox());
    }

    private void validateInvoiceFromList(SalesInvoiceSummary sel) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("list.dialog.validate.body"),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("list.dialog.validate.title"));
        Optional<ButtonType> ans = confirm.showAndWait();
        if (ans.isEmpty() || ans.get() != ButtonType.OK) return;

        Task<SalesInvoiceSummary> task = new Task<>() {
            @Override
            protected SalesInvoiceSummary call() throws Exception {
                return billingApiClient.validateInvoice(sel.id());
            }
        };
        task.setOnSucceeded(ev -> {
            SalesInvoiceSummary v = task.getValue();
            Alert ok = new Alert(Alert.AlertType.INFORMATION,
                    t("list.dialog.validate.success_prefix") + v.invoiceNumber(), ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
            showBilling();
        });
        task.setOnFailed(ev -> showError(t("editor.error.validate_failed.title"),
                t("list.dialog.validate.failure_body")));
        start(task, "billing-invoice-validate-from-list");
    }

    private void voidInvoiceFromList(SalesInvoiceSummary sel) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("list.dialog.void.body"),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("list.dialog.void.title"));
        Optional<ButtonType> ans = confirm.showAndWait();
        if (ans.isEmpty() || ans.get() != ButtonType.OK) return;

        Task<SalesInvoiceSummary> task = new Task<>() {
            @Override
            protected SalesInvoiceSummary call() throws Exception {
                return billingApiClient.voidInvoice(sel.id());
            }
        };
        task.setOnSucceeded(ev -> {
            SalesInvoiceSummary draft = task.getValue();
            Alert ok = new Alert(Alert.AlertType.INFORMATION,
                    t("list.dialog.void.success_prefix") + shortId(draft.id())
                            + t("list.dialog.void.success_suffix"),
                    ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
            showBilling();
        });
        task.setOnFailed(ev -> showError(t("list.dialog.void.failure.title"),
                t("list.dialog.void.failure.body")));
        start(task, "billing-invoice-void");
    }

    private void deleteDraftFromList(SalesInvoiceSummary sel) {
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
        task.setOnSucceeded(ev -> showBilling());
        task.setOnFailed(ev -> showError(t("list.dialog.delete.failure_title"),
                t("list.dialog.delete.failure_body")));
        start(task, "billing-invoice-delete-from-list");
    }

    private void reloadInvoices() {
        String status = mapAllOrValue(billingStatusFilter.getValue());
        String payment = mapAllOrValue(billingPaymentFilter.getValue());
        Task<List<SalesInvoiceSummary>> task = new Task<>() {
            @Override
            protected List<SalesInvoiceSummary> call() throws Exception {
                return billingApiClient.listInvoices(status, payment, null, 200);
            }
        };
        task.setOnSucceeded(event -> billingTable.setItems(FXCollections.observableArrayList(task.getValue())));
        task.setOnFailed(event -> showError(t("list.dialog.reload_failed.title"), t("list.dialog.reload_failed.body")));
        start(task, "billing-invoices-reload");
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

    // ----- Sub-tab Configuracion (modo VeriFactu + cert + pie + series) -----

    private ComboBox<String> verifactuModeCombo;
    private ComboBox<CertificateOption> verifactuCertCombo;
    private TextField verifactuFooterField;
    private ComboBox<SeriesEntry> migrationSeriesCombo;
    private TextField migrationNextNumberField;
    private CheckBox migrationAcknowledgeCheck;
    private javafx.scene.control.TextArea textPieArea;
    private javafx.scene.control.TextArea textExemptArea;
    private javafx.scene.control.TextArea textReverseChargeArea;
    private javafx.scene.control.TextArea textReducedVatArea;
    private javafx.scene.control.TextArea textRectifyingArea;
    private javafx.scene.control.TextArea textLegalTermsArea;
    private CheckBox showIbanCheck;

    private Node billingConfigTab(VerifactuConfig config, List<SeriesEntry> series, List<CertificateOption> certificates, InvoiceTexts texts) {
        Label section = label(t("billing.config.verifactu.section"), "settings-section-title");
        Label hint = new Label(t("billing.config.verifactu.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        verifactuModeCombo = new ComboBox<>();
        verifactuModeCombo.getItems().addAll("OFF", "TEST", "PROD");
        verifactuModeCombo.getSelectionModel().select(config.mode() == null ? "OFF" : config.mode());
        verifactuModeCombo.getStyleClass().add("form-input");

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

        verifactuFooterField = textInput(config.invoiceFooterTemplate(), t("billing.config.verifactu.footer.prompt"));
        verifactuFooterField.setPrefColumnCount(60);

        GridPane grid = formGrid();
        addFormRow(grid, 0, t("billing.config.field.mode"), verifactuModeCombo);
        addFormRow(grid, 1, t("billing.config.field.cert"), verifactuCertCombo);
        addFormRow(grid, 2, t("billing.config.field.footer"), verifactuFooterField);

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
        sNext.setPrefWidth(140);
        TableColumn<SeriesEntry, String> sYear = new TableColumn<>(t("billing.config.series.col.year"));
        sYear.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().currentYear() == null ? "—" : String.valueOf(c.getValue().currentYear())));
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

        migrationNextNumberField = new TextField();
        migrationNextNumberField.setPromptText(t("billing.config.migration.next.prompt"));
        migrationNextNumberField.getStyleClass().add("form-input");

        migrationAcknowledgeCheck = new CheckBox(t("billing.config.migration.ack"));
        migrationAcknowledgeCheck.setWrapText(true);

        Button applyMigration = new Button(t("billing.config.migration.apply"));
        applyMigration.setGraphic(icon("fas-file-import"));
        applyMigration.setOnAction(event -> applyMigration());

        GridPane migrationGrid = formGrid();
        addFormRow(migrationGrid, 0, t("billing.config.migration.field.series"), migrationSeriesCombo);
        addFormRow(migrationGrid, 1, t("billing.config.migration.field.next"), migrationNextNumberField);

        VBox migrationBlock = new VBox(8,
                migrationHeader,
                migrationHint,
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

        Button saveTexts = new Button(t("billing.config.texts.save"));
        saveTexts.setGraphic(icon("fas-save"));
        saveTexts.setOnAction(event -> saveInvoiceTexts());

        VBox textsBlock = new VBox(8,
                textsHeader, textsHint,
                textsGrid, showIbanCheck,
                new HBox(saveTexts)
        );

        Button save = new Button(t("billing.config.verifactu.save"));
        save.setGraphic(icon("fas-save"));
        save.setOnAction(event -> saveVerifactuConfig());

        HBox actions = new HBox(save);
        actions.getStyleClass().add("settings-actions");

        VBox body = new VBox(16,
                section, hint, grid, certHint,
                new Separator(),
                seriesHeader, seriesHint, seriesTable, seriesActions,
                new Separator(),
                migrationBlock,
                new Separator(),
                textsBlock
        );
        return tabLayout(label(t("billing.config.tab_title"), "settings-section-title"), body, actions);
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
        SeriesEntry serie = migrationSeriesCombo.getValue();
        if (serie == null) {
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
        int nextNumber = next;
        Task<SeriesEntry> task = new Task<>() {
            @Override
            protected SeriesEntry call() throws Exception {
                return billingApiClient.migrateSeries(serie.id(), nextNumber, true);
            }
        };
        task.setOnSucceeded(event -> {
            Alert ok = new Alert(Alert.AlertType.INFORMATION,
                    t("billing.config.migration.success_prefix") + serie.code()
                            + t("billing.config.migration.success.middle") + nextNumber + ".",
                    ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
            pendingBillingTab = "config";
            showBilling();
        });
        task.setOnFailed(event -> showError(t("billing.config.migration.fail.title"),
                t("billing.config.migration.fail.body")));
        start(task, "billing-series-migrate");
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
        dialog.getDialogPane().setContent(dialogBody);

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
            pendingBillingTab = "config";
            showBilling();
        });
        task.setOnFailed(ev -> showError(
                existing == null ? t("billing.series.editor.fail.create.title") : t("billing.series.editor.fail.save.title"),
                t("billing.series.editor.fail.body")));
        start(task, "billing-series-save");
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

    // ===================================================================
    //  Pantalla crear/editar factura (Slice F4)
    //  - showInvoiceEditor(null)  => crear desde cero (DRAFT vacio).
    //  - showInvoiceEditor(id)    => cargar DRAFT existente y editar.
    //  El editor reusa form-grid, form-input, data-table del CSS de Pablo
    //  (regla: no inventar paletas).
    // ===================================================================

    private ComboBox<CustomerSummary> editorCustomerCombo;
    private javafx.scene.control.DatePicker editorInvoiceDate;
    private javafx.scene.control.DatePicker editorDueDate;
    private javafx.scene.control.TextArea editorNotesArea;
    private TableView<InvoiceLineDraft> editorLinesTable;
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

    private void showInvoiceEditor(String existingInvoiceId) {
        recordNav(() -> showInvoiceEditor(existingInvoiceId));
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
                setCenterAnimated(scroll(prerequisitePanel(
                        t("prereq.no_customers.title"),
                        t("prereq.no_customers.body"))));
                return;
            }
            if (existingInvoiceId == null && bundle.series().isEmpty()) {
                setCenterAnimated(scroll(prerequisitePanel(
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

        // ----- Header -----
        Button back = new Button(t("editor.back"));
        back.setGraphic(icon("fas-arrow-left"));
        back.setOnAction(event -> showBilling());

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
        SeriesEntry standardSeries = bundle.series().stream()
                .filter(s -> "STANDARD".equals(s.invoiceKind()))
                .findFirst()
                .orElse(null);

        editorInvoiceDate = new javafx.scene.control.DatePicker(
                bundle.existing() == null || bundle.existing().invoiceDate() == null || bundle.existing().invoiceDate().isBlank()
                        ? LocalDate.now()
                        : LocalDate.parse(bundle.existing().invoiceDate()));
        editorInvoiceDate.getStyleClass().add("invoice-input");
        editorInvoiceDate.setMaxWidth(Double.MAX_VALUE);

        editorDueDate = new javafx.scene.control.DatePicker(
                bundle.existing() == null || bundle.existing().dueDate() == null || bundle.existing().dueDate().isBlank()
                        ? LocalDate.now().plusDays(30)
                        : LocalDate.parse(bundle.existing().dueDate()));
        editorDueDate.getStyleClass().add("invoice-input");
        editorDueDate.setMaxWidth(Double.MAX_VALUE);

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
        editorCustomerCombo.valueProperty().addListener((obs, oldV, newV) -> refreshClientDetail.run());
        refreshClientDetail.run();

        // Pill grande con el tipo de factura. Si es una RECTIFYING (borrador
        // creado vía "Anular" desde el listado), mostramos un texto que
        // referencia la original; si no, "Factura normal · serie automatica".
        // La serie la resuelve el server segun el kind; el usuario no la
        // elige aqui.
        boolean isRectifying = bundle.existing() != null
                && "RECTIFYING".equals(bundle.existing().invoiceType());
        Label kindPill = label(
                isRectifying
                        ? t("editor.rectifying.pill_prefix") + shortId(bundle.existing().originalInvoiceId())
                        : t("editor.kind.pill"),
                "invoice-pill");

        // El badge del header refleja el proximo numero de la serie que
        // tocara al validar: STANDARD para facturas normales, RECT para
        // rectificativas.
        SeriesEntry badgeSeries = isRectifying
                ? bundle.series().stream()
                        .filter(s -> "RECTIFYING".equals(s.invoiceKind()))
                        .findFirst()
                        .orElse(standardSeries)
                : standardSeries;
        if (badgeSeries != null) {
            nextNumberBadgeValue.setText(previewNextNumber(badgeSeries));
        } else {
            nextNumberBadgeValue.setText("—");
        }

        VBox colCliente = new VBox(8,
                label(t("editor.field.customer"), "invoice-field-label"),
                editorCustomerCombo,
                clientDetail
        );
        VBox colFechas = new VBox(8,
                label(t("editor.field.invoice_date"), "invoice-field-label"),
                editorInvoiceDate,
                label(t("editor.field.due_date"), "invoice-field-label"),
                editorDueDate
        );
        VBox colTipo = new VBox(8,
                label(t("editor.field.kind"), "invoice-field-label"),
                kindPill
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
            editorLinesTable.getItems().add(new InvoiceLineDraft());
        }

        Button addLine = new Button(t("editor.line.add"));
        addLine.setGraphic(icon("fas-plus"));
        addLine.getStyleClass().add("invoice-primary-action");
        addLine.setOnAction(event -> {
            editorLinesTable.getItems().add(new InvoiceLineDraft());
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

        HBox lineasActions = new HBox(8, addLine, removeLine);
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
        cancel.setOnAction(event -> showBilling());

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

    /**
     * Reproduce el formato de numeracion de SeriesService (backend) sin
     * tener que llamar al endpoint: sustituye {CODE} por el codigo de la
     * serie, {YYYY} por el ano actual y {0000+} por el proximo numero
     * con padding. Asi podemos pintar en la UI el "F-2026-0043" que se
     * asignara cuando el usuario pulse "Validar y emitir", sin gastarlo.
     */
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
        colDesc.setPrefWidth(280);

        TableColumn<InvoiceLineDraft, String> colQty = decimalColumn(t("editor.lines.col.qty"), InvoiceLineDraft::getQuantity, InvoiceLineDraft::setQuantity);
        TableColumn<InvoiceLineDraft, String> colPrice = decimalColumn(t("editor.lines.col.price"), InvoiceLineDraft::getUnitPrice, InvoiceLineDraft::setUnitPrice);
        TableColumn<InvoiceLineDraft, String> colVat = decimalColumn(t("editor.lines.col.vat"), InvoiceLineDraft::getVatPercent, InvoiceLineDraft::setVatPercent);
        TableColumn<InvoiceLineDraft, String> colRet = decimalColumn(t("editor.lines.col.retention"), InvoiceLineDraft::getRetentionPercent, InvoiceLineDraft::setRetentionPercent);

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
            private final TextField field = new TextField();
            private boolean syncing = false;
            private InvoiceLineDraft boundRow;
            {
                field.setMaxWidth(Double.MAX_VALUE);
                field.getStyleClass().add("invoice-line-input");
                field.textProperty().addListener((obs, oldV, newV) -> {
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

    private void persistDraft(String existingId, boolean validateAfter) {
        CustomerSummary customer = editorCustomerCombo.getValue();
        if (customer == null) {
            showError(t("editor.error.no_customer.title"), t("editor.error.no_customer.body"));
            return;
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
        String dueDateIso = editorDueDate.getValue() == null ? null : editorDueDate.getValue().toString();
        String notes = editorNotesArea.getText();
        List<InvoiceLineDraft> lines = new java.util.ArrayList<>(editorLinesTable.getItems());

        // No mandamos seriesId: el server lo resuelve por invoice_type.
        // Mantenemos la firma del cliente API (seriesId nullable) por
        // si en algun flujo futuro queremos forzarlo explicitamente.
        Task<SalesInvoiceSummary> task = new Task<>() {
            @Override
            protected SalesInvoiceSummary call() throws Exception {
                SalesInvoiceSummary saved;
                if (existingId == null) {
                    saved = billingApiClient.createInvoice(customer.id(), null, "NORMAL",
                            invoiceDateIso, dueDateIso, notes, lines);
                } else {
                    saved = billingApiClient.updateInvoice(existingId, customer.id(), null, "NORMAL",
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
            showBilling();
        });
        task.setOnFailed(event -> showError(
                validateAfter ? t("editor.error.validate_failed.title") : t("editor.error.save_failed.title"),
                t("editor.error.save_failed.body")));
        start(task, "billing-invoice-save" + (validateAfter ? "-validate" : ""));
    }

    private void saveVerifactuConfig() {
        String mode = verifactuModeCombo.getValue();
        CertificateOption cert = verifactuCertCombo.getValue();
        String certId = cert == null ? null : cert.id();
        String footer = verifactuFooterField.getText();

        Task<VerifactuConfig> task = new Task<>() {
            @Override
            protected VerifactuConfig call() throws Exception {
                return billingApiClient.updateVerifactuConfig(mode, certId, footer);
            }
        };
        task.setOnSucceeded(event -> {
            Alert ok = new Alert(Alert.AlertType.INFORMATION,
                    t("billing.verifactu.save.success_prefix") + mode + t("billing.verifactu.save.success_suffix"),
                    ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
        });
        task.setOnFailed(event -> showError(t("billing.verifactu.save.fail.title"),
                t("billing.verifactu.save.fail.body")));
        start(task, "billing-config-save");
    }

    /**
     * Patron compartido por los 4 tabs de Configuracion: cabecera arriba,
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
        Button retry = new Button(t("common.btn.retry"));
        retry.setGraphic(icon("fas-sync-alt"));
        retry.setOnAction(event -> showDashboard());
        panel.getChildren().addAll(title, retry);
        return panel;
    }

    /**
     * Panel "blocker" que se muestra cuando falta una precondicion (ej.
     * no hay clientes, no hay series). Mensaje accionable + boton para
     * volver a Facturacion.
     */
    private VBox prerequisitePanel(String title, String details) {
        VBox panel = content();
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("module-detail-title");
        Label detailsLabel = new Label(details);
        detailsLabel.setWrapText(true);
        detailsLabel.getStyleClass().add("module-detail-description");
        Button back = new Button(t("common.btn.back_to_billing"));
        back.setGraphic(icon("fas-arrow-left"));
        back.setOnAction(event -> showBilling());
        VBox card = new VBox(12, titleLabel, detailsLabel, back);
        card.getStyleClass().add("module-detail-header");
        panel.getChildren().add(card);
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
                case "module.settings" -> "Settings";
                case "module.advisory.customers" -> "Client portfolio";
                case "module.advisory.billing" -> "Client billing";
                case "module.advisory.issuers" -> "Client issuers";
                case "module.advisory.purchases" -> "Reviewed purchases";
                case "module.advisory.labor" -> "Client labor";
                case "module.advisory.tax" -> "Client tax";
                case "module.advisory.reports" -> "Advisory reports";
                case "module.advisory.calendar" -> "Advisory calendar";
                case "module.advisory.settings" -> "Settings";
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
                // ---- Editor de facturas (Slice F4) ----
                case "editor.back" -> "Back to list";
                case "editor.title.new" -> "New invoice";
                case "editor.title.edit" -> "Edit draft";
                case "editor.subtitle.new" -> "The number is assigned automatically when validating. Saving the draft does NOT consume it.";
                case "editor.subtitle.edit" -> "You are editing a draft. Validating it numbers and locks it.";
                case "editor.next_number.caption" -> "NEXT Nº WHEN VALIDATED";
                case "editor.notes.prompt" -> "Internal notes or observations to appear on the invoice.";
                case "editor.client.detail_title" -> "Billing details";
                case "editor.client.tax_id_prefix" -> "Tax ID: ";
                case "editor.client.email_prefix" -> "Email: ";
                case "editor.client.phone_prefix" -> "Phone: ";
                case "editor.kind.pill" -> "Standard invoice · automatic series";
                case "editor.field.customer" -> "Customer *";
                case "editor.field.invoice_date" -> "Issue date *";
                case "editor.field.due_date" -> "Due date";
                case "editor.field.kind" -> "Type";
                case "editor.card.header" -> "Invoice header";
                case "editor.line.add" -> "Add line";
                case "editor.line.remove" -> "Remove line";
                case "editor.card.lines" -> "Items / Lines";
                case "editor.total.subtotal" -> "Net amount";
                case "editor.total.vat" -> "Total VAT";
                case "editor.total.retention" -> "IRPF withholding";
                case "editor.total.total" -> "INVOICE TOTAL";
                case "editor.notes.label" -> "Notes / Observations";
                case "editor.card.totals" -> "Totals and observations";
                case "editor.action.cancel" -> "Cancel";
                case "editor.action.save_draft" -> "Save draft";
                case "editor.action.save_changes" -> "Save changes";
                case "editor.action.validate" -> "Validate and issue";
                case "editor.error.no_customer.title" -> "Missing customer";
                case "editor.error.no_customer.body" -> "Select a customer.";
                case "editor.error.no_lines.title" -> "No lines";
                case "editor.error.no_lines.body" -> "An invoice without lines cannot be saved.";
                case "editor.error.line_incomplete.title" -> "Incomplete line";
                case "editor.error.line_incomplete.body" -> "There is a line without description. Fill it in or remove it.";
                case "editor.error.save_failed.title" -> "Could not save";
                case "editor.error.validate_failed.title" -> "Could not validate";
                case "editor.error.save_failed.body" -> "Check the data. To validate, the series must be available and totals correct.";
                case "editor.saved.validated_prefix" -> "Invoice validated: ";
                case "editor.saved.draft" -> "Draft saved.";
                case "prereq.no_customers.title" -> "You need to create a customer before billing";
                case "prereq.no_customers.body" -> "Go to Customers and create at least one. Then come back and press 'New invoice'.";
                case "prereq.no_series.title" -> "You need to configure a numbering series";
                case "prereq.no_series.body" -> "Go to Billing > Configuration > Series and press 'Define my invoice series'. Suggestion: STANDARD type, BY_YEAR numbering, next nº 1.";
                case "prereq.editor_failed" -> "Could not open the editor: active customers or series missing.";
                case "prereq.invoice_load_failed" -> "Could not load the invoice.";
                // ---- Listado de facturas (F4) ----
                case "list.header" -> "Invoice list";
                case "list.hint" -> "Double click on a draft opens the editor. Selecting a row enables the actions in the bottom bar.";
                case "list.filter.label.status" -> "Status:";
                case "list.filter.label.collection" -> "Collection:";
                case "list.filter.all" -> "(all)";
                case "list.filter.apply" -> "Apply filters";
                case "list.filter.reset" -> "Clear";
                case "list.column.number" -> "Number";
                case "list.column.customer" -> "Customer";
                case "list.column.date" -> "Date";
                case "list.column.due_date" -> "Due date";
                case "list.column.status" -> "Status";
                case "list.column.collection" -> "Collection";
                case "list.column.total" -> "Total";
                case "list.placeholder.empty" -> "No invoices match the current filters.";
                case "list.draft_label" -> "(draft)";
                case "list.action.delete_draft" -> "Delete draft";
                case "list.action.generate_pdf" -> "Generate PDF";
                case "list.dialog.pdf.title" -> "Pending · slice F4b";
                case "list.dialog.pdf.body" -> "PDF generation lands in slice F4b (multi-page PDF with company data, logo, VAT-grouped totals, legal texts and footer). For now the invoice is registered and numbered; the PDF will appear then.";
                case "list.dialog.validate.title" -> "Validate and issue invoice";
                case "list.dialog.validate.body" -> "You are about to issue the invoice number for this draft. Once validated you cannot edit it (only issue a corrective).";
                case "list.dialog.validate.success_prefix" -> "Invoice validated with nº ";
                case "list.dialog.validate.failure_body" -> "Check that the invoice has a series and at least one line with description.";
                case "list.dialog.delete.title" -> "Delete draft";
                case "list.dialog.delete.body" -> "You are about to delete this draft. This cannot be undone.";
                case "list.dialog.delete.failure_title" -> "Could not delete";
                case "list.dialog.delete.failure_body" -> "Only drafts (DRAFT) can be deleted.";
                case "list.dialog.validated_no_edit" -> "Only drafts (DRAFT) can be edited. To correct a validated invoice, issue a corrective.";
                case "list.dialog.validated_no_edit.header" -> "Validated invoice";
                case "list.dialog.reload_failed.title" -> "Filter error";
                case "list.dialog.reload_failed.body" -> "Could not refresh the list.";
                // ---- Facturacion shell (F2/F3/F5) ----
                case "billing.shell.title" -> "Billing";
                case "billing.shell.subtitle" -> "Integral management and VeriFactu";
                case "billing.shell.new_invoice" -> "New invoice";
                case "billing.shell.load_failed" -> "Could not load Billing (is module billing active? role OWNER/ADMIN/ACCOUNTANT?)";
                case "billing.tab.dashboard" -> "Dashboard";
                case "billing.tab.invoices" -> "Invoices";
                case "billing.tab.config" -> "Configuration";
                case "billing.dash.section" -> "Quick summary";
                case "billing.dash.hint" -> "The full dashboard with KPIs (monthly billed, pending collection, next due date, VAT chart, etc.) lands in slice F6. Until then this summary shows minimal numbers.";
                case "billing.dash.tab_title" -> "Overview";
                case "billing.dash.metric.total" -> "Total invoices";
                case "billing.dash.metric.total.detail" -> "All statuses";
                case "billing.dash.metric.drafts" -> "Drafts";
                case "billing.dash.metric.drafts.detail" -> "Not validated yet";
                case "billing.dash.metric.validated" -> "Validated";
                case "billing.dash.metric.validated.detail" -> "Numbered and sealed";
                case "billing.dash.metric.pending" -> "Pending collection";
                case "billing.dash.metric.pending.detail" -> "Unpaid";
                // ---- Command Palette ----
                case "palette.title" -> "BENJAGEST · Commands";
                case "palette.search.prompt" -> "Search action... (Esc to close)";
                case "palette.action.home" -> "Home · dashboard";
                case "palette.action.customers" -> "Customers";
                case "palette.action.billing" -> "Billing";
                case "palette.action.new_invoice" -> "New invoice";
                case "palette.action.settings" -> "Settings";
                case "palette.action.calendar" -> "Calendar";
                case "palette.action.purchases" -> "Purchases";
                case "palette.action.tax" -> "Tax";
                case "palette.action.labor" -> "Labor";
                case "palette.action.reports" -> "Reports";
                // ---- Billing configuration (F5+) ----
                case "billing.config.tab_title" -> "Billing configuration";
                case "billing.config.verifactu.section" -> "VeriFactu";
                case "billing.config.verifactu.hint" -> "Enables sending invoices to AEAT. OFF by default. To use PROD you need a .p12 certificate uploaded in Documents > Certificates; TEST allows testing against AEAT pre-production environment.";
                case "billing.config.verifactu.cert.none" -> "(none)";
                case "billing.config.verifactu.footer.prompt" -> "Text that appears at the bottom of each invoice";
                case "billing.config.field.mode" -> "Mode *";
                case "billing.config.field.cert" -> "Certificate";
                case "billing.config.field.footer" -> "Invoice footer";
                case "billing.config.cert.hint.empty" -> "No certificates uploaded. Activate the Documents module and upload one via /api/certificates.";
                case "billing.config.cert.hint.count_prefix" -> " certificate(s) available.";
                case "billing.config.verifactu.save" -> "Save VeriFactu";
                case "billing.verifactu.save.success_prefix" -> "VeriFactu configuration saved (mode ";
                case "billing.verifactu.save.success_suffix" -> ").";
                case "billing.verifactu.save.fail.title" -> "Could not save";
                case "billing.verifactu.save.fail.body" -> "If you selected PROD remember to choose a .p12 certificate.";
                case "billing.config.series.section" -> "Numbering series";
                case "billing.config.series.hint" -> "You only define the series for your standard invoices. PROFORMA and RECTIFYING series are system-managed (RD 1619/2012 Art.13). Your STANDARD series auto-locks as soon as you issue the first validated invoice of the year (legal continuity — only unlocked at year-end).";
                case "billing.config.series.placeholder.empty" -> "No series. Press 'Define my invoice series' to create the STANDARD one.";
                case "billing.config.series.col.code" -> "Code";
                case "billing.config.series.col.kind" -> "Type";
                case "billing.config.series.col.format" -> "Format";
                case "billing.config.series.col.next" -> "Next number";
                case "billing.config.series.col.year" -> "Year";
                case "billing.config.series.kind.standard.label" -> "Standard invoice";
                case "billing.config.series.kind.system_suffix" -> " · system";
                case "billing.config.series.reserved.body" -> "This series is system-managed (Art.13 RD 1619/2012) and maintained automatically. It cannot be edited or deleted.";
                case "billing.config.series.reserved.header_prefix" -> "Reserved series · ";
                case "billing.config.series.btn.define" -> "Define my invoice series";
                case "billing.config.series.btn.edit" -> "Edit my series";
                case "billing.config.migration.section" -> "Migrate from another program";
                case "billing.config.migration.hint" -> "If your company already issued invoices with other software, indicate here the number to continue from. Once the first invoice is validated in BENJAGEST, the series code and format remain locked until year-end.";
                case "billing.config.migration.next.prompt" -> "E.g. 43 (if your last invoice was F-...-0042)";
                case "billing.config.migration.ack" -> "I confirm the number matches my previous bookkeeping and release BENJAGEST from any liability for series gaps.";
                case "billing.config.migration.apply" -> "Apply migration";
                case "billing.config.migration.field.series" -> "Series";
                case "billing.config.migration.field.next" -> "Next number";
                case "billing.config.migration.combo.suffix_prefix" -> " — next ";
                case "billing.config.migration.error.no_series.title" -> "Missing series";
                case "billing.config.migration.error.no_series.body" -> "Select the series whose counter you want to migrate.";
                case "billing.config.migration.error.no_ack.title" -> "Missing confirmation";
                case "billing.config.migration.error.no_ack.body" -> "You must confirm you take responsibility before applying the migration.";
                case "billing.config.migration.error.bad_number.title" -> "Invalid number";
                case "billing.config.migration.error.bad_number.body" -> "Enter an integer >= 1.";
                case "billing.config.migration.error.bad_number.body_low" -> "The next number must be >= 1.";
                case "billing.config.migration.success_prefix" -> "Series ";
                case "billing.config.migration.success.middle" -> " migrated. Next number: ";
                case "billing.config.migration.fail.title" -> "Could not migrate";
                case "billing.config.migration.fail.body" -> "Check the number and that the series remains active.";
                case "billing.config.texts.section" -> "Legal texts on invoice";
                case "billing.config.texts.hint" -> "They appear at the bottom of each issued invoice depending on the case. Empty = that section is not printed.";
                case "billing.config.texts.prompt.pie" -> "General footer (contact details, thanks, etc.)";
                case "billing.config.texts.prompt.exempt" -> "Text for VAT-exempt invoices (Art.20 VAT Law)";
                case "billing.config.texts.prompt.reverse" -> "Reverse charge (intracommunity services, Art.84 LIVA)";
                case "billing.config.texts.prompt.reduced" -> "Message when reduced VAT is applied (4%/10%)";
                case "billing.config.texts.prompt.rectifying" -> "Text for corrective invoices";
                case "billing.config.texts.prompt.legal_terms" -> "Legal terms (due date, default, jurisdiction)";
                case "billing.config.texts.show_iban" -> "Show company IBAN on the invoice";
                case "billing.config.texts.field.pie" -> "General footer";
                case "billing.config.texts.field.exempt" -> "VAT exemption";
                case "billing.config.texts.field.reverse" -> "Reverse charge";
                case "billing.config.texts.field.reduced" -> "Reduced VAT";
                case "billing.config.texts.field.rectifying" -> "Correctives";
                case "billing.config.texts.field.legal_terms" -> "Legal terms";
                case "billing.config.texts.save" -> "Save texts";
                case "billing.texts.save.success" -> "Legal texts saved.";
                case "billing.texts.save.fail.title" -> "Could not save the texts";
                case "billing.texts.save.fail.body" -> "Try again in a few seconds.";
                // ---- Series editor dialog ----
                case "billing.series.editor.title.create" -> "Define invoice series";
                case "billing.series.editor.title.edit" -> "Edit my invoice series";
                case "billing.series.editor.field.code" -> "Code *";
                case "billing.series.editor.code.prompt" -> "E.g. F2026, FRA, F";
                case "billing.series.editor.kind.fixed" -> "Standard invoice (STANDARD)";
                case "billing.series.editor.field.kind" -> "Type";
                case "billing.series.editor.field.numbering" -> "Numbering *";
                case "billing.series.editor.field.format" -> "Format";
                case "billing.series.editor.format.prompt" -> "Placeholders: {CODE}, {YYYY}, {0000}";
                case "billing.series.editor.field.next" -> "Next nº";
                case "billing.series.editor.next.hint.create" -> "Number the series will start with (usually 1; if you come from another program, use Migration).";
                case "billing.series.editor.next.hint.edit" -> "The counter cannot be changed from here. Use 'Migrate from another program' if you come from other software.";
                case "billing.series.editor.autolock.hint" -> "The series auto-locks for editing as soon as you issue the first validated invoice of the year (legal continuity). No checkbox: it is automatic.";
                case "billing.series.editor.btn.create" -> "Create";
                case "billing.series.editor.btn.save" -> "Save";
                case "billing.series.editor.error.no_code.title" -> "Missing code";
                case "billing.series.editor.error.no_code.body" -> "Set a unique code for the series (e.g. F2026).";
                case "billing.series.editor.error.bad_number.title" -> "Invalid number";
                case "billing.series.editor.error.bad_number.body" -> "The next number must be an integer >= 1.";
                case "billing.series.editor.fail.create.title" -> "Could not create series";
                case "billing.series.editor.fail.save.title" -> "Could not save";
                case "billing.series.editor.fail.body" -> "Check the code is not duplicated. If the series has validated invoices this year, code/format/type cannot change (legal continuity — use migration).";
                case "billing.series.delete.confirm_prefix" -> "You are about to delete series ";
                case "billing.series.delete.confirm_suffix" -> ". If it already has associated invoices, the backend will reject the operation. Continue?";
                case "billing.series.delete.fail.title" -> "Could not delete";
                case "billing.series.delete.fail.body" -> "The series may have issued invoices. In that case it can only be blocked (edit -> 'Series blocked').";
                // ---- Settings (C3) ----
                case "settings.shell.title" -> "Settings";
                case "settings.load_failed" -> "Could not load Settings (you need OWNER or ADMIN role)";
                case "settings.tab.company" -> "Company";
                case "settings.tab.email" -> "SMTP Email";
                case "settings.tab.modules" -> "Modules";
                case "settings.tab.audit" -> "Audit";
                case "settings.company.section_label" -> "Company";
                case "settings.company.section.general" -> "General data";
                case "settings.company.section.address" -> "Postal address";
                case "settings.company.section.billing" -> "Billing data";
                case "settings.company.section.billing.hint" -> "These details appear on every invoice you issue as a company.";
                case "settings.company.prompt.legal_name" -> "Legal name";
                case "settings.company.prompt.trade_name" -> "Trade name";
                case "settings.company.prompt.tax_id" -> "Tax ID";
                case "settings.company.prompt.email" -> "Contact email";
                case "settings.company.prompt.phone" -> "Phone";
                case "settings.company.prompt.website" -> "Website";
                case "settings.company.prompt.address_line" -> "Street, number, floor";
                case "settings.company.prompt.city" -> "City";
                case "settings.company.prompt.province" -> "Province";
                case "settings.company.prompt.postal_code" -> "Postal code";
                case "settings.company.prompt.country" -> "Country";
                case "settings.company.country.default" -> "Spain";
                case "settings.company.prompt.iban" -> "ES00 0000 0000 0000 0000 0000";
                case "settings.company.prompt.registry" -> "Commercial registry, volume, sheet...";
                case "settings.company.prompt.terms" -> "Legal terms that appear on the invoice";
                case "settings.company.prompt.footer" -> "Invoice footer";
                case "settings.company.field.legal_name" -> "Legal name *";
                case "settings.company.field.trade_name" -> "Trade name";
                case "settings.company.field.tax_id" -> "Tax ID";
                case "settings.company.field.email" -> "Email";
                case "settings.company.field.phone" -> "Phone";
                case "settings.company.field.website" -> "Website";
                case "settings.company.field.address" -> "Address";
                case "settings.company.field.city" -> "City";
                case "settings.company.field.province" -> "Province";
                case "settings.company.field.postal_code" -> "Postal code";
                case "settings.company.field.country" -> "Country";
                case "settings.company.field.iban" -> "IBAN";
                case "settings.company.field.registry" -> "Registry data";
                case "settings.company.field.terms" -> "Legal terms";
                case "settings.company.field.footer" -> "Invoice footer";
                case "settings.company.type_note_prefix" -> "Company type: ";
                case "settings.company.type_note_suffix" -> " (not editable from here)";
                case "settings.company.save" -> "Save changes";
                case "settings.company.error.missing_legal_name.title" -> "Missing data";
                case "settings.company.error.missing_legal_name.body" -> "Legal name is mandatory";
                case "settings.company.save.fail.title" -> "Could not save";
                case "settings.company.save.fail.body" -> "Check the data and try again.";
                case "settings.email.section" -> "SMTP server";
                case "settings.email.prompt.host" -> "smtp.your-server.com";
                case "settings.email.prompt.port" -> "587";
                case "settings.email.prompt.user" -> "user@domain";
                case "settings.email.prompt.password.saved" -> "(password saved - leave blank to keep)";
                case "settings.email.prompt.password" -> "password";
                case "settings.email.prompt.from_address" -> "invoices@your-domain";
                case "settings.email.prompt.from_name" -> "Name shown as sender";
                case "settings.email.prompt.reply_to" -> "replies@your-domain";
                case "settings.email.field.host" -> "SMTP server";
                case "settings.email.field.port" -> "Port";
                case "settings.email.field.user" -> "User";
                case "settings.email.field.password" -> "Password";
                case "settings.email.field.from_address" -> "From (sender)";
                case "settings.email.field.from_name" -> "Sender name";
                case "settings.email.field.reply_to" -> "Reply-To";
                case "settings.email.flag.tls" -> "TLS / STARTTLS enabled";
                case "settings.email.flag.auth" -> "The SMTP server requires authentication";
                case "settings.email.test.prompt" -> "recipient@domain (for test email)";
                case "settings.email.btn.save" -> "Save";
                case "settings.email.btn.test" -> "Send test email";
                case "settings.email.section.test" -> "Test configuration";
                case "settings.email.section.test.hint" -> "Send a test mail with the saved configuration to verify the credentials work.";
                case "settings.email.save.success" -> "SMTP configuration saved.";
                case "settings.email.save.fail.title" -> "Could not save";
                case "settings.email.save.fail.body" -> "Check the SMTP server data.";
                case "settings.email.test.missing.title" -> "Missing data";
                case "settings.email.test.missing.body" -> "Indicate a recipient email for the test.";
                case "settings.email.test.success_prefix" -> "Test email sent to ";
                case "settings.email.test.success_suffix" -> ".";
                case "settings.email.test.fail.title" -> "Send failed";
                case "settings.email.test.fail.body" -> "Check host/port/user/password and try again.";
                case "settings.modules.section" -> "Active modules per company";
                case "settings.modules.hint" -> "Check or uncheck each module and press Save changes. Each module is all-or-nothing: if you enable Billing the whole block (series, invoices, collections, recurring) comes in; if you disable it, it all goes out.";
                case "settings.audit.section" -> "Recent events";
                case "settings.audit.hint" -> "Who did what and when. Useful to investigate suspicious accesses or configuration changes. Up to 200 entries are shown, ordered by most recent.";
                case "settings.audit.placeholder.empty" -> "No events recorded yet.";
                case "settings.audit.col.when" -> "When";
                case "settings.audit.col.type" -> "Type";
                case "settings.audit.col.result" -> "Result";
                case "settings.audit.col.user" -> "User";
                case "settings.audit.col.entity" -> "Entity";
                case "settings.audit.col.ip" -> "IP";
                case "settings.audit.col.details" -> "Detail";
                case "settings.audit.btn.refresh" -> "Refresh";
                case "settings.audit.filter.label" -> "Filter by type:";
                case "settings.audit.load.fail" -> "Could not load events.";
                // ---- Common dialog/panel actions ----
                case "common.btn.retry" -> "Retry";
                case "common.btn.back_to_billing" -> "Back to Billing";
                // ---- Editor lines table columns ----
                case "editor.lines.col.description" -> "Description";
                case "editor.lines.col.qty" -> "Qty.";
                case "editor.lines.col.price" -> "Price";
                case "editor.lines.col.vat" -> "VAT %";
                case "editor.lines.col.retention" -> "Withh. %";
                case "editor.lines.col.subtotal" -> "Subtotal";
                case "editor.lines.col.total" -> "Total";
                // ---- Calendar / Agenda ----
                case "calendar.events_count_zero" -> "0 events";
                case "calendar.events_count_one" -> "1 event";
                case "calendar.events_count_many" -> " events";
                case "calendar.mode.day" -> "Day";
                case "calendar.mode.week" -> "Week";
                case "calendar.mode.month" -> "Month";
                case "calendar.mode.year" -> "Year";
                case "calendar.btn.new_event" -> "New event";
                case "calendar.day.empty" -> "No events for this day. You can create one from New event.";
                case "calendar.day.scheduled_one" -> "1 event scheduled";
                case "calendar.day.scheduled_many_suffix" -> " events scheduled";
                case "calendar.week.range_prefix" -> "Week of ";
                case "calendar.week.range_middle" -> " to ";
                case "calendar.week.no_events" -> "No events";
                case "calendar.week.more_prefix" -> "+";
                case "calendar.week.more_suffix" -> " more";
                case "calendar.year.title_prefix" -> "Yearly view ";
                case "calendar.event.default_title" -> "Event";
                case "calendar.event.no_detail" -> "No detail";
                case "calendar.event.default_type" -> "GENERAL";
                case "calendar.day_agenda.title" -> "Day agenda";
                case "calendar.day_agenda.no_events" -> "No events for today.";
                case "calendar.dialog.title" -> "Agenda";
                case "calendar.dialog.empty.title" -> "Free day";
                case "calendar.dialog.empty.body" -> "Nothing scheduled. You can create an appointment, deadline or reminder for this day.";
                case "calendar.dialog.empty.btn" -> "Create event";
                case "calendar.dialog.planned_one" -> "1 planned event";
                case "calendar.dialog.planned_many_suffix" -> " planned events";
                case "calendar.dialog.month.no_events" -> "No events this month.";
                case "calendar.weekday.mon" -> "M";
                case "calendar.weekday.tue" -> "T";
                case "calendar.weekday.wed" -> "W";
                case "calendar.weekday.thu" -> "T";
                case "calendar.weekday.fri" -> "F";
                case "calendar.weekday.sat" -> "S";
                case "calendar.weekday.sun" -> "S";
                case "common.btn.edit" -> "Edit";
                case "common.btn.delete" -> "Delete";
                // ---- Generic module views ----
                case "module.records_count_suffix" -> " records";
                case "module.section.summary" -> "Summary";
                case "module.section.summary_total" -> "Total records";
                case "module.section.activity_by_date" -> "Activity by date";
                case "module.section.payment_status" -> "Payment status";
                case "module.section.status" -> "Status";
                case "module.section.models_by_period" -> "Models by period";
                case "module.empty.no_data_loaded" -> "No data loaded for this module.";
                case "module.empty.no_data" -> "No data";
                case "module.unit.expenses" -> "expenses";
                case "module.unit.tax_models" -> "tax models";
                case "module.unit.invoices" -> "invoices";
                case "module.unit.customers" -> "customers";
                case "module.unit.events" -> "events";
                case "module.unit.records" -> "records";
                case "module.unit.active_customers" -> "active customers";
                case "module.unit.work_logs" -> "work logs";
                case "module.unit.alerts" -> "alerts";
                case "module.unit.users_employees" -> "users/employees";
                case "module.section.team_by_type" -> "Team by type";
                case "module.section.main_distribution" -> "Main distribution";
                case "module.section.collection_status" -> "Collection status";
                case "module.section.pin_access" -> "PIN access";
                case "module.section.contacts" -> "Contacts";
                case "module.summary.no_field" -> "No data";
                case "module.summary.ready_to_review" -> "Data ready to review.";
                // ---- Void (anulación con vínculo) ----
                case "list.action.void" -> "Void";
                case "list.dialog.void.title" -> "Void validated invoice";
                case "list.dialog.void.body" -> "A linked corrective DRAFT will be created (lines negated). The original invoice will remain VALIDATED until you validate the corrective. Continue?";
                case "list.dialog.void.success_prefix" -> "Corrective draft created with id ";
                case "list.dialog.void.success_suffix" -> ". Open it to review and validate.";
                case "list.dialog.void.failure.title" -> "Could not void";
                case "list.dialog.void.failure.body" -> "Check that the invoice is VALIDATED and does not already have a linked corrective.";
                case "editor.rectifying.pill_prefix" -> "Corrective for ";
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
            case "module.settings" -> "Configuracion";
            case "module.advisory.customers" -> "Cartera clientes";
            case "module.advisory.billing" -> "Facturacion clientes";
            case "module.advisory.issuers" -> "Emisores clientes";
            case "module.advisory.purchases" -> "Compras revisadas";
            case "module.advisory.labor" -> "Laboral clientes";
            case "module.advisory.tax" -> "Fiscal clientes";
            case "module.advisory.reports" -> "Informes asesoria";
            case "module.advisory.calendar" -> "Agenda asesoria";
            case "module.advisory.settings" -> "Configuracion";
            // ---- Editor de facturas (Slice F4) ----
            case "editor.back" -> "Volver al listado";
            case "editor.title.new" -> "Nueva factura";
            case "editor.title.edit" -> "Editar borrador";
            case "editor.subtitle.new" -> "El nº se asigna automaticamente al validar. Guardar borrador NO lo consume.";
            case "editor.subtitle.edit" -> "Estas editando un borrador. Validar lo numera y lo bloquea.";
            case "editor.next_number.caption" -> "PROXIMO Nº AL VALIDAR";
            case "editor.notes.prompt" -> "Notas internas u observaciones que apareceran en la factura.";
            case "editor.client.detail_title" -> "Datos de facturacion";
            case "editor.client.tax_id_prefix" -> "NIF: ";
            case "editor.client.email_prefix" -> "Email: ";
            case "editor.client.phone_prefix" -> "Tel.: ";
            case "editor.kind.pill" -> "Factura normal · serie automatica";
            case "editor.field.customer" -> "Cliente *";
            case "editor.field.invoice_date" -> "Fecha de emision *";
            case "editor.field.due_date" -> "Fecha de vencimiento";
            case "editor.field.kind" -> "Tipo";
            case "editor.card.header" -> "Cabecera de la factura";
            case "editor.line.add" -> "Anadir linea";
            case "editor.line.remove" -> "Quitar linea";
            case "editor.card.lines" -> "Conceptos / Lineas";
            case "editor.total.subtotal" -> "Base imponible";
            case "editor.total.vat" -> "Cuota IVA total";
            case "editor.total.retention" -> "Retencion IRPF";
            case "editor.total.total" -> "TOTAL FACTURA";
            case "editor.notes.label" -> "Notas / Observaciones";
            case "editor.card.totals" -> "Totales y observaciones";
            case "editor.action.cancel" -> "Cancelar";
            case "editor.action.save_draft" -> "Guardar borrador";
            case "editor.action.save_changes" -> "Guardar cambios";
            case "editor.action.validate" -> "Validar y emitir";
            case "editor.error.no_customer.title" -> "Falta cliente";
            case "editor.error.no_customer.body" -> "Selecciona un cliente.";
            case "editor.error.no_lines.title" -> "Sin lineas";
            case "editor.error.no_lines.body" -> "Una factura sin lineas no se puede guardar.";
            case "editor.error.line_incomplete.title" -> "Linea incompleta";
            case "editor.error.line_incomplete.body" -> "Hay una linea sin descripcion. Rellenala o quitala.";
            case "editor.error.save_failed.title" -> "No se pudo guardar";
            case "editor.error.validate_failed.title" -> "No se pudo validar";
            case "editor.error.save_failed.body" -> "Revisa los datos. Si validas, la serie debe estar disponible y los totales correctos.";
            case "editor.saved.validated_prefix" -> "Factura validada: ";
            case "editor.saved.draft" -> "Borrador guardado.";
            case "prereq.no_customers.title" -> "Necesitas crear un cliente antes de facturar";
            case "prereq.no_customers.body" -> "Ve a Clientes y da de alta al menos uno. Despues vuelve aqui y pulsa 'Nueva factura'.";
            case "prereq.no_series.title" -> "Necesitas configurar una serie de numeracion";
            case "prereq.no_series.body" -> "Ve a Facturacion > Configuracion > Series y pulsa 'Definir mi serie de facturas'. Sugerencia: tipo STANDARD, numeracion BY_YEAR, proximo nº 1.";
            case "prereq.editor_failed" -> "No se pudo abrir el editor: faltan clientes o series activos.";
            case "prereq.invoice_load_failed" -> "No se pudo cargar la factura.";
            // ---- Listado de facturas (F4) ----
            case "list.header" -> "Listado de facturas";
            case "list.hint" -> "Doble click sobre un borrador abre el editor. Seleccionando una fila se habilitan las acciones de la barra inferior.";
            case "list.filter.label.status" -> "Estado:";
            case "list.filter.label.collection" -> "Cobro:";
            case "list.filter.all" -> "(todos)";
            case "list.filter.apply" -> "Aplicar filtros";
            case "list.filter.reset" -> "Limpiar";
            case "list.column.number" -> "Numero";
            case "list.column.customer" -> "Cliente";
            case "list.column.date" -> "Fecha";
            case "list.column.due_date" -> "Vencimiento";
            case "list.column.status" -> "Estado";
            case "list.column.collection" -> "Cobro";
            case "list.column.total" -> "Total";
            case "list.placeholder.empty" -> "Sin facturas para los filtros actuales.";
            case "list.draft_label" -> "(borrador)";
            case "list.action.delete_draft" -> "Eliminar borrador";
            case "list.action.generate_pdf" -> "Generar PDF";
            case "list.dialog.pdf.title" -> "Pendiente · slice F4b";
            case "list.dialog.pdf.body" -> "La generacion de PDF llega en el slice F4b (PDF multipagina con datos de la empresa, logo, totales agrupados por IVA, textos legales y pie). Por ahora la factura queda registrada y numerada; el PDF se vera entonces.";
            case "list.dialog.validate.title" -> "Validar y emitir factura";
            case "list.dialog.validate.body" -> "Vas a emitir el numero de factura para este borrador. Una vez validada no podras editarla (solo emitir rectificativa).";
            case "list.dialog.validate.success_prefix" -> "Factura validada con n.º ";
            case "list.dialog.validate.failure_body" -> "Revisa que la factura tenga serie y al menos una linea con descripcion.";
            case "list.dialog.delete.title" -> "Eliminar borrador";
            case "list.dialog.delete.body" -> "Vas a eliminar este borrador. No se puede deshacer.";
            case "list.dialog.delete.failure_title" -> "No se pudo eliminar";
            case "list.dialog.delete.failure_body" -> "Solo se pueden eliminar borradores (DRAFT).";
            case "list.dialog.validated_no_edit" -> "Solo se pueden editar facturas en borrador (DRAFT). Para corregir una factura validada hay que emitir rectificativa.";
            case "list.dialog.validated_no_edit.header" -> "Factura validada";
            case "list.dialog.reload_failed.title" -> "Error al filtrar";
            case "list.dialog.reload_failed.body" -> "No se pudo refrescar el listado.";
            // ---- Facturacion shell (F2/F3/F5) ----
            case "billing.shell.title" -> "Facturacion";
            case "billing.shell.subtitle" -> "Gestion integral y VeriFactu";
            case "billing.shell.new_invoice" -> "Nueva factura";
            case "billing.shell.load_failed" -> "No se pudo cargar Facturacion (modulo billing activo? rol OWNER/ADMIN/ACCOUNTANT?)";
            case "billing.tab.dashboard" -> "Dashboard";
            case "billing.tab.invoices" -> "Facturas";
            case "billing.tab.config" -> "Configuracion";
            case "billing.dash.section" -> "Resumen rapido";
            case "billing.dash.hint" -> "El dashboard completo con KPIs (facturado mes, pendiente cobro, proximo vencimiento, grafica IVA, etc.) llega en el slice F6. Hasta entonces este resumen se queda con cifras minimas.";
            case "billing.dash.tab_title" -> "Vista general";
            case "billing.dash.metric.total" -> "Total facturas";
            case "billing.dash.metric.total.detail" -> "Todos los estados";
            case "billing.dash.metric.drafts" -> "Borradores";
            case "billing.dash.metric.drafts.detail" -> "Sin validar todavia";
            case "billing.dash.metric.validated" -> "Validadas";
            case "billing.dash.metric.validated.detail" -> "Numeradas y selladas";
            case "billing.dash.metric.pending" -> "Pendientes cobro";
            case "billing.dash.metric.pending.detail" -> "Sin pagar";
            // ---- Command Palette ----
            case "palette.title" -> "BENJAGEST · Comandos";
            case "palette.search.prompt" -> "Buscar accion... (Esc para cerrar)";
            case "palette.action.home" -> "Inicio · dashboard";
            case "palette.action.customers" -> "Clientes";
            case "palette.action.billing" -> "Facturacion";
            case "palette.action.new_invoice" -> "Nueva factura";
            case "palette.action.settings" -> "Configuracion";
            case "palette.action.calendar" -> "Agenda";
            case "palette.action.purchases" -> "Compras";
            case "palette.action.tax" -> "Fiscal";
            case "palette.action.labor" -> "Laboral";
            case "palette.action.reports" -> "Informes";
            // ---- Billing configuration (F5+) ----
            case "billing.config.tab_title" -> "Configuracion de facturacion";
            case "billing.config.verifactu.section" -> "VeriFactu";
            case "billing.config.verifactu.hint" -> "Activa el envio de facturas a AEAT. Por defecto OFF. Para usar PROD necesitas un certificado .p12 subido en Documentos > Certificados; TEST permite hacer pruebas contra el entorno preproductivo de la AEAT.";
            case "billing.config.verifactu.cert.none" -> "(ninguno)";
            case "billing.config.verifactu.footer.prompt" -> "Texto que aparece al pie de cada factura";
            case "billing.config.field.mode" -> "Modo *";
            case "billing.config.field.cert" -> "Certificado";
            case "billing.config.field.footer" -> "Pie de factura";
            case "billing.config.cert.hint.empty" -> "No hay certificados subidos. Activa el modulo Documentos y sube uno en /api/certificates.";
            case "billing.config.cert.hint.count_prefix" -> " certificado(s) disponible(s).";
            case "billing.config.verifactu.save" -> "Guardar VeriFactu";
            case "billing.verifactu.save.success_prefix" -> "Configuracion VeriFactu guardada (modo ";
            case "billing.verifactu.save.success_suffix" -> ").";
            case "billing.verifactu.save.fail.title" -> "No se pudo guardar";
            case "billing.verifactu.save.fail.body" -> "Si seleccionaste PROD recuerda elegir un certificado .p12.";
            case "billing.config.series.section" -> "Series de numeracion";
            case "billing.config.series.hint" -> "Solo defines la serie de tus facturas normales. Las series para PROFORMA y RECTIFICATIVAS son del sistema (RD 1619/2012 Art.13). Tu serie STANDARD se autobloquea automaticamente en cuanto emites la primera factura validada del ano (continuidad legal — solo se desbloquea al cerrar el ano).";
            case "billing.config.series.placeholder.empty" -> "Sin series. Pulsa 'Definir mi serie de facturas' para crear la STANDARD.";
            case "billing.config.series.col.code" -> "Codigo";
            case "billing.config.series.col.kind" -> "Tipo";
            case "billing.config.series.col.format" -> "Formato";
            case "billing.config.series.col.next" -> "Proximo numero";
            case "billing.config.series.col.year" -> "Anio";
            case "billing.config.series.kind.standard.label" -> "Factura normal";
            case "billing.config.series.kind.system_suffix" -> " · sistema";
            case "billing.config.series.reserved.body" -> "Esta serie es del sistema (Art.13 RD 1619/2012) y se mantiene automaticamente. No se puede editar ni borrar.";
            case "billing.config.series.reserved.header_prefix" -> "Serie reservada · ";
            case "billing.config.series.btn.define" -> "Definir mi serie de facturas";
            case "billing.config.series.btn.edit" -> "Editar mi serie";
            case "billing.config.migration.section" -> "Migracion desde otro programa";
            case "billing.config.migration.hint" -> "Si tu empresa ya emitia facturas con otro software, indica aqui el numero por el que continuar. Una vez emitida la primera factura validada en BENJAGEST, el codigo y formato de la serie quedan bloqueados hasta cerrar el ano.";
            case "billing.config.migration.next.prompt" -> "Ej. 43 (si tu ultima factura fue F-...-0042)";
            case "billing.config.migration.ack" -> "Confirmo que el numero indicado coincide con mi contabilidad previa y eximo a BENJAGEST de cualquier responsabilidad por saltos en la serie.";
            case "billing.config.migration.apply" -> "Aplicar migracion";
            case "billing.config.migration.field.series" -> "Serie";
            case "billing.config.migration.field.next" -> "Proximo numero";
            case "billing.config.migration.combo.suffix_prefix" -> " — proximo ";
            case "billing.config.migration.error.no_series.title" -> "Falta serie";
            case "billing.config.migration.error.no_series.body" -> "Selecciona la serie cuyo correlativo quieres migrar.";
            case "billing.config.migration.error.no_ack.title" -> "Falta confirmacion";
            case "billing.config.migration.error.no_ack.body" -> "Debes confirmar que asumes la responsabilidad antes de aplicar la migracion.";
            case "billing.config.migration.error.bad_number.title" -> "Numero invalido";
            case "billing.config.migration.error.bad_number.body" -> "Indica un numero entero >= 1.";
            case "billing.config.migration.error.bad_number.body_low" -> "El proximo numero debe ser >= 1.";
            case "billing.config.migration.success_prefix" -> "Serie ";
            case "billing.config.migration.success.middle" -> " migrada. Proximo numero: ";
            case "billing.config.migration.fail.title" -> "No se pudo migrar";
            case "billing.config.migration.fail.body" -> "Comprueba el numero y que la serie sigue activa.";
            case "billing.config.texts.section" -> "Textos legales en la factura";
            case "billing.config.texts.hint" -> "Aparecen al pie de cada factura emitida segun el caso. Vacios = no se imprime esa seccion.";
            case "billing.config.texts.prompt.pie" -> "Pie general (datos de contacto, agradecimiento, etc.)";
            case "billing.config.texts.prompt.exempt" -> "Texto para facturas con IVA exento (art.20 Ley IVA)";
            case "billing.config.texts.prompt.reverse" -> "Sujeto pasivo (servicios intracomunitarios, art.84 LIVA)";
            case "billing.config.texts.prompt.reduced" -> "Mensaje cuando se aplica IVA reducido (4%/10%)";
            case "billing.config.texts.prompt.rectifying" -> "Texto para facturas rectificativas";
            case "billing.config.texts.prompt.legal_terms" -> "Terminos legales (vencimiento, mora, jurisdiccion)";
            case "billing.config.texts.show_iban" -> "Mostrar IBAN de la empresa en la factura";
            case "billing.config.texts.field.pie" -> "Pie general";
            case "billing.config.texts.field.exempt" -> "Exencion IVA";
            case "billing.config.texts.field.reverse" -> "Sujeto pasivo";
            case "billing.config.texts.field.reduced" -> "IVA reducido";
            case "billing.config.texts.field.rectifying" -> "Rectificativas";
            case "billing.config.texts.field.legal_terms" -> "Terminos legales";
            case "billing.config.texts.save" -> "Guardar textos";
            case "billing.texts.save.success" -> "Textos legales guardados.";
            case "billing.texts.save.fail.title" -> "No se pudieron guardar los textos";
            case "billing.texts.save.fail.body" -> "Vuelve a intentarlo en unos segundos.";
            // ---- Series editor dialog ----
            case "billing.series.editor.title.create" -> "Definir serie de facturas";
            case "billing.series.editor.title.edit" -> "Editar mi serie de facturas";
            case "billing.series.editor.field.code" -> "Codigo *";
            case "billing.series.editor.code.prompt" -> "Ej. F2026, FRA, F";
            case "billing.series.editor.kind.fixed" -> "Factura normal (STANDARD)";
            case "billing.series.editor.field.kind" -> "Tipo";
            case "billing.series.editor.field.numbering" -> "Numeracion *";
            case "billing.series.editor.field.format" -> "Formato";
            case "billing.series.editor.format.prompt" -> "Placeholders: {CODE}, {YYYY}, {0000}";
            case "billing.series.editor.field.next" -> "Proximo nº";
            case "billing.series.editor.next.hint.create" -> "Numero por el que arrancara la serie (normalmente 1; si vienes de otro programa, usa Migracion).";
            case "billing.series.editor.next.hint.edit" -> "El correlativo no se cambia desde aqui. Usa 'Migracion desde otro programa' si vienes de otro software.";
            case "billing.series.editor.autolock.hint" -> "La serie se autobloquea para edicion en cuanto emites la primera factura validada del ano (continuidad legal). No hay checkbox: es automatico.";
            case "billing.series.editor.btn.create" -> "Crear";
            case "billing.series.editor.btn.save" -> "Guardar";
            case "billing.series.editor.error.no_code.title" -> "Falta codigo";
            case "billing.series.editor.error.no_code.body" -> "Pon un codigo unico para la serie (ej. F2026).";
            case "billing.series.editor.error.bad_number.title" -> "Numero invalido";
            case "billing.series.editor.error.bad_number.body" -> "El proximo numero debe ser un entero >= 1.";
            case "billing.series.editor.fail.create.title" -> "No se pudo crear la serie";
            case "billing.series.editor.fail.save.title" -> "No se pudo guardar";
            case "billing.series.editor.fail.body" -> "Comprueba que el codigo no este duplicado. Si la serie tiene facturas validadas este ano no se puede cambiar codigo/formato/tipo (continuidad legal — usa migracion).";
            case "billing.series.delete.confirm_prefix" -> "Vas a eliminar la serie ";
            case "billing.series.delete.confirm_suffix" -> ". Si ya tiene facturas asociadas, el backend rechazara la operacion. ¿Continuar?";
            case "billing.series.delete.fail.title" -> "No se pudo eliminar";
            case "billing.series.delete.fail.body" -> "La serie puede tener facturas emitidas. En ese caso solo se puede bloquear (editar -> 'Serie bloqueada').";
            // ---- Settings (C3) ----
            case "settings.shell.title" -> "Configuracion";
            case "settings.load_failed" -> "No se pudo cargar Configuracion (necesitas rol OWNER o ADMIN)";
            case "settings.tab.company" -> "Empresa";
            case "settings.tab.email" -> "Email SMTP";
            case "settings.tab.modules" -> "Modulos";
            case "settings.tab.audit" -> "Auditoria";
            case "settings.company.section_label" -> "Empresa";
            case "settings.company.section.general" -> "Datos generales";
            case "settings.company.section.address" -> "Direccion postal";
            case "settings.company.section.billing" -> "Datos de facturacion";
            case "settings.company.section.billing.hint" -> "Estos datos aparecen en cada factura que emites como empresa.";
            case "settings.company.prompt.legal_name" -> "Razon social";
            case "settings.company.prompt.trade_name" -> "Nombre comercial";
            case "settings.company.prompt.tax_id" -> "NIF/CIF";
            case "settings.company.prompt.email" -> "Email de contacto";
            case "settings.company.prompt.phone" -> "Telefono";
            case "settings.company.prompt.website" -> "Web";
            case "settings.company.prompt.address_line" -> "Calle, numero, escalera";
            case "settings.company.prompt.city" -> "Localidad";
            case "settings.company.prompt.province" -> "Provincia";
            case "settings.company.prompt.postal_code" -> "CP";
            case "settings.company.prompt.country" -> "Pais";
            case "settings.company.country.default" -> "Espana";
            case "settings.company.prompt.iban" -> "ES00 0000 0000 0000 0000 0000";
            case "settings.company.prompt.registry" -> "Registro mercantil, tomo, hoja...";
            case "settings.company.prompt.terms" -> "Condiciones legales que aparecen en la factura";
            case "settings.company.prompt.footer" -> "Pie de factura";
            case "settings.company.field.legal_name" -> "Razon social *";
            case "settings.company.field.trade_name" -> "Nombre comercial";
            case "settings.company.field.tax_id" -> "NIF/CIF";
            case "settings.company.field.email" -> "Email";
            case "settings.company.field.phone" -> "Telefono";
            case "settings.company.field.website" -> "Web";
            case "settings.company.field.address" -> "Direccion";
            case "settings.company.field.city" -> "Localidad";
            case "settings.company.field.province" -> "Provincia";
            case "settings.company.field.postal_code" -> "CP";
            case "settings.company.field.country" -> "Pais";
            case "settings.company.field.iban" -> "IBAN";
            case "settings.company.field.registry" -> "Datos registrales";
            case "settings.company.field.terms" -> "Condiciones legales";
            case "settings.company.field.footer" -> "Pie de factura";
            case "settings.company.type_note_prefix" -> "Tipo de empresa: ";
            case "settings.company.type_note_suffix" -> " (no editable desde aqui)";
            case "settings.company.save" -> "Guardar cambios";
            case "settings.company.error.missing_legal_name.title" -> "Falta dato";
            case "settings.company.error.missing_legal_name.body" -> "La razon social es obligatoria";
            case "settings.company.save.fail.title" -> "No se pudo guardar";
            case "settings.company.save.fail.body" -> "Comprueba los datos y vuelve a intentarlo.";
            case "settings.email.section" -> "Servidor SMTP";
            case "settings.email.prompt.host" -> "smtp.tu-servidor.com";
            case "settings.email.prompt.port" -> "587";
            case "settings.email.prompt.user" -> "usuario@dominio";
            case "settings.email.prompt.password.saved" -> "(password guardada - deja vacio para no cambiar)";
            case "settings.email.prompt.password" -> "password";
            case "settings.email.prompt.from_address" -> "facturas@tu-dominio";
            case "settings.email.prompt.from_name" -> "Nombre que aparece como remitente";
            case "settings.email.prompt.reply_to" -> "respuestas@tu-dominio";
            case "settings.email.field.host" -> "Servidor SMTP";
            case "settings.email.field.port" -> "Puerto";
            case "settings.email.field.user" -> "Usuario";
            case "settings.email.field.password" -> "Password";
            case "settings.email.field.from_address" -> "From (remitente)";
            case "settings.email.field.from_name" -> "Nombre del remitente";
            case "settings.email.field.reply_to" -> "Reply-To";
            case "settings.email.flag.tls" -> "TLS / STARTTLS habilitado";
            case "settings.email.flag.auth" -> "El servidor SMTP requiere autenticacion";
            case "settings.email.test.prompt" -> "destinatario@dominio (para email de prueba)";
            case "settings.email.btn.save" -> "Guardar";
            case "settings.email.btn.test" -> "Enviar email de prueba";
            case "settings.email.section.test" -> "Probar configuracion";
            case "settings.email.section.test.hint" -> "Envia un correo de prueba con la configuracion guardada para verificar que las credenciales funcionan.";
            case "settings.email.save.success" -> "Configuracion SMTP guardada.";
            case "settings.email.save.fail.title" -> "No se pudo guardar";
            case "settings.email.save.fail.body" -> "Revisa los datos del servidor SMTP.";
            case "settings.email.test.missing.title" -> "Falta dato";
            case "settings.email.test.missing.body" -> "Indica un email destinatario para la prueba.";
            case "settings.email.test.success_prefix" -> "Email de prueba enviado a ";
            case "settings.email.test.success_suffix" -> ".";
            case "settings.email.test.fail.title" -> "Envio fallido";
            case "settings.email.test.fail.body" -> "Comprueba host/puerto/usuario/password y vuelve a intentarlo.";
            case "settings.modules.section" -> "Modulos activos por empresa";
            case "settings.modules.hint" -> "Marca o desmarca cada modulo y pulsa Guardar cambios. Cada modulo es todo-o-nada: si activas Facturacion entra el bloque completo (series, facturas, cobros, recurrentes); si lo desactivas, sale entero.";
            case "settings.audit.section" -> "Eventos recientes";
            case "settings.audit.hint" -> "Quien hizo que y cuando. Util para investigar accesos sospechosos o cambios de configuracion. Se muestran hasta 200 entradas, ordenadas por mas recientes.";
            case "settings.audit.placeholder.empty" -> "Sin eventos registrados todavia.";
            case "settings.audit.col.when" -> "Cuando";
            case "settings.audit.col.type" -> "Tipo";
            case "settings.audit.col.result" -> "Resultado";
            case "settings.audit.col.user" -> "Usuario";
            case "settings.audit.col.entity" -> "Entidad";
            case "settings.audit.col.ip" -> "IP";
            case "settings.audit.col.details" -> "Detalle";
            case "settings.audit.btn.refresh" -> "Refrescar";
            case "settings.audit.filter.label" -> "Filtrar por tipo:";
            case "settings.audit.load.fail" -> "No se pudieron cargar los eventos.";
            // ---- Common dialog/panel actions ----
            case "common.btn.retry" -> "Reintentar";
            case "common.btn.back_to_billing" -> "Volver a Facturacion";
            // ---- Editor lines table columns ----
            case "editor.lines.col.description" -> "Descripcion";
            case "editor.lines.col.qty" -> "Cant.";
            case "editor.lines.col.price" -> "Precio";
            case "editor.lines.col.vat" -> "IVA %";
            case "editor.lines.col.retention" -> "Ret. %";
            case "editor.lines.col.subtotal" -> "Subtotal";
            case "editor.lines.col.total" -> "Total";
            // ---- Calendar / Agenda ----
            case "calendar.events_count_zero" -> "0 eventos";
            case "calendar.events_count_one" -> "1 evento";
            case "calendar.events_count_many" -> " eventos";
            case "calendar.mode.day" -> "Día";
            case "calendar.mode.week" -> "Semana";
            case "calendar.mode.month" -> "Mes";
            case "calendar.mode.year" -> "Año";
            case "calendar.btn.new_event" -> "Nuevo evento";
            case "calendar.day.empty" -> "No hay eventos para este dia. Puedes crear uno desde Nuevo evento.";
            case "calendar.day.scheduled_one" -> "1 evento programado";
            case "calendar.day.scheduled_many_suffix" -> " eventos programados";
            case "calendar.week.range_prefix" -> "Semana del ";
            case "calendar.week.range_middle" -> " al ";
            case "calendar.week.no_events" -> "Sin eventos";
            case "calendar.week.more_prefix" -> "+";
            case "calendar.week.more_suffix" -> " mas";
            case "calendar.year.title_prefix" -> "Vista anual ";
            case "calendar.event.default_title" -> "Evento";
            case "calendar.event.no_detail" -> "Sin detalle";
            case "calendar.event.default_type" -> "GENERAL";
            case "calendar.day_agenda.title" -> "Agenda del dia";
            case "calendar.day_agenda.no_events" -> "No hay eventos para hoy.";
            case "calendar.dialog.title" -> "Agenda";
            case "calendar.dialog.empty.title" -> "Dia libre";
            case "calendar.dialog.empty.body" -> "No hay nada programado. Puedes crear una cita, vencimiento o recordatorio para este dia.";
            case "calendar.dialog.empty.btn" -> "Crear evento";
            case "calendar.dialog.planned_one" -> "1 evento planificado";
            case "calendar.dialog.planned_many_suffix" -> " eventos planificados";
            case "calendar.dialog.month.no_events" -> "No hay eventos en este mes.";
            case "calendar.weekday.mon" -> "L";
            case "calendar.weekday.tue" -> "M";
            case "calendar.weekday.wed" -> "X";
            case "calendar.weekday.thu" -> "J";
            case "calendar.weekday.fri" -> "V";
            case "calendar.weekday.sat" -> "S";
            case "calendar.weekday.sun" -> "D";
            case "common.btn.edit" -> "Editar";
            case "common.btn.delete" -> "Eliminar";
            // ---- Generic module views ----
            case "module.records_count_suffix" -> " registros";
            case "module.section.summary" -> "Resumen";
            case "module.section.summary_total" -> "Total registros";
            case "module.section.activity_by_date" -> "Actividad por fecha";
            case "module.section.payment_status" -> "Estado de pago";
            case "module.section.status" -> "Estado";
            case "module.section.models_by_period" -> "Modelos por periodo";
            case "module.empty.no_data_loaded" -> "Sin datos cargados para este modulo.";
            case "module.empty.no_data" -> "Sin datos";
            case "module.unit.expenses" -> "gastos";
            case "module.unit.tax_models" -> "modelos fiscales";
            case "module.unit.invoices" -> "facturas";
            case "module.unit.customers" -> "clientes";
            case "module.unit.events" -> "eventos";
            case "module.unit.records" -> "registros";
            case "module.unit.active_customers" -> "clientes activos";
            case "module.unit.work_logs" -> "partes de trabajo";
            case "module.unit.alerts" -> "avisos";
            case "module.unit.users_employees" -> "usuarios/empleados";
            case "module.section.team_by_type" -> "Equipo por tipo";
            case "module.section.main_distribution" -> "Distribucion principal";
            case "module.section.collection_status" -> "Estado de cobro";
            case "module.section.pin_access" -> "Acceso PIN";
            case "module.section.contacts" -> "Contactos";
            case "module.summary.no_field" -> "Sin dato";
            case "module.summary.ready_to_review" -> "Datos listos para revisar.";
            // ---- Void (anulación con vínculo) ----
            case "list.action.void" -> "Anular";
            case "list.dialog.void.title" -> "Anular factura validada";
            case "list.dialog.void.body" -> "Se creara un borrador RECTIFICATIVA enlazado (lineas con cantidad invertida). La original sigue VALIDATED hasta que valides la rectificativa. ¿Continuar?";
            case "list.dialog.void.success_prefix" -> "Borrador rectificativa creado con id ";
            case "list.dialog.void.success_suffix" -> ". Abrelo para revisar y validar.";
            case "list.dialog.void.failure.title" -> "No se pudo anular";
            case "list.dialog.void.failure.body" -> "Comprueba que la factura este VALIDATED y que aun no tenga rectificativa enlazada.";
            case "editor.rectifying.pill_prefix" -> "Rectificativa de ";
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
