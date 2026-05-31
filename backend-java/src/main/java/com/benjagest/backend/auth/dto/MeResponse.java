package com.benjagest.backend.auth.dto;

import java.util.List;

public record MeResponse(
        String userId,
        String email,
        String displayName,
        String globalRole,
        String activeCompanyId,
        String roleInActiveCompany,
        List<MembershipResponse> memberships
) {
}
