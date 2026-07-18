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

    /** DIAG MULTIMON — describe todas las pantallas (bounds + escala) para el log. */
    public static String screensDebug() {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Screen s : Screen.getScreens()) {
            Rectangle2D b = s.getBounds();
            Rectangle2D v = s.getVisualBounds();
            sb.append(String.format(java.util.Locale.ROOT,
                    " [scr%d bounds=(%.0f,%.0f %.0fx%.0f) visual=(%.0f,%.0f %.0fx%.0f) scale=%.2f]",
                    i++, b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight(),
                    v.getMinX(), v.getMinY(), v.getWidth(), v.getHeight(), s.getOutputScaleX()));
        }
        return sb.toString();
    }

    /** DIAG MULTIMON — describe una ventana (posición/tamaño) y la pantalla que le asigna JavaFX. */
    public static String windowDebug(String tag, javafx.stage.Window w) {
        if (w == null) return "[" + tag + " win=null]";
        double cx = w.getX() + Math.max(1, w.getWidth()) / 2;
        double cy = w.getY() + Math.max(1, w.getHeight()) / 2;
        var scr = Screen.getScreensForRectangle(cx, cy, 1, 1);
        String scrIdx = scr.isEmpty() ? "NONE"
                : String.format(java.util.Locale.ROOT, "(%.0f,%.0f %.0fx%.0f)",
                        scr.get(0).getBounds().getMinX(), scr.get(0).getBounds().getMinY(),
                        scr.get(0).getBounds().getWidth(), scr.get(0).getBounds().getHeight());
        return String.format(java.util.Locale.ROOT,
                "[%s win=(%.0f,%.0f %.0fx%.0f) center=(%.0f,%.0f) -> screen=%s]",
                tag, w.getX(), w.getY(), w.getWidth(), w.getHeight(), cx, cy, scrIdx);
    }

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
            System.out.println("[MULTIMON-SAVE] x=" + x + " y=" + y + " w=" + w + " h=" + h
                    + " max=" + max + " |" + screensDebug());
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
            if (Double.isNaN(x) || Double.isNaN(y)) return false;
            var screens = Screen.getScreensForRectangle(x, y, Math.max(1, w), Math.max(1, h));
            System.out.println("[MULTIMON-RESTORE] saved x=" + x + " y=" + y + " w=" + w + " h=" + h
                    + " max=" + max + " -> " + (screens.isEmpty() ? "RECHAZADO (ninguna pantalla) -> primaria" : "OK")
                    + " |" + screensDebug());
            if (screens.isEmpty()) return false; // esa pantalla ya no está conectada
            Rectangle2D vb = screens.get(0).getVisualBounds();
            double fw = Math.min(w, vb.getWidth());
            double fh = Math.min(h, vb.getHeight());
            stage.setWidth(fw);
            stage.setHeight(fh);
            stage.setX(clamp(x, vb.getMinX(), vb.getMaxX() - fw));
            stage.setY(clamp(y, vb.getMinY(), vb.getMaxY() - fh));
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

    private static double clamp(double v, double min, double max) {
        double hi = Math.max(min, max);
        return Math.max(min, Math.min(v, hi));
    }
}
