package com.benjagest.ui.screens;

import com.benjagest.ui.AppBrand;
import com.benjagest.ui.service.AuthApiClient;
import com.benjagest.ui.service.AuthSession;
import com.benjagest.ui.service.DeviceConfig;
import com.benjagest.ui.service.GoogleDesktopOAuth;
import com.benjagest.ui.support.Router;
import java.util.Optional;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * SM-4 — Módulo Autenticación (bloque UIR). Login/registro/emparejado/PIN,
 * extraído del God Object como movimiento puro (mismas claves i18n y CSS,
 * mismo comportamiento). UI puro — NO toca AuthService/JWT/AuthSession (§11.2
 * CLAUDE.md); las mutaciones de sesión pasan por {@link Host#handleLoginSuccess()}.
 * {@code passwordWithToggle}/{@code blankAny} se movieron aquí (verificado en
 * código: no tenían más caller que estas pantallas, pese a la nota previa del
 * backlog de que "se quedaban en shell"). {@code bringToFront} sí se queda en
 * el shell (lo usa también Configuración) y llega por {@link Host}.
 */
public class AuthScreen extends ScreenBase {

    /** Puente hacia el estado del shell: layout crudo (sin sidebar/topbar) y sesión. */
    public interface Host {
        void showAuthCenter(Node content);
        void bringToFront();
        void handleLoginSuccess();
    }

    private final AuthApiClient authApiClient;
    private final Host host;

    public AuthScreen(AuthApiClient authApiClient, Function<String, String> tt, Router router, Host host) {
        super(tt, router);
        this.authApiClient = authApiClient;
        this.host = host;
    }

    private void bringToFront() { host.bringToFront(); }
    private void handleLoginSuccess() { host.handleLoginSuccess(); }

    /**
     * Arranque: si NO hay ninguna cuenta (instalación nueva), muestra el
     * REGISTRO; si ya hay cuentas, el flujo normal de login (PIN / emparejar).
     * El check es asíncrono; ante fallo de red cae al login (defensivo).
     */
    public void showInitialScreen() {
        Task<AuthApiClient.BootstrapStatus> task = new Task<>() {
            @Override protected AuthApiClient.BootstrapStatus call() {
                return authApiClient.bootstrapStatus();
            }
        };
        task.setOnSucceeded(e -> {
            var s = task.getValue();
            if (!s.hasAccounts()) {
                showRegister();                 // instalación nueva → registro
            } else if (s.hasAdvisory()) {
                showLogin();                    // asesoría → multi-puesto (emparejar/PIN)
            } else {
                showEmailLogin();               // empresario → email/contraseña, sin multi-puesto
            }
        });
        task.setOnFailed(e -> showLogin());
        start(task, "bootstrap-status");
    }

    public void showLogin() {
        Optional<DeviceConfig> dc = DeviceConfig.load();
        if (dc.isPresent()) {
            showPinKeypad(dc.get());
        } else {
            showPairingScreen();
        }
    }

    /**
     * L4-3 — Login email/password clásico. Era la pantalla de arranque
     * antes del modelo PIN multi-puesto; ahora es la pantalla
     * secundaria a la que se llega desde "Entrar como administrador"
     * en el teclado PIN o cuando aún no hay PINs configurados.
     */
    public void showEmailLogin() {
        VBox panel = new VBox(14);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(42));
        panel.setMaxWidth(420);
        panel.getStyleClass().add("summary-card");

        Label title = new Label("BENJAGEST");
        title.getStyleClass().add("hero-title");
        Label subtitle = new Label(t("login.email.subtitle"));
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

        Button googleButton = new Button(t("login.with_google"));
        googleButton.setGraphic(icon("fab-google"));
        googleButton.setMaxWidth(Double.MAX_VALUE);
        googleButton.setOnAction(ev -> startGoogleLogin(googleButton));

        Hyperlink createAccount = new Hyperlink(t("register.link"));
        createAccount.setOnAction(ev -> showRegister());

        panel.getChildren().addAll(
                AppBrand.createLogoMark(), title, subtitle,
                emailField, passwordWithToggle(passwordField), loginButton,
                createAccount,
                new Separator(),
                googleButton
        );

        // L4-3 — Si el PC ya está emparejado, ofrece volver al teclado PIN.
        // El email/password sigue funcionando como flujo secundario, pero
        // el día a día va por PIN.
        if (DeviceConfig.load().isPresent()) {
            Hyperlink backToPin = new Hyperlink(t("pin.back_to_keypad"));
            backToPin.setOnAction(ev -> showLogin());
            VBox.setMargin(backToPin, new Insets(8, 0, 0, 0));
            panel.getChildren().add(backToPin);
        }

        BorderPane wrapper = new BorderPane(panel);
        wrapper.setPadding(new Insets(70));
        BorderPane.setAlignment(panel, Pos.CENTER);
        host.showAuthCenter(wrapper);
    }

    /**
     * REG-2 — Pantalla "Crear cuenta" (alta de asesoría o empresa). Llama a
     * {@code /api/auth/register} y, al volver OK, entra directo (auto-login).
     * El alta con Google se activa cuando la instalación configure sus propias
     * credenciales (Configuración → Integraciones); aquí queda el botón listo.
     */
    public void showRegister() {
        VBox panel = new VBox(12);
        panel.setAlignment(Pos.CENTER_LEFT);
        panel.setPadding(new Insets(36));
        panel.setMaxWidth(480);
        panel.getStyleClass().add("summary-card");

        Label title = new Label(t("register.title"));
        title.getStyleClass().add("hero-title");
        Label subtitle = new Label(t("register.subtitle"));
        subtitle.getStyleClass().add("hero-body");
        subtitle.setWrapText(true);

        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("ADVISORY", "BUSINESS");
        typeCombo.setConverter(new StringConverter<>() {
            @Override public String toString(String s) { return s == null ? "" : t("register.type." + s); }
            @Override public String fromString(String s) { return null; }
        });
        typeCombo.getSelectionModel().select("ADVISORY");
        typeCombo.setMaxWidth(Double.MAX_VALUE);

        TextField legalName = field(t("register.legal_name"));
        TextField taxId = field(t("register.tax_id"));
        TextField addressLine = field(t("register.address"));
        TextField city = field(t("register.city"));
        TextField province = field(t("register.province"));
        TextField postalCode = field(t("register.postal_code"));
        TextField displayName = field(t("register.owner_name"));
        TextField email = field(t("register.email"));
        PasswordField password = new PasswordField();
        password.setPromptText(t("register.password"));
        password.setMaxWidth(Double.MAX_VALUE);
        PasswordField password2 = new PasswordField();
        password2.setPromptText(t("register.password2"));
        password2.setMaxWidth(Double.MAX_VALUE);

        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8);
        int r = 0;
        g.add(new Label(t("register.type")), 0, r); g.add(typeCombo, 1, r++);
        g.add(new Label(t("register.legal_name")), 0, r); g.add(legalName, 1, r++);
        g.add(new Label(t("register.tax_id")), 0, r); g.add(taxId, 1, r++);
        g.add(new Label(t("register.address")), 0, r); g.add(addressLine, 1, r++);
        g.add(new Label(t("register.city")), 0, r); g.add(city, 1, r++);
        g.add(new Label(t("register.province")), 0, r); g.add(province, 1, r++);
        g.add(new Label(t("register.postal_code")), 0, r); g.add(postalCode, 1, r++);
        g.add(new Label(t("register.owner_name")), 0, r); g.add(displayName, 1, r++);
        g.add(new Label(t("register.email")), 0, r); g.add(email, 1, r++);
        g.add(new Label(t("register.password")), 0, r); g.add(passwordWithToggle(password), 1, r++);
        g.add(new Label(t("register.password2")), 0, r); g.add(passwordWithToggle(password2), 1, r++);
        ColumnConstraints c0 = new ColumnConstraints();
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS); c1.setFillWidth(true);
        g.getColumnConstraints().addAll(c0, c1);

        Button createBtn = new Button(t("register.submit"));
        createBtn.getStyleClass().add("button-primary");
        createBtn.setMaxWidth(Double.MAX_VALUE);
        createBtn.setOnAction(ev -> doRegister(createBtn, typeCombo.getValue(), legalName.getText(), taxId.getText(),
                addressLine.getText(), city.getText(), province.getText(), postalCode.getText(),
                displayName.getText(), email.getText(), password.getText(), password2.getText()));

        Button googleBtn = new Button(t("register.with_google"));
        googleBtn.setGraphic(icon("fab-google"));
        googleBtn.setMaxWidth(Double.MAX_VALUE);
        googleBtn.setOnAction(ev -> startGoogleRegister(googleBtn, typeCombo.getValue(),
                legalName.getText(), taxId.getText(), addressLine.getText(), city.getText(),
                province.getText(), postalCode.getText(), displayName.getText()));

        Hyperlink back = new Hyperlink(t("register.back"));
        back.setOnAction(ev -> showEmailLogin());

        panel.getChildren().addAll(title, subtitle, g, createBtn,
                new Separator(), googleBtn, back);

        ScrollPane sp = new ScrollPane(panel);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("edge-to-edge");
        BorderPane wrapper = new BorderPane(sp);
        wrapper.setPadding(new Insets(40));
        BorderPane.setAlignment(sp, Pos.CENTER);
        host.showAuthCenter(wrapper);
    }

    private TextField field(String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        f.setMaxWidth(Double.MAX_VALUE);
        return f;
    }

    /**
     * Envuelve un {@link PasswordField} con un botón "ojo" para ver/ocultar la
     * contraseña. Se mantiene el mismo {@code PasswordField} (su {@code getText()}
     * sigue valiendo) y se superpone un TextField visible sincronizado por
     * binding bidireccional. Devuelve el contenedor a colocar en el layout.
     */
    private Node passwordWithToggle(PasswordField pf) {
        TextField visible = new TextField();
        visible.setPromptText(pf.getPromptText());
        visible.setManaged(false);
        visible.setVisible(false);
        visible.textProperty().bindBidirectional(pf.textProperty());
        StackPane stack = new StackPane(pf, visible);
        HBox.setHgrow(stack, Priority.ALWAYS);

        ToggleButton eye = new ToggleButton();
        eye.setGraphic(icon("fas-eye"));
        eye.setFocusTraversable(false);
        eye.setTooltip(new Tooltip(t("password.toggle")));
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

    private void doRegister(Button submitBtn, String type, String legalName, String taxId, String address,
                            String city, String province, String postalCode, String displayName,
                            String email, String password, String password2) {
        if (blankAny(type, legalName, taxId, address, city, displayName, email, password)) {
            showError(t("register.title"), t("register.err.required"));
            return;
        }
        if (!password.equals(password2)) {
            showError(t("register.title"), t("register.err.password_mismatch"));
            return;
        }
        if (password.length() < 8) {
            showError(t("register.title"), t("register.err.password_short"));
            return;
        }
        // Anti doble-clic: el botón se bloquea hasta que la llamada termina.
        submitBtn.setDisable(true);
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception {
                return authApiClient.register(type, legalName.trim(), taxId.trim(), address.trim(),
                        city.trim(), province == null ? "" : province.trim(),
                        postalCode == null ? "" : postalCode.trim(),
                        displayName.trim(), email.trim(), password);
            }
        };
        // REG-VERIFY: el alta NO entra directo; pide el PIN enviado al email.
        task.setOnSucceeded(ev -> { submitBtn.setDisable(false); showEmailVerification(task.getValue()); });
        task.setOnFailed(ev -> {
            submitBtn.setDisable(false);
            showError(t("register.err.title"),
                    task.getException() == null ? t("register.err.generic") : task.getException().getMessage());
        });
        start(task, "register");
    }

    /** REG-VERIFY — Diálogo del PIN de verificación enviado al email tras el alta. */
    private void showEmailVerification(String email) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(t("verify.title"));
        dialog.setHeaderText(t("verify.header").replace("{email}", email == null ? "" : email));
        TextField pinField = new TextField();
        pinField.setPromptText(t("verify.pin.prompt"));
        pinField.setMaxWidth(160);
        Hyperlink resend = new Hyperlink(t("verify.resend"));
        Label status = new Label();
        status.getStyleClass().add("settings-hint");
        status.setWrapText(true);
        resend.setOnAction(e -> {
            Task<Void> rt = new Task<>() {
                @Override protected Void call() throws Exception { authApiClient.resendVerification(email); return null; }
            };
            rt.setOnSucceeded(ev -> status.setText(t("verify.resent")));
            rt.setOnFailed(ev -> status.setText(rt.getException() == null ? "" : rt.getException().getMessage()));
            start(rt, "verify-resend");
        });
        VBox box = new VBox(10, new Label(t("verify.body")), pinField, resend, status);
        box.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(box);
        ButtonType verifyType = new ButtonType(t("verify.btn"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(verifyType, ButtonType.CANCEL);
        Button vb = (Button) dialog.getDialogPane().lookupButton(verifyType);
        vb.addEventFilter(ActionEvent.ACTION, ev -> {
            ev.consume(); // no cerrar hasta validar el PIN
            String pin = pinField.getText();
            if (pin == null || pin.isBlank()) { status.setText(t("verify.pin.prompt")); return; }
            vb.setDisable(true);
            Task<Void> vt = new Task<>() {
                @Override protected Void call() throws Exception { authApiClient.verifyEmail(email, pin.trim()); return null; }
            };
            vt.setOnSucceeded(e -> { dialog.setResult(null); dialog.close(); handleLoginSuccess(); });
            vt.setOnFailed(e -> {
                vb.setDisable(false);
                status.setText(vt.getException() == null ? t("verify.fail") : vt.getException().getMessage());
                pinField.clear();
            });
            start(vt, "verify-pin");
        });
        dialog.showAndWait();
    }

    private boolean blankAny(String... vs) {
        for (String v : vs) if (v == null || v.isBlank()) return true;
        return false;
    }

    /** REG-3 — Login con Google: abre el navegador, intercambia en backend, entra. */
    private void startGoogleLogin(Button btn) {
        btn.setDisable(true);
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                AuthApiClient.GoogleConfig cfg = authApiClient.googleConfig();
                if (!cfg.enabled() || cfg.clientId() == null) throw new IllegalStateException(t("google.not_configured"));
                authApiClient.googleLogin(GoogleDesktopOAuth.authorize(cfg.clientId()));
                return null;
            }
        };
        task.setOnSucceeded(e -> { handleLoginSuccess(); bringToFront(); });
        task.setOnFailed(e -> { btn.setDisable(false); showError(t("google.title"),
                task.getException() == null ? t("google.failed") : task.getException().getMessage()); });
        start(task, "google-login");
    }

    /** REG-3 — Alta con Google: valida los datos de empresa, abre el navegador y crea la cuenta. */
    private void startGoogleRegister(Button btn, String type, String legalName, String taxId, String address,
                                     String city, String province, String postalCode, String displayName) {
        if (blankAny(type, legalName, taxId, address, city)) {
            showError(t("register.title"), t("register.err.required"));
            return;
        }
        btn.setDisable(true);
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                AuthApiClient.GoogleConfig cfg = authApiClient.googleConfig();
                if (!cfg.enabled() || cfg.clientId() == null) throw new IllegalStateException(t("google.not_configured"));
                GoogleDesktopOAuth.Result oauth = GoogleDesktopOAuth.authorize(cfg.clientId());
                authApiClient.googleRegister(oauth, type, legalName.trim(), taxId.trim(), address.trim(),
                        city.trim(), province == null ? "" : province.trim(),
                        postalCode == null ? "" : postalCode.trim(),
                        displayName == null ? "" : displayName.trim());
                return null;
            }
        };
        task.setOnSucceeded(e -> { handleLoginSuccess(); bringToFront(); });
        task.setOnFailed(e -> { btn.setDisable(false); showError(t("register.err.title"),
                task.getException() == null ? t("google.failed") : task.getException().getMessage()); });
        start(task, "google-register");
    }

    private void login(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            showError(t("login.email.missing.title"), t("login.email.missing.body"));
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
        task.setOnFailed(event -> {
            Throwable ex = task.getException();
            String msg = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
            // REG-VERIFY: si el email no está verificado, abrir el diálogo del PIN.
            if (msg.contains("EMAIL_NOT_VERIFIED")) {
                showEmailVerification(email.trim());
            } else {
                showError(t("loginFailed"), t("loginFailedDetail"));
            }
        });
        start(task, "auth-login");
    }

    // ===================================================================
    //  L4-3 — Pantalla de emparejado del PC con la asesoría
    // ===================================================================

    private void showPairingScreen() {
        VBox panel = new VBox(14);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(42));
        panel.setMaxWidth(460);
        panel.getStyleClass().add("summary-card");

        Label title = new Label(t("pin.pair.title"));
        title.getStyleClass().add("hero-title");
        Label subtitle = new Label(t("pin.pair.subtitle"));
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(380);
        subtitle.getStyleClass().add("hero-body");

        TextField emailField = new TextField();
        emailField.setPromptText(t("pin.pair.email_prompt"));
        emailField.setMaxWidth(Double.MAX_VALUE);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(t("pin.pair.password_prompt"));
        passwordField.setMaxWidth(Double.MAX_VALUE);

        TextField deviceNameField = new TextField(); // en blanco (antes el hostname del PC)
        deviceNameField.setPromptText(t("pin.pair.device_name_prompt"));
        deviceNameField.setMaxWidth(Double.MAX_VALUE);

        Label deviceNameHint = new Label(t("pin.pair.device_name_hint"));
        deviceNameHint.setWrapText(true);
        deviceNameHint.setMaxWidth(380);
        deviceNameHint.getStyleClass().add("settings-hint");

        Button pairButton = new Button(t("pin.pair.button"));
        pairButton.setGraphic(icon("fas-link"));
        pairButton.setMaxWidth(Double.MAX_VALUE);
        pairButton.getStyleClass().add("primary-button");

        Runnable submitPair = () -> {
            String email = emailField.getText() == null ? "" : emailField.getText().trim();
            String password = passwordField.getText() == null ? "" : passwordField.getText();
            String deviceName = deviceNameField.getText() == null ? "" : deviceNameField.getText().trim();
            if (email.isBlank() || password.isBlank() || deviceName.isBlank()) {
                showError(t("pin.pair.missing.title"), t("pin.pair.missing.body"));
                return;
            }
            pairButton.setDisable(true);
            Task<AuthApiClient.PairResult> task = new Task<>() {
                @Override protected AuthApiClient.PairResult call() throws Exception {
                    return authApiClient.pairDevice(email, password, deviceName);
                }
            };
            task.setOnSucceeded(ev -> {
                AuthApiClient.PairResult r = task.getValue();
                try {
                    DeviceConfig.save(
                            r.deviceId(), r.deviceSecret(),
                            r.companyId(), r.companyName());
                } catch (java.io.IOException ex) {
                    pairButton.setDisable(false);
                    showError(t("pin.pair.save.fail.title"), t("pin.pair.save.fail.body"));
                    return;
                }
                // Emparejado OK → pasamos directamente al teclado PIN
                // para que el OWNER pueda meter SU PIN (asumiendo L4-8
                // ya está sembrado) y entrar.
                showInfo(t("pin.pair.ok.title"),
                        t("pin.pair.ok.body") + " " + r.companyName());
                showLogin();
            });
            task.setOnFailed(ev -> {
                pairButton.setDisable(false);
                Throwable err = task.getException();
                String msg = err == null || err.getMessage() == null
                        ? t("pin.pair.fail.body") : err.getMessage();
                showError(t("pin.pair.fail.title"), msg);
            });
            start(task, "pin-pair");
        };

        pairButton.setOnAction(ev -> submitPair.run());
        passwordField.setOnAction(ev -> submitPair.run());

        Hyperlink emailLoginLink = new Hyperlink(t("pin.pair.use_email_login"));
        emailLoginLink.setOnAction(ev -> showEmailLogin());

        VBox.setMargin(emailLoginLink, new Insets(8, 0, 0, 0));

        panel.getChildren().addAll(
                AppBrand.createLogoMark(),
                title, subtitle,
                emailField, passwordField,
                deviceNameField, deviceNameHint,
                pairButton,
                new Separator(),
                emailLoginLink
        );

        BorderPane wrapper = new BorderPane(panel);
        wrapper.setPadding(new Insets(70));
        BorderPane.setAlignment(panel, Pos.CENTER);
        host.showAuthCenter(wrapper);
    }

    // ===================================================================
    //  L4-3 — Teclado PIN sin lista de nombres
    // ===================================================================

    private void showPinKeypad(DeviceConfig dc) {
        VBox panel = new VBox(16);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(36));
        panel.setMaxWidth(360);
        panel.getStyleClass().add("summary-card");

        Label title = new Label(t("pin.keypad.title"));
        title.getStyleClass().add("hero-title");
        Label company = new Label(dc.companyName() == null || dc.companyName().isBlank()
                ? t("pin.keypad.subtitle_no_company")
                : dc.companyName());
        company.getStyleClass().add("hero-body");

        // Display de los dots — actualizado dinámicamente cuando se pulsa
        // teclado. El PIN real vive en una StringBuilder local.
        Label dots = new Label("");
        dots.getStyleClass().add("hero-title");
        dots.setStyle("-fx-font-size: 28px; -fx-letter-spacing: 0.4em;");

        StringBuilder pinBuffer = new StringBuilder();
        Runnable refreshDots = () -> dots.setText("●".repeat(pinBuffer.length()));

        Label errorLabel = new Label("");
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(280);
        errorLabel.getStyleClass().add("settings-hint");

        // Botón Entrar habilitado solo con 4+ dígitos
        Button enterBtn = new Button(t("pin.keypad.enter"));
        enterBtn.setMaxWidth(Double.MAX_VALUE);
        enterBtn.getStyleClass().add("primary-button");
        enterBtn.setDisable(true);

        Runnable trySubmit = () -> {
            if (pinBuffer.length() < 4) return;
            enterBtn.setDisable(true);
            String pin = pinBuffer.toString();
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    authApiClient.pinLogin(dc.deviceSecret(), pin);
                    return null;
                }
            };
            task.setOnSucceeded(ev -> handleLoginSuccess());
            task.setOnFailed(ev -> {
                enterBtn.setDisable(false);
                pinBuffer.setLength(0);
                refreshDots.run();
                Throwable err = task.getException();
                String msg = err == null || err.getMessage() == null
                        ? t("pin.keypad.fail.generic") : err.getMessage();
                if ("DEVICE_NOT_RECOGNIZED".equals(msg)) {
                    // El backend no reconoce el device — el secret local
                    // está obsoleto (asesoría lo revocó, BD reseteada…).
                    // Borramos el archivo y volvemos a emparejar.
                    DeviceConfig.clear();
                    showError(t("pin.keypad.device_lost.title"),
                            t("pin.keypad.device_lost.body"));
                    showLogin();
                    return;
                }
                errorLabel.setText(msg);
            });
            start(task, "pin-login");
        };

        enterBtn.setOnAction(ev -> trySubmit.run());

        // Acciones reutilizables: las usan los botones del teclado virtual
        // Y el handler del teclado físico del PC más abajo.
        java.util.function.IntConsumer appendDigit = digit -> {
            if (pinBuffer.length() >= 8) return; // tope superior 8 dígitos
            pinBuffer.append(digit);
            refreshDots.run();
            errorLabel.setText("");
            enterBtn.setDisable(pinBuffer.length() < 4);
        };
        Runnable backspace = () -> {
            if (pinBuffer.length() == 0) return;
            pinBuffer.setLength(pinBuffer.length() - 1);
            refreshDots.run();
            errorLabel.setText("");
            enterBtn.setDisable(pinBuffer.length() < 4);
        };

        // Teclado 3x4 — 1..9 / borrar / 0 / OK pequeño
        GridPane keypad = new GridPane();
        keypad.setHgap(8);
        keypad.setVgap(8);
        keypad.setAlignment(Pos.CENTER);
        int[][] layout = {
                {1, 2, 3},
                {4, 5, 6},
                {7, 8, 9}
        };
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                final int digit = layout[r][c];
                Button b = pinKey(String.valueOf(digit));
                b.setFocusTraversable(false); // que no roben Enter al panel
                b.setOnAction(ev -> appendDigit.accept(digit));
                keypad.add(b, c, r);
            }
        }
        Button del = pinKey("⌫");
        del.setFocusTraversable(false);
        del.setOnAction(ev -> backspace.run());
        Button zero = pinKey("0");
        zero.setFocusTraversable(false);
        zero.setOnAction(ev -> appendDigit.accept(0));
        Button okMini = pinKey("OK");
        okMini.setFocusTraversable(false);
        okMini.setOnAction(ev -> trySubmit.run());
        keypad.add(del, 0, 3);
        keypad.add(zero, 1, 3);
        keypad.add(okMini, 2, 3);

        // Handler del teclado FÍSICO del PC. Acepta:
        //   - Dígitos 0-9 de la fila numérica y del numpad (getText()
        //     devuelve el carácter tipeado, independiente del layout).
        //   - Backspace / Supr → quitar último dígito.
        //   - Enter → enviar (si hay 4+ dígitos).
        //   - Escape → limpia el buffer (atajo cómodo si tecleas mal).
        // Lo registramos en el panel y pedimos focus al mostrarlo.
        // EventHandler en lugar de setOnKeyPressed para que coexista con
        // otros handlers globales si los hubiera en el futuro.
        panel.setFocusTraversable(true);
        panel.setOnKeyPressed(ev -> {
            String text = ev.getText();
            if (text != null && text.length() == 1
                    && text.charAt(0) >= '0' && text.charAt(0) <= '9') {
                appendDigit.accept(text.charAt(0) - '0');
                ev.consume();
                return;
            }
            switch (ev.getCode()) {
                case BACK_SPACE, DELETE -> { backspace.run(); ev.consume(); }
                case ENTER -> { trySubmit.run(); ev.consume(); }
                case ESCAPE -> {
                    pinBuffer.setLength(0);
                    refreshDots.run();
                    errorLabel.setText("");
                    enterBtn.setDisable(true);
                    ev.consume();
                }
                default -> { /* ignorar otras teclas */ }
            }
        });
        // requestFocus() en el panel cuando se haya añadido a la escena.
        // Lo programamos para el siguiente tick del JavaFX thread, así
        // el nodo ya está conectado y puede recibir focus realmente.
        Platform.runLater(panel::requestFocus);

        // Enlaces secundarios
        Hyperlink emailLink = new Hyperlink(t("pin.keypad.admin_login"));
        emailLink.setOnAction(ev -> showEmailLogin());

        Hyperlink forgetLink = new Hyperlink(t("pin.keypad.forget_device"));
        forgetLink.setOnAction(ev -> confirmForgetDevice(dc));

        HBox links = new HBox(16, emailLink, forgetLink);
        links.setAlignment(Pos.CENTER);

        panel.getChildren().addAll(
                AppBrand.createLogoMark(),
                title, company,
                dots,
                keypad,
                enterBtn,
                errorLabel,
                new Separator(),
                links
        );

        BorderPane wrapper = new BorderPane(panel);
        wrapper.setPadding(new Insets(40));
        BorderPane.setAlignment(panel, Pos.CENTER);
        host.showAuthCenter(wrapper);
    }

    /** Construye un botón uniforme del teclado PIN. */
    private Button pinKey(String label) {
        Button b = new Button(label);
        b.setMinSize(70, 60);
        b.setPrefSize(70, 60);
        b.setStyle("-fx-font-size: 18px;");
        return b;
    }

    /**
     * Diálogo de confirmación para "Olvidar este equipo". Pide email +
     * password del OWNER, revoca el device en backend y borra el
     * archivo local. Tras éxito vuelve a la pantalla de emparejado.
     */
    private void confirmForgetDevice(DeviceConfig dc) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(t("pin.forget.title"));
        dialog.setHeaderText(t("pin.forget.header"));

        TextField emailF = new TextField();
        emailF.setPromptText(t("pin.pair.email_prompt"));
        PasswordField pwdF = new PasswordField();
        pwdF.setPromptText(t("pin.pair.password_prompt"));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(8));
        grid.add(new Label(t("pin.pair.email_prompt")), 0, 0);
        grid.add(emailF, 1, 0);
        grid.add(new Label(t("pin.pair.password_prompt")), 0, 1);
        grid.add(pwdF, 1, 1);
        Label hint = new Label(t("pin.forget.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");
        grid.add(hint, 0, 2, 2, 1);

        dialog.getDialogPane().setContent(grid);
        ButtonType okBtn = new ButtonType(t("pin.forget.confirm"),
                ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtn, ButtonType.CANCEL);

        final Node okNode = dialog.getDialogPane().lookupButton(okBtn);
        okNode.addEventFilter(ActionEvent.ACTION, ev -> {
            String email = emailF.getText() == null ? "" : emailF.getText().trim();
            String pwd = pwdF.getText() == null ? "" : pwdF.getText();
            if (email.isBlank() || pwd.isBlank()) {
                showError(t("pin.pair.missing.title"), t("pin.pair.missing.body"));
                ev.consume();
                return;
            }
            ev.consume();
            // Login con email/password para obtener JWT con el que llamar
            // a DELETE /devices/{id}. Asume que el OWNER existe; si las
            // credenciales son incorrectas el flujo aborta y deja el
            // device.json intacto.
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    authApiClient.login(email, pwd);
                    authApiClient.revokeDevice(dc.deviceId());
                    return null;
                }
            };
            task.setOnSucceeded(s -> {
                DeviceConfig.clear();
                AuthSession.get().clear();
                dialog.setResult(okBtn);
                dialog.close();
                showInfo(t("pin.forget.ok.title"), t("pin.forget.ok.body"));
                showLogin();
            });
            task.setOnFailed(s -> showError(t("pin.forget.fail.title"),
                    t("pin.forget.fail.body")));
            start(task, "pin-forget-device");
        });

        dialog.showAndWait();
    }

    /** Nombre por defecto del dispositivo: hostname si lo conocemos. */
    private String defaultDeviceName() {
        try {
            String host = java.net.InetAddress.getLocalHost().getHostName();
            return host == null || host.isBlank() ? "PC asesoría" : host;
        } catch (Exception ex) {
            return "PC asesoría";
        }
    }
}
