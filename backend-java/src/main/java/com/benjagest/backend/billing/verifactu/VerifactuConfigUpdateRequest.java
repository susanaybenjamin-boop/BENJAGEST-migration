package com.benjagest.backend.billing.verifactu;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Payload de PUT /api/billing/verifactu-config.
 *
 * mode obligatorio. certificateId opcional (puede llegar vacio o null
 * si se quiere desvincular el certificado del modo OFF).
 *
 * Validacion adicional en Service: si mode=PROD, certificateId
 * obligatorio (sin certificado no se firma, sin firma AEAT rechaza).
 */
public record VerifactuConfigUpdateRequest(
        @NotBlank @Pattern(regexp = "OFF|TEST|PROD") String mode,
        String certificateId,
        String invoiceFooterTemplate
) {
}
