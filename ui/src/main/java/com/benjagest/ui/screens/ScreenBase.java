package com.benjagest.ui.screens;

import com.benjagest.ui.support.BackendErrors;
import com.benjagest.ui.support.Dialogs;
import com.benjagest.ui.support.Icons;
import com.benjagest.ui.support.Formatters;
import com.benjagest.ui.support.Router;
import com.benjagest.ui.support.UiBuilders;
import java.math.BigDecimal;
import java.util.function.Function;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

/**
 * Base de las pantallas extraidas del God Object (Fase 3 del bloque UIR).
 * Reune los delegados a los helpers compartidos (i18n, iconos, formato,
 * dialogos, navegacion/async via {@link Router}) para que el cuerpo de cada
 * pantalla movido del monolito compile sin cambios.
 */
public abstract class ScreenBase {

    protected final Function<String, String> tt;
    protected final Router router;

    protected ScreenBase(Function<String, String> tt, Router router) {
        this.tt = tt;
        this.router = router;
    }

    protected String t(String key) { return tt.apply(key); }
    protected Node icon(String literal) { return Icons.icon(literal); }
    protected String money(BigDecimal value) { return Formatters.money(value); }
    protected String money(String value) { return Formatters.money(value); }
    protected String displayValue(String value) { return Formatters.displayValue(value); }
    protected Label label(String text, String styleClass) { return UiBuilders.label(text, styleClass); }
    protected ScrollPane scroll(VBox content) { return UiBuilders.scroll(content); }
    protected void showError(String title, String message) { Dialogs.error(title, BackendErrors.humanize(message)); }
    protected void showInfo(String title, String body) { Dialogs.info(title, body); }
    protected void toast(String message) { Dialogs.toast(message); }
    protected void toast(javafx.stage.Window owner, String message) { Dialogs.toast(owner, message); }
    protected void start(Task<?> task, String name) { router.runTask(task, name); }
    protected void setCenterAnimated(Node node) { router.setCenter(node); }
}
