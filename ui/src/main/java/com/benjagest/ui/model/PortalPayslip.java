package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * PORT-1 — Nómina del empleado vista desde el Portal del empleado.
 * Read-only. Si {@code pdfPath} no está en blanco, el UI ofrece descarga
 * vía PdfViewer / Desktop.
 */
public record PortalPayslip(
        String id,
        int year,
        int month,
        BigDecimal grossAmount,
        BigDecimal netAmount,
        String status,
        String pdfPath
) {}
