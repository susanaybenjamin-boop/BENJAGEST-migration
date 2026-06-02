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
        all.add(new PaletteAction("Inicio · dashboard", "fas-home", this::showDashboard));
        all.add(new PaletteAction("Clientes", "fas-users", () -> showModule("customers")));
        all.add(new PaletteAction("Facturacion", "fas-file-invoice-dollar", () -> showModule("billing")));
        all.add(new PaletteAction("Nueva factura", "fas-plus", () -> showInvoiceEditor(null)));
        all.add(new PaletteAction("Configuracion", "fas-cog", () -> showModule("settings")));
        all.add(new PaletteAction("Agenda", "fas-calendar-alt", () -> showModule("calendar")));
        all.add(new PaletteAction("Compras", "fas-receipt", () -> showModule("purchases")));
        all.add(new PaletteAction("Fiscal", "fas-percentage", () -> showModule("tax")));
        all.add(new PaletteAction("Laboral", "fas-hard-hat", () -> showModule("labor")));
        all.add(new PaletteAction("Informes", "fas-chart-line", () -> showModule("reports")));
        return all;
    }

    private void showCommandPalette() {
        Dialog<PaletteAction> dialog = new Dialog<>();
        dialog.setTitle("BENJAGEST · Comandos");
        dialog.setHeaderText(null);
        dialog.initStyle(javafx.stage.StageStyle.UTILITY);

        TextField search = new TextField();
        search.setPromptText("Buscar accion... (Esc para cerrar)");
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
        Tab auditTab = new Tab("Auditoria", settingsAuditTab());
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
        TextField legalName = textInput(company.legalName(), "Razon social");
        TextField tradeName = textInput(company.tradeName(), "Nombre comercial");
        TextField taxId = textInput(company.taxIdentifier(), "NIF/CIF");
        TextField email = textInput(company.email(), "Email de contacto");
        TextField phone = textInput(company.phone(), "Telefono");
        TextField website = textInput(company.website(), "Web");

        TextField addressLine = textInput(company.addressLine(), "Calle, numero, escalera");
        TextField city = textInput(company.city(), "Localidad");
        TextField province = textInput(company.province(), "Provincia");
        TextField postalCode = textInput(company.postalCode(), "CP");
        TextField country = textInput(company.country() == null || company.country().isBlank() ? "Espana" : company.country(), "Pais");

        TextField iban = textInput(company.iban(), "ES00 0000 0000 0000 0000 0000");
        TextField registry = textInput(company.registryInformation(), "Registro mercantil, tomo, hoja...");
        TextField terms = textInput(company.legalTerms(), "Condiciones legales que aparecen en la factura");
        TextField footer = textInput(company.invoiceFooter(), "Pie de factura");

        GridPane generalGrid = formGrid();
        addFormRow(generalGrid, 0, "Razon social *", legalName);
        addFormRow(generalGrid, 1, "Nombre comercial", tradeName);
        addFormRow(generalGrid, 2, "NIF/CIF", taxId);
        addFormRow(generalGrid, 3, "Email", email);
        addFormRow(generalGrid, 4, "Telefono", phone);
        addFormRow(generalGrid, 5, "Web", website);

        GridPane addressGrid = formGrid();
        addFormRow(addressGrid, 0, "Direccion", addressLine);
        addFormRow(addressGrid, 1, "Localidad", city);
        addFormRow(addressGrid, 2, "Provincia", province);
        addFormRow(addressGrid, 3, "CP", postalCode);
        addFormRow(addressGrid, 4, "Pais", country);

        GridPane billingGrid = formGrid();
        addFormRow(billingGrid, 0, "IBAN", iban);
        addFormRow(billingGrid, 1, "Datos registrales", registry);
        addFormRow(billingGrid, 2, "Condiciones legales", terms);
        addFormRow(billingGrid, 3, "Pie de factura", footer);

        Label typeNote = new Label("Tipo de empresa: " + company.companyType()
                + " (no editable desde aqui)");
        typeNote.getStyleClass().add("settings-hint");

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
                label("Datos generales", "settings-section-title"),
                generalGrid,
                typeNote,
                new Separator(),
                label("Direccion postal", "settings-section-title"),
                addressGrid,
                new Separator(),
                label("Datos de facturacion", "settings-section-title"),
                label("Estos datos aparecen en cada factura que emites como empresa.", "settings-hint"),
                billingGrid
        );

        Label sectionTitle = label("Empresa", "settings-section-title");
        return tabLayout(sectionTitle, body, actions);
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
        Label sectionTitle = label("Eventos recientes", "settings-section-title");
        Label hint = new Label("Quien hizo que y cuando. Util para investigar accesos sospechosos o "
                + "cambios de configuracion. Se muestran hasta 200 entradas, ordenadas por mas recientes.");
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        javafx.scene.control.ComboBox<String> typeFilter = new javafx.scene.control.ComboBox<>();
        typeFilter.getItems().addAll(AUDIT_EVENT_TYPES);
        typeFilter.getSelectionModel().selectFirst();
        typeFilter.getStyleClass().add("form-input");

        TableView<AuditEvent> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Sin eventos registrados todavia."));

        TableColumn<AuditEvent, String> colWhen = new TableColumn<>("Cuando");
        colWhen.setCellValueFactory(c -> new SimpleStringProperty(shortIso(c.getValue().createdAt())));
        colWhen.setPrefWidth(160);
        TableColumn<AuditEvent, String> colType = new TableColumn<>("Tipo");
        colType.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().eventType()));
        colType.setPrefWidth(150);
        TableColumn<AuditEvent, String> colResult = new TableColumn<>("Resultado");
        colResult.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().result()));
        colResult.setPrefWidth(80);
        TableColumn<AuditEvent, String> colUser = new TableColumn<>("Usuario");
        colUser.setCellValueFactory(c -> new SimpleStringProperty(shortId(c.getValue().userId())));
        colUser.setPrefWidth(120);
        TableColumn<AuditEvent, String> colEntity = new TableColumn<>("Entidad");
        colEntity.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().entityType() == null ? "" : c.getValue().entityType() + ":" + shortId(c.getValue().entityId())
        ));
        colEntity.setPrefWidth(160);
        TableColumn<AuditEvent, String> colIp = new TableColumn<>("IP");
        colIp.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().ipAddress()));
        colIp.setPrefWidth(120);
        TableColumn<AuditEvent, String> colDetails = new TableColumn<>("Detalle");
        colDetails.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().details()));
        table.getColumns().addAll(List.of(colWhen, colType, colResult, colUser, colEntity, colIp, colDetails));

        Button refresh = new Button("Refrescar");
        refresh.setGraphic(icon("fas-sync-alt"));
        refresh.setOnAction(event -> loadAuditEvents(table, typeFilter.getValue()));
        typeFilter.setOnAction(event -> loadAuditEvents(table, typeFilter.getValue()));

        loadAuditEvents(table, typeFilter.getValue());

        HBox filterRow = new HBox(10, label("Filtrar por tipo:", "form-label"), typeFilter);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        HBox actions = new HBox(refresh);
        actions.getStyleClass().add("settings-actions");

        VBox header = new VBox(8, sectionTitle, hint, filterRow);
        return tabLayout(header, table, actions);
    }

    private void loadAuditEvents(TableView<AuditEvent> table, String selectedType) {
        String filter = selectedType == null || "(todos)".equals(selectedType) ? null : selectedType;
        Task<List<AuditEvent>> task = new Task<>() {
            @Override
            protected List<AuditEvent> call() throws Exception {
                return settingsApiClient.listAuditEvents(filter, null, 200);
            }
        };
        task.setOnSucceeded(event -> table.setItems(FXCollections.observableArrayList(task.getValue())));
        task.setOnFailed(event -> table.setPlaceholder(new Label("No se pudieron cargar los eventos.")));
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
                "No se pudo cargar Facturacion (modulo billing activo? rol OWNER/ADMIN/ACCOUNTANT?)"))));
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

        Label title = new Label("Facturacion");
        title.getStyleClass().add("module-detail-title");
        Label subtitle = new Label("Gestion integral y VeriFactu");
        subtitle.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, subtitle);

        StackPane moduleIcon = iconBubble("fas-file-invoice-dollar", "module-title-icon");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button newInvoice = new Button("Nueva factura");
        newInvoice.setGraphic(icon("fas-plus"));
        newInvoice.setOnAction(event -> showInvoiceEditor(null));

        HBox header = new HBox(16, titleBox, moduleIcon, spacer, newInvoice);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("settings-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab dashboardTab = new Tab("Dashboard", billingDashboardTab(bundle));
        dashboardTab.setGraphic(icon("fas-chart-bar"));

        Tab invoicesTab = new Tab("Facturas", billingInvoicesTab(bundle.invoices()));
        invoicesTab.setGraphic(icon("fas-file-invoice"));

        Tab configTab = new Tab("Configuracion", billingConfigTab(bundle.verifactuConfig(), bundle.series(), bundle.certificates(), bundle.invoiceTexts()));
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
        Label section = label("Resumen rapido", "settings-section-title");
        Label hint = new Label("El dashboard completo con KPIs (facturado mes, pendiente cobro, "
                + "proximo vencimiento, grafica IVA, etc.) llega en el slice F6. "
                + "Hasta entonces este resumen se queda con cifras minimas.");
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
                metric("Total facturas", String.valueOf(total), "Todos los estados", "fas-file-invoice", "module-blue"),
                metric("Borradores", String.valueOf(drafts), "Sin validar todavia", "fas-edit", "metric-amber"),
                metric("Validadas", String.valueOf(validated), "Numeradas y selladas", "fas-check", "metric-green"),
                metric("Pendientes cobro", String.valueOf(pending), "Sin pagar", "fas-hourglass-half", "metric-rose")
        );

        VBox body = new VBox(16, section, hint, metrics);
        return tabLayout(label("Vista general", "settings-section-title"), body, new HBox());
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
        billingStatusFilter.getItems().addAll("(todos)", "DRAFT", "VALIDATED", "CANCELLED", "VOIDED");
        billingStatusFilter.getSelectionModel().selectFirst();
        billingStatusFilter.getStyleClass().add("form-input");

        billingPaymentFilter = new ComboBox<>();
        billingPaymentFilter.getItems().addAll("(todos)", "PENDING", "PARTIAL", "PAID", "OVERDUE");
        billingPaymentFilter.getSelectionModel().selectFirst();
        billingPaymentFilter.getStyleClass().add("form-input");

        Button apply = new Button("Aplicar filtros");
        apply.setGraphic(icon("fas-filter"));
        apply.setOnAction(event -> reloadInvoices());

        Button reset = new Button("Limpiar");
        reset.setGraphic(icon("fas-sync-alt"));
        reset.setOnAction(event -> {
            billingStatusFilter.getSelectionModel().selectFirst();
            billingPaymentFilter.getSelectionModel().selectFirst();
            reloadInvoices();
        });

        HBox filters = new HBox(10,
                label("Estado:", "form-label"), billingStatusFilter,
                label("Cobro:", "form-label"), billingPaymentFilter,
                apply, reset);
        filters.setAlignment(Pos.CENTER_LEFT);

        billingTable = new TableView<>();
        billingTable.getStyleClass().add("data-table");
        billingTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        billingTable.setPlaceholder(new Label("Sin facturas para los filtros actuales."));

        TableColumn<SalesInvoiceSummary, String> colNumber = new TableColumn<>("Numero");
        colNumber.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().invoiceNumber() == null || c.getValue().invoiceNumber().isBlank()
                        ? "(borrador)"
                        : c.getValue().invoiceNumber()
        ));
        colNumber.setPrefWidth(160);

        TableColumn<SalesInvoiceSummary, String> colCustomer = new TableColumn<>("Cliente");
        colCustomer.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().customerLegalName()));
        colCustomer.setPrefWidth(220);

        TableColumn<SalesInvoiceSummary, String> colDate = new TableColumn<>("Fecha");
        colDate.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().invoiceDate()));
        colDate.setPrefWidth(110);

        TableColumn<SalesInvoiceSummary, String> colDue = new TableColumn<>("Vencimiento");
        colDue.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().dueDate()));
        colDue.setPrefWidth(120);

        TableColumn<SalesInvoiceSummary, String> colStatus = new TableColumn<>("Estado");
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status()));
        colStatus.setPrefWidth(110);

        TableColumn<SalesInvoiceSummary, String> colPayment = new TableColumn<>("Cobro");
        colPayment.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().paymentStatus()));
        colPayment.setPrefWidth(100);

        TableColumn<SalesInvoiceSummary, String> colTotal = new TableColumn<>("Total");
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
                                "Solo se pueden editar facturas en borrador (DRAFT). "
                                + "Para corregir una factura validada hay que emitir rectificativa.",
                                ButtonType.OK);
                        info.setHeaderText(null);
                        info.showAndWait();
                    }
                }
            });
            return row;
        });

        Label header = label("Listado de facturas", "settings-section-title");
        Label hint = new Label("Doble click sobre un borrador abre el editor. Seleccionando una fila "
                + "se habilitan las acciones de la barra inferior.");
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        VBox topBlock = new VBox(8, header, hint, filters);

        // Barra de acciones contextual: validar borrador, eliminar
        // borrador, generar PDF. Cada boton se autohabilita segun el
        // estado de la fila seleccionada (no tiene sentido validar una
        // VALIDATED ni borrar fisicamente algo que ya tiene numero
        // legal).
        Button validateRowBtn = new Button("Validar y emitir");
        validateRowBtn.setGraphic(icon("fas-check"));
        validateRowBtn.getStyleClass().add("invoice-validate-action");
        validateRowBtn.setDisable(true);
        validateRowBtn.setOnAction(ev -> {
            SalesInvoiceSummary sel = billingTable.getSelectionModel().getSelectedItem();
            if (sel != null) validateInvoiceFromList(sel);
        });

        Button deleteDraftBtn = new Button("Eliminar borrador");
        deleteDraftBtn.setGraphic(icon("fas-trash-alt"));
        deleteDraftBtn.setDisable(true);
        deleteDraftBtn.setOnAction(ev -> {
            SalesInvoiceSummary sel = billingTable.getSelectionModel().getSelectedItem();
            if (sel != null) deleteDraftFromList(sel);
        });

        Button pdfBtn = new Button("Generar PDF");
        pdfBtn.setGraphic(icon("fas-file-pdf"));
        pdfBtn.setDisable(true);
        pdfBtn.setOnAction(ev -> {
            Alert info = new Alert(Alert.AlertType.INFORMATION,
                    "La generacion de PDF llega en el slice F4b (PDF multipagina con datos de la empresa, "
                            + "logo, totales agrupados por IVA, textos legales y pie). "
                            + "Por ahora la factura queda registrada y numerada; el PDF se vera entonces.",
                    ButtonType.OK);
            info.setHeaderText("Pendiente · slice F4b");
            info.showAndWait();
        });

        // Wire up de habilitacion segun la fila seleccionada.
        billingTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            boolean isDraft = newV != null && "DRAFT".equals(newV.status());
            boolean isValidated = newV != null && "VALIDATED".equals(newV.status());
            validateRowBtn.setDisable(!isDraft);
            deleteDraftBtn.setDisable(!isDraft);
            pdfBtn.setDisable(!isValidated);
        });

        Region rowActionsSpacer = new Region();
        HBox.setHgrow(rowActionsSpacer, Priority.ALWAYS);
        HBox rowActions = new HBox(10, validateRowBtn, deleteDraftBtn, rowActionsSpacer, pdfBtn);
        rowActions.getStyleClass().add("settings-actions");

        VBox bottomBlock = new VBox(12, billingTable, rowActions);

        return tabLayout(topBlock, bottomBlock, new HBox());
    }

    private void validateInvoiceFromList(SalesInvoiceSummary sel) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Vas a emitir el numero de factura para este borrador. "
                        + "Una vez validada no podras editarla (solo emitir rectificativa).",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Validar y emitir factura");
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
                    "Factura validada con n.º " + v.invoiceNumber(), ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
            showBilling();
        });
        task.setOnFailed(ev -> showError("No se pudo validar",
                "Revisa que la factura tenga serie y al menos una linea con descripcion."));
        start(task, "billing-invoice-validate-from-list");
    }

    private void deleteDraftFromList(SalesInvoiceSummary sel) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Vas a eliminar este borrador. No se puede deshacer.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Eliminar borrador");
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
        task.setOnFailed(ev -> showError("No se pudo eliminar",
                "Solo se pueden eliminar borradores (DRAFT)."));
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
        task.setOnFailed(event -> showError("Error al filtrar", "No se pudo refrescar el listado."));
        start(task, "billing-invoices-reload");
    }

    private String mapAllOrValue(String selection) {
        return selection == null || "(todos)".equals(selection) ? null : selection;
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
        Label section = label("VeriFactu", "settings-section-title");
        Label hint = new Label("Activa el envio de facturas a AEAT. Por defecto OFF. "
                + "Para usar PROD necesitas un certificado .p12 subido en Documentos > Certificados; "
                + "TEST permite hacer pruebas contra el entorno preproductivo de la AEAT.");
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        verifactuModeCombo = new ComboBox<>();
        verifactuModeCombo.getItems().addAll("OFF", "TEST", "PROD");
        verifactuModeCombo.getSelectionModel().select(config.mode() == null ? "OFF" : config.mode());
        verifactuModeCombo.getStyleClass().add("form-input");

        verifactuCertCombo = new ComboBox<>();
        verifactuCertCombo.getItems().add(new CertificateOption(null, "(ninguno)", ""));
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

        verifactuFooterField = textInput(config.invoiceFooterTemplate(), "Texto que aparece al pie de cada factura");
        verifactuFooterField.setPrefColumnCount(60);

        GridPane grid = formGrid();
        addFormRow(grid, 0, "Modo *", verifactuModeCombo);
        addFormRow(grid, 1, "Certificado", verifactuCertCombo);
        addFormRow(grid, 2, "Pie de factura", verifactuFooterField);

        Label certHint = new Label(certificates.isEmpty()
                ? "No hay certificados subidos. Activa el modulo Documentos y sube uno en /api/certificates."
                : certificates.size() + " certificado(s) disponible(s).");
        certHint.getStyleClass().add("settings-hint");

        Label seriesHeader = label("Series de numeracion", "settings-section-title");
        Label seriesHint = new Label("Solo defines la serie de tus facturas normales (STANDARD). "
                + "Las series para PROFORMA y RECTIFICATIVAS son del sistema (RD 1619/2012 Art.13). "
                + "Tu serie STANDARD se autobloquea automaticamente en cuanto emites la primera "
                + "factura validada del ano (continuidad legal — solo se desbloquea al cerrar el ano).");
        seriesHint.setWrapText(true);
        seriesHint.getStyleClass().add("settings-hint");

        TableView<SeriesEntry> seriesTable = new TableView<>();
        seriesTable.getStyleClass().add("data-table");
        seriesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        seriesTable.setPlaceholder(new Label("Sin series. Pulsa 'Definir mi serie de facturas' para crear la STANDARD."));
        TableColumn<SeriesEntry, String> sCode = new TableColumn<>("Codigo");
        sCode.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().code()));
        sCode.setPrefWidth(120);
        TableColumn<SeriesEntry, String> sKind = new TableColumn<>("Tipo");
        sKind.setCellValueFactory(c -> new SimpleStringProperty(
                "STANDARD".equals(c.getValue().invoiceKind())
                        ? "Factura normal"
                        : c.getValue().invoiceKind() + " · sistema"));
        sKind.setPrefWidth(160);
        TableColumn<SeriesEntry, String> sFormat = new TableColumn<>("Formato");
        sFormat.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().formatTemplate()));
        sFormat.setPrefWidth(180);
        TableColumn<SeriesEntry, String> sNext = new TableColumn<>("Proximo numero");
        sNext.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().nextNumber())));
        sNext.setPrefWidth(140);
        TableColumn<SeriesEntry, String> sYear = new TableColumn<>("Anio");
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
                                "Esta serie es del sistema (Art.13 RD 1619/2012) y se mantiene "
                                        + "automaticamente. No se puede editar ni borrar.",
                                ButtonType.OK);
                        info.setHeaderText("Serie reservada · " + sel.invoiceKind());
                        info.showAndWait();
                    }
                }
            });
            return row;
        });

        Button newSeriesBtn = new Button(hasStandard ? "Editar mi serie" : "Definir mi serie de facturas");
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
        Label migrationHeader = label("Migracion desde otro programa", "settings-section-title");
        Label migrationHint = new Label("Si tu empresa ya emitia facturas con otro software, "
                + "indica aqui el numero por el que continuar. Una vez emitida la primera factura validada "
                + "en BENJAGEST, el codigo y formato de la serie quedan bloqueados hasta cerrar el ano.");
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
                setText(empty || item == null ? "" : item.code() + " — proximo " + item.nextNumber());
            }
        });
        migrationSeriesCombo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(SeriesEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.code() + " — proximo " + item.nextNumber());
            }
        });

        migrationNextNumberField = new TextField();
        migrationNextNumberField.setPromptText("Ej. 43 (si tu ultima factura fue F-...-0042)");
        migrationNextNumberField.getStyleClass().add("form-input");

        migrationAcknowledgeCheck = new CheckBox("Confirmo que el numero indicado coincide con mi contabilidad previa "
                + "y eximo a BENJAGEST de cualquier responsabilidad por saltos en la serie.");
        migrationAcknowledgeCheck.setWrapText(true);

        Button applyMigration = new Button("Aplicar migracion");
        applyMigration.setGraphic(icon("fas-file-import"));
        applyMigration.setOnAction(event -> applyMigration());

        GridPane migrationGrid = formGrid();
        addFormRow(migrationGrid, 0, "Serie", migrationSeriesCombo);
        addFormRow(migrationGrid, 1, "Proximo numero", migrationNextNumberField);

        VBox migrationBlock = new VBox(8,
                migrationHeader,
                migrationHint,
                migrationGrid,
                migrationAcknowledgeCheck,
                new HBox(applyMigration)
        );

        // ---- Textos legales de factura ----
        Label textsHeader = label("Textos legales en la factura", "settings-section-title");
        Label textsHint = new Label("Aparecen al pie de cada factura emitida segun el caso. "
                + "Vacios = no se imprime esa seccion.");
        textsHint.setWrapText(true);
        textsHint.getStyleClass().add("settings-hint");

        textPieArea = textArea(texts == null ? null : texts.pie(), "Pie general (datos de contacto, agradecimiento, etc.)");
        textExemptArea = textArea(texts == null ? null : texts.exempt(), "Texto para facturas con IVA exento (art.20 Ley IVA)");
        textReverseChargeArea = textArea(texts == null ? null : texts.reverseCharge(), "Sujeto pasivo (servicios intracomunitarios, art.84 LIVA)");
        textReducedVatArea = textArea(texts == null ? null : texts.reducedVat(), "Mensaje cuando se aplica IVA reducido (4%/10%)");
        textRectifyingArea = textArea(texts == null ? null : texts.rectifying(), "Texto para facturas rectificativas");
        textLegalTermsArea = textArea(texts == null ? null : texts.legalTerms(), "Terminos legales (vencimiento, mora, jurisdiccion)");

        showIbanCheck = new CheckBox("Mostrar IBAN de la empresa en la factura");
        showIbanCheck.setSelected(texts == null || texts.showIban());

        GridPane textsGrid = formGrid();
        addFormRow(textsGrid, 0, "Pie general", textPieArea);
        addFormRow(textsGrid, 1, "Exencion IVA", textExemptArea);
        addFormRow(textsGrid, 2, "Sujeto pasivo", textReverseChargeArea);
        addFormRow(textsGrid, 3, "IVA reducido", textReducedVatArea);
        addFormRow(textsGrid, 4, "Rectificativas", textRectifyingArea);
        addFormRow(textsGrid, 5, "Terminos legales", textLegalTermsArea);

        Button saveTexts = new Button("Guardar textos");
        saveTexts.setGraphic(icon("fas-save"));
        saveTexts.setOnAction(event -> saveInvoiceTexts());

        VBox textsBlock = new VBox(8,
                textsHeader, textsHint,
                textsGrid, showIbanCheck,
                new HBox(saveTexts)
        );

        Button save = new Button("Guardar VeriFactu");
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
        return tabLayout(label("Configuracion de facturacion", "settings-section-title"), body, actions);
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
            showError("Falta serie", "Selecciona la serie cuyo correlativo quieres migrar.");
            return;
        }
        if (!migrationAcknowledgeCheck.isSelected()) {
            showError("Falta confirmacion",
                    "Debes confirmar que asumes la responsabilidad antes de aplicar la migracion.");
            return;
        }
        Integer next;
        try {
            next = Integer.parseInt(migrationNextNumberField.getText().trim());
        } catch (NumberFormatException ex) {
            showError("Numero invalido", "Indica un numero entero >= 1.");
            return;
        }
        if (next < 1) {
            showError("Numero invalido", "El proximo numero debe ser >= 1.");
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
                    "Serie " + serie.code() + " migrada. Proximo numero: " + nextNumber + ".", ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
            pendingBillingTab = "config";
            showBilling();
        });
        task.setOnFailed(event -> showError("No se pudo migrar",
                "Comprueba el numero y que la serie sigue activa."));
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
        dialog.setTitle(existing == null ? "Definir serie de facturas" : "Editar mi serie de facturas");
        dialog.setHeaderText(null);

        TextField codeField = new TextField(existing == null ? "" : existing.code());
        codeField.setPromptText("Ej. F2026, FRA, F");
        codeField.getStyleClass().add("form-input");

        // Tipo factura: fijo a STANDARD (el usuario solo define su serie
        // de facturas normales). Las PROF/RECT las gestiona el sistema.
        Label kindFixedLabel = new Label("Factura normal (STANDARD)");
        kindFixedLabel.getStyleClass().add("invoice-pill");

        ComboBox<String> numberingCombo = new ComboBox<>();
        numberingCombo.getItems().addAll("STANDARD", "BY_YEAR", "PREFIXED");
        numberingCombo.getSelectionModel().select(existing == null ? "BY_YEAR" : existing.numberingType());
        numberingCombo.getStyleClass().add("form-input");

        TextField formatField = new TextField(existing == null
                ? "{CODE}-{YYYY}-{0000}"
                : (existing.formatTemplate() == null ? "" : existing.formatTemplate()));
        formatField.setPromptText("Placeholders: {CODE}, {YYYY}, {0000}");
        formatField.getStyleClass().add("form-input");

        TextField nextNumberField = new TextField(existing == null ? "1" : String.valueOf(existing.nextNumber()));
        nextNumberField.getStyleClass().add("form-input");
        nextNumberField.setDisable(existing != null);

        Label nextNumberHint = new Label(existing == null
                ? "Numero por el que arrancara la serie (normalmente 1; si vienes de otro programa, usa Migracion)."
                : "El correlativo no se cambia desde aqui. Usa 'Migracion desde otro programa' si vienes de otro software.");
        nextNumberHint.setWrapText(true);
        nextNumberHint.getStyleClass().add("settings-hint");

        Label autoLockHint = new Label("La serie se autobloquea para edicion en cuanto emites la primera "
                + "factura validada del ano (continuidad legal). No hay checkbox: es automatico.");
        autoLockHint.setWrapText(true);
        autoLockHint.getStyleClass().add("settings-hint");

        GridPane grid = formGrid();
        addFormRow(grid, 0, "Codigo *", codeField);
        addFormRow(grid, 1, "Tipo", kindFixedLabel);
        addFormRow(grid, 2, "Numeracion *", numberingCombo);
        addFormRow(grid, 3, "Formato", formatField);
        addFormRow(grid, 4, "Proximo nº", nextNumberField);

        VBox dialogBody = new VBox(12, grid, nextNumberHint, autoLockHint);
        dialogBody.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(dialogBody);

        ButtonType saveBtn = new ButtonType(existing == null ? "Crear" : "Guardar", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        // Validacion local antes de cerrar el dialogo: codigo no vacio
        // y, si es CREATE, proximo numero entero >= 1.
        Node saveButton = dialog.getDialogPane().lookupButton(saveBtn);
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            if (codeField.getText().trim().isBlank()) {
                ev.consume();
                showError("Falta codigo", "Pon un codigo unico para la serie (ej. F2026).");
                return;
            }
            if (existing == null) {
                try {
                    int n = Integer.parseInt(nextNumberField.getText().trim());
                    if (n < 1) throw new NumberFormatException();
                } catch (NumberFormatException ex) {
                    ev.consume();
                    showError("Numero invalido", "El proximo numero debe ser un entero >= 1.");
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
                existing == null ? "No se pudo crear la serie" : "No se pudo guardar",
                "Comprueba que el codigo no este duplicado. Si la serie tiene facturas validadas este ano "
                        + "no se puede cambiar codigo/formato/tipo (continuidad legal — usa migracion)."));
        start(task, "billing-series-save");
    }

    private void deleteSeries(SeriesEntry serie) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Vas a eliminar la serie " + serie.code() + ". "
                        + "Si ya tiene facturas asociadas, el backend rechazara la operacion. "
                        + "¿Continuar?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) {
            return;
        }
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                billingApiClient.deleteSeries(serie.id());
                return null;
            }
        };
        task.setOnSucceeded(ev -> {
            pendingBillingTab = "config";
            showBilling();
        });
        task.setOnFailed(ev -> showError("No se pudo eliminar",
                "La serie puede tener facturas emitidas. En ese caso solo se puede bloquear (editar -> 'Serie bloqueada')."));
        start(task, "billing-series-delete");
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
                    "Textos legales guardados.", ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
        });
        task.setOnFailed(event -> showError("No se pudieron guardar los textos",
                "Vuelve a intentarlo en unos segundos."));
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
                        "Necesitas crear un cliente antes de facturar",
                        "Ve a Clientes y da de alta al menos uno. Despues vuelve aqui y pulsa 'Nueva factura'.")));
                return;
            }
            if (existingInvoiceId == null && bundle.series().isEmpty()) {
                setCenterAnimated(scroll(prerequisitePanel(
                        "Necesitas configurar una serie de numeracion",
                        "Ve a Facturacion > Configuracion > Series y pulsa 'Nueva serie'. "
                                + "Sugerencia: codigo F" + LocalDate.now().getYear()
                                + ", tipo STANDARD, numeracion BY_YEAR, proximo nº 1.")));
                return;
            }
            setCenterAnimated(scroll(invoiceEditorView(bundle, existingInvoiceId)));
        });
        task.setOnFailed(event -> setCenterAnimated(scroll(errorPanel(
                existingInvoiceId == null
                        ? "No se pudo abrir el editor: faltan clientes o series activos."
                        : "No se pudo cargar la factura."))));
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
        Button back = new Button("Volver al listado");
        back.setGraphic(icon("fas-arrow-left"));
        back.setOnAction(event -> showBilling());

        Label title = new Label(existingId == null ? "Nueva factura" : "Editar borrador");
        title.getStyleClass().add("module-detail-title");
        Label subtitle = new Label(existingId == null
                ? "El nº se asigna automaticamente al validar. Guardar borrador NO lo consume."
                : "Estas editando un borrador. Validar lo numera y lo bloquea.");
        subtitle.getStyleClass().add("module-detail-description");

        // Pill grande con el numero que se asignara al validar. Se mantiene
        // igual aunque guardes varios borradores: solo "Validar y emitir"
        // consume el correlativo. Cuando cambias de serie en el combo se
        // recalcula en vivo.
        Label nextNumberBadgeLabel = label("PROXIMO Nº AL VALIDAR", "invoice-next-number-caption");
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
        editorNotesArea.setPromptText("Notas internas u observaciones que apareceran en la factura.");
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
            Label datos = label("Datos de facturacion", "invoice-detail-title");
            Label nif = new Label("NIF: " + (c.taxIdentifier() == null || c.taxIdentifier().isBlank() ? "—" : c.taxIdentifier()));
            nif.getStyleClass().add("invoice-detail-line");
            clientDetail.getChildren().addAll(datos, nif);
            if (c.email() != null && !c.email().isBlank()) {
                Label em = new Label("Email: " + c.email());
                em.getStyleClass().add("invoice-detail-line");
                clientDetail.getChildren().add(em);
            }
            if (c.phone() != null && !c.phone().isBlank()) {
                Label tel = new Label("Tel.: " + c.phone());
                tel.getStyleClass().add("invoice-detail-line");
                clientDetail.getChildren().add(tel);
            }
        };
        editorCustomerCombo.valueProperty().addListener((obs, oldV, newV) -> refreshClientDetail.run());
        refreshClientDetail.run();

        // Pill grande con el tipo de factura. La serie ya no se elige; la
        // resuelve el server segun el kind. Por ahora siempre "Factura
        // normal" (STANDARD). Cuando llegue el flujo de proformas
        // anadiremos un selector aqui.
        Label kindPill = label("Factura normal · serie automatica",
                "invoice-pill");

        // El badge del header refleja el proximo numero de la STANDARD.
        if (standardSeries != null) {
            nextNumberBadgeValue.setText(previewNextNumber(standardSeries));
        } else {
            nextNumberBadgeValue.setText("—");
        }

        VBox colCliente = new VBox(8,
                label("Cliente *", "invoice-field-label"),
                editorCustomerCombo,
                clientDetail
        );
        VBox colFechas = new VBox(8,
                label("Fecha de emision *", "invoice-field-label"),
                editorInvoiceDate,
                label("Fecha de vencimiento", "invoice-field-label"),
                editorDueDate
        );
        VBox colTipo = new VBox(8,
                label("Tipo", "invoice-field-label"),
                kindPill
        );
        HBox.setHgrow(colCliente, Priority.ALWAYS);
        HBox.setHgrow(colFechas, Priority.ALWAYS);
        HBox.setHgrow(colTipo, Priority.ALWAYS);
        colCliente.setMinWidth(0);
        colFechas.setMinWidth(0);
        colTipo.setMinWidth(0);
        HBox cabeceraGrid = new HBox(20, colCliente, colFechas, colTipo);
        Node cabeceraCard = invoiceCard("Cabecera de la factura", "fas-info-circle", cabeceraGrid);

        // ----- Card 2: Lineas -----
        editorLinesTable = buildEditorLinesTable(bundle.existingLines());
        // Para una factura nueva, arrancamos con una linea vacia: evita la
        // sensacion de "pantalla rota" sin filas y replica el patron de
        // CONTENDO al abrir Nueva Factura.
        if (existingId == null && editorLinesTable.getItems().isEmpty()) {
            editorLinesTable.getItems().add(new InvoiceLineDraft());
        }

        Button addLine = new Button("Anadir linea");
        addLine.setGraphic(icon("fas-plus"));
        addLine.getStyleClass().add("invoice-primary-action");
        addLine.setOnAction(event -> {
            editorLinesTable.getItems().add(new InvoiceLineDraft());
            recomputeEditorTotals();
        });

        Button removeLine = new Button("Quitar linea");
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
        Node lineasCard = invoiceCardWithActions("Conceptos / Lineas", "fas-calculator",
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
                invoiceTotalsRow("Base imponible", editorSubtotalLabel),
                invoiceTotalsRow("Cuota IVA total", editorVatLabel),
                invoiceTotalsRow("Retencion IRPF", editorRetentionLabel)
        );

        Label totalLabel = label("TOTAL FACTURA", "invoice-total-label");
        Region totalSpacer = new Region();
        HBox.setHgrow(totalSpacer, Priority.ALWAYS);
        HBox totalBigBox = new HBox(12, totalLabel, totalSpacer, editorTotalLabel);
        totalBigBox.setAlignment(Pos.CENTER_LEFT);
        totalBigBox.getStyleClass().add("invoice-total-card");

        VBox rightTotals = new VBox(14, totalsRows, totalBigBox);
        rightTotals.setMinWidth(320);
        rightTotals.setMaxWidth(420);

        VBox notesCol = new VBox(8,
                label("Notas / Observaciones", "invoice-field-label"),
                editorNotesArea
        );
        HBox.setHgrow(notesCol, Priority.ALWAYS);
        notesCol.setMinWidth(0);
        VBox.setVgrow(editorNotesArea, Priority.ALWAYS);

        HBox totalesGrid = new HBox(24, notesCol, rightTotals);
        Node totalesCard = invoiceCard("Totales y observaciones", "fas-euro-sign", totalesGrid);

        recomputeEditorTotals();

        // ----- Footer bar -----
        Button cancel = new Button("Cancelar");
        cancel.setOnAction(event -> showBilling());

        Button saveDraft = new Button(existingId == null ? "Guardar borrador" : "Guardar cambios");
        saveDraft.setGraphic(icon("fas-save"));
        saveDraft.getStyleClass().add("invoice-primary-action");
        saveDraft.setOnAction(event -> persistDraft(existingId, false));

        Button validate = new Button("Validar y emitir");
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

    private void configureSeriesCombo(ComboBox<SeriesEntry> combo) {
        // En ambos cells mostramos el preview ya formateado (previewNextNumber
        // ya inyecta {CODE}). Antes hacíamos `code + " · " + preview` y el
        // usuario veia "FRA · FRA-2026-0001" (CODE duplicado). Ahora:
        // dropdown muestra "FRA-2026-0001 — STANDARD" y el boton solo
        // "FRA-2026-0001".
        combo.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(SeriesEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null
                        ? ""
                        : previewNextNumber(item) + "  ·  " + item.invoiceKind());
            }
        });
        combo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(SeriesEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : previewNextNumber(item));
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

        TableColumn<InvoiceLineDraft, String> colDesc = liveTextColumn("Descripcion",
                InvoiceLineDraft::getDescription, InvoiceLineDraft::setDescription);
        colDesc.setPrefWidth(280);

        TableColumn<InvoiceLineDraft, String> colQty = decimalColumn("Cant.", InvoiceLineDraft::getQuantity, InvoiceLineDraft::setQuantity);
        TableColumn<InvoiceLineDraft, String> colPrice = decimalColumn("Precio", InvoiceLineDraft::getUnitPrice, InvoiceLineDraft::setUnitPrice);
        TableColumn<InvoiceLineDraft, String> colVat = decimalColumn("IVA %", InvoiceLineDraft::getVatPercent, InvoiceLineDraft::setVatPercent);
        TableColumn<InvoiceLineDraft, String> colRet = decimalColumn("Ret. %", InvoiceLineDraft::getRetentionPercent, InvoiceLineDraft::setRetentionPercent);

        // Limpiamos los mapas al construir la tabla — la instancia anterior
        // ya no existe y sus labels son basura.
        rowSubtotalLabels.clear();
        rowLineTotalLabels.clear();

        TableColumn<InvoiceLineDraft, String> colSubtotal = computedColumn("Subtotal",
                line -> money(lineSubtotal(line).toPlainString()),
                rowSubtotalLabels);
        TableColumn<InvoiceLineDraft, String> colLineTotal = computedColumn("Total",
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
            showError("Falta cliente", "Selecciona un cliente.");
            return;
        }
        if (editorLinesTable.getItems().isEmpty()) {
            showError("Sin lineas", "Una factura sin lineas no se puede guardar.");
            return;
        }
        for (InvoiceLineDraft line : editorLinesTable.getItems()) {
            if (line.getDescription() == null || line.getDescription().isBlank()) {
                showError("Linea incompleta", "Hay una linea sin descripcion. Rellenala o quitala.");
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
                    ? "Factura validada: " + result.invoiceNumber()
                    : "Borrador guardado.";
            Alert ok = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
            showBilling();
        });
        task.setOnFailed(event -> showError(
                validateAfter ? "No se pudo validar" : "No se pudo guardar",
                "Revisa los datos. Si validas, la serie debe estar disponible y los totales correctos."));
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
                    "Configuracion VeriFactu guardada (modo " + mode + ").", ButtonType.OK);
            ok.setHeaderText(null);
            ok.showAndWait();
        });
        task.setOnFailed(event -> showError("No se pudo guardar",
                "Si seleccionaste PROD recuerda elegir un certificado .p12."));
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
        Button retry = new Button("Reintentar");
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
        Button back = new Button("Volver a Facturacion");
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
