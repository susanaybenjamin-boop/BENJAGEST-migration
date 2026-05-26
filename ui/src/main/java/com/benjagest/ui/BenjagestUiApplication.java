package com.benjagest.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.kordamp.ikonli.javafx.FontIcon;

import com.benjagest.ui.model.BackendStatus;
import com.benjagest.ui.model.CustomerCreateRequest;
import com.benjagest.ui.model.CustomerSummary;
import com.benjagest.ui.service.BackendStatusService;
import com.benjagest.ui.service.CustomerApiClient;

import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

public class BenjagestUiApplication extends Application {

    private final BackendStatusService backendStatusService = new BackendStatusService();
    private final CustomerApiClient customerApiClient = new CustomerApiClient();
    private final Map<String, Button> navigationButtons = new HashMap<>();
    private BorderPane root;

    @Override
    public void start(Stage stage) {
        root = new BorderPane();
        root.getStyleClass().add("app-root");

        VBox sidebar = createSidebar();
        root.setTop(createTopArea());
        root.setLeft(sidebar);
        root.setCenter(createDashboardScroll());
        root.setBottom(createFooter());


        Scene scene = new Scene(root, 1100, 700);
        scene.getStylesheets().add(getClass().getResource("/com/benjagest/ui/app.css").toExternalForm());
        scene.widthProperty().addListener((observable, oldValue, newValue) -> {
            root.setLeft(newValue.doubleValue() < 760 ? null : sidebar);
        });

        stage.setTitle("BENJAGEST");
        stage.getIcons().add(AppBrand.loadWindowIcon());
        stage.setMinWidth(420);
        stage.setMinHeight(620);
        stage.setScene(scene);
        stage.show();
    }

