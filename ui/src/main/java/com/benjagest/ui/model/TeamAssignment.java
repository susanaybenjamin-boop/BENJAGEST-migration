package com.benjagest.ui.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Slice 5C — Asignación de cliente a empleado dentro de una asesoría
 * vista desde la UI. Espejo del record backend ClientAssignment.
 *
 * <p>{@code moduleSlugs} vacía = "asignación abierta" → el empleado
 * tiene acceso a TODOS los módulos activos del cliente.
 */
public record TeamAssignment(
        String id,
        String advisoryCompanyId,
        String employeeUserId,
        String clientCompanyId,
        String roleInClient,
        boolean active,
        String delegatedToUserId,
        LocalDate delegatedFrom,
        LocalDate delegatedUntil,
        String notes,
        /** Módulos asignados; vacía = todos. */
        List<String> moduleSlugs
) {}
