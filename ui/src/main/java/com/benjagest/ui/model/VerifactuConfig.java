package com.benjagest.ui.model;

/**
 * Configuración VeriFactu vista desde la UI. Tras VF-OFF-DEPRECATE (V17)
 * dos conceptos separados:
 *   - modality: VERIFACTU o NO_VERIFACTU (legal, RD 1007/2023).
 *   - mode: TEST o PROD (entorno del cliente AEAT, solo si modality es
 *           VERIFACTU; se guarda igualmente por si se cambia de modalidad).
 */
public record VerifactuConfig(
        String modality,
        String mode,
        String certificateId,
        String certificateAlias,
        String invoiceFooterTemplate,
        String invoiceStorageRoot
) {
}
