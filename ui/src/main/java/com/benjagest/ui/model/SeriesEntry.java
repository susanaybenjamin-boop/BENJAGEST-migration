package com.benjagest.ui.model;

public record SeriesEntry(
        String id,
        String code,
        String invoiceKind,
        String numberingType,
        String formatTemplate,
        int nextNumber,
        Integer currentYear,
        boolean locked,
        boolean active
) {
}
