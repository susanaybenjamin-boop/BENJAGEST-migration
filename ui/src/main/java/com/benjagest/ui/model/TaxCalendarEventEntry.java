package com.benjagest.ui.model;

/**
 * Vencimiento AEAT del calendario fiscal (CAL-FISCAL).
 * status: PENDING / SUBMITTED / CANCELLED.
 */
public record TaxCalendarEventEntry(
        String id,
        String companyId,
        String modelCode,
        String periodLabel,
        String dueDate,
        String description,
        int fiscalYear,
        String status,
        String submittedAt,
        String notes
) {
    public boolean isPending() { return "PENDING".equals(status); }
}
