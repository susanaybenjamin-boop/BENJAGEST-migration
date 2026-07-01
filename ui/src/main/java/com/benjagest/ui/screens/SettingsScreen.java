package com.benjagest.ui.screens;

import com.benjagest.ui.model.AppMode;
import com.benjagest.ui.model.CompanyData;
import com.benjagest.ui.model.CompanyModuleEntry;
import com.benjagest.ui.model.EmailConfig;
import com.benjagest.ui.model.Language;
import com.benjagest.ui.service.AltaApiClient;
import com.benjagest.ui.service.AuthApiClient;
import com.benjagest.ui.service.CertificateApiClient;
import com.benjagest.ui.service.SettingsApiClient;
import com.benjagest.ui.support.Router;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * SM-2 — Módulo Configuración/Settings (bloque UIR). Extraído del God Object como
 * movimiento puro (mismas claves i18n y CSS, mismo comportamiento). SM-2a mueve el
 * NÚCLEO (empresa, correo + integración Google, módulos, certificados, sesión/PIN,
 * about) + showSettings/settingsView. Las pestañas pesadas (owners, credenciales,
 * auditoría, backup, mi-asesoría, mi-tpb, boe) siguen en el shell y llegan por
 * {@link Host} hasta SM-2b, que las mueve aquí.
 *
 * <p>Todo lo que toca ESTADO del shell (traer ventana al frente, comprobar
 * actualización, timeout de bloqueo, estilo del salvapantallas, nombre de
 * empresa de la sesión, refresco del shell al renombrar empresa o guardar
 * módulos) se delega vía {@link Host}. Los factories puros de UI
 * (textInput/settingsSection/passwordWithToggle) se copian aquí (se comparten
 * con login/perfil, que aún viven en el shell).
 */
public class SettingsScreen extends ScreenBase {

    /** Puente hacia el estado/navegación del shell y las pestañas aún no movidas. */
    public interface Host {
        void bringToFront();
        javafx.stage.Window ownerWindow();
        void checkForUpdates(boolean manual);
        void refreshLockTimeout(int newTimeoutMin);
        void setScreensaverStyle(String style);
        String sessionCompanyName();
        void onCompanyRenamed(String legalName);
        void onModulesSaved(List<CompanyModuleEntry> catalog);
        // Pestañas pesadas (SM-2b las moverá a esta clase y estos métodos desaparecerán).
        Node auditTab();
        Node backupTab();
        Node myAdvisoryTab();
        Node myTpbTab();
        Node boeAlertsTab();
    }

    private final SettingsApiClient settingsApiClient;
    private final AuthApiClient authApiClient;
    private final CertificateApiClient certificateApi;
    private final AltaApiClient altaApiClient;
    private final AppMode appMode;
    private final Language language;
    private final Host host;

    // Campos de la pestaña Módulos (movidos del shell).
    private final java.util.Map<String, Boolean> pendingModuleChanges = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, Boolean> moduleBaselineState = new java.util.LinkedHashMap<>();
    private Button saveModulesButton;
    private Label modulesDirtyHint;

    public SettingsScreen(SettingsApiClient settingsApiClient, AuthApiClient authApiClient,
                          CertificateApiClient certificateApi, AltaApiClient altaApiClient,
                          AppMode appMode, Language language,
                          Function<String, String> tt, Router router, Host host) {
        super(tt, router);
        this.settingsApiClient = settingsApiClient;
        this.authApiClient = authApiClient;
        this.certificateApi = certificateApi;
        this.altaApiClient = altaApiClient;
        this.appMode = appMode;
        this.language = language;
        this.host = host;
    }

    // ---- delegados a estado del shell (mismas firmas que el código movido) ----
    private void bringToFront() { host.bringToFront(); }
    private void checkForUpdates(boolean manual) { host.checkForUpdates(manual); }
    private void refreshLockTimeout(int newTimeoutMin) { host.refreshLockTimeout(newTimeoutMin); }

    // ---- factories puros copiados del shell (se comparten con login/perfil) ----
    private TextField textInput(String value, String prompt) {
        TextField field = new TextField(value == null ? "" : value);
        field.setPromptText(prompt);
        return field;
    }

    private VBox settingsSection(String title, String hint) {
        VBox box = new VBox(8);
        box.getStyleClass().add("settings-section");
        Label t = new Label(title);
        t.getStyleClass().add("settings-section-title");
        Label h = new Label(hint);
        h.setWrapText(true);
        h.getStyleClass().add("settings-hint");
        box.getChildren().addAll(t, h);
        return box;
    }

