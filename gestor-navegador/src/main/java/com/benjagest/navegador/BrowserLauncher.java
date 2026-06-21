package com.benjagest.navegador;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
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
import javax.swing.JToolBar;
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
        DEFAULT_TABS.put("Seg. Social (RED)", "https://www.seg-social.es/wps/portal/wss/internet/Inicio");
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
        builder.setInstallDir(new File(System.getProperty("user.home"), ".benjagest/jcef-bundle"));
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

    /** Una pestaña = barra (atrás/adelante/recargar + dirección) + navegador. */
    private static JPanel buildTab(CefBrowser browser) {
        JPanel panel = new JPanel(new BorderLayout());

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JButton back = new JButton("◀");
        back.setToolTipText("Atrás");
        back.addActionListener(a -> browser.goBack());
        JButton fwd = new JButton("▶");
        fwd.setToolTipText("Adelante");
        fwd.addActionListener(a -> browser.goForward());
        JButton reload = new JButton("⟳");
        reload.setToolTipText("Recargar");
        reload.addActionListener(a -> browser.reload());
        JTextField address = new JTextField(browser.getURL());
        address.addActionListener(a -> browser.loadURL(address.getText().trim()));
        address.setPreferredSize(new Dimension(600, 30));
        // Estética FlatLaf: botones de barra sin borde + campo de dirección redondeado.
        for (JButton b : new JButton[]{back, fwd, reload}) {
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setFocusable(false);
        }
        address.putClientProperty("JTextField.placeholderText", "Dirección…");
        address.putClientProperty("JComponent.roundRect", Boolean.TRUE);

        bar.add(back);
        bar.add(fwd);
        bar.add(reload);
        bar.addSeparator();
        bar.add(address);

        panel.add(bar, BorderLayout.NORTH);
        Component ui = browser.getUIComponent();
        panel.add(ui, BorderLayout.CENTER);
        return panel;
    }
}
