module com.benjagest.ui {
    requires java.net.http;
    // java.desktop expone java.awt.Desktop para abrir el PDF generado
    // con el visor del sistema tras descargarlo (F4b).
    requires java.desktop;
    requires javafx.controls;
    requires javafx.graphics;
    requires org.kordamp.ikonli.fontawesome6;
    requires org.kordamp.ikonli.javafx;

    exports com.benjagest.ui;
}
