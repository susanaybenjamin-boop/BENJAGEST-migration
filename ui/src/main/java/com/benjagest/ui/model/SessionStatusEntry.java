package com.benjagest.ui.model;

/**
 * PORT-4 SESSION — Estado de la configuración de sesión del usuario.
 */
public record SessionStatusEntry(
        int pinTimeoutMin,
        String screensaverStyle,
        boolean pinConfigured
) {}