    private Node passwordWithToggle(PasswordField pf) {
        TextField visible = new TextField();
        visible.setPromptText(pf.getPromptText());
        visible.setManaged(false);
        visible.setVisible(false);
        visible.textProperty().bindBidirectional(pf.textProperty());
        javafx.scene.layout.StackPane stack = new javafx.scene.layout.StackPane(pf, visible);
        HBox.setHgrow(stack, Priority.ALWAYS);

        javafx.scene.control.ToggleButton eye = new javafx.scene.control.ToggleButton();
        eye.setGraphic(icon("fas-eye"));
        eye.setFocusTraversable(false);
        eye.setTooltip(new javafx.scene.control.Tooltip(t("password.toggle")));
        eye.setOnAction(e -> {
            boolean show = eye.isSelected();
            pf.setVisible(!show); pf.setManaged(!show);
            visible.setVisible(show); visible.setManaged(show);
            eye.setGraphic(icon(show ? "fas-eye-slash" : "fas-eye"));
        });

        HBox box = new HBox(4, stack, eye);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private static String humanizeBackendError(String raw) {
        return com.benjagest.ui.support.BackendErrors.humanize(raw);
    }

    // ---- helpers de layout/formulario copiados del shell (compartidos) ----
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

    private void addFormRow(GridPane grid, int row, String labelText, javafx.scene.Node input) {
        Label fieldLabel = new Label(labelText);
        fieldLabel.getStyleClass().add("form-label");
        grid.add(fieldLabel, 0, row);
        grid.add(input, 1, row);
        GridPane.setHgrow(input, Priority.ALWAYS);
    }

    // ==== cuerpo movido del monolito (SM-2a) ====

    public void showSettings() {
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

    /**
     * REG-3 — Configuración → Integraciones → Google. El admin pega el Client ID
     * y el Client Secret de SU proyecto Google (tipo "Aplicación de escritorio").
     * El secreto se guarda cifrado en el backend y nunca se vuelve a mostrar.
     */
    /**
     * Panel de conexión con Google (Gmail + Calendar) reutilizable. Vive
     * dentro del tab Correo, visible solo cuando el proveedor elegido es
     * Google. La configuración de "mi propio proyecto Google" (client-id/
     * secret per-instalación) queda plegada como avanzado: por defecto se
     * usan las credenciales centrales de BENJAGEST.
     */
    private VBox googleIntegrationPanel() {
        Label title = new Label(t("settings.integrations.google.title"));
        title.getStyleClass().add("settings-section-title");
        Label hint = new Label(t("settings.integrations.google.hint"));
        hint.getStyleClass().add("settings-hint"); hint.setWrapText(true); hint.setMaxWidth(620);
        Label steps = new Label(t("settings.integrations.google.steps"));
        steps.getStyleClass().add("settings-hint"); steps.setWrapText(true); steps.setMaxWidth(620);

        TextField clientId = new TextField();
        clientId.setPromptText("Client ID (…apps.googleusercontent.com)");
        clientId.setMaxWidth(Double.MAX_VALUE);
        PasswordField clientSecret = new PasswordField();
        clientSecret.setPromptText(t("settings.integrations.google.secret_prompt"));
        Label status = new Label();
        status.getStyleClass().add("settings-hint");

        Button save = new Button(t("settings.integrations.google.save"));
        save.getStyleClass().add("button-primary");

        Runnable loadStatus = () -> {
            Task<com.benjagest.ui.service.AuthApiClient.GoogleConfig> tk = new Task<>() {
                @Override protected com.benjagest.ui.service.AuthApiClient.GoogleConfig call() {
                    return authApiClient.googleConfig();
                }
            };
            tk.setOnSucceeded(e -> {
                var c = tk.getValue();
                if (c.clientId() != null) clientId.setText(c.clientId());
                status.setText(c.enabled()
                        ? t("settings.integrations.google.status.enabled")
                        : t("settings.integrations.google.status.disabled"));
            });
            start(tk, "google-cfg-load");
        };

        save.setOnAction(e -> {
            if (clientId.getText() == null || clientId.getText().isBlank()) {
                showError(t("settings.integrations.google.title"), t("settings.integrations.google.need_client_id"));
                return;
            }
            save.setDisable(true);
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception {
                    authApiClient.saveGoogleConfig(clientId.getText().trim(), clientSecret.getText());
                    return null;
                }
            };
            tk.setOnSucceeded(ev -> {
                save.setDisable(false); clientSecret.clear(); loadStatus.run();
                showInfo(t("settings.integrations.google.title"), t("settings.integrations.google.saved"));
            });
            tk.setOnFailed(ev -> { save.setDisable(false); showError(t("settings.integrations.google.title"),
                    humanizeBackendError(tk.getException() == null ? "" : tk.getException().getMessage())); });
            start(tk, "google-cfg-save");
        });

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8);
        g.add(new Label(t("settings.integrations.google.client_id")), 0, 0); g.add(clientId, 1, 0);
        g.add(new Label(t("settings.integrations.google.secret")), 0, 1); g.add(passwordWithToggle(clientSecret), 1, 1);
        javafx.scene.layout.ColumnConstraints c1 = new javafx.scene.layout.ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS); c1.setFillWidth(true);
        g.getColumnConstraints().addAll(new javafx.scene.layout.ColumnConstraints(), c1);

        // --- Conectar Gmail para enviar correo (sin contraseña de aplicación) ---
        Label gmailTitle = new Label(t("settings.integrations.gmail.title"));
        gmailTitle.getStyleClass().add("settings-section-title");
        Label gmailHint = new Label(t("settings.integrations.gmail.hint"));
        gmailHint.getStyleClass().add("settings-hint"); gmailHint.setWrapText(true); gmailHint.setMaxWidth(620);
        Label gmailStatusLbl = new Label();
        gmailStatusLbl.getStyleClass().add("settings-hint");
        Button connectGmailBtn = new Button(t("settings.integrations.gmail.connect"));
        connectGmailBtn.getStyleClass().add("button-primary");
        connectGmailBtn.setGraphic(icon("fab-google"));
        Button disconnectGmailBtn = new Button(t("settings.integrations.gmail.disconnect"));
        disconnectGmailBtn.getStyleClass().add("button-danger-outline");

        Runnable loadGmail = () -> {
            Task<com.benjagest.ui.service.AuthApiClient.GmailStatus> tk = new Task<>() {
                @Override protected com.benjagest.ui.service.AuthApiClient.GmailStatus call() throws Exception {
                    return authApiClient.gmailStatus();
                }
            };
            tk.setOnSucceeded(e -> {
                var s = tk.getValue();
                gmailStatusLbl.setText(s.gmail()
                        ? t("settings.integrations.gmail.connected").replace("{email}", s.email() == null ? "" : s.email())
                        : t("settings.integrations.gmail.not_connected"));
                disconnectGmailBtn.setDisable(!s.gmail());
            });
            start(tk, "gmail-status");
        };

