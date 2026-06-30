package com.benjagest.ui.screens;

import com.benjagest.ui.model.*;
import com.benjagest.ui.service.AltaApiClient;
import com.benjagest.ui.support.Router;
import java.util.function.Function;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * AS-5 — Pestaña "Acuerdo TPB" (acuerdo previo de facturacion por tercero,
 * RD 1619/2012 art. 5) de la ficha de cliente. Proponer, firmar (Magic Link +
 * OTP para no vinculados; PIN para vinculados), descargar propuesta/firmado,
 * reparar serie, revocar. Polling cada 5s para auto-detectar la firma del
 * cliente. ZONA CALIENTE LEGAL: movido tal cual, sin reescritura.
 *
 * <p>Los callbacks {@code onActivated}/{@code onRevoked} (que el composite usa
 * para anadir/quitar la pestana Facturacion en caliente al firmar/revocar) se
 * pasan a {@link #buildTab}. El shell conserva
 * {@code buildClientTpbAgreementTab(...)} como wrapper (1 caller). Los
 * {@code humanizeTpb*} siguen en el shell (compartidos con la vista del cliente)
 * y aqui se copian.
 */
public class ClientTpbAgreementScreen extends ScreenBase {

    private final AltaApiClient altaApiClient;
    private javafx.scene.Node tpbRoot;

    public ClientTpbAgreementScreen(AltaApiClient altaApiClient,
                                    Function<String, String> tt, Router router) {
        super(tt, router);
        this.altaApiClient = altaApiClient;
    }

    public Node buildTab(
            com.benjagest.ui.model.ManagedClientEntry client, boolean isLinked,
            Runnable onActivated, Runnable onRevoked) {
        VBox root = new VBox(14);
        root.setPadding(new Insets(20));

        Label title = label(t("tpb.title"), "settings-section-title");
        Label hint = new Label(t("tpb.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        VBox stateSlot = new VBox(10);

        // Guardamos el ultimo estado conocido para detectar transiciones
        // (p.ej. PROPOSED -> ACTIVE cuando el cliente firma via magic link).
        // Sin esto el polling sobreescribiria el UI cada 5s aunque nada
        // hubiera cambiado.
        final String[] lastStatus = new String[]{ null };

        Runnable[] reloadHolder = new Runnable[1];
        Runnable realReload = () -> {
            stateSlot.getChildren().clear();
            Task<com.benjagest.ui.model.TpbAgreementEntry> task = new Task<>() {
                @Override
                protected com.benjagest.ui.model.TpbAgreementEntry call() throws Exception {
                    return altaApiClient.tpbFindCurrent(client.id());
                }
            };
            task.setOnSucceeded(ev -> {
                var a = task.getValue();
                renderTpbState(stateSlot, a, client, isLinked, reloadHolder[0]);
                String newStatus = a == null ? null : a.status();
                String prev = lastStatus[0];
                lastStatus[0] = newStatus;
                // Transicion a ACTIVE: notificar al parent para que pueda
                // anadir el tab Facturacion en caliente.
                if ("ACTIVE".equals(newStatus) && !"ACTIVE".equals(prev)
                        && onActivated != null) {
                    onActivated.run();
                }
                // Transicion a REVOKED: simetrico, quitar Facturacion.
                if ("REVOKED".equals(newStatus) && !"REVOKED".equals(prev)
                        && onRevoked != null) {
                    onRevoked.run();
                }
            });
            task.setOnFailed(ev -> {
                Label err = new Label(t("tpb.fail.load") + " "
                        + (task.getException() == null ? "" : task.getException().getMessage()));
                err.setWrapText(true);
                err.setStyle("-fx-text-fill: #b91c1c;");
                stateSlot.getChildren().add(err);
            });
            start(task, "tpb-load");
        };
        reloadHolder[0] = realReload;
        realReload.run();

        // Polling cada 5 segundos para auto-detectar firma del cliente
        // (magic link) sin necesidad de que el asesor pulse "Recargar".
        // Se inicia siempre; el Timeline se detiene cuando el nodo se
        // quita de la escena (parentProperty listener).
        javafx.animation.Timeline poller = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(
                        javafx.util.Duration.seconds(5),
                        ev -> {
                            // Poleamos mientras no este REVOKED (estado
                            // terminal). PROPOSED → detectar firma del
                            // cliente. ACTIVE → detectar revocacion del
                            // cliente via magic link.
                            String s = lastStatus[0];
                            if (!"REVOKED".equals(s)) {
                                realReload.run();
                            }
                        }));
        poller.setCycleCount(javafx.animation.Animation.INDEFINITE);
        // Lifecycle: cuando el tab se desvincule de la escena (asesor
        // cierra el cliente, navega a otro lado), detenemos el polling
        // para no hacer requests fantasma.
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                poller.stop();
            } else {
                poller.play();
            }
        });
        poller.play();

        // Boton recargar manual (por si el asesor quiere forzar antes
        // del proximo tick del Timeline).
        Button reloadBtn = new Button(t("tpb.action.reload"));
        reloadBtn.setGraphic(icon("fas-sync-alt"));
        reloadBtn.setOnAction(e -> realReload.run());
        HBox titleRow = new HBox(12, title, new Region(), reloadBtn);
        HBox.setHgrow(titleRow.getChildren().get(1), Priority.ALWAYS);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(titleRow, hint, stateSlot);
        tpbRoot = root;
        return root;
    }

    private void renderTpbState(VBox slot,
                                  com.benjagest.ui.model.TpbAgreementEntry a,
                                  com.benjagest.ui.model.ManagedClientEntry client,
                                  boolean isLinked,
                                  Runnable reload) {
        slot.getChildren().clear();
        if (a == null) {
            // Sin acuerdo — botón proponer
            Label empty = new Label(t("tpb.empty"));
            empty.setWrapText(true);
            slot.getChildren().add(empty);
            if (!isLinked) {
                // Aviso temprano: sin vinculo no hay forma legal de
                // firmar el acuerdo. No bloqueamos proponerlo (el
                // backend lo permite), pero advertimos que el cliente
                // tendra que vincularse para firmar.
                Label warn = new Label(t("tpb.propose.needs_link"));
                warn.setWrapText(true);
                warn.setStyle("-fx-text-fill: #b91c1c; -fx-font-weight: bold;");
                slot.getChildren().add(warn);
            }
            Button propose = new Button(t("tpb.propose.button"));
            propose.setGraphic(icon("fas-plus"));
            propose.getStyleClass().add("button-primary");
            propose.setOnAction(e -> showTpbProposeDialog(client, reload));
            slot.getChildren().add(propose);
            return;
        }
        // Estado del acuerdo
        GridPane g = new GridPane();
        g.setHgap(20); g.setVgap(6);
        int r = 0;
        g.add(new Label(t("tpb.field.status")), 0, r);
        Label statusLbl = new Label(humanizeTpbStatus(a.status()));
        statusLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: "
                + (a.isActive() ? "#16a34a" : a.isPending() ? "#d97706" : "#64748b") + ";");
        g.add(statusLbl, 1, r++);
        g.add(new Label(t("tpb.field.scope")), 0, r);
        g.add(new Label(humanizeTpbScope(a)), 1, r++);
        if (a.signedAt() != null && !a.signedAt().isBlank()) {
            g.add(new Label(t("tpb.field.signed_at")), 0, r);
            g.add(new Label(a.signedAt()), 1, r++);
            g.add(new Label(t("tpb.field.signed_method")), 0, r);
            g.add(new Label(humanizeTpbMethod(a.signedMethod())), 1, r++);
        }
        slot.getChildren().add(g);

        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_LEFT);

        if (a.isPending()) {
            if (!isLinked) {
                // Cliente NO vinculado — flujo Magic Link + OTP (V104,
                // decision Benjamin 2026-06-12 tras bloquear offline-PDF).
                // La asesoria envia al email del cliente un enlace de
                // firma electronica simple (eIDAS art. 25). El cliente
                // abre el enlace en cualquier navegador, lee el PDF, e
                // introduce el OTP que tambien le ha llegado por email.
                Label hint = new Label(t("tpb.magic.hint"));
                hint.setWrapText(true);
                hint.getStyleClass().add("settings-hint");

                TextField emailField = new TextField();
                emailField.setPromptText(t("tpb.magic.email_prompt"));
                emailField.setPrefColumnCount(28);
                if (client.email() != null && !client.email().isBlank()) {
                    emailField.setText(client.email());
                }

                Button send = new Button(t("tpb.magic.send"));
                send.setGraphic(icon("fas-paper-plane"));
                send.getStyleClass().add("button-primary");
                send.setOnAction(e -> tpbSendMagicLinkAction(
                        a.id(), emailField.getText(), reload));

                HBox row = new HBox(8, emailField, send);
                row.setAlignment(Pos.CENTER_LEFT);
                slot.getChildren().addAll(hint, row);
            } else {
                // Cliente vinculado — esperando firma con PIN desde su lado
                Label wait = new Label(t("tpb.pending.waiting_client"));
                wait.setWrapText(true);
                wait.getStyleClass().add("settings-hint");
                slot.getChildren().add(wait);
            }
        } else if (a.isActive()) {
            Button dl = new Button(t("tpb.signed.download"));
            dl.setGraphic(icon("fas-file-pdf"));
            dl.setOnAction(e -> tpbDownloadSignedPdfAction(a.id()));
            actions.getChildren().add(dl);
            // Boton de diagnostico: si el acuerdo esta ACTIVE y cubre
            // ventas, fuerza la creacion de la serie TPB. Util para
            // reparar acuerdos firmados antes del fix de auto-repair
            // (caso Benjamin 2026-06-12).
            if (a.scopeSales()) {
                Button repair = new Button(t("tpb.action.repair_series"));
                repair.setGraphic(icon("fas-wrench"));
                repair.setOnAction(e -> tpbRepairSeriesAction(a.id(), reload));
                actions.getChildren().add(repair);
            }
            // V105: si el cliente firmo via Magic Link (no vinculado),
            // ofrecemos a la asesoria reenviar el enlace de revocacion
            // por si el cliente perdio el original.
            if (!isLinked && "MAGIC_LINK_OTP".equals(a.signedMethod())) {
                Button resend = new Button(t("tpb.action.resend_revoke"));
                resend.setGraphic(icon("fas-paper-plane"));
                resend.setOnAction(e -> tpbResendRevokeLinkAction(a.id()));
                actions.getChildren().add(resend);
            }
        }

        Button revoke = new Button(t("tpb.revoke"));
        revoke.setGraphic(icon("fas-ban"));
        revoke.setOnAction(e -> tpbRevokeAction(a.id(), reload));
        actions.getChildren().add(revoke);
        slot.getChildren().add(actions);
    }

    private void tpbResendRevokeLinkAction(String agreementId) {
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return altaApiClient.tpbResendRevokeLink(agreementId);
            }
        };
        task.setOnSucceeded(e -> showInfo(t("tpb.resend_revoke.ok.title"),
                t("tpb.resend_revoke.ok.body")));
        task.setOnFailed(e -> showError(t("tpb.resend_revoke.fail.title"),
                task.getException() == null ? "" : task.getException().getMessage()));
        start(task, "tpb-resend-revoke");
    }

    private void tpbSendMagicLinkAction(String agreementId, String email, Runnable reload) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            showError(t("tpb.magic.fail.title"), t("tpb.magic.fail.bad_email"));
            return;
        }
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return altaApiClient.tpbSendMagicLink(agreementId, email);
            }
        };
        task.setOnSucceeded(e -> {
            showInfo(t("tpb.magic.ok.title"), t("tpb.magic.ok.body") + " " + email);
            if (reload != null) reload.run();
        });
        task.setOnFailed(e -> showError(t("tpb.magic.fail.title"),
                task.getException() == null ? "" : task.getException().getMessage()));
        start(task, "tpb-send-magic-link");
    }

    private void tpbRepairSeriesAction(String agreementId, Runnable reload) {
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return altaApiClient.tpbEnsureSeries(agreementId);
            }
        };
        task.setOnSucceeded(e -> {
            String json = task.getValue();
            java.util.regex.Matcher mCode = java.util.regex.Pattern
                    .compile("\"code\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            java.util.regex.Matcher mCreated = java.util.regex.Pattern
                    .compile("\"created\"\\s*:\\s*(true|false)").matcher(json);
            String code = mCode.find() ? mCode.group(1) : "?";
            boolean created = mCreated.find() && "true".equals(mCreated.group(1));
            showInfo(t("tpb.repair.ok.title"),
                    (created ? t("tpb.repair.ok.created") : t("tpb.repair.ok.existed"))
                    + " " + code);
            if (reload != null) reload.run();
        });
        task.setOnFailed(e -> showError(t("tpb.repair.fail.title"),
                task.getException() == null ? "" : task.getException().getMessage()));
        start(task, "tpb-repair-series");
    }

    private void showTpbProposeDialog(com.benjagest.ui.model.ManagedClientEntry client,
                                        Runnable onSaved) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle(t("tpb.propose.dialog.title"));
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        CheckBox sales = new CheckBox(t("tpb.scope.sales"));
        CheckBox purchases = new CheckBox(t("tpb.scope.purchases"));
        CheckBox taxModels = new CheckBox(t("tpb.scope.tax_models"));
        sales.setSelected(true);

        Label intro = new Label(t("tpb.propose.dialog.intro"));
        intro.setWrapText(true);
        intro.getStyleClass().add("settings-hint");
        VBox content = new VBox(10, intro, sales, purchases, taxModels);
        content.setPadding(new Insets(16));
        content.setPrefWidth(440);
        dlg.getDialogPane().setContent(content);

        dlg.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            if (!sales.isSelected() && !purchases.isSelected() && !taxModels.isSelected()) {
                showError(t("tpb.propose.fail.title"), t("tpb.propose.fail.empty_scope"));
                return;
            }
            String advisoryId = com.benjagest.ui.service.AuthSession.get().activeCompanyId();
            Task<com.benjagest.ui.model.TpbAgreementEntry> task = new Task<>() {
                @Override
                protected com.benjagest.ui.model.TpbAgreementEntry call() throws Exception {
                    return altaApiClient.tpbPropose(advisoryId, client.id(),
                            sales.isSelected(), purchases.isSelected(), taxModels.isSelected());
                }
            };
            task.setOnSucceeded(e -> onSaved.run());
            task.setOnFailed(e -> showError(t("tpb.propose.fail.title"),
                    task.getException() == null ? "" : task.getException().getMessage()));
            start(task, "tpb-propose");
        });
    }

    private void tpbDownloadProposalPdfAction(String agreementId) {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle(t("tpb.proposal.download"));
        fc.setInitialFileName("acuerdo-facturacion-tercero.pdf");
        fc.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("PDF", "*.pdf"));
        java.io.File target = fc.showSaveDialog(window());
        if (target == null) return;
        Task<byte[]> dl = new Task<>() {
            @Override protected byte[] call() throws Exception {
                return altaApiClient.tpbDownloadProposalPdf(agreementId);
            }
        };
        dl.setOnSucceeded(s -> {
            try { java.nio.file.Files.write(target.toPath(), dl.getValue());
                showInfo(t("tpb.proposal.download.ok"), target.getAbsolutePath());
            } catch (java.io.IOException ex) {
                showError(t("tpb.fail.io"), ex.getMessage());
            }
        });
        dl.setOnFailed(s -> showError(t("tpb.fail.io"),
                dl.getException() == null ? "" : dl.getException().getMessage()));
        start(dl, "tpb-dl-proposal");
    }

    private void tpbDownloadSignedPdfAction(String agreementId) {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle(t("tpb.signed.download"));
        fc.setInitialFileName("acuerdo-firmado.pdf");
        fc.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("PDF", "*.pdf"));
        java.io.File target = fc.showSaveDialog(window());
        if (target == null) return;
        Task<byte[]> dl = new Task<>() {
            @Override protected byte[] call() throws Exception {
                return altaApiClient.tpbDownloadSignedPdf(agreementId);
            }
        };
        dl.setOnSucceeded(s -> {
            try { java.nio.file.Files.write(target.toPath(), dl.getValue());
                showInfo(t("tpb.signed.download.ok"), target.getAbsolutePath());
            } catch (java.io.IOException ex) {
                showError(t("tpb.fail.io"), ex.getMessage());
            }
        });
        dl.setOnFailed(s -> showError(t("tpb.fail.io"),
                dl.getException() == null ? "" : dl.getException().getMessage()));
        start(dl, "tpb-dl-signed");
    }

    private void tpbUploadSignedAction(String agreementId, Runnable onDone) {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle(t("tpb.proposal.upload_signed"));
        fc.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("PDF", "*.pdf"));
        java.io.File f = fc.showOpenDialog(window());
        if (f == null) return;
        Task<com.benjagest.ui.model.TpbAgreementEntry> up = new Task<>() {
            @Override
            protected com.benjagest.ui.model.TpbAgreementEntry call() throws Exception {
                return altaApiClient.tpbSignWithOfflinePdf(agreementId, f);
            }
        };
        up.setOnSucceeded(s -> onDone.run());
        up.setOnFailed(s -> showError(t("tpb.fail.upload"),
                up.getException() == null ? "" : up.getException().getMessage()));
        start(up, "tpb-up-signed");
    }

    private void tpbRevokeAction(String agreementId, Runnable onDone) {
        javafx.scene.control.TextInputDialog td = new javafx.scene.control.TextInputDialog();
        td.setTitle(t("tpb.revoke"));
        td.setHeaderText(t("tpb.revoke.prompt"));
        String reason = td.showAndWait().orElse(null);
        if (reason == null) return;
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                altaApiClient.tpbRevoke(agreementId, reason);
                return null;
            }
        };
        task.setOnSucceeded(s -> onDone.run());
        task.setOnFailed(s -> showError(t("tpb.fail.revoke"),
                task.getException() == null ? "" : task.getException().getMessage()));
        start(task, "tpb-revoke");
    }

    // ----- helpers locales (copias stateless; siguen en el shell, compartidas
    //       con la vista TPB del cliente) -----

    private javafx.stage.Window window() {
        return tpbRoot == null || tpbRoot.getScene() == null ? null : tpbRoot.getScene().getWindow();
    }

    private String humanizeTpbStatus(String s) {
        if (s == null) return "";
        return switch (s) {
            case "PROPOSED" -> t("tpb.status.proposed");
            case "ACTIVE"   -> t("tpb.status.active");
            case "REVOKED"  -> t("tpb.status.revoked");
            default         -> s;
        };
    }

    private String humanizeTpbMethod(String m) {
        if (m == null) return "";
        return switch (m) {
            case "PIN_SESSION"    -> t("tpb.method.pin_session");
            case "OFFLINE_PDF"    -> t("tpb.method.offline_pdf");
            case "MAGIC_LINK_OTP" -> t("tpb.method.magic_link_otp");
            default               -> m;
        };
    }

    private String humanizeTpbScope(com.benjagest.ui.model.TpbAgreementEntry a) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (a.scopeSales()) parts.add(t("tpb.scope.sales"));
        if (a.scopePurchases()) parts.add(t("tpb.scope.purchases"));
        if (a.scopeTaxModels()) parts.add(t("tpb.scope.tax_models"));
        return String.join(" · ", parts);
    }
}
