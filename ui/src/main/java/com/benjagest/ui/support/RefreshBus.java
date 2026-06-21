package com.benjagest.ui.support;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javafx.application.Platform;
import javafx.scene.Node;

/**
 * Bus de eventos central para refrescar listados sin tener que recordar
 * llamar a {@code load()} manualmente en cada {@code setOnSucceeded} de
 * cada diálogo de creación/edición/borrado.
 *
 * <p><b>Patrón.</b>
 * <ol>
 *   <li>El listado se suscribe a su topic cuando se monta:
 *       {@code RefreshBus.subscribe(TOPIC_PURCHASES, this::loadList, ownerNode);}
 *       — pasando un Node de la escena para auto-baja al desmontar.</li>
 *   <li>Cualquier flujo que cambia ese tipo de datos (Guardar gasto,
 *       Validar asiento, Eliminar cliente…) emite el topic al terminar
 *       con éxito: {@code RefreshBus.emit(TOPIC_PURCHASES);}</li>
 *   <li>Todos los suscriptores activos se ejecutan en JavaFX thread.</li>
 * </ol>
 *
 * <p><b>Auto-baja.</b> Pasar un {@link Node} en {@code subscribe} engancha
 * la baja al árbol de la escena: cuando el Node deja de tener Scene
 * (la pantalla se desmonta porque el usuario navegó a otra), el listener
 * se borra automáticamente. Si no se pasa Node, el listener vive hasta
 * que el usuario llame manualmente a {@link #unsubscribe}.
 *
 * <p><b>Thread safety.</b> emit puede llegar desde cualquier hilo
 * (background Task / async API). El bus garantiza ejecución del Runnable
 * en JavaFX Application Thread vía {@link Platform#runLater}.
 *
 * <p><b>Topics.</b> Constantes públicas que cubren todos los listados del
 * proyecto. Si haces un cambio que afecta a varios topics, emite todos:
 * <pre>RefreshBus.emit(TOPIC_PURCHASES, TOPIC_JOURNAL);</pre>
 */
public final class RefreshBus {

    // ---- Topics -----------------------------------------------------------

    public static final String TOPIC_PURCHASES = "purchases";
    public static final String TOPIC_SALES = "sales";
    public static final String TOPIC_JOURNAL = "journal";
    public static final String TOPIC_CUSTOMERS = "customers";
    public static final String TOPIC_SUPPLIERS = "suppliers";
    public static final String TOPIC_EMPLOYEES = "employees";
    public static final String TOPIC_BANK_ACCOUNTS = "bank_accounts";
    public static final String TOPIC_LOANS = "loans";
    public static final String TOPIC_FIXED_ASSETS = "fixed_assets";
    public static final String TOPIC_AEAT_MODELS = "aeat_models";
    public static final String TOPIC_RECURRING = "recurring";
    public static final String TOPIC_RULES = "rules";
    public static final String TOPIC_PORTFOLIO = "portfolio";
    public static final String TOPIC_DEHU = "dehu";
    public static final String TOPIC_TIMECARDS = "timecards";
    public static final String TOPIC_AUDIT = "audit";
    public static final String TOPIC_INVITATIONS = "invitations";
    public static final String TOPIC_CALENDAR = "calendar";
    public static final String TOPIC_ACCOUNTS_CATALOG = "accounts_catalog";
    public static final String TOPIC_TEMPLATES = "templates";
    public static final String TOPIC_VAT_TYPES = "vat_types";
    public static final String TOPIC_TIMECLOCK = "timeclock";
    public static final String TOPIC_LEAVE_REQUESTS = "leave_requests";
    public static final String TOPIC_PAYSLIPS = "payslips";

    // ---- Implementación ---------------------------------------------------

    private RefreshBus() {}

    private static final Map<String, Set<Runnable>> SUBS = new HashMap<>();

    /**
     * Suscribe el listener al topic. Si {@code ownerNode} no es null, el
     * listener se borra automáticamente cuando el Node abandona su Scene
     * (típico en JavaFX cuando navegas a otra pantalla).
     */
    public static synchronized void subscribe(String topic, Runnable listener, Node ownerNode) {
        if (topic == null || listener == null) return;
        SUBS.computeIfAbsent(topic, k -> new LinkedHashSet<>()).add(listener);
        if (ownerNode != null) {
            // Auto-baja al desmontar el Node de la escena. El listener
            // de sceneProperty es un weak natural en Node.
            ownerNode.sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene == null) {
                    unsubscribe(topic, listener);
                }
            });
        }
    }

    /** Atajo sin auto-baja. El caller es responsable de unsubscribe. */
    public static synchronized void subscribe(String topic, Runnable listener) {
        subscribe(topic, listener, null);
    }

    public static synchronized void unsubscribe(String topic, Runnable listener) {
        Set<Runnable> set = SUBS.get(topic);
        if (set != null) {
            set.remove(listener);
            if (set.isEmpty()) SUBS.remove(topic);
        }
    }

    /**
     * Emite el topic. Todos los suscriptores activos reciben la
     * notificación en el JavaFX thread.
     */
    public static void emit(String topic) {
        if (topic == null) return;
        Runnable[] copy;
        synchronized (RefreshBus.class) {
            Set<Runnable> set = SUBS.get(topic);
            if (set == null || set.isEmpty()) return;
            copy = set.toArray(new Runnable[0]);
        }
        Platform.runLater(() -> {
            for (Runnable r : copy) {
                try { r.run(); }
                catch (Exception ex) {
                    System.err.println("[RefreshBus] listener falló en topic "
                            + topic + ": " + ex.getMessage());
                }
            }
        });
    }

    /** Emite varios topics en un solo paso. */
    public static void emit(String... topics) {
        if (topics == null) return;
        for (String t : topics) emit(t);
    }

    /** Para tests / cleanup en hot reload. */
    public static synchronized void reset() {
        SUBS.clear();
    }
}
