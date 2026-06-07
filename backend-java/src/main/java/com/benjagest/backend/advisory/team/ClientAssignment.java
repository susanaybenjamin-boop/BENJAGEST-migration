package com.benjagest.backend.advisory.team;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Slice 5A — Asignación de cliente a empleado dentro de una asesoría.
 *
 * <p>El OWNER de la asesoría reparte sus clientes entre los miembros
 * del equipo. Cada empleado solo ve los clientes asignados a él en
 * "Mis clientes". Cuando un empleado se ausenta, el OWNER puede
 * delegar temporalmente sus asignaciones a otro miembro usando los
 * tres campos {@code delegated_*}.
 *
 * <p>El {@link com.benjagest.backend.audit.AuditService} graba el
 * user_id del actor real en cada evento — combinado con este modelo,
 * la traza fiscal queda firmada por la persona concreta que ejecutó
 * la acción, no por una identidad genérica de la asesoría.
 */
public record ClientAssignment(
        String id,
        String advisoryCompanyId,
        String employeeUserId,
        String clientCompanyId,
        String roleInClient,
        Instant assignedAt,
        String assignedByUserId,
        boolean active,
        String delegatedToUserId,
        LocalDate delegatedFrom,
        LocalDate delegatedUntil,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String ROLE_ADVISOR = "ADVISOR";
    public static final String ROLE_ACCOUNTANT = "ACCOUNTANT";
    public static final String ROLE_EMPLOYEE = "EMPLOYEE";
    public static final String ROLE_VIEWER = "VIEWER";
}
