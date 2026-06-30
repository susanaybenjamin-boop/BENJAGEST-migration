package com.benjagest.ui.support;

/** Runnable que puede lanzar excepcion comprobada. Extraido en UIR-2. */
public interface ThrowingRunnable {
    void run() throws Exception;
}
