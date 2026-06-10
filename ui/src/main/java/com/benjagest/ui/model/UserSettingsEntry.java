package com.benjagest.ui.model;

/**
 * PORT-3 PERFIL — Preferencias personales del usuario logueado.
 */
public record UserSettingsEntry(
        String userId,
        String language,
        int pinTimeoutMin,
        String screensaverStyle,
        boolean aiEnabled,
        String avatarPath,
        String workdayTemplate
) {}
