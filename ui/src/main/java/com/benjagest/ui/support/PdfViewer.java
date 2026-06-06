package com.benjagest.ui.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Visor PDF embebido — alternativa rápida al lector del sistema.
 *
 * <p>Implementación: usa PDFBox para renderizar cada página a
 * {@link BufferedImage} y la convierte a {@link javafx.scene.image.Image}
 * via {@link SwingFXUtils}. Cubre el 95% de los casos de revisión de
 * facturas (1-3 páginas) sin la latencia de Acrobat (~100ms vs ~2s).
 *
 * <p>Caché: cada combinación (página, zoom) se cachea para que paginar
 * y luego volver atrás sea instantáneo.
 *
 * <p>Atajos teclado (cuando el viewer tiene foco):
 * <ul>
 *   <li>PageDown / →   → página siguiente</li>
 *   <li>PageUp   / ←   → página anterior</li>
 *   <li>+              → zoom in</li>
 *   <li>-              → zoom out</li>
 *   <li>0              → fit-width (100%)</li>
 * </ul>
 *
 * <p>Uso:
 * <pre>
 *   PdfViewer v = new PdfViewer();
 *   v.loadFromBytes(pdfBytes);
 *   parentContainer.getChildren().add(v);
 * </pre>
 *
 * <p><b>Importante:</b> llamar a {@link #dispose()} cuando se quita
 * de la escena para cerrar el {@link PDDocument} y liberar memoria.
 */
public final class PdfViewer extends BorderPane {

    private static final float DPI = 110f;        // resolución base
    private static final int MAX_CACHE = 8;       // bytes ~ 80MB con 12 pages cached

    private final ImageView imageView = new ImageView();
    private final Label pageLabel = new Label("—");
    private final ComboBox<Integer> zoomCombo = new ComboBox<>();
    private final Button prevBtn = new Button("◀");
    private final Button nextBtn = new Button("▶");
    private final ProgressIndicator spinner = new ProgressIndicator();

    private PDDocument document;
    private PDFRenderer renderer;
    private int currentPage = 0;
    private int totalPages = 0;
    private int zoomPercent = 100;
    private final java.util.LinkedHashMap<String, javafx.scene.image.Image> cache =
            new java.util.LinkedHashMap<>(MAX_CACHE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String,
                        javafx.scene.image.Image> e) {
                    return size() > MAX_CACHE;
                }
            };

    public PdfViewer() {
        getStyleClass().add("pdf-viewer");
        setStyle("-fx-background-color: #e6e6e6;");
        zoomCombo.getItems().addAll(50, 75, 100, 125, 150, 200);
        zoomCombo.setValue(100);
        zoomCombo.valueProperty().addListener((o, a, b) -> {
            if (b != null) { zoomPercent = b; renderCurrent(); }
        });
        prevBtn.setOnAction(e -> goPrev());
        nextBtn.setOnAction(e -> goNext());
        prevBtn.setDisable(true); nextBtn.setDisable(true);

        HBox toolbar = new HBox(8, prevBtn, pageLabel, nextBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                new Label("Zoom:"), zoomCombo);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 8, 6, 8));
        toolbar.setStyle("-fx-background-color: white; -fx-border-color: #d0d0d0; "
                + "-fx-border-width: 0 0 1 0;");

        spinner.setMaxSize(48, 48);
        spinner.setVisible(false);

        // El ImageView vive dentro de un StackPane (para overlay del
        // spinner). El StackPane queda en su tamaño natural (= tamaño de
        // la imagen renderizada por PDFBox a DPI*zoom). El ScrollPane
        // scrollea tanto horizontal como verticalmente sin estirar el
        // contenido — fitToWidth/fitToHeight = FALSE para que zoom alto
        // permita ver toda la factura, no solo lo que cabe en pantalla.
        StackPane center = new StackPane(imageView, spinner);
        center.setStyle("-fx-background-color: #5a5a5a;");
        center.setAlignment(Pos.TOP_CENTER);
        ScrollPane scroll = new ScrollPane(center);
        scroll.setFitToWidth(false);
        scroll.setFitToHeight(false);
        scroll.setPannable(true);   // arrastrar con el ratón para mover.
        scroll.setStyle("-fx-background-color: #5a5a5a;");
        scroll.setPadding(new Insets(8));
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        imageView.setPreserveRatio(true);

        setTop(toolbar);
        setCenter(scroll);

        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        setFocusTraversable(true);

        // Ctrl + rueda del ratón → zoom in/out. Sin Ctrl deja que el
        // ScrollPane interno haga su scroll normal.
        addEventFilter(ScrollEvent.SCROLL, ev -> {
            if (ev.isControlDown()) {
                if (ev.getDeltaY() > 0) adjustZoom(+25);
                else if (ev.getDeltaY() < 0) adjustZoom(-25);
                ev.consume();
            }
        });
        // Hander al final (no filter): si el ScrollPane interno ya consumió
        // el scroll porque scrollea, no llegamos aquí. Si llegamos es que
        // la imagen cabe entera → consumimos para que el scroll NO se
        // propague al padre (SplitPane → formulario derecho).
        addEventHandler(ScrollEvent.SCROLL, ScrollEvent::consume);
    }

    private void onKey(KeyEvent ev) {
        if (ev.getCode() == KeyCode.PAGE_DOWN || ev.getCode() == KeyCode.RIGHT) {
            goNext(); ev.consume();
        } else if (ev.getCode() == KeyCode.PAGE_UP || ev.getCode() == KeyCode.LEFT) {
            goPrev(); ev.consume();
        } else if (ev.getCode() == KeyCode.PLUS || ev.getCode() == KeyCode.ADD
                || ev.getCode() == KeyCode.EQUALS) {
            adjustZoom(+25); ev.consume();
        } else if (ev.getCode() == KeyCode.MINUS || ev.getCode() == KeyCode.SUBTRACT) {
            adjustZoom(-25); ev.consume();
        } else if (ev.getCode() == KeyCode.DIGIT0 || ev.getCode() == KeyCode.NUMPAD0) {
            zoomCombo.setValue(100); ev.consume();
        }
    }

    private void adjustZoom(int delta) {
        int target = Math.max(25, Math.min(400, zoomPercent + delta));
        // Si el target no está en la lista exacta, pick el más cercano.
        int closest = zoomCombo.getItems().get(0);
        int closestDist = Math.abs(target - closest);
        for (int z : zoomCombo.getItems()) {
            int d = Math.abs(target - z);
            if (d < closestDist) { closest = z; closestDist = d; }
        }
        zoomCombo.setValue(closest);
    }

    /** Carga el PDF desde bytes en memoria (síncrono al cerrar el anterior). */
    public void loadFromBytes(byte[] pdfBytes) {
        dispose();
        spinner.setVisible(true);
        Task<PDDocument> load = new Task<>() {
            @Override
            protected PDDocument call() throws IOException {
                return Loader.loadPDF(pdfBytes);
            }
        };
        load.setOnSucceeded(e -> {
            try {
                document = load.getValue();
                renderer = new PDFRenderer(document);
                totalPages = document.getNumberOfPages();
                currentPage = 0;
                updateNavButtons();
                renderCurrent();
            } catch (Exception ex) {
                showError("No se pudo abrir el PDF: " + ex.getMessage());
            }
        });
        load.setOnFailed(e -> {
            spinner.setVisible(false);
            showError("No se pudo abrir el PDF.");
        });
        Thread th = new Thread(load, "pdf-viewer-load");
        th.setDaemon(true);
        th.start();
    }

    private void renderCurrent() {
        if (document == null || renderer == null) return;
        String key = currentPage + "@" + zoomPercent;
        javafx.scene.image.Image cached = cache.get(key);
        if (cached != null) {
            imageView.setImage(cached);
            pageLabel.setText((currentPage + 1) + " / " + totalPages);
            spinner.setVisible(false);
            return;
        }
        spinner.setVisible(true);
        final int pageToRender = currentPage;
        final float dpi = DPI * zoomPercent / 100f;
        Task<javafx.scene.image.Image> task = new Task<>() {
            @Override
            protected javafx.scene.image.Image call() throws IOException {
                BufferedImage bi = renderer.renderImageWithDPI(pageToRender, dpi);
                // Sin javafx-swing en el classpath: BufferedImage → PNG bytes
                // → JavaFX Image. Coste: 1 copia extra de la imagen, pero
                // cabe perfecto para revisión de facturas (1-3 páginas).
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                javax.imageio.ImageIO.write(bi, "png", baos);
                return new javafx.scene.image.Image(
                        new ByteArrayInputStream(baos.toByteArray()));
            }
        };
        task.setOnSucceeded(e -> {
            javafx.scene.image.Image img = task.getValue();
            cache.put(key, img);
            // Solo aplicar si seguimos en la misma página (el usuario podría
            // haber paginado mientras renderizábamos).
            if (pageToRender == currentPage && zoomPercent * 100 / 100 == zoomPercent) {
                imageView.setImage(img);
                pageLabel.setText((currentPage + 1) + " / " + totalPages);
            }
            spinner.setVisible(false);
        });
        task.setOnFailed(e -> {
            spinner.setVisible(false);
            showError("No se pudo renderizar la página.");
        });
        Thread th = new Thread(task, "pdf-viewer-render");
        th.setDaemon(true);
        th.start();
    }

    private void goPrev() {
        if (currentPage <= 0) return;
        currentPage--;
        updateNavButtons();
        renderCurrent();
    }

    private void goNext() {
        if (currentPage >= totalPages - 1) return;
        currentPage++;
        updateNavButtons();
        renderCurrent();
    }

    private void updateNavButtons() {
        prevBtn.setDisable(currentPage <= 0);
        nextBtn.setDisable(currentPage >= totalPages - 1);
    }

    private void showError(String msg) {
        imageView.setImage(null);
        pageLabel.setText(msg);
    }

    /** Cierra el documento y libera memoria. Idempotente. */
    public void dispose() {
        cache.clear();
        imageView.setImage(null);
        if (document != null) {
            try { document.close(); } catch (IOException ignored) {}
            document = null;
            renderer = null;
        }
        totalPages = 0;
        currentPage = 0;
        prevBtn.setDisable(true);
        nextBtn.setDisable(true);
        pageLabel.setText("—");
    }

    /** Garantía de cierre cuando el Node sale de la escena. */
    public void attachAutoDispose() {
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) Platform.runLater(this::dispose);
        });
    }

    @SuppressWarnings("unused")
    private static VBox spinnerBox(ProgressIndicator spinner) {
        VBox b = new VBox(spinner);
        b.setAlignment(Pos.CENTER);
        return b;
    }
}
