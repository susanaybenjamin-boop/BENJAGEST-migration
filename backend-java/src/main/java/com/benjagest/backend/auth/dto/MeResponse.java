package com.benjagest.backend.auth.dto;

import java.util.List;

public record MeResponse(
        String userId,
        String email,
        String displayName,
        String globalRole,
        String activeCompanyId,
        String roleInActiveCompany,
        List<MembershipResponse> memberships,
        // PIN-PAIR-SESSION — con que puede entrar la cuenta. Solo el SI/NO:
        // Configuracion -> Sesion lo muestra, y explica por que una cuenta de
        // Google no puede usar el emparejado que pide contrasena.
        boolean hasPassword,
        boolean googleLinked
) {
}