    private VBox createTopArea() {
        MenuBar menuBar = new MenuBar();
        menuBar.getStyleClass().add("app-menu");

        MenuItem checkBackendItem = new MenuItem("Comprobar servicio");
        checkBackendItem.setOnAction(event -> checkBackend());
        MenuItem exitItem = new MenuItem("Salir");
        exitItem.setOnAction(event -> System.exit(0));

        Menu fileMenu = new Menu("Archivo");
        fileMenu.getItems().addAll(checkBackendItem, exitItem);

        MenuItem dashboardItem = new MenuItem("Panel principal");
        dashboardItem.setOnAction(event -> showDashboard());
        MenuItem systemStatusItem = new MenuItem("Resumen");
        systemStatusItem.setOnAction(event -> showActionDialog("Resumen de actividad"));
        Menu viewMenu = new Menu("Vista");
        viewMenu.getItems().addAll(dashboardItem, systemStatusItem);

        MenuItem aboutItem = new MenuItem("Acerca de BENJAGEST");
        aboutItem.setOnAction(event -> showAboutDialog());
        Menu helpMenu = new Menu("Ayuda");
        helpMenu.getItems().add(aboutItem);

        menuBar.getMenus().addAll(fileMenu, viewMenu, helpMenu);

        Label title = new Label("BENJAGEST");
        title.getStyleClass().add("app-title");

        Label subtitle = new Label("Gestion para empresas de construccion");
        subtitle.getStyleClass().add("app-subtitle");

        VBox titleBlock = new VBox(2, title, subtitle);
        titleBlock.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(14, AppBrand.createLogoMark(), titleBlock, spacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("app-header");

        return new VBox(menuBar, header);
    }

    private VBox createSidebar() {
        VBox sidebar = new VBox(8);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(230);

        Label section = new Label("Modulos");
        section.getStyleClass().add("sidebar-section");

        sidebar.getChildren().add(section);
        sidebar.getChildren().addAll(
                navItem("Inicio", "home", true, this::showDashboard),
                navItem("Clientes", "clients", false, () -> showModule("Clientes", "clients", "Empresas, contactos y datos fiscales.", "module-teal")),
                navItem("Facturacion", "invoice", false, () -> showModule("Facturacion", "invoice", "Facturas, proformas, cobros y gastos.", "module-blue")),
                navItem("Laboral", "labor", false, () -> showModule("Laboral", "labor", "Empleados, jornadas, nominas y fichajes.", "module-violet")),
                navItem("Fiscal", "tax", false, () -> showModule("Fiscal", "tax", "Modelos, cierres y certificados.", "module-red")),
                navItem("Informes", "reports", false, () -> showModule("Informes", "reports", "Indicadores y reportes operativos.", "module-green")),
                navItem("Configuracion", "settings", false, () -> showModule("Configuracion", "settings", "Empresa, usuarios y permisos.", "module-slate"))
        );

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox accountBox = new VBox(4);
        accountBox.getStyleClass().add("sidebar-account");
        accountBox.getChildren().addAll(label("Sesion", "account-caption"), label("Administrador", "account-title"));

        sidebar.getChildren().addAll(spacer, accountBox);
        return sidebar;
    }

    private Button navItem(String text, String icon, boolean selected, Runnable action) {
        Button button = new Button(text);
        button.setGraphic(sidebarIcon(icon));
        button.getStyleClass().add("nav-item");
        if (selected) {
            button.getStyleClass().add("nav-item-selected");
        }
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(event -> {
            selectNavigation(text);
            action.run();
        });
        navigationButtons.put(text, button);
        return button;
    }

    private void selectNavigation(String text) {
        navigationButtons.values().forEach(button -> button.getStyleClass().remove("nav-item-selected"));
        Button selected = navigationButtons.get(text);
        if (selected != null && !selected.getStyleClass().contains("nav-item-selected")) {
            selected.getStyleClass().add("nav-item-selected");
        }
    }

    private void showDashboard() {
        if (root != null) {
            selectNavigation("Inicio");
            root.setCenter(createDashboardScroll());
        }
    }

    private void showModule(String title, String icon, String description, String colorClass) {
        if (root != null) {
            selectNavigation(title);
            root.setCenter(createModuleScroll(title, icon, description, colorClass));
        }
    }

    private ScrollPane createDashboardScroll() {
        ScrollPane scrollPane = new ScrollPane(createDashboard());
        scrollPane.getStyleClass().add("content-scroll");
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scrollPane;
    }

    private VBox createDashboard() {
        VBox content = new VBox(22);
        content.getStyleClass().add("content");

        Label modulesTitle = new Label("Areas de gestion");
        modulesTitle.getStyleClass().add("section-title");

        TilePane modules = createModuleGrid();

        content.getChildren().addAll(createWelcomePanel(), modulesTitle, modules);
        return content;
    }

    private ScrollPane createModuleScroll(String title, String icon, String description, String colorClass) {
        ScrollPane scrollPane = new ScrollPane(createModuleDetail(title, icon, description, colorClass));
        scrollPane.getStyleClass().add("content-scroll");
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scrollPane;
    }

    private VBox createModuleDetail(String title, String icon, String description, String colorClass) {
        VBox content = new VBox(22);
        content.getStyleClass().add("content");

        StackPane bubble = iconBubble(icon, "module-detail-icon", colorClass);
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("module-detail-title");

        Label descriptionLabel = new Label(description);
        descriptionLabel.getStyleClass().add("module-detail-description");
        descriptionLabel.setWrapText(true);

        VBox copy = new VBox(8, titleLabel, descriptionLabel);
        copy.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(copy, Priority.ALWAYS);

        HBox header = new HBox(18, bubble, copy);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("module-detail-header");

        Label actionsTitle = new Label("Acciones principales");
        actionsTitle.getStyleClass().add("section-title");

        TilePane actions = new TilePane();
        actions.getStyleClass().add("launcher-grid");
        actions.setHgap(12);
        actions.setVgap(12);
        actions.setPrefTileWidth(134);
        actions.setPrefTileHeight(132);
        actions.getChildren().addAll(createModuleActions(title));

        Label infoTitle = new Label("Actividad");
        infoTitle.getStyleClass().add("section-title");

        VBox activity = new VBox(10,
                quickLine("Pendiente de revisar", "tasks", "--"),
                quickLine("Ultimos movimientos", "reports", "--"),
                quickLine("Avisos", "alert", "--")
        );
        activity.getStyleClass().add("summary-card");

        content.getChildren().addAll(header, actionsTitle, actions, infoTitle, activity);
        return content;
    }

    private Node[] createModuleActions(String module) {
        return switch (module) {
            case "Clientes" -> new Node[]{
                    actionTile("Nuevo cliente", "client-plus", "Alta"),
                    actionTile("Buscar cliente", "clients", "Buscar"),
                    actionTile("Datos fiscales", "tax", "Fiscal")
            };
            case "Facturacion" -> new Node[]{
                    actionTile("Nueva factura", "invoice-plus", "Crear"),
                    actionTile("Ver cobros", "cash", "Cobros"),
                    actionTile("Registrar gasto", "expense", "Gasto")
            };
            case "Laboral" -> new Node[]{
                    actionTile("Registrar jornada", "calendar", "Hoy"),
                    actionTile("Empleados", "labor", "Equipo"),
                    actionTile("Avisos", "alert", "Avisos")
            };
            case "Fiscal" -> new Node[]{
                    actionTile("Modelos", "tax", "Fiscal"),
                    actionTile("Calendario", "calendar", "Fechas"),
                    actionTile("Informes", "reports", "Datos")
            };
            case "Informes" -> new Node[]{
                    actionTile("Panel de datos", "reports", "Datos"),
                    actionTile("Cobros", "cash", "Cobros"),
                    actionTile("Tareas", "tasks", "Tareas")
            };
            case "Configuracion" -> new Node[]{
                    actionTile("Empresa", "settings", "Ajustes"),
                    actionTile("Usuarios", "clients", "Usuarios"),
                    actionTile("Permisos", "settings", "Permisos")
            };
            default -> new Node[]{actionTile("Volver", "home", "Inicio")};
        };
    }

    private HBox createWelcomePanel() {
        Label title = new Label("Panel de trabajo");
        title.getStyleClass().add("hero-title");

        Label body = new Label("Gestiona clientes, facturas, cobros, tareas y actividad desde un unico punto.");
        body.getStyleClass().add("hero-body");
        body.setWrapText(true);

        TilePane metrics = new TilePane();
        metrics.getChildren().addAll(
                metric("cash", "Facturado", "0,00", "metric-green"),
                metric("alert", "Pendiente", "0", "metric-amber"),
                metric("tasks", "Tareas", "0", "metric-blue")
        );
        metrics.getStyleClass().add("metric-row");
        metrics.setHgap(12);
        metrics.setVgap(12);
        metrics.setPrefTileWidth(150);
        metrics.setPrefTileHeight(88);

        TilePane actions = createActionLauncher();

        VBox textPanel = new VBox(18, title, body, metrics);
        textPanel.getStyleClass().add("hero-copy");
        HBox.setHgrow(textPanel, Priority.ALWAYS);

        VBox actionPanel = new VBox(16, label("Accesos rapidos", "card-title"), actions, createTodayPanel());
        actionPanel.getStyleClass().add("hero-actions");
        actionPanel.setMinWidth(360);
        actionPanel.setPrefWidth(430);

        HBox panel = new HBox(28, textPanel, actionPanel);
        panel.getStyleClass().add("hero-panel");
        HBox.setHgrow(panel, Priority.ALWAYS);
        return panel;
    }

    private VBox createTodayPanel() {
        VBox panel = new VBox(10,
                quickLine("Clientes", "clients", "--"),
                quickLine("Cobros", "cash", "--"),
                quickLine("Avisos", "alert", "--")
        );
        panel.getStyleClass().add("summary-card");
        panel.setPrefWidth(320);
        panel.setMinWidth(280);
        return panel;
    }

    private HBox quickLine(String title, String icon, String value) {
        StackPane bubble = iconBubble(icon, "small-round-icon");
        Label titleLabel = label(title, "quick-line-title");
        Label valueLabel = label(value, "quick-line-value");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10, bubble, titleLabel, spacer, valueLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("quick-line");
        row.setCursor(Cursor.HAND);
        row.setOnMouseClicked(event -> showActionDialog(title));
        return row;
    }

    private TilePane createActionLauncher() {
        TilePane launcher = new TilePane();
        launcher.getStyleClass().add("launcher-grid");
        launcher.setHgap(12);
        launcher.setVgap(12);
        launcher.setPrefTileWidth(118);
        launcher.setPrefTileHeight(132);
        launcher.getChildren().addAll(
                actionTile("Nueva factura", "invoice-plus", "Crear"),
                actionTile("Nuevo cliente", "client-plus", "Alta"),
                actionTile("Registrar gasto", "expense", "Gasto"),
                actionTile("Ver cobros", "cash", "Cobros"),
                actionTile("Agenda", "calendar", "Hoy"),
                actionTile("Informes", "reports", "Datos")
        );
        return launcher;
    }

    private TilePane createModuleGrid() {
        TilePane grid = new TilePane();
        grid.setHgap(16);
        grid.setVgap(16);
        grid.getStyleClass().add("module-grid");
        grid.setPrefTileWidth(300);
        grid.setPrefTileHeight(164);

        grid.getChildren().addAll(
                moduleCard("Clientes", "clients", "Empresas, contactos y datos fiscales.", "module-teal"),
                moduleCard("Facturacion", "invoice", "Facturas, proformas, cobros y gastos.", "module-blue"),
                moduleCard("Laboral", "labor", "Empleados, jornadas, nominas y fichajes.", "module-violet"),
                moduleCard("Fiscal", "tax", "Modelos, cierres y certificados.", "module-red"),
                moduleCard("Informes", "reports", "Indicadores y reportes operativos.", "module-green"),
                moduleCard("Configuracion", "settings", "Empresa, usuarios y permisos.", "module-slate")
        );

        return grid;
    }

    private VBox moduleCard(String title, String icon, String description, String colorClass) {
        StackPane bubble = iconBubble(icon, "icon-bubble", colorClass);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("module-title");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        StackPane arrow = moduleArrow();

        HBox moduleHeader = new HBox(12, bubble, titleLabel, headerSpacer, arrow);
        moduleHeader.setAlignment(Pos.CENTER_LEFT);

        Label descriptionLabel = new Label(description);
        descriptionLabel.getStyleClass().add("module-description");
        descriptionLabel.setWrapText(true);

        VBox card = new VBox(14, moduleHeader, descriptionLabel);
        card.getStyleClass().add("module-card");
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(event -> showModule(title, icon, description, colorClass));
        return card;
    }

    private VBox metric(String icon, String label, String value, String colorClass) {
        HBox header = new HBox(8, miniIcon(icon, colorClass), label(label, "metric-label"));
        header.setAlignment(Pos.CENTER_LEFT);
        VBox metric = new VBox(8, header, label(value, "metric-value"));
        metric.getStyleClass().add("metric-card");
        return metric;
    }

    private VBox actionTile(String text, String icon, String caption) {
        StackPane bubble = iconBubble(icon, "launcher-icon");
        VBox tile = new VBox(12, bubble, label(caption, "action-caption"), label(text, "action-title"));
        tile.getStyleClass().add("action-tile");
        tile.setAlignment(Pos.CENTER);
        tile.setCursor(Cursor.HAND);
        tile.setOnMouseClicked(event -> showActionDialog(text));
        return tile;
    }

    private StackPane iconBubble(String iconName, String... styleClasses) {
        StackPane bubble = new StackPane(createIconGraphic(iconName));
        bubble.getStyleClass().addAll(styleClasses);
        return bubble;
    }

    private StackPane moduleArrow() {
        Polygon arrowShape = polygon(new double[]{-3, -7, 5, 0, -3, 7});
        arrowShape.getStyleClass().setAll("module-arrow-shape");
        StackPane arrow = new StackPane(arrowShape);
        arrow.getStyleClass().add("module-arrow");
        return arrow;
    }

    private StackPane miniIcon(String iconName, String colorClass) {
        StackPane icon = new StackPane(createIconGraphic(iconName));
        icon.getStyleClass().addAll("mini-icon", colorClass);
        return icon;
    }

    private StackPane sidebarIcon(String iconName) {
        StackPane icon = new StackPane(createIconGraphic(iconName));
        icon.getStyleClass().add("sidebar-icon");
        return icon;
    }

    private StackPane createIconGraphic(String iconName) {
        String iconLiteral = iconLiteral(iconName);
        if (iconLiteral != null) {
            try {
                FontIcon icon = new FontIcon(iconLiteral);
                icon.getStyleClass().add("font-icon");
                return iconGraphic(icon);
            } catch (RuntimeException ignored) {
                // Fallback to local vector shapes if an icon literal is unavailable.
            }
        }

        return switch (iconName) {
            case "home" -> homeIcon();
            case "clients", "client-plus" -> clientsIcon(iconName.equals("client-plus"));
            case "invoice", "invoice-plus" -> invoiceIcon(iconName.equals("invoice-plus"));
            case "expense" -> expenseIcon();
            case "cash" -> cashIcon();
            case "calendar" -> calendarIcon();
            case "labor" -> laborIcon();
            case "tax" -> taxIcon();
            case "reports" -> reportsIcon();
            case "settings" -> settingsIcon();
            case "alert" -> alertIcon();
            case "tasks" -> tasksIcon();
            default -> reportsIcon();
        };
    }

    private String iconLiteral(String iconName) {
        return switch (iconName) {
            case "home" -> "fas-house";
            case "clients" -> "fas-users";
            case "client-plus" -> "fas-user-plus";
            case "invoice" -> "fas-file-invoice";
            case "invoice-plus" -> "fas-file-circle-plus";
            case "expense" -> "fas-receipt";
            case "cash" -> "fas-money-bill-wave";
            case "calendar" -> "fas-calendar-days";
            case "labor" -> "fas-helmet-safety";
            case "tax" -> "fas-percent";
            case "reports" -> "fas-chart-column";
            case "settings" -> "fas-gear";
            case "alert" -> "fas-circle-exclamation";
            case "tasks" -> "fas-list-check";
            default -> null;
        };
    }

    private StackPane homeIcon() {
        Polygon roof = polygon(new double[]{0, -13, 15, 0, 10, 0, 10, 13, -10, 13, -10, 0, -15, 0});
        Rectangle door = rect(6, 11, 0, 7);
        return iconGraphic(roof, door);
    }

    private StackPane clientsIcon(boolean withPlus) {
        StackPane icon = iconGraphic(
                circle(6, 0, -9),
                circle(4.5, -10, -5),
                circle(4.5, 10, -5),
                rect(18, 11, 0, 8),
                rect(10, 8, -10, 10),
                rect(10, 8, 10, 10)
        );
        if (withPlus) {
            icon.getChildren().addAll(line(-18, 13, -8, 13), line(-13, 8, -13, 18));
        }
        return icon;
    }

    private StackPane invoiceIcon(boolean withPlus) {
        StackPane icon = iconGraphic(
                outlineRect(22, 28, 0, 0),
                line(-7, -7, 7, -7),
                line(-7, 0, 7, 0),
                line(-7, 7, 3, 7)
        );
        if (withPlus) {
            icon.getChildren().addAll(line(8, 10, 18, 10), line(13, 5, 13, 15));
        }
        return icon;
    }

    private StackPane expenseIcon() {
        Polygon receipt = polygon(new double[]{-12, -15, 12, -15, 12, 13, 6, 9, 0, 13, -6, 9, -12, 13});
        receipt.getStyleClass().setAll("icon-outline-fill");
        Line down = line(0, -6, 0, 7);
        Polygon arrow = polygon(new double[]{-6, 3, 0, 10, 6, 3});
        return iconGraphic(receipt, down, arrow);
    }

    private StackPane cashIcon() {
        return iconGraphic(
                circle(9, -6, 4),
                circle(9, 5, -3),
                line(-4, -3, 8, -3),
                line(-4, 2, 8, 2)
        );
    }

    private StackPane calendarIcon() {
        return iconGraphic(
                outlineRect(26, 24, 0, 2),
                line(-13, -6, 13, -6),
                line(-6, 2, -6, 11),
                line(3, 2, 3, 11),
                line(-11, 6, 11, 6),
                line(-8, -16, -8, -10),
                line(8, -16, 8, -10)
        );
    }

    private StackPane laborIcon() {
        return iconGraphic(
                rect(22, 12, 0, 7),
                line(-13, 2, 13, 2),
                line(-7, 2, -3, -10),
                line(7, 2, 3, -10),
                rect(16, 8, 0, -9)
        );
    }

    private StackPane taxIcon() {
        return iconGraphic(
                outlineRect(23, 28, -2, 0),
                circle(4, -7, -7),
                circle(4, 7, 7),
                line(-8, 10, 8, -10)
        );
    }

    private StackPane reportsIcon() {
        return iconGraphic(
                rect(5, 14, -10, 6),
                rect(5, 22, 0, 2),
                rect(5, 30, 10, -2),
                line(-16, 16, 16, 16)
        );
    }

    private StackPane settingsIcon() {
        StackPane icon = iconGraphic(
                circle(8, 0, 0),
                circle(3, 0, 0),
                line(-16, 0, -9, 0),
                line(9, 0, 16, 0),
                line(0, -16, 0, -9),
                line(0, 9, 0, 16),
                line(-12, -12, -7, -7),
                line(7, 7, 12, 12),
                line(12, -12, 7, -7),
                line(-7, 7, -12, 12)
        );
        return icon;
    }

    private StackPane alertIcon() {
        return iconGraphic(
                line(0, -13, 0, 4),
                circle(2.5, 0, 12)
        );
    }

    private StackPane tasksIcon() {
        return iconGraphic(
                outlineRect(24, 26, 0, 0),
                line(-7, -6, -3, -2),
                line(-3, -2, 7, -10),
                line(-7, 6, -3, 10),
                line(-3, 10, 7, 2)
        );
    }

    private StackPane iconGraphic(Node... shapes) {
        StackPane graphic = new StackPane(shapes);
        graphic.getStyleClass().add("icon-graphic");
        return graphic;
    }

    private Rectangle rect(double width, double height, double translateX, double translateY) {
        Rectangle rectangle = new Rectangle(width, height);
        rectangle.setTranslateX(translateX);
        rectangle.setTranslateY(translateY);
        rectangle.getStyleClass().add("icon-fill");
        return rectangle;
    }

    private Rectangle outlineRect(double width, double height, double translateX, double translateY) {
        Rectangle rectangle = new Rectangle(width, height);
        rectangle.setTranslateX(translateX);
        rectangle.setTranslateY(translateY);
        rectangle.getStyleClass().add("icon-outline");
        return rectangle;
    }

    private Circle circle(double radius, double translateX, double translateY) {
        Circle circle = new Circle(radius);
        circle.setTranslateX(translateX);
        circle.setTranslateY(translateY);
        circle.getStyleClass().add("icon-fill");
        return circle;
    }

    private Line line(double startX, double startY, double endX, double endY) {
        Line line = new Line(startX, startY, endX, endY);
        line.getStyleClass().add("icon-stroke");
        return line;
    }

    private Polygon polygon(double[] points) {
        Polygon polygon = new Polygon(points);
        polygon.getStyleClass().add("icon-fill");
        return polygon;
    }

    private HBox createFooter() {
        Label left = new Label("BENJAGEST");
        left.getStyleClass().add("footer-text");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label right = new Label("Listo");
        right.getStyleClass().add("footer-text");

        HBox footer = new HBox(12, left, spacer, right);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("footer");
        return footer;
    }

    private Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private void checkBackend() {
        Task<BackendStatus> task = new Task<>() {
            @Override
            protected BackendStatus call() {
                return backendStatusService.fetchHealth();
            }
        };

        task.setOnSucceeded(event -> {
            BackendStatus status = task.getValue();
            showServiceStatusDialog(status.reachable() ? "Conexion operativa" : "Sin conexion", status.message());
        });
        task.setOnFailed(event -> {
            showServiceStatusDialog("No se pudo comprobar", "La comprobacion fallo antes de recibir respuesta del servicio.");
        });

        Thread worker = new Thread(task, "service-health-check");
        worker.setDaemon(true);
        worker.start();
    }

    private void showAboutDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "BENJAGEST\nGestion empresarial.", ButtonType.OK);
        alert.setTitle("Acerca de BENJAGEST");
        alert.setHeaderText("BENJAGEST");
        alert.showAndWait();
    }

    private void showActionDialog(String action) {
        if ("Nuevo cliente".equals(action)) {
            showNewCustomerDialog();
            return;
        }
        if ("Buscar cliente".equals(action) || "Clientes".equals(action)) {
            loadCustomers();
            return;
        }

        Alert alert = new Alert(Alert.AlertType.INFORMATION, "Accion seleccionada: " + action, ButtonType.OK);
        alert.setTitle("BENJAGEST");
        alert.setHeaderText(action);
        alert.showAndWait();
    }

    private void showNewCustomerDialog() {
        Dialog<CustomerCreateRequest> dialog = new Dialog<>();
        dialog.setTitle("BENJAGEST");
        dialog.setHeaderText("Nuevo cliente");

        TextField legalName = new TextField();
        legalName.setPromptText("Nombre fiscal");
        TextField tradeName = new TextField();
        tradeName.setPromptText("Nombre comercial");
        TextField taxIdentifier = new TextField();
        taxIdentifier.setPromptText("CIF/NIF");
        TextField contactName = new TextField();
        contactName.setPromptText("Persona de contacto");
        TextField email = new TextField();
        email.setPromptText("Correo");
        TextField phone = new TextField();
        phone.setPromptText("Telefono");

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.addRow(0, new Label("Nombre fiscal"), legalName);
        form.addRow(1, new Label("Nombre comercial"), tradeName);
        form.addRow(2, new Label("CIF/NIF"), taxIdentifier);
        form.addRow(3, new Label("Contacto"), contactName);
        form.addRow(4, new Label("Correo"), email);
        form.addRow(5, new Label("Telefono"), phone);

        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            return new CustomerCreateRequest(
                    legalName.getText(),
                    tradeName.getText(),
                    taxIdentifier.getText(),
                    contactName.getText(),
                    email.getText(),
                    phone.getText()
            );
        });

        Optional<CustomerCreateRequest> result = dialog.showAndWait();
        result.ifPresent(request -> {
            if (isBlank(request.legalName()) || isBlank(request.taxIdentifier())) {
                showErrorDialog("Faltan datos", "El nombre fiscal y el CIF/NIF son obligatorios.");
                return;
            }
            createCustomer(request);
        });
    }

    private void createCustomer(CustomerCreateRequest request) {
        Task<CustomerSummary> task = new Task<>() {
            @Override
            protected CustomerSummary call() throws Exception {
                return customerApiClient.create(request);
            }
        };

        task.setOnSucceeded(event -> {
            CustomerSummary customer = task.getValue();
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "Cliente guardado correctamente:\n" + customer.legalName(),
                    ButtonType.OK);
            alert.setTitle("BENJAGEST");
            alert.setHeaderText("Cliente creado");
            alert.showAndWait();
        });
        task.setOnFailed(event -> showErrorDialog(
                "No se pudo guardar el cliente",
                "Comprueba que el servicio esta iniciado y que la base de datos esta disponible."
        ));

        Thread worker = new Thread(task, "customer-create");
        worker.setDaemon(true);
        worker.start();
    }

    private void loadCustomers() {
        Task<List<CustomerSummary>> task = new Task<>() {
            @Override
            protected List<CustomerSummary> call() throws Exception {
                return customerApiClient.list();
            }
        };

        task.setOnSucceeded(event -> {
            List<CustomerSummary> customers = task.getValue();
            String message = customers.isEmpty()
                    ? "Todavia no hay clientes registrados."
                    : customerListMessage(customers);
            Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
            alert.setTitle("BENJAGEST");
            alert.setHeaderText("Clientes");
            alert.showAndWait();
        });
        task.setOnFailed(event -> showErrorDialog(
                "No se pudieron cargar los clientes",
                "Comprueba que el servicio esta iniciado y que la base de datos esta disponible."
        ));

        Thread worker = new Thread(task, "customer-list");
        worker.setDaemon(true);
        worker.start();
    }

    private String customerListMessage(List<CustomerSummary> customers) {
        StringBuilder message = new StringBuilder();
        customers.stream().limit(10).forEach(customer -> {
            message.append("- ").append(customer.legalName());
            if (!isBlank(customer.taxIdentifier())) {
                message.append(" (").append(customer.taxIdentifier()).append(")");
            }
            message.append("\n");
        });
        if (customers.size() > 10) {
            message.append("\nMostrando 10 de ").append(customers.size()).append(" clientes.");
        }
        return message.toString();
    }

    private void showErrorDialog(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle("BENJAGEST");
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void showServiceStatusDialog(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setTitle("Estado del servicio");
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
