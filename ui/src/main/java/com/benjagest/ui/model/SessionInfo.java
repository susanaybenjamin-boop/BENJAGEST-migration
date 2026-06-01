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
    /**
     * Devuelve una copia con el companyName actualizado. Lo usa el
     * refresh silencioso tras guardar la pestana Empresa en Configuracion,
     * sin tener que hacer logout/login.
     */
    public SessionInfo withCompanyName(String newCompanyName) {
        return new SessionInfo(
                employeeId,
                employeeName,
                companyId,
                newCompanyName,
                role,
                token,
                defaultMode
        );
    }
}
