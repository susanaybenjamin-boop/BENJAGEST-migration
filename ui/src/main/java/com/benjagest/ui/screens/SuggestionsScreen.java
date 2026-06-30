package com.benjagest.ui.screens;

import com.benjagest.ui.model.SuggestionEntry;
import com.benjagest.ui.service.AltaApiClient;
import com.benjagest.ui.support.BackendErrors;
import com.benjagest.ui.support.Dialogs;
import com.benjagest.ui.support.Icons;
import com.benjagest.ui.support.Router;
import com.benjagest.ui.support.TableSelectionHelper;
import com.benjagest.ui.support.UiBuilders;
import java.util.function.Function;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Modulo SUGERENCIAS (buzon de sugerencias del usuario). Extraido del God
 * Object en UIR-5 como primera pantalla-plantilla de la Fase 3.
 *
 * <p>Sigue el contrato de {@link AccountingScreen}: recibe su ApiClient + la
 * funcion de traduccion + el {@link Router} del shell, y expone {@link #buildView()}.
 * El shell solo hace currentModule/recordNav/select y monta el nodo.
 */
public class SuggestionsScreen {

    private final AltaApiClient api;
    private final Function<String, String> tt;
    private final Router router;

    public SuggestionsScreen(AltaApiClient api, Function<String, String> tt, Router router) {
        this.api = api;
        this.tt = tt;
        this.router = router;
    }

    // ---- delegados a los helpers compartidos (UIR-1..5) ----
    private String t(String key) { return tt.apply(key); }
    private Node icon(String literal) { return Icons.icon(literal); }
    private Label label(String text, String styleClass) { return UiBuilders.label(text, styleClass); }
    private ScrollPane scroll(VBox content) { return UiBuilders.scroll(content); }
    private void showError(String title, String message) { Dialogs.error(title, BackendErrors.humanize(message)); }
    private void start(Task<?> task, String name) { router.runTask(task, name); }

    public Node buildView() {
        VBox root = new VBox(16);
        root.getStyleClass().add("content");

        Label header = label(t("suggestions.title"), "settings-section-title");
        Label hint = new Label(t("suggestions.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        Button newBtn = new Button(t("suggestions.btn.new"));
        newBtn.setGraphic(icon("fas-plus"));
        newBtn.getStyleClass().add("button-primary");

        TableView<SuggestionEntry> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(t("suggestions.empty")));
        TableSelectionHelper.install(table);

        TableColumn<SuggestionEntry, String> cDate =
                new TableColumn<>(t("suggestions.col.date"));
        cDate.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().createdAt() == null || c.getValue().createdAt().length() < 10
                        ? c.getValue().createdAt()
                        : c.getValue().createdAt().substring(0, 10)));
        cDate.setPrefWidth(110);
        TableColumn<SuggestionEntry, String> cTitle =
                new TableColumn<>(t("suggestions.col.title"));
        cTitle.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().title()));
        TableColumn<SuggestionEntry, String> cCat =
                new TableColumn<>(t("suggestions.col.category"));
        cCat.setCellValueFactory(c -> new SimpleStringProperty(
                humanizeSuggestionCategory(c.getValue().category())));
        cCat.setPrefWidth(120);
        TableColumn<SuggestionEntry, String> cStatus =
                new TableColumn<>(t("suggestions.col.status"));
        cStatus.setCellValueFactory(c -> new SimpleStringProperty(
                humanizeSuggestionStatus(c.getValue().status())));
        cStatus.setPrefWidth(120);
        TableColumn<SuggestionEntry, String> cAnswer =
                new TableColumn<>(t("suggestions.col.answer"));
        cAnswer.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().answer() == null || c.getValue().answer().isBlank()
                        ? t("suggestions.no_answer") : c.getValue().answer()));

        table.getColumns().addAll(java.util.List.of(cDate, cTitle, cCat, cStatus, cAnswer));

        Button closeBtn = new Button(t("suggestions.btn.close"));
        closeBtn.setGraphic(icon("fas-check"));
        closeBtn.setDisable(true);
        Button delBtn = new Button(t("suggestions.btn.delete"));
        delBtn.setGraphic(icon("fas-trash"));
        delBtn.setDisable(true);

        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            boolean sel = nv != null;
            closeBtn.setDisable(!sel || "closed".equals(nv == null ? "" : nv.status()));
            delBtn.setDisable(!sel);
        });

        HBox actionBar = new HBox(8, newBtn, closeBtn, delBtn);
        actionBar.setAlignment(Pos.CENTER_LEFT);

        Runnable reload = () -> {
            Task<java.util.List<SuggestionEntry>> task = new Task<>() {
                @Override protected java.util.List<SuggestionEntry> call() throws Exception {
                    return api.listSuggestions();
                }
            };
            task.setOnSucceeded(ev -> table.setItems(FXCollections.observableArrayList(task.getValue())));
            task.setOnFailed(ev -> showError(t("suggestions.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "suggestions-load");
        };

        newBtn.setOnAction(ev -> showSuggestionForm(reload));
        closeBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Task<Void> t = new Task<>() {
                @Override protected Void call() throws Exception {
                    api.updateSuggestionStatus(sel.id(), "closed");
                    return null;
                }
            };
            t.setOnSucceeded(s -> reload.run());
            t.setOnFailed(s -> showError(t("suggestions.fail.title"),
                    t.getException() == null ? "" : t.getException().getMessage()));
            start(t, "suggestions-close");
        });
        delBtn.setOnAction(ev -> {
            var sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(t("suggestions.confirm.delete.title"));
            confirm.setHeaderText(t("suggestions.confirm.delete.body"));
            confirm.showAndWait().ifPresent(rsp -> {
                if (rsp == ButtonType.OK) {
                    Task<Void> t = new Task<>() {
                        @Override protected Void call() throws Exception {
                            api.deleteSuggestion(sel.id());
                            return null;
                        }
                    };
                    t.setOnSucceeded(s -> reload.run());
                    t.setOnFailed(s -> showError(t("suggestions.fail.title"),
                            t.getException() == null ? "" : t.getException().getMessage()));
                    start(t, "suggestions-delete");
                }
            });
        });

        reload.run();

        VBox.setVgrow(table, Priority.ALWAYS);
        root.getChildren().addAll(header, hint, actionBar, table);
        return scroll(root);
    }

    private void showSuggestionForm(Runnable onSaved) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(t("suggestions.form.title"));
        dialog.setHeaderText(t("suggestions.form.header"));

        TextField titleField = new TextField();
        titleField.setPromptText(t("suggestions.form.title_prompt"));
        TextArea descField = new TextArea();
        descField.setPromptText(t("suggestions.form.desc_prompt"));
        descField.setPrefRowCount(6);
        descField.setWrapText(true);
        ComboBox<String> catCombo = new ComboBox<>(FXCollections.observableArrayList(
                "general", "improvement", "module", "bug", "other"));
        catCombo.setValue("general");
        catCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String s) {
                return s == null ? "" : humanizeSuggestionCategory(s);
            }
            @Override public String fromString(String s) { return s; }
        });

        VBox form = new VBox(10,
                new Label(t("suggestions.form.field.title")), titleField,
                new Label(t("suggestions.form.field.category")), catCombo,
                new Label(t("suggestions.form.field.description")), descField);
        form.setPadding(new Insets(16));
        form.setPrefWidth(520);

        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        Button okBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.setText(t("suggestions.form.btn.send"));

        dialog.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                if (titleField.getText() == null || titleField.getText().isBlank()
                        || descField.getText() == null || descField.getText().isBlank()) {
                    showError(t("suggestions.form.missing.title"),
                            t("suggestions.form.missing.body"));
                    return null;
                }
                Task<Void> t = new Task<>() {
                    @Override protected Void call() throws Exception {
                        api.createSuggestion(
                                titleField.getText().trim(),
                                descField.getText().trim(),
                                catCombo.getValue());
                        return null;
                    }
                };
                t.setOnSucceeded(s -> onSaved.run());
                t.setOnFailed(s -> showError(t("suggestions.fail.title"),
                        t.getException() == null ? "" : t.getException().getMessage()));
                start(t, "suggestions-create");
            }
            return null;
        });
        dialog.showAndWait();
    }

    private String humanizeSuggestionCategory(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return switch (raw.trim().toLowerCase()) {
            case "general" -> t("suggestions.cat.general");
            case "improvement" -> t("suggestions.cat.improvement");
            case "module" -> t("suggestions.cat.module");
            case "bug" -> t("suggestions.cat.bug");
            case "other" -> t("suggestions.cat.other");
            default -> raw;
        };
    }

    private String humanizeSuggestionStatus(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return switch (raw.trim().toLowerCase()) {
            case "new" -> t("suggestions.status.new");
            case "read" -> t("suggestions.status.read");
            case "answered" -> t("suggestions.status.answered");
            case "closed" -> t("suggestions.status.closed");
            default -> raw;
        };
    }
}
