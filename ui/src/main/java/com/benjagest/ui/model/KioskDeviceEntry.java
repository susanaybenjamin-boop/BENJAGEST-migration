package com.benjagest.ui.model;

/** FM — Dispositivo de kiosco/PDA de fichaje (admin). */
public record KioskDeviceEntry(
        String id,
        String name,
        String workCenterId,
        boolean requirePhoto,
        int photoRetentionDays,
        boolean active,
        boolean activated
) {}
