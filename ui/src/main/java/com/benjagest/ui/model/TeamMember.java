package com.benjagest.ui.model;

/**
 * Slice 5C — Miembro activo de la asesoría tal y como llega del
 * endpoint {@code GET /api/advisory/team/assignments/members}. Lo
 * consume el combo "Asignar a" del módulo Equipo y el listado de la
 * pestaña Empleados.
 *
 * <p>{@code roleName} es el rol en company_memberships (OWNER, ADMIN,
 * ACCOUNTANT…), no el rol que después se le asigne en cada cliente.
 */
public record TeamMember(
        String userId,
        String email,
        String displayName,
        String roleName,
        String globalRole,
        boolean active
) {
    /** Texto bonito para el combo: "Nombre · email". */
    public String label() {
        if (displayName == null || displayName.isBlank()) return email;
        if (email == null || email.isBlank()) return displayName;
        return displayName + " · " + email;
    }
}
