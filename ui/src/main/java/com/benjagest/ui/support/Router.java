package com.benjagest.ui.support;

import javafx.concurrent.Task;
import javafx.scene.Node;

/**
 * Shell de navegacion que reciben las pantallas extraidas (UIR-4). Evita que
 * una pantalla llame directamente a otra (`showX()->showY()`): en su lugar
 * pide al Router que navegue, monte un nodo en el centro o lance una tarea.
 *
 * <p>Lo implementa {@link com.benjagest.ui.BenjagestUiApplication} (el shell);
 * las pantallas de {@code screens/} solo conocen esta interfaz.
 */
public interface Router {

    /** Navega a un modulo por su slug (equivale al antiguo showModule). */
    void navigateTo(String module);

    /** Monta un nodo en el centro del shell, con la animacion estandar. */
    void setCenter(Node node);

    /** Monta un nodo en el centro SIN animacion (para refrescos de polling). */
    void setCenterSilent(Node node);

    /** Lanza una tarea en segundo plano con guarda anti doble-ejecucion por clave. */
    void runTask(Task<?> task, String name);
}
