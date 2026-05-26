package com.benjagest.ui.model;

public record SessionInfo(
        String employeeId,
        String employeeName,
        String companyId,
        String companyName,
        String role,
        String token,
        String defaultMode
) {
}
