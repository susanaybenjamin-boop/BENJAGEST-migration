package com.benjagest.ui.model;

/**
 * Resumen agregado de un hilo de mensajes asesoría↔cliente.
 * Usado para badge no-leídos + listado de conversaciones.
 */
public record AdvisoryThreadSummary(
        String otherCompanyId,
        String lastAt,
        int unreadCount,
        int totalCount
) {}
