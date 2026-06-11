package com.benjagest.backend.billing.tpb;

import java.time.Instant;

/**
 * Acuerdo previo de facturación por tercero — RD 1619/2012 art. 5.
 *
 * <p>Una asesoría puede emitir facturas materialmente en nombre del
 * cliente solo si existe un acuerdo previo firmado que especifique
 * a qué operaciones se refiere. Estados:
 * <ul>
 *   <li>{@code PROPOSED} — propuesto por una de las partes, pendiente
 *       de firma del cliente.</li>
 *   <li>{@code ACTIVE} — firmado y vigente. La asesoría puede emitir
 *       por tercero según el alcance.</li>
 *   <li>{@code REVOKED} — cancelado por alguna parte. Sin efecto
 *       hacia adelante; las facturas ya emitidas no se tocan.</li>
 * </ul>
 *
 * <p>{@code signedMethod}: {@code PIN_SESSION} (cliente vinculado firma
 * desde su UI con PIN) o {@code OFFLINE_PDF} (cliente sin acceso a
 * BENJAGEST firma físicamente y la asesoría sube el PDF escaneado).
 */
public record ThirdPartyBillingAgreement(
        String id,
        String advisoryCompanyId,
        String clientCompanyId,
        boolean scopeSales,
        boolean scopePurchases,
        boolean scopeTaxModels,
        String status,
        boolean initiatedByAdvisory,
        Instant signedAt,
        String signedMethod,
        String signedPdfPath,
        Instant revokedAt,
        Boolean revokedByAdvisory,
        String revokedReason,
        String createdByUserId,
        Instant createdAt
) {
    public static final String STATUS_PROPOSED = "PROPOSED";
    public static final String STATUS_ACTIVE   = "ACTIVE";
    public static final String STATUS_REVOKED  = "REVOKED";

    public static final String METHOD_PIN_SESSION = "PIN_SESSION";
    public static final String METHOD_OFFLINE_PDF = "OFFLINE_PDF";

    public boolean covers(String operationType) {
        return switch (operationType == null ? "" : operationType.toUpperCase()) {
            case "SALES", "SALES_INVOICE" -> scopeSales;
            case "PURCHASES", "PURCHASE"  -> scopePurchases;
            case "TAX_MODELS", "TAX"      -> scopeTaxModels;
            default -> false;
        };
    }
}
