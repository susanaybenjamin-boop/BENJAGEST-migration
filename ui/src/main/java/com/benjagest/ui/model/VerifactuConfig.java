package com.benjagest.ui.model;

public record VerifactuConfig(
        String mode,
        String certificateId,
        String certificateAlias,
        String invoiceFooterTemplate
) {
}
