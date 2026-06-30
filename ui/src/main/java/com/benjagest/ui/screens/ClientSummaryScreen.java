package com.benjagest.ui.screens;

import com.benjagest.ui.model.ManagedClientEntry;
import com.benjagest.ui.service.LaborApiClient;
import com.benjagest.ui.support.Router;
import java.time.LocalDate;
import java.util.function.Function;
import javafx.beans.property.ObjectProperty;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * AS-3 — Pestaña "Resumen" de la ficha de cliente: identidad fiscal (nombre,
 * NIF, tipo, email, ciudad) + KPIs reales (ventas/gastos/IVA/303/borradores)
 * cuando procede. Extraída del God Object.
 *
 * <p>El bloque de KPIs ({@code buildClientKpisBlock}) se comparte con la
 * pestaña "Ventas y Gastos" del cliente no vinculado, así que sigue viviendo en
 * el shell y se inyecta vía {@link Host}. Movido tal cual; sin CSS/i18n nuevos.
 */
public class ClientSummaryScreen extends ScreenBase {

    /** Provee el bloque de KPIs (vive en el shell, compartido con otras pestañas). */
    @FunctionalInterface
    public interface Host {
        Node buildClientKpisBlock(ObjectProperty<LocalDate> fromProp, ObjectProperty<LocalDate> toProp);
    }

    private final LaborApiClient laborApiClient;
    private final Host host;

    public ClientSummaryScreen(LaborApiClient laborApiClient,
                               Function<String, String> tt, Router router, Host host) {
        super(tt, router);
        this.laborApiClient = laborApiClient;
        this.host = host;
    }

    public Node buildTab(ManagedClientEntry client, boolean showKpis) {
        Label title = label(t("advisory.client.summary.title"), "settings-section-title");
        Label hint = new Label(t("advisory.client.summary.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-hint");

        GridPane g = new GridPane();
        g.setHgap(20); g.setVgap(8);
        // Etiquetas con color explícito: sin -fx-text-fill heredarían el texto
        // claro del tema sobre el fondo blanco → casi invisibles (bug Benjamin
        // 2026-06-24). Campo en gris medio, valor en oscuro.
        java.util.function.Function<String, Label> fld = s -> {
            Label l = new Label(s);
            l.setStyle("-fx-text-fill: #64748b;");
            return l;
        };
        java.util.function.Function<String, Label> val = s -> {
            Label l = new Label(s);
            l.setStyle("-fx-text-fill: #1e293b; -fx-font-weight: bold;");
            return l;
        };
        int r = 0;
        g.add(fld.apply(t("advisory.client.field.legal_name")), 0, r);
        g.add(val.apply(client.legalName() == null ? "—" : client.legalName()), 1, r++);
        g.add(fld.apply(t("advisory.client.field.nif")), 0, r);
        g.add(val.apply(client.taxIdentifier() == null ? "—" : client.taxIdentifier()), 1, r++);
        if (client.companyType() != null) {
            g.add(fld.apply(t("advisory.client.field.type")), 0, r);
            Label typeVal = val.apply(localizedEnum("customer_type", client.companyType()));
            g.add(typeVal, 1, r++);
            // El "Tipo" se afina con la Forma jurídica (advisory_config.legal_form)
            // si está fijada: una empresa vinculada cuyo titular es autónomo debe
            // verse como Autónomo, no como Empresa (decisión Benjamin 2026-06-30).
            // Si no hay forma jurídica, se queda el companyType/customer_type.
            Task<com.benjagest.ui.model.ClientConfigModels.AdvisoryConfigEntry> typeTask = new Task<>() {
                @Override protected com.benjagest.ui.model.ClientConfigModels.AdvisoryConfigEntry call() throws Exception {
                    return laborApiClient.getClientAdvisoryConfig();
                }
            };
            typeTask.setOnSucceeded(ev -> {
                var cfg = typeTask.getValue();
                String derived = typeFromLegalForm(cfg == null ? null : cfg.legalForm());
                if (derived != null) typeVal.setText(derived);
            });
            typeTask.setOnFailed(ev -> { /* silencio: se queda el tipo base */ });
            start(typeTask, "client-summary-type");
        }
        if (client.email() != null) {
            g.add(fld.apply(t("advisory.client.field.email")), 0, r);
            g.add(val.apply(client.email()), 1, r++);
        }
        if (client.city() != null && !client.city().isBlank()) {
            g.add(fld.apply(t("advisory.client.field.city")), 0, r);
            g.add(val.apply(client.city()), 1, r++);
        }

        VBox body = new VBox(14, title, hint, g);
        // KPIs reales (Slice 2A). Solo los pintamos aquí si el cliente
        // está vinculado — en el NO vinculado los KPIs ya viven en la
        // pestaña "Ventas y Gastos" para estar junto a los listados,
        // y duplicarlos en el Resumen confundiría.
        if (showKpis) {
            Label kpisTitle = label(t("advisory.client.kpis.title"),
                    "settings-section-title");
            LocalDate todaySum = LocalDate.now();
            int currentQuarterSum = (todaySum.getMonthValue() - 1) / 3 + 1;
            LocalDate initFromSum = LocalDate.of(todaySum.getYear(),
                    (currentQuarterSum - 1) * 3 + 1, 1);
            LocalDate initToSum = initFromSum.plusMonths(3).minusDays(1);
            javafx.beans.property.ObjectProperty<LocalDate> fromPropSum =
                    new javafx.beans.property.SimpleObjectProperty<>(initFromSum);
            javafx.beans.property.ObjectProperty<LocalDate> toPropSum =
                    new javafx.beans.property.SimpleObjectProperty<>(initToSum);
            Node kpisBlock = host.buildClientKpisBlock(fromPropSum, toPropSum);
            body.getChildren().addAll(new Separator(), kpisTitle, kpisBlock);
        }
        body.setPadding(new Insets(20));
        return body;
    }

    /**
     * Traduce la forma jurídica a la etiqueta de "Tipo" (Autónomo/Empresa).
     * AUTONOMO → Autónomo; sociedades (SL/SA/SLU/SC/CB/COOPERATIVA) → Empresa.
     * Devuelve null si no hay forma jurídica o es OTRO → se mantiene el
     * customer_type/company_type base.
     */
    private String typeFromLegalForm(String legalForm) {
        if (legalForm == null || legalForm.isBlank()) return null;
        return switch (legalForm.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "AUTONOMO" -> t("enum.customer_type.SELF_EMPLOYED");
            case "SL", "SA", "SLU", "SC", "CB", "COOPERATIVA" -> t("enum.customer_type.COMPANY");
            default -> null;
        };
    }
}
