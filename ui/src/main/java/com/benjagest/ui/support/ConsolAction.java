package com.benjagest.ui.support;

/** Accion de consolidacion que puede lanzar excepcion comprobada. Extraido en UIR-2. */
public interface ConsolAction {
    void run() throws Exception;
}
