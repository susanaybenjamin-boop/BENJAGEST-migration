package com.benjagest.ui.support;

/** Accion sobre partes de trabajo que puede lanzar excepcion comprobada. Extraido en UIR-2. */
public interface WorkLogAction {
    void run() throws Exception;
}