        connectGmailBtn.setOnAction(e -> {
            connectGmailBtn.setDisable(true);
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception {
                    com.benjagest.ui.service.AuthApiClient.GoogleConfig cfg = authApiClient.googleConfig();
                    if (!cfg.enabled() || cfg.clientId() == null) throw new IllegalStateException(t("google.not_configured"));
                    var oauth = com.benjagest.ui.service.GoogleDesktopOAuth.authorize(cfg.clientId(),
                            "openid email profile https://www.googleapis.com/auth/gmail.send", true);
                    authApiClient.connectGmail(oauth);
                    return null;
                }
            };
            tk.setOnSucceeded(ev -> { connectGmailBtn.setDisable(false); bringToFront(); loadGmail.run();
                    showInfo(t("settings.integrations.gmail.title"), t("settings.integrations.gmail.connected_ok")); });
            tk.setOnFailed(ev -> { connectGmailBtn.setDisable(false); bringToFront();
                    showError(t("settings.integrations.gmail.title"),
                            tk.getException() == null ? t("google.failed") : tk.getException().getMessage()); });
            start(tk, "gmail-connect");
        });
        disconnectGmailBtn.setOnAction(e -> {
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception { authApiClient.disconnectGmail(); return null; }
            };
            tk.setOnSucceeded(ev -> loadGmail.run());
            start(tk, "gmail-disconnect");
        });

        // --- Conectar Google Calendar y sincronizar con la Agenda ---
        Label calTitle = new Label(t("settings.integrations.calendar.title"));
        calTitle.getStyleClass().add("settings-section-title");
        Label calHint = new Label(t("settings.integrations.calendar.hint"));
        calHint.getStyleClass().add("settings-hint"); calHint.setWrapText(true); calHint.setMaxWidth(620);
        Label calStatusLbl = new Label();
        calStatusLbl.getStyleClass().add("settings-hint");
        Button connectCalBtn = new Button(t("settings.integrations.calendar.connect"));
        connectCalBtn.getStyleClass().add("button-primary");
        connectCalBtn.setGraphic(icon("fab-google"));
        Button syncCalBtn = new Button(t("settings.integrations.calendar.sync"));
        syncCalBtn.getStyleClass().add("button-secondary");
        syncCalBtn.setGraphic(icon("fas-sync-alt"));

        Runnable loadCalendar = () -> {
            Task<com.benjagest.ui.service.AuthApiClient.GmailStatus> tk = new Task<>() {
                @Override protected com.benjagest.ui.service.AuthApiClient.GmailStatus call() throws Exception {
                    return authApiClient.gmailStatus();
                }
            };
            tk.setOnSucceeded(e -> {
                var s = tk.getValue();
                calStatusLbl.setText(s.calendar()
                        ? t("settings.integrations.calendar.connected").replace("{email}", s.email() == null ? "" : s.email())
                        : t("settings.integrations.calendar.not_connected"));
                syncCalBtn.setDisable(!s.calendar());
            });
            start(tk, "calendar-status");
        };

        connectCalBtn.setOnAction(e -> {
            connectCalBtn.setDisable(true);
            Task<Void> tk = new Task<>() {
                @Override protected Void call() throws Exception {
                    com.benjagest.ui.service.AuthApiClient.GoogleConfig cfg = authApiClient.googleConfig();
                    if (!cfg.enabled() || cfg.clientId() == null) throw new IllegalStateException(t("google.not_configured"));
                    var oauth = com.benjagest.ui.service.GoogleDesktopOAuth.authorize(cfg.clientId(),
                            "openid email profile https://www.googleapis.com/auth/calendar", true);
                    authApiClient.connectCalendar(oauth);
                    return null;
                }
            };
            tk.setOnSucceeded(ev -> { connectCalBtn.setDisable(false); bringToFront(); loadCalendar.run();
                    showInfo(t("settings.integrations.calendar.title"), t("settings.integrations.calendar.connected_ok")); });
            tk.setOnFailed(ev -> { connectCalBtn.setDisable(false); bringToFront();
                    showError(t("settings.integrations.calendar.title"),
                            tk.getException() == null ? t("google.failed") : tk.getException().getMessage()); });
            start(tk, "calendar-connect");
        });
        syncCalBtn.setOnAction(e -> {
            syncCalBtn.setDisable(true);
            Task<com.benjagest.ui.service.AuthApiClient.CalendarSync> tk = new Task<>() {
                @Override protected com.benjagest.ui.service.AuthApiClient.CalendarSync call() throws Exception {
                    return authApiClient.calendarSync();
                }
            };
            tk.setOnSucceeded(ev -> { syncCalBtn.setDisable(false);
                    var r = tk.getValue();
                    com.benjagest.ui.support.RefreshBus.emit(com.benjagest.ui.support.RefreshBus.TOPIC_CALENDAR);
                    showInfo(t("settings.integrations.calendar.title"),
                            t("settings.integrations.calendar.sync_done")
                                    .replace("{pushed}", String.valueOf(r.pushed()))
                                    .replace("{pulled}", String.valueOf(r.pulled()))); });
            tk.setOnFailed(ev -> { syncCalBtn.setDisable(false);
                    showError(t("settings.integrations.calendar.title"),
                            tk.getException() == null ? t("google.failed") : tk.getException().getMessage()); });
            start(tk, "calendar-sync");
        });

        // Config per-instalación (client-id/secret propio): AVANZADO y plegado.
        // Por defecto se usan las credenciales centrales de BENJAGEST.
        VBox advancedContent = new VBox(8, hint, steps, g, new HBox(8, save), status);
        advancedContent.setPadding(new Insets(8, 0, 0, 0));
        javafx.scene.control.TitledPane advanced = new javafx.scene.control.TitledPane(
                t("settings.integrations.google.advanced"), advancedContent);
        advanced.setExpanded(false);
        advanced.setMaxWidth(680);

        VBox box = new VBox(12, title,
                gmailTitle, gmailHint,
                new HBox(8, connectGmailBtn, disconnectGmailBtn), gmailStatusLbl,
                new Separator(), calTitle, calHint,
                new HBox(8, connectCalBtn, syncCalBtn), calStatusLbl,
                new Separator(), advanced);
        box.setMaxWidth(680);
        javafx.application.Platform.runLater(loadStatus);
        javafx.application.Platform.runLater(loadGmail);
        javafx.application.Platform.runLater(loadCalendar);
        return box;
    }

    /** Configuración → Acerca de: versión instalada + buscar actualización. */
    private Node settingsAboutTab() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(16));
        Label section = label(t("settings.about.section"), "settings-section-title");
        Label ver = new Label(t("settings.about.version")
                .replace("{v}", com.benjagest.ui.service.UpdateService.APP_VERSION));
        Label hint = new Label(t("settings.about.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");
        Button check = new Button(t("settings.about.check"));
        check.setGraphic(icon("fas-cloud-download-alt"));
        check.getStyleClass().add("primary-button");
        check.setOnAction(e -> checkForUpdates(true));
        box.getChildren().addAll(section, ver, hint, check);
        return box;
    }

    private VBox settingsView(SettingsBundle bundle) {
        VBox content = content();

        Label title = new Label(t("settings.shell.title"));
        title.getStyleClass().add("module-detail-title");
        Label subtitle = new Label(host.sessionCompanyName());
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
        // El antiguo tab "Integraciones" (Google) se fusionó en el tab Correo:
        // el panel Google aparece al elegir el proveedor Google.
        Tab certificateTab = new Tab(t("settings.tab.certificate"), settingsCertificateTab());
        certificateTab.setGraphic(icon("fas-certificate"));
        Tab auditTab = new Tab(t("settings.tab.audit"), host.auditTab());
        auditTab.setGraphic(icon("fas-shield-alt"));
        // PORT-4 SESSION (2026-06-10) — pestaña Sesión con timeout
        // de inactividad, PIN de sesión y salvapantallas.
        Tab sessionTab = new Tab(t("settings.tab.session"), settingsSessionTab());
        sessionTab.setGraphic(icon("fas-user-clock"));
        // Acerca de / Actualizaciones (versión + buscar actualización).
        Tab aboutTab = new Tab(t("settings.tab.about"), settingsAboutTab());
        aboutTab.setGraphic(icon("fas-sync-alt"));

        // "Mi asesoría" solo tiene sentido para empresas CLIENT — una
        // asesoría no necesita otra asesoría que la asesore.
        tabs.getTabs().addAll(companyTab, ownersTab, emailTab, modulesTab,
                credentialsTab, certificateTab, sessionTab, aboutTab);
        if (appMode != AppMode.ADVISORY) {
            Tab advisoryTab = new Tab(t("settings.tab.my_advisory"), host.myAdvisoryTab());
            advisoryTab.setGraphic(icon("fas-handshake"));
            tabs.getTabs().add(advisoryTab);
            // Acuerdo TPB con la asesoria — solo cliente. Aqui el
            // empresario consulta estado, descarga el PDF firmado y
            // puede firmar/revocar.
            Tab myTpbTab = new Tab(t("settings.tab.my_tpb"), host.myTpbTab());
            myTpbTab.setGraphic(icon("fas-file-signature"));
            tabs.getTabs().add(myTpbTab);
        }
        // BOE-RSS — Alertas BOE solo en modo asesoría.
        if (appMode == AppMode.ADVISORY) {
            Tab boeTab = new Tab(t("settings.tab.boe_alerts"), host.boeAlertsTab());
            boeTab.setGraphic(icon("fas-newspaper"));
            tabs.getTabs().add(boeTab);
        }
        // BACKUP-LOCAL — pestaña Backups para OWNER/ADMIN.
        Tab backupTab = new Tab(t("settings.tab.backup"), host.backupTab());
        backupTab.setGraphic(icon("fas-hdd"));
        tabs.getTabs().add(backupTab);
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

        Label typeNote = new Label(t("settings.company.type_note_prefix")
                + localizedEnum("customer_type", company.companyType())
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

        // PORT-4 LOGO (2026-06-10) — Sección Logo de empresa.
        Node logoSection = buildCompanyLogoSection();

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
                billingNote,
                new Separator(),
                logoSection
        );

        Label sectionTitle = label(t("settings.company.section_label"), "settings-section-title");
        return tabLayout(sectionTitle, body, actions);
    }

    // ============================================================
    //  PORT-4 SESSION — Pestaña "Sesión" de Configuración
    // ============================================================
    private Node settingsSessionTab() {
        VBox body = new VBox(20);
        body.setPadding(new Insets(8));

        // --- Sección 1: bloqueo por inactividad ---
        Label lockTitle = label(t("settings.session.lock.title"), "settings-section-title");
        Label lockHint = new Label(t("settings.session.lock.hint"));
        lockHint.setWrapText(true);
        lockHint.getStyleClass().add("settings-hint");
        javafx.scene.control.Spinner<Integer> timeoutSpin =
                new javafx.scene.control.Spinner<>(0, 120, 0, 1);
        timeoutSpin.setEditable(true);
        Label timeoutSuffix = new Label(t("settings.session.lock.minutes"));
        HBox timeoutRow = new HBox(8, timeoutSpin, timeoutSuffix);
        timeoutRow.setAlignment(Pos.CENTER_LEFT);
        Label zeroHint = new Label(t("settings.session.lock.zero_hint"));
        zeroHint.getStyleClass().add("settings-hint");
        zeroHint.setWrapText(true);
        VBox lockSection = new VBox(8, lockTitle, lockHint, timeoutRow, zeroHint);
        lockSection.getStyleClass().add("settings-section");

        // --- Sección 2: PIN de sesión ---
        Label pinTitle = label(t("settings.session.pin.title"), "settings-section-title");
        Label pinHint = new Label(t("settings.session.pin.hint"));
        pinHint.setWrapText(true);
        pinHint.getStyleClass().add("settings-hint");
        Label pinStatus = new Label(t("settings.session.pin.status.unknown"));
        pinStatus.getStyleClass().add("settings-hint");
        Button definePinBtn = new Button(t("settings.session.pin.btn.define"));
        definePinBtn.setGraphic(icon("fas-lock"));
        Button changePinBtn = new Button(t("settings.session.pin.btn.change"));
        changePinBtn.setGraphic(icon("fas-key"));
        Button removePinBtn = new Button(t("settings.session.pin.btn.remove"));
        removePinBtn.setGraphic(icon("fas-unlock"));
        HBox pinActions = new HBox(8, definePinBtn, changePinBtn, removePinBtn);
        pinActions.setAlignment(Pos.CENTER_LEFT);
        VBox pinSection = new VBox(8, pinTitle, pinHint, pinStatus, pinActions);
        pinSection.getStyleClass().add("settings-section");

        // --- Sección 3: Salvapantallas ---
        // SALVAPANTALLAS: único estilo = reloj sobre fondo azul (decisión Benjamin
        // 2026-06-27 — el logo/carrusel provocaba crash nativo D3D en Windows).
        // Se queda fijo por defecto; sin selector.

        // --- Footer guardar ---
        Button saveBtn = new Button(t("settings.session.save"));
        saveBtn.setGraphic(icon("fas-save"));
        saveBtn.getStyleClass().add("button-primary");
        HBox footer = new HBox(saveBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);

        body.getChildren().addAll(lockSection, pinSection, footer);

        // --- Carga + handlers ---
        Runnable reload = () -> {
            Task<com.benjagest.ui.model.SessionStatusEntry> task = new Task<>() {
                @Override protected com.benjagest.ui.model.SessionStatusEntry call() throws Exception {
                    return altaApiClient.getSessionStatus();
                }
            };
            task.setOnSucceeded(ev -> {
                var s = task.getValue();
                timeoutSpin.getValueFactory().setValue(s.pinTimeoutMin());
                if (s.pinConfigured()) {
                    pinStatus.setText(t("settings.session.pin.status.configured"));
                    definePinBtn.setDisable(true);
                    changePinBtn.setDisable(false);
                    removePinBtn.setDisable(false);
                } else {
                    pinStatus.setText(t("settings.session.pin.status.missing"));
                    definePinBtn.setDisable(false);
                    changePinBtn.setDisable(true);
                    removePinBtn.setDisable(true);
                }
                // Aplicar el timeout al checker LOCK en caliente.
                refreshLockTimeout(s.pinTimeoutMin());
                host.setScreensaverStyle("clock");
            });
            task.setOnFailed(ev -> showError(t("settings.session.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "session-load");
        };

        saveBtn.setOnAction(ev -> {
            Task<com.benjagest.ui.model.SessionStatusEntry> save = new Task<>() {
                @Override protected com.benjagest.ui.model.SessionStatusEntry call() throws Exception {
                    return altaApiClient.saveSessionSettings(
                            timeoutSpin.getValue(), "clock");
                }
            };
            save.setOnSucceeded(s -> {
                refreshLockTimeout(save.getValue().pinTimeoutMin());
                host.setScreensaverStyle("clock");
                Alert ok = new Alert(Alert.AlertType.INFORMATION);
                ok.setTitle(t("settings.session.save.ok.title"));
                ok.setHeaderText(t("settings.session.save.ok.body"));
                ok.showAndWait();
            });
            save.setOnFailed(s -> showError(t("settings.session.fail.title"),
                    save.getException() == null ? "" : save.getException().getMessage()));
            start(save, "session-save");
        });

        definePinBtn.setOnAction(ev -> openSessionPinDialog(false, reload));
        changePinBtn.setOnAction(ev -> openSessionPinDialog(true, reload));
        removePinBtn.setOnAction(ev -> openSessionPinRemoveDialog(reload));

        reload.run();

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("content-scroll");
        return scroll;
    }

    private void openSessionPinDialog(boolean requireCurrent, Runnable onSaved) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(t(requireCurrent
                ? "settings.session.pin.dialog.change.title"
                : "settings.session.pin.dialog.define.title"));
        dialog.setHeaderText(t(requireCurrent
                ? "settings.session.pin.dialog.change.header"
                : "settings.session.pin.dialog.define.header"));

        PasswordField currentField = new PasswordField();
        currentField.setPromptText(t("settings.session.pin.dialog.current"));
        PasswordField newField = new PasswordField();
        newField.setPromptText(t("settings.session.pin.dialog.new"));
        PasswordField confirmField = new PasswordField();
        confirmField.setPromptText(t("settings.session.pin.dialog.confirm"));

        VBox form = new VBox(10);
        if (requireCurrent) {
            form.getChildren().addAll(
                    new Label(t("settings.session.pin.dialog.current")), currentField);
        }
        form.getChildren().addAll(
                new Label(t("settings.session.pin.dialog.new")), newField,
                new Label(t("settings.session.pin.dialog.confirm")), confirmField,
                new Label(t("settings.session.pin.dialog.rules")));
        form.setPadding(new Insets(16));
        form.setPrefWidth(380);

        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);
        ((javafx.scene.control.Button) dialog.getDialogPane()
                .lookupButton(javafx.scene.control.ButtonType.OK))
                .setText(t("settings.session.pin.dialog.save"));

        dialog.setResultConverter(bt -> {
            if (bt == javafx.scene.control.ButtonType.OK) {
                String newPin = newField.getText();
                String confirm = confirmField.getText();
                if (newPin == null || newPin.isBlank() || !newPin.equals(confirm)) {
                    showError(t("settings.session.pin.dialog.error.title"),
                            t("settings.session.pin.dialog.error.mismatch"));
                    return null;
                }
                String current = requireCurrent ? currentField.getText() : "";
                Task<Void> t = new Task<>() {
                    @Override protected Void call() throws Exception {
                        altaApiClient.setSessionPin(current, newPin);
                        return null;
                    }
                };
                t.setOnSucceeded(s -> onSaved.run());
                t.setOnFailed(s -> showError(t("settings.session.fail.title"),
                        t.getException() == null ? "" : t.getException().getMessage()));
                start(t, "session-pin-set");
            }
            return null;
        });
        dialog.showAndWait();
    }

    private void openSessionPinRemoveDialog(Runnable onDone) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(t("settings.session.pin.dialog.remove.title"));
        dialog.setHeaderText(t("settings.session.pin.dialog.remove.header"));

        PasswordField currentField = new PasswordField();
        currentField.setPromptText(t("settings.session.pin.dialog.current"));
        VBox form = new VBox(10,
                new Label(t("settings.session.pin.dialog.current")), currentField,
                new Label(t("settings.session.pin.dialog.remove.warning")));
        form.setPadding(new Insets(16));
        form.setPrefWidth(380);

        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);
        ((javafx.scene.control.Button) dialog.getDialogPane()
                .lookupButton(javafx.scene.control.ButtonType.OK))
                .setText(t("settings.session.pin.dialog.remove.confirm"));

        dialog.setResultConverter(bt -> {
            if (bt == javafx.scene.control.ButtonType.OK) {
                String current = currentField.getText();
                if (current == null || current.isBlank()) {
                    showError(t("settings.session.pin.dialog.error.title"),
                            t("settings.session.pin.dialog.error.current_required"));
                    return null;
                }
                Task<Void> t = new Task<>() {
                    @Override protected Void call() throws Exception {
                        altaApiClient.deleteSessionPin(current);
                        return null;
                    }
                };
                t.setOnSucceeded(s -> onDone.run());
                t.setOnFailed(s -> showError(t("settings.session.fail.title"),
                        t.getException() == null ? "" : t.getException().getMessage()));
                start(t, "session-pin-delete");
            }
            return null;
        });
        dialog.showAndWait();
    }

    /** Estilo de salvapantallas activo (clock/logo/dark/carousel). */
    private String screensaverStyle = "clock";

    /** PORT-4 LOGO — Sección del logo de empresa en Configuración → Empresa. */
    private Node buildCompanyLogoSection() {
        VBox box = new VBox(10);
        Label title = label(t("settings.company.section.logo"), "settings-section-title");
        Label hint = new Label(t("settings.company.section.logo.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        javafx.scene.image.ImageView preview = new javafx.scene.image.ImageView();
        preview.setFitWidth(200);
        preview.setFitHeight(120);
        preview.setPreserveRatio(true);
        preview.setSmooth(true);
        StackPane previewBox = new StackPane(preview);
        previewBox.setMinHeight(120);
        previewBox.setMaxWidth(220);
        previewBox.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 8;"
                + " -fx-border-color: #cbd5e1; -fx-border-radius: 8;");

        Button uploadBtn = new Button(t("settings.company.logo.upload"));
        uploadBtn.setGraphic(icon("fas-upload"));
        uploadBtn.getStyleClass().add("button-primary");
        Button deleteBtn = new Button(t("settings.company.logo.delete"));
        deleteBtn.setGraphic(icon("fas-trash"));
        deleteBtn.setDisable(true);

        Runnable reload = () -> {
            Task<byte[]> task = new Task<>() {
                @Override protected byte[] call() throws Exception {
                    return altaApiClient.getCompanyLogoBytes();
                }
            };
            task.setOnSucceeded(ev -> {
                byte[] bytes = task.getValue();
                if (bytes == null || bytes.length == 0) {
                    preview.setImage(null);
                    deleteBtn.setDisable(true);
                } else {
                    preview.setImage(new javafx.scene.image.Image(
                            new java.io.ByteArrayInputStream(bytes)));
                    deleteBtn.setDisable(false);
                }
            });
            task.setOnFailed(ev -> { /* sin logo o sin permisos — ignorar */ });
            start(task, "logo-load");
        };

        uploadBtn.setOnAction(ev -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle(t("settings.company.logo.upload"));
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                    "PNG / JPG", "*.png", "*.jpg", "*.jpeg"));
            java.io.File f = fc.showOpenDialog(box.getScene().getWindow());
            if (f == null) return;
            Task<Boolean> up = new Task<>() {
                @Override protected Boolean call() throws Exception {
                    return altaApiClient.uploadCompanyLogo(f);
                }
            };
            up.setOnSucceeded(s -> reload.run());
            up.setOnFailed(s -> showError(t("settings.company.logo.fail.title"),
                    up.getException() == null ? "" : up.getException().getMessage()));
            start(up, "logo-upload");
        });

        deleteBtn.setOnAction(ev -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(t("settings.company.logo.delete"));
            confirm.setHeaderText(t("settings.company.logo.delete.confirm"));
            confirm.showAndWait().ifPresent(rsp -> {
                if (rsp == javafx.scene.control.ButtonType.OK) {
                    Task<Void> d = new Task<>() {
                        @Override protected Void call() throws Exception {
                            altaApiClient.deleteCompanyLogo();
                            return null;
                        }
                    };
                    d.setOnSucceeded(s -> reload.run());
                    d.setOnFailed(s -> showError(t("settings.company.logo.fail.title"),
                            d.getException() == null ? "" : d.getException().getMessage()));
                    start(d, "logo-delete");
                }
            });
        });

        HBox actions = new HBox(8, uploadBtn, deleteBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        reload.run();
        box.getChildren().addAll(title, hint, previewBox, actions);
        return box;
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
            host.onCompanyRenamed(saved.legalName());
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

        // Slice 4A — Selector de proveedor que autorrellena los campos
        // técnicos (host/puerto/TLS/auth) según el servicio elegido. El
        // asesor/empresario solo necesita pegar su email y la contraseña
        // (o app password). Detecta el proveedor por defecto a partir
        // del host actual si ya está configurado.
        ComboBox<String> providerCombo = new ComboBox<>();
        providerCombo.getItems().addAll(
                t("settings.email.provider.custom"),
                "Gmail / Google Workspace",
                "Outlook / Office 365 / Hotmail",
                "Yahoo Mail",
                "iCloud",
                "Zoho Mail",
                "Dondominio",
                "Webempresa",
                "OVH",
                "IONOS / 1&1",
                "Strato",
                "Arsys");
        providerCombo.setValue(detectProviderFromHost(smtpHost.getText()));
        providerCombo.setMaxWidth(360);

        // Hint dinámico con info específica del proveedor y link.
        javafx.scene.control.Hyperlink helpLink = new javafx.scene.control.Hyperlink();
        helpLink.setVisible(false);
        helpLink.setManaged(false);
        Label helpHint = new Label();
        helpHint.getStyleClass().add("settings-hint");
        helpHint.setWrapText(true);

        Runnable refreshHelp = () -> {
            String prov = providerCombo.getValue();
            ProviderPreset preset = providerPreset(prov);
            if (preset == null) {
                helpHint.setText(t("settings.email.help.custom"));
                helpLink.setVisible(false);
                helpLink.setManaged(false);
            } else {
                helpHint.setText(preset.hint);
                if (preset.helpUrl != null) {
                    helpLink.setText(preset.helpText);
                    helpLink.setVisible(true);
                    helpLink.setManaged(true);
                    helpLink.setOnAction(e -> openExternalUrl(preset.helpUrl));
                } else {
                    helpLink.setVisible(false);
                    helpLink.setManaged(false);
                }
            }
        };
        providerCombo.valueProperty().addListener((obs, oldV, newV) -> {
            ProviderPreset preset = providerPreset(newV);
            if (preset != null) {
                smtpHost.setText(preset.host);
                smtpPort.setText(String.valueOf(preset.port));
                tlsEnabled.setSelected(preset.tls);
                authRequired.setSelected(preset.auth);
            }
            refreshHelp.run();
        });
        refreshHelp.run();

        // Si los campos están vacíos, intenta pre-rellenar smtpUser y
        // fromAddress con el email de la empresa (pestaña Empresa →
        // contact_email) — minimiza tecleo en el alta inicial.
        if (smtpUser.getText() == null || smtpUser.getText().isBlank()) {
            String companyEmail = tryReadCompanyContactEmail();
            if (companyEmail != null) {
                smtpUser.setText(companyEmail);
                if (fromAddress.getText() == null || fromAddress.getText().isBlank()) {
                    fromAddress.setText(companyEmail);
                }
            }
        }

        GridPane grid = formGrid();
        addFormRow(grid, 0, t("settings.email.field.provider"), providerCombo);
        addFormRow(grid, 1, t("settings.email.field.host"), smtpHost);
        addFormRow(grid, 2, t("settings.email.field.port"), smtpPort);
        addFormRow(grid, 3, t("settings.email.field.user"), smtpUser);
        addFormRow(grid, 4, t("settings.email.field.password"), smtpPassword);
        addFormRow(grid, 5, t("settings.email.field.from_address"), fromAddress);
        addFormRow(grid, 6, t("settings.email.field.from_name"), fromName);
        addFormRow(grid, 7, t("settings.email.field.reply_to"), replyTo);

        VBox flags = new VBox(8, tlsEnabled, authRequired);

        // Bloque de ayuda contextual, debajo del formulario.
        VBox helpBox = new VBox(4, helpHint, helpLink);
        helpBox.setPadding(new Insets(4, 0, 0, 0));

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

        // Panel de conexión con Google (Gmail + Calendar): visible solo cuando
        // el proveedor elegido es Google. Sustituye al antiguo tab Integraciones.
        // Con Google conectado el correo sale por Gmail (OAuth) sin contraseña
        // de aplicación; si el proveedor no es Google, se usa SMTP.
        VBox googleBox = googleIntegrationPanel();
        Label googleNote = label(t("settings.email.google.note"), "settings-hint");
        googleNote.setWrapText(true); googleNote.setMaxWidth(620);
        VBox googleSection = new VBox(8, new Separator(), googleNote, googleBox);
        Runnable toggleGoogle = () -> {
            String p = providerCombo.getValue();
            boolean isGoogle = p != null && p.startsWith("Gmail");
            googleSection.setVisible(isGoogle);
            googleSection.setManaged(isGoogle);
        };
        providerCombo.valueProperty().addListener((o, ov, nv) -> toggleGoogle.run());
        toggleGoogle.run();

        VBox center = new VBox(16,
                grid,
                flags,
                helpBox,
                googleSection,
                new Separator(),
                label(t("settings.email.section.test"), "settings-section-title"),
                label(t("settings.email.section.test.hint"), "settings-hint"),
                testRecipient
        );
        return tabLayout(label(t("settings.email.section"), "settings-section-title"), center, actions);
    }

    /**
     * Slice 4A — Presets SMTP por proveedor. El usuario solo elige el
     * combo y los campos host/port/tls/auth se autorrellenan.
     */
    private record ProviderPreset(
            String host, int port, boolean tls, boolean auth,
            String hint, String helpText, String helpUrl) {}

    private ProviderPreset providerPreset(String provider) {
        if (provider == null) return null;
        if (provider.startsWith("Gmail")) return new ProviderPreset(
                "smtp.gmail.com", 587, true, true,
                t("settings.email.help.gmail"),
                t("settings.email.help.gmail.link"),
                "https://myaccount.google.com/apppasswords");
        if (provider.startsWith("Outlook")) return new ProviderPreset(
                "smtp.office365.com", 587, true, true,
                t("settings.email.help.outlook"),
                t("settings.email.help.outlook.link"),
                "https://account.live.com/proofs/AppPassword");
        if (provider.startsWith("Yahoo")) return new ProviderPreset(
                "smtp.mail.yahoo.com", 587, true, true,
                t("settings.email.help.yahoo"),
                t("settings.email.help.yahoo.link"),
                "https://login.yahoo.com/account/security/app-passwords");
        if (provider.startsWith("iCloud")) return new ProviderPreset(
                "smtp.mail.me.com", 587, true, true,
                t("settings.email.help.icloud"),
                t("settings.email.help.icloud.link"),
                "https://appleid.apple.com/account/manage");
        if (provider.startsWith("Zoho")) return new ProviderPreset(
                "smtp.zoho.eu", 587, true, true,
                t("settings.email.help.zoho"),
                t("settings.email.help.zoho.link"),
                "https://accounts.zoho.eu/u/h#sessions/apppasswords");
        if (provider.startsWith("Dondominio")) return new ProviderPreset(
                "mail.tudominio.com", 587, true, true,
                t("settings.email.help.dondominio"), null, null);
        if (provider.startsWith("Webempresa")) return new ProviderPreset(
                "mail.tudominio.com", 587, true, true,
                t("settings.email.help.generic_hosting"), null, null);
        if (provider.startsWith("OVH")) return new ProviderPreset(
                "ssl0.ovh.net", 587, true, true,
                t("settings.email.help.ovh"), null, null);
        if (provider.startsWith("IONOS")) return new ProviderPreset(
                "smtp.ionos.es", 587, true, true,
                t("settings.email.help.ionos"), null, null);
        if (provider.startsWith("Strato")) return new ProviderPreset(
                "smtp.strato.com", 587, true, true,
                t("settings.email.help.generic_hosting"), null, null);
        if (provider.startsWith("Arsys")) return new ProviderPreset(
                "smtp.arsys.es", 587, true, true,
                t("settings.email.help.generic_hosting"), null, null);
        return null; // "Personalizado" o desconocido
    }

    /**
     * Detecta el proveedor a partir del host SMTP guardado. Permite
     * que al entrar a la pantalla, el combo refleje lo que ya hay
     * configurado en lugar de quedarse en blanco.
     */
    private String detectProviderFromHost(String host) {
        if (host == null || host.isBlank()) return t("settings.email.provider.custom");
        String h = host.toLowerCase();
        if (h.contains("gmail")) return "Gmail / Google Workspace";
        if (h.contains("office365") || h.contains("outlook") || h.contains("hotmail"))
            return "Outlook / Office 365 / Hotmail";
        if (h.contains("yahoo")) return "Yahoo Mail";
        if (h.contains("me.com") || h.contains("icloud")) return "iCloud";
        if (h.contains("zoho")) return "Zoho Mail";
        if (h.contains("dondominio")) return "Dondominio";
        if (h.contains("webempresa")) return "Webempresa";
        if (h.contains("ovh")) return "OVH";
        if (h.contains("ionos") || h.contains("1and1")) return "IONOS / 1&1";
        if (h.contains("strato")) return "Strato";
        if (h.contains("arsys")) return "Arsys";
        return t("settings.email.provider.custom");
    }

    /**
     * Lee perezosamente el email de la empresa actual de la pestaña
     * "Empresa" para pre-rellenar smtpUser/fromAddress. Si el endpoint
     * falla por cualquier motivo, devolvemos null sin ruido.
     */
    private String tryReadCompanyContactEmail() {
        try {
            var company = settingsApiClient.getCompany();
            if (company != null && company.email() != null
                    && !company.email().isBlank()) {
                return company.email();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Abre una URL en el navegador del sistema. Si Desktop no está
     * soportado (entornos headless, Linux sin DE), muestra la URL
     * en un diálogo para que el usuario la copie.
     */
    private void openExternalUrl(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(
                            java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                return;
            }
        } catch (Exception ignored) {}
        Alert a = new Alert(Alert.AlertType.INFORMATION, url, ButtonType.OK);
        a.setHeaderText(t("settings.email.help.copy_url"));
        a.showAndWait();
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
        task.setOnFailed(event -> {
            // Mostrar SIEMPRE la causa real (el backend incluye el error SMTP:
            // "No se pudo enviar el email: 535 ... auth disabled", etc.). Si
            // humanize no logra extraer un "message" limpio (timeout, JSON sin
            // ese campo), caemos al mensaje crudo en vez de dejar solo el
            // genérico — el usuario necesita ver por qué rechaza el SMTP.
            String raw = task.getException() == null ? null : task.getException().getMessage();
            String detail = humanizeBackendError(raw);
            if (detail == null || detail.isBlank()) detail = raw;
            showError(t("settings.email.test.fail.title"),
                    detail == null || detail.isBlank() ? t("settings.email.test.fail.body")
                            : t("settings.email.test.fail.body") + "\n\n" + detail);
        });
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

    // ===================================================================
    //  CERT-IMPORT (2026-06-05) — pestaña Certificado en Configuración
    // ===================================================================

    private TableView<com.benjagest.ui.model.CertificateSummaryEntry> certsTable;

    public Node settingsCertificateTab() {
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
        colValidUntil.setComparator(ISO_DATE_COMPARATOR);
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
        return tabLayoutFill(header, body, actions);
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
        com.benjagest.ui.support.EditableCells.installIsoDateMask(validFromField);
        TextField validToField = new TextField();
        validToField.setPromptText("AAAA-MM-DD");
        com.benjagest.ui.support.EditableCells.installIsoDateMask(validToField);

        Button inspectBtn = new Button(t("settings.cert.upload.inspect"));
        inspectBtn.setDisable(true); // habilita cuando hay archivo

        chooseBtn.setOnAction(ev -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle(t("settings.cert.upload.choose"));
            chooser.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter("PKCS#12 (.p12, .pfx)", "*.p12", "*.pfx"));
            java.io.File file = chooser.showOpenDialog(host.ownerWindow());
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

    // ---- pestana Modulos: helpers movidos (estaban en la zona de helpers de formularios) ----
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
            host.onModulesSaved(task.getValue());
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

    private TableView<com.benjagest.ui.model.CompanyOwnerEntry> ownersTable;

    public Node settingsOwnersTab() {
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
        VBox.setVgrow(ownersTable, Priority.ALWAYS); // LAYOUT-FILL: la tabla llena el alto y scrollea por dentro.
        return tabLayoutFill(label(t("settings.owners.section_label"), "settings-section-title"), body, actions);
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
        localizeEnumCombo(roleCombo, "owner_role");
        roleCombo.getSelectionModel().select(existing == null ? "ADMINISTRATOR" : existing.role());
        ComboBox<String> ssCombo = new ComboBox<>();
        ssCombo.getItems().addAll("RETA", "GENERAL", "AUTONOMO_SOCIETARIO", "NO_COTIZA", "OTHER");
        localizeEnumCombo(ssCombo, "ss_regime");
        ssCombo.getSelectionModel().select(existing == null || existing.ssRegime() == null || existing.ssRegime().isBlank()
                ? "RETA" : existing.ssRegime());
        TextField pctField = new TextField(existing == null || existing.ownershipPercent() == null
                ? "" : existing.ownershipPercent().toPlainString());
        TextField apptField = new TextField(existing == null ? "" : existing.appointmentDate());
        apptField.setPromptText("AAAA-MM-DD");
        com.benjagest.ui.support.EditableCells.installIsoDateMask(apptField);
        TextField termField = new TextField(existing == null ? "" : existing.terminationDate());
        termField.setPromptText("AAAA-MM-DD");
        com.benjagest.ui.support.EditableCells.installIsoDateMask(termField);
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

    private TableView<com.benjagest.ui.model.ExternalCredentialEntry> credentialsTable;
    private TableView<com.benjagest.ui.model.CertificateUsageEntry> certUsageTable;

    // ===================================================================
    //  ALTA — Pestana Credenciales externas + Log uso certificados
    // ===================================================================

    public Node settingsCredentialsTab() {
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
        localizeEnumCombo(sysCombo, "credential_system");
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
}