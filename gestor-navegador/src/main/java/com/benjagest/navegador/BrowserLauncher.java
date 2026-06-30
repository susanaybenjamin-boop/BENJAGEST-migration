package com.benjagest.navegador;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefApp.CefAppState;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;

/**
 * GESTOR-NAVEGADOR — Navegador embebido (Chromium real vía JCEF) que la app
 * BENJAGEST lanza como PROCESO/ventana aparte (decisión Benjamin: "ventana aparte
 * por cliente"). Abre pestañas a las sedes electrónicas (AEAT, DEHú, Seguridad
 * Social) usando el ALMACÉN DE CERTIFICADOS DEL SISTEMA (en Windows, el certificado
 * FNMT importado), así que el login con certificado funciona con el diálogo nativo
 * de Chromium (Fase 1: el usuario elige el certificado una vez por sesión).
 *
 * <p>Va en módulo SEPARADO y NO modular a propósito: JCEF trae jars con nombres no
 * válidos para módulos automáticos de JPMS, incompatibles con el módulo `ui`.
 *
 * <p>Uso: {@code java -jar gestor-navegador.jar ["--title=..."] ["Etiqueta=URL" ...]}.
 * Sin pestañas explícitas abre las sedes por defecto.
 */
public final class BrowserLauncher {

    /** Pestañas por defecto (sedes electrónicas con login por certificado). */
    private static final Map<String, String> DEFAULT_TABS = new LinkedHashMap<>();
    static {
        DEFAULT_TABS.put("AEAT", "https://sede.agenciatributaria.gob.es");
        DEFAULT_TABS.put("DEHú", "https://dehu.redsara.es/");
        DEFAULT_TABS.put("Import@ss", "https://portal.seg-social.gob.es/");
    }

    private BrowserLauncher() {}

    public static void main(String[] args) {
        String title = "Gestor Navegador — BENJAGEST";
        Map<String, String> tabs = new LinkedHashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--title=")) {
                title = arg.substring("--title=".length());
            } else {
                int eq = arg.indexOf('=');
                if (eq > 0) tabs.put(arg.substring(0, eq), arg.substring(eq + 1));
            }
        }
        if (tabs.isEmpty()) tabs.putAll(DEFAULT_TABS);

        setupLookAndFeel();
        try {
            launch(title, tabs);
        } catch (Exception ex) {
            System.err.println("[gestor-navegador] No se pudo iniciar JCEF: " + ex.getMessage());
            ex.printStackTrace();
            System.exit(2);
        }
    }

    /** Look&Feel plano moderno (FlatLaf) con el azul de acento de BENJAGEST. */
    private static void setupLookAndFeel() {
        try {
            com.formdev.flatlaf.FlatLightLaf.setup();
            java.awt.Color accent = new java.awt.Color(0x25, 0x63, 0xEB); // azul BENJAGEST
            javax.swing.UIManager.put("Component.accentColor", accent);
            javax.swing.UIManager.put("Component.focusColor", accent);
            javax.swing.UIManager.put("TabbedPane.underlineColor", accent);
            javax.swing.UIManager.put("TabbedPane.tabHeight", 38);
        } catch (Exception ignored) {
            // sin FlatLaf seguimos con el L&F por defecto (no rompe el navegador).
        }
    }

    private static void launch(String title, Map<String, String> tabs) throws Exception {
        // 1) Bootstrap de CEF. En el PRIMER arranque descarga e instala el bundle
        //    nativo de Chromium (~150-200 MB) en installDir; luego reutiliza.
        CefAppBuilder builder = new CefAppBuilder();
        builder.getCefSettings().windowless_rendering_enabled = false; // modo ventana (no OSR)
        // Silencia el log interno de Chromium (INFO:CONSOLE de las webs cargadas,
        // browser_info.cc, CSP/preload warnings...). Solo errores reales.
        builder.getCefSettings().log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_ERROR;
        builder.setInstallDir(new File(System.getProperty("user.home"), ".benjagest/jcef-bundle"));
        // Cache persistente (cookies/sesión) + silencia el aviso/singleton del cache por defecto.
        File cacheDir = new File(System.getProperty("user.home"), ".benjagest/jcef-cache");
        cacheDir.mkdirs();
        builder.getCefSettings().cache_path = cacheDir.getAbsolutePath();
        builder.setAppHandler(new MavenCefAppHandlerAdapter() {
            @Override
            public void stateHasChanged(CefAppState state) {
                if (state == CefAppState.TERMINATED) System.exit(0);
            }
        });

        CefApp cefApp = builder.build(); // bloqueante: descarga/instala en el 1er arranque
        CefClient client = cefApp.createClient();

        final JFrame frame = new JFrame(title);
        final JTabbedPane pane = new JTabbedPane();
        // Estilo moderno (FlatLaf): pestañas subrayadas con el acento, no recuadros.
        pane.putClientProperty("JTabbedPane.tabType", "underlined");
        pane.putClientProperty("JTabbedPane.showTabSeparators", Boolean.TRUE);
        pane.putClientProperty("JTabbedPane.tabAreaAlignment", "leading");
        pane.setFont(pane.getFont().deriveFont(13f));

        List<CefBrowser> browsers = new ArrayList<>();
        for (Map.Entry<String, String> e : tabs.entrySet()) {
            CefBrowser browser = client.createBrowser(e.getValue(), false, false);
            browsers.add(browser);
            pane.addTab(e.getKey(), buildTab(browser));
        }

        frame.getContentPane().add(pane, BorderLayout.CENTER);
        frame.setSize(1280, 860);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent ev) {
                try { CefApp.getInstance().dispose(); } catch (Exception ignored) {}
                frame.dispose();
            }
        });
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
    }

    /** Una pestaña = barra (atrás/adelante/recargar + dirección + cerrar) + navegador. */
    private static JPanel buildTab(CefBrowser browser) {
        JPanel panel = new JPanel(new BorderLayout());

        JButton back = navButton("◀", "Atrás", a -> browser.goBack());
        JButton fwd = navButton("▶", "Adelante", a -> browser.goForward());
        JButton reload = navButton("⟳", "Recargar", a -> browser.reload());

        JTextField address = new JTextField(browser.getURL());
        address.addActionListener(a -> browser.loadURL(address.getText().trim()));
        address.putClientProperty("JTextField.placeholderText", "Dirección…");
        address.putClientProperty("JComponent.roundRect", Boolean.TRUE);

        // Layout robusto: botones fijos a la izquierda, la dirección rellena el resto.
        // (Con JToolBar, el campo de ancho fijo empujaba los botones fuera al estrechar.)
        JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        left.add(back);
        left.add(fwd);
        left.add(reload);

        JButton close = navButton("✕", "Cerrar pestaña/ventana", a -> {
            java.awt.Window w = SwingUtilities.getWindowAncestor((Component) a.getSource());
            if (w != null) w.dispose();
        });
        JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        right.add(close);

        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8));
        bar.add(left, BorderLayout.WEST);
        bar.add(address, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);

        panel.add(bar, BorderLayout.NORTH);
        Component ui = browser.getUIComponent();
        panel.add(ui, BorderLayout.CENTER);
        return panel;
    }

    private static JButton navButton(String text, String tip, java.awt.event.ActionListener onClick) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.addActionListener(onClick);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        return b;
    }
}
