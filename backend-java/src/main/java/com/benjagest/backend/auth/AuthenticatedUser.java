package com.benjagest.backend.auth;

/**
 * Snapshot del usuario logueado en una peticion concreta. Lo construye
 * JwtAuthenticationFilter a partir de los claims del token y lo deposita
 * en el SecurityContext (como Principal de la Authentication).
 *
 * El TenantContext y los endpoints que necesiten saber "quien soy"
 * leen esto del SecurityContext via CurrentUserService.
 */
public record AuthenticatedUser(
        String userId,
        String email,
        String displayName,
        String globalRole,
        String activeCompanyId,
        String roleInActiveCompany
) {
}
