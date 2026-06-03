package com.benjagest.backend.billing.verifactu;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Payload de PUT /api/billing/verifactu-config.
 *
 *   - modality (legal): VERIFACTU | NO_VERIFACTU (obligatorio).
 *   - mode (technical): TEST | PROD (obligatorio incluso en NO_VERIFACTU
 *     — se guarda por si la empresa cambia de modalidad, pero no se usa
 *     mientras este en NO_VERIFACTU).
 *   - certificateId: opcional. Solo se exige cuando modality=VERIFACTU
 *     y mode=PROD (sin certificado real AEAT rechaza los envios).
 *
 * Validacion adicional en Service: modality=VERIFACTU + mode=PROD sin
 * certificateId -> 400.
 */
public record VerifactuConfigUpdateRequest(
        @NotBlank @Pattern(regexp = "VERIFACTU|NO_VERIFACTU") String modality,
        @NotBlank @Pattern(regexp = "TEST|PROD") String mode,
        String certificateId,
        String invoiceFooterTemplate
) {
}
