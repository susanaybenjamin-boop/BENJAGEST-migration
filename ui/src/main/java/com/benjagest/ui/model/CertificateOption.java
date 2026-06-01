package com.benjagest.ui.model;

/**
 * Opcion para el ComboBox del selector de certificado en la
 * configuracion VeriFactu. La UI tiene su propio toString controlado
 * para no exponer ids al usuario.
 */
public record CertificateOption(String id, String alias, String certificateType) {
    @Override
    public String toString() {
        if (alias == null || alias.isBlank()) {
            return id == null ? "(sin alias)" : id.substring(0, Math.min(8, id.length()));
        }
        return alias + (certificateType == null || certificateType.isBlank() ? "" : " (" + certificateType + ")");
    }
}
