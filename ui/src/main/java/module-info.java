module com.benjagest.ui {
    requires java.net.http;
    // java.desktop expone java.awt.Desktop para abrir el PDF generado
    // con el visor del sistema tras descargarlo (F4b).
    requires java.desktop;
    requires javafx.controls;
    requires javafx.graphics;
    requires org.kordamp.ikonli.fontawesome6;
    requires org.kordamp.ikonli.javafx;
    // PDFBox 3.0 — visor PDF interno para revisión de facturas.
    // Es un Automatic-Module-Name (org.apache.pdfbox), funciona sin
    // module-info propio.
    requires org.apache.pdfbox;

    exports com.benjagest.ui;
}
