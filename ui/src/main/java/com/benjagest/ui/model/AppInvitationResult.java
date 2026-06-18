package com.benjagest.ui.model;

/** MEMP-1 — Resultado de generar una invitación a la PWA del empleado. */
public record AppInvitationResult(
        String token,
        String url,
        int expiresInHours
) {}
