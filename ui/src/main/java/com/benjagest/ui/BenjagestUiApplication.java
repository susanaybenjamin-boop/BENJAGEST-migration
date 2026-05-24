package com.benjagest.ui;

import com.benjagest.ui.model.BackendStatus;
import com.benjagest.ui.service.BackendStatusService;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class BenjagestUiApplication extends Application {

    private final BackendStatusService backendStatusService = new BackendStatusService();
    private final Label statusLabel = new Label("Backend pendiente de comprobar");
    private final Button checkBackendButton = new Button("Comprobar backend");

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");

        Label title = new Label("BENJAGEST");
        title.getStyleClass().add("app-title");

        Label subtitle = new Label("UI JavaFX conectada por HTTP al backend Java");
        subtitle.getStyleClass().add("app-subtitle");

        VBox header = new VBox(4, title, subtitle);
        header.setAlignment(Pos.CENTER_LEFT);

        statusLabel.getStyleClass().add("status-label");
        checkBackendButton.setOnAction(event -> checkBackend());

        HBox actions = new HBox(12, checkBackendButton, statusLabel);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(24, header, actions);
        content.setPadding(new Insets(32));
        content.setAlignment(Pos.TOP_LEFT);
        root.setCenter(content);

        Scene scene = new Scene(root, 760, 420);
        scene.getStylesheets().add(getClass().getResource("/com/benjagest/ui/app.css").toExternalForm());

        stage.setTitle("BENJAGEST");
        stage.setScene(scene);
        stage.show();
    }

    private void checkBackend() {
        checkBackendButton.setDisable(true);
        statusLabel.setText("Comprobando...");

        Task<BackendStatus> task = new Task<>() {
            @Override
            protected BackendStatus call() {
                return backendStatusService.fetchHealth();
            }
        };

        task.setOnSucceeded(event -> {
            BackendStatus status = task.getValue();
            statusLabel.setText(status.message());
            checkBackendButton.setDisable(false);
        });
        task.setOnFailed(event -> {
            statusLabel.setText("No se pudo comprobar el backend");
            checkBackendButton.setDisable(false);
        });

        Thread worker = new Thread(task, "backend-health-check");
        worker.setDaemon(true);
        worker.start();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
