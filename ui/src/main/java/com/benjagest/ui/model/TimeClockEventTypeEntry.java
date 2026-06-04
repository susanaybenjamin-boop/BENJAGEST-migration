package com.benjagest.ui.model;

public record TimeClockEventTypeEntry(
        String id,
        String code,
        String labelEs,
        String labelEn,
        String icon,
        int displayOrder,
        boolean isWorkTime,
        boolean isPause,
        boolean active
) {}
