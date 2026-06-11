package com.benjagest.ui.model;

/**
 * Acuerdo previo de facturación por tercero (RD 1619/2012 art. 5).
 *
 * <p>{@code status}: PROPOSED → ACTIVE → REVOKED.
 * {@code signedMethod}: PIN_SESSION (cliente vinculado) o
 * OFFLINE_PDF (asesoría sube PDF firmado físico).
 */
public record TpbAgreementEntry(
        String id,
        String advisoryCompanyId,
        String clientCompanyId,
        boolean scopeSales,
        boolean scopePurchases,
        boolean scopeTaxModels,
        String status,
        boolean initiatedByAdvisory,
        String signedAt,
        String signedMethod,
        String signedPdfPath,
        String revokedAt,
        Boolean revokedByAdvisory,
        String revokedReason,
        String createdAt
) {
    public static final String STATUS_PROPOSED = "PROPOSED";
    public static final String STATUS_ACTIVE   = "ACTIVE";
    public static final String STATUS_REVOKED  = "REVOKED";

    public static final String METHOD_PIN_SESSION = "PIN_SESSION";
    public static final String METHOD_OFFLINE_PDF = "OFFLINE_PDF";

    public boolean isPending() {
        return STATUS_PROPOSED.equals(status);
    }
    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}
