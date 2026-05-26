package com.benjagest.backend.workspace;

import java.util.List;

public record PinLoginResponse(
        String employeeId,
        String employeeName,
        String companyId,
        String companyName,
        String role,
        String token,
        String defaultMode,
        List<String> availableModes
) {
}
