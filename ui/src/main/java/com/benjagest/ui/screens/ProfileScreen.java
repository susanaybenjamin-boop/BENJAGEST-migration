package com.benjagest.ui.screens;

import com.benjagest.ui.service.AltaApiClient;
import com.benjagest.ui.support.Router;
import java.util.function.Function;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * SM-3 — Módulo Perfil del usuario (idioma, bloqueo por inactividad, IA Copilot,
 * avatar). Extraído del God Object como movimiento puro (mismas claves i18n y CSS).
 * La navegación (currentModule/recordNav/select) se queda en el wrapper del shell.
 * El cambio de idioma es GLOBAL (afecta a toda la UI) y el timeout de bloqueo toca
 * el checker del shell, así que ambos se aplican vía {@link Host}. settingsSection
 * se copia (factory puro compartido con Configuración).
 */
public class ProfileScreen extends ScreenBase {

    /** Puente hacia el estado global del shell (idioma + timeout de bloqueo). */
    public interface Host {
        void applyLanguage(String lang);
        void refreshLockTimeout(int newTimeoutMin);
    }

    private final AltaApiClient altaApiClient;
    private final Host host;

    public ProfileScreen(AltaApiClient altaApiClient, Function<String, String> tt, Router router, Host host) {
        super(tt, router);
        this.altaApiClient = altaApiClient;
        this.host = host;
    }

    private void refreshLockTimeout(int newTimeoutMin) { host.refreshLockTimeout(newTimeoutMin); }

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

    public void show() {
        VBox root = new VBox(16);
        root.getStyleClass().add("content");

        Label header = label(t("profile.title"), "settings-section-title");
        Label hint = new Label(t("profile.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        VBox sections = new VBox(18);

        // --- Seccion: Idioma ---
        VBox sLang = settingsSection(t("profile.section.language"),
                t("profile.section.language.hint"));
        ComboBox<String> langCombo = new ComboBox<>(FXCollections.observableArrayList("es", "en"));
        langCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) {
                return "es".equals(s) ? "Español"
                        : ("en".equals(s) ? "English" : (s == null ? "" : s));
            }
            @Override public String fromString(String s) { return s; }
        });
        sLang.getChildren().add(langCombo);

        // --- Seccion: Bloqueo por inactividad (PORT-3 LOCK) ---
        VBox sLock = settingsSection(t("profile.section.lock"),
                t("profile.section.lock.hint"));
        javafx.scene.control.Spinner<Integer> timeoutSpin =
                new javafx.scene.control.Spinner<>(0, 120, 0, 1);
        timeoutSpin.setEditable(true);
        Label timeoutHint = new Label(t("profile.lock.zero_hint"));
        timeoutHint.getStyleClass().add("settings-hint");
        timeoutHint.setWrapText(true);
        sLock.getChildren().addAll(
                new Label(t("profile.lock.timeout_label")), timeoutSpin, timeoutHint);

        // --- Seccion: IA Copilot (reservado) ---
        VBox sAi = settingsSection(t("profile.section.ai"),
                t("profile.section.ai.hint"));
        CheckBox aiBox = new CheckBox(t("profile.ai.enable"));
        sAi.getChildren().add(aiBox);

        // --- Seccion: Avatar (ruta local) ---
        VBox sAvatar = settingsSection(t("profile.section.avatar"),
                t("profile.section.avatar.hint"));
        TextField avatarField = new TextField();
        avatarField.setPromptText(t("profile.avatar.path_prompt"));
        Button browseBtn = new Button(t("profile.avatar.browse"));
        browseBtn.setOnAction(ev -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle(t("profile.avatar.browse"));
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                    "PNG / JPG", "*.png", "*.jpg", "*.jpeg"));
            java.io.File f = fc.showOpenDialog(root.getScene().getWindow());
            if (f != null) avatarField.setText(f.getAbsolutePath());
        });
        HBox avatarRow = new HBox(8, avatarField, browseBtn);
        HBox.setHgrow(avatarField, Priority.ALWAYS);
        sAvatar.getChildren().add(avatarRow);

        // Footer
        Button saveBtn = new Button(t("profile.btn.save"));
        saveBtn.setGraphic(icon("fas-save"));
        saveBtn.getStyleClass().add("button-primary");
        HBox footer = new HBox(8, saveBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);

        sections.getChildren().addAll(sLang, sLock, sAi, sAvatar);
        root.getChildren().addAll(header, hint, sections, footer);

        // Carga inicial
        Task<com.benjagest.ui.model.UserSettingsEntry> load = new Task<>() {
            @Override protected com.benjagest.ui.model.UserSettingsEntry call() throws Exception {
                return altaApiClient.getUserSettings();
            }
        };
        load.setOnSucceeded(ev -> {
            var s = load.getValue();
            langCombo.setValue(s.language() == null || s.language().isBlank() ? "es" : s.language());
            timeoutSpin.getValueFactory().setValue(s.pinTimeoutMin());
            aiBox.setSelected(s.aiEnabled());
            avatarField.setText(s.avatarPath() == null ? "" : s.avatarPath());
        });
        load.setOnFailed(ev -> showError(t("profile.fail.title"),
                load.getException() == null ? "" : load.getException().getMessage()));
        start(load, "profile-load");

        saveBtn.setOnAction(ev -> {
            Task<com.benjagest.ui.model.UserSettingsEntry> save = new Task<>() {
                @Override protected com.benjagest.ui.model.UserSettingsEntry call() throws Exception {
                    return altaApiClient.saveUserSettings(
                            langCombo.getValue(),
                            timeoutSpin.getValue(),
                            "clock",
                            aiBox.isSelected(),
                            avatarField.getText(),
                            "");
                }
            };
            save.setOnSucceeded(s -> {
                // Aplicar inmediatamente cambio de idioma (global, via Host)
                host.applyLanguage(save.getValue().language());
                // Aplicar nuevo timeout LOCK
                refreshLockTimeout(save.getValue().pinTimeoutMin());
                Alert ok = new Alert(Alert.AlertType.INFORMATION);
                ok.setTitle(t("profile.save.success.title"));
                ok.setHeaderText(t("profile.save.success.body"));
                ok.showAndWait();
            });
            save.setOnFailed(s -> showError(t("profile.fail.title"),
                    save.getException() == null ? "" : save.getException().getMessage()));
            start(save, "profile-save");
        });

        setCenterAnimated(scroll(root));
    }
}
