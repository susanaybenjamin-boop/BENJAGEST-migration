package com.benjagest.ui.support;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * MULTIMON — Recuerda en qué pantalla y posición se cerró la ventana principal y
 * la reabre AHÍ si esa pantalla sigue conectada; si no hay dato o esa pantalla ya
 * no existe, cae al comportamiento por defecto (centrada en la primaria).
 *
 * <p>Persistencia en {@code ~/.benjagest/ui-window.properties} (la UI corre como
 * el usuario interactivo). Todo best-effort: si algo falla, no rompe el arranque.
 *
 * <p>Guarda SIEMPRE la geometría "restaurada" (no la de maximizado): unos
 * listeners recuerdan la última posición/tamaño mientras la ventana NO está
 * maximizada, y aparte se guarda el flag {@code maximized} para re-maximizar en
 * la pantalla correcta.
 */
public final class WindowGeometry {

    private WindowGeometry() {}

    private static final Path FILE = Paths.get(
            System.getProperty("user.home"), ".benjagest", "ui-window.properties");

    // Última geometría con la ventana NO maximizada (la que queremos persistir).
    private static double lastX = Double.NaN, lastY = Double.NaN, lastW, lastH;
    private static boolean tracking;

    /** Engancha listeners para recordar la geometría restaurada. Llamar tras {@link #restore}. */
    public static void track(Stage stage) {
        Runnable update = () -> {
            if (!stage.isMaximized() && !stage.isIconified()
                    && stage.getWidth() > 0 && stage.getHeight() > 0) {
                lastX = stage.getX();
                lastY = stage.getY();
                lastW = stage.getWidth();
                lastH = stage.getHeight();
            }
        };
        stage.xProperty().addListener((o, a, b) -> update.run());
        stage.yProperty().addListener((o, a, b) -> update.run());
        stage.widthProperty().addListener((o, a, b) -> update.run());
        stage.heightProperty().addListener((o, a, b) -> update.run());
        tracking = true;
        update.run();
    }

    /** Guarda la geometría restaurada + el flag de maximizado. Best-effort. */
    public static void save(Stage stage) {
        if (stage == null) return;
        try {
            boolean max = stage.isMaximized();
            // Posición ACTUAL (getX/getY): identifica la pantalla donde está la
            // ventana, INCLUSO maximizada (apuntan al origen de esa pantalla). Antes
            // usábamos lastX/lastY (última NO-maximizada), que no se actualizan si la
            // ventana se movió al 2º monitor ya maximizada → se reabría en el monitor
            // equivocado y el login/PIN no salía donde se cerró.
            double x = stage.getX();
            double y = stage.getY();
            if (Double.isNaN(x) || Double.isNaN(y)) return;
            // Tamaño "restaurado" (para que al des-maximizar no quede a pantalla
            // completa): el último NO-maximizado si está maximizada; el actual si no.
            double w = !max && stage.getWidth() > 0 ? stage.getWidth()
                    : (lastW > 0 ? lastW : stage.getWidth());
            double h = !max && stage.getHeight() > 0 ? stage.getHeight()
                    : (lastH > 0 ? lastH : stage.getHeight());
            Properties p = new Properties();
            p.setProperty("x", Double.toString(x));
            p.setProperty("y", Double.toString(y));
            p.setProperty("w", Double.toString(w));
            p.setProperty("h", Double.toString(h));
            p.setProperty("maximized", Boolean.toString(stage.isMaximized()));
            Files.createDirectories(FILE.getParent());
            try (OutputStream os = Files.newOutputStream(FILE)) {
                p.store(os, "BENJAGEST UI window geometry");
            }
        } catch (Exception ignored) {
            // best-effort: no pasa nada si no se puede guardar
        }
    }

    /**
     * Coloca la ventana en la posición guardada si cae en una pantalla CONECTADA.
     * Devuelve {@code true} si restauró (el caller NO debe centrar en la primaria);
     * {@code false} si no había dato válido o la pantalla ya no existe.
     */
    public static boolean restore(Stage stage, double defW, double defH) {
        try {
            if (!Files.exists(FILE)) return false;
            Properties p = new Properties();
            try (InputStream is = Files.newInputStream(FILE)) {
                p.load(is);
            }
            double x = parse(p.getProperty("x"), Double.NaN);
            double y = parse(p.getProperty("y"), Double.NaN);
            double w = parse(p.getProperty("w"), defW);
            double h = parse(p.getProperty("h"), defH);
            boolean max = Boolean.parseBoolean(p.getProperty("maximized", "false"));
            if (Double.isNaN(x) || Double.isNaN(y) || w <= 0 || h <= 0) return false;
            // LENIENTE (como VS Code / WhatsApp): solo descartamos si el rectángulo
            // guardado NO toca NINGUNA pantalla conectada (monitor desenchufado).
            // Antes usábamos getScreensForRectangle + clamp a esa pantalla: con
            // escalados distintos JavaFX asignaba el monitor equivocado y el clamp
            // ARRASTRABA la ventana al primario. Ahora aplicamos lo guardado TAL CUAL.
            if (!intersectsAnyScreen(x, y, w, h)) return false;
            stage.setX(x);
            stage.setY(y);
            stage.setWidth(w);
            stage.setHeight(h);
            if (max) stage.setMaximized(true);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static double parse(String s, double def) {
        if (s == null) return def;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** ¿El rectángulo (x,y,w,h) toca alguna pantalla conectada? (bounds directos, sin mapear). */
    private static boolean intersectsAnyScreen(double x, double y, double w, double h) {
        Rectangle2D r = new Rectangle2D(x, y, Math.max(1, w), Math.max(1, h));
        for (Screen s : Screen.getScreens()) {
            if (s.getBounds().intersects(r)) return true;
        }
        return false;
    }
}
