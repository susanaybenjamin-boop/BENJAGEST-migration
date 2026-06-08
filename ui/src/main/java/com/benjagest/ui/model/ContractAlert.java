package com.benjagest.ui.model;

/**
 * CTR-6 — Alerta de vencimiento de contrato (UI).
 *
 * <p>firesAt y employeeId son strings — el UI no necesita LocalDate aquí,
 * el formato del backend es ISO y se pinta tal cual.
 */
public record ContractAlert(
        String id,
        String kind,
        String title,
        String message,
        String severity,
        String firesAt,
        boolean seen,
        String contractId,
        String employeeId,
        String employeeName
) {}
