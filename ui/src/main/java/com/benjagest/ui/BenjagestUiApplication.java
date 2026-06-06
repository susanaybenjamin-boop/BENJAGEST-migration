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
import com.benjagest.ui.service.AltaApiClient;
import com.benjagest.ui.service.LaborApiClient;
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
import javafx.scene.control.DatePicker;
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
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
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
    private final com.benjagest.ui.service.AccountingApiClient accountingApiClient =
            new com.benjagest.ui.service.AccountingApiClient();
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
            "tax", "reports", "calendar", "settings",
            "advisory", "self-employed", "notifications", "time-clock"
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
        // Listados: Escape y click en zona vacía deseleccionan en CUALQUIER
        // TableView de la aplicación (presentes y futuros). Un único punto
        // de instalación cubre los 38+ listados.
        com.benjagest.ui.support.TableSelectionHelper.attachToScene(scene);

        // Botones laterales del raton: 4 = BACK, 5 = FORWARD.
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, ev -> {
            // Si hay algún diálogo/alert/popup modal abierto, NO navegamos
            // — el back debe afectar al dialog o ser ignorado, nunca
            // sacar al usuario del cliente cuando está leyendo un mensaje.
            // Iteramos las ventanas: si alguna está visible y NO es el
            // stage principal, asumimos que es un modal.
            for (javafx.stage.Window w : javafx.stage.Stage.getWindows()) {
                if (w.isShowing() && w != scene.getWindow()) {
                    return; // hay modal → no consumimos, dejamos que el dialog actúe
                }
            }
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

        // Credenciales demo seleccionables para copiar/pegar (CTRL+C).
        // Usamos TextField readonly en lugar de Label porque Label no
        // permite selección de texto en JavaFX.
        Label demoTitle = new Label("Datos demo (selecciona y copia con Ctrl+C):");
        demoTitle.getStyleClass().add("status-detail");

        TextField demoAdmin = new TextField("admin@benjagest.local");
        demoAdmin.setEditable(false);
        demoAdmin.setFocusTraversable(false);
        demoAdmin.getStyleClass().add("status-detail");

        TextField demoEmpresario = new TextField("empresario@benjagest.local");
        demoEmpresario.setEditable(false);
        demoEmpresario.setFocusTraversable(false);
        demoEmpresario.getStyleClass().add("status-detail");

        TextField demoPassword = new TextField("Benjamin123456$");
        demoPassword.setEditable(false);
        demoPassword.setFocusTraversable(false);
        demoPassword.getStyleClass().add("status-detail");

        // Doble-click sobre cualquiera de los tres → pre-rellena los
        // campos de login. Atajo para no tener que copiar dos veces.
        demoAdmin.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                emailField.setText(demoAdmin.getText());
                passwordField.setText(demoPassword.getText());
                passwordField.requestFocus();
            }
        });
        demoEmpresario.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                emailField.setText(demoEmpresario.getText());
                passwordField.setText(demoPassword.getText());
                passwordField.requestFocus();
            }
        });

        VBox demoBox = new VBox(4, demoTitle, demoAdmin, demoEmpresario, demoPassword);
        demoBox.setAlignment(Pos.CENTER_LEFT);

        panel.getChildren().addAll(
                AppBrand.createLogoMark(), title, subtitle,
                emailField, passwordField, loginButton,
                new Separator(),
                googleButton,
                demoBox
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
                        m.icon() == null || m.icon().isBlank() ? "fas-cube" : m.icon(),
                        m.advisoryOnly()
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
        // El top se compone de un VBox para poder insertar/quitar el
        // banner de modo cliente sin reconstruir el header.
        topContainer = new VBox(header());
        root.setTop(topContainer);
        refreshClientModeBanner();
        // Sidebar envuelto en ScrollPane: en portatil con muchos modulos
        // activos no caben todos verticalmente. Aparece scrollbar solo
        // cuando hace falta (vbarPolicy AS_NEEDED).
        ScrollPane sidebarScroll = new ScrollPane(sidebar());
        sidebarScroll.setFitToWidth(true);
        sidebarScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sidebarScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sidebarScroll.getStyleClass().add("sidebar-scroll");
        root.setLeft(sidebarScroll);
        root.setBottom(footer());
        startInvitationsPolling();
        startAdvisoryClientsPolling();
    }

    /**
     * Limpia el modo "operando como cliente": elimina el override
     * actingForCompanyId en AuthSession (las próximas llamadas vuelven
     * al activeCompanyId real de la asesoría), borra el nombre cacheado
     * y refresca el banner. Llamado por todos los handlers del sidebar
     * para evitar que la asesoría se quede atrapada.
     */
    private void exitClientMode() {
        if (!AuthSession.get().isActingForClient()) return;
        AuthSession.get().setActingForCompanyId(null);
        actingClientName = null;
        refreshClientModeBanner();
    }

    /**
     * Reconstruye (o quita) el banner amber permanente que indica que
     * el asesor está operando dentro del tenant de un cliente concreto.
     * El banner siempre lleva un botón "✕ Salir" para volver al modo
     * asesoría con un solo click.
     */
    private void refreshClientModeBanner() {
        if (topContainer == null) return;
        topContainer.getChildren().removeIf(n -> "client-mode-banner".equals(n.getId()));
        if (!AuthSession.get().isActingForClient()) return;
        topContainer.getChildren().add(buildClientModeBanner());
    }

    private Node buildClientModeBanner() {
        Label icon = new Label("👤");
        icon.setStyle("-fx-font-size: 16;");
        String name = actingClientName == null
                ? t("client_mode.banner.title_generic")
                : t("client_mode.banner.title") + " " + actingClientName;
        Label text = new Label(name);
        text.setStyle("-fx-font-weight: bold; -fx-text-fill: #92400e;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button exit = new Button("✕ " + t("client_mode.banner.exit"));
        exit.setStyle(
                "-fx-background-color: transparent;"
              + " -fx-text-fill: #92400e;"
              + " -fx-border-color: #92400e;"
              + " -fx-border-radius: 4;"
              + " -fx-padding: 2 10 2 10;");
        exit.setOnAction(ev -> {
            exitClientMode();
            showAdvisoryClients();
        });
        HBox banner = new HBox(10, icon, text, spacer, exit);
        banner.setPadding(new Insets(8, 16, 8, 16));
        banner.setStyle(
                "-fx-background-color: #fef3c7;"
              + " -fx-border-color: #f59e0b;"
              + " -fx-border-width: 0 0 1 0;");
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.setId("client-mode-banner");
        return banner;
    }

    /**
     * Arranca el polling de invitaciones (live). Cada 30s consulta al
     * backend y actualiza el banner si está visible o muestra un aviso
     * si hay invitaciones nuevas y el usuario está en otra pantalla.
     */
    private void startInvitationsPolling() {
        if (invitationsPoller != null) return; // ya activo
        // 5s: el empresario tiene que ver las invitaciones de su
        // asesoría casi al instante para sentir que la comunicación
        // está viva.
        invitationsPoller = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(
                        javafx.util.Duration.seconds(5),
                        ev -> pollPendingInvitations()));
        invitationsPoller.setCycleCount(javafx.animation.Animation.INDEFINITE);
        invitationsPoller.play();
        pollPendingInvitations();
    }

    private void stopInvitationsPolling() {
        if (invitationsPoller != null) {
            invitationsPoller.stop();
            invitationsPoller = null;
        }
        seenInvitationIds.clear();
        invitationsBootstrapped = false;
        dashboardInvitationsSlot = null;
    }

    /**
     * Polling para la asesoría: cada 30s comprueba si han aparecido
     * clientes nuevos (típicamente porque alguien acaba de aceptar
     * una invitación). Solo arranca si appMode == ADVISORY.
     */
    private void startAdvisoryClientsPolling() {
        if (advisoryClientsPoller != null || appMode != AppMode.ADVISORY) return;
        // 5s: queremos que la asesoría detecte vinculaciones y
        // desvinculaciones de forma "casi en vivo" durante el flujo de
        // comunicación con clientes. El endpoint es ligero (una query
        // sobre customers con dos subqueries por fila); 5s no es agresivo.
        advisoryClientsPoller = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(
                        javafx.util.Duration.seconds(5),
                        ev -> pollAdvisoryClients()));
        advisoryClientsPoller.setCycleCount(javafx.animation.Animation.INDEFINITE);
        advisoryClientsPoller.play();
        pollAdvisoryClients();
    }

    private void stopAdvisoryClientsPolling() {
        if (advisoryClientsPoller != null) {
            advisoryClientsPoller.stop();
            advisoryClientsPoller = null;
        }
        seenLinkedCompanyIds.clear();
        advisoryClientsBootstrapped = false;
        advisoryPortfolioTable = null;
    }

    private void pollAdvisoryClients() {
        if (!com.benjagest.ui.service.AuthSession.get().isAuthenticated()) return;
        if (appMode != AppMode.ADVISORY) return;
        Task<java.util.List<com.benjagest.ui.model.CustomerPortfolioEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.CustomerPortfolioEntry> call() throws Exception {
                return altaApiClient.listAdvisoryPortfolio();
            }
        };
        task.setOnSucceeded(e -> {
            var list = task.getValue();
            if (list == null) list = java.util.List.of();

            // Construir set de linkedCompanyIds VIGENTES tras la query.
            java.util.Set<String> currentlyLinked = new java.util.HashSet<>();
            for (var c : list) {
                if (c.isLinked() && c.linkedCompanyId() != null) {
                    currentlyLinked.add(c.linkedCompanyId());
                }
            }

            boolean hadNewLinks = false;
            boolean hadUnlinks = false;
            boolean activeClientUnlinked = false;
            String actingFor = AuthSession.get().getActingForCompanyId();
            if (advisoryClientsBootstrapped) {
                // Nuevos vínculos: están en currentlyLinked pero no en seen.
                for (String id : currentlyLinked) {
                    if (seenLinkedCompanyIds.add(id)) hadNewLinks = true;
                }
                // Desvinculaciones: están en seen pero no en currentlyLinked.
                var toRemove = new java.util.ArrayList<String>();
                for (String id : seenLinkedCompanyIds) {
                    if (!currentlyLinked.contains(id)) {
                        toRemove.add(id);
                        hadUnlinks = true;
                        // Si el cliente que la asesoría está viendo en este
                        // momento es uno de los desvinculados, hay que
                        // sacarla del modo cliente o se queda haciendo
                        // peticiones en nombre de un cliente al que ya no
                        // tiene acceso (todas devolverían 403).
                        if (actingFor != null && actingFor.equals(id)) {
                            activeClientUnlinked = true;
                        }
                    }
                }
                seenLinkedCompanyIds.removeAll(toRemove);
            } else {
                seenLinkedCompanyIds.addAll(currentlyLinked);
                advisoryClientsBootstrapped = true;
            }

            // Refrescar la tabla del portfolio si está visible. setAll
            // reemplaza todas las filas con el estado nuevo del backend,
            // así que badges (linked / pending / not_linked) y filas
            // desaparecidas se reflejan automáticamente.
            boolean tableVisible = advisoryPortfolioTable != null
                    && advisoryPortfolioTable.getScene() != null;
            if (tableVisible) {
                advisoryPortfolioTable.getItems().setAll(list);
            }

            // Caso crítico: si el cliente que la asesoría estaba
            // mirando se ha desvinculado, sacarla del modo cliente AHORA
            // y notificarla. Hacerlo antes de cualquier otra
            // notificación porque marca el cambio de contexto más
            // importante para esta UI.
            if (activeClientUnlinked) {
                exitClientMode();
                showInfo(t("advisory.toast.active_client_unlinked.title"),
                        t("advisory.toast.active_client_unlinked.body"));
                showDashboard();
            }

            // Notificaciones nativas cuando NO estamos viendo la pantalla.
            if (!tableVisible && !activeClientUnlinked) {
                if (hadNewLinks) {
                    showInfo(t("advisory.toast.new_client.title"),
                            t("advisory.toast.new_client.body"));
                } else if (hadUnlinks) {
                    showInfo(t("advisory.toast.unlinked.title"),
                            t("advisory.toast.unlinked.body"));
                }
            }
        });
        task.setOnFailed(e -> { /* silencio */ });
        start(task, "advisory-clients-poll");
    }

    private void pollPendingInvitations() {
        if (!com.benjagest.ui.service.AuthSession.get().isAuthenticated()) return;
        // Las invitaciones solo tienen sentido para CLIENTs — una
        // asesoría no recibe invitaciones de otra asesoría. Saltamos.
        if (appMode == AppMode.ADVISORY) return;
        Task<java.util.List<com.benjagest.ui.model.AdvisoryInvitationEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.AdvisoryInvitationEntry> call() throws Exception {
                return invitationsApi.listPending();
            }
        };
        task.setOnSucceeded(e -> {
            var list = task.getValue();
            if (list == null) list = java.util.List.of();
            // Detección de invitaciones NUEVAS desde el último ciclo.
            boolean hadNewOnes = false;
            if (invitationsBootstrapped) {
                for (var inv : list) {
                    if (inv.id() != null && seenInvitationIds.add(inv.id())) {
                        hadNewOnes = true;
                    }
                }
            } else {
                // Primera carga tras login: marca todo como visto sin notificar.
                for (var inv : list) if (inv.id() != null) seenInvitationIds.add(inv.id());
                invitationsBootstrapped = true;
            }
            // Refrescar el banner si el dashboard sigue montado.
            if (dashboardInvitationsSlot != null
                    && dashboardInvitationsSlot.getScene() != null) {
                dashboardInvitationsSlot.getChildren().clear();
                for (var inv : list) {
                    dashboardInvitationsSlot.getChildren().add(
                            buildInvitationCard(inv, dashboardInvitationsSlot));
                }
            }
            // Si hay nuevas y NO estamos en el Home (slot no visible),
            // notificar con un alert nativo discreto.
            if (hadNewOnes && (dashboardInvitationsSlot == null
                    || dashboardInvitationsSlot.getScene() == null)) {
                showInfo(t("advisory.invitation.toast.title"),
                        t("advisory.invitation.toast.body"));
            }
        });
        task.setOnFailed(e -> { /* backend caído → silencio para no spamear */ });
        start(task, "advisory-invitations-poll");
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
            stopInvitationsPolling();
            stopAdvisoryClientsPolling();
            stopDehuPolling();
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

        Button home = navButton("dashboard", t("home"), "fas-home");
        // Sidebar SIEMPRE limpia el modo cliente. Si la asesoría estaba
        // viendo un cliente y pulsa cualquier botón del sidebar, debe
        // volver a operar como asesoría — no quedarse atrapada en el
        // tenant del cliente.
        home.setOnAction(event -> { exitClientMode(); showDashboard(); });

        List<ModuleLink> modules = activeModules();

        // Sidebar dual SOLO para la asesoría operando sobre sí misma.
        // Cuando entra en un cliente (actingForCompanyId != null), no se
        // muestran los módulos advisory_only — está actuando como ese
        // cliente y debe ver SU sidebar empresarial; el banner ámbar
        // permite volver.
        boolean dualMode = appMode == AppMode.ADVISORY
                && !AuthSession.get().isActingForClient()
                && modules.stream().anyMatch(ModuleLink::advisoryOnly);

        if (dualMode) {
            Label myCompanySection = new Label(t("sidebar.section.my_company"));
            myCompanySection.getStyleClass().add("sidebar-section");
            sidebar.getChildren().addAll(myCompanySection, home);
            // Módulos empresariales: gestión propia (Personal, Mi
            // facturación, Mis compras, Configuración, etc.).
            for (ModuleLink link : modules) {
                if (link.advisoryOnly()) continue;
                Button button = navButton(link.id(), moduleTitle(link.id()), link.icon());
                button.setOnAction(event -> { exitClientMode(); showModule(link.id()); });
                sidebar.getChildren().add(button);
            }
            // Sección "Mis clientes" con los módulos advisory_only.
            Label clientsSection = new Label(t("sidebar.section.my_clients"));
            clientsSection.getStyleClass().add("sidebar-section");
            // Separador visual con un margin top mayor.
            VBox.setMargin(clientsSection, new Insets(16, 0, 0, 0));
            sidebar.getChildren().add(clientsSection);
            for (ModuleLink link : modules) {
                if (!link.advisoryOnly()) continue;
                Button button = navButton(link.id(), moduleTitle(link.id()), link.icon());
                button.setOnAction(event -> { exitClientMode(); showModule(link.id()); });
                sidebar.getChildren().add(button);
            }
        } else {
            // Empresario, o asesoría operando dentro de un cliente:
            // sidebar plano con una sola sección "Módulos".
            Label section = new Label(t("modules"));
            section.getStyleClass().add("sidebar-section");
            sidebar.getChildren().addAll(section, home);
            for (ModuleLink link : modules) {
                Button button = navButton(link.id(), moduleTitle(link.id()), link.icon());
                button.setOnAction(event -> { exitClientMode(); showModule(link.id()); });
                sidebar.getChildren().add(button);
            }
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

    /**
     * Carga las invitaciones PENDING dirigidas al empresario actual y
     * las pinta como banner destacado en el Home. Si no hay
     * invitaciones (caso habitual), el slot queda vacío.
     *
     * Guarda referencia al slot en {@link #dashboardInvitationsSlot} para
     * que el polling periódico ({@link #pollPendingInvitations()}) pueda
     * refrescarlo sin necesidad de reentrar al Home.
     */
    private void loadPendingInvitationsBanner(VBox slot) {
        dashboardInvitationsSlot = slot;
        Task<java.util.List<com.benjagest.ui.model.AdvisoryInvitationEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.AdvisoryInvitationEntry> call() throws Exception {
                return invitationsApi.listPending();
            }
        };
        task.setOnSucceeded(ev -> {
            slot.getChildren().clear();
            var list = task.getValue();
            if (list == null || list.isEmpty()) return;
            for (var inv : list) {
                slot.getChildren().add(buildInvitationCard(inv, slot));
                // Mantener el set sincronizado para no notificar invitaciones
                // que el usuario YA está viendo en pantalla.
                if (inv.id() != null) seenInvitationIds.add(inv.id());
            }
            invitationsBootstrapped = true;
        });
        task.setOnFailed(ev -> { /* silencioso: backend caído no debe romper el dashboard */ });
        start(task, "advisory-pending-invitations");
    }

    private Node buildInvitationCard(com.benjagest.ui.model.AdvisoryInvitationEntry inv,
                                       VBox parentSlot) {
        Label icon = new Label("📩"); // 📩
        icon.setStyle("-fx-font-size: 28;");
        String fromHint = inv.invitedCompanyName() == null
                ? t("advisory.invitation.banner.from_generic")
                : t("advisory.invitation.banner.from") + " " + inv.invitedCompanyName();
        Label title = new Label(t("advisory.invitation.banner.title"));
        title.getStyleClass().add("section-title");
        Label hint = new Label(fromHint + "\n" + t("advisory.invitation.banner.body"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        Button accept = new Button(t("advisory.invitation.banner.accept"));
        accept.getStyleClass().add("button-primary");
        accept.setOnAction(ev -> acceptInvitationFromBanner(inv, parentSlot));
        Button reject = new Button(t("advisory.invitation.banner.reject"));
        reject.getStyleClass().add("button-danger-outline");
        reject.setOnAction(ev -> rejectInvitationFromBanner(inv, parentSlot));

        HBox actions = new HBox(8, accept, reject);
        VBox copy = new VBox(6, title, hint, actions);
        HBox card = new HBox(16, icon, copy);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(16));
        card.setStyle(
                "-fx-background-color: #fff8e1;"
              + " -fx-background-radius: 10;"
              + " -fx-border-color: #f4b400;"
              + " -fx-border-width: 1.5;"
              + " -fx-border-radius: 10;"
        );
        HBox.setHgrow(copy, Priority.ALWAYS);
        return card;
    }

    private void acceptInvitationFromBanner(com.benjagest.ui.model.AdvisoryInvitationEntry inv,
                                              VBox parentSlot) {
        Task<com.benjagest.ui.model.AdvisoryInvitationEntry> task = new Task<>() {
            @Override
            protected com.benjagest.ui.model.AdvisoryInvitationEntry call() throws Exception {
                return invitationsApi.accept(inv.token());
            }
        };
        task.setOnSucceeded(e -> {
            showInfo(t("advisory.invitation.accept.ok.title"),
                    t("advisory.invitation.accept.ok.body"));
            // Refresco local inmediato: banner del Home (que mostrará
            // que ya no hay PENDING) + tick de polling (que recargará
            // pestaña "Mi asesoría" si está visible). El polling de la
            // asesoría se enterará en ≤5s por su propio ciclo.
            loadPendingInvitationsBanner(parentSlot);
            pollPendingInvitations();
        });
        task.setOnFailed(e -> showError(t("advisory.invitation.accept.fail.title"),
                t("advisory.invitation.accept.fail.body")));
        start(task, "advisory-invitation-accept");
    }

    private void rejectInvitationFromBanner(com.benjagest.ui.model.AdvisoryInvitationEntry inv,
                                              VBox parentSlot) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                invitationsApi.reject(inv.token());
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            showInfo(t("advisory.invitation.reject.ok.title"),
                    t("advisory.invitation.reject.ok.body"));
            loadPendingInvitationsBanner(parentSlot);
            pollPendingInvitations();
        });
        task.setOnFailed(e -> showError(t("advisory.invitation.reject.fail.title"),
                t("advisory.invitation.reject.fail.body")));
        start(task, "advisory-invitation-reject");
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

        content.getChildren().add(hero);

        // Banner de invitaciones pendientes — solo se carga si el
        // usuario es CLIENT (las asesorías no reciben invitaciones de
        // otras asesorías).
        if (appMode != AppMode.ADVISORY) {
            VBox invBannerSlot = new VBox();
            content.getChildren().add(invBannerSlot);
            loadPendingInvitationsBanner(invBannerSlot);
        }

        content.getChildren().addAll(
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
        if ("time-clock".equals(module)) {
            // C4 (RD 8/2019): modulo de fichaje tiene su propia pantalla
            // — no usa el moduleView genérico porque tiene flujo distinto
            // (botón grande "Fichar entrada/salida" + listado últimos).
            showTimeClock();
            return;
        }
        if ("purchases".equals(module)) {
            // C3: módulo Compras con extractor PDF de facturas. La vista
            // genérica de módulo se enriquece con un botón "Importar PDF"
            // que abre un FileChooser y muestra los campos detectados.
            showPurchasesWithImport();
            return;
        }
        if ("tax".equals(module)) {
            // ALTA-6: módulo Modelos AEAT con tabla de declaraciones,
            // editores específicos por modelo (303 IVA, 130 IRPF) y
            // calendario fiscal del año seleccionado.
            showTaxModels();
            return;
        }
        if ("advisory".equals(module)) {
            // ALTA-3: módulo asesoría — listado de clientes gestionados
            // con switch de tenant al hacer doble-click.
            showAdvisoryClients();
            return;
        }
        if ("labor".equals(module)) {
            showLaborModule();
            return;
        }
        if ("accounting".equals(module)) {
            // Módulo Contabilidad (ACC-LEARN-UI + ACC-UI-DIARIO):
            // tabs Por validar / Diario / Asientos manuales / Reglas
            // aprendidas / Recurrentes. Delegado a AccountingScreen
            // para no engrosar más este archivo.
            showAccountingModule();
            return;
        }
        if ("self-employed".equals(module)) {
            showRetaModule();
            return;
        }
        if ("notifications".equals(module)) {
            showDehuModule();
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
        installDialog(dialog, content);
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
    //  Pantalla Compras con importador PDF (Slice C3)
    // ===================================================================

    private final com.benjagest.ui.service.PdfImportApiClient pdfImportApi =
            new com.benjagest.ui.service.PdfImportApiClient();

    private final com.benjagest.ui.service.CertificateApiClient certificateApi =
            new com.benjagest.ui.service.CertificateApiClient();

    private final com.benjagest.ui.service.PurchaseInvoiceApiClient purchasesApi =
            new com.benjagest.ui.service.PurchaseInvoiceApiClient();

    private final com.benjagest.ui.service.AdvisoryInvitationApiClient invitationsApi =
            new com.benjagest.ui.service.AdvisoryInvitationApiClient();

    private TableView<com.benjagest.ui.model.AdvisoryInvitationEntry> advisoryInvitationsTable;

    // Modo cliente (asesoría operando sobre un cliente vinculado).
    // Cuando está activo, se muestra un banner amber permanente bajo el
    // header con botón de salida, y el sidebar limpia este estado al
    // navegar para evitar que el asesor se quede atrapado.
    private String actingClientName;
    private VBox topContainer;

    // Live polling de invitaciones — evita tener que refrescar la pantalla.
    // El Timeline persiste durante toda la sesión, se arranca tras login
    // y se detiene en logout. Cada ciclo (cada 30s) compara con los IDs
    // ya vistos y notifica si hay nuevas.
    private javafx.animation.Timeline invitationsPoller;
    private final java.util.Set<String> seenInvitationIds = new java.util.HashSet<>();
    private boolean invitationsBootstrapped = false;
    private VBox dashboardInvitationsSlot;

    // Live polling de la cartera de clientes de la asesoría. Refresca
    // tanto vinculaciones nuevas (cliente acepta invitación) como
    // desvinculaciones (cliente desvincula desde su Configuración).
    private javafx.animation.Timeline advisoryClientsPoller;
    // Conjunto de "links activos": guarda los linkedCompanyId que
    // hemos visto vinculados. Cuando uno desaparece detectamos una
    // desvinculación; cuando aparece uno nuevo, detectamos vinculación.
    private final java.util.Set<String> seenLinkedCompanyIds = new java.util.HashSet<>();
    private boolean advisoryClientsBootstrapped = false;

    // Live polling de la bandeja DEHú. Persistente desde el primer
    // acceso al módulo; el tick comprueba `currentModule` y solo
    // refresca la vista si está activa. Las notificaciones DEHú
    // llegan desde el exterior (AEAT/SS) sin aviso, así que esta es
    // la pantalla "live" más obvia del módulo labor.
    private javafx.animation.Timeline dehuPoller;

    private TableView<com.benjagest.ui.model.PurchaseInvoiceEntry> purchaseInvoicesTable;
    private ComboBox<String> purchaseYearFilter;
    private ComboBox<String> purchaseQuarterFilter;
    private TextField purchaseSupplierFilter;
    // Caché para filtrar localmente sin re-pegar al backend en cada
    // cambio de combo (el endpoint solo soporta year+supplierNif).
    private java.util.List<com.benjagest.ui.model.PurchaseInvoiceEntry> purchaseInvoicesCache = java.util.List.of();

    private void showPurchasesWithImport() {
        setCenterAnimated(buildPurchasesListing(true));
    }

    /**
     * Construye el listado de Compras y Gastos con filtros y acciones.
     * Reutilizable desde:
     *   - El módulo "Compras y Gastos" del sidebar (showWithHeader=true,
     *     que añade título + descripción del módulo).
     *   - La pestaña "Compras y Gastos" de la pantalla del cliente
     *     dentro de la asesoría (showWithHeader=false — el header del
     *     cliente y los tabs ya son contexto suficiente).
     */
    private Node buildPurchasesListing(boolean showWithHeader) {
        Button importBtn = new Button(t("purchases.action.import_pdf"));
        importBtn.setGraphic(icon("fas-file-import"));
        importBtn.getStyleClass().add("button-primary");
        importBtn.setOnAction(ev -> importPurchasePdf());

        // Filtros: año (combo) + trimestre (combo) + proveedor (text).
        purchaseYearFilter = new ComboBox<>();
        purchaseYearFilter.getItems().add(t("list.filter.all"));
        int currentYear = java.time.LocalDate.now().getYear();
        for (int y = currentYear; y >= currentYear - 5; y--) purchaseYearFilter.getItems().add(String.valueOf(y));
        purchaseYearFilter.getSelectionModel().selectFirst();
        purchaseYearFilter.setOnAction(ev -> reloadPurchaseInvoices());

        purchaseQuarterFilter = new ComboBox<>();
        purchaseQuarterFilter.getItems().addAll(
                t("list.filter.all"), "T1", "T2", "T3", "T4");
        purchaseQuarterFilter.getSelectionModel().selectFirst();
        // El trimestre se aplica EN CLIENTE sobre el caché (no toca el
        // backend) — el endpoint REST solo soporta year+supplierNif.
        purchaseQuarterFilter.setOnAction(ev -> applyClientSideFilters());

        purchaseSupplierFilter = new TextField();
        purchaseSupplierFilter.setPromptText(t("purchases.filter.supplier.prompt"));
        purchaseSupplierFilter.setPrefColumnCount(14);
        purchaseSupplierFilter.textProperty().addListener((o, ov, nv) -> applyClientSideFilters());

        Button reloadBtn = new Button(t("purchases.action.refresh"));
        reloadBtn.setGraphic(icon("fas-sync"));
        reloadBtn.setOnAction(ev -> reloadPurchaseInvoices());

        HBox filters = new HBox(8,
                new Label(t("purchases.filter.year")), purchaseYearFilter,
                new Label(t("purchases.filter.quarter")), purchaseQuarterFilter,
                new Label(t("purchases.filter.supplier")), purchaseSupplierFilter,
                reloadBtn);
        filters.setAlignment(Pos.CENTER_LEFT);

        purchaseInvoicesTable = new TableView<>();
        purchaseInvoicesTable.getStyleClass().add("data-table");
        purchaseInvoicesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        purchaseInvoicesTable.setPlaceholder(new Label(t("purchases.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.PurchaseInvoiceEntry, String> colDate =
                new TableColumn<>(t("purchases.col.date"));
        colDate.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().invoiceDate() == null ? "—" : c.getValue().invoiceDate().toString()));
        colDate.setPrefWidth(105);
        colDate.setComparator(ISO_DATE_COMPARATOR);

        TableColumn<com.benjagest.ui.model.PurchaseInvoiceEntry, String> colSupplier =
                new TableColumn<>(t("purchases.col.supplier"));
        colSupplier.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().supplierName() == null ? "—" : c.getValue().supplierName()));
        colSupplier.setPrefWidth(220);

        TableColumn<com.benjagest.ui.model.PurchaseInvoiceEntry, String> colNif =
                new TableColumn<>(t("purchases.col.nif"));
        colNif.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().supplierNif() == null ? "—" : c.getValue().supplierNif()));
        colNif.setPrefWidth(110);

        TableColumn<com.benjagest.ui.model.PurchaseInvoiceEntry, String> colNumber =
                new TableColumn<>(t("purchases.col.number"));
        colNumber.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().invoiceNumber() == null ? "—" : c.getValue().invoiceNumber()));
        colNumber.setPrefWidth(150);

        TableColumn<com.benjagest.ui.model.PurchaseInvoiceEntry, String> colBase =
                new TableColumn<>(t("purchases.col.base"));
        colBase.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().baseAmount() == null ? "—"
                        : c.getValue().baseAmount().toPlainString() + " €"));
        colBase.setPrefWidth(100);
        colBase.setComparator(NUMERIC_STRING_COMPARATOR);

        TableColumn<com.benjagest.ui.model.PurchaseInvoiceEntry, String> colVat =
                new TableColumn<>(t("purchases.col.vat"));
        colVat.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().vatAmount() == null ? "—"
                        : c.getValue().vatAmount().toPlainString() + " €"));
        colVat.setPrefWidth(100);
        colVat.setComparator(NUMERIC_STRING_COMPARATOR);

        TableColumn<com.benjagest.ui.model.PurchaseInvoiceEntry, String> colTotal =
                new TableColumn<>(t("purchases.col.total"));
        colTotal.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().totalAmount() == null ? "—"
                        : c.getValue().totalAmount().toPlainString() + " €"));
        colTotal.setPrefWidth(110);
        colTotal.setComparator(NUMERIC_STRING_COMPARATOR);

        // Columna "Estado": DRAFT/POSTED/VOID. Para multivalidación
        // resulta útil ver qué pendiente queda. Se muestra traducido
        // (Borrador/Validado/Anulado en ES, Draft/Posted/Void en EN).
        TableColumn<com.benjagest.ui.model.PurchaseInvoiceEntry, String> colStatus =
                new TableColumn<>(t("purchases.col.status"));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().status() == null ? "—"
                        : t("accounting.status." + c.getValue().status())));
        colStatus.setPrefWidth(95);

        // Columna "Asiento": indica si se generó apunte contable
        // automático (depende de si la empresa tiene plan contable
        // sembrado con cuentas 600/472/400 + fiscal_year OPEN).
        TableColumn<com.benjagest.ui.model.PurchaseInvoiceEntry, String> colJournal =
                new TableColumn<>(t("purchases.col.journal"));
        colJournal.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().hasJournal() ? "✓" : ""));
        colJournal.setPrefWidth(80);

        purchaseInvoicesTable.getColumns().setAll(
                colDate, colSupplier, colNif, colNumber, colBase, colVat, colTotal,
                colStatus, colJournal);

        // Multiselección para validar lotes de DRAFT.
        purchaseInvoicesTable.getSelectionModel().setSelectionMode(
                javafx.scene.control.SelectionMode.MULTIPLE);

        Button deleteBtn = new Button(t("purchases.action.delete"));
        deleteBtn.setGraphic(icon("fas-trash"));
        deleteBtn.getStyleClass().add("button-danger-outline");
        deleteBtn.setDisable(true);
        deleteBtn.setOnAction(ev -> {
            var sel = purchaseInvoicesTable.getSelectionModel().getSelectedItem();
            if (sel != null) deletePurchaseInvoice(sel);
        });

        // Botón "Validar seleccionados": habilitado solo cuando hay al
        // menos un DRAFT en la selección. Llama al endpoint batch.
        Button validateBatchBtn = new Button(t("purchases.action.validate_batch"));
        validateBatchBtn.setGraphic(icon("fas-check-double"));
        validateBatchBtn.getStyleClass().add("button-primary");
        validateBatchBtn.setDisable(true);
        validateBatchBtn.setOnAction(ev -> validatePurchaseBatch());

        purchaseInvoicesTable.getSelectionModel().getSelectedItems()
                .addListener((javafx.collections.ListChangeListener<com.benjagest.ui.model.PurchaseInvoiceEntry>)
                        ch -> {
                    var sel = purchaseInvoicesTable.getSelectionModel().getSelectedItems();
                    deleteBtn.setDisable(sel.isEmpty());
                    boolean anyDraft = sel.stream().anyMatch(
                            e -> e != null && "DRAFT".equalsIgnoreCase(e.status()));
                    validateBatchBtn.setDisable(!anyDraft);
                });

        HBox actions = new HBox(10, importBtn, validateBatchBtn, deleteBtn);

        VBox body = new VBox(12);
        if (showWithHeader) {
            Label header = label(t("purchases.header"), "settings-section-title");
            Label hint = new Label(t("purchases.hint"));
            hint.setWrapText(true);
            hint.getStyleClass().add("settings-hint");
            body.getChildren().addAll(header, hint);
        }
        body.getChildren().addAll(filters, purchaseInvoicesTable, actions);
        VBox.setVgrow(purchaseInvoicesTable, Priority.ALWAYS);
        body.setPadding(new Insets(20));

        reloadPurchaseInvoices();
        return body;
    }

    private void reloadPurchaseInvoices() {
        if (purchaseInvoicesTable == null) return;
        Integer year = parseYearFilter(purchaseYearFilter.getValue());
        Task<java.util.List<com.benjagest.ui.model.PurchaseInvoiceEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.PurchaseInvoiceEntry> call() throws Exception {
                return purchasesApi.list(year, null, null);
            }
        };
        task.setOnSucceeded(e -> {
            purchaseInvoicesCache = task.getValue() == null
                    ? java.util.List.of() : task.getValue();
            applyClientSideFilters();
        });
        task.setOnFailed(e -> showError(t("purchases.list.fail.title"),
                t("purchases.list.fail.body")));
        start(task, "purchases-list");
    }

    /**
     * Aplica los filtros que se calculan en cliente sobre el caché:
     * trimestre (T1-T4) y proveedor (NIF o nombre, sub-string).
     * Llamado en cada cambio de combo/text sin tocar el backend.
     */
    private void applyClientSideFilters() {
        if (purchaseInvoicesTable == null) return;
        String q = purchaseQuarterFilter == null ? null : purchaseQuarterFilter.getValue();
        String supplier = purchaseSupplierFilter == null ? null
                : purchaseSupplierFilter.getText();
        String supplierLower = (supplier == null || supplier.isBlank())
                ? null : supplier.trim().toLowerCase();
        var filtered = new java.util.ArrayList<com.benjagest.ui.model.PurchaseInvoiceEntry>();
        for (var inv : purchaseInvoicesCache) {
            if (q != null && !q.equals(t("list.filter.all"))
                    && inv.invoiceDate() != null) {
                int month = inv.invoiceDate().getMonthValue();
                int quarter = (month - 1) / 3 + 1;
                if (!("T" + quarter).equals(q)) continue;
            }
            if (supplierLower != null) {
                String nif = inv.supplierNif() == null ? "" : inv.supplierNif().toLowerCase();
                String name = inv.supplierName() == null ? "" : inv.supplierName().toLowerCase();
                if (!nif.contains(supplierLower) && !name.contains(supplierLower)) continue;
            }
            filtered.add(inv);
        }
        purchaseInvoicesTable.getItems().setAll(filtered);
    }

    private Integer parseYearFilter(String text) {
        if (text == null || text.equals(t("list.filter.all"))) return null;
        try { return Integer.parseInt(text); } catch (Exception ex) { return null; }
    }

    /**
     * Valida en lote los gastos DRAFT seleccionados llamando al endpoint
     * {@code POST /api/purchases/invoices/validate-batch}. Tras la
     * respuesta refresca el listado y muestra un resumen al usuario.
     */
    private void validatePurchaseBatch() {
        if (purchaseInvoicesTable == null) return;
        var selected = purchaseInvoicesTable.getSelectionModel().getSelectedItems();
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (var e : selected) {
            if (e == null) continue;
            if ("DRAFT".equalsIgnoreCase(e.status())) ids.add(e.id());
        }
        if (ids.isEmpty()) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("purchases.confirm.validate_batch")
                        .replace("{n}", String.valueOf(ids.size())),
                ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText(t("purchases.action.validate_batch"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.YES) return;
            Task<String> task = new Task<>() {
                @Override protected String call() throws Exception {
                    return purchasesApi.validateBatch(ids);
                }
            };
            task.setOnSucceeded(e -> {
                String body = task.getValue();
                int posted = parseIntField(body, "posted");
                int failed = parseIntField(body, "failed");
                Alert info = new Alert(Alert.AlertType.INFORMATION,
                        t("purchases.validate_batch.result")
                                .replace("{p}", String.valueOf(posted))
                                .replace("{f}", String.valueOf(failed)));
                info.setHeaderText(t("purchases.action.validate_batch"));
                info.showAndWait();
                reloadPurchaseInvoices();
            });
            task.setOnFailed(e -> showError(t("purchases.validate_batch.fail.title"),
                    task.getException() == null ? "?" : task.getException().getMessage()));
            start(task, "purchases-validate-batch");
        });
    }

    /** Extrae un valor int del JSON {"posted":N,...} con regex simple. */
    private int parseIntField(String json, String key) {
        if (json == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private void deletePurchaseInvoice(com.benjagest.ui.model.PurchaseInvoiceEntry row) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("purchases.confirm.delete.body") + "\n\n"
                        + (row.supplierName() == null ? "" : row.supplierName() + "  ")
                        + (row.invoiceNumber() == null ? "" : "#" + row.invoiceNumber()),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("purchases.confirm.delete.title"));
        confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    purchasesApi.deleteInvoice(row.id());
                    return null;
                }
            };
            task.setOnSucceeded(e -> reloadPurchaseInvoices());
            task.setOnFailed(e -> showError(t("purchases.delete.fail.title"),
                    t("purchases.delete.fail.body")));
            start(task, "purchase-invoice-delete");
        });
    }

    private void importPurchasePdf() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(t("purchases.import.select_pdf"));
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("PDF", "*.pdf"));
        java.io.File file = chooser.showOpenDialog(root.getScene().getWindow());
        if (file == null) return;

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return pdfImportApi.uploadAndExtract(file);
            }
        };
        task.setOnSucceeded(ev -> {
            // El backend ahora devuelve SIEMPRE un array JSON, incluso
            // con una sola factura. Multi-factura (Amazon) → un diálogo
            // por cada elemento, en secuencia (showAndWait bloquea).
            List<String> invoices = splitJsonArrayObjects(task.getValue());
            if (invoices.isEmpty()) {
                showError(t("purchases.import.fail.title"),
                        t("purchases.import.fail.body"));
                return;
            }
            int n = invoices.size();
            for (int i = 0; i < n; i++) {
                String suffix = n > 1 ? "  (" + (i + 1) + "/" + n + ")" : "";
                showExtractionResult(invoices.get(i), file.getName() + suffix, i);
            }
        });
        task.setOnFailed(ev -> showError(t("purchases.import.fail.title"),
                t("purchases.import.fail.body")));
        start(task, "purchases-pdf-import");
    }

    /**
     * Parte un array JSON top-level "[{...},{...}]" en una lista de
     * objetos top-level. Tolera strings con llaves escapadas dentro.
     * No es un parser JSON completo — solo necesitamos separar objetos
     * en el primer nivel del array.
     */
    private List<String> splitJsonArrayObjects(String arrayJson) {
        List<String> out = new ArrayList<>();
        if (arrayJson == null) return out;
        String s = arrayJson.trim();
        if (s.isEmpty()) return out;
        // Si nos pasan un objeto suelto (no array), tratarlo como
        // lista de 1.
        if (s.startsWith("{")) {
            out.add(s);
            return out;
        }
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(s.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return out;
    }

    private void showExtractionResult(String json, String filename, int invoiceIndex) {
        // Diálogo EDITABLE: el usuario corrige los campos que el
        // extractor no acertó y puede guardar la corrección como
        // plantilla para futuras facturas del mismo NIF de proveedor.
        String supplier = extractField(json, "supplierName");
        String emitter = extractField(json, "emitterNif");
        String number = extractField(json, "invoiceNumber");
        String date = extractField(json, "invoiceDate");
        String base = extractNumber(json, "baseAmount");
        String vatPct = extractNumber(json, "vatPercent");
        String vatAmt = extractNumber(json, "vatAmount");
        String total = extractNumber(json, "totalAmount");
        String confidence = extractField(json, "confidence");
        String hash = extractField(json, "documentSha256");

        // Valores originales (para detectar si el usuario modificó)
        String origSupplier = supplier;
        String origVatPct = vatPct;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("purchases.import.result_prefix") + filename);
        ButtonType acceptBt = new ButtonType(t("purchases.import.action.accept"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType saveExpenseBt = new ButtonType(t("purchases.import.action.save_expense"),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType saveTemplateBt = new ButtonType(t("purchases.import.action.save_template"),
                ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(
                saveTemplateBt, saveExpenseBt, acceptBt);

        TextField supplierField = new TextField(supplier);
        TextField emitterField = new TextField(emitter);
        TextField numberField = new TextField(number);
        TextField dateField = new TextField(date);
        TextField baseField = new TextField(base);
        TextField vatPctField = new TextField(vatPct);
        TextField vatAmtField = new TextField(vatAmt);
        TextField totalField = new TextField(total);
        // Concepto del gasto — el asesor escribe qué fue ese gasto
        // (p. ej. "Material de oficina") y eso aparece en la línea 6xx
        // del asiento contable. Si lo deja vacío, se genera un fallback.
        TextField conceptField = new TextField();
        conceptField.setPromptText(t("purchases.import.field.concept_prompt"));
        for (TextField tf : new TextField[]{
                supplierField, emitterField, numberField, dateField,
                baseField, vatPctField, vatAmtField, totalField,
                conceptField}) {
            tf.setPrefColumnCount(32);
        }

        GridPane g = new GridPane();
        g.setHgap(12); g.setVgap(8);
        int row = 0;
        g.add(new Label(t("purchases.import.field.supplier")), 0, row); g.add(supplierField, 1, row++);
        g.add(new Label(t("purchases.import.field.emitter_nif")), 0, row); g.add(emitterField, 1, row++);
        g.add(new Label(t("purchases.import.field.number")), 0, row); g.add(numberField, 1, row++);
        g.add(new Label(t("purchases.import.field.date")), 0, row); g.add(dateField, 1, row++);
        g.add(new Label(t("purchases.import.field.base")), 0, row); g.add(baseField, 1, row++);
        g.add(new Label(t("purchases.import.field.vat_pct")), 0, row); g.add(vatPctField, 1, row++);
        g.add(new Label(t("purchases.import.field.vat_amount")), 0, row); g.add(vatAmtField, 1, row++);
        g.add(new Label(t("purchases.import.field.total")), 0, row); g.add(totalField, 1, row++);
        g.add(new Label(t("purchases.import.field.concept")), 0, row); g.add(conceptField, 1, row++);
        g.add(new Separator(), 0, row++, 2, 1);
        Label confLabel = new Label(confidenceLabel(confidence));
        confLabel.getStyleClass().add("settings-section-title");
        g.add(new Label(t("purchases.import.field.confidence")), 0, row); g.add(confLabel, 1, row++);

        Label tip = new Label(t("purchases.import.tip.edit"));
        tip.setWrapText(true);
        tip.getStyleClass().add("settings-hint");
        g.add(tip, 0, row++, 2, 1);

        if (hash != null && hash.length() > 16) {
            Label hashShort = new Label(t("purchases.import.field.hash") + " " + hash.substring(0, 16) + "…");
            hashShort.getStyleClass().add("settings-hint");
            g.add(hashShort, 0, row++, 2, 1);
        }

        installDialog(dialog, g);
        dialog.getDialogPane().setPrefWidth(620);

        // Botón "Guardar plantilla" deshabilitado si NI el extractor ni el
        // usuario han informado un NIF — sin ningún NIF no hay clave de
        // búsqueda posible.
        final String detectedNif = (emitter == null) ? "" : emitter.trim();
        Button saveBtn = (Button) dialog.getDialogPane().lookupButton(saveTemplateBt);
        Runnable refreshSaveEnabled = () -> {
            String corr = emitterField.getText() == null ? "" : emitterField.getText().trim();
            saveBtn.setDisable(detectedNif.isEmpty() && corr.isEmpty());
        };
        refreshSaveEnabled.run();
        emitterField.textProperty().addListener((o, ov, nv) -> refreshSaveEnabled.run());

        saveBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            ev.consume(); // no cerrar
            // Componer rules JSON con los valores fijos. CLAVE de diseño:
            // la plantilla se busca por el NIF que DETECTA el extractor
            // (aunque sea el equivocado, p. ej. el LU de Amazon en vez del
            // W español). Si el usuario corrige el NIF en el modal, ese
            // NIF corregido se guarda como regla `emitterNif` para que
            // futuras importaciones (que detectarán otra vez lo mismo)
            // muestren el NIF correcto. Así la corrección sobrevive al
            // hecho de que el extractor falle de forma consistente.
            StringBuilder rules = new StringBuilder("{");
            boolean first = true;
            String sName = supplierField.getText();
            if (sName != null && !sName.isBlank()) {
                rules.append("\"supplierName\":\"").append(sName.replace("\"", "\\\"")).append("\"");
                first = false;
            }
            String pct = vatPctField.getText();
            if (pct != null && !pct.isBlank() && !pct.equals("—")) {
                if (!first) rules.append(",");
                rules.append("\"vatPercent\":\"").append(pct.trim()).append("\"");
                first = false;
            }
            String correctedNif = emitterField.getText() == null ? "" : emitterField.getText().trim();
            if (!correctedNif.isEmpty() && !correctedNif.equalsIgnoreCase(detectedNif)) {
                if (!first) rules.append(",");
                rules.append("\"emitterNif\":\"").append(correctedNif.replace("\"", "\\\"")).append("\"");
            }
            rules.append("}");
            // Llave de búsqueda: el detectado si existe; si el extractor no
            // detectó nada, caemos al corregido como llave (caso raro).
            String lookupNif = detectedNif.isEmpty() ? correctedNif : detectedNif;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    pdfImportApi.saveTemplate(lookupNif, sName, rules.toString());
                    return null;
                }
            };
            task.setOnSucceeded(e -> showInfo(t("purchases.import.template_saved.title"),
                    t("purchases.import.template_saved.body") + " " + lookupNif));
            task.setOnFailed(e -> showError(t("purchases.import.template_failed.title"),
                    t("purchases.import.template_failed.body")));
            start(task, "pdf-template-save");
        });

        // Botón "Guardar gasto" — persiste la factura como purchase_invoice,
        // intenta crear asiento contable (si la empresa tiene PGC + fiscal
        // year), maneja dedup 409. Cierra el diálogo en éxito.
        Button saveExpense = (Button) dialog.getDialogPane().lookupButton(saveExpenseBt);
        saveExpense.getStyleClass().add("button-primary");
        saveExpense.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            ev.consume();
            var payload = new com.benjagest.ui.service.PurchaseInvoiceApiClient.SavePayload();
            payload.supplierNif = nullIfBlank(emitterField.getText());
            payload.supplierName = nullIfBlank(supplierField.getText());
            payload.invoiceNumber = nullIfBlank(numberField.getText());
            payload.invoiceDate = parseDateSafe(dateField.getText());
            payload.baseAmount = parseDecimalSafe(baseField.getText());
            payload.vatPercent = parseDecimalSafe(vatPctField.getText());
            payload.vatAmount = parseDecimalSafe(vatAmtField.getText());
            payload.totalAmount = parseDecimalSafe(totalField.getText());
            payload.documentSha256 = nullIfBlank(hash);
            payload.invoiceIndexInPdf = invoiceIndex;
            payload.concept = nullIfBlank(conceptField.getText());
            // Validación mínima: necesitamos al menos total o base+iva.
            if (payload.totalAmount == null
                    && (payload.baseAmount == null || payload.vatAmount == null)) {
                showError(t("purchases.save.fail.missing.title"),
                        t("purchases.save.fail.missing.body"));
                return;
            }
            Task<com.benjagest.ui.service.PurchaseInvoiceApiClient.SaveOutcome> task = new Task<>() {
                @Override
                protected com.benjagest.ui.service.PurchaseInvoiceApiClient.SaveOutcome call()
                        throws Exception {
                    return purchasesApi.save(payload);
                }
            };
            task.setOnSucceeded(e -> {
                var outcome = task.getValue();
                if (outcome.duplicate()) {
                    showInfo(t("purchases.save.duplicate.title"),
                            t("purchases.save.duplicate.body"));
                } else {
                    showInfo(t("purchases.save.ok.title"),
                            t("purchases.save.ok.body"));
                }
                dialog.setResult(saveExpenseBt);
                dialog.close();
            });
            task.setOnFailed(e -> showError(t("purchases.save.fail.title"),
                    t("purchases.save.fail.body")));
            start(task, "purchase-invoice-save");
        });

        dialog.showAndWait();
    }

    /** Parser seguro para BigDecimal; admite coma o punto decimal y "—". */
    private java.math.BigDecimal parseDecimalSafe(String text) {
        if (text == null || text.isBlank() || "—".equals(text.trim())) return null;
        try { return new java.math.BigDecimal(text.trim().replace(",", ".")); }
        catch (Exception ex) { return null; }
    }

    private String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String confidenceLabel(String code) {
        if (code == null || code.isBlank()) return "—";
        return switch (code) {
            case "HIGH" -> "✓ " + t("purchases.import.confidence.high");
            case "MEDIUM" -> "⚠ " + t("purchases.import.confidence.medium");
            case "LOW" -> "✗ " + t("purchases.import.confidence.low");
            default -> code;
        };
    }

    private String extractField(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : "—";
    }

    private String extractNumber(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*([0-9.\\-]+)")
                .matcher(json);
        return m.find() ? m.group(1) : "—";
    }

    // ===================================================================
    //  Pantalla Fichajes (Slice C4 — RD 8/2019)
    // ===================================================================

    private final com.benjagest.ui.service.TimeClockApiClient timeClockApi =
            new com.benjagest.ui.service.TimeClockApiClient();
    private TableView<com.benjagest.ui.model.TimeClockEntry> timeClockTable;

    private void showTimeClock() {
        // EMP-USER-MAP: el employeeId NO es el userId. Buscamos primero
        // la ficha en `employees` por (user_id, company_id). Si el
        // usuario no tiene ficha (típico de OWNERs de empresa que nunca
        // se dieron de alta como empleado), pintamos pantalla amigable
        // con instrucciones en lugar de explotar al primer fichaje.
        Task<com.benjagest.ui.service.TimeClockApiClient.MyEmployee> resolve = new Task<>() {
            @Override
            protected com.benjagest.ui.service.TimeClockApiClient.MyEmployee call() throws Exception {
                return timeClockApi.me();
            }
        };
        resolve.setOnSucceeded(ev -> renderTimeClock(resolve.getValue()));
        resolve.setOnFailed(ev -> {
            Throwable err = resolve.getException();
            if (err instanceof com.benjagest.ui.service.TimeClockApiClient.NotEnrolledException nee) {
                renderTimeClockNotEnrolled(nee.getMessage());
            } else {
                setCenterAnimated(scroll(errorPanel(t("timeclock.fail.title"))));
            }
        });
        start(resolve, "timeclock-resolve-employee");
    }

    /** Pinta una pantalla amigable cuando el usuario no tiene ficha de
     *  empleado en la empresa activa — no puede fichar hasta que el
     *  admin lo dé de alta en Personal > Empleados. */
    private void renderTimeClockNotEnrolled(String backendMsg) {
        Label header = label(t("timeclock.header"), "settings-section-title");
        Label icon = new Label("⚠");
        icon.setStyle("-fx-font-size: 48px;");
        Label title = new Label(t("timeclock.not_enrolled.title"));
        title.getStyleClass().add("settings-section-title");
        Label body = new Label(backendMsg != null && !backendMsg.isBlank()
                ? backendMsg
                : t("timeclock.not_enrolled.body"));
        body.setWrapText(true);
        body.getStyleClass().add("settings-hint");
        VBox notice = new VBox(12, icon, title, body);
        notice.setPadding(new Insets(40));
        notice.setAlignment(Pos.CENTER);
        VBox content = new VBox(16, header, notice);
        content.setPadding(new Insets(20));
        setCenterAnimated(scroll(content));
    }

    private void renderTimeClock(com.benjagest.ui.service.TimeClockApiClient.MyEmployee me) {
        String employeeId = me.employeeId();
        // Header explicativo: contexto legal RD 8/2019 y aclaración de
        // que el CSV emitido al fichar sirve para verificación pública.
        Label header = label(t("timeclock.header"), "settings-section-title");
        Label hint = new Label(t("timeclock.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        // Bloque grande con botones de fichaje. Tamaño visible para
        // que sirva también en kiosko (los empleados fichan rápido).
        Button inBtn = bigPunchButton(t("timeclock.action.in"), "fas-sign-in-alt", "IN", employeeId);
        Button outBtn = bigPunchButton(t("timeclock.action.out"), "fas-sign-out-alt", "OUT", employeeId);
        Button breakStartBtn = bigPunchButton(t("timeclock.action.break_start"), "fas-coffee", "BREAK_START", employeeId);
        Button breakEndBtn = bigPunchButton(t("timeclock.action.break_end"), "fas-utensils", "BREAK_END", employeeId);
        HBox punchRow = new HBox(12, inBtn, outBtn, breakStartBtn, breakEndBtn);

        // Listado últimos N fichajes del empleado.
        timeClockTable = new TableView<>();
        timeClockTable.getStyleClass().add("data-table");
        timeClockTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        timeClockTable.setPlaceholder(new Label(t("timeclock.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.TimeClockEntry, String> colWhen =
                new TableColumn<>(t("timeclock.col.when"));
        colWhen.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().eventTimeIso()));
        colWhen.setPrefWidth(200);
        colWhen.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TimeClockEntry, String> colType =
                new TableColumn<>(t("timeclock.col.type"));
        colType.setCellValueFactory(c -> new SimpleStringProperty(localizedPunchType(c.getValue().eventType())));
        colType.setPrefWidth(150);
        TableColumn<com.benjagest.ui.model.TimeClockEntry, String> colOrigin =
                new TableColumn<>(t("timeclock.col.origin"));
        colOrigin.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().origin()));
        colOrigin.setPrefWidth(100);
        TableColumn<com.benjagest.ui.model.TimeClockEntry, String> colStatus =
                new TableColumn<>(t("timeclock.col.status"));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status()));
        colStatus.setPrefWidth(110);
        timeClockTable.getColumns().addAll(java.util.List.of(colWhen, colType, colOrigin, colStatus));

        Button refresh = new Button(t("timeclock.action.refresh"));
        refresh.setGraphic(icon("fas-sync"));
        refresh.setOnAction(ev -> reloadTimeClock(employeeId));

        // Bloque de exportación para Inspección de Trabajo / Hacienda.
        // Defaults: trimestre actual completo (lo más habitual).
        Label exportTitle = label(t("timeclock.export.title"), "settings-section-title");
        Label exportHint = new Label(t("timeclock.export.hint"));
        exportHint.setWrapText(true);
        exportHint.getStyleClass().add("settings-hint");
        java.time.LocalDate today = java.time.LocalDate.now();
        int quarter = (today.getMonthValue() - 1) / 3;
        java.time.LocalDate quarterStart = java.time.LocalDate.of(today.getYear(), quarter * 3 + 1, 1);
        java.time.LocalDate quarterEnd = quarterStart.plusMonths(3).minusDays(1);
        DatePicker fromPicker = new DatePicker(quarterStart);
        DatePicker toPicker = new DatePicker(quarterEnd);
        Label fromLbl = new Label(t("timeclock.export.from"));
        Label toLbl = new Label(t("timeclock.export.to"));
        Button exportPdfBtn = new Button(t("timeclock.export.pdf"));
        exportPdfBtn.setGraphic(icon("fas-file-pdf"));
        exportPdfBtn.getStyleClass().add("button-primary");
        exportPdfBtn.setOnAction(ev -> downloadTimeClockExport("pdf",
                fromPicker.getValue(), toPicker.getValue(), employeeId));
        Button exportCsvBtn = new Button(t("timeclock.export.csv"));
        exportCsvBtn.setGraphic(icon("fas-file-csv"));
        exportCsvBtn.setOnAction(ev -> downloadTimeClockExport("csv",
                fromPicker.getValue(), toPicker.getValue(), employeeId));
        HBox exportRow = new HBox(8, fromLbl, fromPicker, toLbl, toPicker,
                exportPdfBtn, exportCsvBtn);
        exportRow.setAlignment(Pos.CENTER_LEFT);
        VBox exportBlock = new VBox(8, exportTitle, exportHint, exportRow);

        VBox body = new VBox(16, header, hint, punchRow, refresh, timeClockTable,
                new Separator(), exportBlock);
        body.setPadding(new Insets(20));

        setCenterAnimated(scroll(body));
        reloadTimeClock(employeeId);
    }

    /** Descarga el export de fichajes y ofrece guardarlo. */
    private void downloadTimeClockExport(String format,
                                          java.time.LocalDate from, java.time.LocalDate to,
                                          String employeeId) {
        if (from == null || to == null || from.isAfter(to)) {
            showError(t("timeclock.export.fail.range.title"),
                    t("timeclock.export.fail.range.body"));
            return;
        }
        Task<byte[]> task = new Task<>() {
            @Override protected byte[] call() throws Exception {
                return "pdf".equals(format)
                        ? timeClockApi.exportPdf(from.toString(), to.toString(), employeeId)
                        : timeClockApi.exportCsv(from.toString(), to.toString(), employeeId);
            }
        };
        task.setOnSucceeded(ev -> {
            byte[] body = task.getValue();
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setInitialFileName("fichajes-" + from + "_" + to + "." + format);
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                    format.toUpperCase(), "*." + format));
            java.io.File target = fc.showSaveDialog(root.getScene().getWindow());
            if (target == null) return;
            try {
                java.nio.file.Files.write(target.toPath(), body);
                showInfo(t("timeclock.export.ok.title"),
                        t("timeclock.export.ok.body") + "\n" + target.getAbsolutePath());
            } catch (java.io.IOException ex) {
                showError(t("timeclock.export.fail.write.title"), ex.getMessage());
            }
        });
        task.setOnFailed(ev -> showError(t("timeclock.export.fail.title"),
                t("timeclock.export.fail.body")));
        start(task, "timeclock-export-" + format);
    }

    private Button bigPunchButton(String text, String iconName, String eventType, String employeeId) {
        Button btn = new Button(text);
        btn.setGraphic(icon(iconName));
        btn.getStyleClass().add("invoice-validate-action");
        btn.setMinHeight(50);
        btn.setMinWidth(160);
        btn.setOnAction(ev -> punch(employeeId, eventType));
        return btn;
    }

    private void punch(String employeeId, String eventType) {
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return timeClockApi.punch(employeeId, eventType);
            }
        };
        task.setOnSucceeded(ev -> {
            String csv = task.getValue();
            // Mostramos el CSV en un dialog con TextField copiable para
            // que el trabajador lo guarde si lo necesita (justificante).
            javafx.scene.control.TextField field = new javafx.scene.control.TextField(csv);
            field.setEditable(false);
            field.setPrefColumnCount(20);
            Alert ok = new Alert(Alert.AlertType.INFORMATION);
            ok.setHeaderText(t("timeclock.success.title"));
            VBox content = new VBox(8,
                    new Label(t("timeclock.success.csv_label")),
                    field);
            content.setPadding(new Insets(8));
            ok.getDialogPane().setContent(content);
            ok.showAndWait();
            reloadTimeClock(employeeId);
        });
        task.setOnFailed(ev -> showError(t("timeclock.fail.title"), t("timeclock.fail.body")));
        start(task, "timeclock-punch");
    }

    private void reloadTimeClock(String employeeId) {
        Task<java.util.List<com.benjagest.ui.model.TimeClockEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.TimeClockEntry> call() throws Exception {
                return timeClockApi.recent(employeeId, 50);
            }
        };
        task.setOnSucceeded(ev -> {
            if (timeClockTable != null) {
                timeClockTable.setItems(FXCollections.observableArrayList(task.getValue()));
            }
        });
        task.setOnFailed(ev -> {
            if (timeClockTable != null) timeClockTable.getItems().clear();
        });
        start(task, "timeclock-reload");
    }

    private String localizedPunchType(String code) {
        if (code == null) return "";
        return switch (code) {
            case "IN" -> t("timeclock.type.in");
            case "OUT" -> t("timeclock.type.out");
            case "BREAK_START" -> t("timeclock.type.break_start");
            case "BREAK_END" -> t("timeclock.type.break_end");
            default -> code;
        };
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
        Tab ownersTab = new Tab(t("settings.tab.owners"), settingsOwnersTab());
        ownersTab.setGraphic(icon("fas-users"));
        Tab emailTab = new Tab(t("settings.tab.email"), settingsEmailTab(bundle.email()));
        emailTab.setGraphic(icon("fas-envelope"));
        Tab modulesTab = new Tab(t("settings.tab.modules"), settingsModulesTab(bundle.modules()));
        modulesTab.setGraphic(icon("fas-cubes"));
        Tab credentialsTab = new Tab(t("settings.tab.credentials"), settingsCredentialsTab());
        credentialsTab.setGraphic(icon("fas-key"));
        Tab certificateTab = new Tab(t("settings.tab.certificate"), settingsCertificateTab());
        certificateTab.setGraphic(icon("fas-certificate"));
        Tab auditTab = new Tab(t("settings.tab.audit"), settingsAuditTab());
        auditTab.setGraphic(icon("fas-shield-alt"));

        // "Mi asesoría" solo tiene sentido para empresas CLIENT — una
        // asesoría no necesita otra asesoría que la asesore.
        tabs.getTabs().addAll(companyTab, ownersTab, emailTab, modulesTab,
                credentialsTab, certificateTab);
        if (appMode != AppMode.ADVISORY) {
            Tab advisoryTab = new Tab(t("settings.tab.my_advisory"), settingsMyAdvisoryTab());
            advisoryTab.setGraphic(icon("fas-handshake"));
            tabs.getTabs().add(advisoryTab);
        }
        tabs.getTabs().add(auditTab);
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

        // Tras V22 (consolidacion 2026-06-04), los textos "de factura"
        // (pie, condiciones legales, exencion IVA, IVA reducido,
        // rectificativa) se editan SOLO en Facturacion -> Configuracion
        // -> Textos legales. Aqui solo quedan los datos administrativos
        // (IBAN para domiciliacion, registro mercantil para
        // identificacion juridica).
        GridPane billingGrid = formGrid();
        addFormRow(billingGrid, 0, t("settings.company.field.iban"), iban);
        addFormRow(billingGrid, 1, t("settings.company.field.registry"), registry);
        Label billingNote = new Label(t("settings.company.billing_note"));
        billingNote.setWrapText(true);
        billingNote.getStyleClass().add("settings-hint");

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
                registry.getText()
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
                billingGrid,
                billingNote
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

    // ===================================================================
    //  CERT-IMPORT (2026-06-05) — pestaña Certificado en Configuración
    // ===================================================================

    private TableView<com.benjagest.ui.model.CertificateSummaryEntry> certsTable;

    private Node settingsCertificateTab() {
        Label sectionTitle = label(t("settings.cert.section"), "settings-section-title");
        Label hint = new Label(t("settings.cert.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        certsTable = new TableView<>();
        certsTable.getStyleClass().add("data-table");
        certsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        certsTable.setPlaceholder(new Label(t("settings.cert.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.CertificateSummaryEntry, String> colAlias =
                new TableColumn<>(t("settings.cert.col.alias"));
        colAlias.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().alias()));
        colAlias.setPrefWidth(180);

        TableColumn<com.benjagest.ui.model.CertificateSummaryEntry, String> colType =
                new TableColumn<>(t("settings.cert.col.type"));
        colType.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().certificateType()));
        colType.setPrefWidth(160);

        TableColumn<com.benjagest.ui.model.CertificateSummaryEntry, String> colNif =
                new TableColumn<>(t("settings.cert.col.nif"));
        colNif.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().subjectTaxIdentifier() == null ? "—" : c.getValue().subjectTaxIdentifier()));
        colNif.setPrefWidth(110);

        TableColumn<com.benjagest.ui.model.CertificateSummaryEntry, String> colSubject =
                new TableColumn<>(t("settings.cert.col.subject"));
        colSubject.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().subjectName() == null ? "—" : c.getValue().subjectName()));

        TableColumn<com.benjagest.ui.model.CertificateSummaryEntry, String> colValidUntil =
                new TableColumn<>(t("settings.cert.col.valid_until"));
        colValidUntil.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().validTo() == null ? "—"
                        : c.getValue().validTo().atZone(java.time.ZoneId.systemDefault())
                              .toLocalDate().toString()));
        colValidUntil.setPrefWidth(120);

        TableColumn<com.benjagest.ui.model.CertificateSummaryEntry, String> colUploadedBy =
                new TableColumn<>(t("settings.cert.col.uploaded_by"));
        colUploadedBy.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().uploadedByAdvisory()
                        ? t("settings.cert.uploaded_by.advisory")
                        : t("settings.cert.uploaded_by.self")));
        colUploadedBy.setPrefWidth(180);

        TableColumn<com.benjagest.ui.model.CertificateSummaryEntry, Void> colActions =
                new TableColumn<>(t("settings.cert.col.actions"));
        colActions.setPrefWidth(110);
        colActions.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            private final Button del = new Button(t("settings.cert.action.delete"));
            {
                del.getStyleClass().add("button-danger-outline");
                del.setOnAction(ev -> {
                    com.benjagest.ui.model.CertificateSummaryEntry row = getTableRow().getItem();
                    if (row != null) deleteCertificate(row);
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : del);
            }
        });

        certsTable.getColumns().setAll(colAlias, colType, colNif, colSubject,
                colValidUntil, colUploadedBy, colActions);

        Button uploadBtn = new Button(t("settings.cert.action.upload"));
        uploadBtn.getStyleClass().add("button-primary");
        uploadBtn.setOnAction(ev -> showCertificateUploadDialog());

        Button refreshBtn = new Button(t("settings.cert.action.refresh"));
        refreshBtn.setOnAction(ev -> reloadCertificates());

        HBox actions = new HBox(10, uploadBtn, refreshBtn);

        // tabLayout: header + body scroll + footer fijo. La tabla con el
        // listado puede crecer mucho en clientes con varios .p12; sin
        // este patron los botones del pie quedan fuera de pantalla en
        // portatil. Mismo enfoque que las otras pestanas de Configuracion.
        VBox header = new VBox(8, sectionTitle, hint);
        VBox body = new VBox(12, certsTable);
        VBox.setVgrow(certsTable, Priority.ALWAYS);

        reloadCertificates();
        return tabLayout(header, body, actions);
    }

    private void reloadCertificates() {
        if (certsTable == null) return;
        Task<java.util.List<com.benjagest.ui.model.CertificateSummaryEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.CertificateSummaryEntry> call() throws Exception {
                return certificateApi.list();
            }
        };
        task.setOnSucceeded(e -> certsTable.getItems().setAll(task.getValue()));
        task.setOnFailed(e -> showError(t("settings.cert.fail.list.title"),
                t("settings.cert.fail.list.body")));
        start(task, "certificates-list");
    }

    private void deleteCertificate(com.benjagest.ui.model.CertificateSummaryEntry row) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("settings.cert.confirm.delete.body") + "\n\n" + row.alias(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("settings.cert.confirm.delete.title"));
        confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    certificateApi.delete(row.id());
                    return null;
                }
            };
            task.setOnSucceeded(e -> reloadCertificates());
            task.setOnFailed(e -> showError(t("settings.cert.fail.delete.title"),
                    t("settings.cert.fail.delete.body")));
            start(task, "certificate-delete");
        });
    }

    private void showCertificateUploadDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("settings.cert.upload.title"));
        ButtonType saveBt = new ButtonType(t("settings.cert.upload.save"),
                ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        Label fileLabel = new Label(t("settings.cert.upload.no_file"));
        fileLabel.getStyleClass().add("settings-hint");
        Button chooseBtn = new Button(t("settings.cert.upload.choose"));
        // Buffer del .p12 cargado (en base64 cuando se selecciona)
        final String[] base64Holder = new String[1];

        PasswordField passwordField = new PasswordField();
        passwordField.setPrefColumnCount(28);

        TextField aliasField = new TextField();
        aliasField.setPrefColumnCount(28);
        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll(
                "FNMT_PERSONA_FISICA", "FNMT_REPRESENTANTE",
                "CAMERFIRMA", "IZENPE", "ANCERT", "SELLO_EMPRESA", "OTRO");
        typeCombo.getSelectionModel().select("OTRO");
        TextField subjectField = new TextField();
        subjectField.setPrefColumnCount(28);
        TextField nifField = new TextField();
        nifField.setPrefColumnCount(16);
        TextField validFromField = new TextField();
        validFromField.setPromptText("AAAA-MM-DD");
        TextField validToField = new TextField();
        validToField.setPromptText("AAAA-MM-DD");

        Button inspectBtn = new Button(t("settings.cert.upload.inspect"));
        inspectBtn.setDisable(true); // habilita cuando hay archivo

        chooseBtn.setOnAction(ev -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle(t("settings.cert.upload.choose"));
            chooser.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter("PKCS#12 (.p12, .pfx)", "*.p12", "*.pfx"));
            java.io.File file = chooser.showOpenDialog(root.getScene().getWindow());
            if (file == null) return;
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                base64Holder[0] = java.util.Base64.getEncoder().encodeToString(bytes);
                fileLabel.setText(file.getName() + " (" + (bytes.length / 1024) + " KB)");
                inspectBtn.setDisable(false);
                if (aliasField.getText().isBlank()) {
                    String name = file.getName().replaceAll("(?i)\\.(p12|pfx)$", "");
                    aliasField.setText(name);
                }
            } catch (Exception ex) {
                showError(t("settings.cert.fail.read_file.title"),
                        t("settings.cert.fail.read_file.body"));
            }
        });

        inspectBtn.setOnAction(ev -> {
            if (base64Holder[0] == null) return;
            String pwd = passwordField.getText();
            Task<com.benjagest.ui.model.CertificateInspectInfo> task = new Task<>() {
                @Override
                protected com.benjagest.ui.model.CertificateInspectInfo call() throws Exception {
                    return certificateApi.inspect(base64Holder[0], pwd);
                }
            };
            task.setOnSucceeded(e -> {
                var info = task.getValue();
                if (info.subjectName() != null) subjectField.setText(info.subjectName());
                if (info.subjectTaxIdentifier() != null) nifField.setText(info.subjectTaxIdentifier());
                if (info.certificateTypeGuess() != null) typeCombo.getSelectionModel().select(info.certificateTypeGuess());
                if (info.validFrom() != null) {
                    validFromField.setText(info.validFrom().atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate().toString());
                }
                if (info.validTo() != null) {
                    validToField.setText(info.validTo().atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate().toString());
                }
                if (aliasField.getText().isBlank() && info.subjectName() != null) {
                    aliasField.setText(info.subjectName());
                }
            });
            task.setOnFailed(e -> showError(t("settings.cert.fail.inspect.title"),
                    t("settings.cert.fail.inspect.body")));
            start(task, "certificate-inspect");
        });

        GridPane g = new GridPane();
        g.setHgap(12); g.setVgap(10);
        int r = 0;
        g.add(new Label(t("settings.cert.upload.file")), 0, r);
        HBox fileBox = new HBox(8, chooseBtn, fileLabel);
        g.add(fileBox, 1, r++);
        g.add(new Label(t("settings.cert.upload.password")), 0, r); g.add(passwordField, 1, r++);
        g.add(new Label(""), 0, r); g.add(inspectBtn, 1, r++);
        g.add(new Separator(), 0, r++, 2, 1);
        g.add(new Label(t("settings.cert.upload.alias")), 0, r); g.add(aliasField, 1, r++);
        g.add(new Label(t("settings.cert.upload.type")), 0, r); g.add(typeCombo, 1, r++);
        g.add(new Label(t("settings.cert.upload.subject")), 0, r); g.add(subjectField, 1, r++);
        g.add(new Label(t("settings.cert.upload.nif")), 0, r); g.add(nifField, 1, r++);
        g.add(new Label(t("settings.cert.upload.valid_from")), 0, r); g.add(validFromField, 1, r++);
        g.add(new Label(t("settings.cert.upload.valid_to")), 0, r); g.add(validToField, 1, r++);
        Label tip = new Label(t("settings.cert.upload.tip"));
        tip.setWrapText(true);
        tip.getStyleClass().add("settings-hint");
        g.add(tip, 0, r++, 2, 1);

        installDialog(dialog, g);
        dialog.getDialogPane().setPrefWidth(580);

        Button save = (Button) dialog.getDialogPane().lookupButton(saveBt);
        save.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            ev.consume();
            if (base64Holder[0] == null) {
                showError(t("settings.cert.fail.no_file.title"),
                        t("settings.cert.fail.no_file.body"));
                return;
            }
            if (aliasField.getText().isBlank()) {
                showError(t("settings.cert.fail.no_alias.title"),
                        t("settings.cert.fail.no_alias.body"));
                return;
            }
            java.time.Instant vFrom = parseDateInstant(validFromField.getText());
            java.time.Instant vTo = parseDateInstant(validToField.getText());
            String pwd = passwordField.getText();
            String b64 = base64Holder[0];
            String alias = aliasField.getText().trim();
            String type = typeCombo.getValue() == null ? "OTRO" : typeCombo.getValue();
            String subject = subjectField.getText().trim();
            String nif = nifField.getText().trim();
            Task<com.benjagest.ui.model.CertificateSummaryEntry> task = new Task<>() {
                @Override
                protected com.benjagest.ui.model.CertificateSummaryEntry call() throws Exception {
                    return certificateApi.upload(alias, type,
                            subject.isBlank() ? null : subject,
                            nif.isBlank() ? null : nif,
                            pwd.isBlank() ? null : pwd,
                            b64, vFrom, vTo);
                }
            };
            task.setOnSucceeded(e -> {
                dialog.setResult(saveBt);
                dialog.close();
                reloadCertificates();
            });
            task.setOnFailed(e -> showError(t("settings.cert.fail.upload.title"),
                    t("settings.cert.fail.upload.body")));
            start(task, "certificate-upload");
        });

        dialog.showAndWait();
    }

    private java.time.Instant parseDateInstant(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(text.trim())
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Pestaña "Mi asesoría" del empresario: muestra la asesoría a la
     * que está vinculado (si la hay) + botón Desvincular. Si no hay
     * vínculo, muestra hint explicando cómo aceptar una invitación
     * y ofrece un campo para pegar el token directamente (útil
     * cuando la invitación llegó pero el banner del Home no la
     * recogió, o cuando el empresario quiere re-vincularse).
     */
    private Node settingsMyAdvisoryTab() {
        Label sectionTitle = label(t("settings.my_advisory.section"), "settings-section-title");
        Label hint = new Label(t("settings.my_advisory.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        VBox infoSlot = new VBox(8);
        infoSlot.setPadding(new Insets(8, 0, 0, 0));

        // Bloque "Pegar token" — siempre visible para que el empresario
        // pueda vincularse manualmente con cualquier token que le
        // pasen, independientemente de si tiene una asesoría vinculada
        // (en ese caso, debe desvincularse primero, claro).
        Label tokenTitle = label(t("settings.my_advisory.paste_token.title"), "settings-section-title");
        Label tokenHint = new Label(t("settings.my_advisory.paste_token.hint"));
        tokenHint.setWrapText(true);
        tokenHint.getStyleClass().add("settings-hint");
        TextField tokenField = new TextField();
        tokenField.setPromptText(t("settings.my_advisory.paste_token.prompt"));
        tokenField.setPrefColumnCount(40);
        Button acceptTokenBtn = new Button(t("settings.my_advisory.paste_token.accept"));
        acceptTokenBtn.setGraphic(icon("fas-link"));
        acceptTokenBtn.getStyleClass().add("button-primary");
        HBox tokenRow = new HBox(8, tokenField, acceptTokenBtn);
        tokenRow.setAlignment(Pos.CENTER_LEFT);
        VBox tokenBlock = new VBox(8, tokenTitle, tokenHint, tokenRow);
        tokenBlock.setPadding(new Insets(12, 0, 0, 0));

        Button unlinkBtn = new Button(t("settings.my_advisory.action.unlink"));
        unlinkBtn.setGraphic(icon("fas-unlink"));
        unlinkBtn.getStyleClass().add("button-danger-outline");
        unlinkBtn.setDisable(true);

        Runnable reload = () -> {
            infoSlot.getChildren().clear();
            unlinkBtn.setDisable(true);
            Task<com.benjagest.ui.service.AdvisoryInvitationApiClient.LinkedAdvisory> task = new Task<>() {
                @Override
                protected com.benjagest.ui.service.AdvisoryInvitationApiClient.LinkedAdvisory
                        call() throws Exception {
                    return invitationsApi.getLinkedAdvisory();
                }
            };
            task.setOnSucceeded(e -> {
                var link = task.getValue();
                if (link == null) {
                    Label empty = new Label(t("settings.my_advisory.empty"));
                    empty.setWrapText(true);
                    empty.getStyleClass().add("settings-hint");
                    infoSlot.getChildren().add(empty);
                    unlinkBtn.setDisable(true);
                    return;
                }
                GridPane g = new GridPane();
                g.setHgap(12); g.setVgap(6);
                int r = 0;
                g.add(new Label(t("settings.my_advisory.field.legal_name")), 0, r);
                g.add(new Label(link.legalName() == null ? "—" : link.legalName()), 1, r++);
                if (link.tradeName() != null && !link.tradeName().isBlank()) {
                    g.add(new Label(t("settings.my_advisory.field.trade_name")), 0, r);
                    g.add(new Label(link.tradeName()), 1, r++);
                }
                g.add(new Label(t("settings.my_advisory.field.nif")), 0, r);
                g.add(new Label(link.taxIdentifier() == null ? "—" : link.taxIdentifier()), 1, r++);
                g.add(new Label(t("settings.my_advisory.field.email")), 0, r);
                g.add(new Label(link.email() == null ? "—" : link.email()), 1, r++);
                infoSlot.getChildren().add(g);
                unlinkBtn.setDisable(false);
            });
            task.setOnFailed(e -> showError(t("settings.my_advisory.fail.load.title"),
                    t("settings.my_advisory.fail.load.body")));
            start(task, "my-advisory-load");
        };

        unlinkBtn.setOnAction(ev -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    t("settings.my_advisory.confirm.unlink.body"),
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText(t("settings.my_advisory.confirm.unlink.title"));
            confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
                Task<Void> task = new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        invitationsApi.unlink();
                        return null;
                    }
                };
                task.setOnSucceeded(e -> {
                    // Refresco local inmediato: la pestaña "Mi asesoría"
                    // tiene que mostrar YA que el vínculo desapareció,
                    // sin esperar al tick de 5s.
                    reload.run();
                    pollPendingInvitations();
                });
                task.setOnFailed(e -> showError(t("settings.my_advisory.fail.unlink.title"),
                        t("settings.my_advisory.fail.unlink.body")));
                start(task, "my-advisory-unlink");
            });
        });

        acceptTokenBtn.setOnAction(ev -> {
            String token = tokenField.getText();
            if (token == null || token.isBlank()) {
                showError(t("settings.my_advisory.paste_token.fail.empty.title"),
                        t("settings.my_advisory.paste_token.fail.empty.body"));
                return;
            }
            Task<com.benjagest.ui.model.AdvisoryInvitationEntry> task = new Task<>() {
                @Override
                protected com.benjagest.ui.model.AdvisoryInvitationEntry call() throws Exception {
                    return invitationsApi.accept(token.trim());
                }
            };
            task.setOnSucceeded(e -> {
                showInfo(t("advisory.invitation.accept.ok.title"),
                        t("advisory.invitation.accept.ok.body"));
                tokenField.clear();
                reload.run();
                pollPendingInvitations();
            });
            task.setOnFailed(e -> showError(t("advisory.invitation.accept.fail.title"),
                    t("advisory.invitation.accept.fail.body")));
            start(task, "advisory-invitation-accept-by-token");
        });

        HBox actions = new HBox(8, unlinkBtn);
        VBox header = new VBox(8, sectionTitle, hint);
        VBox body = new VBox(12, infoSlot, new Separator(), tokenBlock);

        reload.run();
        return tabLayout(header, body, actions);
    }

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
        colWhen.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<AuditEvent, String> colType = new TableColumn<>(t("settings.audit.col.type"));
        colType.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().eventType()));
        colType.setPrefWidth(150);
        TableColumn<AuditEvent, String> colResult = new TableColumn<>(t("settings.audit.col.result"));
        colResult.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().result()));
        colResult.setPrefWidth(80);
        TableColumn<AuditEvent, String> colSeq = new TableColumn<>(t("settings.audit.col.seq"));
        colSeq.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().sequenceNumber() == null ? "—" : c.getValue().sequenceNumber().toString()));
        colSeq.setComparator(NUMERIC_STRING_COMPARATOR);
        colSeq.setPrefWidth(60);
        TableColumn<AuditEvent, String> colUser = new TableColumn<>(t("settings.audit.col.user"));
        // Muestra el display_name humano resuelto en backend. Si el
        // user fue borrado, cae a userId.
        colUser.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().userName() == null || c.getValue().userName().isBlank()
                        ? shortId(c.getValue().userId())
                        : c.getValue().userName()));
        colUser.setPrefWidth(160);
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
        TableColumn<AuditEvent, String> colHash = new TableColumn<>(t("settings.audit.col.hash"));
        colHash.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().eventHash() == null ? "—"
                        : c.getValue().eventHash().substring(0,
                                Math.min(12, c.getValue().eventHash().length()))));
        colHash.setPrefWidth(110);
        table.getColumns().addAll(List.of(colSeq, colWhen, colType, colResult, colUser, colEntity, colIp, colDetails, colHash));

        Button refresh = new Button(t("settings.audit.btn.refresh"));
        refresh.setGraphic(icon("fas-sync-alt"));
        refresh.setOnAction(event -> loadAuditEvents(table, typeFilter.getValue()));
        typeFilter.setOnAction(event -> loadAuditEvents(table, typeFilter.getValue()));

        Button verifyBtn = new Button(t("settings.audit.btn.verify"));
        verifyBtn.setGraphic(icon("fas-shield-alt"));
        verifyBtn.setOnAction(ev -> verifyAuditChain());

        loadAuditEvents(table, typeFilter.getValue());

        HBox filterRow = new HBox(10, label(t("settings.audit.filter.label"), "form-label"), typeFilter);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        // Bloque "Exportar para Inspección/Hacienda": PDF + CSV
        // verificable del registro completo en un rango. Defaults al
        // trimestre fiscal actual (lo más común para requerimientos).
        java.time.LocalDate today = java.time.LocalDate.now();
        int quarter = (today.getMonthValue() - 1) / 3;
        java.time.LocalDate quarterStart = java.time.LocalDate.of(today.getYear(), quarter * 3 + 1, 1);
        java.time.LocalDate quarterEnd = quarterStart.plusMonths(3).minusDays(1);
        DatePicker fromPicker = new DatePicker(quarterStart);
        DatePicker toPicker = new DatePicker(quarterEnd);
        Button exportPdfBtn = new Button(t("settings.audit.export.pdf"));
        exportPdfBtn.setGraphic(icon("fas-file-pdf"));
        exportPdfBtn.getStyleClass().add("button-primary");
        exportPdfBtn.setOnAction(ev -> downloadAuditExport("pdf",
                fromPicker.getValue(), toPicker.getValue(), typeFilter.getValue()));
        Button exportCsvBtn = new Button(t("settings.audit.export.csv"));
        exportCsvBtn.setGraphic(icon("fas-file-csv"));
        exportCsvBtn.setOnAction(ev -> downloadAuditExport("csv",
                fromPicker.getValue(), toPicker.getValue(), typeFilter.getValue()));
        Label exportTitle = label(t("settings.audit.export.title"), "settings-section-title");
        Label exportHint = new Label(t("settings.audit.export.hint"));
        exportHint.setWrapText(true);
        exportHint.getStyleClass().add("settings-hint");
        HBox exportRow = new HBox(8,
                label(t("settings.audit.export.from"), "form-label"), fromPicker,
                label(t("settings.audit.export.to"), "form-label"), toPicker,
                exportPdfBtn, exportCsvBtn);
        exportRow.setAlignment(Pos.CENTER_LEFT);
        VBox exportBlock = new VBox(8, new Separator(), exportTitle, exportHint, exportRow);

        HBox actions = new HBox(8, refresh, verifyBtn);
        actions.getStyleClass().add("settings-actions");

        VBox header = new VBox(8, sectionTitle, hint, filterRow);
        VBox content = new VBox(16, table, exportBlock);
        VBox.setVgrow(table, Priority.ALWAYS);
        return tabLayout(header, content, actions);
    }

    /** Llama al endpoint /verify y muestra el resultado en un dialog. */
    private void verifyAuditChain() {
        Task<com.benjagest.ui.service.SettingsApiClient.ChainVerification> task = new Task<>() {
            @Override
            protected com.benjagest.ui.service.SettingsApiClient.ChainVerification call() throws Exception {
                return settingsApiClient.verifyAuditChain();
            }
        };
        task.setOnSucceeded(ev -> {
            var v = task.getValue();
            if (v.valid()) {
                showInfo(t("settings.audit.verify.ok.title"),
                        t("settings.audit.verify.ok.body") + "\n"
                                + v.count() + " " + t("settings.audit.verify.events"));
            } else {
                showError(t("settings.audit.verify.fail.title"),
                        t("settings.audit.verify.fail.body") + "\n"
                                + (v.brokenAtId() == null ? "" : "ID: " + v.brokenAtId() + "\n")
                                + (v.message() == null ? "" : v.message()));
            }
        });
        task.setOnFailed(ev -> showError(t("settings.audit.verify.fail.title"),
                t("settings.audit.verify.fail.body")));
        start(task, "audit-verify-chain");
    }

    /** Descarga el export de auditoría y guarda con FileChooser. */
    private void downloadAuditExport(String format,
                                      java.time.LocalDate from, java.time.LocalDate to,
                                      String selectedTypeFilter) {
        if (from == null || to == null || from.isAfter(to)) {
            showError(t("settings.audit.export.fail.range.title"),
                    t("settings.audit.export.fail.range.body"));
            return;
        }
        // El backend acepta eventTypePrefix (no exact match). Solo lo
        // mandamos si el usuario eligió un tipo concreto del combo.
        String filter = selectedTypeFilter;
        if (filter != null && ("(todos)".equals(filter) || "(all)".equals(filter)
                || t("list.filter.all").equals(filter))) {
            filter = null;
        }
        String prefix = filter;
        Task<byte[]> task = new Task<>() {
            @Override protected byte[] call() throws Exception {
                return settingsApiClient.exportAuditEvents(format,
                        from.toString(), to.toString(), prefix);
            }
        };
        task.setOnSucceeded(ev -> {
            byte[] body = task.getValue();
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setInitialFileName("auditoria-" + from + "_" + to + "." + format);
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                    format.toUpperCase(), "*." + format));
            java.io.File target = fc.showSaveDialog(root.getScene().getWindow());
            if (target == null) return;
            try {
                java.nio.file.Files.write(target.toPath(), body);
                showInfo(t("settings.audit.export.ok.title"),
                        t("settings.audit.export.ok.body") + "\n" + target.getAbsolutePath());
            } catch (java.io.IOException ex) {
                showError(t("settings.audit.export.fail.write.title"), ex.getMessage());
            }
        });
        task.setOnFailed(ev -> showError(t("settings.audit.export.fail.title"),
                t("settings.audit.export.fail.body")));
        start(task, "audit-export-" + format);
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
    //  ALTA — Pestana Titulares (Configuracion -> Titulares)
    //
    //  Modelo 200 (Impuesto Sociedades) y SS exigen identificar a los
    //  administradores y socios con su rol y % participacion. Esta pestana
    //  es CRUD sobre /api/settings/owners y resuelve esa pata de los
    //  modelos AEAT que dependen de quien es quien en la empresa.
    // ===================================================================

    private final AltaApiClient altaApiClient = new AltaApiClient();
    private TableView<com.benjagest.ui.model.CompanyOwnerEntry> ownersTable;
    private TableView<com.benjagest.ui.model.ExternalCredentialEntry> credentialsTable;
    private TableView<com.benjagest.ui.model.CertificateUsageEntry> certUsageTable;

    private Node settingsOwnersTab() {
        Label section = label(t("settings.owners.section"), "settings-section-title");
        Label hint = new Label(t("settings.owners.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        ownersTable = new TableView<>();
        ownersTable.getStyleClass().add("data-table");
        ownersTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        ownersTable.setPlaceholder(new Label(t("settings.owners.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.CompanyOwnerEntry, String> colName =
                new TableColumn<>(t("settings.owners.col.name"));
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().fullName()));
        colName.setPrefWidth(200);
        TableColumn<com.benjagest.ui.model.CompanyOwnerEntry, String> colNif =
                new TableColumn<>(t("settings.owners.col.nif"));
        colNif.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().taxIdentifier()));
        colNif.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.CompanyOwnerEntry, String> colRole =
                new TableColumn<>(t("settings.owners.col.role"));
        colRole.setCellValueFactory(c -> new SimpleStringProperty(t("settings.owners.role." + c.getValue().role())));
        colRole.setPrefWidth(140);
        TableColumn<com.benjagest.ui.model.CompanyOwnerEntry, String> colSs =
                new TableColumn<>(t("settings.owners.col.ss_regime"));
        colSs.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().ssRegime() == null
                ? "" : t("settings.owners.ss_regime." + c.getValue().ssRegime())));
        colSs.setPrefWidth(160);
        TableColumn<com.benjagest.ui.model.CompanyOwnerEntry, String> colPct =
                new TableColumn<>(t("settings.owners.col.pct"));
        colPct.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().ownershipPercent() == null ? "" : c.getValue().ownershipPercent().toPlainString() + " %"));
        colPct.setPrefWidth(80);
        colPct.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.CompanyOwnerEntry, String> colFlags =
                new TableColumn<>(t("settings.owners.col.flags"));
        colFlags.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().active() ? "" : t("settings.owners.inactive")));
        colFlags.setPrefWidth(100);
        ownersTable.getColumns().addAll(java.util.List.of(colName, colNif, colRole, colSs, colPct, colFlags));

        Button addBtn = new Button(t("settings.owners.action.add"));
        addBtn.setGraphic(icon("fas-plus"));
        addBtn.setOnAction(ev -> showOwnerEditor(null));

        Button editBtn = new Button(t("settings.owners.action.edit"));
        editBtn.setGraphic(icon("fas-edit"));
        editBtn.setDisable(true);
        editBtn.setOnAction(ev -> {
            var sel = ownersTable.getSelectionModel().getSelectedItem();
            if (sel != null) showOwnerEditor(sel);
        });

        Button deleteBtn = new Button(t("settings.owners.action.delete"));
        deleteBtn.setGraphic(icon("fas-trash"));
        deleteBtn.setDisable(true);
        deleteBtn.setOnAction(ev -> {
            var sel = ownersTable.getSelectionModel().getSelectedItem();
            if (sel != null) deleteOwner(sel);
        });

        ownersTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            editBtn.setDisable(newV == null);
            deleteBtn.setDisable(newV == null);
        });

        HBox actions = new HBox(8, addBtn, editBtn, deleteBtn);
        actions.getStyleClass().add("settings-actions");

        reloadOwners();

        VBox body = new VBox(12, section, hint, ownersTable);
        return tabLayout(label(t("settings.owners.section_label"), "settings-section-title"), body, actions);
    }

    private void reloadOwners() {
        if (ownersTable == null) return;
        Task<java.util.List<com.benjagest.ui.model.CompanyOwnerEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.CompanyOwnerEntry> call() throws Exception {
                return altaApiClient.listOwners();
            }
        };
        task.setOnSucceeded(ev -> ownersTable.setItems(FXCollections.observableArrayList(task.getValue())));
        task.setOnFailed(ev -> ownersTable.getItems().clear());
        start(task, "settings-owners-reload");
    }

    private void showOwnerEditor(com.benjagest.ui.model.CompanyOwnerEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? t("settings.owners.editor.title_new") : t("settings.owners.editor.title_edit"));
        ButtonType saveBt = new ButtonType(t("settings.owners.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField nameField = new TextField(existing == null ? "" : existing.fullName());
        TextField nifField = new TextField(existing == null ? "" : existing.taxIdentifier());
        ComboBox<String> roleCombo = new ComboBox<>();
        roleCombo.getItems().addAll("ADMINISTRATOR", "JOINT", "SOLE", "BOARD_MEMBER", "PARTNER", "AUTONOMOUS");
        roleCombo.getSelectionModel().select(existing == null ? "ADMINISTRATOR" : existing.role());
        ComboBox<String> ssCombo = new ComboBox<>();
        ssCombo.getItems().addAll("RETA", "GENERAL", "AUTONOMO_SOCIETARIO", "NO_COTIZA", "OTHER");
        ssCombo.getSelectionModel().select(existing == null || existing.ssRegime() == null || existing.ssRegime().isBlank()
                ? "RETA" : existing.ssRegime());
        TextField pctField = new TextField(existing == null || existing.ownershipPercent() == null
                ? "" : existing.ownershipPercent().toPlainString());
        TextField apptField = new TextField(existing == null ? "" : existing.appointmentDate());
        apptField.setPromptText("AAAA-MM-DD");
        TextField termField = new TextField(existing == null ? "" : existing.terminationDate());
        termField.setPromptText("AAAA-MM-DD");
        TextField emailField = new TextField(existing == null ? "" : existing.email());
        TextField phoneField = new TextField(existing == null ? "" : existing.phone());
        TextArea notesField = new TextArea(existing == null ? "" : existing.notes());
        notesField.setPrefRowCount(2);
        CheckBox activeCb = new CheckBox(t("settings.owners.editor.active"));
        activeCb.setSelected(existing == null || existing.active());

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.add(new Label(t("settings.owners.editor.name")), 0, 0); grid.add(nameField, 1, 0);
        grid.add(new Label(t("settings.owners.editor.nif")), 0, 1); grid.add(nifField, 1, 1);
        grid.add(new Label(t("settings.owners.editor.role")), 0, 2); grid.add(roleCombo, 1, 2);
        grid.add(new Label(t("settings.owners.editor.ss_regime")), 0, 3); grid.add(ssCombo, 1, 3);
        grid.add(new Label(t("settings.owners.editor.pct")), 0, 4); grid.add(pctField, 1, 4);
        grid.add(new Label(t("settings.owners.editor.appointment")), 0, 5); grid.add(apptField, 1, 5);
        grid.add(new Label(t("settings.owners.editor.termination")), 0, 6); grid.add(termField, 1, 6);
        grid.add(new Label(t("settings.owners.editor.email")), 0, 7); grid.add(emailField, 1, 7);
        grid.add(new Label(t("settings.owners.editor.phone")), 0, 8); grid.add(phoneField, 1, 8);
        grid.add(new Label(t("settings.owners.editor.notes")), 0, 9); grid.add(notesField, 1, 9);
        grid.add(activeCb, 1, 10);
        installDialog(dialog, grid);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            java.math.BigDecimal pct;
            try {
                pct = pctField.getText().isBlank() ? null
                        : new java.math.BigDecimal(pctField.getText().trim().replace(",", "."));
            } catch (NumberFormatException ex) {
                showError(t("settings.owners.editor.fail.title"), t("settings.owners.editor.invalid_pct"));
                return;
            }
            com.benjagest.ui.model.CompanyOwnerEntry payload = new com.benjagest.ui.model.CompanyOwnerEntry(
                    existing == null ? null : existing.id(),
                    nameField.getText().trim(),
                    nifField.getText().trim(),
                    roleCombo.getValue(),
                    ssCombo.getValue(),
                    pct,
                    blankToNullOrSelf(apptField.getText()),
                    blankToNullOrSelf(termField.getText()),
                    blankToNullOrSelf(emailField.getText()),
                    blankToNullOrSelf(phoneField.getText()),
                    blankToNullOrSelf(notesField.getText()),
                    activeCb.isSelected());
            Task<com.benjagest.ui.model.CompanyOwnerEntry> task = new Task<>() {
                @Override
                protected com.benjagest.ui.model.CompanyOwnerEntry call() throws Exception {
                    return existing == null
                            ? altaApiClient.createOwner(payload)
                            : altaApiClient.updateOwner(existing.id(), payload);
                }
            };
            task.setOnSucceeded(ev -> reloadOwners());
            task.setOnFailed(ev -> showError(t("settings.owners.editor.fail.title"),
                    t("settings.owners.editor.fail.body")));
            start(task, "settings-owners-save");
        });
    }

    private void deleteOwner(com.benjagest.ui.model.CompanyOwnerEntry entry) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("settings.owners.delete.body") + " " + entry.fullName(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("settings.owners.delete.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    altaApiClient.deleteOwner(entry.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> reloadOwners());
            task.setOnFailed(ev -> showError(t("settings.owners.editor.fail.title"),
                    t("settings.owners.editor.fail.body")));
            start(task, "settings-owners-delete");
        });
    }

    private String blankToNullOrSelf(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    // ===================================================================
    //  ALTA — Pestana Credenciales externas + Log uso certificados
    // ===================================================================

    private Node settingsCredentialsTab() {
        Label section = label(t("settings.credentials.section"), "settings-section-title");
        Label hint = new Label(t("settings.credentials.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        credentialsTable = new TableView<>();
        credentialsTable.getStyleClass().add("data-table");
        credentialsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        credentialsTable.setPlaceholder(new Label(t("settings.credentials.placeholder.empty")));
        credentialsTable.setPrefHeight(240);

        TableColumn<com.benjagest.ui.model.ExternalCredentialEntry, String> colSys =
                new TableColumn<>(t("settings.credentials.col.system"));
        colSys.setCellValueFactory(c -> new SimpleStringProperty(
                t("settings.credentials.system." + c.getValue().systemCode())));
        colSys.setPrefWidth(140);
        TableColumn<com.benjagest.ui.model.ExternalCredentialEntry, String> colLabel =
                new TableColumn<>(t("settings.credentials.col.label"));
        colLabel.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().label()));
        TableColumn<com.benjagest.ui.model.ExternalCredentialEntry, String> colUser =
                new TableColumn<>(t("settings.credentials.col.user"));
        colUser.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().username()));
        colUser.setPrefWidth(140);
        TableColumn<com.benjagest.ui.model.ExternalCredentialEntry, String> colPwd =
                new TableColumn<>(t("settings.credentials.col.password"));
        colPwd.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().passwordConfigured() ? "***" : t("settings.credentials.empty")));
        colPwd.setPrefWidth(80);
        TableColumn<com.benjagest.ui.model.ExternalCredentialEntry, String> colFlags =
                new TableColumn<>(t("settings.credentials.col.flags"));
        colFlags.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().active() ? "" : t("settings.credentials.inactive")));
        colFlags.setPrefWidth(80);
        credentialsTable.getColumns().addAll(java.util.List.of(colSys, colLabel, colUser, colPwd, colFlags));

        Button addBtn = new Button(t("settings.credentials.action.add"));
        addBtn.setGraphic(icon("fas-plus"));
        addBtn.setOnAction(ev -> showCredentialEditor(null));

        Button editBtn = new Button(t("settings.credentials.action.edit"));
        editBtn.setGraphic(icon("fas-edit"));
        editBtn.setDisable(true);
        editBtn.setOnAction(ev -> {
            var sel = credentialsTable.getSelectionModel().getSelectedItem();
            if (sel != null) showCredentialEditor(sel);
        });

        Button deleteBtn = new Button(t("settings.credentials.action.delete"));
        deleteBtn.setGraphic(icon("fas-trash"));
        deleteBtn.setDisable(true);
        deleteBtn.setOnAction(ev -> {
            var sel = credentialsTable.getSelectionModel().getSelectedItem();
            if (sel != null) deleteCredential(sel);
        });

        credentialsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            editBtn.setDisable(newV == null);
            deleteBtn.setDisable(newV == null);
        });

        HBox credActions = new HBox(8, addBtn, editBtn, deleteBtn);
        credActions.setAlignment(Pos.CENTER_LEFT);

        reloadCredentials();

        // ---- Log de uso de certificados ----
        Label logSection = label(t("settings.credentials.log.section"), "settings-section-title");
        Label logHint = new Label(t("settings.credentials.log.hint"));
        logHint.setWrapText(true);
        logHint.getStyleClass().add("settings-hint");

        certUsageTable = new TableView<>();
        certUsageTable.getStyleClass().add("data-table");
        certUsageTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        certUsageTable.setPlaceholder(new Label(t("settings.credentials.log.placeholder.empty")));
        certUsageTable.setPrefHeight(220);

        TableColumn<com.benjagest.ui.model.CertificateUsageEntry, String> colWhen =
                new TableColumn<>(t("settings.credentials.log.col.when"));
        colWhen.setCellValueFactory(c -> new SimpleStringProperty(shortIso(c.getValue().usedAt())));
        colWhen.setPrefWidth(160);
        colWhen.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.CertificateUsageEntry, String> colCert =
                new TableColumn<>(t("settings.credentials.log.col.cert"));
        colCert.setCellValueFactory(c -> new SimpleStringProperty(shortId(c.getValue().certificateId())));
        colCert.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.CertificateUsageEntry, String> colPurpose =
                new TableColumn<>(t("settings.credentials.log.col.purpose"));
        colPurpose.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().purpose()));
        colPurpose.setPrefWidth(140);
        TableColumn<com.benjagest.ui.model.CertificateUsageEntry, String> colOk =
                new TableColumn<>(t("settings.credentials.log.col.result"));
        colOk.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().success() ? "OK" : "ERR"));
        colOk.setPrefWidth(60);
        TableColumn<com.benjagest.ui.model.CertificateUsageEntry, String> colUserCert =
                new TableColumn<>(t("settings.credentials.log.col.user"));
        colUserCert.setCellValueFactory(c -> new SimpleStringProperty(shortId(c.getValue().userId())));
        colUserCert.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.CertificateUsageEntry, String> colIp =
                new TableColumn<>(t("settings.credentials.log.col.ip"));
        colIp.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().ipAddress()));
        colIp.setPrefWidth(120);
        TableColumn<com.benjagest.ui.model.CertificateUsageEntry, String> colMsg =
                new TableColumn<>(t("settings.credentials.log.col.message"));
        colMsg.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().errorMessage() == null ? c.getValue().targetUrl() : c.getValue().errorMessage()));
        certUsageTable.getColumns().addAll(java.util.List.of(colWhen, colCert, colPurpose, colOk, colUserCert, colIp, colMsg));

        Button refreshLog = new Button(t("settings.credentials.log.refresh"));
        refreshLog.setGraphic(icon("fas-sync-alt"));
        refreshLog.setOnAction(ev -> reloadCertUsage());

        HBox logActions = new HBox(8, refreshLog);
        logActions.setAlignment(Pos.CENTER_LEFT);

        reloadCertUsage();

        VBox body = new VBox(16,
                section, hint, credentialsTable, credActions,
                new Separator(),
                logSection, logHint, certUsageTable, logActions);
        return tabLayout(label(t("settings.credentials.section_label"), "settings-section-title"), body,
                new HBox());
    }

    private void reloadCredentials() {
        if (credentialsTable == null) return;
        Task<java.util.List<com.benjagest.ui.model.ExternalCredentialEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.ExternalCredentialEntry> call() throws Exception {
                return altaApiClient.listCredentials();
            }
        };
        task.setOnSucceeded(ev -> credentialsTable.setItems(FXCollections.observableArrayList(task.getValue())));
        task.setOnFailed(ev -> credentialsTable.getItems().clear());
        start(task, "settings-credentials-reload");
    }

    private void reloadCertUsage() {
        if (certUsageTable == null) return;
        Task<java.util.List<com.benjagest.ui.model.CertificateUsageEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.CertificateUsageEntry> call() throws Exception {
                return altaApiClient.listCertUsage(null, 200);
            }
        };
        task.setOnSucceeded(ev -> certUsageTable.setItems(FXCollections.observableArrayList(task.getValue())));
        task.setOnFailed(ev -> certUsageTable.getItems().clear());
        start(task, "settings-cert-usage-reload");
    }

    private void showCredentialEditor(com.benjagest.ui.model.ExternalCredentialEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? t("settings.credentials.editor.title_new")
                : t("settings.credentials.editor.title_edit"));
        ButtonType saveBt = new ButtonType(t("settings.credentials.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        ComboBox<String> sysCombo = new ComboBox<>();
        sysCombo.getItems().addAll("DEHU", "SS_RED", "SILTRA", "AEAT_CLAVE",
                "NOTIFICA_GOB", "SEDE_AEAT", "BANCO_ESPANA", "OTHER");
        sysCombo.getSelectionModel().select(existing == null ? "DEHU" : existing.systemCode());
        sysCombo.setDisable(existing != null);

        TextField labelField = new TextField(existing == null ? "" : existing.label());
        TextField userField = new TextField(existing == null ? "" : existing.username());
        PasswordField pwdField = new PasswordField();
        pwdField.setPromptText(existing != null && existing.passwordConfigured()
                ? t("settings.credentials.editor.password.keep")
                : t("settings.credentials.editor.password.new"));
        TextField authUrlField = new TextField(existing == null ? "" : existing.authUrl());
        TextArea notesField = new TextArea(existing == null ? "" : existing.notes());
        notesField.setPrefRowCount(2);
        CheckBox activeCb = new CheckBox(t("settings.credentials.editor.active"));
        activeCb.setSelected(existing == null || existing.active());

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.add(new Label(t("settings.credentials.editor.system")), 0, 0); grid.add(sysCombo, 1, 0);
        grid.add(new Label(t("settings.credentials.editor.label")), 0, 1); grid.add(labelField, 1, 1);
        grid.add(new Label(t("settings.credentials.editor.username")), 0, 2); grid.add(userField, 1, 2);
        grid.add(new Label(t("settings.credentials.editor.password")), 0, 3); grid.add(pwdField, 1, 3);
        grid.add(new Label(t("settings.credentials.editor.auth_url")), 0, 4); grid.add(authUrlField, 1, 4);
        grid.add(new Label(t("settings.credentials.editor.notes")), 0, 5); grid.add(notesField, 1, 5);
        grid.add(activeCb, 1, 6);
        installDialog(dialog, grid);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            String pwd = pwdField.getText();
            Task<com.benjagest.ui.model.ExternalCredentialEntry> task = new Task<>() {
                @Override
                protected com.benjagest.ui.model.ExternalCredentialEntry call() throws Exception {
                    if (existing == null) {
                        return altaApiClient.createCredential(
                                sysCombo.getValue(),
                                labelField.getText().trim(),
                                userField.getText().trim(),
                                pwd,
                                authUrlField.getText().trim(),
                                notesField.getText().trim(),
                                activeCb.isSelected());
                    }
                    return altaApiClient.updateCredential(
                            existing.id(),
                            existing.systemCode(),
                            labelField.getText().trim(),
                            userField.getText().trim(),
                            pwd,  // si vacio, el cliente no envia el campo
                            authUrlField.getText().trim(),
                            notesField.getText().trim(),
                            activeCb.isSelected());
                }
            };
            task.setOnSucceeded(ev -> reloadCredentials());
            task.setOnFailed(ev -> showError(t("settings.credentials.editor.fail.title"),
                    t("settings.credentials.editor.fail.body")));
            start(task, "settings-credentials-save");
        });
    }

    private void deleteCredential(com.benjagest.ui.model.ExternalCredentialEntry entry) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("settings.credentials.delete.body") + " " + entry.label(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("settings.credentials.delete.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    altaApiClient.deleteCredential(entry.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> reloadCredentials());
            task.setOnFailed(ev -> showError(t("settings.credentials.editor.fail.title"),
                    t("settings.credentials.editor.fail.body")));
            start(task, "settings-credentials-delete");
        });
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
    private ComboBox<String> billingTypeFilter;
    private TableView<SalesInvoiceSummary> billingTable;

    private Node billingInvoicesTab(List<SalesInvoiceSummary> initialList) {
        billingStatusFilter = new ComboBox<>();
        // Los items siguen siendo los códigos técnicos (DRAFT/VALIDATED/...)
        // para que mapAllOrValue() los mande al backend tal cual. La
        // visualización pasa por localizedInvoiceStatus en cellFactory+
        // buttonCell, así el filtro se ve traducido pero internamente
        // sigue hablando el idioma del API.
        billingStatusFilter.getItems().addAll(t("list.filter.all"), "DRAFT", "VALIDATED", "CANCELLED", "VOIDED");
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

        // Filtro por tipo de factura: NORMAL / PROFORMA / RECTIFYING.
        // Misma técnica que los demás filtros: items=códigos técnicos,
        // visualización vía localizedInvoiceTypeLabel en cell/buttonCell.
        billingTypeFilter = new ComboBox<>();
        billingTypeFilter.getItems().addAll(t("list.filter.all"), "NORMAL", "PROFORMA", "RECTIFYING");
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

        HBox filters = new HBox(10,
                label(t("list.filter.label.status"), "form-label"), billingStatusFilter,
                label(t("list.filter.label.collection"), "form-label"), billingPaymentFilter,
                label(t("list.filter.label.type"), "form-label"), billingTypeFilter,
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
        colDate.setComparator(ISO_DATE_COMPARATOR);

        TableColumn<SalesInvoiceSummary, String> colDue = new TableColumn<>(t("list.column.due_date"));
        colDue.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().dueDate()));
        colDue.setPrefWidth(120);
        colDue.setComparator(ISO_DATE_COMPARATOR);

        TableColumn<SalesInvoiceSummary, String> colStatus = new TableColumn<>(t("list.column.status"));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(localizedInvoiceStatus(c.getValue().status())));
        colStatus.setPrefWidth(110);

        TableColumn<SalesInvoiceSummary, String> colPayment = new TableColumn<>(t("list.column.collection"));
        colPayment.setCellValueFactory(c -> new SimpleStringProperty(localizedPaymentStatus(c.getValue().paymentStatus())));
        colPayment.setPrefWidth(100);

        TableColumn<SalesInvoiceSummary, String> colTotal = new TableColumn<>(t("list.column.total"));
        colTotal.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().total() == null ? "" : money(c.getValue().total().toPlainString())));
        colTotal.setPrefWidth(110);
        // Sin esto "1.234,56 €" ordenaría alfabéticamente — "9" caería
        // después de "10" porque "9" > "1". El comparator extrae el
        // número y compara como BigDecimal.
        colTotal.setComparator(NUMERIC_STRING_COMPARATOR);

        billingTable.getColumns().addAll(List.of(colNumber, colCustomer, colDate, colDue, colStatus, colPayment, colTotal));
        billingTable.setItems(FXCollections.observableArrayList(initialList));
        // Doble click sobre fila editable -> abrir editor. Editable =
        // cualquier DRAFT, o una PROFORMA aunque ya esté VALIDATED
        // (las proformas no son documentos fiscales y siguen siendo
        // borradores comerciales hasta que se convierten a factura).
        billingTable.setRowFactory(tv -> {
            javafx.scene.control.TableRow<SalesInvoiceSummary> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    SalesInvoiceSummary inv = row.getItem();
                    boolean editable = "DRAFT".equals(inv.status())
                            || "PROFORMA".equals(inv.invoiceType());
                    if (editable) {
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
            voidBtn.setDisable(!isValidated || isRectifying || isProforma);
            pdfBtn.setDisable(newV == null || isDraft);
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
            emailBtn.setDisable(!isValidated);
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

        Region rowActionsSpacer = new Region();
        HBox.setHgrow(rowActionsSpacer, Priority.ALWAYS);
        HBox rowActions = new HBox(10, validateRowBtn, deleteDraftBtn, voidBtn,
                toValidatedBtn, rowActionsSpacer, emailBtn, pdfBtn);
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
        task.setOnFailed(ev -> showError(t("list.dialog.email.fail.title"),
                t("list.dialog.email.fail.body")));
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
        });
        task.setOnFailed(ev -> showError(t("list.dialog.proforma.fail.title"),
                t("list.dialog.proforma.fail.body")));
        start(task, "billing-invoice-convert-proforma");
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

    private ComboBox<String> verifactuModalityCombo;
    private ComboBox<String> verifactuModeCombo;
    private ComboBox<CertificateOption> verifactuCertCombo;
    private TextField verifactuFooterField;
    private TextField verifactuStorageRootField;
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

        verifactuFooterField = textInput(config.invoiceFooterTemplate(), t("billing.config.verifactu.footer.prompt"));
        verifactuFooterField.setPrefColumnCount(60);

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
        addFormRow(grid, 3, t("billing.config.field.footer"), verifactuFooterField);
        addFormRow(grid, 4, t("billing.config.field.storage_root"), storageRow);

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
        manufacturerBtn.setOnAction(event -> showManufacturerDeclaration());

        // VF-EVENTS-B: bloque de Registro de Eventos del SIF — solo es
        // legalmente obligatorio en NO VeriFactu, pero lo mostramos en
        // ambas modalidades porque (a) las facturas anteriores al
        // cambio de modalidad pueden tener eventos, (b) la pestaña es
        // de solo lectura y sirve de evidencia auditable.
        Node sifEventsBlock = sifEventsAuditBlock();

        HBox actions = new HBox(8, save, verifyChain, manufacturerBtn);
        actions.getStyleClass().add("settings-actions");

        Node vatRatesBlock = vatRatesAuditBlock();

        VBox body = new VBox(16,
                section, hint, grid, certHint,
                new Separator(),
                seriesHeader, seriesHint, seriesTable, seriesActions,
                new Separator(),
                migrationBlock,
                new Separator(),
                textsBlock,
                new Separator(),
                vatRatesBlock,
                new Separator(),
                sifEventsBlock
        );
        return tabLayout(label(t("billing.config.tab_title"), "settings-section-title"), body, actions);
    }

    /**
     * Bloque "Tipos impositivos" en config facturación. Lista los
     * tipos VAT/IRPF de la empresa y permite añadir/editar/borrar.
     * Disponible en los dos modos (asesoría y cliente) — el catalogo
     * es por empresa.
     */
    private TableView<com.benjagest.ui.model.VatRateEntry> vatRatesTable;

    private Node vatRatesAuditBlock() {
        Label header = label(t("billing.config.vat.section"), "settings-section-title");
        Label hint = new Label(t("billing.config.vat.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        vatRatesTable = new TableView<>();
        vatRatesTable.getStyleClass().add("data-table");
        vatRatesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        vatRatesTable.setPlaceholder(new Label(t("billing.config.vat.placeholder.empty")));
        vatRatesTable.setPrefHeight(240);

        TableColumn<com.benjagest.ui.model.VatRateEntry, String> colKind =
                new TableColumn<>(t("billing.config.vat.col.kind"));
        colKind.setCellValueFactory(c -> new SimpleStringProperty(
                "VAT".equals(c.getValue().kind()) ? t("billing.config.vat.kind.vat")
                        : t("billing.config.vat.kind.withholding")));
        colKind.setPrefWidth(100);
        TableColumn<com.benjagest.ui.model.VatRateEntry, String> colCode =
                new TableColumn<>(t("billing.config.vat.col.code"));
        colCode.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().code()));
        colCode.setPrefWidth(80);
        TableColumn<com.benjagest.ui.model.VatRateEntry, String> colLabel =
                new TableColumn<>(t("billing.config.vat.col.label"));
        colLabel.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().label()));
        TableColumn<com.benjagest.ui.model.VatRateEntry, String> colPct =
                new TableColumn<>(t("billing.config.vat.col.percent"));
        colPct.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().percent() == null ? "" : c.getValue().percent().toPlainString() + " %"));
        colPct.setPrefWidth(80);
        colPct.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.VatRateEntry, String> colFlags =
                new TableColumn<>(t("billing.config.vat.col.flags"));
        colFlags.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().isDefault() ? "★ " : "")
                + (c.getValue().active() ? "" : t("billing.config.vat.inactive"))));
        colFlags.setPrefWidth(100);
        vatRatesTable.getColumns().addAll(java.util.List.of(colKind, colCode, colLabel, colPct, colFlags));

        Button addBtn = new Button(t("billing.config.vat.action.add"));
        addBtn.setGraphic(icon("fas-plus"));
        addBtn.setOnAction(ev -> showVatRateEditor(null));

        Button editBtn = new Button(t("billing.config.vat.action.edit"));
        editBtn.setGraphic(icon("fas-edit"));
        editBtn.setDisable(true);
        editBtn.setOnAction(ev -> {
            var sel = vatRatesTable.getSelectionModel().getSelectedItem();
            if (sel != null) showVatRateEditor(sel);
        });

        Button deleteBtn = new Button(t("billing.config.vat.action.delete"));
        deleteBtn.setGraphic(icon("fas-trash"));
        deleteBtn.setDisable(true);
        deleteBtn.setOnAction(ev -> {
            var sel = vatRatesTable.getSelectionModel().getSelectedItem();
            if (sel != null) deleteVatRate(sel);
        });

        vatRatesTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            editBtn.setDisable(newV == null);
            deleteBtn.setDisable(newV == null || newV.isDefault());
        });

        HBox btnRow = new HBox(8, addBtn, editBtn, deleteBtn);
        btnRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        reloadVatRates();
        return new VBox(8, header, hint, vatRatesTable, btnRow);
    }

    private void reloadVatRates() {
        if (vatRatesTable == null) return;
        Task<java.util.List<com.benjagest.ui.model.VatRateEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.VatRateEntry> call() throws Exception {
                return billingApiClient.listVatRates(true);
            }
        };
        task.setOnSucceeded(ev -> vatRatesTable.setItems(FXCollections.observableArrayList(task.getValue())));
        task.setOnFailed(ev -> vatRatesTable.getItems().clear());
        start(task, "billing-vat-rates-reload");
    }

    private void showVatRateEditor(com.benjagest.ui.model.VatRateEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? t("billing.config.vat.editor.title_new")
                : t("billing.config.vat.editor.title_edit"));
        ButtonType saveBt = new ButtonType(t("billing.config.vat.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        ComboBox<String> kindCombo = new ComboBox<>();
        kindCombo.getItems().addAll("VAT", "WITHHOLDING");
        kindCombo.getSelectionModel().select(existing == null ? "VAT" : existing.kind());
        kindCombo.setDisable(existing != null);  // no editable tras crear

        TextField codeField = new TextField(existing == null ? "" : existing.code());
        codeField.setDisable(existing != null);  // no editable tras crear
        TextField labelField = new TextField(existing == null ? "" : existing.label());
        TextField pctField = new TextField(existing == null ? "21" : existing.percent().toPlainString());
        CheckBox defaultCb = new CheckBox(t("billing.config.vat.editor.is_default"));
        defaultCb.setSelected(existing != null && existing.isDefault());
        CheckBox activeCb = new CheckBox(t("billing.config.vat.editor.active"));
        activeCb.setSelected(existing == null || existing.active());

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.add(new Label(t("billing.config.vat.editor.kind")), 0, 0); grid.add(kindCombo, 1, 0);
        grid.add(new Label(t("billing.config.vat.editor.code")), 0, 1); grid.add(codeField, 1, 1);
        grid.add(new Label(t("billing.config.vat.editor.label")), 0, 2); grid.add(labelField, 1, 2);
        grid.add(new Label(t("billing.config.vat.editor.percent")), 0, 3); grid.add(pctField, 1, 3);
        grid.add(defaultCb, 1, 4);
        grid.add(activeCb, 1, 5);
        installDialog(dialog, grid);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            try {
                java.math.BigDecimal pct = new java.math.BigDecimal(pctField.getText().trim().replace(",", "."));
                Task<com.benjagest.ui.model.VatRateEntry> task = new Task<>() {
                    @Override
                    protected com.benjagest.ui.model.VatRateEntry call() throws Exception {
                        if (existing == null) {
                            return billingApiClient.createVatRate(
                                    kindCombo.getValue(),
                                    codeField.getText().trim().toUpperCase(),
                                    labelField.getText().trim(),
                                    pct,
                                    defaultCb.isSelected());
                        }
                        return billingApiClient.updateVatRate(
                                existing.id(),
                                existing.kind(),
                                existing.code(),
                                labelField.getText().trim(),
                                pct,
                                defaultCb.isSelected(),
                                activeCb.isSelected());
                    }
                };
                task.setOnSucceeded(ev -> reloadVatRates());
                task.setOnFailed(ev -> showError(t("billing.config.vat.editor.fail.title"),
                        t("billing.config.vat.editor.fail.body")));
                start(task, "billing-vat-rates-save");
            } catch (NumberFormatException ex) {
                showError(t("billing.config.vat.editor.fail.title"),
                        t("billing.config.vat.editor.invalid_percent"));
            }
        });
    }

    private void deleteVatRate(com.benjagest.ui.model.VatRateEntry entry) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("billing.config.vat.delete.body") + " " + entry.label(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("billing.config.vat.delete.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    billingApiClient.deleteVatRate(entry.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> reloadVatRates());
            task.setOnFailed(ev -> showError(t("billing.config.vat.delete.fail.title"),
                    t("billing.config.vat.delete.fail.body")));
            start(task, "billing-vat-rates-delete");
        });
    }

    /**
     * Bloque "Auditoría SIF" — listado del Registro de Eventos del SIF
     * con filtro por tipo, botón Refrescar y botón Verificar integridad.
     * Es la cara visible del slice VF-EVENTS para el OWNER/ADMIN.
     */
    private TableView<com.benjagest.ui.model.SifEventEntry> sifEventsTable;
    private ComboBox<String> sifEventTypeFilter;

    private Node sifEventsAuditBlock() {
        Label header = label(t("billing.config.sif.section"), "settings-section-title");
        Label hint = new Label(t("billing.config.sif.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        sifEventTypeFilter = new ComboBox<>();
        sifEventTypeFilter.getItems().addAll(
                t("list.filter.all"),
                "SYSTEM_START", "SYSTEM_STOP",
                "INVOICE_VALIDATED", "INVOICE_VOIDED",
                "ANOMALY_DETECTION_INVOICES_RUN", "ANOMALY_DETECTION_INVOICES_HIT",
                "ANOMALY_DETECTION_EVENTS_RUN", "ANOMALY_DETECTION_EVENTS_HIT",
                "BACKUP_RESTORED",
                "EXPORT_INVOICES", "EXPORT_EVENTS",
                "SUMMARY_6H", "SUMMARY_SHUTDOWN");
        sifEventTypeFilter.getSelectionModel().selectFirst();
        sifEventTypeFilter.getStyleClass().add("form-input");

        sifEventsTable = new TableView<>();
        sifEventsTable.getStyleClass().add("data-table");
        sifEventsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        sifEventsTable.setPlaceholder(new Label(t("billing.config.sif.placeholder.empty")));
        sifEventsTable.setPrefHeight(220);

        TableColumn<com.benjagest.ui.model.SifEventEntry, String> colWhen =
                new TableColumn<>(t("billing.config.sif.col.when"));
        colWhen.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().generatedAtIso()));
        colWhen.setPrefWidth(160);
        colWhen.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.SifEventEntry, String> colType =
                new TableColumn<>(t("billing.config.sif.col.type"));
        colType.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().eventType()));
        colType.setPrefWidth(220);
        TableColumn<com.benjagest.ui.model.SifEventEntry, String> colHash =
                new TableColumn<>(t("billing.config.sif.col.hash"));
        colHash.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().hashCurrent() == null || c.getValue().hashCurrent().length() < 16
                        ? "" : c.getValue().hashCurrent().substring(0, 16) + "…"));
        colHash.setPrefWidth(150);
        TableColumn<com.benjagest.ui.model.SifEventEntry, String> colPayload =
                new TableColumn<>(t("billing.config.sif.col.payload"));
        colPayload.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().payload() == null ? "" : c.getValue().payload()));
        sifEventsTable.getColumns().addAll(List.of(colWhen, colType, colHash, colPayload));

        Button refresh = new Button(t("billing.config.sif.refresh"));
        refresh.setGraphic(icon("fas-sync"));
        refresh.setOnAction(event -> refreshSifEvents());

        Button verifySif = new Button(t("billing.config.sif.verify"));
        verifySif.setGraphic(icon("fas-shield-alt"));
        verifySif.setOnAction(event -> verifySifEventChain());

        HBox filterRow = new HBox(8, sifEventTypeFilter, refresh, verifySif);
        filterRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // VF-EVENTS-EXPORT: bloque de exportación PDF/CSV verificable.
        // Defaults: trimestre actual. Emite evento legal EXPORT_EVENTS
        // en la cadena SIF + auditoría con SHA-256 del documento.
        java.time.LocalDate today = java.time.LocalDate.now();
        int quarter = (today.getMonthValue() - 1) / 3;
        java.time.LocalDate quarterStart = java.time.LocalDate.of(today.getYear(), quarter * 3 + 1, 1);
        java.time.LocalDate quarterEnd = quarterStart.plusMonths(3).minusDays(1);
        DatePicker fromPicker = new DatePicker(quarterStart);
        DatePicker toPicker = new DatePicker(quarterEnd);
        Button exportPdfBtn = new Button(t("billing.config.sif.export.pdf"));
        exportPdfBtn.setGraphic(icon("fas-file-pdf"));
        exportPdfBtn.getStyleClass().add("button-primary");
        exportPdfBtn.setOnAction(ev -> downloadSifExport("pdf",
                fromPicker.getValue(), toPicker.getValue(),
                sifEventTypeFilter == null ? null : sifEventTypeFilter.getValue()));
        Button exportCsvBtn = new Button(t("billing.config.sif.export.csv"));
        exportCsvBtn.setGraphic(icon("fas-file-csv"));
        exportCsvBtn.setOnAction(ev -> downloadSifExport("csv",
                fromPicker.getValue(), toPicker.getValue(),
                sifEventTypeFilter == null ? null : sifEventTypeFilter.getValue()));
        Label exportTitle = label(t("billing.config.sif.export.title"), "settings-section-title");
        Label exportHint = new Label(t("billing.config.sif.export.hint"));
        exportHint.setWrapText(true);
        exportHint.getStyleClass().add("settings-hint");
        HBox exportRow = new HBox(8,
                label(t("billing.config.sif.export.from"), "form-label"), fromPicker,
                label(t("billing.config.sif.export.to"), "form-label"), toPicker,
                exportPdfBtn, exportCsvBtn);
        exportRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox exportBlock = new VBox(8, new Separator(), exportTitle, exportHint, exportRow);

        // Carga inicial diferida: si la pestaña aun no esta visible,
        // un Task no bloqueante asegura que la UI responde antes de
        // que el endpoint responda.
        refreshSifEvents();

        return new VBox(8, header, hint, filterRow, sifEventsTable, exportBlock);
    }

    /** Descarga el export del Registro de Eventos del SIF. */
    private void downloadSifExport(String format,
                                    java.time.LocalDate from, java.time.LocalDate to,
                                    String selectedTypeFilter) {
        if (from == null || to == null || from.isAfter(to)) {
            showError(t("settings.audit.export.fail.range.title"),
                    t("settings.audit.export.fail.range.body"));
            return;
        }
        String filter = selectedTypeFilter;
        if (filter != null && ("(todos)".equals(filter) || "(all)".equals(filter)
                || t("list.filter.all").equals(filter))) {
            filter = null;
        }
        String eventType = filter;
        Task<byte[]> task = new Task<>() {
            @Override protected byte[] call() throws Exception {
                return billingApiClient.exportSifEvents(format,
                        from.toString(), to.toString(), eventType);
            }
        };
        task.setOnSucceeded(ev -> {
            byte[] body = task.getValue();
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setInitialFileName("sif-events-" + from + "_" + to + "." + format);
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                    format.toUpperCase(), "*." + format));
            java.io.File target = fc.showSaveDialog(root.getScene().getWindow());
            if (target == null) return;
            try {
                java.nio.file.Files.write(target.toPath(), body);
                showInfo(t("settings.audit.export.ok.title"),
                        t("settings.audit.export.ok.body") + "\n" + target.getAbsolutePath());
                // Refresca el listado para que se vea el EXPORT_EVENTS recién emitido.
                refreshSifEvents();
            } catch (java.io.IOException ex) {
                showError(t("settings.audit.export.fail.write.title"), ex.getMessage());
            }
        });
        task.setOnFailed(ev -> showError(t("settings.audit.export.fail.title"),
                t("settings.audit.export.fail.body")));
        start(task, "sif-export-" + format);
    }

    private void refreshSifEvents() {
        if (sifEventsTable == null) return;
        String selected = sifEventTypeFilter == null ? null : sifEventTypeFilter.getValue();
        String filter = (selected == null
                || t("list.filter.all").equals(selected)
                || "(todos)".equals(selected)
                || "(all)".equals(selected))
                ? null : selected;
        Task<java.util.List<com.benjagest.ui.model.SifEventEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.SifEventEntry> call() throws Exception {
                return billingApiClient.listSifEvents(filter);
            }
        };
        task.setOnSucceeded(event -> sifEventsTable.getItems().setAll(task.getValue()));
        task.setOnFailed(event -> sifEventsTable.getItems().clear());
        start(task, "billing-sif-events-list");
    }

    private void verifySifEventChain() {
        Task<com.benjagest.ui.model.SifEventIntegrityResult> task = new Task<>() {
            @Override
            protected com.benjagest.ui.model.SifEventIntegrityResult call() throws Exception {
                return billingApiClient.verifySifEventChain();
            }
        };
        task.setOnSucceeded(event -> {
            com.benjagest.ui.model.SifEventIntegrityResult result = task.getValue();
            if (result.ok()) {
                Alert ok = new Alert(Alert.AlertType.INFORMATION,
                        t("billing.config.sif.verify.ok.prefix") + result.totalChecked()
                                + t("billing.config.sif.verify.ok.suffix"),
                        ButtonType.OK);
                ok.setHeaderText(null);
                ok.showAndWait();
            } else {
                String broken = result.brokenEventType() == null ? "—" : result.brokenEventType();
                String body = t("billing.config.sif.verify.broken.prefix") + broken + "\n"
                        + (result.reason() == null ? "" : result.reason());
                showError(t("billing.config.sif.verify.broken.title"), body);
            }
        });
        task.setOnFailed(event -> showError(
                t("billing.config.sif.verify.fail.title"),
                t("billing.config.sif.verify.fail.body")));
        start(task, "billing-sif-events-verify");
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
    private ComboBox<String> editorInvoiceTypeCombo;
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
            editorInvoiceTypeCombo.getItems().addAll("NORMAL", "PROFORMA");
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
    /**
     * Comparator para columnas que muestran un decimal formateado
     * ("1.234,56 €", "10,50 %"…). Sin él, JavaFX ordena por string
     * (alfabéticamente), lo cual coloca "10" antes de "9". Limpia
     * separadores de miles, sustituye coma decimal por punto y
     * extrae el primer número de la cadena. Filas vacías o no
     * numéricas se mandan al final.
     */
    private static final java.util.Comparator<String> NUMERIC_STRING_COMPARATOR = (a, b) -> {
        java.math.BigDecimal va = parseDecimal(a);
        java.math.BigDecimal vb = parseDecimal(b);
        if (va == null && vb == null) return 0;
        if (va == null) return 1;
        if (vb == null) return -1;
        return va.compareTo(vb);
    };

    /**
     * Comparator para fechas en formato ISO ("yyyy-MM-dd"). Para
     * fechas ISO, el orden alfabético YA es cronológico, así que en
     * realidad el comparator por defecto bastaría — pero
     * explicitarlo lo blinda contra cambios de formato futuros y
     * coloca null/vacíos al final.
     */
    private static final java.util.Comparator<String> ISO_DATE_COMPARATOR = (a, b) -> {
        boolean ea = a == null || a.isBlank();
        boolean eb = b == null || b.isBlank();
        if (ea && eb) return 0;
        if (ea) return 1;
        if (eb) return -1;
        return a.compareTo(b);
    };

    private static java.math.BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // Extraer el primer "número" (con coma o punto). Ignora € %
        // espacios, etc.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("-?\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d+)?|-?\\d+(?:[.,]\\d+)?")
                .matcher(raw);
        if (!m.find()) return null;
        String token = m.group();
        // Si hay coma Y punto, asumimos formato europeo "1.234,56" → quitar puntos, coma→punto.
        // Si solo hay coma, asumimos decimal europeo "10,50" → coma→punto.
        // Si solo hay punto, asumimos decimal anglo "10.50" → tal cual.
        if (token.contains(",") && token.contains(".")) {
            token = token.replace(".", "").replace(",", ".");
        } else if (token.contains(",")) {
            token = token.replace(",", ".");
        }
        try {
            return new java.math.BigDecimal(token);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Etiqueta humana de un invoice_type (NORMAL/PROFORMA) traducida. */
    private String localizedInvoiceTypeLabel(String code) {
        if (code == null) return "";
        return switch (code) {
            case "NORMAL" -> t("editor.kind.normal");
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
                if (existingId == null) {
                    saved = billingApiClient.createInvoice(customer.id(), null, selectedInvoiceType,
                            invoiceDateIso, dueDateIso, notes, lines);
                } else {
                    saved = billingApiClient.updateInvoice(existingId, customer.id(), null, selectedInvoiceType,
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
        String footer = verifactuFooterField.getText();
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
        java.io.File selected = chooser.showDialog(root.getScene().getWindow());
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
    /**
     * Diálogo informativo con la declaración responsable del fabricante
     * del SIF (RD 1007/2023 + Orden HAC/1177/2024 art. 15). Carga el
     * JSON del endpoint y lo formatea como texto plano dentro de un
     * Alert con TextArea — el contenido es largo (varias líneas) y un
     * label tipo "header" no lo renderizaría bien.
     */
    private void showManufacturerDeclaration() {
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return billingApiClient.fetchManufacturerDeclaration();
            }
        };
        task.setOnSucceeded(ev -> {
            String json = task.getValue();
            String formatted = formatManufacturerDeclaration(json);
            javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(formatted);
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount(20);
            area.setPrefColumnCount(70);
            Alert info = new Alert(Alert.AlertType.INFORMATION);
            info.setHeaderText(t("billing.config.manufacturer.dialog.title"));
            info.getDialogPane().setContent(area);
            info.getDialogPane().setPrefWidth(720);
            info.showAndWait();
        });
        task.setOnFailed(ev -> showError(t("billing.config.manufacturer.fail.title"),
                t("billing.config.manufacturer.fail.body")));
        start(task, "billing-config-manufacturer");
    }

    private String formatManufacturerDeclaration(String json) {
        // Parser minimal — saca cada campo en orden y lo presenta con
        // etiqueta humana. El endpoint devuelve un objeto plano sin
        // anidamientos, así que extraer por nombre vía regex basta.
        StringBuilder sb = new StringBuilder();
        sb.append(t("billing.config.manufacturer.section.manufacturer")).append("\n");
        sb.append("  ").append(extract(json, "manufacturerName")).append("\n");
        sb.append("  NIF: ").append(extract(json, "manufacturerTaxIdentifier")).append("\n");
        sb.append("  Email: ").append(extract(json, "manufacturerEmail")).append("\n");
        sb.append("  ").append(extract(json, "manufacturerAddress")).append("\n\n");
        sb.append(t("billing.config.manufacturer.section.product")).append("\n");
        sb.append("  ").append(extract(json, "productName"))
                .append(" v").append(extract(json, "productVersion")).append("\n");
        sb.append("  ").append(extract(json, "productType")).append("\n\n");
        sb.append("  ").append(extract(json, "productFunctionalities")).append("\n\n");
        sb.append(t("billing.config.manufacturer.section.date")).append("\n");
        sb.append("  ").append(extract(json, "declarationDate")).append(" — ")
                .append(extract(json, "declarationPlace")).append("\n\n");
        sb.append(t("billing.config.manufacturer.section.commitment")).append("\n");
        sb.append("  ").append(extract(json, "complianceCommitment")).append("\n");
        return sb.toString();
    }

    private String extract(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
                .matcher(json);
        if (!m.find()) return "";
        return m.group(1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }

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

    /**
     * Wrapper retro-compatible (v1). Solo envuelve en ScrollPane el
     * contenido, sin tocar el Stage del Dialog — útil para sitios que
     * no son diálogos. Para diálogos prefiero {@link #installDialog}.
     */
    private Node dialogScroll(Node content) {
        return com.benjagest.ui.layout.ResponsiveLayout.dialog(content);
    }

    /**
     * Configura un Dialog responsivo: ScrollPane interno + cap en
     * DialogPane + cap en Stage al mostrar. Garantiza que los botones
     * (Aceptar / Cancelar / Guardar) son SIEMPRE visibles, incluso en
     * portátil 13" maximizado. Reemplaza
     * {@code dialog.getDialogPane().setContent(X)}.
     */
    private void installDialog(javafx.scene.control.Dialog<?> dialog, Node content) {
        com.benjagest.ui.layout.ResponsiveLayout.installDialog(dialog, content);
    }

    /**
     * Wrap reactivo para pantallas (tabs) que no usan tabLayout y
     * pueden crecer más alto que la ventana en portátil. Delega en
     * ResponsiveLayout.
     */
    private Node screenScroll(Node content) {
        return com.benjagest.ui.layout.ResponsiveLayout.screen(content);
    }

    private void setCenterAnimated(Node node) {
        node.setOpacity(0);
        node.setTranslateY(12);
        // Garantia ultima de scroll: si el contenido no es ya un
        // ScrollPane, lo envolvemos. Las pantallas que ya envuelven con
        // tabLayout/screenScroll vuelven por el corto-circuito
        // idempotente de ResponsiveLayout.screen.
        Node centerNode = (node instanceof ScrollPane) ? node
                : com.benjagest.ui.layout.ResponsiveLayout.screen(node);
        root.setCenter(centerNode);

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

    /**
     * Reemplazo silencioso del contenido central, sin animación de
     * entrada. Pensado para tickers de polling que refrescan la misma
     * pantalla cada N segundos — usar la versión animada haría
     * parpadear toda la vista en cada tick.
     */
    private void setCenterSilent(Node node) {
        Node centerNode = (node instanceof ScrollPane) ? node
                : com.benjagest.ui.layout.ResponsiveLayout.screen(node);
        root.setCenter(centerNode);
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
                case "sidebar.section.my_company" -> "My company";
                case "sidebar.section.my_clients" -> "My clients";
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
                case "module.purchases" -> "Purchases & Expenses";
                case "module.labor" -> "HR";
                case "module.tax" -> "Tax (AEAT)";
                case "module.reports" -> "Reports";
                case "module.calendar" -> "Calendar";
                case "module.settings" -> "Settings";
                case "module.advisory" -> "My clients";
                case "module.notifications" -> "DEHú inbox";
                case "module.self-employed" -> "Self-employed";
                case "module.time-clock" -> "Time tracking";
                case "module.documents" -> "Documents";
                case "module.accounting" -> "Accounting";
                case "module.core" -> "Core";
                case "module.kiosk" -> "Kiosk";
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
                case "editor.kind.normal" -> "Standard invoice";
                case "editor.kind.proforma" -> "Proforma";
                case "editor.kind.rectifying" -> "Corrective";
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
                case "list.filter.label.type" -> "Type:";
                case "list.action.proforma_to_draft" -> "To draft";
                case "list.action.proforma_to_validated" -> "Convert and validate";
                case "list.dialog.proforma.convert_draft.title" -> "Convert proforma to draft invoice";
                case "list.dialog.proforma.convert_draft.body" -> "The proforma will become a NORMAL draft (without VeriFactu number yet). You can review it and validate later. Continue?";
                case "list.dialog.proforma.convert_validate.title" -> "Convert and validate";
                case "list.dialog.proforma.convert_validate.body" -> "The proforma will be converted to a standard invoice AND validated in the same step (number, chained hash and QR are issued — irreversible). Continue?";
                case "list.dialog.proforma.success_prefix" -> "Conversion done. Resulting invoice: ";
                case "list.dialog.proforma.success_suffix" -> ".";
                case "list.dialog.proforma.fail.title" -> "Could not convert proforma";
                case "list.dialog.proforma.fail.body" -> "Make sure the proforma is in DRAFT and has at least one line. The server logs detail the cause.";
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
                case "list.action.generate_pdf" -> "PDF";
                case "list.action.open_pdf" -> "Open PDF";
                case "list.action.save_pdf" -> "Save PDF";
                case "list.dialog.pdf.stored_prefix" -> "PDF saved to: ";
                case "list.dialog.pdf.stored_suffix" -> ".";
                case "list.dialog.pdf.store_failed.title" -> "Could not save PDF";
                case "list.dialog.pdf.store_failed.body" -> "Check storage path in Settings → Billing and disk permissions.";
                case "list.action.send_email" -> "Send by email";
                case "list.dialog.email.window_title" -> "Send invoice by email";
                case "list.dialog.email.title" -> "Send invoice by email:";
                case "list.dialog.email.recipient_label" -> "Recipient (empty = use customer's registered email):";
                case "list.dialog.email.success_prefix" -> "Invoice sent to: ";
                case "list.dialog.email.success_suffix" -> ".";
                case "list.dialog.email.fail.title" -> "Could not send email";
                case "list.dialog.email.fail.body" -> "Check SMTP configuration in Settings → Email and that the customer has a valid email registered.";
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
                case "billing.config.field.modality" -> "VeriFactu modality *";
                case "billing.config.field.mode" -> "AEAT environment *";
                case "billing.config.field.cert" -> "Certificate";
                case "billing.config.field.footer" -> "Invoice footer";
                case "billing.config.field.storage_root" -> "Invoice storage path";
                case "billing.config.field.storage_root.prompt" -> "e.g. C:\\benjagest\\invoices or /var/benjagest/invoices (empty = backend default)";
                case "billing.config.field.storage_root.browse" -> "Browse…";
                case "billing.config.field.storage_root.dialog_title" -> "Select folder for invoice storage";
                case "billing.config.modality.verifactu" -> "VeriFactu (real-time submission to AEAT)";
                case "billing.config.modality.no_verifactu" -> "No VeriFactu (local hash + SIF event log)";
                case "billing.config.cert.hint.empty" -> "No certificates uploaded. Activate the Documents module and upload one via /api/certificates.";
                case "billing.config.cert.hint.count_prefix" -> " certificate(s) available.";
                case "billing.config.verifactu.save" -> "Save VeriFactu";
                case "billing.verifactu.save.success_prefix" -> "VeriFactu configuration saved (mode ";
                case "billing.verifactu.save.success_suffix" -> ").";
                case "billing.verifactu.save.fail.title" -> "Could not save";
                case "billing.verifactu.save.fail.body" -> "If you selected PROD remember to choose a .p12 certificate.";
                case "billing.config.verifactu.verify" -> "Verify integrity";
                case "billing.config.verifactu.verify.off" -> "VeriFactu is OFF. Activate TEST or PROD mode first to start the chain.";
                case "billing.config.verifactu.verify.ok.prefix" -> "Chain integrity OK. Invoices checked: ";
                case "billing.config.verifactu.verify.ok.suffix" -> ".";
                case "billing.config.verifactu.verify.broken.title" -> "Chain integrity BROKEN";
                case "billing.config.verifactu.verify.broken.prefix" -> "First suspect invoice: ";
                case "billing.config.verifactu.verify.fail.title" -> "Could not verify";
                case "billing.config.verifactu.verify.fail.body" -> "The server did not respond. Check the connection and try again.";
                case "billing.config.manufacturer.btn" -> "About this SIF";
                case "billing.config.manufacturer.dialog.title" -> "SIF manufacturer responsible declaration (RD 1007/2023 art. 15)";
                case "billing.config.manufacturer.section.manufacturer" -> "Manufacturer / Developer:";
                case "billing.config.manufacturer.section.product" -> "Product:";
                case "billing.config.manufacturer.section.date" -> "Declaration date and place:";
                case "billing.config.manufacturer.section.commitment" -> "Compliance commitment:";
                case "billing.config.manufacturer.fail.title" -> "Could not load manufacturer declaration";
                case "billing.config.manufacturer.fail.body" -> "Make sure the backend is running and try again.";
                case "billing.config.vat.section" -> "Tax rates (VAT + withholdings)";
                case "billing.config.vat.hint" -> "Configurable VAT and withholding rates used by the invoice editor. Same catalogue for both modes (advisor / client). Default ★ is the one preselected on a new line. Inactive rates stay for historical invoices but don't appear in the combo.";
                case "billing.config.vat.placeholder.empty" -> "No rates yet.";
                case "billing.config.vat.col.kind" -> "Kind";
                case "billing.config.vat.col.code" -> "Code";
                case "billing.config.vat.col.label" -> "Label";
                case "billing.config.vat.col.percent" -> "%";
                case "billing.config.vat.col.flags" -> "Flags";
                case "billing.config.vat.kind.vat" -> "VAT";
                case "billing.config.vat.kind.withholding" -> "Withholding";
                case "billing.config.vat.inactive" -> "inactive";
                case "billing.config.vat.action.add" -> "Add rate";
                case "billing.config.vat.action.edit" -> "Edit";
                case "billing.config.vat.action.delete" -> "Delete";
                case "billing.config.vat.editor.title_new" -> "New tax rate";
                case "billing.config.vat.editor.title_edit" -> "Edit tax rate";
                case "billing.config.vat.editor.save" -> "Save";
                case "billing.config.vat.editor.kind" -> "Kind:";
                case "billing.config.vat.editor.code" -> "Code:";
                case "billing.config.vat.editor.label" -> "Label:";
                case "billing.config.vat.editor.percent" -> "Percent:";
                case "billing.config.vat.editor.is_default" -> "Default for its kind";
                case "billing.config.vat.editor.active" -> "Active (visible in invoice combo)";
                case "billing.config.vat.editor.fail.title" -> "Could not save";
                case "billing.config.vat.editor.fail.body" -> "Check the percent is a number 0-100 and the code is unique.";
                case "billing.config.vat.editor.invalid_percent" -> "The percent must be a number between 0 and 100.";
                case "billing.config.vat.delete.title" -> "Delete tax rate";
                case "billing.config.vat.delete.body" -> "Delete this rate?";
                case "billing.config.vat.delete.fail.title" -> "Could not delete";
                case "billing.config.vat.delete.fail.body" -> "The default rate cannot be deleted — mark another as default first.";
                case "timeclock.header" -> "Time clock (RD 8/2019)";
                case "timeclock.hint" -> "Each punch is immutable (art. 34.9). The CSV issued lets you publicly verify the punch with the Labour Inspectorate or any third party (art. 35.8) without credentials.";
                case "timeclock.action.in" -> "Clock in";
                case "timeclock.action.out" -> "Clock out";
                case "timeclock.action.break_start" -> "Break start";
                case "timeclock.action.break_end" -> "Break end";
                case "timeclock.action.refresh" -> "Refresh";
                case "timeclock.placeholder.empty" -> "No punches yet.";
                case "timeclock.col.when" -> "When";
                case "timeclock.col.type" -> "Type";
                case "timeclock.col.origin" -> "Origin";
                case "timeclock.col.status" -> "Status";
                case "timeclock.type.in" -> "In";
                case "timeclock.type.out" -> "Out";
                case "timeclock.type.break_start" -> "Break start";
                case "timeclock.type.break_end" -> "Break end";
                case "timeclock.success.title" -> "Punch recorded";
                case "timeclock.success.csv_label" -> "Public verification code (CSV) — keep it for your records:";
                case "timeclock.fail.title" -> "Could not record punch";
                case "timeclock.fail.body" -> "Make sure your employee profile exists and the backend is running.";
                case "timeclock.not_enrolled.title" -> "You have no employee profile in this company";
                case "timeclock.not_enrolled.body" -> "Ask the administrator to add you in Personal > Employees, linking your user. Once they do, refresh this screen and you will be able to punch in.";
                case "purchases.header" -> "Purchases";
                case "purchases.hint" -> "Upload a received invoice as PDF — BENJAGEST extracts the issuer NIF, date, base, VAT and total automatically (no AI, just regex on the embedded text). Scanned PDFs require OCR — coming in a follow-up slice.";
                case "purchases.action.import_pdf" -> "Import PDF invoice";
                case "purchases.placeholder.coming_soon" -> "The full Purchases module (supplier list, recurring expenses, payment status) is on the roadmap. Today only the PDF importer is available — the detected fields are shown for review but not persisted yet.";
                case "purchases.placeholder.empty" -> "No expenses recorded yet. Use 'Import PDF' to add the first one.";
                case "purchases.filter.year" -> "Year:";
                case "purchases.filter.quarter" -> "Quarter:";
                case "purchases.filter.supplier" -> "Supplier:";
                case "purchases.filter.supplier.prompt" -> "Tax ID or name";
                case "purchases.action.refresh" -> "Refresh";
                case "purchases.action.delete" -> "Delete";
                case "purchases.col.date" -> "Date";
                case "purchases.col.supplier" -> "Supplier";
                case "purchases.col.nif" -> "Tax ID";
                case "purchases.col.number" -> "Invoice #";
                case "purchases.col.base" -> "Base";
                case "purchases.col.vat" -> "VAT";
                case "purchases.col.total" -> "Total";
                case "purchases.col.journal" -> "Journal";
                case "purchases.col.status" -> "Status";
                case "purchases.action.validate_batch" -> "Validate selected";
                case "purchases.confirm.validate_batch" -> "Validate {n} selected DRAFT expenses? Each will be POSTED and the accounting entry generated automatically.";
                case "purchases.validate_batch.result" -> "Validated: {p}\nFailed: {f}\n\nThe list has been refreshed.";
                case "purchases.validate_batch.fail.title" -> "Could not validate batch";
                case "purchases.import.action.save_expense" -> "💾 Save expense";
                case "purchases.save.ok.title" -> "Expense saved";
                case "purchases.save.ok.body" -> "The expense was recorded. If the company has chart of accounts active, a journal entry was also created.";
                case "purchases.save.duplicate.title" -> "Already registered";
                case "purchases.save.duplicate.body" -> "This invoice was already recorded (same SHA-256). The existing one is shown in the list.";
                case "purchases.save.fail.title" -> "Could not save expense";
                case "purchases.save.fail.body" -> "Check the connection and try again.";
                case "purchases.save.fail.missing.title" -> "Missing data";
                case "purchases.save.fail.missing.body" -> "At least Total (or Base + VAT) is required to save the expense.";
                case "purchases.list.fail.title" -> "Could not load expenses";
                case "purchases.list.fail.body" -> "Check the backend connection and your active company.";
                case "purchases.confirm.delete.title" -> "Delete this expense?";
                case "purchases.confirm.delete.body" -> "The expense will be permanently removed from the database. If a journal entry was generated, it will be reversed. The deletion is recorded in the audit log.";
                case "purchases.delete.fail.title" -> "Could not delete";
                case "purchases.delete.fail.body" -> "Try again or check the connection.";
                case "purchases.import.select_pdf" -> "Select PDF invoice";
                case "purchases.import.fail.title" -> "Could not extract from PDF";
                case "purchases.import.fail.body" -> "Maybe the PDF is scanned (no embedded text) or encrypted. Make sure the backend is running.";
                case "purchases.import.result_prefix" -> "Detected fields — ";
                case "purchases.import.field.supplier" -> "Supplier:";
                case "purchases.import.field.confidence" -> "Confidence:";
                case "purchases.import.field.hash" -> "Document SHA-256:";
                case "purchases.import.confidence.high" -> "High (base + VAT ≈ total)";
                case "purchases.import.confidence.medium" -> "Medium (review missing fields)";
                case "purchases.import.confidence.low" -> "Low (cross-check failed)";
                case "purchases.import.action.accept" -> "Accept";
                case "purchases.import.action.save_template" -> "💾 Save template for this supplier";
                case "purchases.import.tip.edit" -> "Tip: correct any wrong field. If supplier and VAT% are constant for this provider, click 'Save template' so next imports from the same NIF apply your fix automatically.";
                case "purchases.import.template_saved.title" -> "Template saved";
                case "purchases.import.template_saved.body" -> "Future imports for NIF/VAT will apply your corrections:";
                case "purchases.import.template_failed.title" -> "Could not save template";
                case "purchases.import.template_failed.body" -> "Check that the supplier NIF is set and try again.";
                case "purchases.import.field.emitter_nif" -> "Issuer NIF:";
                case "purchases.import.field.number" -> "Invoice number:";
                case "purchases.import.field.date" -> "Invoice date:";
                case "purchases.import.field.base" -> "Tax base:";
                case "purchases.import.field.vat_pct" -> "VAT rate:";
                case "purchases.import.field.vat_amount" -> "VAT amount:";
                case "purchases.import.field.total" -> "Total:";
                case "purchases.import.field.concept" -> "Concept:";
                case "purchases.import.field.concept_prompt" -> "e.g. Office supplies, electricity, …";
                case "billing.config.sif.section" -> "SIF event registry (No VeriFactu)";
                case "billing.config.sif.hint" -> "Mandatory chained event log for systems running as No VeriFactu (RD 1007/2023, art. 16). Each event is SHA-256 hashed and linked to the previous one. Lifecycle and invoicing operations are recorded automatically.";
                case "billing.config.sif.placeholder.empty" -> "No SIF events yet. They appear as soon as the system starts and invoices are validated.";
                case "billing.config.sif.col.when" -> "When";
                case "billing.config.sif.col.type" -> "Event type";
                case "billing.config.sif.col.hash" -> "Hash";
                case "billing.config.sif.col.payload" -> "Payload";
                case "billing.config.sif.refresh" -> "Refresh";
                case "billing.config.sif.verify" -> "Verify SIF chain";
                case "billing.config.sif.verify.ok.prefix" -> "SIF chain OK. Events checked: ";
                case "billing.config.sif.verify.ok.suffix" -> ".";
                case "billing.config.sif.verify.broken.title" -> "SIF chain BROKEN";
                case "billing.config.sif.verify.broken.prefix" -> "First suspect event: ";
                case "billing.config.sif.verify.fail.title" -> "Could not verify SIF chain";
                case "billing.config.sif.verify.fail.body" -> "The server did not respond. Check the connection and try again.";
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
                case "settings.tab.owners" -> "Owners";
                case "settings.tab.email" -> "SMTP Email";
                case "settings.tab.modules" -> "Modules";
                case "settings.tab.credentials" -> "Credentials";
                case "settings.tab.certificate" -> "Certificate";
                case "settings.tab.audit" -> "Audit";
                case "settings.cert.section" -> "Digital certificate (.p12 / .pfx)";
                case "settings.cert.hint" -> "Upload your electronic certificate (FNMT, Camerfirma, etc.) to sign invoices for VeriFactu, submit AEAT forms, and receive DEHú notifications. If you are an advisory firm and have a linked client, switch to that client from 'My clients' to upload their certificate on their behalf — both parties will see it.";
                case "settings.cert.placeholder.empty" -> "No certificates uploaded yet";
                case "settings.cert.col.alias" -> "Alias";
                case "settings.cert.col.type" -> "Type";
                case "settings.cert.col.nif" -> "Tax ID";
                case "settings.cert.col.subject" -> "Subject";
                case "settings.cert.col.valid_until" -> "Valid until";
                case "settings.cert.col.uploaded_by" -> "Uploaded by";
                case "settings.cert.col.actions" -> "Actions";
                case "settings.cert.uploaded_by.self" -> "Self";
                case "settings.cert.uploaded_by.advisory" -> "Advisory firm";
                case "settings.cert.action.upload" -> "Upload .p12 / .pfx";
                case "settings.cert.action.refresh" -> "Refresh";
                case "settings.cert.action.delete" -> "Delete";
                case "settings.cert.confirm.delete.title" -> "Delete certificate?";
                case "settings.cert.confirm.delete.body" -> "The certificate will be deactivated. Records signed with it remain valid.";
                case "settings.cert.upload.title" -> "Upload digital certificate";
                case "settings.cert.upload.choose" -> "Choose file";
                case "settings.cert.upload.no_file" -> "(no file selected)";
                case "settings.cert.upload.file" -> "File:";
                case "settings.cert.upload.password" -> "Password:";
                case "settings.cert.upload.inspect" -> "Inspect → auto-fill";
                case "settings.cert.upload.alias" -> "Alias:";
                case "settings.cert.upload.type" -> "Type:";
                case "settings.cert.upload.subject" -> "Subject (CN):";
                case "settings.cert.upload.nif" -> "Tax ID:";
                case "settings.cert.upload.valid_from" -> "Valid from:";
                case "settings.cert.upload.valid_to" -> "Valid until:";
                case "settings.cert.upload.save" -> "Save";
                case "settings.cert.upload.tip" -> "Tip: choose the .p12 and enter its password, then click 'Inspect' to auto-fill subject, NIF and validity dates from the certificate itself.";
                case "settings.cert.fail.list.title" -> "Could not load certificates";
                case "settings.cert.fail.list.body" -> "Check the backend connection and your active company.";
                case "settings.cert.fail.delete.title" -> "Could not delete";
                case "settings.cert.fail.delete.body" -> "The certificate may have been removed already.";
                case "settings.cert.fail.read_file.title" -> "Could not read the file";
                case "settings.cert.fail.read_file.body" -> "The file may be locked or unreadable.";
                case "settings.cert.fail.inspect.title" -> "Could not read the certificate";
                case "settings.cert.fail.inspect.body" -> "The password may be wrong or the file is not a valid PKCS#12 keystore.";
                case "settings.cert.fail.no_file.title" -> "No file selected";
                case "settings.cert.fail.no_file.body" -> "Choose a .p12 or .pfx file before saving.";
                case "settings.cert.fail.no_alias.title" -> "Alias required";
                case "settings.cert.fail.no_alias.body" -> "Enter a short name for this certificate.";
                case "settings.cert.fail.upload.title" -> "Could not save the certificate";
                case "settings.cert.fail.upload.body" -> "If your role is ACCOUNTANT operating on a client, confirm the client is linked to your advisory firm.";
                // ---- Owners (ALTA) ----
                case "settings.owners.section" -> "Owners and administrators";
                case "settings.owners.section_label" -> "Owners";
                case "settings.owners.hint" -> "People with administrative or ownership rights over the company. Required for Form 200 (Corporate Tax) and Social Security filings.";
                case "settings.owners.placeholder.empty" -> "No owners registered yet.";
                case "settings.owners.col.name" -> "Full name";
                case "settings.owners.col.nif" -> "Tax ID";
                case "settings.owners.col.role" -> "Role";
                case "settings.owners.col.ss_regime" -> "SS regime";
                case "settings.owners.col.pct" -> "% Share";
                case "settings.owners.col.flags" -> "";
                case "settings.owners.inactive" -> "(inactive)";
                case "settings.owners.role.ADMINISTRATOR" -> "Administrator";
                case "settings.owners.role.JOINT" -> "Joint administrator";
                case "settings.owners.role.SOLE" -> "Sole administrator";
                case "settings.owners.role.BOARD_MEMBER" -> "Board member";
                case "settings.owners.role.PARTNER" -> "Partner";
                case "settings.owners.role.AUTONOMOUS" -> "Self-employed (autónomo)";
                case "settings.owners.ss_regime.RETA" -> "RETA";
                case "settings.owners.ss_regime.GENERAL" -> "General SS";
                case "settings.owners.ss_regime.AUTONOMO_SOCIETARIO" -> "RETA (corporate)";
                case "settings.owners.ss_regime.NO_COTIZA" -> "Not contributing";
                case "settings.owners.ss_regime.OTHER" -> "Other";
                case "settings.owners.action.add" -> "Add owner";
                case "settings.owners.action.edit" -> "Edit";
                case "settings.owners.action.delete" -> "Delete";
                case "settings.owners.editor.title_new" -> "New owner";
                case "settings.owners.editor.title_edit" -> "Edit owner";
                case "settings.owners.editor.save" -> "Save";
                case "settings.owners.editor.name" -> "Full name";
                case "settings.owners.editor.nif" -> "Tax ID";
                case "settings.owners.editor.role" -> "Role";
                case "settings.owners.editor.ss_regime" -> "SS regime";
                case "settings.owners.editor.pct" -> "% Share (0-100)";
                case "settings.owners.editor.appointment" -> "Appointment date";
                case "settings.owners.editor.termination" -> "Termination date";
                case "settings.owners.editor.email" -> "Email";
                case "settings.owners.editor.phone" -> "Phone";
                case "settings.owners.editor.notes" -> "Notes";
                case "settings.owners.editor.active" -> "Active";
                case "settings.owners.editor.invalid_pct" -> "Invalid percentage.";
                case "settings.owners.editor.fail.title" -> "Could not save";
                case "settings.owners.editor.fail.body" -> "Check the data and try again.";
                case "settings.owners.delete.title" -> "Delete owner?";
                case "settings.owners.delete.body" -> "You are about to delete";
                // ---- Credentials (ALTA) ----
                case "settings.credentials.section" -> "External credentials";
                case "settings.credentials.section_label" -> "Credentials";
                case "settings.credentials.hint" -> "Encrypted passwords for external systems (DEHú, SS RED, SILTRA, AEAT Cl@ve…). Passwords are stored encrypted and never shown again.";
                case "settings.credentials.placeholder.empty" -> "No credentials registered.";
                case "settings.credentials.col.system" -> "System";
                case "settings.credentials.col.label" -> "Label";
                case "settings.credentials.col.user" -> "Username";
                case "settings.credentials.col.password" -> "Password";
                case "settings.credentials.col.flags" -> "";
                case "settings.credentials.empty" -> "—";
                case "settings.credentials.inactive" -> "(inactive)";
                case "settings.credentials.system.DEHU" -> "DEHú";
                case "settings.credentials.system.SS_RED" -> "SS RED";
                case "settings.credentials.system.SILTRA" -> "SILTRA";
                case "settings.credentials.system.AEAT_CLAVE" -> "AEAT Cl@ve";
                case "settings.credentials.system.NOTIFICA_GOB" -> "Notifica.gob";
                case "settings.credentials.system.SEDE_AEAT" -> "AEAT Sede";
                case "settings.credentials.system.BANCO_ESPANA" -> "Bank of Spain";
                case "settings.credentials.system.OTHER" -> "Other";
                case "settings.credentials.action.add" -> "Add credential";
                case "settings.credentials.action.edit" -> "Edit";
                case "settings.credentials.action.delete" -> "Delete";
                case "settings.credentials.editor.title_new" -> "New credential";
                case "settings.credentials.editor.title_edit" -> "Edit credential";
                case "settings.credentials.editor.save" -> "Save";
                case "settings.credentials.editor.system" -> "System";
                case "settings.credentials.editor.label" -> "Label";
                case "settings.credentials.editor.username" -> "Username";
                case "settings.credentials.editor.password" -> "Password";
                case "settings.credentials.editor.password.keep" -> "(leave blank to keep current)";
                case "settings.credentials.editor.password.new" -> "Enter password";
                case "settings.credentials.editor.auth_url" -> "Login URL";
                case "settings.credentials.editor.notes" -> "Notes";
                case "settings.credentials.editor.active" -> "Active";
                case "settings.credentials.editor.fail.title" -> "Could not save";
                case "settings.credentials.editor.fail.body" -> "Check the data and try again.";
                case "settings.credentials.delete.title" -> "Delete credential?";
                case "settings.credentials.delete.body" -> "You are about to delete";
                case "settings.credentials.log.section" -> "Certificate usage log";
                case "settings.credentials.log.hint" -> "Every time a digital certificate is used to sign or send something, the action is logged here for traceability.";
                case "settings.credentials.log.placeholder.empty" -> "No certificate usage events yet.";
                case "settings.credentials.log.col.when" -> "When";
                case "settings.credentials.log.col.cert" -> "Certificate";
                case "settings.credentials.log.col.purpose" -> "Purpose";
                case "settings.credentials.log.col.result" -> "Result";
                case "settings.credentials.log.col.user" -> "User";
                case "settings.credentials.log.col.ip" -> "IP";
                case "settings.credentials.log.col.message" -> "Detail";
                case "settings.credentials.log.refresh" -> "Refresh";
                // ---- Advisory module (ALTA) ----
                case "advisory.title" -> "Managed clients";
                case "advisory.subtitle" -> "Switch tenant to operate as one of your clients.";
                case "advisory.hint" -> "Double-click a row or use 'Switch' to operate in that client's context. Your session stays the same; only the active tenant changes.";
                case "advisory.placeholder.empty" -> "No managed clients linked to this advisory.";
                case "advisory.col.legal_name" -> "Legal name";
                case "advisory.col.nif" -> "Tax ID";
                case "advisory.col.type" -> "Type";
                case "advisory.col.city" -> "City";
                case "advisory.col.email" -> "Email";
                case "advisory.action.switch" -> "Switch to client";
                case "advisory.switch.title" -> "Switch tenant?";
                case "advisory.switch.body" -> "Future requests will operate as";
                case "advisory.load_failed" -> "Could not load the client list.";
                // ---- Tax module (ALTA) ----
                case "tax.title" -> "Tax filings (AEAT)";
                case "tax.subtitle" -> "Quarterly and yearly filings: 303, 130, 200, 347, 390 and others.";
                case "tax.load_failed" -> "Could not load tax filings.";
                case "tax.year" -> "Year";
                case "tax.action.new" -> "New filing";
                case "tax.tab.filings" -> "Filings";
                case "tax.tab.calendar" -> "Calendar";
                case "tax.filings.placeholder.empty" -> "No filings for this year yet.";
                case "tax.filings.col.model" -> "Form";
                case "tax.filings.col.period" -> "Period";
                case "tax.filings.col.status" -> "Status";
                case "tax.filings.col.amount" -> "Amount";
                case "tax.filings.col.deadline" -> "Deadline";
                case "tax.filings.col.csv" -> "AEAT CSV";
                case "tax.filings.action.edit" -> "Edit";
                case "tax.filings.action.delete" -> "Delete";
                case "tax.filings.delete.title" -> "Delete filing?";
                case "tax.filings.delete.body" -> "You are about to delete";
                case "tax.filings.delete.fail.title" -> "Could not delete";
                case "tax.filings.delete.fail.body" -> "Only draft or cancelled filings can be deleted.";
                case "tax.calendar.hint" -> "Standard AEAT deadlines for the selected year. Holidays not factored in — confirm in the official calendar.";
                case "tax.calendar.placeholder.empty" -> "No deadlines for this year.";
                case "tax.calendar.col.deadline" -> "Deadline";
                case "tax.calendar.col.model" -> "Form";
                case "tax.calendar.col.name" -> "Name";
                case "tax.calendar.col.period" -> "Period";
                case "tax.calendar.col.state" -> "State";
                case "tax.calendar.state.pending" -> "Pending";
                case "tax.status.DRAFT" -> "Draft";
                case "tax.status.READY" -> "Ready";
                case "tax.status.PRESENTED" -> "Submitted";
                case "tax.status.PAID" -> "Paid";
                case "tax.status.REJECTED" -> "Rejected";
                case "tax.status.CANCELLED" -> "Cancelled";
                case "tax.new.title" -> "New tax filing";
                case "tax.new.next" -> "Continue";
                case "tax.new.model" -> "Form";
                case "tax.new.year" -> "Year";
                case "tax.new.period" -> "Period";
                case "tax.new.fail.title" -> "Could not create";
                case "tax.new.fail.body" -> "Check the form, period and year.";
                case "tax.editor.generic.title" -> "Edit filing";
                case "tax.editor.save" -> "Save";
                case "tax.editor.status" -> "Status";
                case "tax.editor.total" -> "Total amount";
                case "tax.editor.csv" -> "AEAT CSV";
                case "tax.editor.data" -> "Form data (JSON)";
                case "tax.editor.notes" -> "Notes";
                case "tax.editor.fail.title" -> "Could not save";
                case "tax.editor.fail.body" -> "Check the data and try again.";
                case "settings.company.section_label" -> "Company";
                case "settings.company.section.general" -> "General data";
                case "settings.company.section.address" -> "Postal address";
                case "settings.company.section.billing" -> "Billing data";
                case "settings.company.section.billing.hint" -> "Administrative billing data only. Per-invoice texts (footer, legal notes, exempt VAT, reduced VAT, corrective notice) live in Billing → Settings → Legal texts.";
                case "settings.company.billing_note" -> "ℹ The per-invoice footer, legal terms and other texts are configured in Billing → Settings → Legal texts. This avoids duplicated places to edit the same thing.";
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
                case "settings.audit.col.seq" -> "Seq";
                case "settings.audit.col.hash" -> "Hash (12)";
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
                case "list.dialog.void.body" -> "A LINKED corrective invoice will be issued (lines negated) and the original will be marked VOIDED in the same transaction. This is a final legal act and cannot be undone or edited. Continue?";
                case "list.dialog.void.success_prefix" -> "Corrective invoice issued with nº ";
                case "list.dialog.void.success_suffix" -> ". The original invoice has been voided.";
                case "list.dialog.void.failure.title" -> "Could not void";
                case "list.dialog.void.failure.body" -> "Check that the invoice is VALIDATED and does not already have a linked corrective.";
                case "editor.rectifying.pill_prefix" -> "Corrective for ";
                // ---- F4b PDF download ----
                case "list.dialog.pdf.save_title" -> "Save invoice PDF";
                case "list.dialog.pdf.filter" -> "PDF documents";
                case "list.dialog.pdf.success_title" -> "PDF saved";
                case "list.dialog.pdf.success_prefix" -> "Saved as ";
                case "list.dialog.pdf.success_suffix" -> ". Open it now?";
                case "list.dialog.pdf.open_failed.title" -> "Could not open the PDF";
                case "list.dialog.pdf.open_failed.body" -> "Open it manually from the chosen folder.";
                case "list.dialog.pdf.save_failed.title" -> "Could not save the PDF";
                case "list.dialog.pdf.save_failed.body" -> "Check folder permissions and try again.";
                case "list.dialog.pdf.download_failed.title" -> "Could not generate the PDF";
                case "list.dialog.pdf.download_failed.body" -> "Check that the backend is running and the invoice exists.";
                // ---- Invoice statuses ----
                case "status.invoice.draft" -> "Draft";
                case "status.invoice.validated" -> "Validated";
                case "status.invoice.cancelled" -> "Cancelled";
                case "status.invoice.voided" -> "Voided";
                case "status.payment.pending" -> "Pending";
                case "status.payment.partial" -> "Partial";
                case "status.payment.paid" -> "Paid";
                case "status.payment.overdue" -> "Overdue";
                default -> {
                    String v = tNewModulesEn(key);
                    if (v == null) v = tAdvisoryInvitationsEn(key);
                    if (v == null) v = tExportsAndChainEn(key);
                    yield v != null ? v : (key.startsWith("column.") ? key.substring(7) : key);
                }
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
            case "sidebar.section.my_company" -> "Mi empresa";
            case "sidebar.section.my_clients" -> "Mis clientes";
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
            case "module.purchases" -> "Compras y Gastos";
            case "module.labor" -> "Personal";
            case "module.tax" -> "Modelos AEAT";
            case "module.reports" -> "Informes";
            case "module.calendar" -> "Agenda";
            case "module.settings" -> "Configuracion";
            case "module.advisory" -> "Mis clientes";
            case "module.notifications" -> "Buzon DEHu";
            case "module.self-employed" -> "Autonomos";
            case "module.time-clock" -> "Fichajes";
            case "module.documents" -> "Documentos";
            case "module.accounting" -> "Contabilidad";
            case "module.core" -> "Nucleo";
            case "module.kiosk" -> "Kiosko";
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
            case "editor.kind.normal" -> "Factura normal";
            case "editor.kind.proforma" -> "Proforma";
            case "editor.kind.rectifying" -> "Rectificativa";
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
            case "list.filter.label.type" -> "Tipo:";
            case "list.action.proforma_to_draft" -> "A borrador";
            case "list.action.proforma_to_validated" -> "Convertir y validar";
            case "list.dialog.proforma.convert_draft.title" -> "Convertir proforma en borrador";
            case "list.dialog.proforma.convert_draft.body" -> "La proforma se convertira en un borrador NORMAL (sin numero VeriFactu todavia). Podras revisarla y validarla despues. Continuar?";
            case "list.dialog.proforma.convert_validate.title" -> "Convertir y validar";
            case "list.dialog.proforma.convert_validate.body" -> "La proforma se convertira en factura standard Y se validara en el mismo paso (numero, hash encadenado y QR son emitidos — irreversible). Continuar?";
            case "list.dialog.proforma.success_prefix" -> "Conversion realizada. Factura resultante: ";
            case "list.dialog.proforma.success_suffix" -> ".";
            case "list.dialog.proforma.fail.title" -> "No se pudo convertir la proforma";
            case "list.dialog.proforma.fail.body" -> "Comprueba que la proforma esta en DRAFT y tiene al menos una linea. Los logs del servidor detallan la causa.";
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
            case "list.action.generate_pdf" -> "PDF";
            case "list.action.open_pdf" -> "Abrir PDF";
            case "list.action.save_pdf" -> "Guardar PDF";
            case "list.dialog.pdf.stored_prefix" -> "PDF guardado en: ";
            case "list.dialog.pdf.stored_suffix" -> ".";
            case "list.dialog.pdf.store_failed.title" -> "No se pudo guardar el PDF";
            case "list.dialog.pdf.store_failed.body" -> "Comprueba la ruta de almacenamiento en Configuracion -> Facturacion y los permisos de disco.";
            case "list.action.send_email" -> "Enviar por email";
            case "list.dialog.email.window_title" -> "Enviar factura por email";
            case "list.dialog.email.title" -> "Enviar factura por email:";
            case "list.dialog.email.recipient_label" -> "Destinatario (vacio = email del cliente registrado):";
            case "list.dialog.email.success_prefix" -> "Factura enviada a: ";
            case "list.dialog.email.success_suffix" -> ".";
            case "list.dialog.email.fail.title" -> "No se pudo enviar el email";
            case "list.dialog.email.fail.body" -> "Comprueba la configuracion SMTP en Configuracion -> Email y que el cliente tenga un email valido registrado.";
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
            case "billing.config.field.modality" -> "Modalidad VeriFactu *";
            case "billing.config.field.mode" -> "Entorno AEAT *";
            case "billing.config.field.cert" -> "Certificado";
            case "billing.config.field.footer" -> "Pie de factura";
            case "billing.config.field.storage_root" -> "Ruta de almacenamiento de facturas";
            case "billing.config.field.storage_root.prompt" -> "ej. C:\\benjagest\\facturas o /var/benjagest/facturas (vacio = ruta por defecto del servidor)";
            case "billing.config.field.storage_root.browse" -> "Examinar…";
            case "billing.config.field.storage_root.dialog_title" -> "Selecciona carpeta para almacenar facturas";
            case "billing.config.modality.verifactu" -> "VeriFactu (envio en tiempo real a AEAT)";
            case "billing.config.modality.no_verifactu" -> "No VeriFactu (hash local + registro de eventos del SIF)";
            case "billing.config.cert.hint.empty" -> "No hay certificados subidos. Activa el modulo Documentos y sube uno en /api/certificates.";
            case "billing.config.cert.hint.count_prefix" -> " certificado(s) disponible(s).";
            case "billing.config.verifactu.save" -> "Guardar VeriFactu";
            case "billing.verifactu.save.success_prefix" -> "Configuracion VeriFactu guardada (modo ";
            case "billing.verifactu.save.success_suffix" -> ").";
            case "billing.verifactu.save.fail.title" -> "No se pudo guardar";
            case "billing.verifactu.save.fail.body" -> "Si seleccionaste PROD recuerda elegir un certificado .p12.";
            case "billing.config.verifactu.verify" -> "Verificar integridad";
            case "billing.config.verifactu.verify.off" -> "VeriFactu esta en OFF. Activa el modo TEST o PROD para empezar la cadena.";
            case "billing.config.verifactu.verify.ok.prefix" -> "Integridad de la cadena correcta. Facturas comprobadas: ";
            case "billing.config.verifactu.verify.ok.suffix" -> ".";
            case "billing.config.verifactu.verify.broken.title" -> "Cadena de integridad ROTA";
            case "billing.config.verifactu.verify.broken.prefix" -> "Primera factura sospechosa: ";
            case "billing.config.verifactu.verify.fail.title" -> "No se pudo verificar";
            case "billing.config.verifactu.verify.fail.body" -> "El servidor no respondio. Comprueba la conexion y vuelve a intentarlo.";
            case "billing.config.manufacturer.btn" -> "Acerca del SIF";
            case "billing.config.manufacturer.dialog.title" -> "Declaracion responsable del fabricante del SIF (RD 1007/2023 art. 15)";
            case "billing.config.manufacturer.section.manufacturer" -> "Fabricante / Desarrollador:";
            case "billing.config.manufacturer.section.product" -> "Producto:";
            case "billing.config.manufacturer.section.date" -> "Fecha y lugar de la declaracion:";
            case "billing.config.manufacturer.section.commitment" -> "Compromiso de cumplimiento:";
            case "billing.config.manufacturer.fail.title" -> "No se pudo cargar la declaracion del fabricante";
            case "billing.config.manufacturer.fail.body" -> "Asegurate de que el backend esta en marcha y vuelve a intentarlo.";
            case "billing.config.vat.section" -> "Tipos impositivos (IVA + retenciones)";
            case "billing.config.vat.hint" -> "Tipos de IVA y retencion configurables que el editor de facturas usa. El mismo catalogo para los dos modos (asesoria / cliente). El marcado con ★ es el que sale preseleccionado en una linea nueva. Los inactivos se conservan para facturas antiguas pero no aparecen en el combo.";
            case "billing.config.vat.placeholder.empty" -> "Sin tipos configurados.";
            case "billing.config.vat.col.kind" -> "Tipo";
            case "billing.config.vat.col.code" -> "Codigo";
            case "billing.config.vat.col.label" -> "Etiqueta";
            case "billing.config.vat.col.percent" -> "%";
            case "billing.config.vat.col.flags" -> "Estado";
            case "billing.config.vat.kind.vat" -> "IVA";
            case "billing.config.vat.kind.withholding" -> "Retencion";
            case "billing.config.vat.inactive" -> "inactivo";
            case "billing.config.vat.action.add" -> "Añadir tipo";
            case "billing.config.vat.action.edit" -> "Editar";
            case "billing.config.vat.action.delete" -> "Borrar";
            case "billing.config.vat.editor.title_new" -> "Nuevo tipo impositivo";
            case "billing.config.vat.editor.title_edit" -> "Editar tipo impositivo";
            case "billing.config.vat.editor.save" -> "Guardar";
            case "billing.config.vat.editor.kind" -> "Tipo:";
            case "billing.config.vat.editor.code" -> "Codigo:";
            case "billing.config.vat.editor.label" -> "Etiqueta:";
            case "billing.config.vat.editor.percent" -> "Porcentaje:";
            case "billing.config.vat.editor.is_default" -> "Por defecto en su categoria";
            case "billing.config.vat.editor.active" -> "Activo (visible en el combo del editor)";
            case "billing.config.vat.editor.fail.title" -> "No se pudo guardar";
            case "billing.config.vat.editor.fail.body" -> "Comprueba que el porcentaje es un numero 0-100 y que el codigo es unico.";
            case "billing.config.vat.editor.invalid_percent" -> "El porcentaje debe ser un numero entre 0 y 100.";
            case "billing.config.vat.delete.title" -> "Borrar tipo impositivo";
            case "billing.config.vat.delete.body" -> "Borrar este tipo?";
            case "billing.config.vat.delete.fail.title" -> "No se pudo borrar";
            case "billing.config.vat.delete.fail.body" -> "El tipo por defecto no se puede borrar — marca otro como predeterminado primero.";
            case "timeclock.header" -> "Fichajes (RD 8/2019)";
            case "timeclock.hint" -> "Cada fichaje es inalterable (art. 34.9). El CSV emitido te permite verificar publicamente el fichaje ante la Inspeccion de Trabajo o cualquier tercero (art. 35.8) sin credenciales.";
            case "timeclock.action.in" -> "Fichar entrada";
            case "timeclock.action.out" -> "Fichar salida";
            case "timeclock.action.break_start" -> "Iniciar pausa";
            case "timeclock.action.break_end" -> "Fin pausa";
            case "timeclock.action.refresh" -> "Refrescar";
            case "timeclock.placeholder.empty" -> "Sin fichajes todavia.";
            case "timeclock.col.when" -> "Cuando";
            case "timeclock.col.type" -> "Tipo";
            case "timeclock.col.origin" -> "Origen";
            case "timeclock.col.status" -> "Estado";
            case "timeclock.type.in" -> "Entrada";
            case "timeclock.type.out" -> "Salida";
            case "timeclock.type.break_start" -> "Inicio pausa";
            case "timeclock.type.break_end" -> "Fin pausa";
            case "timeclock.success.title" -> "Fichaje registrado";
            case "timeclock.success.csv_label" -> "Codigo de verificacion publico (CSV) — guardalo como justificante:";
            case "timeclock.fail.title" -> "No se pudo registrar el fichaje";
            case "timeclock.fail.body" -> "Comprueba que tu perfil de empleado existe y que el backend esta en marcha.";
            case "timeclock.not_enrolled.title" -> "No tienes ficha de empleado en esta empresa";
            case "timeclock.not_enrolled.body" -> "Pide al administrador que te de de alta en Personal > Empleados vinculando tu usuario. Cuando lo haga, refresca esta pantalla y podras fichar.";
            case "purchases.header" -> "Compras";
            case "purchases.hint" -> "Sube una factura recibida en PDF — BENJAGEST extrae el NIF emisor, fecha, base, IVA y total automaticamente (sin IA, solo regex sobre el texto embebido). PDFs escaneados necesitan OCR — llega en un slice posterior.";
            case "purchases.action.import_pdf" -> "Importar factura PDF";
            case "purchases.placeholder.coming_soon" -> "El modulo Compras completo (proveedores, gastos recurrentes, estado cobro) esta en hoja de ruta. Hoy solo esta disponible el importador PDF — los campos detectados se muestran para revision pero todavia no se persisten.";
            case "purchases.placeholder.empty" -> "Sin gastos registrados todavia. Usa 'Importar PDF' para anyadir el primero.";
            case "purchases.filter.year" -> "Año:";
            case "purchases.filter.quarter" -> "Trimestre:";
            case "purchases.filter.supplier" -> "Proveedor:";
            case "purchases.filter.supplier.prompt" -> "NIF o nombre";
            case "purchases.action.refresh" -> "Refrescar";
            case "purchases.action.delete" -> "Eliminar";
            case "purchases.col.date" -> "Fecha";
            case "purchases.col.supplier" -> "Proveedor";
            case "purchases.col.nif" -> "NIF";
            case "purchases.col.number" -> "Nº factura";
            case "purchases.col.base" -> "Base";
            case "purchases.col.vat" -> "IVA";
            case "purchases.col.total" -> "Total";
            case "purchases.col.journal" -> "Asiento";
            case "purchases.col.status" -> "Estado";
            case "purchases.action.validate_batch" -> "Validar seleccionados";
            case "purchases.confirm.validate_batch" -> "¿Validar {n} gastos seleccionados (en borrador)? Cada uno pasa a POSTED y se genera su asiento contable automático. Esta acción solo afecta a los marcados como DRAFT — los ya validados se ignoran.";
            case "purchases.validate_batch.result" -> "Validados: {p}\nFallidos: {f}\n\nEl listado se ha actualizado.";
            case "purchases.validate_batch.fail.title" -> "No se pudo validar el lote";
            case "purchases.import.action.save_expense" -> "💾 Guardar gasto";
            case "purchases.save.ok.title" -> "Gasto guardado";
            case "purchases.save.ok.body" -> "El gasto se ha registrado. Si la empresa tiene plan contable activo, se creo tambien el asiento contable.";
            case "purchases.save.duplicate.title" -> "Ya registrada";
            case "purchases.save.duplicate.body" -> "Esta factura ya estaba registrada (mismo SHA-256). La existente aparece en el listado.";
            case "purchases.save.fail.title" -> "No se pudo guardar el gasto";
            case "purchases.save.fail.body" -> "Comprueba la conexion e intentalo de nuevo.";
            case "purchases.save.fail.missing.title" -> "Faltan datos";
            case "purchases.save.fail.missing.body" -> "Para guardar el gasto se requiere al menos el Total (o Base + IVA).";
            case "purchases.list.fail.title" -> "No se pudieron cargar los gastos";
            case "purchases.list.fail.body" -> "Comprueba la conexion con el backend y la empresa activa.";
            case "purchases.confirm.delete.title" -> "¿Eliminar este gasto?";
            case "purchases.confirm.delete.body" -> "El gasto se borrara fisicamente de la base de datos. Si tenia asiento contable, se revertira. La eliminacion queda registrada en el log de auditoria.";
            case "purchases.delete.fail.title" -> "No se pudo eliminar";
            case "purchases.delete.fail.body" -> "Intentalo de nuevo o revisa la conexion.";
            case "purchases.import.select_pdf" -> "Seleccionar factura PDF";
            case "purchases.import.fail.title" -> "No se pudo extraer del PDF";
            case "purchases.import.fail.body" -> "Quiza el PDF este escaneado (sin texto embebido) o cifrado. Comprueba que el backend este en marcha.";
            case "purchases.import.result_prefix" -> "Campos detectados — ";
            case "purchases.import.field.supplier" -> "Proveedor:";
            case "purchases.import.field.confidence" -> "Confianza:";
            case "purchases.import.field.hash" -> "SHA-256 documento:";
            case "purchases.import.confidence.high" -> "Alta (base + IVA ≈ total)";
            case "purchases.import.confidence.medium" -> "Media (revisar campos vacios)";
            case "purchases.import.confidence.low" -> "Baja (validacion cruzada fallida)";
            case "purchases.import.action.accept" -> "Aceptar";
            case "purchases.import.action.save_template" -> "💾 Guardar plantilla para este proveedor";
            case "purchases.import.tip.edit" -> "Consejo: corrige los campos mal detectados. Si el nombre del proveedor y el %IVA son siempre los mismos para esta empresa, pulsa 'Guardar plantilla' para que las proximas importaciones del mismo NIF apliquen tu correccion automaticamente.";
            case "purchases.import.template_saved.title" -> "Plantilla guardada";
            case "purchases.import.template_saved.body" -> "Las proximas importaciones para el NIF aplicaran tus correcciones:";
            case "purchases.import.template_failed.title" -> "No se pudo guardar la plantilla";
            case "purchases.import.template_failed.body" -> "Comprueba que el NIF del proveedor este informado e intentalo de nuevo.";
            case "purchases.import.field.emitter_nif" -> "NIF emisor:";
            case "purchases.import.field.number" -> "Número factura:";
            case "purchases.import.field.date" -> "Fecha factura:";
            case "purchases.import.field.base" -> "Base imponible:";
            case "purchases.import.field.vat_pct" -> "% IVA:";
            case "purchases.import.field.vat_amount" -> "Cuota IVA:";
            case "purchases.import.field.total" -> "Total:";
            case "purchases.import.field.concept" -> "Concepto:";
            case "purchases.import.field.concept_prompt" -> "ej. Material de oficina, electricidad, …";
            case "billing.config.sif.section" -> "Registro de eventos del SIF (No VeriFactu)";
            case "billing.config.sif.hint" -> "Registro encadenado de eventos obligatorio para sistemas en No VeriFactu (RD 1007/2023, art. 16). Cada evento lleva un SHA-256 encadenado al anterior. Las operaciones de ciclo de vida y de facturacion se registran automaticamente.";
            case "billing.config.sif.placeholder.empty" -> "Aun no hay eventos SIF. Apareceran en cuanto arranque el sistema y se validen facturas.";
            case "billing.config.sif.col.when" -> "Cuando";
            case "billing.config.sif.col.type" -> "Tipo de evento";
            case "billing.config.sif.col.hash" -> "Huella";
            case "billing.config.sif.col.payload" -> "Datos";
            case "billing.config.sif.refresh" -> "Refrescar";
            case "billing.config.sif.verify" -> "Verificar cadena SIF";
            case "billing.config.sif.verify.ok.prefix" -> "Cadena SIF correcta. Eventos comprobados: ";
            case "billing.config.sif.verify.ok.suffix" -> ".";
            case "billing.config.sif.verify.broken.title" -> "Cadena SIF ROTA";
            case "billing.config.sif.verify.broken.prefix" -> "Primer evento sospechoso: ";
            case "billing.config.sif.verify.fail.title" -> "No se pudo verificar la cadena SIF";
            case "billing.config.sif.verify.fail.body" -> "El servidor no respondio. Comprueba la conexion y vuelve a intentarlo.";
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
            case "settings.tab.owners" -> "Titulares";
            case "settings.tab.email" -> "Email SMTP";
            case "settings.tab.modules" -> "Modulos";
            case "settings.tab.credentials" -> "Credenciales";
            case "settings.tab.certificate" -> "Certificado";
            case "settings.tab.audit" -> "Auditoria";
            case "settings.cert.section" -> "Certificado digital (.p12 / .pfx)";
            case "settings.cert.hint" -> "Sube tu certificado electronico (FNMT, Camerfirma, etc.) para firmar facturas VeriFactu, presentar modelos AEAT y recibir notificaciones DEHu. Si eres asesoria y tienes un cliente vinculado, ve a 'Mis clientes' y cambia al cliente para subir su certificado en su nombre — ambos lo vereis.";
            case "settings.cert.placeholder.empty" -> "Aun no hay certificados subidos";
            case "settings.cert.col.alias" -> "Alias";
            case "settings.cert.col.type" -> "Tipo";
            case "settings.cert.col.nif" -> "NIF";
            case "settings.cert.col.subject" -> "Sujeto";
            case "settings.cert.col.valid_until" -> "Valido hasta";
            case "settings.cert.col.uploaded_by" -> "Subido por";
            case "settings.cert.col.actions" -> "Acciones";
            case "settings.cert.uploaded_by.self" -> "Yo";
            case "settings.cert.uploaded_by.advisory" -> "Mi asesoria";
            case "settings.cert.action.upload" -> "Subir .p12 / .pfx";
            case "settings.cert.action.refresh" -> "Refrescar";
            case "settings.cert.action.delete" -> "Eliminar";
            case "settings.cert.confirm.delete.title" -> "¿Eliminar certificado?";
            case "settings.cert.confirm.delete.body" -> "El certificado se desactivara. Los registros firmados con el siguen siendo validos.";
            case "settings.cert.upload.title" -> "Subir certificado digital";
            case "settings.cert.upload.choose" -> "Elegir archivo";
            case "settings.cert.upload.no_file" -> "(sin archivo seleccionado)";
            case "settings.cert.upload.file" -> "Archivo:";
            case "settings.cert.upload.password" -> "Contrasena:";
            case "settings.cert.upload.inspect" -> "Inspeccionar → autorrellenar";
            case "settings.cert.upload.alias" -> "Alias:";
            case "settings.cert.upload.type" -> "Tipo:";
            case "settings.cert.upload.subject" -> "Sujeto (CN):";
            case "settings.cert.upload.nif" -> "NIF:";
            case "settings.cert.upload.valid_from" -> "Valido desde:";
            case "settings.cert.upload.valid_to" -> "Valido hasta:";
            case "settings.cert.upload.save" -> "Guardar";
            case "settings.cert.upload.tip" -> "Consejo: elige el .p12 e introduce la contrasena; pulsa 'Inspeccionar' para autorrellenar sujeto, NIF y fechas de validez directamente desde el certificado.";
            case "settings.cert.fail.list.title" -> "No se pudieron cargar los certificados";
            case "settings.cert.fail.list.body" -> "Comprueba la conexion con el backend y la empresa activa.";
            case "settings.cert.fail.delete.title" -> "No se pudo eliminar";
            case "settings.cert.fail.delete.body" -> "Es posible que el certificado ya se haya eliminado.";
            case "settings.cert.fail.read_file.title" -> "No se pudo leer el archivo";
            case "settings.cert.fail.read_file.body" -> "El archivo puede estar bloqueado o ser ilegible.";
            case "settings.cert.fail.inspect.title" -> "No se pudo leer el certificado";
            case "settings.cert.fail.inspect.body" -> "La contrasena puede estar equivocada o el archivo no es un PKCS#12 valido.";
            case "settings.cert.fail.no_file.title" -> "Sin archivo";
            case "settings.cert.fail.no_file.body" -> "Elige un .p12 o .pfx antes de guardar.";
            case "settings.cert.fail.no_alias.title" -> "Alias obligatorio";
            case "settings.cert.fail.no_alias.body" -> "Introduce un nombre corto para identificar el certificado.";
            case "settings.cert.fail.upload.title" -> "No se pudo guardar el certificado";
            case "settings.cert.fail.upload.body" -> "Si tu rol es ACCOUNTANT operando sobre un cliente, confirma que el cliente este vinculado a tu asesoria.";
            // ---- Titulares (ALTA) ----
            case "settings.owners.section" -> "Titulares y administradores";
            case "settings.owners.section_label" -> "Titulares";
            case "settings.owners.hint" -> "Personas con poder de administracion o participacion en la empresa. Necesario para modelo 200 (Impuesto Sociedades) y comunicaciones de Seguridad Social.";
            case "settings.owners.placeholder.empty" -> "Aun no hay titulares registrados.";
            case "settings.owners.col.name" -> "Nombre completo";
            case "settings.owners.col.nif" -> "NIF";
            case "settings.owners.col.role" -> "Rol";
            case "settings.owners.col.ss_regime" -> "Reg. SS";
            case "settings.owners.col.pct" -> "% Participacion";
            case "settings.owners.col.flags" -> "";
            case "settings.owners.inactive" -> "(inactivo)";
            case "settings.owners.role.ADMINISTRATOR" -> "Administrador";
            case "settings.owners.role.JOINT" -> "Adm. mancomunado";
            case "settings.owners.role.SOLE" -> "Adm. unico";
            case "settings.owners.role.BOARD_MEMBER" -> "Consejero";
            case "settings.owners.role.PARTNER" -> "Socio";
            case "settings.owners.role.AUTONOMOUS" -> "Autonomo";
            case "settings.owners.ss_regime.RETA" -> "RETA";
            case "settings.owners.ss_regime.GENERAL" -> "Reg. General";
            case "settings.owners.ss_regime.AUTONOMO_SOCIETARIO" -> "RETA Societario";
            case "settings.owners.ss_regime.NO_COTIZA" -> "No cotiza";
            case "settings.owners.ss_regime.OTHER" -> "Otro";
            case "settings.owners.action.add" -> "Anadir titular";
            case "settings.owners.action.edit" -> "Editar";
            case "settings.owners.action.delete" -> "Borrar";
            case "settings.owners.editor.title_new" -> "Nuevo titular";
            case "settings.owners.editor.title_edit" -> "Editar titular";
            case "settings.owners.editor.save" -> "Guardar";
            case "settings.owners.editor.name" -> "Nombre completo";
            case "settings.owners.editor.nif" -> "NIF";
            case "settings.owners.editor.role" -> "Rol";
            case "settings.owners.editor.ss_regime" -> "Regimen SS";
            case "settings.owners.editor.pct" -> "% Participacion (0-100)";
            case "settings.owners.editor.appointment" -> "Fecha de nombramiento";
            case "settings.owners.editor.termination" -> "Fecha de cese";
            case "settings.owners.editor.email" -> "Email";
            case "settings.owners.editor.phone" -> "Telefono";
            case "settings.owners.editor.notes" -> "Notas";
            case "settings.owners.editor.active" -> "Activo";
            case "settings.owners.editor.invalid_pct" -> "Porcentaje invalido.";
            case "settings.owners.editor.fail.title" -> "No se pudo guardar";
            case "settings.owners.editor.fail.body" -> "Revisa los datos e intentalo de nuevo.";
            case "settings.owners.delete.title" -> "Borrar titular?";
            case "settings.owners.delete.body" -> "Vas a borrar a";
            // ---- Credenciales (ALTA) ----
            case "settings.credentials.section" -> "Credenciales externas";
            case "settings.credentials.section_label" -> "Credenciales";
            case "settings.credentials.hint" -> "Contrasenas cifradas para sistemas externos (DEHu, SS RED, SILTRA, AEAT Cl@ve...). Se guardan cifradas y no se muestran nunca de nuevo.";
            case "settings.credentials.placeholder.empty" -> "No hay credenciales registradas.";
            case "settings.credentials.col.system" -> "Sistema";
            case "settings.credentials.col.label" -> "Etiqueta";
            case "settings.credentials.col.user" -> "Usuario";
            case "settings.credentials.col.password" -> "Contrasena";
            case "settings.credentials.col.flags" -> "";
            case "settings.credentials.empty" -> "—";
            case "settings.credentials.inactive" -> "(inactivo)";
            case "settings.credentials.system.DEHU" -> "DEHu";
            case "settings.credentials.system.SS_RED" -> "SS RED";
            case "settings.credentials.system.SILTRA" -> "SILTRA";
            case "settings.credentials.system.AEAT_CLAVE" -> "AEAT Cl@ve";
            case "settings.credentials.system.NOTIFICA_GOB" -> "Notifica.gob";
            case "settings.credentials.system.SEDE_AEAT" -> "Sede AEAT";
            case "settings.credentials.system.BANCO_ESPANA" -> "Banco de Espana";
            case "settings.credentials.system.OTHER" -> "Otro";
            case "settings.credentials.action.add" -> "Anadir credencial";
            case "settings.credentials.action.edit" -> "Editar";
            case "settings.credentials.action.delete" -> "Borrar";
            case "settings.credentials.editor.title_new" -> "Nueva credencial";
            case "settings.credentials.editor.title_edit" -> "Editar credencial";
            case "settings.credentials.editor.save" -> "Guardar";
            case "settings.credentials.editor.system" -> "Sistema";
            case "settings.credentials.editor.label" -> "Etiqueta";
            case "settings.credentials.editor.username" -> "Usuario";
            case "settings.credentials.editor.password" -> "Contrasena";
            case "settings.credentials.editor.password.keep" -> "(en blanco para mantener la actual)";
            case "settings.credentials.editor.password.new" -> "Introduce la contrasena";
            case "settings.credentials.editor.auth_url" -> "URL de acceso";
            case "settings.credentials.editor.notes" -> "Notas";
            case "settings.credentials.editor.active" -> "Activa";
            case "settings.credentials.editor.fail.title" -> "No se pudo guardar";
            case "settings.credentials.editor.fail.body" -> "Revisa los datos e intentalo de nuevo.";
            case "settings.credentials.delete.title" -> "Borrar credencial?";
            case "settings.credentials.delete.body" -> "Vas a borrar la credencial de";
            case "settings.credentials.log.section" -> "Log de uso de certificados";
            case "settings.credentials.log.hint" -> "Cada vez que se usa un certificado digital para firmar o enviar algo, queda registrado aqui para trazabilidad.";
            case "settings.credentials.log.placeholder.empty" -> "Aun no hay eventos de uso.";
            case "settings.credentials.log.col.when" -> "Cuando";
            case "settings.credentials.log.col.cert" -> "Certificado";
            case "settings.credentials.log.col.purpose" -> "Proposito";
            case "settings.credentials.log.col.result" -> "Resultado";
            case "settings.credentials.log.col.user" -> "Usuario";
            case "settings.credentials.log.col.ip" -> "IP";
            case "settings.credentials.log.col.message" -> "Detalle";
            case "settings.credentials.log.refresh" -> "Refrescar";
            // ---- Asesoria (ALTA) ----
            case "advisory.title" -> "Mis clientes";
            case "advisory.subtitle" -> "Cambia de tenant para operar como uno de tus clientes.";
            case "advisory.hint" -> "Haz doble click sobre una fila o usa 'Cambiar' para operar en el contexto de ese cliente. Tu sesion no se invalida; solo cambia el tenant activo.";
            case "advisory.placeholder.empty" -> "No hay clientes vinculados a esta asesoria.";
            case "advisory.col.legal_name" -> "Razon social";
            case "advisory.col.nif" -> "NIF";
            case "advisory.col.type" -> "Tipo";
            case "advisory.col.city" -> "Localidad";
            case "advisory.col.email" -> "Email";
            case "advisory.action.switch" -> "Cambiar a este cliente";
            case "advisory.switch.title" -> "Cambiar de tenant?";
            case "advisory.switch.body" -> "Las siguientes peticiones operaran como";
            case "advisory.load_failed" -> "No se pudo cargar la lista de clientes.";
            // ---- Modelos AEAT (ALTA) ----
            case "tax.title" -> "Modelos AEAT";
            case "tax.subtitle" -> "Declaraciones trimestrales y anuales: 303, 130, 200, 347, 390 y otros.";
            case "tax.load_failed" -> "No se pudieron cargar los modelos.";
            case "tax.year" -> "Ano";
            case "tax.action.new" -> "Nueva declaracion";
            case "tax.tab.filings" -> "Declaraciones";
            case "tax.tab.calendar" -> "Calendario";
            case "tax.filings.placeholder.empty" -> "No hay declaraciones para este ano.";
            case "tax.filings.col.model" -> "Modelo";
            case "tax.filings.col.period" -> "Periodo";
            case "tax.filings.col.status" -> "Estado";
            case "tax.filings.col.amount" -> "Importe";
            case "tax.filings.col.deadline" -> "Limite";
            case "tax.filings.col.csv" -> "CSV AEAT";
            case "tax.filings.action.edit" -> "Editar";
            case "tax.filings.action.delete" -> "Borrar";
            case "tax.filings.delete.title" -> "Borrar declaracion?";
            case "tax.filings.delete.body" -> "Vas a borrar";
            case "tax.filings.delete.fail.title" -> "No se pudo borrar";
            case "tax.filings.delete.fail.body" -> "Solo se pueden borrar borradores o canceladas.";
            case "tax.calendar.hint" -> "Plazos estandar AEAT del ano seleccionado. No se consideran festivos — comprueba el calendario oficial.";
            case "tax.calendar.placeholder.empty" -> "No hay vencimientos para este ano.";
            case "tax.calendar.col.deadline" -> "Limite";
            case "tax.calendar.col.model" -> "Modelo";
            case "tax.calendar.col.name" -> "Nombre";
            case "tax.calendar.col.period" -> "Periodo";
            case "tax.calendar.col.state" -> "Estado";
            case "tax.calendar.state.pending" -> "Pendiente";
            case "tax.status.DRAFT" -> "Borrador";
            case "tax.status.READY" -> "Listo";
            case "tax.status.PRESENTED" -> "Presentado";
            case "tax.status.PAID" -> "Pagado";
            case "tax.status.REJECTED" -> "Rechazado";
            case "tax.status.CANCELLED" -> "Cancelado";
            case "tax.new.title" -> "Nueva declaracion";
            case "tax.new.next" -> "Continuar";
            case "tax.new.model" -> "Modelo";
            case "tax.new.year" -> "Ano";
            case "tax.new.period" -> "Periodo";
            case "tax.new.fail.title" -> "No se pudo crear";
            case "tax.new.fail.body" -> "Revisa modelo, periodo y ano.";
            case "tax.editor.generic.title" -> "Editar declaracion";
            case "tax.editor.save" -> "Guardar";
            case "tax.editor.status" -> "Estado";
            case "tax.editor.total" -> "Importe total";
            case "tax.editor.csv" -> "CSV AEAT";
            case "tax.editor.data" -> "Datos del modelo (JSON)";
            case "tax.editor.notes" -> "Notas";
            case "tax.editor.fail.title" -> "No se pudo guardar";
            case "tax.editor.fail.body" -> "Revisa los datos e intentalo de nuevo.";
            case "settings.company.section_label" -> "Empresa";
            case "settings.company.section.general" -> "Datos generales";
            case "settings.company.section.address" -> "Direccion postal";
            case "settings.company.section.billing" -> "Datos de facturacion";
            case "settings.company.section.billing.hint" -> "Solo datos administrativos de facturacion. Los textos por factura (pie, condiciones legales, exencion IVA, IVA reducido, aviso rectificativa) viven en Facturacion → Configuracion → Textos legales.";
            case "settings.company.billing_note" -> "ℹ El pie de factura, condiciones legales y demas textos por factura se configuran en Facturacion → Configuracion → Textos legales. Asi no hay dos sitios para editar lo mismo.";
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
            case "settings.audit.col.seq" -> "Seq";
            case "settings.audit.col.hash" -> "Hash (12)";
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
            case "list.dialog.void.body" -> "Se EMITIRÁ una factura RECTIFICATIVA enlazada (líneas con cantidad invertida) y la original quedará ANULADA en la misma transacción. Es un acto legal y NO se puede deshacer ni editar. ¿Continuar?";
            case "list.dialog.void.success_prefix" -> "Rectificativa emitida con nº ";
            case "list.dialog.void.success_suffix" -> ". La factura original ha quedado anulada.";
            case "list.dialog.void.failure.title" -> "No se pudo anular";
            case "list.dialog.void.failure.body" -> "Comprueba que la factura este VALIDATED y que aun no tenga rectificativa enlazada.";
            case "editor.rectifying.pill_prefix" -> "Rectificativa de ";
            // ---- F4b PDF download ----
            case "list.dialog.pdf.save_title" -> "Guardar PDF de la factura";
            case "list.dialog.pdf.filter" -> "Documentos PDF";
            case "list.dialog.pdf.success_title" -> "PDF guardado";
            case "list.dialog.pdf.success_prefix" -> "Guardado como ";
            case "list.dialog.pdf.success_suffix" -> ". ¿Abrirlo ahora?";
            case "list.dialog.pdf.open_failed.title" -> "No se pudo abrir el PDF";
            case "list.dialog.pdf.open_failed.body" -> "Abrelo manualmente desde la carpeta elegida.";
            case "list.dialog.pdf.save_failed.title" -> "No se pudo guardar el PDF";
            case "list.dialog.pdf.save_failed.body" -> "Comprueba los permisos de la carpeta y vuelve a intentarlo.";
            case "list.dialog.pdf.download_failed.title" -> "No se pudo generar el PDF";
            case "list.dialog.pdf.download_failed.body" -> "Comprueba que el backend este corriendo y que la factura exista.";
            // ---- Invoice statuses ----
            case "status.invoice.draft" -> "Borrador";
            case "status.invoice.validated" -> "Validada";
            case "status.invoice.cancelled" -> "Cancelada";
            case "status.invoice.voided" -> "Anulada";
            case "status.payment.pending" -> "Pendiente";
            case "status.payment.partial" -> "Parcial";
            case "status.payment.paid" -> "Pagada";
            case "status.payment.overdue" -> "Vencida";
            default -> {
                String v = tNewModulesEs(key);
                if (v == null) v = tAdvisoryInvitationsEs(key);
                if (v == null) v = tExportsAndChainEs(key);
                if (v != null) yield v;
                yield key.startsWith("column.") ? key.substring(7) : switch (key) {
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
            }
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

    private record ModuleLink(String id, String title, String icon, boolean advisoryOnly) {
        public ModuleLink(String id, String title, String icon) { this(id, title, icon, false); }
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


    /**
     * Bloque i18n extraído del método t() principal para no rebasar el
     * límite JVM de 64KB por método. Devuelve null si la key no
     * pertenece a estos módulos — entonces el caller cae al default.
     */
    /**
     * Helper i18n EN para las keys de export e integridad de auditoría.
     * Se extrae como helper aparte porque el método {@code t} principal
     * estaba rozando el límite de 64KB por método de la JVM. Mismo
     * patrón que tNewModulesEn / tAdvisoryInvitationsEn.
     */
    private String tExportsAndChainEn(String key) {
        return switch (key) {
            case "timeclock.export.title" -> "Export for inspection";
            case "timeclock.export.hint" -> "Download a verifiable PDF or CSV of the time-clock log. Each row keeps its CSV verification code (RD 8/2019 art. 35.8). The audit log records each export with the document SHA-256 to detect later tampering.";
            case "timeclock.export.from" -> "From";
            case "timeclock.export.to" -> "To";
            case "timeclock.export.pdf" -> "Download PDF";
            case "timeclock.export.csv" -> "Download CSV";
            case "timeclock.export.ok.title" -> "Export saved";
            case "timeclock.export.ok.body" -> "File saved to:";
            case "timeclock.export.fail.title" -> "Export failed";
            case "timeclock.export.fail.body" -> "Could not generate the export. Check the date range and try again.";
            case "timeclock.export.fail.range.title" -> "Invalid range";
            case "timeclock.export.fail.range.body" -> "Choose a start date earlier than or equal to the end date.";
            case "timeclock.export.fail.write.title" -> "Could not save file";
            case "settings.audit.export.title" -> "Export for inspection / tax office";
            case "settings.audit.export.hint" -> "Download a verifiable PDF or CSV of the full audit log for a date range. Each export is itself recorded with the document SHA-256 so the file shown to an inspector can be checked against the registry.";
            case "settings.audit.export.from" -> "From";
            case "settings.audit.export.to" -> "To";
            case "settings.audit.export.pdf" -> "Download PDF";
            case "settings.audit.export.csv" -> "Download CSV";
            case "settings.audit.export.ok.title" -> "Export saved";
            case "settings.audit.export.ok.body" -> "File saved to:";
            case "settings.audit.export.fail.title" -> "Export failed";
            case "settings.audit.export.fail.body" -> "Could not generate the export. Check the date range and try again.";
            case "settings.audit.export.fail.range.title" -> "Invalid range";
            case "settings.audit.export.fail.range.body" -> "Choose a start date earlier than or equal to the end date.";
            case "settings.audit.export.fail.write.title" -> "Could not save file";
            case "settings.audit.btn.verify" -> "Verify chain";
            case "settings.audit.verify.ok.title" -> "Chain intact";
            case "settings.audit.verify.ok.body" -> "All events recompute correctly. No tampering detected.";
            case "settings.audit.verify.fail.title" -> "Chain broken";
            case "settings.audit.verify.fail.body" -> "The audit chain has a corrupted event. Manipulation is likely.";
            case "settings.audit.verify.events" -> "events verified";
            case "billing.config.sif.export.title" -> "Export SIF events for inspection";
            case "billing.config.sif.export.hint" -> "Download a verifiable PDF/CSV of the SIF event registry (RD 1007/2023 + Order HAC/1177/2024, event 9: \"Export of event records for a period\"). The export itself appends an EXPORT_EVENTS event to the SIF chain AND records the document SHA-256 in audit_events.";
            case "billing.config.sif.export.from" -> "From";
            case "billing.config.sif.export.to" -> "To";
            case "billing.config.sif.export.pdf" -> "Download PDF";
            case "billing.config.sif.export.csv" -> "Download CSV";
            default -> null;
        };
    }

    private String tExportsAndChainEs(String key) {
        return switch (key) {
            case "timeclock.export.title" -> "Exportar para Inspeccion";
            case "timeclock.export.hint" -> "Descarga un PDF o CSV verificable del registro de fichajes. Cada fila conserva su CSV de verificacion (RD 8/2019 art. 35.8). La auditoria registra cada exportacion con el SHA-256 del documento para detectar manipulaciones posteriores.";
            case "timeclock.export.from" -> "Desde";
            case "timeclock.export.to" -> "Hasta";
            case "timeclock.export.pdf" -> "Descargar PDF";
            case "timeclock.export.csv" -> "Descargar CSV";
            case "timeclock.export.ok.title" -> "Exportacion guardada";
            case "timeclock.export.ok.body" -> "Archivo guardado en:";
            case "timeclock.export.fail.title" -> "Error al exportar";
            case "timeclock.export.fail.body" -> "No se pudo generar la exportacion. Revisa el rango de fechas y vuelve a intentarlo.";
            case "timeclock.export.fail.range.title" -> "Rango no valido";
            case "timeclock.export.fail.range.body" -> "Elige una fecha de inicio anterior o igual a la fecha de fin.";
            case "timeclock.export.fail.write.title" -> "No se pudo guardar el archivo";
            case "settings.audit.export.title" -> "Exportar para Inspeccion / Hacienda";
            case "settings.audit.export.hint" -> "Descarga un PDF o CSV verificable del registro completo de auditoria en un rango de fechas. Cada exportacion queda a su vez registrada con el SHA-256 del documento para que el fichero que enseñes al inspector se pueda contrastar con el registro.";
            case "settings.audit.export.from" -> "Desde";
            case "settings.audit.export.to" -> "Hasta";
            case "settings.audit.export.pdf" -> "Descargar PDF";
            case "settings.audit.export.csv" -> "Descargar CSV";
            case "settings.audit.export.ok.title" -> "Exportacion guardada";
            case "settings.audit.export.ok.body" -> "Archivo guardado en:";
            case "settings.audit.export.fail.title" -> "Error al exportar";
            case "settings.audit.export.fail.body" -> "No se pudo generar la exportacion. Revisa el rango de fechas y vuelve a intentarlo.";
            case "settings.audit.export.fail.range.title" -> "Rango no valido";
            case "settings.audit.export.fail.range.body" -> "Elige una fecha de inicio anterior o igual a la fecha de fin.";
            case "settings.audit.export.fail.write.title" -> "No se pudo guardar el archivo";
            case "settings.audit.btn.verify" -> "Verificar cadena";
            case "settings.audit.verify.ok.title" -> "Cadena integra";
            case "settings.audit.verify.ok.body" -> "Todos los eventos recalculan correctamente. No se detecta manipulacion.";
            case "settings.audit.verify.fail.title" -> "Cadena rota";
            case "settings.audit.verify.fail.body" -> "La cadena de auditoria tiene un evento corrupto. Es probable que se haya manipulado.";
            case "settings.audit.verify.events" -> "eventos verificados";
            case "billing.config.sif.export.title" -> "Exportar eventos SIF para Inspeccion";
            case "billing.config.sif.export.hint" -> "Descarga un PDF/CSV verificable del Registro de Eventos del SIF (RD 1007/2023 + Orden HAC/1177/2024, evento 9: \"Exportacion de registros de eventos de un periodo\"). La propia exportacion añade un evento EXPORT_EVENTS a la cadena SIF Y registra el SHA-256 del documento en audit_events.";
            case "billing.config.sif.export.from" -> "Desde";
            case "billing.config.sif.export.to" -> "Hasta";
            case "billing.config.sif.export.pdf" -> "Descargar PDF";
            case "billing.config.sif.export.csv" -> "Descargar CSV";
            default -> null;
        };
    }

    private String tNewModulesEn(String key) {
        return switch (key) {
            // ---- Labor module (L1) ----
            case "labor.title" -> "HR / Personnel";
            case "labor.subtitle" -> "Workforce, contracts, time tracking and payslips — all in one place.";
            case "labor.tab.employees" -> "Employees";
            case "labor.tab.contracts" -> "Contracts";
            case "labor.tab.timeclock" -> "Time clock";
            case "labor.tab.payslips" -> "Payslips";
            case "labor.tab.cfg_timeclock" -> "Time clock settings";
            case "labor.tab.audit" -> "Audit";
            // ---- TC-AUDIT ----
            case "labor.audit.hint" -> "Time clock audit for RD 8/2019 art. 34.9 (immutability) and 35.8 (public verification). Filter by range, employee or event type. Records are 4-year retained.";
            case "labor.audit.filter.from" -> "From";
            case "labor.audit.filter.to" -> "To";
            case "labor.audit.filter.employee" -> "Employee";
            case "labor.audit.filter.type" -> "Type";
            case "labor.audit.action.reload" -> "Reload";
            case "labor.audit.action.export" -> "Export…";
            case "labor.audit.export.tooltip" -> "PDF/CSV export with public verification CSV (TC-EXPORT, next slice).";
            case "labor.audit.section.summary" -> "Summary by employee";
            case "labor.audit.section.detail" -> "Event detail";
            case "labor.audit.summary.placeholder.empty" -> "No events in the selected range.";
            case "labor.audit.detail.placeholder.empty" -> "No events match the filters.";
            case "labor.audit.col.employee" -> "Employee";
            case "labor.audit.col.total" -> "Total";
            case "labor.audit.col.ins" -> "IN";
            case "labor.audit.col.outs" -> "OUT";
            case "labor.audit.col.pauses" -> "Pauses";
            case "labor.audit.col.corrections" -> "Corrections";
            case "labor.audit.col.incidence" -> "Incidence";
            case "labor.audit.incidence.yes" -> "Review";
            case "labor.audit.incidence.no" -> "OK";
            case "labor.audit.col.when" -> "Date/time";
            case "labor.audit.col.type" -> "Type";
            case "labor.audit.col.origin" -> "Origin";
            case "labor.audit.col.status" -> "Status";
            case "labor.audit.col.has_corrections" -> "Corr.";
            case "labor.audit.col.csv" -> "CSV";
            // ---- TC-CFG ----
            case "labor.cfg_timeclock.hint" -> "Configure the punch buttons your employees see. Codes are sent to the server (e.g. IN, LUNCH, MEETING). Labels are what the user reads. Order matters for the row.";
            case "labor.cfg_timeclock.placeholder.empty" -> "No event types configured.";
            case "labor.cfg_timeclock.col.order" -> "Order";
            case "labor.cfg_timeclock.col.code" -> "Code";
            case "labor.cfg_timeclock.col.label_es" -> "Label ES";
            case "labor.cfg_timeclock.col.label_en" -> "Label EN";
            case "labor.cfg_timeclock.col.flags" -> "Flags";
            case "labor.cfg_timeclock.inactive" -> "(inactive)";
            case "labor.cfg_timeclock.legend" -> "▶ = opens work time (counts as working) · ⏸ = pause (doesn't count as working) · (inactive) = button hidden from kiosks and apps";
            case "labor.cfg_timeclock.action.add" -> "Add event type";
            case "labor.cfg_timeclock.action.edit" -> "Edit";
            case "labor.cfg_timeclock.action.delete" -> "Delete";
            case "labor.cfg_timeclock.editor.title_new" -> "New event type";
            case "labor.cfg_timeclock.editor.title_edit" -> "Edit event type";
            case "labor.cfg_timeclock.editor.save" -> "Save";
            case "labor.cfg_timeclock.editor.code" -> "Code (UPPERCASE)";
            case "labor.cfg_timeclock.editor.label_es" -> "Label (Spanish)";
            case "labor.cfg_timeclock.editor.label_en" -> "Label (English)";
            case "labor.cfg_timeclock.editor.icon" -> "Icon (FontAwesome)";
            case "labor.cfg_timeclock.editor.order" -> "Display order";
            case "labor.cfg_timeclock.editor.is_work_time" -> "Opens work time (▶)";
            case "labor.cfg_timeclock.editor.is_pause" -> "Is a pause (⏸)";
            case "labor.cfg_timeclock.editor.active" -> "Active";
            case "labor.cfg_timeclock.editor.flags_hint" -> "If unsure: IN/end-of-break are work time; OUT/start-of-break are not. A pause means the worker stays in the shift but stops counting hours (lunch, coffee).";
            case "labor.cfg_timeclock.editor.fail.title" -> "Could not save";
            case "labor.cfg_timeclock.editor.fail.body" -> "Check the data and try again.";
            case "labor.cfg_timeclock.delete.title" -> "Delete event type?";
            case "labor.cfg_timeclock.delete.body" -> "If there are historical punches with this code, it will be deactivated instead of deleted. Code:";
            // ---- contracts global ----
            case "labor.contracts.placeholder.empty.global" -> "No contracts registered yet.";
            case "labor.contracts.col.employee" -> "Employee";
            case "labor.contracts.global.hint" -> "All contracts for the company. Sort by any column. To edit a contract or add a new one, go to the employee detail.";
            // ---- timeclock ----
            case "timeclock.employee.label" -> "Punching as";
            case "timeclock.error.no_employee.title" -> "No employee selected";
            case "timeclock.error.no_employee.body" -> "Choose an employee from the list before punching.";
            // ---- payslips ----
            case "labor.payslips.placeholder.empty" -> "No payslips yet for this year.";
            case "labor.payslips.hint" -> "Calculate, store and send payslips. SS employee = 6.35% of gross; IRPF % from contract or estimated from yearly brackets.";
            case "labor.payslips.col.period" -> "Period";
            case "labor.payslips.col.employee" -> "Employee";
            case "labor.payslips.col.type" -> "Type";
            case "labor.payslips.col.gross" -> "Gross";
            case "labor.payslips.col.ss" -> "SS 6.35%";
            case "labor.payslips.col.irpf" -> "IRPF";
            case "labor.payslips.col.net" -> "Net";
            case "labor.payslips.col.status" -> "Status";
            case "labor.payslips.type.MONTHLY" -> "Monthly";
            case "labor.payslips.type.EXTRA_SUMMER" -> "Summer bonus";
            case "labor.payslips.type.EXTRA_CHRISTMAS" -> "Christmas bonus";
            case "labor.payslips.type.BONUS" -> "Bonus";
            case "labor.payslips.type.SETTLEMENT" -> "Settlement";
            case "labor.payslips.status.DRAFT" -> "Draft";
            case "labor.payslips.status.CALCULATED" -> "Calculated";
            case "labor.payslips.status.PAID" -> "Paid";
            case "labor.payslips.status.CANCELLED" -> "Cancelled";
            case "labor.payslips.action.calculate" -> "Calculate payslip";
            case "labor.payslips.action.pay" -> "Mark as paid";
            case "labor.payslips.action.pdf" -> "Download PDF";
            case "labor.payslips.action.email" -> "Send by email";
            case "labor.payslips.action.delete" -> "Delete";
            case "labor.payslips.calc.title" -> "Calculate payslip";
            case "labor.payslips.calc.save" -> "Calculate";
            case "labor.payslips.calc.employee" -> "Employee";
            case "labor.payslips.calc.year" -> "Year";
            case "labor.payslips.calc.month" -> "Month";
            case "labor.payslips.calc.type" -> "Type";
            case "labor.payslips.calc.extra_prorated" -> "Prorate extra bonuses";
            case "labor.payslips.calc.other_deductions" -> "Other deductions (€)";
            case "labor.payslips.calc.other_deductions.prompt" -> "Optional";
            case "labor.payslips.calc.notes" -> "Notes";
            case "labor.payslips.calc.fail.title" -> "Could not calculate";
            case "labor.payslips.calc.fail.body" -> "Check the contract data and try again.";
            case "labor.payslips.calc.fail.no_employee" -> "Select an employee.";
            case "labor.payslips.pay.title" -> "Mark as paid?";
            case "labor.payslips.pay.body" -> "You are about to mark the payslip as paid for";
            case "labor.payslips.pdf.save_as" -> "Save payslip as…";
            case "labor.payslips.pdf.fail.title" -> "Could not generate PDF";
            case "labor.payslips.pdf.fail.body" -> "Try again later.";
            case "labor.payslips.email.title" -> "Send payslip by email?";
            case "labor.payslips.email.body" -> "The payslip will be sent to the employee email of";
            case "labor.payslips.email.ok.title" -> "Email sent";
            case "labor.payslips.email.ok.body" -> "The employee has received the payslip.";
            case "labor.payslips.email.fail.title" -> "Could not send";
            case "labor.payslips.email.fail.body" -> "Check SMTP configuration and that the employee has an email.";
            case "labor.payslips.delete.title" -> "Delete payslip?";
            case "labor.payslips.delete.body" -> "You are about to delete the payslip of";
            case "labor.title.old" -> "Employees";
            case "labor.load_failed" -> "Could not load the employee list.";
            case "labor.action.new_employee" -> "New employee";
            case "labor.employees.placeholder.empty" -> "No employees yet.";
            case "labor.employees.col.name" -> "Full name";
            case "labor.employees.col.nif" -> "Tax ID";
            case "labor.employees.col.nuss" -> "Social Security #";
            case "labor.employees.col.hire_date" -> "Hire date";
            case "labor.employees.col.ss" -> "SS regime";
            case "labor.employees.col.flags" -> "";
            case "labor.employees.inactive" -> "(inactive)";
            case "labor.employees.action.edit" -> "Edit";
            case "labor.employees.action.contracts" -> "Contracts…";
            case "labor.employees.action.delete" -> "Deactivate";
            case "labor.employee.editor.title_new" -> "New employee";
            case "labor.employee.editor.title_edit" -> "Edit employee";
            case "labor.employee.editor.save" -> "Save";
            case "labor.employee.editor.active" -> "Active";
            case "labor.employee.section.identity" -> "Identity";
            case "labor.employee.section.contact" -> "Contact and address";
            case "labor.employee.section.work" -> "Employment data";
            case "labor.employee.editor.name" -> "Full name";
            case "labor.employee.editor.nif" -> "Tax ID";
            case "labor.employee.editor.nuss" -> "SS number";
            case "labor.employee.editor.birth" -> "Birth date";
            case "labor.employee.editor.gender" -> "Gender";
            case "labor.employee.editor.marital" -> "Marital status";
            case "labor.employee.editor.children" -> "Dependent children";
            case "labor.employee.editor.disabled" -> "Dependent disabled";
            case "labor.employee.editor.email" -> "Email";
            case "labor.employee.editor.phone" -> "Phone";
            case "labor.employee.editor.address" -> "Address";
            case "labor.employee.editor.city" -> "City";
            case "labor.employee.editor.province" -> "Province";
            case "labor.employee.editor.postal" -> "Postal code";
            case "labor.employee.editor.country" -> "Country";
            case "labor.employee.editor.iban" -> "IBAN (payroll)";
            case "labor.employee.editor.work_type" -> "Work type";
            case "labor.employee.editor.ss_regime" -> "SS regime";
            case "labor.employee.editor.hire" -> "Hire date";
            case "labor.employee.editor.termination" -> "Termination date";
            case "labor.employee.editor.term_reason" -> "Termination reason";
            case "labor.employee.editor.geolocation" -> "Allow geolocation when punching";
            case "labor.employee.editor.geolocation.hint" -> "If active, mobile/web punch will request the GPS position and store it with the event. RGPD: only with explicit consent. Default off.";
            case "labor.employees.col.geo" -> "Geo";
            case "labor.employee.editor.fail.title" -> "Could not save";
            case "labor.employee.editor.fail.body" -> "Check the data and try again.";
            case "labor.employee.delete.title" -> "Deactivate employee?";
            case "labor.employee.delete.body" -> "You are about to deactivate";
            case "labor.contracts.dialog.title" -> "Contracts";
            case "labor.contracts.load.fail" -> "Could not load contracts";
            case "labor.contracts.load.fail.body" -> "Try again later.";
            case "labor.contracts.placeholder.empty" -> "This employee has no contracts yet.";
            case "labor.contracts.col.type" -> "Type";
            case "labor.contracts.col.sepe" -> "SEPE";
            case "labor.contracts.col.start" -> "Start";
            case "labor.contracts.col.end" -> "End";
            case "labor.contracts.col.salary" -> "Annual salary";
            case "labor.contracts.col.status" -> "Status";
            case "labor.contracts.action.new" -> "New contract";
            case "labor.contracts.action.edit" -> "Edit";
            case "labor.contract.editor.title_new" -> "New contract";
            case "labor.contract.editor.title_edit" -> "Edit contract";
            case "labor.contract.editor.save" -> "Save";
            case "labor.contract.editor.type" -> "Type";
            case "labor.contract.editor.sepe" -> "SEPE code";
            case "labor.contract.editor.agreement" -> "Collective agreement";
            case "labor.contract.editor.category" -> "Category";
            case "labor.contract.editor.group" -> "Group";
            case "labor.contract.editor.start" -> "Start date";
            case "labor.contract.editor.end" -> "End date";
            case "labor.contract.editor.weekly_hours" -> "Weekly hours";
            case "labor.contract.editor.salary" -> "Annual gross salary";
            case "labor.contract.editor.bonuses" -> "Annual bonuses";
            case "labor.contract.editor.vacation" -> "Vacation days";
            case "labor.contract.editor.irpf" -> "IRPF %";
            case "labor.contract.editor.workplace" -> "Workplace address";
            case "labor.contract.editor.status" -> "Status";
            case "labor.contract.editor.fail.title" -> "Could not save";
            case "labor.contract.editor.fail.body" -> "Check the data and try again.";
            // ---- RETA (L2) ----
            case "reta.title" -> "Self-employed (RETA)";
            case "reta.subtitle" -> "Profiles, contribution bases and base changes.";
            case "reta.load_failed" -> "Could not load RETA profiles.";
            case "reta.action.new" -> "New profile";
            case "reta.action.suggest_tramo" -> "Bracket calculator";
            case "reta.placeholder.empty" -> "No RETA profiles yet.";
            case "reta.col.name" -> "Full name";
            case "reta.col.nif" -> "Tax ID";
            case "reta.col.base" -> "Current base";
            case "reta.col.quota" -> "Current quota";
            case "reta.col.flags" -> "Flags";
            case "reta.inactive" -> "(inactive)";
            case "reta.action.edit" -> "Edit";
            case "reta.action.changes" -> "Base changes…";
            case "reta.action.delete" -> "Deactivate";
            case "reta.editor.title_new" -> "New RETA profile";
            case "reta.editor.title_edit" -> "Edit RETA profile";
            case "reta.editor.save" -> "Save";
            case "reta.editor.name" -> "Full name";
            case "reta.editor.nif" -> "Tax ID";
            case "reta.editor.nuss" -> "SS number";
            case "reta.editor.start" -> "RETA start";
            case "reta.editor.end" -> "RETA end";
            case "reta.editor.pluriactividad" -> "Pluriactividad";
            case "reta.editor.tarifa_plana" -> "Flat rate";
            case "reta.editor.tarifa_until" -> "Flat rate until";
            case "reta.editor.activity_code" -> "Activity code";
            case "reta.editor.activity_desc" -> "Activity description";
            case "reta.editor.iae" -> "IAE epigraph";
            case "reta.editor.net_income" -> "Expected annual net income";
            case "reta.editor.base" -> "Current base";
            case "reta.editor.quota" -> "Current quota";
            case "reta.editor.notes" -> "Notes";
            case "reta.editor.active" -> "Active";
            case "reta.editor.fail.title" -> "Could not save";
            case "reta.editor.fail.body" -> "Check the data and try again.";
            case "reta.delete.title" -> "Deactivate RETA profile?";
            case "reta.delete.body" -> "You are about to deactivate";
            case "reta.changes.title" -> "Base changes";
            case "reta.changes.new" -> "New change";
            case "reta.changes.hint" -> "RETA allows up to 6 base changes per year.";
            case "reta.changes.placeholder.empty" -> "No base changes registered for this year.";
            case "reta.changes.col.date" -> "Effective date";
            case "reta.changes.col.base" -> "New base";
            case "reta.changes.col.quota" -> "New quota";
            case "reta.changes.col.reason" -> "Reason";
            case "reta.changes.col.sent" -> "Sent SS";
            case "reta.changes.load.fail" -> "Could not load changes";
            case "reta.changes.load.fail.body" -> "Try again later.";
            case "reta.change.editor.title" -> "New base change";
            case "reta.change.editor.save" -> "Save";
            case "reta.change.editor.effective" -> "Effective date";
            case "reta.change.editor.reason" -> "Reason";
            case "reta.change.editor.reason.prompt" -> "e.g. Income forecast adjustment";
            case "reta.change.editor.new_base" -> "New base";
            case "reta.change.editor.new_quota" -> "New quota";
            case "reta.change.editor.net_income" -> "Expected net income";
            case "reta.change.editor.submitted" -> "Submitted to SS";
            case "reta.change.editor.notes" -> "Notes";
            case "reta.change.editor.fail.title" -> "Could not save";
            case "reta.change.editor.fail.body" -> "Maybe annual change limit reached (6).";
            case "reta.tramo.title" -> "RETA bracket calculator";
            case "reta.tramo.hint" -> "Enter your expected annual net income (revenue minus deductible expenses). We suggest the bracket and minimum quota.";
            case "reta.tramo.net.label" -> "Annual net income";
            case "reta.tramo.net.prompt" -> "e.g. 24000";
            case "reta.tramo.calc" -> "Calculate";
            case "reta.tramo.invalid" -> "Please enter a valid amount.";
            case "reta.tramo.fail" -> "Could not calculate.";
            case "reta.tramo.result.base_range" -> "Base range";
            case "reta.tramo.result.quota" -> "Minimum quota";
            case "reta.tramo.result.monthly_income" -> "Monthly income";
            // ---- DEHu (N1) ----
            case "dehu.title" -> "DEHú inbox";
            case "dehu.subtitle" -> "Electronic notifications from AEAT, Social Security, councils and other bodies.";
            case "dehu.load_failed" -> "Could not load notifications.";
            case "dehu.action.new" -> "Add notification";
            case "dehu.summary.pending" -> "Pending";
            case "dehu.summary.soon" -> "Expiring in 3 days";
            case "dehu.summary.expired" -> "Expired";
            case "dehu.placeholder.empty" -> "No notifications yet.";
            case "dehu.col.issued" -> "Issued";
            case "dehu.col.expires" -> "Expires";
            case "dehu.col.organism" -> "Organism";
            case "dehu.col.subject" -> "Subject";
            case "dehu.col.status" -> "Status";
            case "dehu.status.PENDING" -> "Pending";
            case "dehu.status.READ" -> "Read";
            case "dehu.status.AUTO_READ" -> "Auto-read";
            case "dehu.status.DISMISSED" -> "Dismissed";
            case "dehu.status.EXPIRED" -> "Expired";
            case "dehu.action.read" -> "Mark as read";
            case "dehu.action.dismiss" -> "Dismiss";
            case "dehu.editor.title" -> "Add DEHú notification";
            case "dehu.editor.save" -> "Save";
            case "dehu.editor.dehu_id" -> "DEHú ID";
            case "dehu.editor.nif" -> "Recipient tax ID";
            case "dehu.editor.organism" -> "Organism name";
            case "dehu.editor.organism_code" -> "Organism code";
            case "dehu.editor.procedure" -> "Procedure";
            case "dehu.editor.procedure_code" -> "Procedure code";
            case "dehu.editor.subject" -> "Subject";
            case "dehu.editor.issued" -> "Issued at (ISO)";
            case "dehu.editor.expires" -> "Expires at (ISO)";
            case "dehu.editor.csv" -> "CSV";
            case "dehu.editor.url" -> "DEHú URL";
            case "dehu.editor.notes" -> "Notes";
            case "dehu.editor.fail.title" -> "Could not save";
            case "dehu.editor.fail.body" -> "Check the data and try again.";
            default -> null;
        };
    }

    private String tNewModulesEs(String key) {
        return switch (key) {
            // ---- Laboral (L1) ----
            case "labor.title" -> "Personal";
            case "labor.subtitle" -> "Plantilla, contratos, fichajes y nominas — todo en una sola pantalla.";
            case "labor.tab.employees" -> "Empleados";
            case "labor.tab.contracts" -> "Contratos";
            case "labor.tab.timeclock" -> "Fichajes";
            case "labor.tab.payslips" -> "Nominas";
            case "labor.tab.cfg_timeclock" -> "Config fichajes";
            case "labor.tab.audit" -> "Auditoria";
            case "labor.audit.hint" -> "Auditoria de fichajes para el RD 8/2019 art. 34.9 (inalterabilidad) y 35.8 (verificacion publica). Filtra por rango, empleado o tipo. Conservacion 4 anos.";
            case "labor.audit.filter.from" -> "Desde";
            case "labor.audit.filter.to" -> "Hasta";
            case "labor.audit.filter.employee" -> "Empleado";
            case "labor.audit.filter.type" -> "Tipo";
            case "labor.audit.action.reload" -> "Recargar";
            case "labor.audit.action.export" -> "Exportar…";
            case "labor.audit.export.tooltip" -> "Exportar PDF/CSV con CSV de verificacion publica (TC-EXPORT, siguiente slice).";
            case "labor.audit.section.summary" -> "Resumen por empleado";
            case "labor.audit.section.detail" -> "Detalle de eventos";
            case "labor.audit.summary.placeholder.empty" -> "No hay eventos en el rango seleccionado.";
            case "labor.audit.detail.placeholder.empty" -> "Ningun evento coincide con los filtros.";
            case "labor.audit.col.employee" -> "Empleado";
            case "labor.audit.col.total" -> "Total";
            case "labor.audit.col.ins" -> "IN";
            case "labor.audit.col.outs" -> "OUT";
            case "labor.audit.col.pauses" -> "Pausas";
            case "labor.audit.col.corrections" -> "Correcciones";
            case "labor.audit.col.incidence" -> "Incidencia";
            case "labor.audit.incidence.yes" -> "Revisar";
            case "labor.audit.incidence.no" -> "OK";
            case "labor.audit.col.when" -> "Fecha/hora";
            case "labor.audit.col.type" -> "Tipo";
            case "labor.audit.col.origin" -> "Origen";
            case "labor.audit.col.status" -> "Estado";
            case "labor.audit.col.has_corrections" -> "Corr.";
            case "labor.audit.col.csv" -> "CSV";
            case "labor.cfg_timeclock.hint" -> "Configura los botones de fichaje que veran tus empleados. Los codigos se envian al servidor (p.ej. IN, COMIDA, REUNION). Las etiquetas son lo que lee el usuario. El orden determina la posicion en la fila.";
            case "labor.cfg_timeclock.placeholder.empty" -> "No hay tipos de evento configurados.";
            case "labor.cfg_timeclock.col.order" -> "Orden";
            case "labor.cfg_timeclock.col.code" -> "Codigo";
            case "labor.cfg_timeclock.col.label_es" -> "Etiqueta ES";
            case "labor.cfg_timeclock.col.label_en" -> "Etiqueta EN";
            case "labor.cfg_timeclock.col.flags" -> "Flags";
            case "labor.cfg_timeclock.inactive" -> "(inactivo)";
            case "labor.cfg_timeclock.legend" -> "▶ = abre tiempo de trabajo (cuenta como trabajando) · ⏸ = pausa (no cuenta) · (inactivo) = boton oculto en apps y kioscos";
            case "labor.cfg_timeclock.action.add" -> "Anadir tipo";
            case "labor.cfg_timeclock.action.edit" -> "Editar";
            case "labor.cfg_timeclock.action.delete" -> "Borrar";
            case "labor.cfg_timeclock.editor.title_new" -> "Nuevo tipo de evento";
            case "labor.cfg_timeclock.editor.title_edit" -> "Editar tipo de evento";
            case "labor.cfg_timeclock.editor.save" -> "Guardar";
            case "labor.cfg_timeclock.editor.code" -> "Codigo (MAYUSCULAS)";
            case "labor.cfg_timeclock.editor.label_es" -> "Etiqueta (espanol)";
            case "labor.cfg_timeclock.editor.label_en" -> "Etiqueta (ingles)";
            case "labor.cfg_timeclock.editor.icon" -> "Icono (FontAwesome)";
            case "labor.cfg_timeclock.editor.order" -> "Orden de visualizacion";
            case "labor.cfg_timeclock.editor.is_work_time" -> "Abre tiempo de trabajo (▶)";
            case "labor.cfg_timeclock.editor.is_pause" -> "Es una pausa (⏸)";
            case "labor.cfg_timeclock.editor.active" -> "Activo";
            case "labor.cfg_timeclock.editor.flags_hint" -> "Si dudas: IN/fin-de-pausa abren tiempo de trabajo; OUT/inicio-de-pausa no. Una pausa significa que el trabajador sigue en jornada pero deja de contar horas (comida, cafe).";
            case "labor.cfg_timeclock.editor.fail.title" -> "No se pudo guardar";
            case "labor.cfg_timeclock.editor.fail.body" -> "Revisa los datos e intentalo de nuevo.";
            case "labor.cfg_timeclock.delete.title" -> "Borrar tipo de evento?";
            case "labor.cfg_timeclock.delete.body" -> "Si hay fichajes historicos con este codigo, se desactivara en lugar de borrarse. Codigo:";
            case "labor.contracts.placeholder.empty.global" -> "Aun no hay contratos registrados.";
            case "labor.contracts.col.employee" -> "Empleado";
            case "labor.contracts.global.hint" -> "Todos los contratos de la empresa. Ordena por cualquier columna. Para editar un contrato o anadir uno nuevo, ve al detalle del empleado.";
            case "timeclock.employee.label" -> "Fichando como";
            case "timeclock.error.no_employee.title" -> "Sin empleado seleccionado";
            case "timeclock.error.no_employee.body" -> "Elige un empleado de la lista antes de fichar.";
            case "labor.payslips.placeholder.empty" -> "No hay nominas para este ano.";
            case "labor.payslips.hint" -> "Calcula, guarda y envia nominas. SS empleado = 6,35% del bruto; % IRPF del contrato o estimado por tramos anuales.";
            case "labor.payslips.col.period" -> "Periodo";
            case "labor.payslips.col.employee" -> "Empleado";
            case "labor.payslips.col.type" -> "Tipo";
            case "labor.payslips.col.gross" -> "Bruto";
            case "labor.payslips.col.ss" -> "SS 6,35%";
            case "labor.payslips.col.irpf" -> "IRPF";
            case "labor.payslips.col.net" -> "Liquido";
            case "labor.payslips.col.status" -> "Estado";
            case "labor.payslips.type.MONTHLY" -> "Mensual";
            case "labor.payslips.type.EXTRA_SUMMER" -> "Paga verano";
            case "labor.payslips.type.EXTRA_CHRISTMAS" -> "Paga navidad";
            case "labor.payslips.type.BONUS" -> "Bonus";
            case "labor.payslips.type.SETTLEMENT" -> "Liquidacion";
            case "labor.payslips.status.DRAFT" -> "Borrador";
            case "labor.payslips.status.CALCULATED" -> "Calculada";
            case "labor.payslips.status.PAID" -> "Pagada";
            case "labor.payslips.status.CANCELLED" -> "Cancelada";
            case "labor.payslips.action.calculate" -> "Calcular nomina";
            case "labor.payslips.action.pay" -> "Marcar pagada";
            case "labor.payslips.action.pdf" -> "Descargar PDF";
            case "labor.payslips.action.email" -> "Enviar por email";
            case "labor.payslips.action.delete" -> "Borrar";
            case "labor.payslips.calc.title" -> "Calcular nomina";
            case "labor.payslips.calc.save" -> "Calcular";
            case "labor.payslips.calc.employee" -> "Empleado";
            case "labor.payslips.calc.year" -> "Ano";
            case "labor.payslips.calc.month" -> "Mes";
            case "labor.payslips.calc.type" -> "Tipo";
            case "labor.payslips.calc.extra_prorated" -> "Prorratear pagas extras";
            case "labor.payslips.calc.other_deductions" -> "Otras deducciones (€)";
            case "labor.payslips.calc.other_deductions.prompt" -> "Opcional";
            case "labor.payslips.calc.notes" -> "Notas";
            case "labor.payslips.calc.fail.title" -> "No se pudo calcular";
            case "labor.payslips.calc.fail.body" -> "Revisa los datos del contrato e intentalo de nuevo.";
            case "labor.payslips.calc.fail.no_employee" -> "Selecciona un empleado.";
            case "labor.payslips.pay.title" -> "Marcar como pagada?";
            case "labor.payslips.pay.body" -> "Vas a marcar como pagada la nomina de";
            case "labor.payslips.pdf.save_as" -> "Guardar nomina como…";
            case "labor.payslips.pdf.fail.title" -> "No se pudo generar PDF";
            case "labor.payslips.pdf.fail.body" -> "Intentalo de nuevo mas tarde.";
            case "labor.payslips.email.title" -> "Enviar nomina por email?";
            case "labor.payslips.email.body" -> "La nomina se enviara al email del empleado";
            case "labor.payslips.email.ok.title" -> "Email enviado";
            case "labor.payslips.email.ok.body" -> "El empleado ha recibido la nomina.";
            case "labor.payslips.email.fail.title" -> "No se pudo enviar";
            case "labor.payslips.email.fail.body" -> "Comprueba la configuracion SMTP y que el empleado tenga email.";
            case "labor.payslips.delete.title" -> "Borrar nomina?";
            case "labor.payslips.delete.body" -> "Vas a borrar la nomina de";
            case "labor.title.old" -> "Empleados";
            case "labor.load_failed" -> "No se pudo cargar la plantilla.";
            case "labor.action.new_employee" -> "Nuevo empleado";
            case "labor.employees.placeholder.empty" -> "Aun no hay empleados.";
            case "labor.employees.col.name" -> "Nombre completo";
            case "labor.employees.col.nif" -> "NIF";
            case "labor.employees.col.nuss" -> "NUSS";
            case "labor.employees.col.hire_date" -> "Alta";
            case "labor.employees.col.ss" -> "Reg. SS";
            case "labor.employees.col.flags" -> "";
            case "labor.employees.inactive" -> "(inactivo)";
            case "labor.employees.action.edit" -> "Editar";
            case "labor.employees.action.contracts" -> "Contratos…";
            case "labor.employees.action.delete" -> "Dar de baja";
            case "labor.employee.editor.title_new" -> "Nuevo empleado";
            case "labor.employee.editor.title_edit" -> "Editar empleado";
            case "labor.employee.editor.save" -> "Guardar";
            case "labor.employee.editor.active" -> "Activo";
            case "labor.employee.section.identity" -> "Identidad";
            case "labor.employee.section.contact" -> "Contacto y direccion";
            case "labor.employee.section.work" -> "Datos laborales";
            case "labor.employee.editor.name" -> "Nombre completo";
            case "labor.employee.editor.nif" -> "NIF";
            case "labor.employee.editor.nuss" -> "Numero SS";
            case "labor.employee.editor.birth" -> "Fecha nacimiento";
            case "labor.employee.editor.gender" -> "Sexo";
            case "labor.employee.editor.marital" -> "Estado civil";
            case "labor.employee.editor.children" -> "Hijos a cargo";
            case "labor.employee.editor.disabled" -> "Discapacitados a cargo";
            case "labor.employee.editor.email" -> "Email";
            case "labor.employee.editor.phone" -> "Telefono";
            case "labor.employee.editor.address" -> "Direccion";
            case "labor.employee.editor.city" -> "Localidad";
            case "labor.employee.editor.province" -> "Provincia";
            case "labor.employee.editor.postal" -> "Codigo postal";
            case "labor.employee.editor.country" -> "Pais";
            case "labor.employee.editor.iban" -> "IBAN (nomina)";
            case "labor.employee.editor.work_type" -> "Tipo de trabajo";
            case "labor.employee.editor.ss_regime" -> "Regimen SS";
            case "labor.employee.editor.hire" -> "Fecha de alta";
            case "labor.employee.editor.termination" -> "Fecha de baja";
            case "labor.employee.editor.term_reason" -> "Motivo de baja";
            case "labor.employee.editor.geolocation" -> "Permitir geolocalizacion al fichar";
            case "labor.employee.editor.geolocation.hint" -> "Si esta activo, el fichaje movil/web pedira la posicion GPS y la guardara junto al evento. RGPD: solo con consentimiento expreso del empleado. Desactivado por defecto.";
            case "labor.employees.col.geo" -> "Geo";
            case "labor.employee.editor.fail.title" -> "No se pudo guardar";
            case "labor.employee.editor.fail.body" -> "Revisa los datos e intentalo de nuevo.";
            case "labor.employee.delete.title" -> "Dar de baja al empleado?";
            case "labor.employee.delete.body" -> "Vas a dar de baja a";
            case "labor.contracts.dialog.title" -> "Contratos";
            case "labor.contracts.load.fail" -> "No se pudieron cargar los contratos";
            case "labor.contracts.load.fail.body" -> "Intentalo de nuevo mas tarde.";
            case "labor.contracts.placeholder.empty" -> "Este empleado no tiene contratos.";
            case "labor.contracts.col.type" -> "Tipo";
            case "labor.contracts.col.sepe" -> "SEPE";
            case "labor.contracts.col.start" -> "Inicio";
            case "labor.contracts.col.end" -> "Fin";
            case "labor.contracts.col.salary" -> "Salario anual";
            case "labor.contracts.col.status" -> "Estado";
            case "labor.contracts.action.new" -> "Nuevo contrato";
            case "labor.contracts.action.edit" -> "Editar";
            case "labor.contract.editor.title_new" -> "Nuevo contrato";
            case "labor.contract.editor.title_edit" -> "Editar contrato";
            case "labor.contract.editor.save" -> "Guardar";
            case "labor.contract.editor.type" -> "Tipo";
            case "labor.contract.editor.sepe" -> "Codigo SEPE";
            case "labor.contract.editor.agreement" -> "Convenio colectivo";
            case "labor.contract.editor.category" -> "Categoria";
            case "labor.contract.editor.group" -> "Grupo";
            case "labor.contract.editor.start" -> "Fecha inicio";
            case "labor.contract.editor.end" -> "Fecha fin";
            case "labor.contract.editor.weekly_hours" -> "Horas semanales";
            case "labor.contract.editor.salary" -> "Salario bruto anual";
            case "labor.contract.editor.bonuses" -> "Pagas extras";
            case "labor.contract.editor.vacation" -> "Vacaciones";
            case "labor.contract.editor.irpf" -> "IRPF %";
            case "labor.contract.editor.workplace" -> "Centro de trabajo";
            case "labor.contract.editor.status" -> "Estado";
            case "labor.contract.editor.fail.title" -> "No se pudo guardar";
            case "labor.contract.editor.fail.body" -> "Revisa los datos e intentalo de nuevo.";
            // ---- RETA (L2) ----
            case "reta.title" -> "Autonomos (RETA)";
            case "reta.subtitle" -> "Perfiles, bases de cotizacion y cambios.";
            case "reta.load_failed" -> "No se pudo cargar los perfiles RETA.";
            case "reta.action.new" -> "Nuevo perfil";
            case "reta.action.suggest_tramo" -> "Calculadora de tramos";
            case "reta.placeholder.empty" -> "Aun no hay perfiles RETA.";
            case "reta.col.name" -> "Nombre";
            case "reta.col.nif" -> "NIF";
            case "reta.col.base" -> "Base actual";
            case "reta.col.quota" -> "Cuota actual";
            case "reta.col.flags" -> "Flags";
            case "reta.inactive" -> "(inactivo)";
            case "reta.action.edit" -> "Editar";
            case "reta.action.changes" -> "Cambios de base…";
            case "reta.action.delete" -> "Dar de baja";
            case "reta.editor.title_new" -> "Nuevo perfil RETA";
            case "reta.editor.title_edit" -> "Editar perfil RETA";
            case "reta.editor.save" -> "Guardar";
            case "reta.editor.name" -> "Nombre completo";
            case "reta.editor.nif" -> "NIF";
            case "reta.editor.nuss" -> "Numero SS";
            case "reta.editor.start" -> "Alta RETA";
            case "reta.editor.end" -> "Baja RETA";
            case "reta.editor.pluriactividad" -> "Pluriactividad";
            case "reta.editor.tarifa_plana" -> "Tarifa plana";
            case "reta.editor.tarifa_until" -> "Tarifa plana hasta";
            case "reta.editor.activity_code" -> "Codigo actividad";
            case "reta.editor.activity_desc" -> "Descripcion actividad";
            case "reta.editor.iae" -> "Epigrafe IAE";
            case "reta.editor.net_income" -> "Rendimiento neto anual previsto";
            case "reta.editor.base" -> "Base actual";
            case "reta.editor.quota" -> "Cuota actual";
            case "reta.editor.notes" -> "Notas";
            case "reta.editor.active" -> "Activo";
            case "reta.editor.fail.title" -> "No se pudo guardar";
            case "reta.editor.fail.body" -> "Revisa los datos e intentalo de nuevo.";
            case "reta.delete.title" -> "Dar de baja perfil RETA?";
            case "reta.delete.body" -> "Vas a dar de baja a";
            case "reta.changes.title" -> "Cambios de base";
            case "reta.changes.new" -> "Nuevo cambio";
            case "reta.changes.hint" -> "RETA permite hasta 6 cambios de base por año.";
            case "reta.changes.placeholder.empty" -> "No hay cambios de base este año.";
            case "reta.changes.col.date" -> "Fecha efecto";
            case "reta.changes.col.base" -> "Nueva base";
            case "reta.changes.col.quota" -> "Nueva cuota";
            case "reta.changes.col.reason" -> "Motivo";
            case "reta.changes.col.sent" -> "Enviado SS";
            case "reta.changes.load.fail" -> "No se pudo cargar el historial";
            case "reta.changes.load.fail.body" -> "Intentalo de nuevo mas tarde.";
            case "reta.change.editor.title" -> "Nuevo cambio de base";
            case "reta.change.editor.save" -> "Guardar";
            case "reta.change.editor.effective" -> "Fecha efecto";
            case "reta.change.editor.reason" -> "Motivo";
            case "reta.change.editor.reason.prompt" -> "p. ej. Ajuste previsión rendimientos";
            case "reta.change.editor.new_base" -> "Nueva base";
            case "reta.change.editor.new_quota" -> "Nueva cuota";
            case "reta.change.editor.net_income" -> "Rendimiento neto previsto";
            case "reta.change.editor.submitted" -> "Enviado a la SS";
            case "reta.change.editor.notes" -> "Notas";
            case "reta.change.editor.fail.title" -> "No se pudo guardar";
            case "reta.change.editor.fail.body" -> "Quizás se alcanzó el límite de 6 cambios anuales.";
            case "reta.tramo.title" -> "Calculadora de tramos RETA";
            case "reta.tramo.hint" -> "Introduce el rendimiento neto anual previsto (ingresos menos gastos deducibles). Te sugerimos el tramo y la cuota minima.";
            case "reta.tramo.net.label" -> "Rendimiento neto anual";
            case "reta.tramo.net.prompt" -> "p. ej. 24000";
            case "reta.tramo.calc" -> "Calcular";
            case "reta.tramo.invalid" -> "Introduce una cantidad valida.";
            case "reta.tramo.fail" -> "No se pudo calcular.";
            case "reta.tramo.result.base_range" -> "Intervalo de base";
            case "reta.tramo.result.quota" -> "Cuota minima";
            case "reta.tramo.result.monthly_income" -> "Rendimiento mensual";
            // ---- DEHu (N1) ----
            case "dehu.title" -> "Bandeja DEHu";
            case "dehu.subtitle" -> "Notificaciones electronicas de AEAT, Seguridad Social, ayuntamientos y otros organismos.";
            case "dehu.load_failed" -> "No se pudieron cargar las notificaciones.";
            case "dehu.action.new" -> "Anadir notificacion";
            case "dehu.summary.pending" -> "Pendientes";
            case "dehu.summary.soon" -> "Caducan en 3 dias";
            case "dehu.summary.expired" -> "Caducadas";
            case "dehu.placeholder.empty" -> "No hay notificaciones.";
            case "dehu.col.issued" -> "Emitida";
            case "dehu.col.expires" -> "Caduca";
            case "dehu.col.organism" -> "Organismo";
            case "dehu.col.subject" -> "Asunto";
            case "dehu.col.status" -> "Estado";
            case "dehu.status.PENDING" -> "Pendiente";
            case "dehu.status.READ" -> "Leida";
            case "dehu.status.AUTO_READ" -> "Auto-leida";
            case "dehu.status.DISMISSED" -> "Descartada";
            case "dehu.status.EXPIRED" -> "Caducada";
            case "dehu.action.read" -> "Marcar como leida";
            case "dehu.action.dismiss" -> "Descartar";
            case "dehu.editor.title" -> "Anadir notificacion DEHu";
            case "dehu.editor.save" -> "Guardar";
            case "dehu.editor.dehu_id" -> "ID DEHu";
            case "dehu.editor.nif" -> "NIF del destinatario";
            case "dehu.editor.organism" -> "Nombre del organismo";
            case "dehu.editor.organism_code" -> "Codigo organismo";
            case "dehu.editor.procedure" -> "Procedimiento";
            case "dehu.editor.procedure_code" -> "Codigo procedimiento";
            case "dehu.editor.subject" -> "Asunto";
            case "dehu.editor.issued" -> "Fecha emision (ISO)";
            case "dehu.editor.expires" -> "Fecha caducidad (ISO)";
            case "dehu.editor.csv" -> "CSV";
            case "dehu.editor.url" -> "URL DEHu";
            case "dehu.editor.notes" -> "Notas";
            case "dehu.editor.fail.title" -> "No se pudo guardar";
            case "dehu.editor.fail.body" -> "Revisa los datos e intentalo de nuevo.";
            default -> null;
        };
    }

    /**
     * Bloque i18n EN del slice ADVISORY-INVITATION. Extraído del switch
     * principal para no rebasar el límite JVM de 64KB por método.
     */
    private String tAdvisoryInvitationsEn(String key) {
        return switch (key) {
            case "settings.tab.my_advisory" -> "My advisory";
            case "settings.my_advisory.section" -> "My advisory firm";
            case "settings.my_advisory.hint" -> "If a tax advisor invited you to manage your company, accept their invitation from the Home banner. Once linked, your advisor can switch into your company from their 'My clients' screen and operate on your behalf (upload your certificate, register expenses, etc.). You can break the link at any time.";
            case "settings.my_advisory.empty" -> "Your company is not linked to any advisory firm. When an advisor sends you an invitation, you will see a banner on the Home screen to accept it.";
            case "settings.my_advisory.field.legal_name" -> "Legal name:";
            case "settings.my_advisory.field.trade_name" -> "Trade name:";
            case "settings.my_advisory.field.nif" -> "Tax ID:";
            case "settings.my_advisory.field.email" -> "Email:";
            case "settings.my_advisory.action.unlink" -> "Unlink";
            case "settings.my_advisory.confirm.unlink.title" -> "Unlink from advisory?";
            case "settings.my_advisory.confirm.unlink.body" -> "Your advisor will lose access to your company. You can re-link later by accepting a new invitation.";
            case "settings.my_advisory.fail.load.title" -> "Could not load advisory";
            case "settings.my_advisory.fail.load.body" -> "Check the backend connection.";
            case "settings.my_advisory.fail.unlink.title" -> "Could not unlink";
            case "settings.my_advisory.fail.unlink.body" -> "Try again or check the connection.";
            case "advisory.action.invite" -> "Invite client";
            case "advisory.invitations.section" -> "Invitations";
            case "advisory.invitations.hint" -> "Invitations you have issued (active and history). Copy the token of a PENDING one and send it to the client through your usual channel — the client must paste it from their 'My advisory' tab or accept the banner that appears on their Home.";
            case "advisory.invitations.placeholder.empty" -> "No invitations issued yet.";
            case "advisory.invitations.col.date" -> "Date";
            case "advisory.invitations.col.email" -> "Email";
            case "advisory.invitations.col.nif" -> "Tax ID";
            case "advisory.invitations.col.company" -> "Company";
            case "advisory.invitations.col.status" -> "Status";
            case "advisory.invitations.status.PENDING" -> "Pending";
            case "advisory.invitations.status.ACCEPTED" -> "Accepted";
            case "advisory.invitations.status.REJECTED" -> "Rejected";
            case "advisory.invitations.status.REVOKED" -> "Revoked";
            case "advisory.invitations.status.EXPIRED" -> "Expired";
            case "advisory.invitations.status.UNLINKED" -> "Unlinked";
            case "advisory.invitations.action.refresh" -> "Refresh";
            case "advisory.invitations.action.copy_link" -> "Copy token";
            case "advisory.invitations.action.revoke" -> "Revoke";
            case "advisory.invitations.create.title" -> "Invite client";
            case "advisory.invitations.create.save" -> "Send invitation";
            case "advisory.invitations.create.hint" -> "Provide email and/or tax ID of the company you want to invite. The system generates a token that the client must accept from their session.";
            case "advisory.invitations.create.email" -> "Client email:";
            case "advisory.invitations.create.nif" -> "Client tax ID:";
            case "advisory.invitations.create.company_name" -> "Company name:";
            case "advisory.invitations.create.notes" -> "Notes:";
            case "advisory.invitations.token_label" -> "Token:";
            case "advisory.invitations.create.ok.title" -> "Invitation sent";
            case "advisory.invitations.create.ok.body" -> "The token has been copied to the clipboard. Share it with the client.";
            case "advisory.invitations.create.fail.title" -> "Could not send";
            case "advisory.invitations.create.fail.body" -> "Check the backend connection.";
            case "advisory.invitations.create.fail.missing.title" -> "Missing data";
            case "advisory.invitations.create.fail.missing.body" -> "At least email or tax ID is required.";
            case "advisory.invitations.copied.title" -> "Token copied";
            case "advisory.invitations.copied.body" -> "Paste the token in the client's session to link them.";
            case "advisory.invitations.revoke.confirm.title" -> "Revoke invitation?";
            case "advisory.invitations.revoke.confirm.body" -> "The invitation will no longer be valid.";
            case "advisory.invitations.revoke.fail.title" -> "Could not revoke";
            case "advisory.invitations.revoke.fail.body" -> "Try again or check the connection.";
            case "advisory.invitations.fail.list.title" -> "Could not load invitations";
            case "advisory.invitations.fail.list.body" -> "Check the backend connection.";
            case "advisory.invitation.banner.title" -> "You have a pending advisory invitation";
            case "advisory.invitation.banner.from" -> "Sent by:";
            case "advisory.invitation.banner.from_generic" -> "Sent by an advisory firm.";
            case "advisory.invitation.banner.body" -> "If you accept, the advisor will be able to switch into your company and manage it on your behalf. You can unlink at any time from Settings → My advisory.";
            case "advisory.invitation.banner.accept" -> "Accept";
            case "advisory.invitation.banner.reject" -> "Reject";
            case "advisory.invitation.accept.ok.title" -> "Linked";
            case "advisory.invitation.accept.ok.body" -> "You are now linked to your advisory firm.";
            case "advisory.invitation.accept.fail.title" -> "Could not accept";
            case "advisory.invitation.accept.fail.body" -> "If you already had an advisor linked, unlink first from Settings → My advisory.";
            case "advisory.invitation.reject.ok.title" -> "Rejected";
            case "advisory.invitation.reject.ok.body" -> "The invitation has been rejected.";
            case "advisory.invitation.reject.fail.title" -> "Could not reject";
            case "advisory.invitation.reject.fail.body" -> "Try again or check the connection.";
            case "advisory.invitation.toast.title" -> "New invitation";
            case "advisory.invitation.toast.body" -> "You have received a new advisory invitation. Go to the Home screen to accept or reject it.";
            case "advisory.toast.new_client.title" -> "New client linked";
            case "advisory.toast.new_client.body" -> "A client has accepted your invitation. Open 'My clients' to start working with them.";
            case "advisory.action.open_client" -> "Open client";
            case "advisory.action.invite_selected" -> "📩 Invite selected client";
            case "advisory.action.invite_selected.tip" -> "Send a vinculation invitation to this customer so you can manage their company data here.";
            case "advisory.action.reinvite" -> "🔁 Re-invite";
            case "advisory.action.reinvite.tip" -> "This customer was previously linked and then unlinked. Send a new invitation to vinculate them again.";
            case "advisory.action.resend_invitation" -> "✉ Resend invitation";
            case "advisory.action.resend_invitation.tip" -> "The customer is already linked. Use this only if they lost access (lost data, replaced device) and need a fresh token. Accepting an idempotent invitation does not change the existing link.";
            case "advisory.portfolio.subtitle" -> "Customer portfolio — billing + linked accounts in one place";
            case "advisory.portfolio.hint" -> "All your customers in one place: those you only invoice and those whose company you also manage. Use 'Invite selected client' to send a vinculation token to a customer already in your portfolio.";
            case "advisory.portfolio.not_linked.title" -> "Customer not linked yet";
            case "advisory.portfolio.not_linked.body" -> "This customer is in your billing portfolio but has not accepted a vinculation invitation. Send them one to manage their company data here.";
            case "advisory.col.link_status" -> "Vinculation";
            case "advisory.link.linked" -> "Linked";
            case "advisory.link.pending" -> "Invitation pending";
            case "advisory.link.not_linked" -> "Not linked";
            case "advisory.link.unlinked" -> "Unlinked by client";
            case "advisory.toast.unlinked.title" -> "Client unlinked";
            case "advisory.toast.unlinked.body" -> "A client has just unlinked from your advisory firm. The portfolio has been updated.";
            case "advisory.toast.active_client_unlinked.title" -> "Client unlinked while you were working on them";
            case "advisory.toast.active_client_unlinked.body" -> "The client whose data you were viewing has just unlinked from your advisory firm. You have been returned to your own dashboard.";
            case "settings.my_advisory.paste_token.title" -> "Have an invitation token?";
            case "settings.my_advisory.paste_token.hint" -> "If your advisor sent you a vinculation token (a 32-character string), paste it here and press Accept to link manually.";
            case "settings.my_advisory.paste_token.prompt" -> "Paste your invitation token here";
            case "settings.my_advisory.paste_token.accept" -> "Accept invitation";
            case "settings.my_advisory.paste_token.fail.empty.title" -> "Empty token";
            case "settings.my_advisory.paste_token.fail.empty.body" -> "Paste a token before accepting.";
            case "advisory.client.back" -> "← Back to My clients";
            case "advisory.client.hint" -> "You are now viewing this client. Anything you do from here is recorded under their company, not yours. Your sidebar still belongs to your advisory firm — you can switch between tabs freely.";
            case "advisory.client.tab.summary" -> "Summary";
            case "advisory.client.tab.billing" -> "Billing";
            case "advisory.client.tab.purchases" -> "Purchases & Expenses";
            case "advisory.client.tab.accounting" -> "Accounting";
            case "advisory.client.tab.banks" -> "Banks";
            case "advisory.client.tab.loans" -> "Loans";
            case "advisory.client.tab.assets" -> "Fixed assets";
            case "advisory.client.tab.labor" -> "Employees";
            case "advisory.client.tab.tax_models" -> "AEAT models";
            case "advisory.client.tab.certificate" -> "Certificate";
            // ============ Accounting module (AccountingScreen) ============
            case "accounting.tab.pending" -> "To validate";
            case "accounting.tab.diary" -> "Journal";
            case "accounting.tab.manual" -> "Manual entries";
            case "accounting.tab.rules" -> "Learned rules";
            case "accounting.tab.recurring" -> "Recurring";
            case "accounting.action.refresh" -> "Refresh";
            case "accounting.action.validate" -> "Validate";
            case "accounting.action.accept" -> "Accept as-is";
            case "accounting.action.new_entry" -> "New entry";
            case "accounting.action.save_draft" -> "Save draft";
            case "accounting.action.close" -> "Close";
            case "accounting.action.toggle" -> "Enable/Disable";
            case "accounting.action.delete" -> "Delete";
            case "accounting.action.run_now" -> "Run now";
            case "accounting.filter.from" -> "From";
            case "accounting.filter.to" -> "To";
            case "accounting.filter.status" -> "Status";
            case "accounting.filter.source" -> "Source";
            case "accounting.filter.search" -> "Search";
            case "accounting.filter.search_prompt" -> "concept, number, source…";
            case "accounting.filter.any" -> "(any)";
            case "accounting.col.num" -> "#";
            case "accounting.col.date" -> "Date";
            case "accounting.col.concept" -> "Concept";
            case "accounting.col.source" -> "Source";
            case "accounting.col.status" -> "Status";
            case "accounting.col.debit_total" -> "Debit";
            case "accounting.col.credit_total" -> "Credit";
            case "accounting.col.confidence" -> "Confidence";
            case "accounting.col.account" -> "Account";
            case "accounting.col.description" -> "Description";
            case "accounting.col.debit" -> "Debit";
            case "accounting.col.credit" -> "Credit";
            case "accounting.field.date" -> "Date:";
            case "accounting.field.concept" -> "Concept:";
            case "accounting.badge.auto_proposed" -> "AUTO-PROPOSED";
            case "accounting.dialog.new_entry" -> "New entry";
            case "accounting.dialog.review_entry" -> "Review entry";
            case "accounting.pending.hint" -> "Entries auto-generated when saving expenses or validating sales. Review and validate — the system learns from your corrections.";
            case "accounting.manual.hint" -> "Create manual accounting entries (adjustments, accruals, estimated tax, etc.). Debit=Credit balance is auto-validated.";
            case "accounting.rules.hint" -> "Rules the system learned from your corrections. The higher the confidence, the more automatic the proposal.";
            case "accounting.recurring.hint" -> "Scheduled tasks: recurring expenses (utilities, rent), loan installments, entry templates. Run daily at 06:10.";
            case "accounting.error.load" -> "Could not load data";
            case "accounting.error.save" -> "Could not save entry";
            case "accounting.error.accept" -> "Could not accept entry";
            case "accounting.error.toggle" -> "Could not change state";
            case "accounting.error.delete" -> "Could not delete";
            case "accounting.error.run_now" -> "Could not run";
            case "accounting.confirm.delete_rule" -> "Delete this learned rule? Past corrections remain in history.";
            case "accounting.action.backfill" -> "Regenerate entries";
            case "accounting.confirm.backfill" -> "This will scan ALL invoices (purchases & sales) without an accounting entry and generate any missing automatic entries. Safe: idempotent (no duplicates) and respects locked fiscal year guards. Continue?";
            case "accounting.backfill.done" -> "Regeneration completed";
            case "accounting.backfill.result" -> "Entries created:\n  · Purchases: {p}\n  · Sales: {s}\n  · Total: {t}\n\nCheck the 'To validate' tab to review the auto-proposed entries.";
            case "accounting.error.backfill" -> "Could not regenerate entries";
            case "accounting.action.reclassify" -> "Reclassify entries";
            case "accounting.reclassify.done" -> "Reclassification finished";
            case "accounting.reclassify.result" -> "Reclassified lines: {n}\nEntries reviewed: {t}\n\nOnly draft entries with a generic account (600/700) were touched. Manual edits made by the accountant are preserved.";
            case "accounting.error.reclassify" -> "Could not reclassify entries";
            case "accounting.tercero.title" -> "Third-party sub-account plan";
            case "accounting.tercero.length" -> "Code length (digits):";
            case "accounting.tercero.mode" -> "Numbering mode:";
            case "accounting.tercero.mode.sequential" -> "Sequential (1, 2, 3…)";
            case "accounting.tercero.mode.by_nif" -> "By NIF/CIF digits";
            case "accounting.tercero.preview" -> "Next code preview: {x}";
            case "accounting.tercero.warn" -> "Changes apply ONLY to third parties created from now on. Existing sub-accounts are NOT renumbered — that would break the accounting history.";
            case "accounting.tercero.error_save" -> "Could not save the third-party plan settings";
            case "accounting.action.validate_batch" -> "Validate selected";
            case "accounting.confirm.validate_batch" -> "Validate {n} selected entries (draft)? Already POSTED ones will be skipped, errors (closed period, etc.) will stay as they are.";
            case "accounting.validate_batch.result" -> "Validated: {p}\nSkipped: {s}\nErrors: {e}\n\nThe pending tab and the journal have been refreshed.";
            case "accounting.error.validate_batch" -> "Could not validate batch";
            case "accounting.error.session_expired_title" -> "Session expired";
            case "accounting.error.session_expired_body" -> "Your session has expired (tokens last 8 hours for security).\n\nClose the app and log in again to continue.\n\nNothing you saved has been lost.";
            // ============ Enum values translated (EN) ============
            case "accounting.status.DRAFT" -> "Draft";
            case "accounting.status.POSTED" -> "Posted";
            case "accounting.status.VOID" -> "Void";
            case "accounting.status.VOIDED" -> "Voided";
            case "accounting.status.VALIDATED" -> "Validated";
            case "accounting.status.PAID" -> "Paid";
            case "accounting.status.PARTIAL" -> "Partial";
            case "accounting.status.OVERDUE" -> "Overdue";
            case "accounting.status.PENDING" -> "Pending";
            case "accounting.status.CANCELLED" -> "Cancelled";
            case "accounting.status.PROFORMA" -> "Proforma";
            case "accounting.source_type.SALES_INVOICE" -> "Sales invoice";
            case "accounting.source_type.PURCHASE_INVOICE" -> "Purchase invoice";
            case "accounting.source_type.MANUAL" -> "Manual";
            case "accounting.source_type.BANK_MOVEMENT" -> "Bank movement";
            case "accounting.source_type.YEAR_CLOSE_REGULARIZATION" -> "Year-close regularization";
            case "accounting.source_type.YEAR_CLOSE_CLOSING" -> "Year-close entry";
            case "accounting.source_type.LOAN_INSTALLMENT" -> "Loan installment";
            case "accounting.source_type.ASSET_DEPRECIATION" -> "Depreciation";
            case "accounting.source_type.ASSET_ACQUISITION" -> "Asset acquisition";
            case "accounting.source_type.ASSET_DISPOSAL" -> "Asset disposal";
            case "accounting.source_type.MANUAL_REVERSAL" -> "Reversal";
            case "accounting.col.name" -> "Name";
            case "accounting.col.rule_kind" -> "Rule kind";
            case "accounting.col.nif" -> "Tax ID";
            case "accounting.col.keyword" -> "Keyword";
            case "accounting.col.target_account" -> "→ Account";
            case "accounting.col.applied" -> "Applied";
            case "accounting.col.overridden" -> "Overridden";
            case "accounting.col.active" -> "Active";
            case "accounting.col.rec_kind" -> "Kind";
            case "accounting.col.frequency" -> "Frequency";
            case "accounting.col.day" -> "Day";
            case "accounting.col.next_run" -> "Next run";
            case "accounting.col.last_run" -> "Last run";
            case "accounting.col.times_run" -> "Times run";
            case "accounting.col.times_failed" -> "Times failed";
            case "accounting.rule_kind.EXPENSE_ACCOUNT_BY_SUPPLIER_NIF" -> "Expense account by supplier Tax ID";
            case "accounting.rule_kind.EXPENSE_ACCOUNT_BY_KEYWORD" -> "Expense account by keyword";
            case "accounting.rule_kind.INCOME_ACCOUNT_BY_CUSTOMER_NIF" -> "Income account by customer Tax ID";
            case "accounting.rule_kind.INCOME_ACCOUNT_BY_KEYWORD" -> "Income account by keyword";
            case "accounting.rule_kind.VAT_RATE_BY_SUPPLIER_NIF" -> "VAT rate by supplier Tax ID";
            case "accounting.rec_kind.PURCHASE" -> "Recurring expense";
            case "accounting.rec_kind.SALES_INVOICE" -> "Recurring sales invoice";
            case "accounting.rec_kind.JOURNAL_ENTRY" -> "Recurring entry";
            case "accounting.rec_kind.TEMPLATE_APPLY" -> "Recurring template";
            case "accounting.rec_kind.LOAN_AUTO_PAY" -> "Loan payment";
            case "accounting.frequency.DAILY" -> "Daily";
            case "accounting.frequency.WEEKLY" -> "Weekly";
            case "accounting.frequency.MONTHLY" -> "Monthly";
            case "accounting.frequency.QUARTERLY" -> "Quarterly";
            case "accounting.frequency.YEARLY" -> "Yearly";
            case "accounting.frequency.CUSTOM_MONTHS" -> "Custom (months)";
            case "accounting.run_status.OK" -> "OK";
            case "accounting.run_status.ERROR" -> "Error";
            case "accounting.run_status.SKIPPED" -> "Skipped";
            // ============ Billing inline columns (EN) ============
            case "billing.col.number" -> "Number";
            case "billing.col.date" -> "Date";
            case "billing.col.customer" -> "Customer";
            case "billing.col.type" -> "Type";
            case "billing.col.total" -> "Total";
            case "billing.col.paid" -> "Paid";
            case "billing.col.status" -> "Status";
            case "billing.col.payment_status" -> "Payment";
            case "billing.payment_status.PENDING" -> "Pending";
            case "billing.payment_status.PAID" -> "Paid";
            case "billing.payment_status.PARTIAL" -> "Partial";
            case "billing.payment_status.OVERDUE" -> "Overdue";
            // ============ Labor inline columns (EN) ============
            case "labor.col.name" -> "Name";
            case "labor.col.nif" -> "Tax ID";
            case "labor.col.regime" -> "SS regime";
            case "labor.col.hire_date" -> "Hire date";
            case "labor.col.active" -> "Active";
            // ============ Tax filings inline columns (EN) ============
            case "tax.col.model" -> "Model";
            case "tax.col.year" -> "Year";
            case "tax.col.quarter" -> "Quarter";
            case "tax.col.month" -> "Month";
            case "tax.col.status" -> "Status";
            case "tax.col.amount" -> "Amount";
            case "tax.col.deadline" -> "Deadline";
            case "tax.filing_status.DRAFT" -> "Draft";
            case "tax.filing_status.READY" -> "Ready";
            case "tax.filing_status.PRESENTED" -> "Presented";
            case "tax.filing_status.PAID" -> "Paid";
            case "tax.filing_status.REJECTED" -> "Rejected";
            case "tax.filing_status.CANCELLED" -> "Cancelled";
            // ============ Bank/Loans/Assets columns (EN) ============
            case "bank.col.alias" -> "Alias";
            case "bank.col.iban" -> "IBAN";
            case "bank.col.bank" -> "Bank";
            case "bank.col.opening" -> "Opening";
            case "bank.col.active" -> "Active";
            case "bank.col.date" -> "Date";
            case "bank.col.description" -> "Description";
            case "bank.col.counterparty" -> "Counterparty";
            case "bank.col.nif" -> "Tax ID";
            case "bank.col.amount" -> "Amount";
            case "bank.col.balance" -> "Balance";
            case "bank.col.status" -> "Status";
            case "bank.col.invoice" -> "Invoice";
            case "bank.movement_status.UNRECONCILED" -> "Unreconciled";
            case "bank.movement_status.MATCHED" -> "Matched";
            case "bank.movement_status.POSTED" -> "Posted";
            case "bank.movement_status.IGNORED" -> "Ignored";
            case "loans.col.code" -> "Code";
            case "loans.col.description" -> "Description";
            case "loans.col.lender" -> "Lender";
            case "loans.col.principal" -> "Principal";
            case "loans.col.interest" -> "Interest %";
            case "loans.col.term" -> "Term (m)";
            case "loans.col.installment" -> "Installment";
            case "loans.col.method" -> "Method";
            case "loans.col.status" -> "Status";
            case "loans.col.due_date" -> "Due date";
            case "loans.col.interest_amount" -> "Interest";
            case "loans.col.remaining" -> "Remaining";
            case "loans.method.FRENCH" -> "French";
            case "loans.method.CONSTANT_PRINCIPAL" -> "Constant principal";
            case "loans.method.BULLET" -> "Bullet";
            case "loans.status.ACTIVE" -> "Active";
            case "loans.status.PAID_OFF" -> "Paid off";
            case "loans.status.CANCELLED" -> "Cancelled";
            case "loans.installment_status.PENDING" -> "Pending";
            case "loans.installment_status.PAID" -> "Paid";
            case "loans.installment_status.OVERDUE" -> "Overdue";
            case "loans.installment_status.CANCELLED" -> "Cancelled";
            case "assets.col.code" -> "Code";
            case "assets.col.name" -> "Name";
            case "assets.col.category" -> "Category";
            case "assets.col.acquisition_date" -> "Acquisition";
            case "assets.col.cost" -> "Cost";
            case "assets.col.useful_life" -> "Useful life (yrs)";
            case "assets.col.method" -> "Method";
            case "assets.col.active" -> "Active";
            case "assets.category.BUILDING" -> "Building";
            case "assets.category.LAND" -> "Land";
            case "assets.category.MACHINERY" -> "Machinery";
            case "assets.category.VEHICLE" -> "Vehicle";
            case "assets.category.IT_EQUIPMENT" -> "IT equipment";
            case "assets.category.OFFICE_FURNITURE" -> "Office furniture";
            case "assets.category.SOFTWARE" -> "Software";
            case "assets.category.INTANGIBLE" -> "Intangible";
            case "assets.category.OTHER" -> "Other";
            case "assets.method.LINEAR" -> "Linear";
            case "assets.method.DEGRESSIVE" -> "Degressive";
            case "assets.method.NONE" -> "None";
            case "advisory.client.summary.title" -> "Client information";
            case "advisory.client.summary.hint" -> "Basic data captured from the client's company profile.";
            case "advisory.client.field.legal_name" -> "Legal name:";
            case "advisory.client.field.nif" -> "Tax ID:";
            case "advisory.client.field.type" -> "Type:";
            case "advisory.client.field.email" -> "Email:";
            case "advisory.client.field.city" -> "City:";
            case "advisory.client.kpis.title" -> "Activity overview";
            case "advisory.client.kpis.coming_soon" -> "Real-time KPIs (issued invoices, recorded expenses, active employees, latest tax filing) will be shown here in a future slice.";
            case "advisory.client.purchases.hint" -> "Manage your client's expenses: import PDFs, register them and generate journal entries.";
            case "advisory.client.purchases.use_module" -> "Use the full Purchases & Expenses module to operate on this client's data.";
            case "advisory.client.purchases.open" -> "Open Purchases & Expenses";
            case "client_mode.banner.title" -> "Working on client:";
            case "client_mode.banner.title_generic" -> "Working on a client";
            case "client_mode.banner.exit" -> "Exit client mode";
            default -> null;
        };
    }

    private String tAdvisoryInvitationsEs(String key) {
        return switch (key) {
            case "settings.tab.my_advisory" -> "Mi asesoria";
            case "settings.my_advisory.section" -> "Mi asesoria fiscal";
            case "settings.my_advisory.hint" -> "Si una asesoria te ha invitado a gestionar tu empresa, acepta su invitacion desde el banner del Home. Una vez vinculado, tu asesor podra cambiarse a tu empresa desde su pantalla 'Mis clientes' y operar en tu nombre (subir tu certificado, registrar gastos, etc.). Puedes deshacer el vinculo en cualquier momento.";
            case "settings.my_advisory.empty" -> "Tu empresa no esta vinculada a ninguna asesoria. Cuando un asesor te envie una invitacion, veras un banner en el Home para aceptarla.";
            case "settings.my_advisory.field.legal_name" -> "Razon social:";
            case "settings.my_advisory.field.trade_name" -> "Nombre comercial:";
            case "settings.my_advisory.field.nif" -> "NIF:";
            case "settings.my_advisory.field.email" -> "Email:";
            case "settings.my_advisory.action.unlink" -> "Desvincular";
            case "settings.my_advisory.confirm.unlink.title" -> "¿Desvincular de la asesoria?";
            case "settings.my_advisory.confirm.unlink.body" -> "Tu asesor perdera el acceso a tu empresa. Puedes re-vincularte mas tarde aceptando una nueva invitacion.";
            case "settings.my_advisory.fail.load.title" -> "No se pudo cargar la asesoria";
            case "settings.my_advisory.fail.load.body" -> "Comprueba la conexion con el backend.";
            case "settings.my_advisory.fail.unlink.title" -> "No se pudo desvincular";
            case "settings.my_advisory.fail.unlink.body" -> "Intentalo de nuevo o revisa la conexion.";
            case "advisory.action.invite" -> "Invitar cliente";
            case "advisory.invitations.section" -> "Invitaciones";
            case "advisory.invitations.hint" -> "Invitaciones que has emitido (activas e historico). Copia el token de una PENDIENTE y enviaselo al cliente por tu canal habitual — el cliente debe pegarlo desde su pestaña 'Mi asesoria' o aceptar el banner que aparece en su Home.";
            case "advisory.invitations.placeholder.empty" -> "Aun no has emitido invitaciones.";
            case "advisory.invitations.col.date" -> "Fecha";
            case "advisory.invitations.col.email" -> "Email";
            case "advisory.invitations.col.nif" -> "NIF";
            case "advisory.invitations.col.company" -> "Empresa";
            case "advisory.invitations.col.status" -> "Estado";
            case "advisory.invitations.status.PENDING" -> "Pendiente";
            case "advisory.invitations.status.ACCEPTED" -> "Aceptada";
            case "advisory.invitations.status.REJECTED" -> "Rechazada";
            case "advisory.invitations.status.REVOKED" -> "Revocada";
            case "advisory.invitations.status.EXPIRED" -> "Caducada";
            case "advisory.invitations.status.UNLINKED" -> "Desvinculada";
            case "advisory.invitations.action.refresh" -> "Refrescar";
            case "advisory.invitations.action.copy_link" -> "Copiar token";
            case "advisory.invitations.action.revoke" -> "Revocar";
            case "advisory.invitations.create.title" -> "Invitar cliente";
            case "advisory.invitations.create.save" -> "Enviar invitacion";
            case "advisory.invitations.create.hint" -> "Informa email y/o NIF de la empresa que quieres invitar. El sistema genera un token que el cliente debe aceptar desde su sesion.";
            case "advisory.invitations.create.email" -> "Email del cliente:";
            case "advisory.invitations.create.nif" -> "NIF del cliente:";
            case "advisory.invitations.create.company_name" -> "Nombre empresa:";
            case "advisory.invitations.create.notes" -> "Notas:";
            case "advisory.invitations.token_label" -> "Token:";
            case "advisory.invitations.create.ok.title" -> "Invitacion enviada";
            case "advisory.invitations.create.ok.body" -> "El token se ha copiado al portapapeles. Compartelo con el cliente.";
            case "advisory.invitations.create.fail.title" -> "No se pudo enviar";
            case "advisory.invitations.create.fail.body" -> "Comprueba la conexion con el backend.";
            case "advisory.invitations.create.fail.missing.title" -> "Faltan datos";
            case "advisory.invitations.create.fail.missing.body" -> "Se requiere al menos email o NIF.";
            case "advisory.invitations.copied.title" -> "Token copiado";
            case "advisory.invitations.copied.body" -> "Pegalo en la sesion del cliente para vincularle.";
            case "advisory.invitations.revoke.confirm.title" -> "¿Revocar invitacion?";
            case "advisory.invitations.revoke.confirm.body" -> "La invitacion dejara de ser valida.";
            case "advisory.invitations.revoke.fail.title" -> "No se pudo revocar";
            case "advisory.invitations.revoke.fail.body" -> "Intentalo de nuevo o revisa la conexion.";
            case "advisory.invitations.fail.list.title" -> "No se pudieron cargar las invitaciones";
            case "advisory.invitations.fail.list.body" -> "Comprueba la conexion con el backend.";
            case "advisory.invitation.banner.title" -> "Tienes una invitacion de asesoria pendiente";
            case "advisory.invitation.banner.from" -> "Enviada por:";
            case "advisory.invitation.banner.from_generic" -> "Enviada por una asesoria.";
            case "advisory.invitation.banner.body" -> "Si aceptas, el asesor podra cambiarse a tu empresa y gestionarla en tu nombre. Puedes desvincularte en cualquier momento desde Configuracion → Mi asesoria.";
            case "advisory.invitation.banner.accept" -> "Aceptar";
            case "advisory.invitation.banner.reject" -> "Rechazar";
            case "advisory.invitation.accept.ok.title" -> "Vinculado";
            case "advisory.invitation.accept.ok.body" -> "Ya estas vinculado a tu asesoria.";
            case "advisory.invitation.accept.fail.title" -> "No se pudo aceptar";
            case "advisory.invitation.accept.fail.body" -> "Si ya tenias una asesoria vinculada, desvincula primero desde Configuracion → Mi asesoria.";
            case "advisory.invitation.reject.ok.title" -> "Rechazada";
            case "advisory.invitation.reject.ok.body" -> "La invitacion ha sido rechazada.";
            case "advisory.invitation.reject.fail.title" -> "No se pudo rechazar";
            case "advisory.invitation.reject.fail.body" -> "Intentalo de nuevo o revisa la conexion.";
            case "advisory.invitation.toast.title" -> "Nueva invitacion";
            case "advisory.invitation.toast.body" -> "Has recibido una nueva invitacion de asesoria. Ve al Home para aceptarla o rechazarla.";
            case "advisory.toast.new_client.title" -> "Nuevo cliente vinculado";
            case "advisory.toast.new_client.body" -> "Un cliente ha aceptado tu invitacion. Abre 'Mis clientes' para empezar a trabajar con el.";
            case "advisory.action.open_client" -> "Abrir cliente";
            case "advisory.action.invite_selected" -> "📩 Invitar cliente seleccionado";
            case "advisory.action.invite_selected.tip" -> "Envia una invitacion de vinculacion para poder gestionar los datos de su empresa desde aqui.";
            case "advisory.action.reinvite" -> "🔁 Reinvitar";
            case "advisory.action.reinvite.tip" -> "Este cliente estuvo vinculado y luego se desvinculo. Envia una nueva invitacion para volver a vincularlo.";
            case "advisory.action.resend_invitation" -> "✉ Reenviar invitacion";
            case "advisory.action.resend_invitation.tip" -> "El cliente ya esta vinculado. Usa esto solo si ha perdido el acceso (perdida de datos, cambio de dispositivo) y necesita un token nuevo. Aceptar una invitacion idempotente no cambia el vinculo existente.";
            case "advisory.portfolio.subtitle" -> "Cartera unificada — facturación y vinculados en un solo sitio";
            case "advisory.portfolio.hint" -> "Todos tus clientes en un solo lugar: los que solo facturas y los que ademas gestionas. Usa 'Invitar cliente seleccionado' para enviar el token de vinculacion a un cliente que ya tienes en cartera.";
            case "advisory.portfolio.not_linked.title" -> "Cliente todavia no vinculado";
            case "advisory.portfolio.not_linked.body" -> "Este cliente esta en tu cartera de facturacion pero no ha aceptado una invitacion de vinculacion. Envia una para poder gestionar los datos de su empresa desde aqui.";
            case "advisory.col.link_status" -> "Vinculacion";
            case "advisory.link.linked" -> "Vinculado";
            case "advisory.link.pending" -> "Invitacion pendiente";
            case "advisory.link.not_linked" -> "Sin vincular";
            case "advisory.link.unlinked" -> "Desvinculado por el cliente";
            case "advisory.toast.unlinked.title" -> "Cliente desvinculado";
            case "advisory.toast.unlinked.body" -> "Un cliente acaba de desvincularse de tu asesoria. La cartera se ha actualizado.";
            case "advisory.toast.active_client_unlinked.title" -> "El cliente se ha desvinculado mientras trabajabas";
            case "advisory.toast.active_client_unlinked.body" -> "El cliente cuyos datos estabas viendo acaba de desvincularse de tu asesoria. Has vuelto a tu propio panel.";
            case "settings.my_advisory.paste_token.title" -> "¿Tienes un token de invitacion?";
            case "settings.my_advisory.paste_token.hint" -> "Si tu asesor te ha enviado un token de vinculacion (cadena de 32 caracteres), pegalo aqui y pulsa Aceptar para vincularte manualmente.";
            case "settings.my_advisory.paste_token.prompt" -> "Pega aqui tu token de invitacion";
            case "settings.my_advisory.paste_token.accept" -> "Aceptar invitacion";
            case "settings.my_advisory.paste_token.fail.empty.title" -> "Token vacio";
            case "settings.my_advisory.paste_token.fail.empty.body" -> "Pega un token antes de aceptar.";
            case "advisory.client.back" -> "← Volver a Mis clientes";
            case "advisory.client.hint" -> "Estas viendo este cliente. Cualquier accion que hagas desde aqui queda registrada en SU empresa, no en la tuya. Tu barra lateral sigue siendo la de tu asesoria — puedes moverte entre las pestañas libremente.";
            case "advisory.client.tab.summary" -> "Resumen";
            case "advisory.client.tab.billing" -> "Facturación";
            case "advisory.client.tab.purchases" -> "Compras y Gastos";
            case "advisory.client.tab.accounting" -> "Contabilidad";
            case "advisory.client.tab.banks" -> "Bancos";
            case "advisory.client.tab.loans" -> "Préstamos";
            case "advisory.client.tab.assets" -> "Inmovilizado";
            case "advisory.client.tab.labor" -> "Empleados";
            case "advisory.client.tab.tax_models" -> "Modelos AEAT";
            case "advisory.client.tab.certificate" -> "Certificado";
            // ============ Módulo Contabilidad (AccountingScreen) ============
            case "accounting.tab.pending" -> "Por validar";
            case "accounting.tab.diary" -> "Diario";
            case "accounting.tab.manual" -> "Asientos manuales";
            case "accounting.tab.rules" -> "Reglas aprendidas";
            case "accounting.tab.recurring" -> "Recurrentes";
            case "accounting.action.refresh" -> "Refrescar";
            case "accounting.action.validate" -> "Validar";
            case "accounting.action.accept" -> "Aceptar tal cual";
            case "accounting.action.new_entry" -> "Nuevo asiento";
            case "accounting.action.save_draft" -> "Guardar borrador";
            case "accounting.action.close" -> "Cerrar";
            case "accounting.action.toggle" -> "Activar/Desactivar";
            case "accounting.action.delete" -> "Borrar";
            case "accounting.action.run_now" -> "Ejecutar ahora";
            case "accounting.filter.from" -> "Desde";
            case "accounting.filter.to" -> "Hasta";
            case "accounting.filter.status" -> "Estado";
            case "accounting.filter.source" -> "Origen";
            case "accounting.filter.search" -> "Buscar";
            case "accounting.filter.search_prompt" -> "concepto, nº, origen…";
            case "accounting.filter.any" -> "(cualquiera)";
            case "accounting.col.num" -> "Nº";
            case "accounting.col.date" -> "Fecha";
            case "accounting.col.concept" -> "Concepto";
            case "accounting.col.source" -> "Origen";
            case "accounting.col.status" -> "Estado";
            case "accounting.col.debit_total" -> "Debe";
            case "accounting.col.credit_total" -> "Haber";
            case "accounting.col.confidence" -> "Confianza";
            case "accounting.col.account" -> "Cuenta";
            case "accounting.col.description" -> "Descripción";
            case "accounting.col.debit" -> "Debe";
            case "accounting.col.credit" -> "Haber";
            case "accounting.field.date" -> "Fecha:";
            case "accounting.field.concept" -> "Concepto:";
            case "accounting.badge.auto_proposed" -> "PROPUESTA AUTOMÁTICA";
            case "accounting.dialog.new_entry" -> "Nuevo asiento";
            case "accounting.dialog.review_entry" -> "Revisar asiento";
            case "accounting.pending.hint" -> "Asientos generados automáticamente al guardar gastos o validar ventas. Revisa y valida — el sistema aprenderá de tus correcciones.";
            case "accounting.manual.hint" -> "Crea asientos contables manuales (ajustes, periodificaciones, IS estimado, etc.). El balance Debe=Haber se valida automáticamente.";
            case "accounting.rules.hint" -> "Reglas que el sistema ha aprendido de tus correcciones. Cuanto mayor sea la confianza, más automática es la propuesta.";
            case "accounting.recurring.hint" -> "Tareas programadas: gastos recurrentes (luz, alquiler), cuotas de préstamo, plantillas de asiento. Se ejecutan cada día a las 06:10.";
            case "accounting.error.load" -> "No se pudieron cargar los datos";
            case "accounting.error.save" -> "No se pudo guardar el asiento";
            case "accounting.error.accept" -> "No se pudo aceptar el asiento";
            case "accounting.error.toggle" -> "No se pudo cambiar el estado";
            case "accounting.error.delete" -> "No se pudo borrar";
            case "accounting.error.run_now" -> "No se pudo ejecutar";
            case "accounting.confirm.delete_rule" -> "¿Borrar esta regla aprendida? Las correcciones anteriores quedan en el histórico.";
            case "accounting.action.backfill" -> "Regenerar asientos";
            case "accounting.confirm.backfill" -> "Esta acción recorre TODAS las facturas (compras y ventas) sin asiento contable y genera los asientos automáticos que falten. Es seguro: idempotente (no duplica) y respeta el bloqueo de ejercicios cerrados. ¿Continuar?";
            case "accounting.backfill.done" -> "Regeneración completada";
            case "accounting.backfill.result" -> "Asientos generados:\n  · Compras: {p}\n  · Ventas: {s}\n  · Total: {t}\n\nRevisa la pestaña 'Por validar' para validar los asientos auto-propuestos.";
            case "accounting.error.backfill" -> "No se pudieron regenerar asientos";
            case "accounting.action.reclassify" -> "Reclasificar asientos";
            case "accounting.reclassify.done" -> "Reclasificación terminada";
            case "accounting.reclassify.result" -> "Líneas reclasificadas: {n}\nAsientos revisados: {t}\n\nSolo se han tocado asientos en borrador cuya cuenta era genérica (600/700). Las cuentas que el asesor ya había cambiado se respetan.";
            case "accounting.error.reclassify" -> "No se pudieron reclasificar los asientos";
            case "accounting.tercero.title" -> "Plan de sub-cuenta de tercero";
            case "accounting.tercero.length" -> "Longitud del código (dígitos):";
            case "accounting.tercero.mode" -> "Modo de numeración:";
            case "accounting.tercero.mode.sequential" -> "Secuencial (1, 2, 3…)";
            case "accounting.tercero.mode.by_nif" -> "Por dígitos del NIF/CIF";
            case "accounting.tercero.preview" -> "Próximo código de ejemplo: {x}";
            case "accounting.tercero.warn" -> "Los cambios se aplican SOLO a los terceros creados a partir de ahora. Las sub-cuentas existentes NO se renumeran — eso rompería el histórico contable.";
            case "accounting.tercero.error_save" -> "No se pudo guardar la configuración del plan de tercero";
            case "accounting.action.validate_batch" -> "Validar seleccionados";
            case "accounting.confirm.validate_batch" -> "¿Validar {n} asientos seleccionados (en borrador)? Los que ya estén POSTED se saltarán y los que tengan errores (fecha en periodo cerrado, etc.) quedarán como están.";
            case "accounting.validate_batch.result" -> "Validados: {p}\nSaltados: {s}\nErrores: {e}\n\nLa pestaña por validar y el diario se han actualizado.";
            case "accounting.error.validate_batch" -> "No se pudo validar el lote";
            case "accounting.error.session_expired_title" -> "Sesión expirada";
            case "accounting.error.session_expired_body" -> "Tu sesión ha caducado (los tokens duran 8 horas por seguridad).\n\nCierra la aplicación y vuelve a iniciar sesión para continuar trabajando.\n\nNo se ha perdido nada de lo que tenías guardado.";
            // ============ Valores enum traducidos (ES) ============
            case "accounting.status.DRAFT" -> "Borrador";
            case "accounting.status.POSTED" -> "Validado";
            case "accounting.status.VOID" -> "Anulado";
            case "accounting.status.VOIDED" -> "Anulado";
            case "accounting.status.VALIDATED" -> "Validado";
            case "accounting.status.PAID" -> "Pagado";
            case "accounting.status.PARTIAL" -> "Parcial";
            case "accounting.status.OVERDUE" -> "Vencido";
            case "accounting.status.PENDING" -> "Pendiente";
            case "accounting.status.CANCELLED" -> "Cancelado";
            case "accounting.status.PROFORMA" -> "Proforma";
            case "accounting.source_type.SALES_INVOICE" -> "Factura emitida";
            case "accounting.source_type.PURCHASE_INVOICE" -> "Factura recibida";
            case "accounting.source_type.MANUAL" -> "Manual";
            case "accounting.source_type.BANK_MOVEMENT" -> "Movimiento bancario";
            case "accounting.source_type.YEAR_CLOSE_REGULARIZATION" -> "Regularización cierre";
            case "accounting.source_type.YEAR_CLOSE_CLOSING" -> "Cierre ejercicio";
            case "accounting.source_type.LOAN_INSTALLMENT" -> "Cuota préstamo";
            case "accounting.source_type.ASSET_DEPRECIATION" -> "Amortización";
            case "accounting.source_type.ASSET_ACQUISITION" -> "Alta inmovilizado";
            case "accounting.source_type.ASSET_DISPOSAL" -> "Baja inmovilizado";
            case "accounting.source_type.MANUAL_REVERSAL" -> "Contraasiento";
            case "accounting.col.name" -> "Nombre";
            case "accounting.col.rule_kind" -> "Tipo de regla";
            case "accounting.col.nif" -> "NIF";
            case "accounting.col.keyword" -> "Palabra clave";
            case "accounting.col.target_account" -> "→ Cuenta";
            case "accounting.col.applied" -> "Aplicadas";
            case "accounting.col.overridden" -> "Corregidas";
            case "accounting.col.active" -> "Activa";
            case "accounting.col.rec_kind" -> "Tipo";
            case "accounting.col.frequency" -> "Frecuencia";
            case "accounting.col.day" -> "Día";
            case "accounting.col.next_run" -> "Próxima";
            case "accounting.col.last_run" -> "Última";
            case "accounting.col.times_run" -> "Ejecutadas";
            case "accounting.col.times_failed" -> "Fallidas";
            case "accounting.rule_kind.EXPENSE_ACCOUNT_BY_SUPPLIER_NIF" -> "Cuenta gasto por NIF proveedor";
            case "accounting.rule_kind.EXPENSE_ACCOUNT_BY_KEYWORD" -> "Cuenta gasto por palabra clave";
            case "accounting.rule_kind.INCOME_ACCOUNT_BY_CUSTOMER_NIF" -> "Cuenta ingreso por NIF cliente";
            case "accounting.rule_kind.INCOME_ACCOUNT_BY_KEYWORD" -> "Cuenta ingreso por palabra clave";
            case "accounting.rule_kind.VAT_RATE_BY_SUPPLIER_NIF" -> "Tipo IVA por NIF proveedor";
            case "accounting.rec_kind.PURCHASE" -> "Gasto recurrente";
            case "accounting.rec_kind.SALES_INVOICE" -> "Venta recurrente";
            case "accounting.rec_kind.JOURNAL_ENTRY" -> "Asiento recurrente";
            case "accounting.rec_kind.TEMPLATE_APPLY" -> "Plantilla recurrente";
            case "accounting.rec_kind.LOAN_AUTO_PAY" -> "Pago cuota préstamo";
            case "accounting.frequency.DAILY" -> "Diaria";
            case "accounting.frequency.WEEKLY" -> "Semanal";
            case "accounting.frequency.MONTHLY" -> "Mensual";
            case "accounting.frequency.QUARTERLY" -> "Trimestral";
            case "accounting.frequency.YEARLY" -> "Anual";
            case "accounting.frequency.CUSTOM_MONTHS" -> "Custom (meses)";
            case "accounting.run_status.OK" -> "OK";
            case "accounting.run_status.ERROR" -> "Error";
            case "accounting.run_status.SKIPPED" -> "Saltada";
            // ============ Columnas Billing inline (ES) ============
            case "billing.col.number" -> "Número";
            case "billing.col.date" -> "Fecha";
            case "billing.col.customer" -> "Cliente";
            case "billing.col.type" -> "Tipo";
            case "billing.col.total" -> "Total";
            case "billing.col.paid" -> "Cobrado";
            case "billing.col.status" -> "Estado";
            case "billing.col.payment_status" -> "Cobro";
            case "billing.payment_status.PENDING" -> "Pendiente";
            case "billing.payment_status.PAID" -> "Pagado";
            case "billing.payment_status.PARTIAL" -> "Parcial";
            case "billing.payment_status.OVERDUE" -> "Vencido";
            // ============ Columnas Labor inline (ES) ============
            case "labor.col.name" -> "Nombre";
            case "labor.col.nif" -> "NIF";
            case "labor.col.regime" -> "Régimen SS";
            case "labor.col.hire_date" -> "Alta";
            case "labor.col.active" -> "Activo";
            // ============ Columnas Tax filings inline (ES) ============
            case "tax.col.model" -> "Modelo";
            case "tax.col.year" -> "Año";
            case "tax.col.quarter" -> "Trimestre";
            case "tax.col.month" -> "Mes";
            case "tax.col.status" -> "Estado";
            case "tax.col.amount" -> "Importe";
            case "tax.col.deadline" -> "Vencimiento";
            case "tax.filing_status.DRAFT" -> "Borrador";
            case "tax.filing_status.READY" -> "Listo";
            case "tax.filing_status.PRESENTED" -> "Presentado";
            case "tax.filing_status.PAID" -> "Pagado";
            case "tax.filing_status.REJECTED" -> "Rechazado";
            case "tax.filing_status.CANCELLED" -> "Cancelado";
            // ============ Columnas Bank/Loans/Assets (ES) ============
            case "bank.col.alias" -> "Alias";
            case "bank.col.iban" -> "IBAN";
            case "bank.col.bank" -> "Banco";
            case "bank.col.opening" -> "Apertura";
            case "bank.col.active" -> "Activa";
            case "bank.col.date" -> "Fecha";
            case "bank.col.description" -> "Descripción";
            case "bank.col.counterparty" -> "Contraparte";
            case "bank.col.nif" -> "NIF";
            case "bank.col.amount" -> "Importe";
            case "bank.col.balance" -> "Saldo";
            case "bank.col.status" -> "Estado";
            case "bank.col.invoice" -> "Factura";
            case "bank.movement_status.UNRECONCILED" -> "Sin conciliar";
            case "bank.movement_status.MATCHED" -> "Casado";
            case "bank.movement_status.POSTED" -> "Contabilizado";
            case "bank.movement_status.IGNORED" -> "Ignorado";
            case "loans.col.code" -> "Código";
            case "loans.col.description" -> "Descripción";
            case "loans.col.lender" -> "Acreedor";
            case "loans.col.principal" -> "Capital";
            case "loans.col.interest" -> "Interés %";
            case "loans.col.term" -> "Plazo (m)";
            case "loans.col.installment" -> "Cuota";
            case "loans.col.method" -> "Método";
            case "loans.col.status" -> "Estado";
            case "loans.col.due_date" -> "Vencimiento";
            case "loans.col.interest_amount" -> "Interés";
            case "loans.col.remaining" -> "Pendiente";
            case "loans.method.FRENCH" -> "Francés";
            case "loans.method.CONSTANT_PRINCIPAL" -> "Capital constante";
            case "loans.method.BULLET" -> "Bullet";
            case "loans.status.ACTIVE" -> "Activo";
            case "loans.status.PAID_OFF" -> "Liquidado";
            case "loans.status.CANCELLED" -> "Cancelado";
            case "loans.installment_status.PENDING" -> "Pendiente";
            case "loans.installment_status.PAID" -> "Pagada";
            case "loans.installment_status.OVERDUE" -> "Vencida";
            case "loans.installment_status.CANCELLED" -> "Cancelada";
            case "assets.col.code" -> "Código";
            case "assets.col.name" -> "Nombre";
            case "assets.col.category" -> "Categoría";
            case "assets.col.acquisition_date" -> "Adquisición";
            case "assets.col.cost" -> "Coste";
            case "assets.col.useful_life" -> "Vida útil (años)";
            case "assets.col.method" -> "Método";
            case "assets.col.active" -> "Activa";
            case "assets.category.BUILDING" -> "Edificio";
            case "assets.category.LAND" -> "Terreno";
            case "assets.category.MACHINERY" -> "Maquinaria";
            case "assets.category.VEHICLE" -> "Vehículo";
            case "assets.category.IT_EQUIPMENT" -> "Equipo informático";
            case "assets.category.OFFICE_FURNITURE" -> "Mobiliario";
            case "assets.category.SOFTWARE" -> "Software";
            case "assets.category.INTANGIBLE" -> "Inmaterial";
            case "assets.category.OTHER" -> "Otro";
            case "assets.method.LINEAR" -> "Lineal";
            case "assets.method.DEGRESSIVE" -> "Decreciente";
            case "assets.method.NONE" -> "Sin amortización";
            case "advisory.client.summary.title" -> "Datos del cliente";
            case "advisory.client.summary.hint" -> "Datos basicos extraidos del perfil de empresa del cliente.";
            case "advisory.client.field.legal_name" -> "Razon social:";
            case "advisory.client.field.nif" -> "NIF:";
            case "advisory.client.field.type" -> "Tipo:";
            case "advisory.client.field.email" -> "Email:";
            case "advisory.client.field.city" -> "Ciudad:";
            case "advisory.client.kpis.title" -> "Resumen de actividad";
            case "advisory.client.kpis.coming_soon" -> "Aqui se mostraran KPIs en tiempo real (facturas emitidas, gastos registrados, empleados activos, ultimo modelo fiscal) en un slice futuro.";
            case "advisory.client.purchases.hint" -> "Gestiona los gastos de tu cliente: importa PDFs, registralos y genera asientos contables.";
            case "advisory.client.purchases.use_module" -> "Usa el modulo Compras y Gastos completo para operar sobre los datos de este cliente.";
            case "advisory.client.purchases.open" -> "Abrir Compras y Gastos";
            case "client_mode.banner.title" -> "Trabajando sobre el cliente:";
            case "client_mode.banner.title_generic" -> "Trabajando sobre un cliente";
            case "client_mode.banner.exit" -> "Salir del modo cliente";
            default -> null;
        };
    }

    private enum Language {
        ES,
        EN
    }

    // ===================================================================
    //  L1 — Modulo Laboral: empleados + contratos
    // ===================================================================

    private final LaborApiClient laborApiClient = new LaborApiClient();
    private TableView<com.benjagest.ui.model.EmployeeEntry> employeesTable;
    private TableView<com.benjagest.ui.model.ContractEntry> contractsTable;

    /**
     * Modulo Personal unificado con 4 sub-tabs: Empleados, Contratos,
     * Fichajes, Nominas. Carga los datos comunes en paralelo y pinta
     * el TabPane.
     */
    /**
     * Muestra el módulo Contabilidad — delegado a {@link AccountingScreen}
     * para mantener este archivo gestionable. La pantalla incluye:
     * <ul>
     *   <li>Por validar — asientos auto-propuestos con badge confianza</li>
     *   <li>Diario — Libro Diario filtrable</li>
     *   <li>Asientos manuales — botón crear + editor con líneas editables</li>
     *   <li>Reglas aprendidas — listado del feedback contable</li>
     *   <li>Recurrentes — tareas cron contables</li>
     * </ul>
     */
    private void showAccountingModule() {
        com.benjagest.ui.screens.AccountingScreen screen =
                new com.benjagest.ui.screens.AccountingScreen(accountingApiClient, this::t);
        // El módulo es responsivo internamente con TabPane; no necesita
        // scroll externo (es preferible que cada tab maneje su propio
        // scroll en el contenido si lo necesita).
        setCenterAnimated((javafx.scene.Node) screen.buildView());
    }

    private void showLaborModule() {
        Task<LaborBundle> task = new Task<>() {
            @Override
            protected LaborBundle call() throws Exception {
                var employees = laborApiClient.listEmployees(true);
                var contracts = laborApiClient.listContracts(null);
                int year = java.time.LocalDate.now().getYear();
                var payslips = laborApiClient.listPayslips(year, null, null);
                java.util.List<com.benjagest.ui.model.TimeClockEventTypeEntry> evTypes;
                try {
                    evTypes = laborApiClient.listTimeClockEventTypes(true);
                } catch (Exception ex) {
                    // Si el modulo timeclock no esta activo o el endpoint
                    // falla, dejamos lista vacia y la UI cae al hardcode.
                    evTypes = java.util.List.of();
                }
                return new LaborBundle(employees, contracts, payslips, evTypes, year);
            }
        };
        task.setOnSucceeded(ev -> setCenterAnimated(scroll(laborView(task.getValue()))));
        task.setOnFailed(ev -> setCenterAnimated(scroll(errorPanel(t("labor.load_failed")))));
        start(task, "labor-load");
    }

    private record LaborBundle(
            java.util.List<com.benjagest.ui.model.EmployeeEntry> employees,
            java.util.List<com.benjagest.ui.model.ContractEntry> contracts,
            java.util.List<com.benjagest.ui.model.PayslipEntry> payslips,
            java.util.List<com.benjagest.ui.model.TimeClockEventTypeEntry> eventTypes,
            int currentYear) {}

    private VBox laborView(LaborBundle bundle) {
        VBox content = content();
        Label title = new Label(t("labor.title"));
        title.getStyleClass().add("module-detail-title");
        Label subtitle = new Label(t("labor.subtitle"));
        subtitle.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, subtitle);
        StackPane moduleIcon = iconBubble("fas-users", "module-title-icon");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(16, titleBox, moduleIcon, spacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("settings-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab empTab = new Tab(t("labor.tab.employees"), buildEmployeesTab(bundle.employees()));
        empTab.setGraphic(icon("fas-user"));
        Tab contractsTab = new Tab(t("labor.tab.contracts"), buildContractsGlobalTab(bundle));
        contractsTab.setGraphic(icon("fas-file-contract"));
        Tab clockTab = new Tab(t("labor.tab.timeclock"), buildTimeClockTab(bundle.employees(), bundle.eventTypes()));
        clockTab.setGraphic(icon("fas-clock"));
        Tab auditTab = new Tab(t("labor.tab.audit"), buildTimeClockAuditTab(bundle.employees()));
        auditTab.setGraphic(icon("fas-shield-alt"));
        Tab payslipsTab = new Tab(t("labor.tab.payslips"), buildPayslipsTab(bundle));
        payslipsTab.setGraphic(icon("fas-file-invoice-dollar"));
        Tab cfgTab = new Tab(t("labor.tab.cfg_timeclock"), buildEventTypeConfigTab(bundle.eventTypes()));
        cfgTab.setGraphic(icon("fas-cog"));

        tabs.getTabs().addAll(empTab, contractsTab, clockTab, auditTab, payslipsTab, cfgTab);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        content.getChildren().addAll(header, tabs);
        return content;
    }

    // ----- Sub-tab Empleados -----

    private Node buildEmployeesTab(java.util.List<com.benjagest.ui.model.EmployeeEntry> employees) {
        Button newEmployee = new Button(t("labor.action.new_employee"));
        newEmployee.setGraphic(icon("fas-plus"));
        newEmployee.setOnAction(ev -> showEmployeeEditor(null));

        employeesTable = new TableView<>();
        employeesTable.getStyleClass().add("data-table");
        employeesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        employeesTable.setPlaceholder(new Label(t("labor.employees.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.EmployeeEntry, String> colName =
                new TableColumn<>(t("labor.employees.col.name"));
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().fullName()));
        TableColumn<com.benjagest.ui.model.EmployeeEntry, String> colNif =
                new TableColumn<>(t("labor.employees.col.nif"));
        colNif.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().taxIdentifier()));
        colNif.setPrefWidth(120);
        TableColumn<com.benjagest.ui.model.EmployeeEntry, String> colNuss =
                new TableColumn<>(t("labor.employees.col.nuss"));
        colNuss.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().socialSecurityNumber()));
        colNuss.setPrefWidth(140);
        TableColumn<com.benjagest.ui.model.EmployeeEntry, String> colHire =
                new TableColumn<>(t("labor.employees.col.hire_date"));
        colHire.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().hireDate() == null ? "" : c.getValue().hireDate().toString()));
        colHire.setPrefWidth(110);
        colHire.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.EmployeeEntry, String> colSs =
                new TableColumn<>(t("labor.employees.col.ss"));
        colSs.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().ssRegime()));
        colSs.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.EmployeeEntry, String> colGeo =
                new TableColumn<>(t("labor.employees.col.geo"));
        colGeo.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().geolocationEnabled() ? "📍" : ""));
        colGeo.setPrefWidth(60);
        TableColumn<com.benjagest.ui.model.EmployeeEntry, String> colFlags =
                new TableColumn<>(t("labor.employees.col.flags"));
        colFlags.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().active() ? "" : t("labor.employees.inactive")));
        colFlags.setPrefWidth(90);
        employeesTable.getColumns().addAll(java.util.List.of(colName, colNif, colNuss, colHire, colSs, colGeo, colFlags));
        employeesTable.setItems(FXCollections.observableArrayList(employees));
        employeesTable.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                var sel = employeesTable.getSelectionModel().getSelectedItem();
                if (sel != null) showEmployeeEditor(sel);
            }
        });

        Button editBtn = new Button(t("labor.employees.action.edit"));
        editBtn.setGraphic(icon("fas-edit"));
        editBtn.setDisable(true);
        editBtn.setOnAction(ev -> {
            var sel = employeesTable.getSelectionModel().getSelectedItem();
            if (sel != null) showEmployeeEditor(sel);
        });
        Button contractsBtn = new Button(t("labor.employees.action.contracts"));
        contractsBtn.setGraphic(icon("fas-file-contract"));
        contractsBtn.setDisable(true);
        contractsBtn.setOnAction(ev -> {
            var sel = employeesTable.getSelectionModel().getSelectedItem();
            if (sel != null) showEmployeeContracts(sel);
        });
        Button deleteBtn = new Button(t("labor.employees.action.delete"));
        deleteBtn.setGraphic(icon("fas-user-slash"));
        deleteBtn.setDisable(true);
        deleteBtn.setOnAction(ev -> {
            var sel = employeesTable.getSelectionModel().getSelectedItem();
            if (sel != null) deleteEmployee(sel);
        });

        employeesTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            editBtn.setDisable(nv == null);
            contractsBtn.setDisable(nv == null);
            deleteBtn.setDisable(nv == null || !nv.active());
        });

        HBox actions = new HBox(8, newEmployee, editBtn, contractsBtn, deleteBtn);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(0, 0, 8, 0));

        VBox body = new VBox(12, actions, employeesTable);
        VBox.setVgrow(employeesTable, Priority.ALWAYS);
        body.setPadding(new Insets(12));
        return screenScroll(body);
    }

    // ----- Sub-tab Contratos globales -----

    private Node buildContractsGlobalTab(LaborBundle bundle) {
        java.util.Map<String, String> empById = new java.util.HashMap<>();
        for (var e : bundle.employees()) empById.put(e.id(), e.fullName());

        TableView<com.benjagest.ui.model.ContractEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.contracts.placeholder.empty.global")));

        TableColumn<com.benjagest.ui.model.ContractEntry, String> colEmp =
                new TableColumn<>(t("labor.contracts.col.employee"));
        colEmp.setCellValueFactory(c -> new SimpleStringProperty(
                empById.getOrDefault(c.getValue().employeeId(), shortId(c.getValue().employeeId()))));
        colEmp.setPrefWidth(180);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colType =
                new TableColumn<>(t("labor.contracts.col.type"));
        colType.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().contractType()));
        colType.setPrefWidth(140);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colSepe =
                new TableColumn<>(t("labor.contracts.col.sepe"));
        colSepe.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sepeContractCode()));
        colSepe.setPrefWidth(70);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colStart =
                new TableColumn<>(t("labor.contracts.col.start"));
        colStart.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().startDate() == null ? "" : c.getValue().startDate().toString()));
        colStart.setPrefWidth(110);
        colStart.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colEnd =
                new TableColumn<>(t("labor.contracts.col.end"));
        colEnd.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().endDate() == null ? "" : c.getValue().endDate().toString()));
        colEnd.setPrefWidth(110);
        colEnd.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colSalary =
                new TableColumn<>(t("labor.contracts.col.salary"));
        colSalary.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().grossSalary() == null ? "" : c.getValue().grossSalary().toPlainString() + " €"));
        colSalary.setPrefWidth(110);
        colSalary.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colStatus =
                new TableColumn<>(t("labor.contracts.col.status"));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status()));
        colStatus.setPrefWidth(110);
        table.getColumns().addAll(java.util.List.of(colEmp, colType, colSepe, colStart, colEnd, colSalary, colStatus));
        table.setItems(FXCollections.observableArrayList(bundle.contracts()));

        Label hint = new Label(t("labor.contracts.global.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        VBox body = new VBox(8, hint, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        body.setPadding(new Insets(12));
        return body;
    }

    // ----- Sub-tab Fichajes (embebido, con lookup employee↔user) -----

    private Node buildTimeClockTab(java.util.List<com.benjagest.ui.model.EmployeeEntry> allEmployees,
                                    java.util.List<com.benjagest.ui.model.TimeClockEventTypeEntry> eventTypes) {
        // ComboBox de empleados (para admin: poder fichar otros). Por
        // defecto preselecciona el resuelto del usuario actual.
        ComboBox<com.benjagest.ui.model.EmployeeEntry> empCombo = new ComboBox<>();
        empCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.EmployeeEntry e) {
                return e == null ? "" : e.fullName()
                        + (e.taxIdentifier() == null || e.taxIdentifier().isBlank() ? ""
                            : " · " + e.taxIdentifier());
            }
            @Override public com.benjagest.ui.model.EmployeeEntry fromString(String s) { return null; }
        });
        empCombo.getItems().addAll(allEmployees.stream().filter(com.benjagest.ui.model.EmployeeEntry::active).toList());

        Label hint = new Label(t("timeclock.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        // Botones DINAMICOS segun los tipos activos configurados por la
        // empresa (TC-CFG). Si la lista esta vacia, fallback a IN/OUT/
        // BREAK_START/BREAK_END.
        java.util.List<com.benjagest.ui.model.TimeClockEventTypeEntry> activeTypes = eventTypes.stream()
                .filter(com.benjagest.ui.model.TimeClockEventTypeEntry::active)
                .sorted(java.util.Comparator.comparingInt(com.benjagest.ui.model.TimeClockEventTypeEntry::displayOrder))
                .toList();
        if (activeTypes.isEmpty()) {
            // Fallback: tipos hardcoded para no dejar al usuario sin botones
            activeTypes = java.util.List.of(
                    new com.benjagest.ui.model.TimeClockEventTypeEntry(null, "IN", "Entrada", "Clock in", "fas-sign-in-alt", 1, true, false, true),
                    new com.benjagest.ui.model.TimeClockEventTypeEntry(null, "OUT", "Salida", "Clock out", "fas-sign-out-alt", 2, false, false, true),
                    new com.benjagest.ui.model.TimeClockEventTypeEntry(null, "BREAK_START", "Inicio pausa", "Break start", "fas-coffee", 3, false, true, true),
                    new com.benjagest.ui.model.TimeClockEventTypeEntry(null, "BREAK_END", "Fin pausa", "Break end", "fas-utensils", 4, true, true, true)
            );
        }

        TableView<com.benjagest.ui.model.TimeClockEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("timeclock.placeholder.empty")));
        TableColumn<com.benjagest.ui.model.TimeClockEntry, String> colWhen =
                new TableColumn<>(t("timeclock.col.when"));
        colWhen.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().eventTimeIso()));
        colWhen.setPrefWidth(180);
        colWhen.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TimeClockEntry, String> colType =
                new TableColumn<>(t("timeclock.col.type"));
        colType.setCellValueFactory(c -> new SimpleStringProperty(localizedPunchType(c.getValue().eventType())));
        colType.setPrefWidth(140);
        TableColumn<com.benjagest.ui.model.TimeClockEntry, String> colOrigin =
                new TableColumn<>(t("timeclock.col.origin"));
        colOrigin.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().origin()));
        colOrigin.setPrefWidth(100);
        TableColumn<com.benjagest.ui.model.TimeClockEntry, String> colStatus =
                new TableColumn<>(t("timeclock.col.status"));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status()));
        colStatus.setPrefWidth(110);
        table.getColumns().addAll(java.util.List.of(colWhen, colType, colOrigin, colStatus));
        timeClockTable = table;

        Runnable reload = () -> {
            var sel = empCombo.getValue();
            if (sel == null) {
                table.getItems().clear();
                return;
            }
            reloadTimeClock(sel.id());
        };

        java.util.function.Consumer<String> doPunch = (eventType) -> {
            var sel = empCombo.getValue();
            if (sel == null) {
                showError(t("timeclock.error.no_employee.title"),
                        t("timeclock.error.no_employee.body"));
                return;
            }
            punch(sel.id(), eventType);
            // refresh tras un pequeño delay para que aparezca el nuevo
            Task<Void> delayed = new Task<>() {
                @Override protected Void call() throws Exception { Thread.sleep(500); return null; }
            };
            delayed.setOnSucceeded(ev -> reload.run());
            start(delayed, "tc-reload-after-punch");
        };

        // (los onAction de los botones dinamicos se enganchan abajo)

        Button refresh = new Button(t("timeclock.action.refresh"));
        refresh.setGraphic(icon("fas-sync"));
        refresh.setOnAction(ev -> reload.run());

        empCombo.setOnAction(ev -> reload.run());

        // Resolver el empleado del usuario actual (lookup automático)
        Task<String> resolveTask = new Task<>() {
            @Override
            protected String call() throws Exception {
                return laborApiClient.resolveEmployeeIdForCurrentUser(AuthSession.get().userId());
            }
        };
        resolveTask.setOnSucceeded(ev -> {
            String resolved = resolveTask.getValue();
            if (resolved != null) {
                for (var e : empCombo.getItems()) {
                    if (resolved.equals(e.id())) { empCombo.setValue(e); break; }
                }
            }
            if (empCombo.getValue() == null && !empCombo.getItems().isEmpty()) {
                empCombo.setValue(empCombo.getItems().get(0));
            }
        });
        start(resolveTask, "tc-resolve-employee");

        HBox punchRow = new HBox(12);
        punchRow.setAlignment(Pos.CENTER_LEFT);
        for (var type : activeTypes) {
            Button b = new Button(language == Language.ES ? type.labelEs() : type.labelEn());
            if (type.icon() != null && !type.icon().isBlank()) {
                b.setGraphic(icon(type.icon()));
            }
            // Resaltado para los tipos que abren tiempo de trabajo (IN, fin
            // pausa) — visualmente diferencia los "verdes" de los demas.
            if (type.isWorkTime()) {
                b.getStyleClass().add("invoice-validate-action");
            }
            b.setMinHeight(48); b.setMinWidth(140);
            final String code = type.code();
            b.setOnAction(ev -> doPunch.accept(code));
            punchRow.getChildren().add(b);
        }
        HBox empRow = new HBox(8, new Label(t("timeclock.employee.label")), empCombo, refresh);
        empRow.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(12, hint, empRow, punchRow, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        body.setPadding(new Insets(12));
        return screenScroll(body);
    }

    // ----- Sub-tab Nominas -----

    private TableView<com.benjagest.ui.model.PayslipEntry> payslipsTable;

    private Node buildPayslipsTab(LaborBundle bundle) {
        java.util.Map<String, String> empById = new java.util.HashMap<>();
        for (var e : bundle.employees()) empById.put(e.id(), e.fullName());

        payslipsTable = new TableView<>();
        payslipsTable.getStyleClass().add("data-table");
        payslipsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        payslipsTable.setPlaceholder(new Label(t("labor.payslips.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cPeriod =
                new TableColumn<>(t("labor.payslips.col.period"));
        cPeriod.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().periodYear() + " · " + String.format("%02d", c.getValue().periodMonth())));
        cPeriod.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cEmp =
                new TableColumn<>(t("labor.payslips.col.employee"));
        cEmp.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().employeeName() == null || c.getValue().employeeName().isBlank()
                        ? empById.getOrDefault(c.getValue().employeeId(), shortId(c.getValue().employeeId()))
                        : c.getValue().employeeName()));
        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cType =
                new TableColumn<>(t("labor.payslips.col.type"));
        cType.setCellValueFactory(c -> new SimpleStringProperty(t("labor.payslips.type." + c.getValue().payslipType())));
        cType.setPrefWidth(120);
        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cGross =
                new TableColumn<>(t("labor.payslips.col.gross"));
        cGross.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().grossAmount() == null ? "" : c.getValue().grossAmount().toPlainString() + " €"));
        cGross.setPrefWidth(110);
        cGross.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cSs =
                new TableColumn<>(t("labor.payslips.col.ss"));
        cSs.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().ssEmployeeAmount() == null ? "" : c.getValue().ssEmployeeAmount().toPlainString() + " €"));
        cSs.setPrefWidth(100);
        cSs.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cIrpf =
                new TableColumn<>(t("labor.payslips.col.irpf"));
        cIrpf.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().irpfAmount() == null ? "" : c.getValue().irpfAmount().toPlainString() + " €"));
        cIrpf.setPrefWidth(100);
        cIrpf.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cNet =
                new TableColumn<>(t("labor.payslips.col.net"));
        cNet.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().netAmount() == null ? "" : c.getValue().netAmount().toPlainString() + " €"));
        cNet.setPrefWidth(110);
        cNet.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.PayslipEntry, String> cStatus =
                new TableColumn<>(t("labor.payslips.col.status"));
        cStatus.setCellValueFactory(c -> new SimpleStringProperty(t("labor.payslips.status." + c.getValue().status())));
        cStatus.setPrefWidth(110);
        payslipsTable.getColumns().addAll(java.util.List.of(cPeriod, cEmp, cType, cGross, cSs, cIrpf, cNet, cStatus));
        payslipsTable.setItems(FXCollections.observableArrayList(bundle.payslips()));

        Button calcBtn = new Button(t("labor.payslips.action.calculate"));
        calcBtn.setGraphic(icon("fas-calculator"));
        calcBtn.setOnAction(ev -> showCalculatePayslipDialog(bundle.employees()));

        Button payBtn = new Button(t("labor.payslips.action.pay"));
        payBtn.setGraphic(icon("fas-money-check-alt"));
        payBtn.setDisable(true);
        payBtn.setOnAction(ev -> {
            var sel = payslipsTable.getSelectionModel().getSelectedItem();
            if (sel != null) markPayslipPaid(sel);
        });

        Button pdfBtn = new Button(t("labor.payslips.action.pdf"));
        pdfBtn.setGraphic(icon("fas-file-pdf"));
        pdfBtn.setDisable(true);
        pdfBtn.setOnAction(ev -> {
            var sel = payslipsTable.getSelectionModel().getSelectedItem();
            if (sel != null) downloadPayslipPdf(sel);
        });

        Button emailBtn = new Button(t("labor.payslips.action.email"));
        emailBtn.setGraphic(icon("fas-envelope"));
        emailBtn.setDisable(true);
        emailBtn.setOnAction(ev -> {
            var sel = payslipsTable.getSelectionModel().getSelectedItem();
            if (sel != null) emailPayslip(sel);
        });

        Button delBtn = new Button(t("labor.payslips.action.delete"));
        delBtn.setGraphic(icon("fas-trash"));
        delBtn.setDisable(true);
        delBtn.setOnAction(ev -> {
            var sel = payslipsTable.getSelectionModel().getSelectedItem();
            if (sel != null) deletePayslip(sel);
        });

        payslipsTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            boolean none = nv == null;
            payBtn.setDisable(none || "PAID".equals(nv == null ? "" : nv.status()));
            pdfBtn.setDisable(none);
            emailBtn.setDisable(none);
            delBtn.setDisable(none || "PAID".equals(nv == null ? "" : nv.status()));
        });

        HBox actions = new HBox(8, calcBtn, payBtn, pdfBtn, emailBtn, delBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label(t("labor.payslips.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        VBox body = new VBox(10, hint, actions, payslipsTable);
        VBox.setVgrow(payslipsTable, Priority.ALWAYS);
        body.setPadding(new Insets(12));
        return screenScroll(body);
    }

    private void showCalculatePayslipDialog(java.util.List<com.benjagest.ui.model.EmployeeEntry> employees) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("labor.payslips.calc.title"));
        ButtonType saveBt = new ButtonType(t("labor.payslips.calc.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        ComboBox<com.benjagest.ui.model.EmployeeEntry> empCombo = new ComboBox<>();
        empCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.EmployeeEntry e) {
                return e == null ? "" : e.fullName();
            }
            @Override public com.benjagest.ui.model.EmployeeEntry fromString(String s) { return null; }
        });
        empCombo.getItems().addAll(employees.stream().filter(com.benjagest.ui.model.EmployeeEntry::active).toList());
        if (!empCombo.getItems().isEmpty()) empCombo.getSelectionModel().selectFirst();

        ComboBox<Integer> yearCombo = new ComboBox<>();
        int year = java.time.LocalDate.now().getYear();
        for (int y = year + 1; y >= year - 5; y--) yearCombo.getItems().add(y);
        yearCombo.getSelectionModel().select(Integer.valueOf(year));

        ComboBox<Integer> monthCombo = new ComboBox<>();
        for (int m = 1; m <= 12; m++) monthCombo.getItems().add(m);
        monthCombo.getSelectionModel().select(Integer.valueOf(java.time.LocalDate.now().getMonthValue()));

        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("MONTHLY", "EXTRA_SUMMER", "EXTRA_CHRISTMAS", "BONUS", "SETTLEMENT");
        typeCombo.getSelectionModel().select("MONTHLY");

        CheckBox extraProrated = new CheckBox(t("labor.payslips.calc.extra_prorated"));
        extraProrated.setSelected(true);

        TextField otherField = new TextField();
        otherField.setPromptText(t("labor.payslips.calc.other_deductions.prompt"));

        TextArea notesArea = new TextArea(); notesArea.setPrefRowCount(2);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(10));
        g.add(new Label(t("labor.payslips.calc.employee")), 0, 0); g.add(empCombo, 1, 0);
        g.add(new Label(t("labor.payslips.calc.year")), 0, 1); g.add(yearCombo, 1, 1);
        g.add(new Label(t("labor.payslips.calc.month")), 0, 2); g.add(monthCombo, 1, 2);
        g.add(new Label(t("labor.payslips.calc.type")), 0, 3); g.add(typeCombo, 1, 3);
        g.add(extraProrated, 1, 4);
        g.add(new Label(t("labor.payslips.calc.other_deductions")), 0, 5); g.add(otherField, 1, 5);
        g.add(new Label(t("labor.payslips.calc.notes")), 0, 6); g.add(notesArea, 1, 6);
        installDialog(dialog, g);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            var emp = empCombo.getValue();
            if (emp == null) {
                showError(t("labor.payslips.calc.fail.title"), t("labor.payslips.calc.fail.no_employee"));
                return;
            }
            java.math.BigDecimal other = parseDecSafe(otherField.getText());
            Task<com.benjagest.ui.model.PayslipEntry> task = new Task<>() {
                @Override
                protected com.benjagest.ui.model.PayslipEntry call() throws Exception {
                    return laborApiClient.calculatePayslip(emp.id(),
                            yearCombo.getValue(), monthCombo.getValue(),
                            typeCombo.getValue(), extraProrated.isSelected(),
                            other, blankToNullOrSelf(notesArea.getText()));
                }
            };
            task.setOnSucceeded(ev -> showLaborModule());
            task.setOnFailed(ev -> showError(t("labor.payslips.calc.fail.title"),
                    t("labor.payslips.calc.fail.body")));
            start(task, "payslip-calculate");
        });
    }

    private void markPayslipPaid(com.benjagest.ui.model.PayslipEntry p) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("labor.payslips.pay.body") + " " + p.employeeName() + " (" + p.periodMonth() + "/" + p.periodYear() + ")",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("labor.payslips.pay.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.markPayslipPaid(p.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> showLaborModule());
            task.setOnFailed(ev -> showError(t("labor.payslips.calc.fail.title"), t("labor.payslips.calc.fail.body")));
            start(task, "payslip-pay");
        });
    }

    private void downloadPayslipPdf(com.benjagest.ui.model.PayslipEntry p) {
        Task<byte[]> task = new Task<>() {
            @Override protected byte[] call() throws Exception {
                return laborApiClient.downloadPayslipPdf(p.id());
            }
        };
        task.setOnSucceeded(ev -> {
            byte[] bytes = task.getValue();
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle(t("labor.payslips.pdf.save_as"));
            fc.setInitialFileName("nomina-" + p.periodYear() + "-"
                    + String.format("%02d", p.periodMonth()) + "-"
                    + (p.employeeName() == null ? p.employeeId() : p.employeeName().replace(" ", "_")) + ".pdf");
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("PDF", "*.pdf"));
            java.io.File f = fc.showSaveDialog(root.getScene().getWindow());
            if (f == null) return;
            try {
                java.nio.file.Files.write(f.toPath(), bytes);
                if (java.awt.Desktop.isDesktopSupported()) {
                    try { java.awt.Desktop.getDesktop().open(f); } catch (Exception ignored) {}
                }
            } catch (java.io.IOException ex) {
                showError(t("labor.payslips.pdf.fail.title"), ex.getMessage());
            }
        });
        task.setOnFailed(ev -> showError(t("labor.payslips.pdf.fail.title"),
                t("labor.payslips.pdf.fail.body")));
        start(task, "payslip-pdf");
    }

    private void emailPayslip(com.benjagest.ui.model.PayslipEntry p) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("labor.payslips.email.body") + " " + p.employeeName(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("labor.payslips.email.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.emailPayslipToEmployee(p.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> showInfo(t("labor.payslips.email.ok.title"),
                    t("labor.payslips.email.ok.body")));
            task.setOnFailed(ev -> showError(t("labor.payslips.email.fail.title"),
                    t("labor.payslips.email.fail.body")));
            start(task, "payslip-email");
        });
    }

    private void deletePayslip(com.benjagest.ui.model.PayslipEntry p) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("labor.payslips.delete.body") + " " + p.periodMonth() + "/" + p.periodYear(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("labor.payslips.delete.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.deletePayslip(p.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> showLaborModule());
            task.setOnFailed(ev -> showError(t("labor.payslips.calc.fail.title"),
                    t("labor.payslips.calc.fail.body")));
            start(task, "payslip-delete");
        });
    }

    private void showInfo(String title, String body) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, body);
        a.setHeaderText(title);
        a.showAndWait();
    }

    // ----- Sub-tab Auditoría fichajes (TC-AUDIT) -----

    private Node buildTimeClockAuditTab(java.util.List<com.benjagest.ui.model.EmployeeEntry> allEmployees) {
        Label hint = new Label(t("labor.audit.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        // Filtros
        LocalDate defaultFrom = LocalDate.now().withDayOfMonth(1);
        LocalDate defaultTo = LocalDate.now();
        TextField fromField = new TextField(defaultFrom.toString());
        TextField toField = new TextField(defaultTo.toString());
        fromField.setPrefColumnCount(12);
        toField.setPrefColumnCount(12);

        ComboBox<com.benjagest.ui.model.EmployeeEntry> empCombo = new ComboBox<>();
        empCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.EmployeeEntry e) {
                return e == null ? "(todos)" : e.fullName();
            }
            @Override public com.benjagest.ui.model.EmployeeEntry fromString(String s) { return null; }
        });
        empCombo.getItems().add(null);
        empCombo.getItems().addAll(allEmployees);
        empCombo.setValue(null);

        TextField eventTypeField = new TextField();
        eventTypeField.setPromptText("IN, OUT, COMIDA…");
        eventTypeField.setPrefColumnCount(8);

        // Tabla resumen por empleado
        TableView<com.benjagest.ui.model.TimeClockAuditSummary> summaryTable = new TableView<>();
        summaryTable.getStyleClass().add("data-table");
        summaryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        summaryTable.setPlaceholder(new Label(t("labor.audit.summary.placeholder.empty")));
        summaryTable.setPrefHeight(180);

        TableColumn<com.benjagest.ui.model.TimeClockAuditSummary, String> sName =
                new TableColumn<>(t("labor.audit.col.employee"));
        sName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().employeeName()));
        sName.setPrefWidth(200);
        TableColumn<com.benjagest.ui.model.TimeClockAuditSummary, String> sTotal =
                new TableColumn<>(t("labor.audit.col.total"));
        sTotal.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().totalEvents())));
        sTotal.setPrefWidth(80);
        sTotal.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TimeClockAuditSummary, String> sIns =
                new TableColumn<>(t("labor.audit.col.ins"));
        sIns.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().ins())));
        sIns.setPrefWidth(70);
        sIns.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TimeClockAuditSummary, String> sOuts =
                new TableColumn<>(t("labor.audit.col.outs"));
        sOuts.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().outs())));
        sOuts.setPrefWidth(70);
        sOuts.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TimeClockAuditSummary, String> sPauses =
                new TableColumn<>(t("labor.audit.col.pauses"));
        sPauses.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().pauses())));
        sPauses.setPrefWidth(80);
        sPauses.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TimeClockAuditSummary, String> sCorr =
                new TableColumn<>(t("labor.audit.col.corrections"));
        sCorr.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().corrections())));
        sCorr.setPrefWidth(110);
        sCorr.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TimeClockAuditSummary, String> sInc =
                new TableColumn<>(t("labor.audit.col.incidence"));
        sInc.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().hasIncidence() ? "⚠ " + t("labor.audit.incidence.yes") : t("labor.audit.incidence.no")));
        sInc.setPrefWidth(130);
        summaryTable.getColumns().addAll(java.util.List.of(sName, sTotal, sIns, sOuts, sPauses, sCorr, sInc));

        // Tabla detalle (eventos)
        TableView<com.benjagest.ui.model.TimeClockAuditEntry> detailTable = new TableView<>();
        detailTable.getStyleClass().add("data-table");
        detailTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        detailTable.setPlaceholder(new Label(t("labor.audit.detail.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.TimeClockAuditEntry, String> dWhen =
                new TableColumn<>(t("labor.audit.col.when"));
        dWhen.setCellValueFactory(c -> new SimpleStringProperty(shortIso(c.getValue().eventTime())));
        dWhen.setPrefWidth(160);
        dWhen.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TimeClockAuditEntry, String> dEmp =
                new TableColumn<>(t("labor.audit.col.employee"));
        dEmp.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().employeeName()));
        dEmp.setPrefWidth(180);
        TableColumn<com.benjagest.ui.model.TimeClockAuditEntry, String> dType =
                new TableColumn<>(t("labor.audit.col.type"));
        dType.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().eventType()));
        dType.setPrefWidth(120);
        TableColumn<com.benjagest.ui.model.TimeClockAuditEntry, String> dOrigin =
                new TableColumn<>(t("labor.audit.col.origin"));
        dOrigin.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().origin()));
        dOrigin.setPrefWidth(90);
        TableColumn<com.benjagest.ui.model.TimeClockAuditEntry, String> dStatus =
                new TableColumn<>(t("labor.audit.col.status"));
        dStatus.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status()));
        dStatus.setPrefWidth(100);
        TableColumn<com.benjagest.ui.model.TimeClockAuditEntry, String> dCorr =
                new TableColumn<>(t("labor.audit.col.has_corrections"));
        dCorr.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().correctionCount() > 0
                        ? "✎ " + c.getValue().correctionCount() : ""));
        dCorr.setPrefWidth(90);
        TableColumn<com.benjagest.ui.model.TimeClockAuditEntry, String> dCsv =
                new TableColumn<>(t("labor.audit.col.csv"));
        dCsv.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().csv()));
        detailTable.getColumns().addAll(java.util.List.of(dWhen, dEmp, dType, dOrigin, dStatus, dCorr, dCsv));

        Runnable reload = () -> {
            String from = fromField.getText().trim();
            String to = toField.getText().trim();
            var emp = empCombo.getValue();
            String empId = emp == null ? null : emp.id();
            String ev = eventTypeField.getText().trim();
            // resumen
            Task<java.util.List<com.benjagest.ui.model.TimeClockAuditSummary>> sumTask = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.TimeClockAuditSummary> call() throws Exception {
                    return laborApiClient.auditSummary(from, to);
                }
            };
            sumTask.setOnSucceeded(ev2 -> summaryTable.setItems(FXCollections.observableArrayList(sumTask.getValue())));
            sumTask.setOnFailed(ev2 -> summaryTable.getItems().clear());
            start(sumTask, "tc-audit-summary");
            // detalle
            Task<java.util.List<com.benjagest.ui.model.TimeClockAuditEntry>> detTask = new Task<>() {
                @Override protected java.util.List<com.benjagest.ui.model.TimeClockAuditEntry> call() throws Exception {
                    return laborApiClient.queryAudit(from, to, empId, ev);
                }
            };
            detTask.setOnSucceeded(ev2 -> detailTable.setItems(FXCollections.observableArrayList(detTask.getValue())));
            detTask.setOnFailed(ev2 -> detailTable.getItems().clear());
            start(detTask, "tc-audit-detail");
        };

        Button reloadBtn = new Button(t("labor.audit.action.reload"));
        reloadBtn.setGraphic(icon("fas-sync-alt"));
        reloadBtn.setOnAction(ev -> reload.run());

        Button exportBtn = new Button(t("labor.audit.action.export"));
        exportBtn.setGraphic(icon("fas-file-export"));
        exportBtn.setDisable(true); // habilitado tras TC-EXPORT (próximo slice)
        exportBtn.setTooltip(new javafx.scene.control.Tooltip(t("labor.audit.export.tooltip")));

        HBox filters = new HBox(8,
                new Label(t("labor.audit.filter.from")), fromField,
                new Label(t("labor.audit.filter.to")), toField,
                new Label(t("labor.audit.filter.employee")), empCombo,
                new Label(t("labor.audit.filter.type")), eventTypeField,
                reloadBtn, exportBtn);
        filters.setAlignment(Pos.CENTER_LEFT);

        // Click sobre fila del resumen → filtra el detalle por ese empleado
        summaryTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (nv == null) return;
            for (var e : empCombo.getItems()) {
                if (e != null && nv.employeeId().equals(e.id())) {
                    empCombo.setValue(e);
                    reload.run();
                    return;
                }
            }
        });

        Label summaryTitle = label(t("labor.audit.section.summary"), "settings-section-title");
        Label detailTitle = label(t("labor.audit.section.detail"), "settings-section-title");

        VBox body = new VBox(10,
                hint, filters,
                summaryTitle, summaryTable,
                detailTitle, detailTable);
        VBox.setVgrow(detailTable, Priority.ALWAYS);
        body.setPadding(new Insets(12));

        // Carga inicial
        Task<Void> initial = new Task<>() {
            @Override protected Void call() throws Exception { Thread.sleep(50); return null; }
        };
        initial.setOnSucceeded(ev -> reload.run());
        start(initial, "tc-audit-initial");

        return screenScroll(body);
    }

    // ----- Sub-tab Config Fichajes (TC-CFG) -----

    private Node buildEventTypeConfigTab(java.util.List<com.benjagest.ui.model.TimeClockEventTypeEntry> types) {
        Label hint = new Label(t("labor.cfg_timeclock.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        TableView<com.benjagest.ui.model.TimeClockEventTypeEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("labor.cfg_timeclock.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.TimeClockEventTypeEntry, String> cOrder =
                new TableColumn<>(t("labor.cfg_timeclock.col.order"));
        cOrder.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().displayOrder())));
        cOrder.setPrefWidth(70);
        cOrder.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TimeClockEventTypeEntry, String> cCode =
                new TableColumn<>(t("labor.cfg_timeclock.col.code"));
        cCode.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().code()));
        cCode.setPrefWidth(140);
        TableColumn<com.benjagest.ui.model.TimeClockEventTypeEntry, String> cEs =
                new TableColumn<>(t("labor.cfg_timeclock.col.label_es"));
        cEs.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().labelEs()));
        TableColumn<com.benjagest.ui.model.TimeClockEventTypeEntry, String> cEn =
                new TableColumn<>(t("labor.cfg_timeclock.col.label_en"));
        cEn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().labelEn()));
        TableColumn<com.benjagest.ui.model.TimeClockEventTypeEntry, String> cFlags =
                new TableColumn<>(t("labor.cfg_timeclock.col.flags"));
        cFlags.setCellValueFactory(c -> {
            var e = c.getValue();
            StringBuilder sb = new StringBuilder();
            if (e.isWorkTime()) sb.append("▶ ");
            if (e.isPause()) sb.append("⏸ ");
            if (!e.active()) sb.append(t("labor.cfg_timeclock.inactive"));
            return new SimpleStringProperty(sb.toString());
        });
        cFlags.setPrefWidth(120);
        table.getColumns().addAll(java.util.List.of(cOrder, cCode, cEs, cEn, cFlags));
        table.setItems(FXCollections.observableArrayList(types));

        Button addBtn = new Button(t("labor.cfg_timeclock.action.add"));
        addBtn.setGraphic(icon("fas-plus"));
        addBtn.setOnAction(ev -> showEventTypeEditor(null));

        Button editBtn = new Button(t("labor.cfg_timeclock.action.edit"));
        editBtn.setGraphic(icon("fas-edit"));
        editBtn.setDisable(true);
        editBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) showEventTypeEditor(sel);
        });

        Button delBtn = new Button(t("labor.cfg_timeclock.action.delete"));
        delBtn.setGraphic(icon("fas-trash"));
        delBtn.setDisable(true);
        delBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) deleteEventType(sel);
        });

        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            editBtn.setDisable(nv == null);
            delBtn.setDisable(nv == null);
        });

        Label legend = new Label(t("labor.cfg_timeclock.legend"));
        legend.setWrapText(true);
        legend.getStyleClass().add("settings-hint");

        HBox actions = new HBox(8, addBtn, editBtn, delBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(10, hint, actions, table, legend);
        VBox.setVgrow(table, Priority.ALWAYS);
        body.setPadding(new Insets(12));
        return screenScroll(body);
    }

    private void showEventTypeEditor(com.benjagest.ui.model.TimeClockEventTypeEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? t("labor.cfg_timeclock.editor.title_new")
                : t("labor.cfg_timeclock.editor.title_edit"));
        ButtonType saveBt = new ButtonType(t("labor.cfg_timeclock.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField codeField = new TextField(existing == null ? "" : existing.code());
        codeField.setDisable(existing != null);
        codeField.setPromptText("EJ. COMIDA, REUNION_CLIENTE…");
        TextField esField = new TextField(existing == null ? "" : existing.labelEs());
        TextField enField = new TextField(existing == null ? "" : existing.labelEn());
        TextField iconField = new TextField(existing == null ? "fas-clock" : existing.icon());
        iconField.setPromptText("fas-clock, fas-coffee, fas-users…");
        TextField orderField = new TextField(existing == null ? "" : String.valueOf(existing.displayOrder()));
        CheckBox isWork = new CheckBox(t("labor.cfg_timeclock.editor.is_work_time"));
        isWork.setSelected(existing == null || existing.isWorkTime());
        CheckBox isPause = new CheckBox(t("labor.cfg_timeclock.editor.is_pause"));
        isPause.setSelected(existing != null && existing.isPause());
        CheckBox active = new CheckBox(t("labor.cfg_timeclock.editor.active"));
        active.setSelected(existing == null || existing.active());

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(10));
        g.add(new Label(t("labor.cfg_timeclock.editor.code")), 0, 0); g.add(codeField, 1, 0);
        g.add(new Label(t("labor.cfg_timeclock.editor.label_es")), 0, 1); g.add(esField, 1, 1);
        g.add(new Label(t("labor.cfg_timeclock.editor.label_en")), 0, 2); g.add(enField, 1, 2);
        g.add(new Label(t("labor.cfg_timeclock.editor.icon")), 0, 3); g.add(iconField, 1, 3);
        g.add(new Label(t("labor.cfg_timeclock.editor.order")), 0, 4); g.add(orderField, 1, 4);
        g.add(isWork, 1, 5);
        g.add(isPause, 1, 6);
        g.add(active, 1, 7);
        Label flagsHint = new Label(t("labor.cfg_timeclock.editor.flags_hint"));
        flagsHint.setWrapText(true);
        flagsHint.getStyleClass().add("settings-hint");
        g.add(flagsHint, 0, 8, 2, 1);
        installDialog(dialog, g);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            Integer order = parseIntSafe(orderField.getText());
            Task<com.benjagest.ui.model.TimeClockEventTypeEntry> task = new Task<>() {
                @Override protected com.benjagest.ui.model.TimeClockEventTypeEntry call() throws Exception {
                    if (existing == null) {
                        return laborApiClient.createEventType(
                                codeField.getText().trim().toUpperCase(),
                                esField.getText().trim(),
                                enField.getText().trim(),
                                blankToNullOrSelf(iconField.getText()),
                                order, isWork.isSelected(), isPause.isSelected(), active.isSelected());
                    }
                    return laborApiClient.updateEventType(
                            existing.id(),
                            existing.code(),
                            esField.getText().trim(),
                            enField.getText().trim(),
                            blankToNullOrSelf(iconField.getText()),
                            order, isWork.isSelected(), isPause.isSelected(), active.isSelected());
                }
            };
            task.setOnSucceeded(ev -> showLaborModule());
            task.setOnFailed(ev -> showError(t("labor.cfg_timeclock.editor.fail.title"),
                    t("labor.cfg_timeclock.editor.fail.body")));
            start(task, "tc-cfg-save");
        });
    }

    private void deleteEventType(com.benjagest.ui.model.TimeClockEventTypeEntry type) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("labor.cfg_timeclock.delete.body") + " " + type.code(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("labor.cfg_timeclock.delete.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.deleteEventType(type.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> showLaborModule());
            task.setOnFailed(ev -> showError(t("labor.cfg_timeclock.editor.fail.title"),
                    t("labor.cfg_timeclock.editor.fail.body")));
            start(task, "tc-cfg-delete");
        });
    }

    private void showEmployeeEditor(com.benjagest.ui.model.EmployeeEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? t("labor.employee.editor.title_new") : t("labor.employee.editor.title_edit"));
        ButtonType saveBt = new ButtonType(t("labor.employee.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField nameField = new TextField(existing == null ? "" : existing.fullName());
        TextField nifField = new TextField(existing == null ? "" : existing.taxIdentifier());
        TextField nussField = new TextField(existing == null ? "" : existing.socialSecurityNumber());
        TextField emailField = new TextField(existing == null ? "" : existing.email());
        TextField phoneField = new TextField(existing == null ? "" : existing.phone());
        TextField birthField = new TextField(existing == null || existing.birthDate() == null
                ? "" : existing.birthDate().toString());
        birthField.setPromptText("AAAA-MM-DD");
        ComboBox<String> genderCombo = new ComboBox<>();
        genderCombo.getItems().addAll("", "MALE", "FEMALE", "OTHER");
        genderCombo.getSelectionModel().select(existing == null || existing.gender() == null ? "" : existing.gender());
        ComboBox<String> maritalCombo = new ComboBox<>();
        maritalCombo.getItems().addAll("", "SINGLE", "MARRIED", "DIVORCED", "WIDOWED", "DOMESTIC_PARTNER");
        maritalCombo.getSelectionModel().select(existing == null || existing.maritalStatus() == null ? "" : existing.maritalStatus());
        TextField childrenField = new TextField(existing == null || existing.dependentChildren() == null
                ? "" : existing.dependentChildren().toString());
        TextField disabledField = new TextField(existing == null || existing.dependentDisabled() == null
                ? "" : existing.dependentDisabled().toString());

        TextField addressField = new TextField(existing == null ? "" : existing.addressLine());
        TextField cityField = new TextField(existing == null ? "" : existing.city());
        TextField provinceField = new TextField(existing == null ? "" : existing.province());
        TextField postalField = new TextField(existing == null ? "" : existing.postalCode());
        TextField countryField = new TextField(existing == null || existing.country() == null || existing.country().isBlank()
                ? "Espana" : existing.country());
        TextField ibanField = new TextField(existing == null ? "" : existing.iban());
        TextField workTypeField = new TextField(existing == null ? "" : existing.workType());
        ComboBox<String> ssCombo = new ComboBox<>();
        ssCombo.getItems().addAll("", "GENERAL", "RETA", "AUTONOMO_SOCIETARIO", "ARTISTAS", "MAR", "AGRARIO", "OTHER");
        ssCombo.getSelectionModel().select(existing == null || existing.ssRegime() == null ? "" : existing.ssRegime());
        TextField hireField = new TextField(existing == null || existing.hireDate() == null
                ? "" : existing.hireDate().toString());
        hireField.setPromptText("AAAA-MM-DD");
        TextField termField = new TextField(existing == null || existing.terminationDate() == null
                ? "" : existing.terminationDate().toString());
        termField.setPromptText("AAAA-MM-DD");
        TextField termReasonField = new TextField(existing == null ? "" : existing.terminationReason());
        CheckBox geoCb = new CheckBox(t("labor.employee.editor.geolocation"));
        geoCb.setSelected(existing != null && existing.geolocationEnabled());
        CheckBox activeCb = new CheckBox(t("labor.employee.editor.active"));
        activeCb.setSelected(existing == null || existing.active());

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(6); g.setPadding(new Insets(10));
        int row = 0;
        g.add(label(t("labor.employee.section.identity"), "settings-section-title"), 0, row++, 4, 1);
        g.add(new Label(t("labor.employee.editor.name")), 0, row); g.add(nameField, 1, row);
        g.add(new Label(t("labor.employee.editor.nif")), 2, row); g.add(nifField, 3, row); row++;
        g.add(new Label(t("labor.employee.editor.nuss")), 0, row); g.add(nussField, 1, row);
        g.add(new Label(t("labor.employee.editor.birth")), 2, row); g.add(birthField, 3, row); row++;
        g.add(new Label(t("labor.employee.editor.gender")), 0, row); g.add(genderCombo, 1, row);
        g.add(new Label(t("labor.employee.editor.marital")), 2, row); g.add(maritalCombo, 3, row); row++;
        g.add(new Label(t("labor.employee.editor.children")), 0, row); g.add(childrenField, 1, row);
        g.add(new Label(t("labor.employee.editor.disabled")), 2, row); g.add(disabledField, 3, row); row++;

        g.add(new Separator(), 0, row++, 4, 1);
        g.add(label(t("labor.employee.section.contact"), "settings-section-title"), 0, row++, 4, 1);
        g.add(new Label(t("labor.employee.editor.email")), 0, row); g.add(emailField, 1, row);
        g.add(new Label(t("labor.employee.editor.phone")), 2, row); g.add(phoneField, 3, row); row++;
        g.add(new Label(t("labor.employee.editor.address")), 0, row); g.add(addressField, 1, row, 3, 1); row++;
        g.add(new Label(t("labor.employee.editor.city")), 0, row); g.add(cityField, 1, row);
        g.add(new Label(t("labor.employee.editor.province")), 2, row); g.add(provinceField, 3, row); row++;
        g.add(new Label(t("labor.employee.editor.postal")), 0, row); g.add(postalField, 1, row);
        g.add(new Label(t("labor.employee.editor.country")), 2, row); g.add(countryField, 3, row); row++;

        g.add(new Separator(), 0, row++, 4, 1);
        g.add(label(t("labor.employee.section.work"), "settings-section-title"), 0, row++, 4, 1);
        g.add(new Label(t("labor.employee.editor.iban")), 0, row); g.add(ibanField, 1, row, 3, 1); row++;
        g.add(new Label(t("labor.employee.editor.work_type")), 0, row); g.add(workTypeField, 1, row);
        g.add(new Label(t("labor.employee.editor.ss_regime")), 2, row); g.add(ssCombo, 3, row); row++;
        g.add(new Label(t("labor.employee.editor.hire")), 0, row); g.add(hireField, 1, row);
        g.add(new Label(t("labor.employee.editor.termination")), 2, row); g.add(termField, 3, row); row++;
        g.add(new Label(t("labor.employee.editor.term_reason")), 0, row); g.add(termReasonField, 1, row, 3, 1); row++;
        g.add(geoCb, 1, row, 3, 1); row++;
        Label geoHint = new Label(t("labor.employee.editor.geolocation.hint"));
        geoHint.setWrapText(true);
        geoHint.getStyleClass().add("settings-hint");
        g.add(geoHint, 1, row, 3, 1); row++;
        g.add(activeCb, 1, row);

        ScrollPane sp = new ScrollPane(g);
        sp.setFitToWidth(true);
        sp.setPrefViewportHeight(520);
        dialog.getDialogPane().setContent(sp);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            com.benjagest.ui.model.EmployeeEntry payload = new com.benjagest.ui.model.EmployeeEntry(
                    existing == null ? null : existing.id(),
                    nameField.getText().trim(),
                    blankToNullOrSelf(nifField.getText()),
                    blankToNullOrSelf(nussField.getText()),
                    blankToNullOrSelf(emailField.getText()),
                    blankToNullOrSelf(phoneField.getText()),
                    parseDateSafe(birthField.getText()),
                    blankToNullOrSelf(genderCombo.getValue()),
                    blankToNullOrSelf(maritalCombo.getValue()),
                    parseIntSafe(childrenField.getText()),
                    parseIntSafe(disabledField.getText()),
                    blankToNullOrSelf(addressField.getText()),
                    blankToNullOrSelf(cityField.getText()),
                    blankToNullOrSelf(provinceField.getText()),
                    blankToNullOrSelf(postalField.getText()),
                    blankToNullOrSelf(countryField.getText()),
                    blankToNullOrSelf(ibanField.getText()),
                    blankToNullOrSelf(workTypeField.getText()),
                    blankToNullOrSelf(ssCombo.getValue()),
                    parseDateSafe(hireField.getText()),
                    parseDateSafe(termField.getText()),
                    blankToNullOrSelf(termReasonField.getText()),
                    geoCb.isSelected(),
                    activeCb.isSelected());
            Task<com.benjagest.ui.model.EmployeeEntry> task = new Task<>() {
                @Override
                protected com.benjagest.ui.model.EmployeeEntry call() throws Exception {
                    return existing == null
                            ? laborApiClient.createEmployee(payload)
                            : laborApiClient.updateEmployee(existing.id(), payload);
                }
            };
            task.setOnSucceeded(ev -> showLaborModule());
            task.setOnFailed(ev -> showError(t("labor.employee.editor.fail.title"),
                    t("labor.employee.editor.fail.body")));
            start(task, "labor-employee-save");
        });
    }

    private void deleteEmployee(com.benjagest.ui.model.EmployeeEntry e) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("labor.employee.delete.body") + " " + e.fullName(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("labor.employee.delete.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.deleteEmployee(e.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> showLaborModule());
            task.setOnFailed(ev -> showError(t("labor.employee.editor.fail.title"),
                    t("labor.employee.editor.fail.body")));
            start(task, "labor-employee-delete");
        });
    }

    private void showEmployeeContracts(com.benjagest.ui.model.EmployeeEntry e) {
        Task<java.util.List<com.benjagest.ui.model.ContractEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.ContractEntry> call() throws Exception {
                return laborApiClient.listContracts(e.id());
            }
        };
        task.setOnSucceeded(ev -> showContractsDialog(e, task.getValue()));
        task.setOnFailed(ev -> showError(t("labor.contracts.load.fail"), t("labor.contracts.load.fail.body")));
        start(task, "labor-contracts-load");
    }

    private void showContractsDialog(com.benjagest.ui.model.EmployeeEntry employee,
                                      java.util.List<com.benjagest.ui.model.ContractEntry> contracts) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("labor.contracts.dialog.title") + " — " + employee.fullName());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        contractsTable = new TableView<>();
        contractsTable.getStyleClass().add("data-table");
        contractsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        contractsTable.setPrefHeight(280);
        contractsTable.setPlaceholder(new Label(t("labor.contracts.placeholder.empty")));
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colType =
                new TableColumn<>(t("labor.contracts.col.type"));
        colType.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().contractType()));
        colType.setPrefWidth(140);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colSepe =
                new TableColumn<>(t("labor.contracts.col.sepe"));
        colSepe.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sepeContractCode()));
        colSepe.setPrefWidth(70);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colStart =
                new TableColumn<>(t("labor.contracts.col.start"));
        colStart.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().startDate() == null ? "" : c.getValue().startDate().toString()));
        colStart.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colEnd =
                new TableColumn<>(t("labor.contracts.col.end"));
        colEnd.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().endDate() == null ? "" : c.getValue().endDate().toString()));
        colEnd.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colSalary =
                new TableColumn<>(t("labor.contracts.col.salary"));
        colSalary.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().grossSalary() == null ? "" : c.getValue().grossSalary().toPlainString() + " €"));
        colSalary.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.ContractEntry, String> colStatus =
                new TableColumn<>(t("labor.contracts.col.status"));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status()));
        colStatus.setPrefWidth(100);
        contractsTable.getColumns().addAll(java.util.List.of(colType, colSepe, colStart, colEnd, colSalary, colStatus));
        contractsTable.setItems(FXCollections.observableArrayList(contracts));

        Button newC = new Button(t("labor.contracts.action.new"));
        newC.setGraphic(icon("fas-plus"));
        newC.setOnAction(ev -> {
            showContractEditor(employee, null);
            dialog.close();
        });
        Button editC = new Button(t("labor.contracts.action.edit"));
        editC.setGraphic(icon("fas-edit"));
        editC.setDisable(true);
        editC.setOnAction(ev -> {
            var sel = contractsTable.getSelectionModel().getSelectedItem();
            if (sel != null) {
                showContractEditor(employee, sel);
                dialog.close();
            }
        });
        contractsTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> editC.setDisable(nv == null));
        HBox actions = new HBox(8, newC, editC);
        VBox body = new VBox(12, contractsTable, actions);
        body.setPadding(new Insets(10));
        installDialog(dialog, body);
        dialog.setResizable(true);
        dialog.showAndWait();
    }

    private void showContractEditor(com.benjagest.ui.model.EmployeeEntry employee,
                                     com.benjagest.ui.model.ContractEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle((existing == null ? t("labor.contract.editor.title_new")
                : t("labor.contract.editor.title_edit")) + " — " + employee.fullName());
        ButtonType saveBt = new ButtonType(t("labor.contract.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField typeField = new TextField(existing == null ? "Indefinido" : existing.contractType());
        TextField sepeField = new TextField(existing == null ? "100" : existing.sepeContractCode());
        TextField agreementField = new TextField(existing == null ? "" : existing.collectiveAgreement());
        TextField catField = new TextField(existing == null ? "" : existing.professionalCategory());
        TextField groupField = new TextField(existing == null ? "" : existing.professionalGroup());
        TextField startField = new TextField(existing == null || existing.startDate() == null
                ? LocalDate.now().toString() : existing.startDate().toString());
        TextField endField = new TextField(existing == null || existing.endDate() == null
                ? "" : existing.endDate().toString());
        TextField hoursField = new TextField(existing == null || existing.weeklyHours() == null
                ? "40" : existing.weeklyHours().toPlainString());
        TextField salaryField = new TextField(existing == null || existing.grossSalary() == null
                ? "" : existing.grossSalary().toPlainString());
        TextField bonusesField = new TextField(existing == null || existing.annualBonuses() == null
                ? "2" : existing.annualBonuses().toString());
        TextField vacationField = new TextField(existing == null || existing.vacationDays() == null
                ? "30" : existing.vacationDays().toString());
        TextField irpfField = new TextField(existing == null || existing.irpfPercent() == null
                ? "" : existing.irpfPercent().toPlainString());
        TextField workplaceField = new TextField(existing == null ? "" : existing.workplaceAddress());
        ComboBox<String> statusCombo = new ComboBox<>();
        statusCombo.getItems().addAll("DRAFT", "ACTIVE", "SUSPENDED", "TERMINATED");
        statusCombo.getSelectionModel().select(existing == null ? "ACTIVE" : existing.status());

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(10));
        int row = 0;
        g.add(new Label(t("labor.contract.editor.type")), 0, row); g.add(typeField, 1, row);
        g.add(new Label(t("labor.contract.editor.sepe")), 2, row); g.add(sepeField, 3, row); row++;
        g.add(new Label(t("labor.contract.editor.agreement")), 0, row); g.add(agreementField, 1, row, 3, 1); row++;
        g.add(new Label(t("labor.contract.editor.category")), 0, row); g.add(catField, 1, row);
        g.add(new Label(t("labor.contract.editor.group")), 2, row); g.add(groupField, 3, row); row++;
        g.add(new Label(t("labor.contract.editor.start")), 0, row); g.add(startField, 1, row);
        g.add(new Label(t("labor.contract.editor.end")), 2, row); g.add(endField, 3, row); row++;
        g.add(new Label(t("labor.contract.editor.weekly_hours")), 0, row); g.add(hoursField, 1, row);
        g.add(new Label(t("labor.contract.editor.salary")), 2, row); g.add(salaryField, 3, row); row++;
        g.add(new Label(t("labor.contract.editor.bonuses")), 0, row); g.add(bonusesField, 1, row);
        g.add(new Label(t("labor.contract.editor.vacation")), 2, row); g.add(vacationField, 3, row); row++;
        g.add(new Label(t("labor.contract.editor.irpf")), 0, row); g.add(irpfField, 1, row);
        g.add(new Label(t("labor.contract.editor.status")), 2, row); g.add(statusCombo, 3, row); row++;
        g.add(new Label(t("labor.contract.editor.workplace")), 0, row); g.add(workplaceField, 1, row, 3, 1);

        installDialog(dialog, g);
        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            com.benjagest.ui.model.ContractEntry payload = new com.benjagest.ui.model.ContractEntry(
                    existing == null ? null : existing.id(),
                    employee.id(),
                    typeField.getText().trim(),
                    blankToNullOrSelf(sepeField.getText()),
                    blankToNullOrSelf(agreementField.getText()),
                    blankToNullOrSelf(catField.getText()),
                    blankToNullOrSelf(groupField.getText()),
                    parseDateSafe(startField.getText()),
                    parseDateSafe(endField.getText()),
                    parseDecSafe(hoursField.getText()),
                    parseDecSafe(salaryField.getText()),
                    parseIntSafe(bonusesField.getText()),
                    parseIntSafe(vacationField.getText()),
                    parseDecSafe(irpfField.getText()),
                    blankToNullOrSelf(workplaceField.getText()),
                    statusCombo.getValue(),
                    null);
            Task<com.benjagest.ui.model.ContractEntry> task = new Task<>() {
                @Override protected com.benjagest.ui.model.ContractEntry call() throws Exception {
                    return existing == null
                            ? laborApiClient.createContract(payload)
                            : laborApiClient.updateContract(existing.id(), payload);
                }
            };
            task.setOnSucceeded(ev -> showEmployeeContracts(employee));
            task.setOnFailed(ev -> showError(t("labor.contract.editor.fail.title"),
                    t("labor.contract.editor.fail.body")));
            start(task, "labor-contract-save");
        });
    }

    // ===================================================================
    //  L2 — Modulo RETA: perfiles + cambios de base
    // ===================================================================

    private TableView<com.benjagest.ui.model.RetaProfileEntry> retaTable;

    private void showRetaModule() {
        Task<java.util.List<com.benjagest.ui.model.RetaProfileEntry>> task = new Task<>() {
            @Override protected java.util.List<com.benjagest.ui.model.RetaProfileEntry> call() throws Exception {
                return laborApiClient.listRetaProfiles(true);
            }
        };
        task.setOnSucceeded(ev -> setCenterAnimated(scroll(retaView(task.getValue()))));
        task.setOnFailed(ev -> setCenterAnimated(scroll(errorPanel(t("reta.load_failed")))));
        start(task, "reta-load");
    }

    private VBox retaView(java.util.List<com.benjagest.ui.model.RetaProfileEntry> profiles) {
        VBox content = content();
        Label title = new Label(t("reta.title"));
        title.getStyleClass().add("module-detail-title");
        Label subtitle = new Label(t("reta.subtitle"));
        subtitle.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, subtitle);
        StackPane moduleIcon = iconBubble("fas-user-tie", "module-title-icon");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button newBtn = new Button(t("reta.action.new"));
        newBtn.setGraphic(icon("fas-plus"));
        newBtn.setOnAction(ev -> showRetaEditor(null));
        Button tramoBtn = new Button(t("reta.action.suggest_tramo"));
        tramoBtn.setGraphic(icon("fas-calculator"));
        tramoBtn.setOnAction(ev -> showRetaTramoSuggester());
        HBox header = new HBox(16, titleBox, moduleIcon, spacer, tramoBtn, newBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        retaTable = new TableView<>();
        retaTable.getStyleClass().add("data-table");
        retaTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        retaTable.setPlaceholder(new Label(t("reta.placeholder.empty")));
        TableColumn<com.benjagest.ui.model.RetaProfileEntry, String> colName =
                new TableColumn<>(t("reta.col.name"));
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().fullName()));
        TableColumn<com.benjagest.ui.model.RetaProfileEntry, String> colNif =
                new TableColumn<>(t("reta.col.nif"));
        colNif.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().taxIdentifier()));
        colNif.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.RetaProfileEntry, String> colBase =
                new TableColumn<>(t("reta.col.base"));
        colBase.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().currentBase() == null ? "" : c.getValue().currentBase().toPlainString() + " €"));
        colBase.setPrefWidth(110);
        colBase.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.RetaProfileEntry, String> colQuota =
                new TableColumn<>(t("reta.col.quota"));
        colQuota.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().currentQuota() == null ? "" : c.getValue().currentQuota().toPlainString() + " €"));
        colQuota.setPrefWidth(110);
        colQuota.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.RetaProfileEntry, String> colFlags =
                new TableColumn<>(t("reta.col.flags"));
        colFlags.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().tarifaPlana() ? "★ " : "")
                + (c.getValue().pluriactividad() ? "‡ " : "")
                + (c.getValue().active() ? "" : t("reta.inactive"))));
        colFlags.setPrefWidth(80);
        retaTable.getColumns().addAll(java.util.List.of(colName, colNif, colBase, colQuota, colFlags));
        retaTable.setItems(FXCollections.observableArrayList(profiles));
        retaTable.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                var sel = retaTable.getSelectionModel().getSelectedItem();
                if (sel != null) showRetaEditor(sel);
            }
        });

        Button editBtn = new Button(t("reta.action.edit"));
        editBtn.setGraphic(icon("fas-edit"));
        editBtn.setDisable(true);
        editBtn.setOnAction(ev -> {
            var sel = retaTable.getSelectionModel().getSelectedItem();
            if (sel != null) showRetaEditor(sel);
        });
        Button changesBtn = new Button(t("reta.action.changes"));
        changesBtn.setGraphic(icon("fas-history"));
        changesBtn.setDisable(true);
        changesBtn.setOnAction(ev -> {
            var sel = retaTable.getSelectionModel().getSelectedItem();
            if (sel != null) showRetaChanges(sel);
        });
        Button delBtn = new Button(t("reta.action.delete"));
        delBtn.setGraphic(icon("fas-user-slash"));
        delBtn.setDisable(true);
        delBtn.setOnAction(ev -> {
            var sel = retaTable.getSelectionModel().getSelectedItem();
            if (sel != null) deleteRetaProfile(sel);
        });
        retaTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            editBtn.setDisable(nv == null);
            changesBtn.setDisable(nv == null);
            delBtn.setDisable(nv == null || !nv.active());
        });
        HBox actions = new HBox(8, editBtn, changesBtn, delBtn);
        actions.getStyleClass().add("settings-actions");

        VBox body = new VBox(12, retaTable);
        VBox.setVgrow(retaTable, Priority.ALWAYS);
        content.getChildren().addAll(header, body, actions);
        return content;
    }

    private void showRetaEditor(com.benjagest.ui.model.RetaProfileEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? t("reta.editor.title_new") : t("reta.editor.title_edit"));
        ButtonType saveBt = new ButtonType(t("reta.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField nameField = new TextField(existing == null ? "" : existing.fullName());
        TextField nifField = new TextField(existing == null ? "" : existing.taxIdentifier());
        TextField nussField = new TextField(existing == null ? "" : existing.socialSecurityNumber());
        TextField startField = new TextField(existing == null || existing.retaStartDate() == null ? "" : existing.retaStartDate().toString());
        startField.setPromptText("AAAA-MM-DD");
        TextField endField = new TextField(existing == null || existing.retaEndDate() == null ? "" : existing.retaEndDate().toString());
        endField.setPromptText("AAAA-MM-DD");
        CheckBox pluri = new CheckBox(t("reta.editor.pluriactividad"));
        pluri.setSelected(existing != null && existing.pluriactividad());
        CheckBox tarifa = new CheckBox(t("reta.editor.tarifa_plana"));
        tarifa.setSelected(existing != null && existing.tarifaPlana());
        TextField tarifaUntil = new TextField(existing == null || existing.tarifaPlanaUntil() == null ? "" : existing.tarifaPlanaUntil().toString());
        tarifaUntil.setPromptText("AAAA-MM-DD");
        TextField actCode = new TextField(existing == null ? "" : existing.activityCode());
        TextField actDesc = new TextField(existing == null ? "" : existing.activityDescription());
        TextField iae = new TextField(existing == null ? "" : existing.iaeEpigraph());
        TextField netIncome = new TextField(existing == null || existing.expectedNetIncome() == null
                ? "" : existing.expectedNetIncome().toPlainString());
        TextField base = new TextField(existing == null || existing.currentBase() == null
                ? "" : existing.currentBase().toPlainString());
        TextField quota = new TextField(existing == null || existing.currentQuota() == null
                ? "" : existing.currentQuota().toPlainString());
        TextArea notes = new TextArea(existing == null ? "" : existing.notes());
        notes.setPrefRowCount(2);
        CheckBox active = new CheckBox(t("reta.editor.active"));
        active.setSelected(existing == null || existing.active());

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(6); g.setPadding(new Insets(10));
        int row = 0;
        g.add(new Label(t("reta.editor.name")), 0, row); g.add(nameField, 1, row, 3, 1); row++;
        g.add(new Label(t("reta.editor.nif")), 0, row); g.add(nifField, 1, row);
        g.add(new Label(t("reta.editor.nuss")), 2, row); g.add(nussField, 3, row); row++;
        g.add(new Label(t("reta.editor.start")), 0, row); g.add(startField, 1, row);
        g.add(new Label(t("reta.editor.end")), 2, row); g.add(endField, 3, row); row++;
        g.add(pluri, 1, row); g.add(tarifa, 3, row); row++;
        g.add(new Label(t("reta.editor.tarifa_until")), 0, row); g.add(tarifaUntil, 1, row, 3, 1); row++;
        g.add(new Separator(), 0, row++, 4, 1);
        g.add(new Label(t("reta.editor.activity_code")), 0, row); g.add(actCode, 1, row);
        g.add(new Label(t("reta.editor.iae")), 2, row); g.add(iae, 3, row); row++;
        g.add(new Label(t("reta.editor.activity_desc")), 0, row); g.add(actDesc, 1, row, 3, 1); row++;
        g.add(new Separator(), 0, row++, 4, 1);
        g.add(new Label(t("reta.editor.net_income")), 0, row); g.add(netIncome, 1, row);
        g.add(new Label(t("reta.editor.base")), 2, row); g.add(base, 3, row); row++;
        g.add(new Label(t("reta.editor.quota")), 0, row); g.add(quota, 1, row);
        g.add(active, 3, row); row++;
        g.add(new Label(t("reta.editor.notes")), 0, row); g.add(notes, 1, row, 3, 1);

        ScrollPane sp = new ScrollPane(g);
        sp.setFitToWidth(true);
        sp.setPrefViewportHeight(520);
        dialog.getDialogPane().setContent(sp);
        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            com.benjagest.ui.model.RetaProfileEntry payload = new com.benjagest.ui.model.RetaProfileEntry(
                    existing == null ? null : existing.id(),
                    null, null,
                    nameField.getText().trim(),
                    blankToNullOrSelf(nifField.getText()),
                    blankToNullOrSelf(nussField.getText()),
                    parseDateSafe(startField.getText()),
                    parseDateSafe(endField.getText()),
                    pluri.isSelected(), tarifa.isSelected(),
                    parseDateSafe(tarifaUntil.getText()),
                    blankToNullOrSelf(actCode.getText()),
                    blankToNullOrSelf(actDesc.getText()),
                    blankToNullOrSelf(iae.getText()),
                    parseDecSafe(netIncome.getText()),
                    parseDecSafe(base.getText()),
                    parseDecSafe(quota.getText()),
                    blankToNullOrSelf(notes.getText()),
                    active.isSelected()
            );
            Task<com.benjagest.ui.model.RetaProfileEntry> task = new Task<>() {
                @Override protected com.benjagest.ui.model.RetaProfileEntry call() throws Exception {
                    return existing == null
                            ? laborApiClient.createRetaProfile(payload)
                            : laborApiClient.updateRetaProfile(existing.id(), payload);
                }
            };
            task.setOnSucceeded(ev -> showRetaModule());
            task.setOnFailed(ev -> showError(t("reta.editor.fail.title"), t("reta.editor.fail.body")));
            start(task, "reta-save");
        });
    }

    private void deleteRetaProfile(com.benjagest.ui.model.RetaProfileEntry p) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("reta.delete.body") + " " + p.fullName(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("reta.delete.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    laborApiClient.deleteRetaProfile(p.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> showRetaModule());
            task.setOnFailed(ev -> showError(t("reta.editor.fail.title"), t("reta.editor.fail.body")));
            start(task, "reta-delete");
        });
    }

    private void showRetaChanges(com.benjagest.ui.model.RetaProfileEntry profile) {
        int year = LocalDate.now().getYear();
        Task<java.util.List<com.benjagest.ui.model.RetaBaseChangeEntry>> task = new Task<>() {
            @Override protected java.util.List<com.benjagest.ui.model.RetaBaseChangeEntry> call() throws Exception {
                return laborApiClient.listRetaChanges(profile.id(), year);
            }
        };
        task.setOnSucceeded(ev -> {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle(t("reta.changes.title") + " — " + profile.fullName() + " (" + year + ")");
            ButtonType newBt = new ButtonType(t("reta.changes.new"), ButtonBar.ButtonData.LEFT);
            dialog.getDialogPane().getButtonTypes().addAll(newBt, ButtonType.CLOSE);

            TableView<com.benjagest.ui.model.RetaBaseChangeEntry> tbl = new TableView<>();
            tbl.getStyleClass().add("data-table");
            tbl.setPrefHeight(260);
            tbl.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            tbl.setPlaceholder(new Label(t("reta.changes.placeholder.empty")));
            TableColumn<com.benjagest.ui.model.RetaBaseChangeEntry, String> cDate =
                    new TableColumn<>(t("reta.changes.col.date"));
            cDate.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().effectiveDate() == null ? "" : c.getValue().effectiveDate().toString()));
            cDate.setPrefWidth(110);
            TableColumn<com.benjagest.ui.model.RetaBaseChangeEntry, String> cBase =
                    new TableColumn<>(t("reta.changes.col.base"));
            cBase.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().newBase() == null ? "" : c.getValue().newBase().toPlainString() + " €"));
            cBase.setPrefWidth(110);
            TableColumn<com.benjagest.ui.model.RetaBaseChangeEntry, String> cQuota =
                    new TableColumn<>(t("reta.changes.col.quota"));
            cQuota.setCellValueFactory(c -> new SimpleStringProperty(
                    c.getValue().newQuota() == null ? "" : c.getValue().newQuota().toPlainString() + " €"));
            cQuota.setPrefWidth(110);
            TableColumn<com.benjagest.ui.model.RetaBaseChangeEntry, String> cReason =
                    new TableColumn<>(t("reta.changes.col.reason"));
            cReason.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().changeReason()));
            TableColumn<com.benjagest.ui.model.RetaBaseChangeEntry, String> cSent =
                    new TableColumn<>(t("reta.changes.col.sent"));
            cSent.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().submittedToSs() ? "✓" : ""));
            cSent.setPrefWidth(60);
            tbl.getColumns().addAll(java.util.List.of(cDate, cBase, cQuota, cReason, cSent));
            tbl.setItems(FXCollections.observableArrayList(task.getValue()));

            VBox body = new VBox(8, new Label(t("reta.changes.hint")), tbl);
            body.setPadding(new Insets(10));
            installDialog(dialog, body);

            // Interceptamos el boton de la izquierda para abrir el sub-editor
            Button newButton = (Button) dialog.getDialogPane().lookupButton(newBt);
            newButton.addEventFilter(javafx.event.ActionEvent.ACTION, btnEv -> {
                btnEv.consume();
                showRetaChangeEditor(profile);
                dialog.close();
            });
            dialog.setResizable(true);
            dialog.showAndWait();
        });
        task.setOnFailed(ev -> showError(t("reta.changes.load.fail"), t("reta.changes.load.fail.body")));
        start(task, "reta-changes-load");
    }

    private void showRetaChangeEditor(com.benjagest.ui.model.RetaProfileEntry profile) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("reta.change.editor.title") + " — " + profile.fullName());
        ButtonType saveBt = new ButtonType(t("reta.change.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField effective = new TextField(LocalDate.now().toString());
        effective.setPromptText("AAAA-MM-DD");
        TextField reason = new TextField();
        reason.setPromptText(t("reta.change.editor.reason.prompt"));
        TextField base = new TextField(profile.currentBase() == null ? "" : profile.currentBase().toPlainString());
        TextField quota = new TextField(profile.currentQuota() == null ? "" : profile.currentQuota().toPlainString());
        TextField netIncome = new TextField(profile.expectedNetIncome() == null
                ? "" : profile.expectedNetIncome().toPlainString());
        CheckBox sent = new CheckBox(t("reta.change.editor.submitted"));
        TextArea notes = new TextArea(); notes.setPrefRowCount(2);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(10));
        g.add(new Label(t("reta.change.editor.effective")), 0, 0); g.add(effective, 1, 0);
        g.add(new Label(t("reta.change.editor.reason")), 0, 1); g.add(reason, 1, 1);
        g.add(new Label(t("reta.change.editor.new_base")), 0, 2); g.add(base, 1, 2);
        g.add(new Label(t("reta.change.editor.new_quota")), 0, 3); g.add(quota, 1, 3);
        g.add(new Label(t("reta.change.editor.net_income")), 0, 4); g.add(netIncome, 1, 4);
        g.add(sent, 1, 5);
        g.add(new Label(t("reta.change.editor.notes")), 0, 6); g.add(notes, 1, 6);
        installDialog(dialog, g);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            com.benjagest.ui.model.RetaBaseChangeEntry payload = new com.benjagest.ui.model.RetaBaseChangeEntry(
                    null, profile.id(),
                    parseDateSafe(effective.getText()),
                    blankToNullOrSelf(reason.getText()),
                    parseDecSafe(base.getText()),
                    parseDecSafe(quota.getText()),
                    parseDecSafe(netIncome.getText()),
                    sent.isSelected(),
                    blankToNullOrSelf(notes.getText())
            );
            Task<com.benjagest.ui.model.RetaBaseChangeEntry> task = new Task<>() {
                @Override protected com.benjagest.ui.model.RetaBaseChangeEntry call() throws Exception {
                    return laborApiClient.createRetaChange(profile.id(), payload);
                }
            };
            task.setOnSucceeded(ev -> showRetaModule());
            task.setOnFailed(ev -> showError(t("reta.change.editor.fail.title"),
                    t("reta.change.editor.fail.body")));
            start(task, "reta-change-save");
        });
    }

    private void showRetaTramoSuggester() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("reta.tramo.title"));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        TextField netField = new TextField();
        netField.setPromptText(t("reta.tramo.net.prompt"));
        Button calc = new Button(t("reta.tramo.calc"));
        Label result = new Label();
        result.setWrapText(true);
        result.getStyleClass().add("settings-section-title");

        calc.setOnAction(ev -> {
            java.math.BigDecimal annual;
            try {
                annual = new java.math.BigDecimal(netField.getText().trim().replace(",", "."));
            } catch (NumberFormatException ex) {
                result.setText(t("reta.tramo.invalid"));
                return;
            }
            Task<com.benjagest.ui.model.RetaTramoSuggestion> task = new Task<>() {
                @Override protected com.benjagest.ui.model.RetaTramoSuggestion call() throws Exception {
                    return laborApiClient.suggestRetaTramo(annual);
                }
            };
            task.setOnSucceeded(e -> {
                var s = task.getValue();
                result.setText(String.format(
                        "%s%n%s: %s – %s €%n%s: %s €%n%s: %s €/mes",
                        s.tramoLabel(),
                        t("reta.tramo.result.base_range"), s.baseMinima(), s.baseMaxima(),
                        t("reta.tramo.result.quota"), s.cuotaMinima(),
                        t("reta.tramo.result.monthly_income"), s.monthlyIncome()));
            });
            task.setOnFailed(e -> result.setText(t("reta.tramo.fail")));
            start(task, "reta-tramo-suggest");
        });

        VBox body = new VBox(10,
                new Label(t("reta.tramo.hint")),
                new HBox(8, new Label(t("reta.tramo.net.label")), netField, calc),
                new Separator(),
                result);
        body.setPadding(new Insets(10));
        body.setPrefWidth(500);
        installDialog(dialog, body);
        dialog.showAndWait();
    }

    // ===================================================================
    //  N1 — Modulo DEHu: bandeja de notificaciones
    // ===================================================================

    private TableView<com.benjagest.ui.model.DehuNotificationEntry> dehuTable;

    private void showDehuModule() {
        currentModule = "dehu";
        Task<DehuBundle> task = new Task<>() {
            @Override protected DehuBundle call() throws Exception {
                return new DehuBundle(
                        laborApiClient.listDehu(null, 200),
                        laborApiClient.dehuSummary());
            }
        };
        task.setOnSucceeded(ev -> setCenterAnimated(scroll(dehuView(task.getValue()))));
        task.setOnFailed(ev -> setCenterAnimated(scroll(errorPanel(t("dehu.load_failed")))));
        start(task, "dehu-load");
        startDehuPolling();
    }

    /**
     * Arranca el polling de la bandeja DEHú la primera vez que el
     * usuario entra al módulo. El Timeline persiste durante la
     * sesión; cada tick comprueba {@code currentModule} y solo
     * recarga si seguimos viendo DEHú (evita peticiones inútiles
     * cuando el usuario navega a otra pantalla).
     */
    private void startDehuPolling() {
        if (dehuPoller != null) return;
        // 15s — las notificaciones DEHú no son tan urgentes como una
        // invitación de asesoría, pero sí deben aparecer sin tener
        // que refrescar a mano.
        dehuPoller = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(
                        javafx.util.Duration.seconds(15),
                        ev -> pollDehu()));
        dehuPoller.setCycleCount(javafx.animation.Animation.INDEFINITE);
        dehuPoller.play();
    }

    private void stopDehuPolling() {
        if (dehuPoller != null) {
            dehuPoller.stop();
            dehuPoller = null;
        }
    }

    /**
     * Tick del poller DEHú. Solo refresca si la pantalla activa es
     * DEHú; en caso contrario, no hace petición. Silencioso (sin
     * animación) para no marear con parpadeo cada 15s.
     */
    private void pollDehu() {
        if (!"dehu".equals(currentModule)) return;
        if (!com.benjagest.ui.service.AuthSession.get().isAuthenticated()) return;
        Task<DehuBundle> task = new Task<>() {
            @Override protected DehuBundle call() throws Exception {
                return new DehuBundle(
                        laborApiClient.listDehu(null, 200),
                        laborApiClient.dehuSummary());
            }
        };
        task.setOnSucceeded(ev -> {
            // Reemplazo silencioso del contenido — no usamos
            // setCenterAnimated para evitar la animación cada 15s.
            if ("dehu".equals(currentModule)) {
                setCenterSilent(scroll(dehuView(task.getValue())));
            }
        });
        task.setOnFailed(ev -> { /* silencio: el siguiente tick lo intenta otra vez */ });
        start(task, "dehu-poll");
    }

    private record DehuBundle(
            java.util.List<com.benjagest.ui.model.DehuNotificationEntry> notifications,
            com.benjagest.ui.model.DehuSummary summary
    ) {}

    private VBox dehuView(DehuBundle bundle) {
        VBox content = content();
        Label title = new Label(t("dehu.title"));
        title.getStyleClass().add("module-detail-title");
        Label subtitle = new Label(t("dehu.subtitle"));
        subtitle.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, subtitle);
        StackPane moduleIcon = iconBubble("fas-bell", "module-title-icon");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button newBtn = new Button(t("dehu.action.new"));
        newBtn.setGraphic(icon("fas-plus"));
        newBtn.setOnAction(ev -> showDehuEditor());

        HBox header = new HBox(16, titleBox, moduleIcon, spacer, newBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        // Resumen: 3 chips coloreados según pendientes / a caducar / caducadas
        var s = bundle.summary();
        Label chipPending = new Label(t("dehu.summary.pending") + ": " + s.pending());
        chipPending.getStyleClass().add(s.pending() > 0 ? "settings-section-title" : "settings-hint");
        Label chipSoon = new Label(t("dehu.summary.soon") + ": " + s.expiringSoon());
        chipSoon.getStyleClass().add(s.expiringSoon() > 0 ? "settings-section-title" : "settings-hint");
        Label chipExpired = new Label(t("dehu.summary.expired") + ": " + s.expired());
        chipExpired.getStyleClass().add("settings-hint");
        HBox summaryRow = new HBox(20, chipPending, chipSoon, chipExpired);
        summaryRow.setPadding(new Insets(8));

        dehuTable = new TableView<>();
        dehuTable.getStyleClass().add("data-table");
        dehuTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        dehuTable.setPlaceholder(new Label(t("dehu.placeholder.empty")));
        TableColumn<com.benjagest.ui.model.DehuNotificationEntry, String> cIssued =
                new TableColumn<>(t("dehu.col.issued"));
        cIssued.setCellValueFactory(c -> new SimpleStringProperty(shortIso(c.getValue().issuedAt())));
        cIssued.setPrefWidth(150);
        cIssued.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.DehuNotificationEntry, String> cExpires =
                new TableColumn<>(t("dehu.col.expires"));
        cExpires.setCellValueFactory(c -> new SimpleStringProperty(shortIso(c.getValue().expiresAt())));
        cExpires.setPrefWidth(150);
        cExpires.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.DehuNotificationEntry, String> cOrgan =
                new TableColumn<>(t("dehu.col.organism"));
        cOrgan.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().organismName()));
        cOrgan.setPrefWidth(200);
        TableColumn<com.benjagest.ui.model.DehuNotificationEntry, String> cSubject =
                new TableColumn<>(t("dehu.col.subject"));
        cSubject.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().subject()));
        TableColumn<com.benjagest.ui.model.DehuNotificationEntry, String> cStatus =
                new TableColumn<>(t("dehu.col.status"));
        cStatus.setCellValueFactory(c -> new SimpleStringProperty(t("dehu.status." + c.getValue().status())));
        cStatus.setPrefWidth(110);
        dehuTable.getColumns().addAll(java.util.List.of(cIssued, cExpires, cOrgan, cSubject, cStatus));
        dehuTable.setItems(FXCollections.observableArrayList(bundle.notifications()));

        Button readBtn = new Button(t("dehu.action.read"));
        readBtn.setGraphic(icon("fas-envelope-open"));
        readBtn.setDisable(true);
        readBtn.setOnAction(ev -> {
            var sel = dehuTable.getSelectionModel().getSelectedItem();
            if (sel != null) markDehuRead(sel);
        });
        Button dismissBtn = new Button(t("dehu.action.dismiss"));
        dismissBtn.setGraphic(icon("fas-archive"));
        dismissBtn.setDisable(true);
        dismissBtn.setOnAction(ev -> {
            var sel = dehuTable.getSelectionModel().getSelectedItem();
            if (sel != null) dismissDehu(sel);
        });
        dehuTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            readBtn.setDisable(nv == null || "READ".equals(nv == null ? "" : nv.status()));
            dismissBtn.setDisable(nv == null || "DISMISSED".equals(nv == null ? "" : nv.status()));
        });
        HBox actions = new HBox(8, readBtn, dismissBtn);
        actions.getStyleClass().add("settings-actions");

        VBox body = new VBox(12, summaryRow, dehuTable);
        VBox.setVgrow(dehuTable, Priority.ALWAYS);
        content.getChildren().addAll(header, body, actions);
        return content;
    }

    private void showDehuEditor() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("dehu.editor.title"));
        ButtonType saveBt = new ButtonType(t("dehu.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField dehuIdField = new TextField();
        TextField nifField = new TextField();
        TextField organField = new TextField();
        TextField organCodeField = new TextField();
        TextField procField = new TextField();
        TextField procCodeField = new TextField();
        TextField subjectField = new TextField();
        TextField issuedField = new TextField(LocalDate.now().toString() + "T00:00:00Z");
        TextField expiresField = new TextField(LocalDate.now().plusDays(10).toString() + "T00:00:00Z");
        TextField csvField = new TextField();
        TextField urlField = new TextField();
        TextArea notes = new TextArea(); notes.setPrefRowCount(2);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(6); g.setPadding(new Insets(10));
        int row = 0;
        g.add(new Label(t("dehu.editor.dehu_id")), 0, row); g.add(dehuIdField, 1, row, 3, 1); row++;
        g.add(new Label(t("dehu.editor.nif")), 0, row); g.add(nifField, 1, row);
        g.add(new Label(t("dehu.editor.subject")), 2, row); g.add(subjectField, 3, row); row++;
        g.add(new Label(t("dehu.editor.organism")), 0, row); g.add(organField, 1, row);
        g.add(new Label(t("dehu.editor.organism_code")), 2, row); g.add(organCodeField, 3, row); row++;
        g.add(new Label(t("dehu.editor.procedure")), 0, row); g.add(procField, 1, row);
        g.add(new Label(t("dehu.editor.procedure_code")), 2, row); g.add(procCodeField, 3, row); row++;
        g.add(new Label(t("dehu.editor.issued")), 0, row); g.add(issuedField, 1, row);
        g.add(new Label(t("dehu.editor.expires")), 2, row); g.add(expiresField, 3, row); row++;
        g.add(new Label(t("dehu.editor.csv")), 0, row); g.add(csvField, 1, row);
        g.add(new Label(t("dehu.editor.url")), 2, row); g.add(urlField, 3, row); row++;
        g.add(new Label(t("dehu.editor.notes")), 0, row); g.add(notes, 1, row, 3, 1);

        installDialog(dialog, g);
        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            com.benjagest.ui.model.DehuNotificationEntry payload = new com.benjagest.ui.model.DehuNotificationEntry(
                    null,
                    blankToNullOrSelf(dehuIdField.getText()),
                    blankToNullOrSelf(nifField.getText()),
                    organField.getText().trim(),
                    blankToNullOrSelf(organCodeField.getText()),
                    blankToNullOrSelf(procField.getText()),
                    blankToNullOrSelf(procCodeField.getText()),
                    subjectField.getText().trim(),
                    blankToNullOrSelf(issuedField.getText()),
                    blankToNullOrSelf(expiresField.getText()),
                    null, null, "PENDING",
                    blankToNullOrSelf(csvField.getText()),
                    blankToNullOrSelf(urlField.getText()),
                    null,
                    blankToNullOrSelf(notes.getText())
            );
            Task<com.benjagest.ui.model.DehuNotificationEntry> task = new Task<>() {
                @Override protected com.benjagest.ui.model.DehuNotificationEntry call() throws Exception {
                    return laborApiClient.createDehu(payload);
                }
            };
            task.setOnSucceeded(ev -> showDehuModule());
            task.setOnFailed(ev -> showError(t("dehu.editor.fail.title"), t("dehu.editor.fail.body")));
            start(task, "dehu-create");
        });
    }

    private void markDehuRead(com.benjagest.ui.model.DehuNotificationEntry n) {
        Task<com.benjagest.ui.model.DehuNotificationEntry> task = new Task<>() {
            @Override protected com.benjagest.ui.model.DehuNotificationEntry call() throws Exception {
                return laborApiClient.markDehuRead(n.id());
            }
        };
        task.setOnSucceeded(ev -> showDehuModule());
        task.setOnFailed(ev -> showError(t("dehu.editor.fail.title"), t("dehu.editor.fail.body")));
        start(task, "dehu-read");
    }

    private void dismissDehu(com.benjagest.ui.model.DehuNotificationEntry n) {
        Task<com.benjagest.ui.model.DehuNotificationEntry> task = new Task<>() {
            @Override protected com.benjagest.ui.model.DehuNotificationEntry call() throws Exception {
                return laborApiClient.dismissDehu(n.id());
            }
        };
        task.setOnSucceeded(ev -> showDehuModule());
        task.setOnFailed(ev -> showError(t("dehu.editor.fail.title"), t("dehu.editor.fail.body")));
        start(task, "dehu-dismiss");
    }

    // ===================================================================
    //  Helpers comunes para L1/L2/N1
    // ===================================================================

    private LocalDate parseDateSafe(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.trim()); }
        catch (Exception ex) { return null; }
    }

    private java.math.BigDecimal parseDecSafe(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new java.math.BigDecimal(s.trim().replace(",", ".")); }
        catch (NumberFormatException ex) { return null; }
    }

    private Integer parseIntSafe(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException ex) { return null; }
    }

    // ===================================================================
    //  ALTA-3 — Asesoria: clientes gestionados (X-Company-Id switcher)
    // ===================================================================

    private void showAdvisoryClients() {
        Task<java.util.List<com.benjagest.ui.model.CustomerPortfolioEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.CustomerPortfolioEntry> call() throws Exception {
                return altaApiClient.listAdvisoryPortfolio();
            }
        };
        task.setOnSucceeded(ev -> setCenterAnimated(scroll(advisoryPortfolioView(task.getValue()))));
        task.setOnFailed(ev -> setCenterAnimated(scroll(errorPanel(t("advisory.load_failed")))));
        start(task, "advisory-clients-load");
    }

    private TableView<com.benjagest.ui.model.CustomerPortfolioEntry> advisoryPortfolioTable;

    private VBox advisoryPortfolioView(java.util.List<com.benjagest.ui.model.CustomerPortfolioEntry> portfolio) {
        VBox content = content();
        Label title = new Label(t("advisory.title"));
        title.getStyleClass().add("module-detail-title");
        Label subtitle = new Label(t("advisory.portfolio.subtitle"));
        subtitle.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, subtitle);
        StackPane moduleIcon = iconBubble("fas-briefcase", "module-title-icon");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(16, titleBox, moduleIcon, spacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        advisoryPortfolioTable = new TableView<>();
        advisoryPortfolioTable.getStyleClass().add("data-table");
        advisoryPortfolioTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        advisoryPortfolioTable.setPlaceholder(new Label(t("advisory.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.CustomerPortfolioEntry, String> colName =
                new TableColumn<>(t("advisory.col.legal_name"));
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().legalName()));
        TableColumn<com.benjagest.ui.model.CustomerPortfolioEntry, String> colNif =
                new TableColumn<>(t("advisory.col.nif"));
        colNif.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().taxIdentifier()));
        colNif.setPrefWidth(120);
        TableColumn<com.benjagest.ui.model.CustomerPortfolioEntry, String> colCity =
                new TableColumn<>(t("advisory.col.city"));
        colCity.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().city() == null ? "" : c.getValue().city()));
        colCity.setPrefWidth(130);
        TableColumn<com.benjagest.ui.model.CustomerPortfolioEntry, String> colEmail =
                new TableColumn<>(t("advisory.col.email"));
        colEmail.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().email() == null ? "" : c.getValue().email()));
        TableColumn<com.benjagest.ui.model.CustomerPortfolioEntry, String> colLinked =
                new TableColumn<>(t("advisory.col.link_status"));
        colLinked.setCellValueFactory(c -> {
            var entry = c.getValue();
            String label;
            if (entry.isLinked())                  label = "✓ " + t("advisory.link.linked");
            else if (entry.hasPendingInvitation()) label = "📩 " + t("advisory.link.pending");
            else if (entry.wasUnlinked())          label = "⚠ " + t("advisory.link.unlinked");
            else                                   label = "✗ " + t("advisory.link.not_linked");
            return new SimpleStringProperty(label);
        });
        colLinked.setPrefWidth(160);
        advisoryPortfolioTable.getColumns().addAll(java.util.List.of(
                colName, colNif, colCity, colEmail, colLinked));
        advisoryPortfolioTable.setItems(FXCollections.observableArrayList(portfolio));

        // Doble click → abre cliente solo si está vinculado; si no,
        // muestra info para invitar.
        advisoryPortfolioTable.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                var sel = advisoryPortfolioTable.getSelectionModel().getSelectedItem();
                if (sel == null) return;
                if (sel.isLinked()) {
                    switchToClient(sel.asManagedClient());
                } else {
                    showInfo(t("advisory.portfolio.not_linked.title"),
                            t("advisory.portfolio.not_linked.body"));
                }
            }
        });

        Label hint = new Label(t("advisory.portfolio.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        Button openClientBtn = new Button(t("advisory.action.open_client"));
        openClientBtn.setGraphic(icon("fas-folder-open"));
        openClientBtn.getStyleClass().add("button-primary");
        openClientBtn.setDisable(true);
        Button inviteSelectedBtn = new Button(t("advisory.action.invite_selected"));
        inviteSelectedBtn.setGraphic(icon("fas-paper-plane"));
        inviteSelectedBtn.setDisable(true);
        // Tooltip contextual: explica el caso "vinculado" (pérdida de datos
        // del empresario) para que la asesoría sepa por qué se le permite
        // emitir un token nuevo a un cliente ya vinculado.
        Tooltip inviteSelectedTip = new Tooltip();
        Tooltip.install(inviteSelectedBtn, inviteSelectedTip);
        advisoryPortfolioTable.getSelectionModel().selectedItemProperty()
                .addListener((o, ov, nv) -> {
                    openClientBtn.setDisable(nv == null || !nv.isLinked());
                    // Solo se bloquea si ya hay una invitación PENDING para
                    // ese cliente — en ese caso el botón a usar es "Copiar
                    // token" del listado de invitaciones.
                    inviteSelectedBtn.setDisable(nv == null || nv.hasPendingInvitation());
                    // Texto + tooltip dependen del estado del cliente:
                    //   vinculado    → "Reenviar invitación" (pérdida de datos)
                    //   desvinculado → "Reinvitar"
                    //   ninguno      → "Invitar cliente seleccionado"
                    if (nv != null) {
                        if (nv.isLinked()) {
                            inviteSelectedBtn.setText(t("advisory.action.resend_invitation"));
                            inviteSelectedTip.setText(t("advisory.action.resend_invitation.tip"));
                        } else if (nv.wasUnlinked()) {
                            inviteSelectedBtn.setText(t("advisory.action.reinvite"));
                            inviteSelectedTip.setText(t("advisory.action.reinvite.tip"));
                        } else {
                            inviteSelectedBtn.setText(t("advisory.action.invite_selected"));
                            inviteSelectedTip.setText(t("advisory.action.invite_selected.tip"));
                        }
                    } else {
                        inviteSelectedBtn.setText(t("advisory.action.invite_selected"));
                    }
                });
        openClientBtn.setOnAction(ev -> {
            var sel = advisoryPortfolioTable.getSelectionModel().getSelectedItem();
            if (sel != null && sel.isLinked()) switchToClient(sel.asManagedClient());
        });
        inviteSelectedBtn.setOnAction(ev -> {
            var sel = advisoryPortfolioTable.getSelectionModel().getSelectedItem();
            if (sel != null) showCreateInvitationDialogPrefilled(sel);
        });

        Button inviteBtn = new Button(t("advisory.action.invite"));
        inviteBtn.setGraphic(icon("fas-paper-plane"));
        inviteBtn.getStyleClass().add("button-primary");
        inviteBtn.setOnAction(ev -> showCreateInvitationDialog());

        HBox actions = new HBox(8, openClientBtn, inviteSelectedBtn, inviteBtn);
        actions.getStyleClass().add("settings-actions");

        // Bloque de invitaciones — listado bajo la tabla de clientes
        Label invTitle = new Label(t("advisory.invitations.section"));
        invTitle.getStyleClass().add("settings-section-title");
        Label invHint = new Label(t("advisory.invitations.hint"));
        invHint.setWrapText(true);
        invHint.getStyleClass().add("settings-hint");

        advisoryInvitationsTable = buildAdvisoryInvitationsTable();
        Button reloadInvBtn = new Button(t("advisory.invitations.action.refresh"));
        reloadInvBtn.setOnAction(ev -> reloadAdvisoryInvitations());
        Button revokeBtn = new Button(t("advisory.invitations.action.revoke"));
        revokeBtn.setGraphic(icon("fas-ban"));
        revokeBtn.getStyleClass().add("button-danger-outline");
        revokeBtn.setDisable(true);
        revokeBtn.setOnAction(ev -> {
            var sel = advisoryInvitationsTable.getSelectionModel().getSelectedItem();
            if (sel != null) revokeInvitation(sel);
        });
        advisoryInvitationsTable.getSelectionModel().selectedItemProperty()
                .addListener((o, ov, nv) -> revokeBtn.setDisable(nv == null || !nv.isPending()));

        Button copyTokenBtn = new Button(t("advisory.invitations.action.copy_link"));
        copyTokenBtn.setGraphic(icon("fas-copy"));
        copyTokenBtn.setDisable(true);
        copyTokenBtn.setOnAction(ev -> {
            var sel = advisoryInvitationsTable.getSelectionModel().getSelectedItem();
            if (sel != null && sel.token() != null) {
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                        java.util.Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, sel.token()));
                showInfo(t("advisory.invitations.copied.title"),
                        t("advisory.invitations.copied.body"));
            }
        });
        advisoryInvitationsTable.getSelectionModel().selectedItemProperty()
                .addListener((o, ov, nv) -> copyTokenBtn.setDisable(nv == null || nv.token() == null));

        HBox invActions = new HBox(8, reloadInvBtn, copyTokenBtn, revokeBtn);

        VBox invitationsBlock = new VBox(8, invTitle, invHint,
                advisoryInvitationsTable, invActions);
        VBox.setVgrow(advisoryInvitationsTable, Priority.ALWAYS);

        VBox body = new VBox(16, hint, advisoryPortfolioTable, new Separator(), invitationsBlock);
        VBox.setVgrow(advisoryPortfolioTable, Priority.ALWAYS);

        reloadAdvisoryInvitations();
        content.getChildren().addAll(header, body, actions);
        return content;
    }

    /**
     * Abre el modal de invitación con datos pre-rellenados desde una
     * entrada existente del portfolio. Útil cuando el asesor ya tiene
     * al cliente en cartera pero aún no lo ha vinculado.
     */
    private void showCreateInvitationDialogPrefilled(com.benjagest.ui.model.CustomerPortfolioEntry prefill) {
        // Reutilizamos el diálogo estándar; los campos se llenan
        // automáticamente con email/NIF/nombre del cliente.
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("advisory.invitations.create.title"));
        ButtonType saveBt = new ButtonType(t("advisory.invitations.create.save"),
                ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField emailField = new TextField(prefill.email() == null ? "" : prefill.email());
        TextField nifField = new TextField(prefill.taxIdentifier() == null ? "" : prefill.taxIdentifier());
        TextField nameField = new TextField(prefill.legalName() == null ? "" : prefill.legalName());
        TextArea notesArea = new TextArea();
        notesArea.setPrefRowCount(3);
        for (TextField tf : new TextField[]{emailField, nifField, nameField}) tf.setPrefColumnCount(28);
        Label hint = new Label(t("advisory.invitations.create.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        GridPane g = new GridPane();
        g.setHgap(12); g.setVgap(8);
        int r = 0;
        g.add(new Label(t("advisory.invitations.create.email")), 0, r); g.add(emailField, 1, r++);
        g.add(new Label(t("advisory.invitations.create.nif")), 0, r); g.add(nifField, 1, r++);
        g.add(new Label(t("advisory.invitations.create.company_name")), 0, r); g.add(nameField, 1, r++);
        g.add(new Label(t("advisory.invitations.create.notes")), 0, r); g.add(notesArea, 1, r++);
        g.add(hint, 0, r++, 2, 1);
        installDialog(dialog, g);

        Button save = (Button) dialog.getDialogPane().lookupButton(saveBt);
        save.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            ev.consume();
            String email = nullIfBlank(emailField.getText());
            String nif = nullIfBlank(nifField.getText());
            if (email == null && nif == null) {
                showError(t("advisory.invitations.create.fail.missing.title"),
                        t("advisory.invitations.create.fail.missing.body"));
                return;
            }
            Task<com.benjagest.ui.model.AdvisoryInvitationEntry> task = new Task<>() {
                @Override
                protected com.benjagest.ui.model.AdvisoryInvitationEntry call() throws Exception {
                    return invitationsApi.create(email, nif, nullIfBlank(nameField.getText()),
                            nullIfBlank(notesArea.getText()));
                }
            };
            task.setOnSucceeded(e -> {
                var inv = task.getValue();
                if (inv.token() != null) {
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                            java.util.Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, inv.token()));
                }
                showInfo(t("advisory.invitations.create.ok.title"),
                        t("advisory.invitations.create.ok.body") + "\n\n"
                                + t("advisory.invitations.token_label") + " " + inv.token());
                dialog.setResult(saveBt);
                dialog.close();
                showAdvisoryClients(); // refresca el portfolio para mostrar el badge "📩 Pendiente"
            });
            task.setOnFailed(e -> showError(t("advisory.invitations.create.fail.title"),
                    t("advisory.invitations.create.fail.body")));
            start(task, "advisory-invitations-create");
        });

        dialog.showAndWait();
    }

    private VBox advisoryView(java.util.List<com.benjagest.ui.model.ManagedClientEntry> clients) {
        VBox content = content();
        Label title = new Label(t("advisory.title"));
        title.getStyleClass().add("module-detail-title");
        Label subtitle = new Label(t("advisory.subtitle"));
        subtitle.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, subtitle);
        StackPane moduleIcon = iconBubble("fas-briefcase", "module-title-icon");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(16, titleBox, moduleIcon, spacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        TableView<com.benjagest.ui.model.ManagedClientEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("advisory.placeholder.empty")));
        TableColumn<com.benjagest.ui.model.ManagedClientEntry, String> colName =
                new TableColumn<>(t("advisory.col.legal_name"));
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().legalName()));
        TableColumn<com.benjagest.ui.model.ManagedClientEntry, String> colNif =
                new TableColumn<>(t("advisory.col.nif"));
        colNif.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().taxIdentifier()));
        colNif.setPrefWidth(120);
        TableColumn<com.benjagest.ui.model.ManagedClientEntry, String> colType =
                new TableColumn<>(t("advisory.col.type"));
        colType.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().companyType()));
        colType.setPrefWidth(100);
        TableColumn<com.benjagest.ui.model.ManagedClientEntry, String> colCity =
                new TableColumn<>(t("advisory.col.city"));
        colCity.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().city()));
        colCity.setPrefWidth(140);
        TableColumn<com.benjagest.ui.model.ManagedClientEntry, String> colEmail =
                new TableColumn<>(t("advisory.col.email"));
        colEmail.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().email()));
        table.getColumns().addAll(java.util.List.of(colName, colNif, colType, colCity, colEmail));
        table.setItems(FXCollections.observableArrayList(clients));
        // Legacy advisoryView — ya no se usa como pantalla principal;
        // se mantiene solo por compatibilidad. El polling apunta a
        // advisoryPortfolioTable (el nuevo portfolio unificado).

        Label hint = new Label(t("advisory.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        // Doble click sobre cliente → abre la pantalla de gestión del
        // cliente sin tocar el activeCompanyId real de la asesoría.
        table.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                var sel = table.getSelectionModel().getSelectedItem();
                if (sel != null) switchToClient(sel);
            }
        });

        Button openClientBtn = new Button(t("advisory.action.open_client"));
        openClientBtn.setGraphic(icon("fas-folder-open"));
        openClientBtn.getStyleClass().add("button-primary");
        openClientBtn.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> openClientBtn.setDisable(nv == null));
        openClientBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) switchToClient(sel);
        });

        Button inviteBtn = new Button(t("advisory.action.invite"));
        inviteBtn.setGraphic(icon("fas-paper-plane"));
        inviteBtn.getStyleClass().add("button-primary");
        inviteBtn.setOnAction(ev -> showCreateInvitationDialog());

        HBox actions = new HBox(8, openClientBtn, inviteBtn);
        actions.getStyleClass().add("settings-actions");

        // Bloque de invitaciones — listado bajo la tabla de clientes
        Label invTitle = new Label(t("advisory.invitations.section"));
        invTitle.getStyleClass().add("settings-section-title");
        Label invHint = new Label(t("advisory.invitations.hint"));
        invHint.setWrapText(true);
        invHint.getStyleClass().add("settings-hint");

        advisoryInvitationsTable = buildAdvisoryInvitationsTable();
        Button reloadInvBtn = new Button(t("advisory.invitations.action.refresh"));
        reloadInvBtn.setOnAction(ev -> reloadAdvisoryInvitations());
        Button revokeBtn = new Button(t("advisory.invitations.action.revoke"));
        revokeBtn.setGraphic(icon("fas-ban"));
        revokeBtn.getStyleClass().add("button-danger-outline");
        revokeBtn.setDisable(true);
        revokeBtn.setOnAction(ev -> {
            var sel = advisoryInvitationsTable.getSelectionModel().getSelectedItem();
            if (sel != null) revokeInvitation(sel);
        });
        advisoryInvitationsTable.getSelectionModel().selectedItemProperty()
                .addListener((o, ov, nv) -> revokeBtn.setDisable(nv == null || !nv.isPending()));

        Button copyTokenBtn = new Button(t("advisory.invitations.action.copy_link"));
        copyTokenBtn.setGraphic(icon("fas-copy"));
        copyTokenBtn.setDisable(true);
        copyTokenBtn.setOnAction(ev -> {
            var sel = advisoryInvitationsTable.getSelectionModel().getSelectedItem();
            if (sel != null && sel.token() != null) {
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                        java.util.Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, sel.token()));
                showInfo(t("advisory.invitations.copied.title"),
                        t("advisory.invitations.copied.body"));
            }
        });
        advisoryInvitationsTable.getSelectionModel().selectedItemProperty()
                .addListener((o, ov, nv) -> copyTokenBtn.setDisable(nv == null || nv.token() == null));

        HBox invActions = new HBox(8, reloadInvBtn, copyTokenBtn, revokeBtn);

        VBox invitationsBlock = new VBox(8, invTitle, invHint,
                advisoryInvitationsTable, invActions);
        VBox.setVgrow(advisoryInvitationsTable, Priority.ALWAYS);

        VBox body = new VBox(16, hint, table, new Separator(), invitationsBlock);
        VBox.setVgrow(table, Priority.ALWAYS);

        reloadAdvisoryInvitations();
        content.getChildren().addAll(header, body, actions);
        return content;
    }

    private TableView<com.benjagest.ui.model.AdvisoryInvitationEntry> buildAdvisoryInvitationsTable() {
        TableView<com.benjagest.ui.model.AdvisoryInvitationEntry> tbl = new TableView<>();
        tbl.getStyleClass().add("data-table");
        tbl.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        tbl.setPlaceholder(new Label(t("advisory.invitations.placeholder.empty")));
        tbl.setPrefHeight(200);

        TableColumn<com.benjagest.ui.model.AdvisoryInvitationEntry, String> colDate =
                new TableColumn<>(t("advisory.invitations.col.date"));
        colDate.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().createdAt() == null ? "—"
                        : c.getValue().createdAt().atZone(java.time.ZoneId.systemDefault())
                                .toLocalDate().toString()));
        colDate.setComparator(ISO_DATE_COMPARATOR);
        colDate.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.AdvisoryInvitationEntry, String> colEmail =
                new TableColumn<>(t("advisory.invitations.col.email"));
        colEmail.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().invitedEmail() == null ? "—" : c.getValue().invitedEmail()));
        TableColumn<com.benjagest.ui.model.AdvisoryInvitationEntry, String> colNif =
                new TableColumn<>(t("advisory.invitations.col.nif"));
        colNif.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().invitedNif() == null ? "—" : c.getValue().invitedNif()));
        colNif.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.AdvisoryInvitationEntry, String> colCompany =
                new TableColumn<>(t("advisory.invitations.col.company"));
        colCompany.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().invitedCompanyName() == null ? "—" : c.getValue().invitedCompanyName()));
        TableColumn<com.benjagest.ui.model.AdvisoryInvitationEntry, String> colStatus =
                new TableColumn<>(t("advisory.invitations.col.status"));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(
                t("advisory.invitations.status." + c.getValue().status())));
        colStatus.setPrefWidth(110);
        tbl.getColumns().setAll(colDate, colEmail, colNif, colCompany, colStatus);
        return tbl;
    }

    private void reloadAdvisoryInvitations() {
        if (advisoryInvitationsTable == null) return;
        Task<java.util.List<com.benjagest.ui.model.AdvisoryInvitationEntry>> task = new Task<>() {
            @Override
            protected java.util.List<com.benjagest.ui.model.AdvisoryInvitationEntry> call() throws Exception {
                return invitationsApi.listMine();
            }
        };
        task.setOnSucceeded(e -> advisoryInvitationsTable.getItems().setAll(task.getValue()));
        task.setOnFailed(e -> showError(t("advisory.invitations.fail.list.title"),
                t("advisory.invitations.fail.list.body")));
        start(task, "advisory-invitations-list");
    }

    private void showCreateInvitationDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("advisory.invitations.create.title"));
        ButtonType saveBt = new ButtonType(t("advisory.invitations.create.save"),
                ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        TextField emailField = new TextField();
        TextField nifField = new TextField();
        TextField nameField = new TextField();
        TextArea notesArea = new TextArea();
        notesArea.setPrefRowCount(3);
        for (TextField tf : new TextField[]{emailField, nifField, nameField}) {
            tf.setPrefColumnCount(28);
        }
        Label hint = new Label(t("advisory.invitations.create.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        GridPane g = new GridPane();
        g.setHgap(12); g.setVgap(8);
        int r = 0;
        g.add(new Label(t("advisory.invitations.create.email")), 0, r); g.add(emailField, 1, r++);
        g.add(new Label(t("advisory.invitations.create.nif")), 0, r); g.add(nifField, 1, r++);
        g.add(new Label(t("advisory.invitations.create.company_name")), 0, r); g.add(nameField, 1, r++);
        g.add(new Label(t("advisory.invitations.create.notes")), 0, r); g.add(notesArea, 1, r++);
        g.add(hint, 0, r++, 2, 1);
        installDialog(dialog, g);

        Button save = (Button) dialog.getDialogPane().lookupButton(saveBt);
        save.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            ev.consume();
            String email = nullIfBlank(emailField.getText());
            String nif = nullIfBlank(nifField.getText());
            if (email == null && nif == null) {
                showError(t("advisory.invitations.create.fail.missing.title"),
                        t("advisory.invitations.create.fail.missing.body"));
                return;
            }
            Task<com.benjagest.ui.model.AdvisoryInvitationEntry> task = new Task<>() {
                @Override
                protected com.benjagest.ui.model.AdvisoryInvitationEntry call() throws Exception {
                    return invitationsApi.create(email, nif,
                            nullIfBlank(nameField.getText()),
                            nullIfBlank(notesArea.getText()));
                }
            };
            task.setOnSucceeded(e -> {
                var inv = task.getValue();
                // Copiar el token al portapapeles automáticamente
                if (inv.token() != null) {
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                            java.util.Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, inv.token()));
                }
                showInfo(t("advisory.invitations.create.ok.title"),
                        t("advisory.invitations.create.ok.body") + "\n\n"
                                + t("advisory.invitations.token_label") + " " + inv.token());
                dialog.setResult(saveBt);
                dialog.close();
                // Refresco local inmediato: la nueva invitación PENDING
                // aparece YA en el listado + el badge de la fila del
                // cliente en el portfolio pasa a "📩 Invitación
                // pendiente" sin esperar al tick de 5s.
                reloadAdvisoryInvitations();
                pollAdvisoryClients();
            });
            task.setOnFailed(e -> showError(t("advisory.invitations.create.fail.title"),
                    t("advisory.invitations.create.fail.body")));
            start(task, "advisory-invitations-create");
        });

        dialog.showAndWait();
    }

    private void revokeInvitation(com.benjagest.ui.model.AdvisoryInvitationEntry row) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("advisory.invitations.revoke.confirm.body"),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("advisory.invitations.revoke.confirm.title"));
        confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    invitationsApi.revoke(row.id());
                    return null;
                }
            };
            task.setOnSucceeded(e -> {
                // Refresco local inmediato: el listado de invitaciones
                // muestra YA la fila como REVOKED + el portfolio se
                // actualiza para que el botón "Invitar cliente
                // seleccionado" vuelva a habilitarse sin esperar a 5s.
                reloadAdvisoryInvitations();
                pollAdvisoryClients();
            });
            task.setOnFailed(e -> showError(t("advisory.invitations.revoke.fail.title"),
                    t("advisory.invitations.revoke.fail.body")));
            start(task, "advisory-invitations-revoke");
        });
    }

    /**
     * Abre la pantalla de gestión del cliente desde la asesoría.
     *
     * <p>NO cambia el activeCompanyId (la asesoría sigue siendo la
     * empresa activa de la sesión, el sidebar es el suyo, el header
     * conserva la marca de la asesoría). Solo activa el override
     * "acting for" en AuthSession para que las llamadas de esta
     * pantalla viajen con {@code X-Company-Id = cliente.id}.
     *
     * <p>Cuando el usuario pulsa "Volver" se limpia el override y se
     * regresa al listado de clientes — recuperando el contexto puro
     * de asesoría.
     */
    private void switchToClient(com.benjagest.ui.model.ManagedClientEntry client) {
        AuthSession.get().setActingForCompanyId(client.id());
        actingClientName = client.legalName();
        refreshClientModeBanner();
        setCenterAnimated(buildClientDetailView(client));
    }

    private Node buildClientDetailView(com.benjagest.ui.model.ManagedClientEntry client) {
        Button backBtn = new Button(t("advisory.client.back"));
        backBtn.setGraphic(icon("fas-arrow-left"));
        backBtn.setOnAction(ev -> {
            exitClientMode();
            showAdvisoryClients();
        });

        Label clientNameLabel = new Label(client.legalName());
        clientNameLabel.getStyleClass().add("module-detail-title");
        Label clientMeta = new Label(
                (client.taxIdentifier() == null ? "" : client.taxIdentifier())
                        + (client.city() == null || client.city().isBlank()
                                ? "" : "  ·  " + client.city()));
        clientMeta.getStyleClass().add("module-detail-description");
        VBox clientTitle = new VBox(2, clientNameLabel, clientMeta);

        StackPane clientIcon = iconBubble("fas-building", "module-title-icon");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(16, backBtn, clientIcon, clientTitle, spacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        Label hint = new Label(t("advisory.client.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        // TabPane: cada sección hace queries con X-Company-Id=cliente
        // gracias al actingForCompanyId activo en AuthSession.
        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("settings-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab summaryTab = new Tab(t("advisory.client.tab.summary"),
                buildClientSummaryTab(client));
        summaryTab.setGraphic(icon("fas-chart-line"));

        // Facturación del cliente — ventas emitidas con estado y total.
        Tab billingTab = new Tab(t("advisory.client.tab.billing"),
                buildClientBillingTab());
        billingTab.setGraphic(icon("fas-file-invoice-dollar"));

        Tab purchasesTab = new Tab(t("advisory.client.tab.purchases"),
                buildClientPurchasesTab());
        purchasesTab.setGraphic(icon("fas-receipt"));

        // Contabilidad del cliente — reutiliza AccountingScreen. Las
        // llamadas API llevan el X-Company-Id del cliente porque
        // AuthSession.actingForCompanyId ya está activo. El asesor revisa
        // asientos auto-propuestos, valida, edita reglas aprendidas y
        // ejecuta recurrentes desde aquí, sin salir del contexto del
        // cliente.
        com.benjagest.ui.screens.AccountingScreen accountingScreen =
                new com.benjagest.ui.screens.AccountingScreen(accountingApiClient, this::t);
        Tab accountingTab = new Tab(t("advisory.client.tab.accounting"),
                accountingScreen.buildView());
        accountingTab.setGraphic(icon("fas-book"));

        // Bancos / Préstamos / Inmovilizado — reutiliza ClientFinancialsScreen.
        com.benjagest.ui.screens.ClientFinancialsScreen financials =
                new com.benjagest.ui.screens.ClientFinancialsScreen(accountingApiClient, this::t);
        Tab banksTab = new Tab(t("advisory.client.tab.banks"), financials.buildBanksTab());
        banksTab.setGraphic(icon("fas-university"));
        Tab loansTab = new Tab(t("advisory.client.tab.loans"), financials.buildLoansTab());
        loansTab.setGraphic(icon("fas-hand-holding-usd"));
        Tab assetsTab = new Tab(t("advisory.client.tab.assets"), financials.buildAssetsTab());
        assetsTab.setGraphic(icon("fas-cubes"));

        Tab laborTab = new Tab(t("advisory.client.tab.labor"),
                buildClientLaborTab());
        laborTab.setGraphic(icon("fas-users"));

        Tab taxTab = new Tab(t("advisory.client.tab.tax_models"),
                buildClientTaxFilingsTab());
        taxTab.setGraphic(icon("fas-landmark"));

        Tab certificateTab = new Tab(t("advisory.client.tab.certificate"),
                settingsCertificateTab());
        certificateTab.setGraphic(icon("fas-certificate"));

        tabs.getTabs().addAll(summaryTab, billingTab, purchasesTab, accountingTab,
                banksTab, loansTab, assetsTab, laborTab, taxTab, certificateTab);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        VBox body = new VBox(12, header, hint, tabs);
        body.setPadding(new Insets(20));
        return body;
    }

    private Node buildClientSummaryTab(com.benjagest.ui.model.ManagedClientEntry client) {
        Label title = label(t("advisory.client.summary.title"), "settings-section-title");
        Label hint = new Label(t("advisory.client.summary.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        GridPane g = new GridPane();
        g.setHgap(20); g.setVgap(8);
        int r = 0;
        g.add(new Label(t("advisory.client.field.legal_name")), 0, r);
        g.add(new Label(client.legalName() == null ? "—" : client.legalName()), 1, r++);
        g.add(new Label(t("advisory.client.field.nif")), 0, r);
        g.add(new Label(client.taxIdentifier() == null ? "—" : client.taxIdentifier()), 1, r++);
        if (client.companyType() != null) {
            g.add(new Label(t("advisory.client.field.type")), 0, r);
            g.add(new Label(client.companyType()), 1, r++);
        }
        if (client.email() != null) {
            g.add(new Label(t("advisory.client.field.email")), 0, r);
            g.add(new Label(client.email()), 1, r++);
        }
        if (client.city() != null && !client.city().isBlank()) {
            g.add(new Label(t("advisory.client.field.city")), 0, r);
            g.add(new Label(client.city()), 1, r++);
        }

        Label kpisTitle = label(t("advisory.client.kpis.title"), "settings-section-title");
        Label kpisHint = new Label(t("advisory.client.kpis.coming_soon"));
        kpisHint.setWrapText(true);
        kpisHint.getStyleClass().add("settings-hint");

        VBox body = new VBox(14, title, hint, g, new Separator(), kpisTitle, kpisHint);
        body.setPadding(new Insets(20));
        return body;
    }

    /**
     * Pestaña Compras y Gastos dentro de la pantalla del cliente.
     * Renderiza el listado COMPLETO de gastos directamente dentro del
     * tab (no navega fuera). Gracias al actingForCompanyId activo en
     * AuthSession, las queries van al tenant del cliente — los gastos
     * que se ven son los de SU empresa, no los de la asesoría.
     *
     * El asesor puede importar PDF, filtrar por año / trimestre /
     * proveedor y eliminar gastos sin salir del contexto del cliente.
     */
    /**
     * Pestaña Facturación dentro de la pantalla del cliente. Listado
     * compacto de facturas emitidas (sales_invoices) con estado y total.
     * Usa el {@link BillingApiClient} existente — las llamadas heredan
     * el actingForCompanyId del cliente.
     */
    private Node buildClientBillingTab() {
        javafx.scene.control.TableView<com.benjagest.ui.model.SalesInvoiceSummary> table =
                new javafx.scene.control.TableView<>();
        addCol(table, t("billing.col.number"), v -> v.invoiceNumber() == null ? "" : v.invoiceNumber(), 130);
        addCol(table, t("billing.col.date"), v -> v.invoiceDate() == null ? "" : v.invoiceDate(), 100);
        addCol(table, t("billing.col.customer"), v -> v.customerLegalName() == null ? "" : v.customerLegalName(), 240);
        addCol(table, t("billing.col.type"), v -> v.invoiceType() == null ? "" : v.invoiceType(), 90);
        addCol(table, t("billing.col.total"), v -> v.total() == null ? "" : v.total().toString(), 110);
        addCol(table, t("billing.col.paid"), v -> v.paidAmount() == null ? "" : v.paidAmount().toString(), 100);
        addCol(table, t("billing.col.status"),
                v -> v.status() == null ? "" : t("accounting.status." + v.status()), 110);
        addCol(table, t("billing.col.payment_status"),
                v -> v.paymentStatus() == null ? "" : t("billing.payment_status." + v.paymentStatus()), 100);

        Button refresh = new Button(t("accounting.action.refresh"));
        refresh.setOnAction(e -> loadClientBilling(table));
        HBox actions = new HBox(8, refresh);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, actions, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPadding(new Insets(12));
        loadClientBilling(table);
        return box;
    }

    private void loadClientBilling(javafx.scene.control.TableView<com.benjagest.ui.model.SalesInvoiceSummary> table) {
        Task<java.util.List<com.benjagest.ui.model.SalesInvoiceSummary>> task = new Task<>() {
            @Override protected java.util.List<com.benjagest.ui.model.SalesInvoiceSummary> call() throws Exception {
                return billingApiClient.listInvoices(null, null, null, 500);
            }
        };
        task.setOnSucceeded(ev -> table.setItems(
                javafx.collections.FXCollections.observableArrayList(task.getValue())));
        task.setOnFailed(ev -> System.err.println("[client-billing] "
                + (task.getException() == null ? "?" : task.getException().getMessage())));
        start(task, "client-billing");
    }

    /**
     * Pestaña Empleados del cliente — lista empleados activos del
     * tenant del cliente. Para nóminas/contratos/fichajes, el asesor
     * accede al módulo Labor completo desde el sidebar de asesoría.
     */
    private Node buildClientLaborTab() {
        javafx.scene.control.TableView<com.benjagest.ui.model.EmployeeEntry> table =
                new javafx.scene.control.TableView<>();
        addCol(table, t("labor.col.name"), v -> v.fullName() == null ? "" : v.fullName(), 220);
        addCol(table, t("labor.col.nif"), v -> v.taxIdentifier() == null ? "" : v.taxIdentifier(), 110);
        addCol(table, t("labor.col.regime"), v -> v.ssRegime() == null ? "" : v.ssRegime(), 110);
        addCol(table, t("labor.col.hire_date"), v -> v.hireDate() == null ? "" : v.hireDate().toString(), 100);
        addCol(table, t("labor.col.active"), v -> v.active() ? "✓" : "✗", 70);

        Button refresh = new Button(t("accounting.action.refresh"));
        refresh.setOnAction(e -> loadClientLabor(table));
        HBox actions = new HBox(8, refresh);
        VBox box = new VBox(8, actions, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPadding(new Insets(12));
        loadClientLabor(table);
        return box;
    }

    private void loadClientLabor(javafx.scene.control.TableView<com.benjagest.ui.model.EmployeeEntry> table) {
        Task<java.util.List<com.benjagest.ui.model.EmployeeEntry>> task = new Task<>() {
            @Override protected java.util.List<com.benjagest.ui.model.EmployeeEntry> call() throws Exception {
                return laborApiClient.listEmployees(true);
            }
        };
        task.setOnSucceeded(ev -> table.setItems(
                javafx.collections.FXCollections.observableArrayList(task.getValue())));
        task.setOnFailed(ev -> System.err.println("[client-labor] "
                + (task.getException() == null ? "?" : task.getException().getMessage())));
        start(task, "client-labor");
    }

    /**
     * Pestaña Modelos AEAT del cliente — listado de declaraciones
     * (tax_filings) con su estado, modelo, periodo e importe. Para
     * generar 347/390/190 el asesor entra en el módulo Modelos AEAT.
     */
    private Node buildClientTaxFilingsTab() {
        javafx.scene.control.TableView<com.benjagest.ui.model.TaxFilingEntry> table =
                new javafx.scene.control.TableView<>();
        addCol(table, t("tax.col.model"), v -> v.taxModelCode() == null ? "" : v.taxModelCode(), 90);
        addCol(table, t("tax.col.year"), v -> String.valueOf(v.periodYear()), 70);
        addCol(table, t("tax.col.quarter"), v -> v.periodQuarter() == null ? "" : "T" + v.periodQuarter(), 90);
        addCol(table, t("tax.col.month"), v -> v.periodMonth() == null ? "" : String.valueOf(v.periodMonth()), 60);
        addCol(table, t("tax.col.status"),
                v -> v.status() == null ? "" : t("tax.filing_status." + v.status()), 110);
        addCol(table, t("tax.col.amount"), v -> v.totalAmount() == null ? "" : v.totalAmount().toString(), 120);
        addCol(table, t("tax.col.deadline"), v -> v.deadlineAt() == null ? "" : v.deadlineAt().toString(), 110);

        Button refresh = new Button(t("accounting.action.refresh"));
        refresh.setOnAction(e -> loadClientFilings(table));
        HBox actions = new HBox(8, refresh);
        VBox box = new VBox(8, actions, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPadding(new Insets(12));
        loadClientFilings(table);
        return box;
    }

    private void loadClientFilings(javafx.scene.control.TableView<com.benjagest.ui.model.TaxFilingEntry> table) {
        Task<java.util.List<com.benjagest.ui.model.TaxFilingEntry>> task = new Task<>() {
            @Override protected java.util.List<com.benjagest.ui.model.TaxFilingEntry> call() throws Exception {
                return altaApiClient.listFilings(null, null, null);
            }
        };
        task.setOnSucceeded(ev -> table.setItems(
                javafx.collections.FXCollections.observableArrayList(task.getValue())));
        task.setOnFailed(ev -> System.err.println("[client-filings] "
                + (task.getException() == null ? "?" : task.getException().getMessage())));
        start(task, "client-filings");
    }

    /** Helper genérico para añadir columnas con un getter String. */
    private <T> void addCol(javafx.scene.control.TableView<T> table, String header,
                              java.util.function.Function<T, String> getter, double width) {
        javafx.scene.control.TableColumn<T, String> c = new javafx.scene.control.TableColumn<>(header);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(getter.apply(cd.getValue())));
        table.getColumns().add(c);
    }

    private Node buildClientPurchasesTab() {
        return buildPurchasesListing(false);
    }

    // ===================================================================
    //  ALTA-6 — Modelos AEAT: listado, calendario, editores 303/130 y
    //  editor genérico para los demás modelos.
    // ===================================================================

    private TableView<com.benjagest.ui.model.TaxFilingEntry> taxFilingsTable;
    private TableView<com.benjagest.ui.model.TaxDueDateEntry> taxCalendarTable;
    private int taxCurrentYear = LocalDate.now().getYear();

    private void showTaxModels() {
        Task<TaxBundle> task = new Task<>() {
            @Override
            protected TaxBundle call() throws Exception {
                return new TaxBundle(
                        altaApiClient.listTaxModels(),
                        altaApiClient.listFilings(taxCurrentYear, null, null),
                        altaApiClient.calendar(taxCurrentYear));
            }
        };
        task.setOnSucceeded(ev -> setCenterAnimated(scroll(taxView(task.getValue()))));
        task.setOnFailed(ev -> setCenterAnimated(scroll(errorPanel(t("tax.load_failed")))));
        start(task, "tax-models-load");
    }

    private record TaxBundle(
            java.util.List<com.benjagest.ui.model.TaxModelEntry> catalog,
            java.util.List<com.benjagest.ui.model.TaxFilingEntry> filings,
            java.util.List<com.benjagest.ui.model.TaxDueDateEntry> calendar
    ) {}

    private VBox taxView(TaxBundle bundle) {
        VBox content = content();
        Label title = new Label(t("tax.title"));
        title.getStyleClass().add("module-detail-title");
        Label subtitle = new Label(t("tax.subtitle"));
        subtitle.getStyleClass().add("module-detail-description");
        VBox titleBox = new VBox(4, title, subtitle);
        StackPane moduleIcon = iconBubble("fas-percentage", "module-title-icon");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Selector de año
        ComboBox<Integer> yearCombo = new ComboBox<>();
        int currentY = LocalDate.now().getYear();
        for (int y = currentY + 1; y >= currentY - 5; y--) yearCombo.getItems().add(y);
        yearCombo.getSelectionModel().select(Integer.valueOf(taxCurrentYear));
        yearCombo.setOnAction(ev -> {
            taxCurrentYear = yearCombo.getValue();
            showTaxModels();
        });

        Button newBtn = new Button(t("tax.action.new"));
        newBtn.setGraphic(icon("fas-plus"));
        newBtn.setOnAction(ev -> showFilingEditor(null, bundle.catalog()));

        HBox header = new HBox(16, titleBox, moduleIcon, spacer,
                new Label(t("tax.year") + ":"), yearCombo, newBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("settings-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab filingsTab = new Tab(t("tax.tab.filings"), buildFilingsTab(bundle));
        filingsTab.setGraphic(icon("fas-file-alt"));
        Tab calendarTab = new Tab(t("tax.tab.calendar"), buildCalendarTab(bundle));
        calendarTab.setGraphic(icon("fas-calendar-alt"));
        tabs.getTabs().addAll(filingsTab, calendarTab);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        content.getChildren().addAll(header, tabs);
        return content;
    }

    private Node buildFilingsTab(TaxBundle bundle) {
        taxFilingsTable = new TableView<>();
        taxFilingsTable.getStyleClass().add("data-table");
        taxFilingsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        taxFilingsTable.setPlaceholder(new Label(t("tax.filings.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.TaxFilingEntry, String> colModel =
                new TableColumn<>(t("tax.filings.col.model"));
        colModel.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().taxModelCode()));
        colModel.setPrefWidth(70);
        TableColumn<com.benjagest.ui.model.TaxFilingEntry, String> colPeriod =
                new TableColumn<>(t("tax.filings.col.period"));
        colPeriod.setCellValueFactory(c -> new SimpleStringProperty(formatPeriod(c.getValue())));
        colPeriod.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.TaxFilingEntry, String> colStatus =
                new TableColumn<>(t("tax.filings.col.status"));
        colStatus.setCellValueFactory(c -> new SimpleStringProperty(t("tax.status." + c.getValue().status())));
        colStatus.setPrefWidth(110);
        TableColumn<com.benjagest.ui.model.TaxFilingEntry, String> colAmount =
                new TableColumn<>(t("tax.filings.col.amount"));
        colAmount.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().totalAmount() == null ? "" : c.getValue().totalAmount().toPlainString() + " €"));
        colAmount.setPrefWidth(110);
        colAmount.setComparator(NUMERIC_STRING_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TaxFilingEntry, String> colDeadline =
                new TableColumn<>(t("tax.filings.col.deadline"));
        colDeadline.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().deadlineAt()));
        colDeadline.setPrefWidth(110);
        colDeadline.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TaxFilingEntry, String> colCsv =
                new TableColumn<>(t("tax.filings.col.csv"));
        colCsv.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().csvAeat()));
        taxFilingsTable.getColumns().addAll(java.util.List.of(colModel, colPeriod, colStatus, colAmount, colDeadline, colCsv));
        taxFilingsTable.setItems(FXCollections.observableArrayList(bundle.filings()));
        taxFilingsTable.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                var sel = taxFilingsTable.getSelectionModel().getSelectedItem();
                if (sel != null) showFilingEditor(sel, bundle.catalog());
            }
        });

        Button editBtn = new Button(t("tax.filings.action.edit"));
        editBtn.setGraphic(icon("fas-edit"));
        editBtn.setDisable(true);
        editBtn.setOnAction(ev -> {
            var sel = taxFilingsTable.getSelectionModel().getSelectedItem();
            if (sel != null) showFilingEditor(sel, bundle.catalog());
        });

        Button deleteBtn = new Button(t("tax.filings.action.delete"));
        deleteBtn.setGraphic(icon("fas-trash"));
        deleteBtn.setDisable(true);
        deleteBtn.setOnAction(ev -> {
            var sel = taxFilingsTable.getSelectionModel().getSelectedItem();
            if (sel != null) deleteFiling(sel);
        });

        taxFilingsTable.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            editBtn.setDisable(nv == null);
            deleteBtn.setDisable(nv == null
                    || !("DRAFT".equals(nv.status()) || "CANCELLED".equals(nv.status())));
        });

        HBox actions = new HBox(8, editBtn, deleteBtn);
        actions.getStyleClass().add("settings-actions");

        VBox body = new VBox(12, taxFilingsTable);
        VBox.setVgrow(taxFilingsTable, Priority.ALWAYS);
        return screenScroll(new VBox(8, body, actions));
    }

    private Node buildCalendarTab(TaxBundle bundle) {
        Label hint = new Label(t("tax.calendar.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        taxCalendarTable = new TableView<>();
        taxCalendarTable.getStyleClass().add("data-table");
        taxCalendarTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        taxCalendarTable.setPlaceholder(new Label(t("tax.calendar.placeholder.empty")));

        TableColumn<com.benjagest.ui.model.TaxDueDateEntry, String> colDeadline =
                new TableColumn<>(t("tax.calendar.col.deadline"));
        colDeadline.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().deadlineAt()));
        colDeadline.setPrefWidth(110);
        colDeadline.setComparator(ISO_DATE_COMPARATOR);
        TableColumn<com.benjagest.ui.model.TaxDueDateEntry, String> colModel =
                new TableColumn<>(t("tax.calendar.col.model"));
        colModel.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().taxModelCode()));
        colModel.setPrefWidth(70);
        TableColumn<com.benjagest.ui.model.TaxDueDateEntry, String> colName =
                new TableColumn<>(t("tax.calendar.col.name"));
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().taxModelName()));
        TableColumn<com.benjagest.ui.model.TaxDueDateEntry, String> colPeriod =
                new TableColumn<>(t("tax.calendar.col.period"));
        colPeriod.setCellValueFactory(c -> new SimpleStringProperty(formatPeriod(
                c.getValue().periodYear(), c.getValue().periodQuarter(), c.getValue().periodMonth())));
        colPeriod.setPrefWidth(120);
        TableColumn<com.benjagest.ui.model.TaxDueDateEntry, String> colState =
                new TableColumn<>(t("tax.calendar.col.state"));
        colState.setCellValueFactory(c -> new SimpleStringProperty(calendarFilingState(c.getValue(), bundle.filings())));
        colState.setPrefWidth(120);
        taxCalendarTable.getColumns().addAll(java.util.List.of(colDeadline, colModel, colName, colPeriod, colState));

        // Ordenar por fecha ascendente para que arriba salgan los más próximos
        var sorted = new java.util.ArrayList<>(bundle.calendar());
        sorted.sort(java.util.Comparator.comparing(com.benjagest.ui.model.TaxDueDateEntry::deadlineAt));
        taxCalendarTable.setItems(FXCollections.observableArrayList(sorted));

        VBox.setVgrow(taxCalendarTable, Priority.ALWAYS);
        return screenScroll(new VBox(8, hint, taxCalendarTable));
    }

    private String calendarFilingState(com.benjagest.ui.model.TaxDueDateEntry due,
                                        java.util.List<com.benjagest.ui.model.TaxFilingEntry> filings) {
        for (var f : filings) {
            if (!due.taxModelCode().equals(f.taxModelCode())) continue;
            if (f.periodYear() != due.periodYear()) continue;
            if (!java.util.Objects.equals(f.periodQuarter(), due.periodQuarter())) continue;
            if (!java.util.Objects.equals(f.periodMonth(), due.periodMonth())) continue;
            return t("tax.status." + f.status());
        }
        return t("tax.calendar.state.pending");
    }

    private String formatPeriod(com.benjagest.ui.model.TaxFilingEntry f) {
        return formatPeriod(f.periodYear(), f.periodQuarter(), f.periodMonth());
    }

    private String formatPeriod(int year, Integer quarter, Integer month) {
        if (quarter != null) return year + " T" + quarter;
        if (month != null) return year + " M" + String.format("%02d", month);
        return String.valueOf(year);
    }

    private void deleteFiling(com.benjagest.ui.model.TaxFilingEntry entry) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                t("tax.filings.delete.body") + " " + entry.taxModelCode() + " " + formatPeriod(entry),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(t("tax.filings.delete.title"));
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    altaApiClient.deleteFiling(entry.id());
                    return null;
                }
            };
            task.setOnSucceeded(ev -> showTaxModels());
            task.setOnFailed(ev -> showError(t("tax.filings.delete.fail.title"),
                    t("tax.filings.delete.fail.body")));
            start(task, "tax-filing-delete");
        });
    }

    /**
     * Editor de declaracion. Si el modelo es 303 o 130 se abren los
     * editores especificos (con sus casillas). Para el resto, un editor
     * generico con JSON crudo en TextArea.
     */
    private void showFilingEditor(com.benjagest.ui.model.TaxFilingEntry existing,
                                   java.util.List<com.benjagest.ui.model.TaxModelEntry> catalog) {
        String modelCode = existing == null ? null : existing.taxModelCode();
        if (existing == null) {
            // Para nueva declaracion, primero el usuario elige modelo y periodo.
            showNewFilingDialog(catalog);
            return;
        }
        if ("303".equals(modelCode)) {
            show303Editor(existing);
        } else if ("130".equals(modelCode)) {
            show130Editor(existing);
        } else {
            showGenericFilingEditor(existing, catalog);
        }
    }

    private void showNewFilingDialog(java.util.List<com.benjagest.ui.model.TaxModelEntry> catalog) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("tax.new.title"));
        ButtonType nextBt = new ButtonType(t("tax.new.next"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(nextBt, ButtonType.CANCEL);

        ComboBox<com.benjagest.ui.model.TaxModelEntry> modelCombo = new ComboBox<>();
        modelCombo.getItems().addAll(catalog);
        modelCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.benjagest.ui.model.TaxModelEntry m) {
                return m == null ? "" : (m.code() + " · " + m.name());
            }
            @Override public com.benjagest.ui.model.TaxModelEntry fromString(String s) { return null; }
        });
        if (!catalog.isEmpty()) modelCombo.getSelectionModel().selectFirst();

        ComboBox<Integer> yearCombo = new ComboBox<>();
        int currentY = LocalDate.now().getYear();
        for (int y = currentY + 1; y >= currentY - 5; y--) yearCombo.getItems().add(y);
        yearCombo.getSelectionModel().select(Integer.valueOf(taxCurrentYear));

        ComboBox<String> periodCombo = new ComboBox<>();
        periodCombo.getItems().addAll("T1", "T2", "T3", "T4", "M01", "M02", "M03", "M04", "M05", "M06",
                "M07", "M08", "M09", "M10", "M11", "M12", "ANUAL");
        periodCombo.getSelectionModel().select("T1");

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.add(new Label(t("tax.new.model")), 0, 0); grid.add(modelCombo, 1, 0);
        grid.add(new Label(t("tax.new.year")), 0, 1); grid.add(yearCombo, 1, 1);
        grid.add(new Label(t("tax.new.period")), 0, 2); grid.add(periodCombo, 1, 2);
        installDialog(dialog, grid);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != nextBt) return;
            var model = modelCombo.getValue();
            if (model == null) return;
            Integer quarter = null, month = null;
            String pv = periodCombo.getValue();
            if (pv != null && pv.startsWith("T")) quarter = Integer.parseInt(pv.substring(1));
            else if (pv != null && pv.startsWith("M")) month = Integer.parseInt(pv.substring(1));
            // Crear stub vacio y abrir editor especifico
            com.benjagest.ui.model.TaxFilingEntry stub = new com.benjagest.ui.model.TaxFilingEntry(
                    null, model.code(), yearCombo.getValue(), quarter, month,
                    "DRAFT", null, null, null, null, null, "{}");
            Task<com.benjagest.ui.model.TaxFilingEntry> task = new Task<>() {
                @Override
                protected com.benjagest.ui.model.TaxFilingEntry call() throws Exception {
                    return altaApiClient.createFiling(model.code(), yearCombo.getValue(), stub.periodQuarter(),
                            stub.periodMonth(), "DRAFT", "{}", null, null, null);
                }
            };
            task.setOnSucceeded(ev -> {
                var created = task.getValue();
                showFilingEditor(created, catalog);
            });
            task.setOnFailed(ev -> showError(t("tax.new.fail.title"), t("tax.new.fail.body")));
            start(task, "tax-filing-create");
        });
    }

    /** Editor genérico: JSON crudo en TextArea + estado + total + CSV + notas. */
    private void showGenericFilingEditor(com.benjagest.ui.model.TaxFilingEntry existing,
                                          java.util.List<com.benjagest.ui.model.TaxModelEntry> catalog) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("tax.editor.generic.title") + " — " + existing.taxModelCode() + " " + formatPeriod(existing));
        ButtonType saveBt = new ButtonType(t("tax.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        ComboBox<String> statusCombo = new ComboBox<>();
        statusCombo.getItems().addAll("DRAFT", "READY", "PRESENTED", "PAID", "REJECTED", "CANCELLED");
        statusCombo.getSelectionModel().select(existing.status());
        TextField amountField = new TextField(existing.totalAmount() == null
                ? "" : existing.totalAmount().toPlainString());
        TextField csvField = new TextField(existing.csvAeat());
        TextArea dataArea = new TextArea(existing.dataJson() == null || existing.dataJson().isBlank()
                ? "{}" : existing.dataJson());
        dataArea.setPrefRowCount(6);
        TextArea notesArea = new TextArea(existing.notes());
        notesArea.setPrefRowCount(2);

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        grid.add(new Label(t("tax.editor.status")), 0, 0); grid.add(statusCombo, 1, 0);
        grid.add(new Label(t("tax.editor.total")), 0, 1); grid.add(amountField, 1, 1);
        grid.add(new Label(t("tax.editor.csv")), 0, 2); grid.add(csvField, 1, 2);
        grid.add(new Label(t("tax.editor.data")), 0, 3); grid.add(dataArea, 1, 3);
        grid.add(new Label(t("tax.editor.notes")), 0, 4); grid.add(notesArea, 1, 4);
        installDialog(dialog, grid);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            saveFiling(existing, statusCombo.getValue(), dataArea.getText(),
                    parseDec(amountField.getText()), csvField.getText(), notesArea.getText(), catalog);
        });
    }

    /** Editor 303 — IVA autoliquidación trimestral. Casillas básicas. */
    private void show303Editor(com.benjagest.ui.model.TaxFilingEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Modelo 303 — " + formatPeriod(existing));
        ButtonType saveBt = new ButtonType(t("tax.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        java.util.Map<String, String> parsed = parseDataMap(existing.dataJson());

        // IVA repercutido (devengado)
        TextField b21 = new TextField(parsed.getOrDefault("base_21", ""));
        TextField c21 = new TextField(parsed.getOrDefault("cuota_21", ""));
        TextField b10 = new TextField(parsed.getOrDefault("base_10", ""));
        TextField c10 = new TextField(parsed.getOrDefault("cuota_10", ""));
        TextField b4 = new TextField(parsed.getOrDefault("base_4", ""));
        TextField c4 = new TextField(parsed.getOrDefault("cuota_4", ""));
        // IVA soportado (deducible)
        TextField bs = new TextField(parsed.getOrDefault("base_soportado", ""));
        TextField cs = new TextField(parsed.getOrDefault("cuota_soportada", ""));

        ComboBox<String> statusCombo = new ComboBox<>();
        statusCombo.getItems().addAll("DRAFT", "READY", "PRESENTED", "PAID", "REJECTED");
        statusCombo.getSelectionModel().select(existing.status());
        TextField csvField = new TextField(existing.csvAeat());

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(6); grid.setPadding(new Insets(12));
        grid.add(label("IVA repercutido (devengado)", "settings-section-title"), 0, 0, 4, 1);
        grid.add(new Label("Base 21 %"), 0, 1); grid.add(b21, 1, 1);
        grid.add(new Label("Cuota 21 %"), 2, 1); grid.add(c21, 3, 1);
        grid.add(new Label("Base 10 %"), 0, 2); grid.add(b10, 1, 2);
        grid.add(new Label("Cuota 10 %"), 2, 2); grid.add(c10, 3, 2);
        grid.add(new Label("Base 4 %"), 0, 3); grid.add(b4, 1, 3);
        grid.add(new Label("Cuota 4 %"), 2, 3); grid.add(c4, 3, 3);

        grid.add(new Separator(), 0, 4, 4, 1);
        grid.add(label("IVA soportado (deducible)", "settings-section-title"), 0, 5, 4, 1);
        grid.add(new Label("Base soportada"), 0, 6); grid.add(bs, 1, 6);
        grid.add(new Label("Cuota soportada"), 2, 6); grid.add(cs, 3, 6);

        grid.add(new Separator(), 0, 7, 4, 1);
        Label resultLabel = new Label();
        resultLabel.getStyleClass().add("settings-section-title");
        grid.add(resultLabel, 0, 8, 4, 1);

        Runnable recompute = () -> {
            java.math.BigDecimal repercutido = sum(c21.getText(), c10.getText(), c4.getText());
            java.math.BigDecimal soportado = parseDec(cs.getText());
            if (soportado == null) soportado = java.math.BigDecimal.ZERO;
            java.math.BigDecimal result = repercutido.subtract(soportado);
            resultLabel.setText("Resultado (casilla 71): " + result.toPlainString() + " €");
        };
        for (TextField f : new TextField[]{c21, c10, c4, cs}) {
            f.textProperty().addListener((o, ov, nv) -> recompute.run());
        }
        recompute.run();

        grid.add(new Label(t("tax.editor.status")), 0, 9); grid.add(statusCombo, 1, 9);
        grid.add(new Label(t("tax.editor.csv")), 2, 9); grid.add(csvField, 3, 9);

        installDialog(dialog, grid);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            java.util.Map<String, String> data = new java.util.LinkedHashMap<>();
            data.put("base_21", b21.getText().trim());
            data.put("cuota_21", c21.getText().trim());
            data.put("base_10", b10.getText().trim());
            data.put("cuota_10", c10.getText().trim());
            data.put("base_4", b4.getText().trim());
            data.put("cuota_4", c4.getText().trim());
            data.put("base_soportado", bs.getText().trim());
            data.put("cuota_soportada", cs.getText().trim());
            java.math.BigDecimal total = sum(c21.getText(), c10.getText(), c4.getText())
                    .subtract(parseDec(cs.getText()) == null ? java.math.BigDecimal.ZERO : parseDec(cs.getText()));
            saveFiling(existing, statusCombo.getValue(), encodeDataMap(data), total,
                    csvField.getText(), existing.notes(), java.util.List.of());
        });
    }

    /** Editor 130 — IRPF pago fraccionado estimación directa. */
    private void show130Editor(com.benjagest.ui.model.TaxFilingEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Modelo 130 — " + formatPeriod(existing));
        ButtonType saveBt = new ButtonType(t("tax.editor.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBt, ButtonType.CANCEL);

        java.util.Map<String, String> parsed = parseDataMap(existing.dataJson());

        TextField ingresos = new TextField(parsed.getOrDefault("ingresos", ""));
        TextField gastos = new TextField(parsed.getOrDefault("gastos", ""));
        TextField retencionesPrev = new TextField(parsed.getOrDefault("retenciones", ""));
        TextField pagosPrev = new TextField(parsed.getOrDefault("pagos_previos", ""));

        ComboBox<String> statusCombo = new ComboBox<>();
        statusCombo.getItems().addAll("DRAFT", "READY", "PRESENTED", "PAID", "REJECTED");
        statusCombo.getSelectionModel().select(existing.status());
        TextField csvField = new TextField(existing.csvAeat());

        Label resultLabel = new Label();
        resultLabel.getStyleClass().add("settings-section-title");

        Runnable recompute = () -> {
            java.math.BigDecimal ing = parseDec(ingresos.getText());
            java.math.BigDecimal gas = parseDec(gastos.getText());
            java.math.BigDecimal ret = parseDec(retencionesPrev.getText());
            java.math.BigDecimal pag = parseDec(pagosPrev.getText());
            if (ing == null) ing = java.math.BigDecimal.ZERO;
            if (gas == null) gas = java.math.BigDecimal.ZERO;
            if (ret == null) ret = java.math.BigDecimal.ZERO;
            if (pag == null) pag = java.math.BigDecimal.ZERO;
            java.math.BigDecimal beneficio = ing.subtract(gas);
            // 20% del beneficio acumulado, menos retenciones y pagos anteriores
            java.math.BigDecimal pago = beneficio.multiply(new java.math.BigDecimal("0.20"))
                    .subtract(ret).subtract(pag).setScale(2, java.math.RoundingMode.HALF_UP);
            resultLabel.setText("Pago fraccionado a ingresar: " + pago.toPlainString() + " €");
        };
        for (TextField f : new TextField[]{ingresos, gastos, retencionesPrev, pagosPrev}) {
            f.textProperty().addListener((o, ov, nv) -> recompute.run());
        }
        recompute.run();

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(12));
        grid.add(new Label("Ingresos acumulados"), 0, 0); grid.add(ingresos, 1, 0);
        grid.add(new Label("Gastos acumulados"), 0, 1); grid.add(gastos, 1, 1);
        grid.add(new Label("Retenciones soportadas"), 0, 2); grid.add(retencionesPrev, 1, 2);
        grid.add(new Label("Pagos fraccionados previos"), 0, 3); grid.add(pagosPrev, 1, 3);
        grid.add(new Separator(), 0, 4, 2, 1);
        grid.add(resultLabel, 0, 5, 2, 1);
        grid.add(new Separator(), 0, 6, 2, 1);
        grid.add(new Label(t("tax.editor.status")), 0, 7); grid.add(statusCombo, 1, 7);
        grid.add(new Label(t("tax.editor.csv")), 0, 8); grid.add(csvField, 1, 8);
        installDialog(dialog, grid);

        dialog.showAndWait().ifPresent(bt -> {
            if (bt != saveBt) return;
            java.util.Map<String, String> data = new java.util.LinkedHashMap<>();
            data.put("ingresos", ingresos.getText().trim());
            data.put("gastos", gastos.getText().trim());
            data.put("retenciones", retencionesPrev.getText().trim());
            data.put("pagos_previos", pagosPrev.getText().trim());
            java.math.BigDecimal ing = parseDec(ingresos.getText());
            java.math.BigDecimal gas = parseDec(gastos.getText());
            java.math.BigDecimal ret = parseDec(retencionesPrev.getText());
            java.math.BigDecimal pag = parseDec(pagosPrev.getText());
            if (ing == null) ing = java.math.BigDecimal.ZERO;
            if (gas == null) gas = java.math.BigDecimal.ZERO;
            if (ret == null) ret = java.math.BigDecimal.ZERO;
            if (pag == null) pag = java.math.BigDecimal.ZERO;
            java.math.BigDecimal total = ing.subtract(gas).multiply(new java.math.BigDecimal("0.20"))
                    .subtract(ret).subtract(pag).setScale(2, java.math.RoundingMode.HALF_UP);
            saveFiling(existing, statusCombo.getValue(), encodeDataMap(data), total,
                    csvField.getText(), existing.notes(), java.util.List.of());
        });
    }

    private void saveFiling(com.benjagest.ui.model.TaxFilingEntry existing, String status,
                             String dataJson, java.math.BigDecimal total, String csv, String notes,
                             java.util.List<com.benjagest.ui.model.TaxModelEntry> catalog) {
        Task<com.benjagest.ui.model.TaxFilingEntry> task = new Task<>() {
            @Override
            protected com.benjagest.ui.model.TaxFilingEntry call() throws Exception {
                return altaApiClient.updateFiling(existing.id(), existing.taxModelCode(),
                        existing.periodYear(), existing.periodQuarter(), existing.periodMonth(),
                        status, dataJson, total,
                        blankToNullOrSelf(csv),
                        blankToNullOrSelf(notes));
            }
        };
        task.setOnSucceeded(ev -> showTaxModels());
        task.setOnFailed(ev -> showError(t("tax.editor.fail.title"), t("tax.editor.fail.body")));
        start(task, "tax-filing-save");
    }

    private java.math.BigDecimal parseDec(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new java.math.BigDecimal(s.trim().replace(",", ".")); }
        catch (NumberFormatException ex) { return null; }
    }

    private java.math.BigDecimal sum(String... values) {
        java.math.BigDecimal acc = java.math.BigDecimal.ZERO;
        for (String v : values) {
            java.math.BigDecimal d = parseDec(v);
            if (d != null) acc = acc.add(d);
        }
        return acc;
    }

    /**
     * Mini-parser: convierte el JSON crudo de `data` en un Map<String,String>
     * para poblar las casillas del editor. No es un parser real — asume
     * un objeto plano con claves y valores string-o-number.
     */
    private java.util.Map<String, String> parseDataMap(String json) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        var p = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*(?:\"([^\"]*)\"|(-?\\d+(?:\\.\\d+)?))");
        var m = p.matcher(json);
        while (m.find()) {
            String key = m.group(1);
            String val = m.group(2) != null ? m.group(2) : m.group(3);
            out.put(key, val);
        }
        return out;
    }

    private String encodeDataMap(java.util.Map<String, String> data) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : data.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":\"")
                    .append(e.getValue() == null ? "" : e.getValue().replace("\"", "\\\""))
                    .append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
