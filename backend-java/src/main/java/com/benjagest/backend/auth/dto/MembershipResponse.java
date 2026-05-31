package com.benjagest.backend.auth.dto;

public record MembershipResponse(
        String companyId,
        String companyLegalName,
        String companyTradeName,
        String companyType,
        String roleName
) {
}
