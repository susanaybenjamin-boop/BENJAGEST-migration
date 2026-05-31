package com.benjagest.backend.auth.dto;

import java.util.List;

/**
 * Lo que devuelve POST /api/auth/login. Incluye los dos tokens y un
 * snapshot del usuario para que la UI pueda pintarse sin tener que
 * llamar a /me inmediatamente despues.
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        long accessExpiresInSeconds,
        String userId,
        String email,
        String displayName,
        String globalRole,
        String activeCompanyId,
        String roleInActiveCompany,
        List<MembershipResponse> memberships
) {
}
